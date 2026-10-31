package dev.auralite.render.biome;

/**
 * Properties that can be blended across biomes, and those that cannot.
 *
 * <p>Splitting them is the honest part of the multi-biome API. A colour or a
 * temperature is a quantity: blending two of them is meaningful and is what
 * removes the seam at a biome border. A category or a precipitation type is a
 * label: blending two labels produces something that is neither, so those are
 * resolved by dominant weight and the shader is told so explicitly.
 */
public enum BiomeProperty {
    WATER_COLOR(true, 3),
    WATER_FOG_COLOR(true, 3),
    FOLIAGE_COLOR(true, 3),
    GRASS_COLOR(true, 3),
    TEMPERATURE(true, 1),
    DOWNFALL(true, 1),
    FOG_DENSITY_SCALE(true, 1),
    CLOUD_COVERAGE_SCALE(true, 1),
    CATEGORY(false, 1),
    PRECIPITATION(false, 1),
    BIOME_ID(false, 1);

    private final boolean blendable;
    private final int components;

    BiomeProperty(boolean blendable, int components) {
        this.blendable = blendable;
        this.components = components;
    }

    /** True when a weighted blend is meaningful; false when argmax must be used. */
    public boolean isBlendable() { return blendable; }
    public int components() { return components; }
    public boolean isVector() { return components == 3; }
}
