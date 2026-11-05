package dev.auralite.render.biome;

/**
 * The biome seam as a blend of a {@link BiomeField} neighbourhood -- the CPU
 * reference the GPU blur must reproduce (BIOME_PLAN B5, gate 1).
 *
 * <h2>Why this class exists</h2>
 *
 * <p>{@link BiomeSampler} answers "what biome is at this position" with a
 * trilinear blend of the eight cells around it, which sees exactly one cell
 * either side -- a 4-block seam. That width is structural: no weight
 * assignment can reach a cell the kernel does not read (see the sampler's
 * class comment).
 *
 * <p>The planned widening blurs the <em>baked property volume</em> in a
 * compute pass and lets fragments fetch one texel instead of eight. A GPU
 * rule that quietly disagrees with the CPU rule would be worse than the
 * narrow seam it replaces -- so the blur's arithmetic is defined <em>here</em>,
 * in plain Java, and the compute shader is required to match it: at blend
 * radius 0 this class reproduces {@link BiomeSampler#sample} exactly (same
 * ids, same weights), and the same merge-and-renormalise rule carries to
 * wider radii.
 *
 * <h2>The rule, stated once</h2>
 *
 * <ol>
 *   <li>For cell {@code (cx, cy, cz)} and radius {@code R}, read every cell in
 *       the box {@code [c-R, c+R]} on each axis -- {@code (2R+1)^3} cells.</li>
 *   <li>Each readable (non-{@link BiomeField#UNKNOWN}) cell carries a weight.
 *       The weight is the trilinear corner weight {@code BiomeSampler} would
 *       give it for a position at the centre of the output cell, with the
 *       kernel extended by {@code R}: for each axis the offset
 *       {@code d} in {@code [-R, R]} scores {@code max(0, R + 1 - |d|)}.</li>
 *   <li>Duplicate ids merge (weights add), the merged list sorts by weight
 *       descending -- ties broken by <b>ascending id</b>, so the order is
 *       deterministic and the shader can match it -- the top
 *       {@link BiomeSample#MAX_ENTRIES} are kept and renormalised over what
 *       was kept.</li>
 *   <li>All-unknown neighbourhood -> an empty sample: callers fall back to the
 *       registry default rather than inventing a biome.</li>
 * </ol>
 *
 * <p>At {@code R = 0} step 2 collapses to the single centre cell with weight
 * 1, so the output is that cell's id alone. That is deliberately <em>not</em>
 * {@code BiomeSampler.sample()} at the centre position -- the sampler at a cell
 * centre also returns one id, and this is why gate 1 compares the two there;
 * sampling anywhere else is a different question (a position, not a cell), and
 * the widened seam answers it by interpolating between the blurred cell
 * centres exactly as the sampler interpolates between field cells.
 *
 * <p>Instances keep their scratch arrays: a bake walks every cell of a window
 * and must not allocate per cell.
 */
public final class BiomeSeam {

    /** Hard ceiling the pack format and packlint enforce (BIOME_PLAN). */
    public static final int MAX_RADIUS = 4;

    private final BiomeField field;
    private final BiomeRegistry registry;

    // Scratch, reused across cells.
    private final int[] mergedIds;
    private final float[] mergedWeights;

    public BiomeSeam(BiomeField field, BiomeRegistry registry) {
        this.field = field;
        this.registry = registry;
        int cells = (2 * MAX_RADIUS + 1);
        mergedIds = new int[cells * cells * cells];
        mergedWeights = new float[mergedIds.length];
    }

    public BiomeField field() { return field; }
    public BiomeRegistry registry() { return registry; }

    /**
     * The widened sample for the cell containing the given world position.
     *
     * @param radius blend radius in cells, {@code 0 <= radius <= MAX_RADIUS};
     *               out-of-range values are refused by name, never clamped
     */
    public BiomeSample sample(double wx, double wy, double wz, int radius) {
        checkRadius(radius);
        return sampleCell(field.worldToCellX(wx), field.worldToCellY(wy),
                field.worldToCellZ(wz), radius, wx, wy, wz);
    }

