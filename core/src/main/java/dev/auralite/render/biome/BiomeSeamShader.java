package dev.auralite.render.biome;

/**
 * The compute emitter for the widened seam: {@code biome_field.comp} is not
 * hand-written, it is <em>generated from this class</em> (BIOME_PLAN B5, step 2).
 *
 * <h2>Why generated, and not written once by hand</h2>
 *
 * <p>A GLSL blur that reproduces {@link BiomeSeam} is a second implementation of
 * the same arithmetic in a second language. The plan's gate 1 exists because two
 * implementations of one rule drift: the shader still compiles, still renders,
 * and quietly disagrees with the CPU by a weight or a tie-break. Keeping the
 * shader in the tree as a generated artefact does not by itself prevent that --
 * the gate does, by regenerating the text from this class and comparing bytes.
 * The committed {@code .comp} is checked in so the pack loads with no toolchain
 * and with no network, which is the same rule every other pack asset follows.
 *
 * <h2>The emitted rule, in order</h2>
 *
 * <ol>
 *   <li>Read every cell of the box {@code [c-R, c+R]} clipped to the volume
 *       (clipping, not skipping: a cell outside the volume cannot contribute,
 *       which is exactly {@link BiomeField#idAtCell}'s {@code UNKNOWN} answer).</li>
 *   <li>Skip {@code UNKNOWN} cells. Merge duplicates, adding integer-valued
 *       tent weights {@code prod(R + 1 - |d|)}.</li>
 *   <li>Select the top {@link BiomeSample#MAX_ENTRIES} by weight descending,
 *       ties by ascending id -- written as a selection instead of a full sort
 *       so the emitted loop is bounded by four passes, and provably the same
 *       order, because it uses the same comparator.</li>
 *   <li>Renormalise over what was kept, quantise to bytes with the last slot
 *       taking the remainder (the CPU packer's rule, so the two layouts agree
 *       byte for byte), pack four ids into .x and four weights into .y, low
 *       slot in the low byte.</li>
 * </ol>
 *
 * <h2>Why the result does not depend on traversal order</h2>
 *
 * <p>The scan order above is fixed, so the shader and the mirror would agree
 * even if the sums were order-sensitive. They are not, and the reason is worth
 * stating because it is what makes the receipt "deterministic" more than a
 * promise: every tent weight is an integer, and the largest total a box can
 * reach is {@code (sum of the tent)^3}, which at {@link BiomeSeam#MAX_RADIUS}
 * is 15625 -- far below 2^24, so float32 holds every partial sum exactly. The
 * merged weights are therefore order-free, and the only order-dependent
 * artefact left is the merged array's layout, which the comparator fixes
 * completely. The suite proves it the rude way: shuffle the field's rows, bake
 * both, compare bytes.
 *
 * <h2>The one place CPU and GPU behave differently, on purpose</h2>
 *
 * <p>An id above {@link BiomeFieldPacker#MAX_TOPK_IDS} cannot travel in a byte.
 * Truncating it would silently rename a biome, so neither side truncates: the
 * CPU bake <em>throws</em> {@code BIOME_VOLUME_IDS_OVER_TOPK} (it can), and the
 * shader <em>marks the cell</em> {@link BiomeFieldPacker#UNKNOWN_TOPK} with zero
 * weights (a compute invocation cannot throw, and a made-up id is worse than an
 * honest hole). Both answers mean "this cell has no representable id set"; the
 * pack never reaches the GPU that way in the first place, because
 * {@link BiomeFieldPacker}'s constructor refuses a registry that wide.
 *
 * <p>{@link #emitVolume} is that rule in Java over a flat array, and is what
 * the gate compares against the CPU sampler and the packer. It is a mirror of
 * the emitted GLSL, not the other way round.
 */
public final class BiomeSeamShader {

