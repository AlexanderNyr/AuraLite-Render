package dev.auralite.render.biome;

/**
 * The read side of the widened seam: one texel, bilinear, no sampler unit --
 * what a terrain fragment does with the baked volume (BIOME_PLAN B5, step 3).
 *
 * <h2>Why this is bilinear and the bake was not</h2>
 *
 * <p>The two stages answer different questions and must not blur twice. The
 * bake resolves <em>which biomes</em> a cell blends, over {@code (2R+1)^3}
 * cells of neighbourhood -- widening is its job. Sampling a position resolves
 * <em>how much</em> of each neighbouring cell applies at that point, which is
 * the same trilinear question {@link BiomeSampler} already answers between
 * field cells. So the fragment side interpolates between the eight baked cell
 * centres around the position and nothing more: same neighbour count, same
 * weights, one texel per corner instead of eight field fetches.
 *
 * <p>This is why the volume is read as an unfiltered (storage) image rather
 * than through a linear sampler. Hardware filtering would interpolate the
 * <em>packed</em> id bytes, and the average of ids 12 and 40 is neither: slot
 * order would be destroyed and ids would be invented. The pack's contract is
 * therefore "read the volume as a texel, interpolate the unpacked entries" --
 * a shader that samples it through a sampler is refused by the format rather
 * than left to look plausible.
 *
 * <h2>What the weights mean</h2>
 *
 * <p>Each corner contributes {@code unpackedWeight / 255 * trilinearCornerWeight},
 * the entries are merged by id (so a biome present in several corners appears
 * once), and the result is renormalised over the top
 * {@link BiomeSample#MAX_ENTRIES}. A corner marked as a hole
 * ({@link BiomeFieldPacker#UNKNOWN_TOPK} with zero weights) contributes
 * nothing -- unknown stays unknown, and an under-covered sample is renormalised
 * over what is left rather than borrowing from the fallback registry biome.
 */
public final class BiomeSeamSampler {

    private final BiomeSeamShader.Layout layout;
    private final BiomeRegistry registry;

    // Scratch: a sample walks eight corners of up to four entries each.
    private final int[] mergedIds;
    private final float[] mergedWeights;

    public BiomeSeamSampler(BiomeRegistry registry) {
        this(BiomeSeamShader.Layout.TOPK_RGBA8UI, registry);
    }

    public BiomeSeamSampler(BiomeSeamShader.Layout layout, BiomeRegistry registry) {
        if (layout == null) throw new IllegalArgumentException("a seam sampler needs a volume layout");
        if (registry == null) throw new IllegalArgumentException("a seam sampler needs a registry");
        this.layout = layout;
        this.registry = registry;
        int capacity = 8 * BiomeSample.MAX_ENTRIES;
        mergedIds = new int[capacity];
        mergedWeights = new float[capacity];
    }

    public BiomeSeamShader.Layout layout() { return layout; }
    public BiomeRegistry registry() { return registry; }

    /**
     * Samples the baked volume at a world position.
     *
     * @param ids     packed id texels, one per cell, {@code x + sx*(y + sy*z)}
     * @param weights packed weight texels, same indexing
     * @param sx,sy,sz the volume's extent in cells
     * @param cellSize blocks per cell, {@code BiomeField.DEFAULT_CELL_SIZE} in
     *                 practice; passed rather than assumed so a pack with a
     *                 different field granularity cannot be read at the wrong scale
     */
    public BiomeSample sample(int[] ids, int[] weights, int sx, int sy, int sz, int cellSize,
                              double wx, double wy, double wz) {
        // No origin given: the volume is assumed to start at cell 0. That is
        // only ever true for a fixture; a field window centred on the player
        // starts at NEGATIVE cells, and reading it as if it started at zero
        // shifts the whole seam by half the window (measured: a border at
        // z = 0 was reported as a flat swamp, and everything inside the swamp
        // as absent, because the fetch landed 23 cells in front of the data).
        return sample(ids, weights, sx, sy, sz, cellSize, 0, 0, 0, wx, wy, wz);
    }