    /** The widened sample for a cell, positioned at its centre. */
    public BiomeSample sampleCell(int cx, int cy, int cz, int radius) {
        checkRadius(radius);
        double s = field.cellSize();
        return sampleCell(cx, cy, cz, radius,
                (cx + 0.5) * s, (cy + 0.5) * s, (cz + 0.5) * s);
    }

    /**
     * Refuses a radius past {@link #MAX_RADIUS} by name -- the plan's
     * {@code BIOME_SEAM_R_TOO_WIDE}. Silent clamping would let a pack believe
     * it shipped one seam and get another.
     */
    public static void checkRadius(int radius) {
        if (radius < 0 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("BIOME_SEAM_R_TOO_WIDE: blend radius " + radius
                    + " is outside 0.." + MAX_RADIUS
                    + " (the pass declares its maximum; nothing is clamped)");
        }
    }

    /** How many distinct ids a bake at this radius can merge (budget check). */
    public static int boxCells(int radius) {
        checkRadius(radius);
        int side = 2 * radius + 1;
        return side * side * side;
    }

    private BiomeSample sampleCell(int cx, int cy, int cz, int radius,
                                   double sampleX, double sampleY, double sampleZ) {
        BiomeSample out = new BiomeSample(registry,
                (float) sampleX, (float) sampleY, (float) sampleZ);

        int merged = 0;
        float total = 0f;
        for (int dz = -radius; dz <= radius; dz++) {
            float wz = axisWeight(dz, radius);
            if (wz <= 0f) continue;
            for (int dy = -radius; dy <= radius; dy++) {
                float wy = axisWeight(dy, radius);
                if (wy <= 0f) continue;
                for (int dx = -radius; dx <= radius; dx++) {
                    float wx = axisWeight(dx, radius);
                    if (wx <= 0f) continue;
                    int id = field.idAtCell(cx + dx, cy + dy, cz + dz);
                    if (id == BiomeField.UNKNOWN) continue;
                    float w = wx * wy * wz;
                    if (w <= 0f) continue;
                    int at = -1;
                    for (int j = 0; j < merged; j++) {
                        if (mergedIds[j] == id) { at = j; break; }
                    }
                    if (at >= 0) {
                        mergedWeights[at] += w;
                    } else {
                        mergedIds[merged] = id;
                        mergedWeights[merged] = w;
                        merged++;
                    }
                    total += w;
                }
            }
        }
        if (merged == 0 || total <= 0f) return out;

        sortDescendingById(merged);

        out.count = Math.min(merged, BiomeSample.MAX_ENTRIES);
        float kept = 0f;
        for (int i = 0; i < out.count; i++) kept += mergedWeights[i];
        for (int i = 0; i < out.count; i++) {
            out.ids[i] = mergedIds[i];
            out.weights[i] = kept > 0f ? mergedWeights[i] / kept : 0f;
        }
        return out;
    }

    /**
     * One axis of the tent kernel: offset {@code |d| <= R} scores
     * {@code R + 1 - |d|}, which is the trilinear corner weight extended by
     * {@code R} cells on each side. At {@code R = 0} the only non-zero offset
     * is {@code d = 0}, weight 1 -- the sampler's own degenerate case.
     */
    static float axisWeight(int d, int radius) {
        int a = Math.abs(d);
        int v = radius + 1 - a;
        return v <= 0 ? 0f : (float) v;
    }

    /** Descending by weight, ties by ascending id: deterministic for the shader. */
    private void sortDescendingById(int n) {
        for (int i = 1; i < n; i++) {
            int id = mergedIds[i];
            float w = mergedWeights[i];
            int j = i - 1;
            while (j >= 0 && (mergedWeights[j] < w
                    || (mergedWeights[j] == w && mergedIds[j] > id))) {
                mergedIds[j + 1] = mergedIds[j];
                mergedWeights[j + 1] = mergedWeights[j];
                j--;
            }
            mergedIds[j + 1] = id;
            mergedWeights[j + 1] = w;
        }
    }

    /**
     * How wide the blend band is, in blocks, at this radius: the seam spans the
     * blurred cells either side of a border, so {@code 2 * R * cellSize} plus
     * the one-cell base the sampler already had.
     */
    public static double seamWidthBlocks(int radius, int cellSize) {
        checkRadius(radius);
        return (2.0 * radius + 1) * cellSize;
    }
}
