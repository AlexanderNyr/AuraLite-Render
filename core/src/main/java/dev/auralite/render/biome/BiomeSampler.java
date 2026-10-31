package dev.auralite.render.biome;

/**
 * Turns a world position into a {@link BiomeSample}.
 *
 * <p>Trilinear interpolation over the eight cells surrounding the position,
 * with cell centres at {@code cellIndex + 0.5}. Duplicate ids are merged, the
 * contributors are sorted by weight and the top {@link BiomeSample#MAX_ENTRIES}
 * are kept and renormalised, so the weights always sum to 1 and the dominant
 * biome is always entry 0.
 *
 * <p>Cells that were never ingested contribute nothing; when all eight are
 * unknown the sample is empty and callers fall back to the registry default
 * rather than inventing a biome.
 *
 * <p><b>Seam width is exactly one biome cell (4 blocks) and that is a
 * structural fact, not a tunable.</b> Trilinear interpolation consults the two
 * cells either side of the position on each axis, so a position more than one
 * cell from a border cannot see it. An earlier revision carried a
 * {@code blendRadius} knob that appeared to widen the seam; measurement showed
 * it only reweighted the same eight corners and never reached a further cell,
 * so it was removed rather than left as a control that lies. Widening a seam is
 * done where it is cheap -- a compute-pass blur over the baked property volume
 * (BIOME_PLAN B5) -- not by widening a per-fragment CPU kernel.
 */
public final class BiomeSampler {
    private final BiomeField field;
    private final BiomeRegistry registry;

    // Scratch reused across calls so sampling is allocation-free at fragment rate.
    private final int[] cornerIds = new int[8];
    private final float[] cornerWeights = new float[8];
    private final int[] mergedIds = new int[8];
    private final float[] mergedWeights = new float[8];

    public BiomeSampler(BiomeField field, BiomeRegistry registry) {
        this.field = field;
        this.registry = registry;
    }

    public BiomeField field() { return field; }
    public BiomeRegistry registry() { return registry; }

    public BiomeSample sample(double wx, double wy, double wz) {
        BiomeSample out = new BiomeSample(registry, (float) wx, (float) wy, (float) wz);

        double cellSize = field.cellSize();
        // Cell-centre space: the centre of cell c sits at (c + 0.5) * cellSize.
        double fx = wx / cellSize - 0.5;
        double fy = wy / cellSize - 0.5;
        double fz = wz / cellSize - 0.5;

        int bx = (int) Math.floor(fx), by = (int) Math.floor(fy), bz = (int) Math.floor(fz);
        float tx = clamp01((float) (fx - bx));
        float ty = clamp01((float) (fy - by));
        float tz = clamp01((float) (fz - bz));

        float w000 = (1 - tx) * (1 - ty) * (1 - tz);
        float w100 = tx * (1 - ty) * (1 - tz);
        float w010 = (1 - tx) * ty * (1 - tz);
        float w110 = tx * ty * (1 - tz);
        float w001 = (1 - tx) * (1 - ty) * tz;
        float w101 = tx * (1 - ty) * tz;
        float w011 = (1 - tx) * ty * tz;
        float w111 = tx * ty * tz;

        cornerIds[0] = field.idAtCell(bx, by, bz);         cornerWeights[0] = w000;
        cornerIds[1] = field.idAtCell(bx + 1, by, bz);     cornerWeights[1] = w100;
        cornerIds[2] = field.idAtCell(bx, by + 1, bz);     cornerWeights[2] = w010;
        cornerIds[3] = field.idAtCell(bx + 1, by + 1, bz); cornerWeights[3] = w110;
        cornerIds[4] = field.idAtCell(bx, by, bz + 1);     cornerWeights[4] = w001;
        cornerIds[5] = field.idAtCell(bx + 1, by, bz + 1); cornerWeights[5] = w101;
        cornerIds[6] = field.idAtCell(bx, by + 1, bz + 1); cornerWeights[6] = w011;
        cornerIds[7] = field.idAtCell(bx + 1, by + 1, bz + 1); cornerWeights[7] = w111;

        int merged = 0;
        float total = 0f;
        for (int i = 0; i < 8; i++) {
            int id = cornerIds[i];
            float w = cornerWeights[i];
            if (id == BiomeField.UNKNOWN || w <= 0f) continue;
            int at = -1;
            for (int j = 0; j < merged; j++) if (mergedIds[j] == id) { at = j; break; }
            if (at >= 0) mergedWeights[at] += w;
            else { mergedIds[merged] = id; mergedWeights[merged] = w; merged++; }
            total += w;
        }
        if (merged == 0 || total <= 0f) return out;

        // Sort by weight, descending. Insertion sort: n <= 8.
        for (int i = 1; i < merged; i++) {
            int id = mergedIds[i];
            float w = mergedWeights[i];
            int j = i - 1;
            while (j >= 0 && mergedWeights[j] < w) {
                mergedIds[j + 1] = mergedIds[j];
                mergedWeights[j + 1] = mergedWeights[j];
                j--;
            }
            mergedIds[j + 1] = id;
            mergedWeights[j + 1] = w;
        }

        out.count = Math.min(merged, BiomeSample.MAX_ENTRIES);
        float kept = 0f;
        for (int i = 0; i < out.count; i++) kept += mergedWeights[i];
        // Renormalise over what we keep, so the weights the shader sees sum to 1
        // even when a fifth biome was dropped.
        for (int i = 0; i < out.count; i++) {
            out.ids[i] = mergedIds[i];
            out.weights[i] = kept > 0f ? mergedWeights[i] / kept : 0f;
        }
        return out;
    }

    /** Convenience for the ingest path: sample at a cell centre. */
    public BiomeSample sampleCellCentre(int cx, int cy, int cz) {
        double s = field.cellSize();
        return sample((cx + 0.5) * s, (cy + 0.5) * s, (cz + 0.5) * s);
    }

    private static float clamp01(float f) { return f < 0f ? 0f : (f > 1f ? 1f : f); }
}