    /**
     * Samples the baked volume at a world position, for a volume that is a
     * window into the world starting at {@code (originCX, originCY, originCZ)}
     * in cell space -- the field's own origin, which is negative for a window
     * centred on a player near the origin.
     */
    public BiomeSample sample(int[] ids, int[] weights, int sx, int sy, int sz, int cellSize,
                              int originCX, int originCY, int originCZ,
                              double wx, double wy, double wz) {
        if (ids == null || weights == null) {
            throw new IllegalArgumentException("sample() needs both baked volumes");
        }
        int cells = sx * sy * sz;
        if (ids.length < cells || weights.length < cells) {
            throw new IllegalArgumentException("BIOME_VOLUME_TOO_SHORT: volumes hold "
                    + Math.min(ids.length, weights.length) + " cells, the extent says " + cells);
        }
        if (cellSize <= 0) throw new IllegalArgumentException("cellSize must be positive");

        BiomeSample out = new BiomeSample(registry, (float) wx, (float) wy, (float) wz);

        // Continuous cell space: cell c covers [c*cs, (c+1)*cs), centre (c+0.5)*cs.
        // Then cell space -> index space by subtracting the window's origin:
        // this is the step whose absence made every live sample miss.
        double fx = wx / cellSize - 0.5 - originCX;
        double fy = wy / cellSize - 0.5 - originCY;
        double fz = wz / cellSize - 0.5 - originCZ;
        int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy), z0 = (int) Math.floor(fz);
        float tx = (float) (fx - x0), ty = (float) (fy - y0), tz = (float) (fz - z0);

        int merged = 0;
        for (int k = 0; k < 8; k++) {
            int dx = k & 1, dy = (k >> 1) & 1, dz = (k >> 2) & 1;
            float corner = (dx == 0 ? 1f - tx : tx)
                    * (dy == 0 ? 1f - ty : ty)
                    * (dz == 0 ? 1f - tz : tz);
            if (corner <= 0f) continue;
            int cx = x0 + dx, cy = y0 + dy, cz = z0 + dz;
            if (cx < 0 || cy < 0 || cz < 0 || cx >= sx || cy >= sy || cz >= sz) {
                // Outside the volume is not invented and not clamped: it is absent.
                continue;
            }
            int at = cx + sx * (cy + sy * cz);
            int idTexel = ids[at];
            int wTexel = weights[at];
            for (int slot = 0; slot < BiomeSample.MAX_ENTRIES; slot++) {
                int id = (idTexel >>> (slot * 8)) & 0xFF;
                int q = (wTexel >>> (slot * 8)) & 0xFF;
                if (id == BiomeFieldPacker.UNKNOWN_TOPK || q == 0) continue;
                float w = (q / 255f) * corner;
                if (w <= 0f) continue;
                int found = -1;
                for (int j = 0; j < merged; j++) {
                    if (mergedIds[j] == id) { found = j; break; }
                }
                if (found >= 0) {
                    mergedWeights[found] += w;
                } else {
                    mergedIds[merged] = id;
                    mergedWeights[merged] = w;
                    merged++;
                }
            }
        }
        if (merged == 0) return out;   // empty: the caller falls back, as the sampler does

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

    /** Samples at a cell centre -- the one position where "one cell, one id" is exact. */
    public BiomeSample sampleCellCentre(int[] ids, int[] weights, int sx, int sy, int sz,
                                        int cellSize, int cx, int cy, int cz) {
        return sample(ids, weights, sx, sy, sz, cellSize,
                (cx + 0.5) * cellSize, (cy + 0.5) * cellSize, (cz + 0.5) * cellSize);
    }

    /** Samples at a world cell's centre for a volume that starts at {@code origin}. */
    public BiomeSample sampleCellCentre(int[] ids, int[] weights, int sx, int sy, int sz,
                                        int cellSize, int originCX, int originCY, int originCZ,
                                        int cx, int cy, int cz) {
        return sample(ids, weights, sx, sy, sz, cellSize, originCX, originCY, originCZ,
                (cx + 0.5) * cellSize, (cy + 0.5) * cellSize, (cz + 0.5) * cellSize);
    }

    /** Same comparator as the bake: descending weight, ties by ascending id. */
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
}