    /**
     * The widened volume's texel layout, stated once so the producer and the
     * reader cannot disagree about where a byte lives.
     *
     * <p>A cell's {@code .x} holds four ids in ascending slot order (low slot in
     * the low byte, unused slots {@link BiomeFieldPacker#UNKNOWN_TOPK}) and its
     * {@code .y} holds the matching weights out of 255, the last occupied slot
     * taking the remainder. The resource that carries them is
     * <b>{@code r32g32_uint}</b>, not {@code rgba8_uint}.
     *
     * <p>That correction is written down because the wrong version shipped and
     * was expensive: a "packed ids" word is four bytes inside ONE channel, and
     * an {@code rgba8ui} channel is one byte wide. Narrowing an integer image
     * write <b>saturates</b> rather than truncates -- {@code 0xFFFFFF06} and
     * {@code 0x12345678} both land as {@code 255} -- so slots 1..3 became
     * {@link BiomeFieldPacker#UNKNOWN_TOPK} in every cell, the fragment skipped
     * every contribution, and a probe that measured barriers, descriptors and
     * ordering found all three clean while the screen stayed black. The byte
     * budget per cell is the same either way (four bytes per channel, two
     * channels read); what changes is whether a whole packed word fits in one
     * channel. It does not, at 8 bits, and the type has to say so.
     */
    public enum Layout {
        /** Four ids plus four byte weights, per cell. */
        TOPK_RGBA8UI
    }

    /** Binding the widened volume is written to, matching {@link BiomeFieldPacker}. */
    public static final int BINDING_VOLUME_OUT = 0;
    /** Binding the exact-id field is read from. */
    public static final int BINDING_FIELD_IN = 1;
    /** What an absent slot holds, matching {@link BiomeFieldPacker#UNKNOWN_TOPK}. */
    public static final int UNKNOWN_SLOT = BiomeFieldPacker.UNKNOWN_TOPK;
    /** What the exact-id field holds for a cell nothing ingested. */
    public static final long UNKNOWN_FIELD = 0xFFFFFFFFL;

    private BiomeSeamShader() { }

    /** Bytes of private storage one invocation's merge table needs at this radius. */
    public static int tableBytes(int radius) {
        BiomeSeam.checkRadius(radius);
        return BiomeSeam.boxCells(radius) * 8;   // one int + one float per cell
    }

    /**
     * The largest total weight any box can reach, {@code (sum of the tent)^3}.
     * Stated so the determinant claim above is checkable rather than asserted:
     * a total past 2^24 would make the integer sums inexact in float32.
     */
    public static int maxTotalWeight(int radius) {
        BiomeSeam.checkRadius(radius);
        int sum = 0;
        for (int d = -radius; d <= radius; d++) sum += (int) BiomeSeam.axisWeight(d, radius);
        return sum * sum * sum;
    }

    /** True when float32 holds every possible partial sum exactly at this radius. */
    public static boolean sumsAreExact(int radius) {
        return maxTotalWeight(radius) < (1 << 24);
    }

