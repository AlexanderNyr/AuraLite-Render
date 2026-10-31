package dev.auralite.render.biome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Id &lt;-&gt; record mapping, in registration order.
 *
 * <p>Registration order is the contract: Iris hands a pack
 * {@code BIOME_PLAINS} and friends as small integers derived from registry
 * order, so the built-in table below is seeded in that order and modded biomes
 * are appended as the game registers them. Ids are therefore stable for the
 * lifetime of a world load but not across versions, which is why the native
 * pack format refers to biomes by {@code registryKey} and resolves to an id at
 * load instead of hard-coding numbers.
 */
public final class BiomeRegistry {

    /** Water colours as used by AuraLite-Shaders v1.2.0, the reference pack. */
    private static final float[] WATER_DEFAULT = { 0.247f, 0.463f, 0.894f };
    private static final float[] WATER_SWAMP = { 0.380f, 0.482f, 0.392f };
    private static final float[] WATER_MANGROVE = { 0.227f, 0.478f, 0.416f };
    private static final float[] WATER_LUKEWARM = { 0.271f, 0.678f, 0.416f };
    private static final float[] WATER_WARM = { 0.263f, 0.835f, 0.933f };
    private static final float[] WATER_COLD = { 0.239f, 0.341f, 0.839f };
    private static final float[] WATER_FROZEN = { 0.224f, 0.220f, 0.788f };
    private static final float[] WATER_MEADOW = { 0.055f, 0.306f, 0.812f };

    private static final float[] FOG_DEFAULT = { 0.05f, 0.16f, 0.20f };
    private static final float[] FOG_SWAMP = { 0.09f, 0.13f, 0.07f };
    private static final float[] FOG_FROZEN = { 0.10f, 0.18f, 0.32f };
    private static final float[] FOG_WARM = { 0.06f, 0.20f, 0.24f };

    private final List<BiomeRecord> byId = new ArrayList<BiomeRecord>();
    private final Map<String, BiomeRecord> byConstant = new LinkedHashMap<String, BiomeRecord>();
    private final Map<String, BiomeRecord> byKey = new LinkedHashMap<String, BiomeRecord>();
    private int capacity = 1024;

    public static BiomeRegistry empty() { return new BiomeRegistry(); }

