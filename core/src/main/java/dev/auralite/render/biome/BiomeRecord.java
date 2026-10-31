package dev.auralite.render.biome;

import java.util.Arrays;

/**
 * One biome's rendering-relevant properties.
 *
 * <p>Only what a shader actually consumes is stored. The vanilla registry is
 * far larger; the Fabric glue copies these fields out of
 * {@code BiomeSpecialEffects} per world load and overwrites the built-in
 * defaults, so a modded biome is correct without this table knowing about it.
 */
public final class BiomeRecord {
    public final int id;
    public final String registryKey;
    public final String constantName;
    public final BiomeCategory category;
    public final BiomePrecipitation precipitation;
    public final float temperature;
    public final float downfall;

    /** Linear-light RGB in 0..1. */
    public final float[] waterColor = new float[3];
    public final float[] waterFogColor = new float[3];
    public final float[] foliageColor = new float[3];
    public final float[] grassColor = new float[3];
    /** Multiplier on the dimension's base fog density; 1.0 is neutral. */
    public float fogDensityScale = 1.0f;
    /** Multiplier on cloud coverage for this biome's column; 1.0 is neutral. */
    public float cloudCoverageScale = 1.0f;

    public BiomeRecord(int id, String registryKey, String constantName, BiomeCategory category,
                       BiomePrecipitation precipitation, float temperature, float downfall,
                       float[] waterColor) {
        this.id = id;
        this.registryKey = registryKey;
        this.constantName = constantName;
        this.category = category;
        this.precipitation = precipitation;
        this.temperature = temperature;
        this.downfall = downfall;
        System.arraycopy(waterColor, 0, this.waterColor, 0, 3);
        // Sensible neutral defaults; the glue replaces them from the live registry.
        Arrays.fill(waterFogColor, 0.05f);
        Arrays.fill(foliageColor, 0.42f);
        Arrays.fill(grassColor, 0.45f);
        waterFogColor[1] = 0.16f;
        waterFogColor[2] = 0.20f;
    }

    public BiomeRecord colors(float[] water, float[] waterFog, float[] foliage, float[] grass) {
        if (water != null) System.arraycopy(water, 0, waterColor, 0, 3);
        if (waterFog != null) System.arraycopy(waterFog, 0, waterFogColor, 0, 3);
        if (foliage != null) System.arraycopy(foliage, 0, foliageColor, 0, 3);
        if (grass != null) System.arraycopy(grassColor, 0, grassColor, 0, 3);
        return this;
    }

    public BiomeRecord fog(float[] waterFog) {
        if (waterFog != null) System.arraycopy(waterFog, 0, this.waterFogColor, 0, 3);
        return this;
    }

    public BiomeRecord foliage(float[] foliage, float[] grass) {
        if (foliage != null) System.arraycopy(foliage, 0, foliageColor, 0, 3);
        if (grass != null) System.arraycopy(grass, 0, grassColor, 0, 3);
        return this;
    }

    public BiomeRecord fogDensityScale(float s) { this.fogDensityScale = s; return this; }
    public BiomeRecord cloudCoverageScale(float s) { this.cloudCoverageScale = s; return this; }

    /** The property vector a shader property id resolves to. */
    public float[] property(BiomeProperty p) {
        switch (p) {
            case WATER_COLOR: return waterColor;
            case WATER_FOG_COLOR: return waterFogColor;
            case FOLIAGE_COLOR: return foliageColor;
            case GRASS_COLOR: return grassColor;
            default: return null;
        }
    }

    public float scalar(BiomeProperty p) {
        switch (p) {
            case TEMPERATURE: return temperature;
            case DOWNFALL: return downfall;
            case FOG_DENSITY_SCALE: return fogDensityScale;
            case CLOUD_COVERAGE_SCALE: return cloudCoverageScale;
            case CATEGORY: return category.id();
            case PRECIPITATION: return precipitation.id();
            case BIOME_ID: return id;
            default: return 0f;
        }
    }

    @Override public String toString() {
        return constantName + "(id=" + id + ", " + category + ", " + precipitation
                + ", t=" + temperature + ", d=" + downfall
                + ", water=" + Arrays.toString(waterColor) + ")";
    }
}