    /**
     * The whole compute source. A pure function of the declared maximum radius,
     * so the committed {@code packs/AuraLite-Native/shaders/biome_field.comp}
     * is byte-comparable against it.
     */
    public static String generate() {
        int maxR = BiomeSeam.MAX_RADIUS;
        int box = BiomeSeam.boxCells(maxR);
        StringBuilder b = new StringBuilder(4096);
        b.append("#version 460\n");
        b.append("// GENERATED FILE - do not edit by hand.\n");
        b.append("// Source of truth: core/.../biome/BiomeSeamShader.java (regenerate with `make shader`).\n");
        b.append("//\n");
        b.append("// Reference pack: the biome seam is widened here, once per baked cell, so a\n");
        b.append("// fragment fetches one texel instead of eight and still gets a wider blend.\n");
        b.append("// The rule is BiomeSeam's: a tent kernel over a (2R+1)^3 box, duplicate ids\n");
        b.append("// merged, top four by weight descending with ties by ascending id, then\n");
        b.append("// renormalised and quantised to bytes, the last slot taking the remainder.\n");
        b.append("//\n");
        b.append("// Cost: a box of ").append(BiomeSeam.boxCells(2)).append(" cells at the reference pack's\n");
        b.append("// R = 2, ").append(box).append(" at the maximum; the merge table is that many int/float\n");
        b.append("// pairs of private storage per invocation, which is why this is a bake and\n");
        b.append("// not a per-frame pass.\n");
        b.append("layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;\n");
        b.append("layout(set = 0, binding = ").append(BINDING_VOLUME_OUT)
                .append(", rg32ui) uniform writeonly uimage3D biome_volume;\n");
        b.append("layout(set = 0, binding = ").append(BINDING_FIELD_IN)
                .append(", r32ui) uniform readonly uimage3D biome_field_ids;\n");
        b.append("\n");
        // The pack's radius arrives as a define (PackDefines inserts the
        // manifest's option lines after #version); without one the shader uses
        // the plan's default. A define past the ceiling is clamped HERE and only
        // here, and the mismatch is impossible to ship: the loader refuses such
        // a pack as BIOME_SEAM_R_TOO_WIDE before any shader is built, so this
        // clamp is the last line of defence rather than the policy.
        b.append("#ifndef BIOME_SEAM_R\n");
        b.append("#define BIOME_SEAM_R 2\n");
        b.append("#endif\n");
        b.append("const int BIOME_SEAM_MAX_R = ").append(maxR).append(";       // BiomeSeam.MAX_RADIUS\n");
        b.append("const int BIOME_SEAM_R_USED = BIOME_SEAM_R > BIOME_SEAM_MAX_R\n");
        b.append("        ? BIOME_SEAM_MAX_R : BIOME_SEAM_R;\n");
        b.append("const int BIOME_TOP_SLOTS = ").append(BiomeSample.MAX_ENTRIES)
                .append(";      // BiomeSample.MAX_ENTRIES\n");
        b.append("const int BIOME_BOX_CELLS = ").append(box).append(";     // (2 * MAX_R + 1)^3\n");
        b.append("const uint BIOME_UNKNOWN_FIELD = 0xFFFFFFFFu;\n");
        b.append("const uint BIOME_UNKNOWN_SLOT = ").append(UNKNOWN_SLOT).append("u;\n");
        b.append("const int BIOME_MAX_TOPK_IDS = ").append(BiomeFieldPacker.MAX_TOPK_IDS)
                .append(";  // BiomeFieldPacker.MAX_TOPK_IDS\n");
        b.append("\n");
        b.append("void main() {\n");
        b.append("    ivec3 dims = imageSize(biome_field_ids);\n");
        b.append("    ivec3 c = ivec3(gl_GlobalInvocationID);\n");
        b.append("    if (any(greaterThanEqual(c, dims))) return;\n");
        b.append("\n");
        b.append("    int ids[BIOME_BOX_CELLS];\n");
        b.append("    float wsum[BIOME_BOX_CELLS];\n");
        b.append("    int merged = 0;\n");
        b.append("\n");
        b.append("    // Clipped box: outside the volume nothing can contribute, which is\n");
        b.append("    // what the CPU side calls an unknown cell.\n");
        b.append("    ivec3 lo = max(ivec3(0), c - BIOME_SEAM_R_USED);\n");
        b.append("    ivec3 hi = min(dims - ivec3(1), c + BIOME_SEAM_R_USED);\n");
        b.append("    for (int z = lo.z; z <= hi.z; z++) {\n");
        b.append("        int wz = BIOME_SEAM_R_USED + 1 - abs(z - c.z);\n");
        b.append("        for (int y = lo.y; y <= hi.y; y++) {\n");
        b.append("            int wy = BIOME_SEAM_R_USED + 1 - abs(y - c.y);\n");
        b.append("            for (int x = lo.x; x <= hi.x; x++) {\n");
        b.append("                int wx = BIOME_SEAM_R_USED + 1 - abs(x - c.x);\n");
        b.append("                uint raw = imageLoad(biome_field_ids, ivec3(x, y, z)).r;\n");
        b.append("                if (raw == BIOME_UNKNOWN_FIELD) continue;\n");
        b.append("                int id = int(raw);\n");
        b.append("                float w = float(wx * wy * wz);\n");
        b.append("                int at = -1;\n");
        b.append("                for (int j = 0; j < merged; j++) {\n");
        b.append("                    if (ids[j] == id) { at = j; break; }\n");
        b.append("                }\n");
        b.append("                if (at < 0) { ids[merged] = id; wsum[merged] = 0.0; at = merged; merged++; }\n");
        b.append("                wsum[at] += w;\n");
        b.append("            }\n");
        b.append("        }\n");
        b.append("    }\n");
        b.append("\n");
        b.append("    // Top BIOME_TOP_SLOTS by weight descending, ties by ascending id: the\n");
        b.append("    // same comparator as BiomeSeam, so the order is the same order.\n");
        b.append("    int cnt = min(merged, BIOME_TOP_SLOTS);\n");
        b.append("    int selIds[BIOME_TOP_SLOTS];\n");
        b.append("    float selW[BIOME_TOP_SLOTS];\n");
        b.append("    float total = 0.0;\n");
        b.append("    for (int i = 0; i < cnt; i++) {\n");
        b.append("        int best = -1;\n");
        b.append("        for (int j = 0; j < merged; j++) {\n");
        b.append("            if (ids[j] < 0) continue;\n");
        b.append("            if (best < 0 || wsum[j] > wsum[best]\n");
        b.append("                || (wsum[j] == wsum[best] && ids[j] < ids[best])) best = j;\n");
        b.append("        }\n");
        b.append("        selIds[i] = ids[best];\n");
        b.append("        selW[i] = wsum[best];\n");
        b.append("        total += wsum[best];\n");
        b.append("        ids[best] = -1;\n");
        b.append("    }\n");
        b.append("\n");
        b.append("    // BIOME_VOLUME_IDS_OVER_TOPK: an id a byte cannot carry is never\n");
        b.append("    // truncated into a different biome - the cell becomes an honest hole.\n");
        b.append("    bool overTopk = false;\n");
        b.append("    for (int i = 0; i < cnt; i++) {\n");
        b.append("        if (selIds[i] > BIOME_MAX_TOPK_IDS) overTopk = true;\n");
        b.append("    }\n");
        b.append("\n");
        b.append("    // Quantise to bytes, the last slot taking the remainder so the four\n");
        b.append("    // weights sum to 255 exactly - the packer's rule, so both layouts agree.\n");
        b.append("    uint packedIds = 0u;\n");
        b.append("    uint packedW = 0u;\n");
        b.append("    int assigned = 0;\n");
        b.append("    for (int k = 0; k < BIOME_TOP_SLOTS; k++) {\n");
        b.append("        uint id = BIOME_UNKNOWN_SLOT;\n");
        b.append("        int q = 0;\n");
        b.append("        if (k < cnt && total > 0.0 && !overTopk) {\n");
        b.append("            id = uint(selIds[k]);\n");
        b.append("            int scaled = int(round(selW[k] / total * 255.0));\n");
        b.append("            if (k < cnt - 1) { q = scaled; assigned += scaled; }\n");
        b.append("            else q = max(0, 255 - assigned);\n");
        b.append("        }\n");
        b.append("        packedIds |= (id & 0xFFu) << uint(k * 8);\n");
        b.append("        packedW |= (uint(q) & 0xFFu) << uint(k * 8);\n");
        b.append("    }\n");
        b.append("    imageStore(biome_volume, c, uvec4(packedIds, packedW, 0u, 0u));\n");
        b.append("}\n");
        return b.toString();
    }