    /** Built-in table. The Fabric glue overwrites colours from the live registry. */
    public static BiomeRegistry vanillaDefaults() {
        BiomeRegistry r = new BiomeRegistry();
        // Order matters: these ids are what a LEGACY pack compares against.
        r.seed("minecraft:ocean", "BIOME_OCEAN", BiomeCategory.CAT_OCEAN, BiomePrecipitation.PPT_RAIN, 0.5f, 0.5f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:plains", "BIOME_PLAINS", BiomeCategory.CAT_PLAINS, BiomePrecipitation.PPT_RAIN, 0.8f, 0.4f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:desert", "BIOME_DESERT", BiomeCategory.CAT_DESERT, BiomePrecipitation.PPT_NONE, 2.0f, 0.0f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:windswept_hills", "BIOME_MOUNTAINS", BiomeCategory.CAT_EXTREME_HILLS, BiomePrecipitation.PPT_RAIN, 0.2f, 0.3f, WATER_COLD, FOG_DEFAULT);
        r.seed("minecraft:forest", "BIOME_FOREST", BiomeCategory.CAT_FOREST, BiomePrecipitation.PPT_RAIN, 0.7f, 0.8f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:taiga", "BIOME_TAIGA", BiomeCategory.CAT_TAIGA, BiomePrecipitation.PPT_RAIN, 0.25f, 0.8f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:swamp", "BIOME_SWAMP", BiomeCategory.CAT_SWAMP, BiomePrecipitation.PPT_RAIN, 0.8f, 0.9f, WATER_SWAMP, FOG_SWAMP);
        r.seed("minecraft:mangrove_swamp", "BIOME_MANGROVE_SWAMP", BiomeCategory.CAT_SWAMP, BiomePrecipitation.PPT_RAIN, 0.8f, 0.9f, WATER_MANGROVE, FOG_SWAMP);
        r.seed("minecraft:river", "BIOME_RIVER", BiomeCategory.CAT_RIVER, BiomePrecipitation.PPT_RAIN, 0.5f, 0.5f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:nether_wastes", "BIOME_NETHER", BiomeCategory.CAT_NETHER, BiomePrecipitation.PPT_NONE, 2.0f, 0.0f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:the_end", "BIOME_THE_END", BiomeCategory.CAT_THE_END, BiomePrecipitation.PPT_NONE, 0.5f, 0.5f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:frozen_ocean", "BIOME_FROZEN_OCEAN", BiomeCategory.CAT_OCEAN, BiomePrecipitation.PPT_SNOW, 0.0f, 0.5f, WATER_FROZEN, FOG_FROZEN);
        r.seed("minecraft:frozen_river", "BIOME_FROZEN_RIVER", BiomeCategory.CAT_RIVER, BiomePrecipitation.PPT_SNOW, 0.0f, 0.5f, WATER_FROZEN, FOG_FROZEN);
        r.seed("minecraft:snowy_plains", "BIOME_ICE_PLAINS", BiomeCategory.CAT_ICY, BiomePrecipitation.PPT_SNOW, 0.0f, 0.5f, WATER_FROZEN, FOG_FROZEN);
        r.seed("minecraft:mushroom_fields", "BIOME_MUSHROOM_ISLAND", BiomeCategory.CAT_MUSHROOM, BiomePrecipitation.PPT_RAIN, 0.9f, 1.0f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:beach", "BIOME_BEACH", BiomeCategory.CAT_BEACH, BiomePrecipitation.PPT_RAIN, 0.8f, 0.4f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:snowy_beach", "BIOME_SNOWY_BEACH", BiomeCategory.CAT_BEACH, BiomePrecipitation.PPT_SNOW, 0.05f, 0.3f, WATER_COLD, FOG_FROZEN);
        r.seed("minecraft:jungle", "BIOME_JUNGLE", BiomeCategory.CAT_JUNGLE, BiomePrecipitation.PPT_RAIN, 0.95f, 0.9f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:sparse_jungle", "BIOME_JUNGLE_EDGE", BiomeCategory.CAT_JUNGLE, BiomePrecipitation.PPT_RAIN, 0.95f, 0.8f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:deep_ocean", "BIOME_DEEP_OCEAN", BiomeCategory.CAT_OCEAN, BiomePrecipitation.PPT_RAIN, 0.5f, 0.5f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:stony_shore", "BIOME_STONE_BEACH", BiomeCategory.CAT_BEACH, BiomePrecipitation.PPT_RAIN, 0.2f, 0.3f, WATER_COLD, FOG_DEFAULT);
        r.seed("minecraft:snowy_taiga", "BIOME_SNOWY_TAIGA", BiomeCategory.CAT_TAIGA, BiomePrecipitation.PPT_SNOW, -0.5f, 0.4f, WATER_COLD, FOG_FROZEN);
        r.seed("minecraft:old_growth_pine_taiga", "BIOME_MEGA_TAIGA", BiomeCategory.CAT_TAIGA, BiomePrecipitation.PPT_RAIN, 0.3f, 0.8f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:old_growth_spruce_taiga", "BIOME_MEGA_SPRUCE_TAIGA", BiomeCategory.CAT_TAIGA, BiomePrecipitation.PPT_RAIN, 0.25f, 0.8f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:windswept_forest", "BIOME_EXTREME_HILLS_FOREST", BiomeCategory.CAT_EXTREME_HILLS, BiomePrecipitation.PPT_RAIN, 0.2f, 0.3f, WATER_COLD, FOG_DEFAULT);
        r.seed("minecraft:savanna", "BIOME_SAVANNA", BiomeCategory.CAT_SAVANNA, BiomePrecipitation.PPT_NONE, 1.2f, 0.0f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:savanna_plateau", "BIOME_SAVANNA_PLATEAU", BiomeCategory.CAT_SAVANNA, BiomePrecipitation.PPT_NONE, 1.0f, 0.0f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:badlands", "BIOME_MESA", BiomeCategory.CAT_MESA, BiomePrecipitation.PPT_NONE, 2.0f, 0.0f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:wooded_badlands", "BIOME_MESA_FOREST", BiomeCategory.CAT_MESA, BiomePrecipitation.PPT_NONE, 2.0f, 0.0f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:eroded_badlands", "BIOME_MESA_PLATEAU", BiomeCategory.CAT_MESA, BiomePrecipitation.PPT_NONE, 2.0f, 0.0f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:warm_ocean", "BIOME_WARM_OCEAN", BiomeCategory.CAT_OCEAN, BiomePrecipitation.PPT_RAIN, 0.5f, 0.5f, WATER_WARM, FOG_WARM);
        r.seed("minecraft:lukewarm_ocean", "BIOME_LUKEWARM_OCEAN", BiomeCategory.CAT_OCEAN, BiomePrecipitation.PPT_RAIN, 0.5f, 0.5f, WATER_LUKEWARM, FOG_WARM);
        r.seed("minecraft:cold_ocean", "BIOME_COLD_OCEAN", BiomeCategory.CAT_OCEAN, BiomePrecipitation.PPT_RAIN, 0.5f, 0.5f, WATER_COLD, FOG_DEFAULT);
        r.seed("minecraft:deep_warm_ocean", "BIOME_DEEP_WARM_OCEAN", BiomeCategory.CAT_OCEAN, BiomePrecipitation.PPT_RAIN, 0.5f, 0.5f, WATER_WARM, FOG_WARM);
        r.seed("minecraft:deep_lukewarm_ocean", "BIOME_DEEP_LUKEWARM_OCEAN", BiomeCategory.CAT_OCEAN, BiomePrecipitation.PPT_RAIN, 0.5f, 0.5f, WATER_LUKEWARM, FOG_WARM);
        r.seed("minecraft:deep_cold_ocean", "BIOME_DEEP_COLD_OCEAN", BiomeCategory.CAT_OCEAN, BiomePrecipitation.PPT_RAIN, 0.5f, 0.5f, WATER_COLD, FOG_DEFAULT);
        r.seed("minecraft:deep_frozen_ocean", "BIOME_DEEP_FROZEN_OCEAN", BiomeCategory.CAT_OCEAN, BiomePrecipitation.PPT_SNOW, 0.5f, 0.5f, WATER_FROZEN, FOG_FROZEN);
        r.seed("minecraft:dark_forest", "BIOME_DARK_FOREST", BiomeCategory.CAT_FOREST, BiomePrecipitation.PPT_RAIN, 0.7f, 0.8f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:meadow", "BIOME_MEADOW", BiomeCategory.CAT_PLAINS, BiomePrecipitation.PPT_RAIN, 0.5f, 0.8f, WATER_MEADOW, FOG_DEFAULT);
        r.seed("minecraft:cherry_grove", "BIOME_CHERRY_GROVE", BiomeCategory.CAT_FOREST, BiomePrecipitation.PPT_RAIN, 0.5f, 0.8f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:soul_sand_valley", "BIOME_SOUL_SAND_VALLEY", BiomeCategory.CAT_NETHER, BiomePrecipitation.PPT_NONE, 2.0f, 0.0f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:crimson_forest", "BIOME_CRIMSON_FOREST", BiomeCategory.CAT_NETHER, BiomePrecipitation.PPT_NONE, 2.0f, 0.0f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:warped_forest", "BIOME_WARPED_FOREST", BiomeCategory.CAT_NETHER, BiomePrecipitation.PPT_NONE, 2.0f, 0.0f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:basalt_deltas", "BIOME_BASALT_DELTAS", BiomeCategory.CAT_NETHER, BiomePrecipitation.PPT_NONE, 2.0f, 0.0f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:small_end_islands", "BIOME_SMALL_END_ISLANDS", BiomeCategory.CAT_THE_END, BiomePrecipitation.PPT_NONE, 0.5f, 0.5f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:end_midlands", "BIOME_END_MIDLANDS", BiomeCategory.CAT_THE_END, BiomePrecipitation.PPT_NONE, 0.5f, 0.5f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:end_highlands", "BIOME_END_HIGHLANDS", BiomeCategory.CAT_THE_END, BiomePrecipitation.PPT_NONE, 0.5f, 0.5f, WATER_DEFAULT, FOG_DEFAULT);
        r.seed("minecraft:end_barrens", "BIOME_END_BARRENS", BiomeCategory.CAT_THE_END, BiomePrecipitation.PPT_NONE, 0.5f, 0.5f, WATER_DEFAULT, FOG_DEFAULT);
        return r;
    }

    private void seed(String key, String constant, BiomeCategory cat, BiomePrecipitation ppt,
                      float temp, float downfall, float[] water, float[] waterFog) {
        BiomeRecord rec = new BiomeRecord(byId.size(), key, constant, cat, ppt, temp, downfall, water);
        rec.fog(waterFog);
        add(rec);
    }

    /** Appends a record, assigning the next id when it does not carry one. */
    public BiomeRecord add(BiomeRecord record) {
        int id = record.id < 0 ? byId.size() : record.id;
        if (id >= capacity) {
            throw new IllegalStateException("biome id " + id + " exceeds registry capacity " + capacity
                    + "; raise BiomeRegistry.capacity before seeding");
        }
        while (byId.size() < id) byId.add(null);
        if (id == byId.size()) byId.add(record);
        else byId.set(id, record);
        byConstant.put(record.constantName, record);
        byKey.put(record.registryKey, record);
        return record;
    }

    public BiomeRecord byId(int id) {
        if (id < 0 || id >= byId.size()) return null;
        return byId.get(id);
    }

    public BiomeRecord byConstant(String constant) { return byConstant.get(constant); }
    public BiomeRecord byKey(String registryKey) { return byKey.get(registryKey); }

    /** Resolves a {@code BIOME_*} constant to its integer id, -1 when unknown. */
    public int constantId(String constant) {
        BiomeRecord r = byConstant.get(constant);
        return r == null ? -1 : r.id;
    }

    public int count() {
        int n = 0;
        for (BiomeRecord r : byId) if (r != null) n++;
        return n;
    }

    public int highestId() { return byId.size() - 1; }
    public int capacity() { return capacity; }
    public BiomeRegistry capacity(int c) { this.capacity = Math.max(byId.size(), c); return this; }

    public List<BiomeRecord> records() {
        List<BiomeRecord> out = new ArrayList<BiomeRecord>();
        for (BiomeRecord r : byId) if (r != null) out.add(r);
        return Collections.unmodifiableList(out);
    }

    /**
     * Binds every {@code BIOME_*}, {@code CAT_*} and {@code PPT_*} constant into
     * an expression environment.
     *
     * <p>Iris predefines these for custom-uniform expressions; a LEGACY pack
     * that writes {@code in(biome, BIOME_SWAMP)} cannot be evaluated without
     * them, and evaluating it is what makes the baked biome field possible.
     */
    public int bindConstants(dev.auralite.render.uniform.ExprEnv env) {
        int n = 0;
        for (BiomeRecord r : byId) {
            if (r == null) continue;
            env.bindInt(r.constantName, r.id);
            n++;
        }
        for (BiomeCategory c : BiomeCategory.values()) { env.bindInt(c.name(), c.id()); n++; }
        for (BiomePrecipitation p : BiomePrecipitation.values()) { env.bindInt(p.name(), p.id()); n++; }
        return n;
    }

    /** Fallback used when a cell holds an id this registry has never seen. */
    public BiomeRecord fallback() {
        BiomeRecord r = byConstant.get("BIOME_PLAINS");
        return r != null ? r : (byId.isEmpty() ? null : byId.get(0));
    }
}
