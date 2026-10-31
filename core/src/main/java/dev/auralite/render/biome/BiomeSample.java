package dev.auralite.render.biome;

import java.util.Arrays;

/**
 * The answer to "which biome is here?" -- up to N biomes and their weights.
 *
 * <p>This is the type Iris has no equivalent of. Its {@code biome} uniform is
 * one int; this is a sorted, normalised weight vector, so a fragment on a
 * swamp/beach border carries both and the shader can blend water colour,
 * fog and foliage across the seam instead of snapping.
 */
public final class BiomeSample {
    /** How many contributors are exposed. Matches the vec4 the shader API uses. */
    public static final int MAX_ENTRIES = 4;

    public final int[] ids = new int[MAX_ENTRIES];
    public final float[] weights = new float[MAX_ENTRIES];
    public int count;
    public final float worldX, worldY, worldZ;
    private final BiomeRegistry registry;

    public BiomeSample(BiomeRegistry registry, float worldX, float worldY, float worldZ) {
        this.registry = registry;
        this.worldX = worldX; this.worldY = worldY; this.worldZ = worldZ;
        Arrays.fill(ids, -1);
    }

    public int dominantId() { return count > 0 ? ids[0] : -1; }
    public float dominantWeight() { return count > 0 ? weights[0] : 0f; }
    public BiomeRecord dominant() { return registry.byId(dominantId()); }

    /** True when more than one biome contributes measurably. */
    public boolean isMixed() {
        return count > 1 && weights[1] > 0.02f;
    }

    public float weightOf(int biomeId) {
        for (int i = 0; i < count; i++) if (ids[i] == biomeId) return weights[i];
        return 0f;
    }

    /**
     * Blends a property across the contributors.
     *
     * <p>Blendable properties are a weighted sum. Non-blendable ones (category,
     * precipitation) come from the dominant contributor, because a weighted
     * average of two labels is a third label that does not exist.
     */
    public float blend(BiomeProperty p) {
        if (count == 0) {
            BiomeRecord f = registry.fallback();
            return f == null ? 0f : f.scalar(p);
        }
        if (!p.isBlendable()) {
            BiomeRecord d = dominant();
            return d == null ? 0f : d.scalar(p);
        }
        double sum = 0, wsum = 0;
        for (int i = 0; i < count; i++) {
            BiomeRecord r = registry.byId(ids[i]);
            if (r == null) continue;
            sum += r.scalar(p) * weights[i];
            wsum += weights[i];
        }
        return wsum == 0 ? 0f : (float) (sum / wsum);
    }

    /** Blends a 3-component property into {@code out}; returns {@code out}. */
    public float[] blend3(BiomeProperty p, float[] out) {
        if (out == null || out.length < 3) out = new float[3];
        if (count == 0) {
            BiomeRecord f = registry.fallback();
            if (f != null) System.arraycopy(f.property(p), 0, out, 0, 3);
            return out;
        }
        out[0] = out[1] = out[2] = 0f;
        double wsum = 0;
        for (int i = 0; i < count; i++) {
            BiomeRecord r = registry.byId(ids[i]);
            if (r == null) continue;
            float[] c = r.property(p);
            if (c == null) continue;
            out[0] += c[0] * weights[i];
            out[1] += c[1] * weights[i];
            out[2] += c[2] * weights[i];
            wsum += weights[i];
        }
        if (wsum > 0) { out[0] /= wsum; out[1] /= wsum; out[2] /= wsum; }
        return out;
    }

    public float[] waterColor() { return blend3(BiomeProperty.WATER_COLOR, new float[3]); }
    public float[] waterFogColor() { return blend3(BiomeProperty.WATER_FOG_COLOR, new float[3]); }
    public float[] foliageColor() { return blend3(BiomeProperty.FOLIAGE_COLOR, new float[3]); }
    public float[] grassColor() { return blend3(BiomeProperty.GRASS_COLOR, new float[3]); }
    public float temperature() { return blend(BiomeProperty.TEMPERATURE); }
    public float downfall() { return blend(BiomeProperty.DOWNFALL); }

    /** The vec4 a native pack reads: three dominant ids plus the residual weight. */
    public float[] asIdsVec4() {
        float[] v = new float[4];
        for (int i = 0; i < 3 && i < count; i++) v[i] = ids[i];
        v[3] = count > 3 ? weights[3] : 0f;
        return v;
    }

    /** The vec4 a native pack reads: the weights, zero-padded. */
    public float[] asWeightsVec4() {
        float[] v = new float[4];
        for (int i = 0; i < MAX_ENTRIES && i < count; i++) v[i] = weights[i];
        return v;
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder("BiomeSample{");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            BiomeRecord r = registry.byId(ids[i]);
            sb.append(r == null ? ("#" + ids[i]) : r.constantName).append('=').append(weights[i]);
        }
        return sb.append(" @ ").append(worldX).append(',').append(worldY).append(',').append(worldZ)
                .append('}').toString();
    }
}