    /**
     * The emitted rule in Java, over a flat {@code int[]} laid out x fastest then
     * y then z -- the same order the GLSL scans, which is why the receipt can be
     * byte equality and not an epsilon.
     *
     * @param ids      field ids, {@link BiomeField#UNKNOWN} for nothing ingested
     * @param sx,sy,sz volume extent in cells
     * @param radius   blend radius in cells, refused past {@link BiomeSeam#MAX_RADIUS}
     * @param outIds   receives one packed id texel per cell, length {@code sx*sy*sz}
     * @param outWeights receives one packed weight texel per cell, same length
     */
    public static void emitVolume(int[] ids, int sx, int sy, int sz, int radius,
                                  int[] outIds, int[] outWeights) {
        int over = bake(ids, sx, sy, sz, radius, outIds, outWeights, true);
        if (over > 0) {
            // bake() throws in strict mode; this guard is for the compiler's benefit.
            throw new IllegalArgumentException("BIOME_VOLUME_IDS_OVER_TOPK: " + over + " cells");
        }
    }

    /**
     * The shader's behaviour, for the gate: cells whose id set cannot be carried
     * in bytes are written as holes ({@code UNKNOWN_SLOT}, zero weights) and
     * counted, exactly what the compute invocation does. Returns how many cells
     * were marked, so a receipt can state it.
     */
    public static int emitVolumeLenient(int[] ids, int sx, int sy, int sz, int radius,
                                        int[] outIds, int[] outWeights) {
        return bake(ids, sx, sy, sz, radius, outIds, outWeights, false);
    }

