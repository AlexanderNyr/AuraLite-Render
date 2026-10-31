package dev.auralite.render.biome;

import dev.auralite.render.rhi.GfxFormat;

/**
 * The biome property lookup table, as a 2D texture.
 *
 * <p>One texel column per biome id, five RGBA32F rows. The shader fetches a
 * row by biome id and gets the property without any CPU involvement, which is
 * what makes per-pixel multi-biome blending affordable: eight id fetches from
 * the field plus eight LUT fetches, no branching, no uniform re-upload.
 *
 * <pre>
 * row 0  WATER_COLOR      rgb = linear water colour          a = 1.0
 * row 1  WATER_FOG_COLOR  rgb = linear water fog colour      a = fogDensityScale
 * row 2  FOLIAGE_COLOR    rgb = linear foliage tint          a = temperature
 * row 3  GRASS_COLOR      rgb = linear grass tint            a = downfall
 * row 4  META             r = biome id, g = category, b = precipitation, a = cloudCoverageScale
 * </pre>
 *
 * The layout is fixed and versioned: {@link #LAYOUT_VERSION} goes up if a row
 * changes meaning, and the native pack format refuses a pack built against a
 * different one.
 */
public final class BiomeLut {
    public static final int LAYOUT_VERSION = 1;
    public static final int ROW_WATER_COLOR = 0;
    public static final int ROW_WATER_FOG = 1;
    public static final int ROW_FOLIAGE = 2;
    public static final int ROW_GRASS = 3;
    public static final int ROW_META = 4;
    public static final int ROWS = 5;
    public static final GfxFormat FORMAT = GfxFormat.RGBA32F;

    private final int width;
    private final float[] texels;

    private BiomeLut(int width, float[] texels) {
        this.width = width;
        this.texels = texels;
    }

    /** Builds the LUT for a registry, padded to a power-of-two width. */
    public static BiomeLut build(BiomeRegistry registry) {
        int needed = Math.max(1, registry.highestId() + 1);
        int w = 1;
        while (w < needed) w <<= 1;
        float[] t = new float[w * ROWS * 4];
        BiomeRecord fallback = registry.fallback();
        for (int id = 0; id < w; id++) {
            BiomeRecord r = registry.byId(id);
            if (r == null) r = fallback;
            if (r == null) continue;
            put(t, w, id, ROW_WATER_COLOR, r.waterColor[0], r.waterColor[1], r.waterColor[2], 1.0f);
            put(t, w, id, ROW_WATER_FOG, r.waterFogColor[0], r.waterFogColor[1], r.waterFogColor[2], r.fogDensityScale);
            put(t, w, id, ROW_FOLIAGE, r.foliageColor[0], r.foliageColor[1], r.foliageColor[2], r.temperature);
            put(t, w, id, ROW_GRASS, r.grassColor[0], r.grassColor[1], r.grassColor[2], r.downfall);
            put(t, w, id, ROW_META, r.id, r.category.id(), r.precipitation.id(), r.cloudCoverageScale);
        }
        return new BiomeLut(w, t);
    }

    private static void put(float[] t, int w, int x, int row, float r, float g, float b, float a) {
        int base = (row * w + x) * 4;
        t[base] = r; t[base + 1] = g; t[base + 2] = b; t[base + 3] = a;
    }

    public int width() { return width; }
    public int height() { return ROWS; }
    public int texelCount() { return width * ROWS; }
    public GfxFormat format() { return FORMAT; }
    public long sizeBytes() { return (long) texels.length * 4; }

    /** Row-major RGBA floats, ready for an upload. Not a copy: treat as read-only. */
    public float[] texels() { return texels; }

    /** Reads one component back; used by the gates instead of poking the array. */
    public float get(int id, int row, int component) {
        if (id < 0 || id >= width) return 0f;
        return texels[(row * width + id) * 4 + component];
    }

    public float[] readColor(int id, int row) {
        return new float[] { get(id, row, 0), get(id, row, 1), get(id, row, 2) };
    }

    @Override public String toString() {
        return "BiomeLut{" + width + "x" + ROWS + " RGBA32F, " + sizeBytes() + " B, layout v" + LAYOUT_VERSION + "}";
    }
}
