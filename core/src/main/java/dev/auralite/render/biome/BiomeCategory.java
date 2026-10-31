package dev.auralite.render.biome;

/**
 * The {@code CAT_*} constants Iris exposes through {@code biome_category}.
 *
 * <p>Names and ordinals are the legacy ones on purpose: a LEGACY pack that
 * compares {@code biome_category == CAT_OCEAN} must keep getting the same
 * integer, so this enum's ordinals are part of the compatibility contract and
 * are pinned by a test gate.
 */
public enum BiomeCategory {
    CAT_NONE,
    CAT_TAIGA,
    CAT_EXTREME_HILLS,
    CAT_JUNGLE,
    CAT_MESA,
    CAT_PLAINS,
    CAT_SAVANNA,
    CAT_ICY,
    CAT_THE_END,
    CAT_BEACH,
    CAT_FOREST,
    CAT_OCEAN,
    CAT_DESERT,
    CAT_RIVER,
    CAT_SWAMP,
    CAT_MUSHROOM,
    CAT_NETHER;

    public int id() { return ordinal(); }

    public static BiomeCategory byId(int id) {
        if (id < 0 || id >= values().length) return CAT_NONE;
        return values()[id];
    }
}
