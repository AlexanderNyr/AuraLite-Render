package dev.auralite.render.biome;

/** The {@code PPT_*} constants behind {@code biome_precipitation}. */
public enum BiomePrecipitation {
    PPT_NONE, PPT_RAIN, PPT_SNOW;

    public int id() { return ordinal(); }

    public static BiomePrecipitation byId(int id) {
        if (id < 0 || id >= values().length) return PPT_NONE;
        return values()[id];
    }
}