    private static int bake(int[] ids, int sx, int sy, int sz, int radius,
                            int[] outIds, int[] outWeights, boolean strict) {
        BiomeSeam.checkRadius(radius);
        int cells = sx * sy * sz;
        if (ids.length < cells) {
            throw new IllegalArgumentException("field holds " + ids.length + " cells, extent says " + cells);
        }
        if (outIds.length < cells || outWeights.length < cells) {
            throw new IllegalArgumentException("output arrays must hold " + cells + " cells");
        }
        int box = BiomeSeam.boxCells(radius);
        int[] mIds = new int[box];
        float[] mW = new float[box];
        int[] selIds = new int[BiomeSample.MAX_ENTRIES];
        float[] selW = new float[BiomeSample.MAX_ENTRIES];
        int marked = 0;

        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    int merged = accumulate(ids, sx, sy, sz, x, y, z, radius, mIds, mW);
                    int cnt = selectTop(merged, mIds, mW, selIds, selW);
                    float total = 0f;
                    for (int i = 0; i < cnt; i++) total += selW[i];

                    boolean overTopk = false;
                    for (int i = 0; i < cnt; i++) {
                        if (selIds[i] > BiomeFieldPacker.MAX_TOPK_IDS) overTopk = true;
                    }
                    if (overTopk) {
                        if (strict) {
                            throw new IllegalArgumentException("BIOME_VOLUME_IDS_OVER_TOPK: cell ("
                                    + x + "," + y + "," + z + ") merges an id above "
                                    + BiomeFieldPacker.MAX_TOPK_IDS
                                    + "; a byte cannot carry it and truncating would rename a biome");
                        }
                        marked++;
                    }

                    int packedIds = 0;
                    int packedW = 0;
                    int assigned = 0;
                    for (int k = 0; k < BiomeSample.MAX_ENTRIES; k++) {
                        int id = UNKNOWN_SLOT;
                        int q = 0;
                        if (k < cnt && total > 0f && !overTopk) {
                            id = selIds[k];
                            int scaled = Math.round(selW[k] / total * 255f);
                            if (k < cnt - 1) { q = scaled; assigned += scaled; }
                            else q = Math.max(0, 255 - assigned);
                        }
                        packedIds |= (id & 0xFF) << (k * 8);
                        packedW |= (q & 0xFF) << (k * 8);
                    }
                    int at = x + sx * (y + sy * z);
                    outIds[at] = packedIds;
                    outWeights[at] = packedW;
                }
            }
        }
        return marked;
    }

    /** Mirrors the emitted scan: clipped box, tent weights, merge into the table. */
    static int accumulate(int[] ids, int sx, int sy, int sz, int cx, int cy, int cz,
                          int radius, int[] mIds, float[] mW) {
        int merged = 0;
        int lz = Math.max(0, cz - radius), hz = Math.min(sz - 1, cz + radius);
        int ly = Math.max(0, cy - radius), hy = Math.min(sy - 1, cy + radius);
        int lx = Math.max(0, cx - radius), hx = Math.min(sx - 1, cx + radius);
        for (int z = lz; z <= hz; z++) {
            int wz = radius + 1 - Math.abs(z - cz);
            for (int y = ly; y <= hy; y++) {
                int wy = radius + 1 - Math.abs(y - cy);
                for (int x = lx; x <= hx; x++) {
                    int wx = radius + 1 - Math.abs(x - cx);
                    int id = ids[x + sx * (y + sy * z)];
                    if (id == BiomeField.UNKNOWN) continue;
                    float w = (float) (wx * wy * wz);
                    int at = -1;
                    for (int j = 0; j < merged; j++) {
                        if (mIds[j] == id) { at = j; break; }
                    }
                    if (at < 0) { mIds[merged] = id; mW[merged] = 0f; at = merged; merged++; }
                    mW[at] += w;
                }
            }
        }
        return merged;
    }

    /** Mirrors the emitted selection sort; returns how many slots were filled. */
    static int selectTop(int merged, int[] mIds, float[] mW, int[] selIds, float[] selW) {
        int cnt = Math.min(merged, BiomeSample.MAX_ENTRIES);
        for (int i = 0; i < cnt; i++) {
            int best = -1;
            for (int j = 0; j < merged; j++) {
                if (mIds[j] < 0) continue;
                if (best < 0 || mW[j] > mW[best] || (mW[j] == mW[best] && mIds[j] < mIds[best])) best = j;
            }
            selIds[i] = mIds[best];
            selW[i] = mW[best];
            mIds[best] = -1;
        }
        return cnt;
    }
}
