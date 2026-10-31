package dev.auralite.render.tests;

import dev.auralite.render.biome.BiomeBake;
import dev.auralite.render.biome.BiomeCategory;
import dev.auralite.render.biome.BiomeField;
import dev.auralite.render.biome.BiomeFieldBuilder;
import dev.auralite.render.biome.BiomeFieldPacker;
import dev.auralite.render.biome.BiomeLut;
import dev.auralite.render.biome.BiomePrecipitation;
import dev.auralite.render.biome.BiomeProperty;
import dev.auralite.render.biome.BiomeRecord;
import dev.auralite.render.biome.BiomeRegistry;
import dev.auralite.render.biome.BiomeSample;
import dev.auralite.render.biome.BiomeSampler;
import dev.auralite.render.biome.DirtyRegion;
import dev.auralite.render.uniform.CustomUniformTable;
import dev.auralite.render.uniform.ExprEnv;
import dev.auralite.render.uniform.FunctionTable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dev.auralite.render.tests.TestRig.check;
import static dev.auralite.render.tests.TestRig.eq;
import static dev.auralite.render.tests.TestRig.near;
import static dev.auralite.render.tests.TestRig.negativeControl;
import static dev.auralite.render.tests.TestRig.notNull;
import static dev.auralite.render.tests.TestRig.receipt;
import static dev.auralite.render.tests.TestRig.section;
import static dev.auralite.render.tests.TestRig.suite;
import static dev.auralite.render.tests.TestRig.throwsNamed;

/**
 * B-lane gate, and the reason this renderer exists.
 *
 * <p>Iris answers "which biome is here?" with one integer sampled at the camera.
 * Every assertion below is about the difference: a field of ids, a sampler that
 * returns weighted contributors, a LUT the shader can index, a packer that
 * uploads it, and a bake that turns an <b>unmodified</b> legacy expression into
 * a spatially varying volume.
 */
public final class BiomeSuite {
    private BiomeSuite() { }

    /**
     * A field with a swamp/plains border at cell x=4, i.e. world x=16.
     * Cell centres therefore sit at world x = 14 (last swamp) and 18 (first plains).
     */
    static BiomeField borderField(BiomeRegistry reg) {
        BiomeField f = new BiomeField();
        f.allocate(8, 4, 8);
        int swamp = reg.constantId("BIOME_SWAMP");
        int plains = reg.constantId("BIOME_PLAINS");
        for (int z = 0; z < 8; z++) {
            for (int y = 0; y < 4; y++) {
                for (int x = 0; x < 8; x++) {
                    f.setIdAtCell(f.originCX() + x, f.originCY() + y, f.originCZ() + z,
                            x < 4 ? swamp : plains);
                }
            }
        }
        return f;
    }

    public static void run() {
        suite("biome");
        BiomeRegistry reg = BiomeRegistry.vanillaDefaults();

        section("registry contract");
        eq(reg.count(), 48, "the built-in table seeds 48 biomes");
        eq(reg.constantId("BIOME_OCEAN"), 0, "BIOME_OCEAN is id 0 -- registration order is the contract");
        eq(reg.constantId("BIOME_PLAINS"), 1, "BIOME_PLAINS is id 1");
        eq(reg.constantId("BIOME_SWAMP"), 6, "BIOME_SWAMP is id 6");
        eq(reg.constantId("BIOME_NOT_A_BIOME"), -1, "an unknown constant resolves to -1, not a guess");
        eq(BiomeCategory.CAT_OCEAN.id(), 11, "CAT_OCEAN keeps its legacy ordinal");
        eq(BiomeCategory.CAT_SWAMP.id(), 14, "CAT_SWAMP keeps its legacy ordinal");
        eq(BiomeCategory.CAT_NETHER.id(), 16, "CAT_NETHER keeps its legacy ordinal");
        eq(BiomePrecipitation.PPT_SNOW.id(), 2, "PPT_SNOW is 2");
        eq(BiomeCategory.byId(99), BiomeCategory.CAT_NONE, "an out-of-range category id falls back to CAT_NONE");
        BiomeRecord swamp = reg.byId(reg.constantId("BIOME_SWAMP"));
        notNull(swamp, "the swamp record exists");
        near(swamp.waterColor[0], 0.380, 1e-6, "swamp water red matches AuraLite-Shaders v1.2.0");
        near(swamp.waterColor[2], 0.392, 1e-6, "swamp water blue matches AuraLite-Shaders v1.2.0");
        eq(swamp.category, BiomeCategory.CAT_SWAMP, "swamp category");
        eq(swamp.precipitation, BiomePrecipitation.PPT_RAIN, "swamp precipitation");
        eq(reg.byKey("minecraft:mangrove_swamp").constantName, "BIOME_MANGROVE_SWAMP",
                "lookup by registry key works, which is how the native format names biomes");
        eq(reg.bindConstants(new ExprEnv(new FunctionTable())),
                48 + BiomeCategory.values().length + BiomePrecipitation.values().length,
                "bindConstants exposes every BIOME_, CAT_ and PPT_ constant");

        section("field");
        BiomeField f = new BiomeField();
        eq(f.cellSize(), BiomeField.DEFAULT_CELL_SIZE, "the default cell is 4 blocks, Minecraft's granularity");
        f.allocate(8, 4, 8);
        eq(f.cellCapacity(), 256, "an 8x4x8 window holds 256 cells");
        eq(f.unknownCount(), 256, "a fresh window is entirely unknown");
        eq(f.idAtWorld(2, 2, 2), BiomeField.UNKNOWN, "reading before ingesting yields UNKNOWN, not a guess");
        check(f.setIdAtWorld(5, 5, 5, 3), "writing a cell reports a change");
        check(!f.setIdAtWorld(5, 5, 5, 3), "rewriting the same id reports no change");
        eq(f.idAtWorld(5, 5, 5), 3, "the write is readable at the same world position");
        eq(f.idAtWorld(4, 5, 5), 3, "the whole 4-block cell shares one id");
        eq(f.idAtWorld(8, 5, 5), BiomeField.UNKNOWN, "the neighbouring cell is still unknown");
        eq(f.worldToCellX(-1), -1, "negative world coordinates map to negative cells");
        eq(f.worldToCellX(-4), -1, "cell division floors toward negative infinity");
        eq(f.worldToCellX(-5), -2, "cell division floors toward negative infinity, second case");
        check(!f.setIdAtCell(999, 0, 0, 1), "writing outside the window is refused, not grown");
        receipt("field: " + f);

        section("window movement carries data");
        BiomeField moving = new BiomeField();
        moving.allocate(8, 4, 8);
        int plainsId = reg.constantId("BIOME_PLAINS");
        for (int z = 0; z < 8; z++)
            for (int y = 0; y < 4; y++)
                for (int x = 4; x < 8; x++)
                    moving.setIdAtCell(x, y, z, plainsId);
        eq(moving.filledCells(), 128, "half the window is filled");
        moving.moveWindowTo(4, 0, 0);
        eq(moving.filledCells(), 128, "shifting the window by 4 cells keeps all 128 filled cells, "
                + "which sat at world x 16..31 and are still inside world x 16..47");
        eq(moving.idAtCell(4, 0, 0), plainsId, "the carried cell is readable at its old world coordinate");
        eq(moving.idAtCell(0, 0, 0), BiomeField.UNKNOWN, "the newly exposed cells are unknown, not stale");
        eq(moving.windowMoves(), 1L, "one window move was recorded");
        moving.moveWindowTo(4, 0, 0);
        eq(moving.windowMoves(), 1L, "a move to the same origin is a no-op");

        section("sampling a uniform region");
        BiomeField uniform = new BiomeField();
        uniform.allocate(4, 4, 4);
        for (int z = 0; z < 4; z++)
            for (int y = 0; y < 4; y++)
                for (int x = 0; x < 4; x++)
                    uniform.setIdAtCell(x, y, z, plainsId);
        BiomeSampler us = new BiomeSampler(uniform, reg);
        BiomeSample s0 = us.sample(6, 6, 6);
        eq(s0.count, 1, "a uniform region has exactly one contributor");
        near(s0.weights[0], 1.0, 1e-6, "the single contributor carries all the weight");
        eq(s0.dominantId(), plainsId, "the dominant biome is the plains");
        check(!s0.isMixed(), "a uniform region is not mixed");

        section("sampling across a border -- the whole point");
        BiomeField bf = borderField(reg);
        int swampId = reg.constantId("BIOME_SWAMP");
        BiomeSampler bs = new BiomeSampler(bf, reg);

        BiomeSample deepSwamp = bs.sample(6, 6, 6);
        eq(deepSwamp.dominantId(), swampId, "well inside the swamp the dominant biome is the swamp");
        near(deepSwamp.dominantWeight(), 1.0, 1e-6, "well inside, one biome carries all the weight");

        BiomeSample deepPlains = bs.sample(26, 6, 6);
        eq(deepPlains.dominantId(), plainsId, "well inside the plains the dominant biome is the plains");

        // World x=16 is exactly halfway between the last swamp cell centre (14)
        // and the first plains cell centre (18).
        BiomeSample seam = bs.sample(16, 6, 6);
        eq(seam.count, 2, "on the seam two biomes contribute");
        check(seam.isMixed(), "the seam sample reports itself as mixed");
        double sum = 0;
        for (int i = 0; i < seam.count; i++) sum += seam.weights[i];
        near(sum, 1.0, 1e-5, "the weights sum to 1 after renormalisation");
        near(seam.weightOf(swampId), 0.5, 1e-5, "at the midpoint the swamp carries half the weight");
        near(seam.weightOf(plainsId), 0.5, 1e-5, "at the midpoint the plains carry half the weight");

        BiomeSample leaning = bs.sample(15, 6, 6);
        check(leaning.weightOf(swampId) > 0.5, "one block toward the swamp tips the weight");
        eq(leaning.dominantId(), swampId, "and the dominant biome follows");

        section("water colour varies across one frame");
        // Five positions in a single draw call. Iris would return one value for
        // all five, because its biome uniform is sampled at the camera.
        double[] xs = { 6, 13, 15, 16, 17, 19, 26 };
        Set<String> distinct = new LinkedHashSet<String>();
        StringBuilder trace = new StringBuilder();
        for (double x : xs) {
            BiomeSample s = bs.sample(x, 6, 6);
            float[] wc = s.waterColor();
            String key = String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", wc[0], wc[1], wc[2]);
            distinct.add(key);
            trace.append(String.format(java.util.Locale.ROOT,
                    "    x=%5.1f  water=(%.3f, %.3f, %.3f)  contributors=%d  dominant=%s%n",
                    x, wc[0], wc[1], wc[2], s.count,
                    s.dominant() == null ? "?" : s.dominant().constantName));
        }
        receipt("water colour across a swamp|plains border in ONE frame:\n" + trace.toString().trim());
        eq(distinct.size(), 5, "seven positions across the border produce five distinct water colours: "
                + "two pure biome values and three intermediate ones no single-biome API can express");
        int intermediate = 0;
        for (double x : new double[] { 15, 16, 17 }) {
            float[] wc = bs.sample(x, 6, 6).waterColor();
            if (wc[0] > Math.min(swamp.waterColor[0], reg.byId(plainsId).waterColor[0])
                    && wc[0] < Math.max(swamp.waterColor[0], reg.byId(plainsId).waterColor[0])) {
                intermediate++;
            }
        }
        eq(intermediate, 3, "all three positions inside the seam carry a blended, in-between colour");
        float[] swampWater = bs.sample(6, 6, 6).waterColor();
        near(swampWater[0], swamp.waterColor[0], 1e-5, "far from the seam the colour is exactly the swamp's");
        float[] plainsWater = bs.sample(26, 6, 6).waterColor();
        BiomeRecord plainsRec = reg.byId(plainsId);
        near(plainsWater[0], plainsRec.waterColor[0], 1e-5, "far from the seam the colour is exactly the plains'");
        float[] seamWater = bs.sample(16, 6, 6).waterColor();
        near(seamWater[0], (swamp.waterColor[0] + plainsRec.waterColor[0]) / 2.0, 1e-5,
                "on the seam the colour is the weighted midpoint, not a snap");
        check(seamWater[0] > Math.min(swampWater[0], plainsWater[0])
                && seamWater[0] < Math.max(swampWater[0], plainsWater[0]),
                "the seam colour lies strictly between the two biomes");

        section("labels are not blended, quantities are");
        BiomeSample mixed = bs.sample(16, 6, 6);
        near(mixed.temperature(),
                (swamp.temperature + plainsRec.temperature) / 2.0, 1e-5,
                "temperature is a quantity, so it blends");
        eq(mixed.blend(BiomeProperty.PRECIPITATION),
                (float) mixed.dominant().precipitation.id(),
                "precipitation is a label, so it comes from the dominant biome");
        eq(mixed.blend(BiomeProperty.CATEGORY), (float) mixed.dominant().category.id(),
                "category is a label, so it comes from the dominant biome");
        check(BiomeProperty.WATER_COLOR.isBlendable(), "WATER_COLOR is declared blendable");
        check(!BiomeProperty.CATEGORY.isBlendable(), "CATEGORY is declared non-blendable");
        check(BiomeProperty.TEMPERATURE.isBlendable(), "TEMPERATURE is declared blendable");

        section("empty samples fall back instead of guessing");
        BiomeField empty = new BiomeField();
        empty.allocate(4, 4, 4);
        BiomeSample none = new BiomeSampler(empty, reg).sample(2, 2, 2);
        eq(none.count, 0, "an uningested field produces an empty sample");
        eq(none.dominantId(), -1, "and the dominant id is -1, not 0");
        float[] fb = none.waterColor();
        BiomeRecord fallback = reg.fallback();
        near(fb[0], fallback.waterColor[0], 1e-6, "the blended property falls back to the registry default");

        section("the seam is exactly one biome cell wide");
        // Cell centres: last swamp at world x=14, first plains at world x=18.
        eq(bs.sample(14, 6, 6).count, 1, "at the last swamp cell centre only the swamp contributes");
        eq(bs.sample(18, 6, 6).count, 1, "at the first plains cell centre only the plains contribute");
        eq(bs.sample(15, 6, 6).count, 2, "one block off that centre, both contribute");
        eq(bs.sample(13, 6, 6).count, 1, "one block the other way, only the swamp contributes");
        eq(bs.sample(12, 6, 6).count, 1, "two cells from the border the other biome is out of kernel range");
        receipt("seam: pure swamp at x<=14, blended 14<x<18, pure plains at x>=18 "
                + "(one 4-block biome cell, which is Minecraft's own granularity)");
        receipt("widening a seam is a compute blur over the baked volume (BIOME_PLAN B5), "
                + "not a wider per-fragment CPU kernel");

        section("lookup table layout");
        BiomeLut lut = BiomeLut.build(reg);
        eq(lut.width(), 64, "48 biomes pad to a 64-texel-wide table");
        eq(lut.height(), BiomeLut.ROWS, "the table has 5 rows");
        eq(lut.format(), dev.auralite.render.rhi.GfxFormat.RGBA32F, "the table is RGBA32F");
        eq(lut.sizeBytes(), 64L * 5 * 4 * 4, "the table is 64x5 RGBA32F = 5120 bytes");
        float[] lutSwamp = lut.readColor(swampId, BiomeLut.ROW_WATER_COLOR);
        near(lutSwamp[0], swamp.waterColor[0], 1e-6, "row 0 holds the water colour");
        near(lut.get(swampId, BiomeLut.ROW_WATER_FOG, 3), swamp.fogDensityScale, 1e-6,
                "row 1 alpha holds the fog density scale");
        near(lut.get(swampId, BiomeLut.ROW_FOLIAGE, 3), swamp.temperature, 1e-6,
                "row 2 alpha holds the temperature");
        near(lut.get(swampId, BiomeLut.ROW_GRASS, 3), swamp.downfall, 1e-6,
                "row 3 alpha holds the downfall");
        eq((int) lut.get(swampId, BiomeLut.ROW_META, 0), swampId, "row 4 red holds the biome id");
        eq((int) lut.get(swampId, BiomeLut.ROW_META, 1), BiomeCategory.CAT_SWAMP.id(),
                "row 4 green holds the category");
        eq((int) lut.get(swampId, BiomeLut.ROW_META, 2), BiomePrecipitation.PPT_RAIN.id(),
                "row 4 blue holds the precipitation");
        near(lut.get(999, BiomeLut.ROW_META, 0), 0.0, 1e-9, "reading past the table yields 0, not a crash");
        receipt("lut: " + lut);

        section("packer, ID_R32UI layout");
        BiomeFieldPacker r32 = new BiomeFieldPacker(bf, BiomeFieldPacker.Layout.ID_R32UI, reg);
        int[] ids = new int[bf.cellCapacity()];
        r32.packIds(ids);
        eq(ids.length, 256, "one texel per cell");
        eq(ids[bf.idx(0, 0, 0)], swampId, "cell (0,0,0) packs the swamp id");
        eq(ids[bf.idx(7, 0, 0)], plainsId, "cell (7,0,0) packs the plains id");
        eq(r32.idBytes(), 1024L, "256 cells at 4 bytes");
        eq(r32.weightBytes(), 0L, "the R32UI layout has no weight texture");
        eq(r32.idFormat(), dev.auralite.render.rhi.GfxFormat.R32_UINT, "the R32UI layout is R32_UINT");
        BiomeField sparse = new BiomeField();
        sparse.allocate(2, 2, 2);
        sparse.setIdAtCell(0, 0, 0, 5);
        int[] sparseIds = new int[8];
        new BiomeFieldPacker(sparse, BiomeFieldPacker.Layout.ID_R32UI, reg).packIds(sparseIds);
        eq(sparseIds[0], 5, "the filled cell packs its id");
        eq(sparseIds[1], BiomeFieldPacker.UNKNOWN_TEXEL, "an unknown cell packs the sentinel 0xFFFFFFFF");

        section("packer, TOPK_RGBA8UI layout");
        BiomeFieldPacker topk = new BiomeFieldPacker(bf, BiomeFieldPacker.Layout.TOPK_RGBA8UI, reg);
        int[] packed = new int[bf.cellCapacity()];
        topk.packIds(packed);
        int deep = packed[bf.idx(0, 0, 0)];
        eq(deep & 0xFF, swampId, "TOPK byte 0 is the dominant id");
        eq((deep >> 8) & 0xFF, BiomeFieldPacker.UNKNOWN_TOPK, "TOPK byte 1 is the sentinel when only one biome contributes");
        int[] weights = new int[bf.cellCapacity()];
        topk.packWeights(weights);
        eq(weights[bf.idx(0, 0, 0)] & 0xFF, 255, "a single contributor takes the full 255");
        eq(topk.idFormat(), dev.auralite.render.rhi.GfxFormat.RGBA8_UINT, "the TOPK layout is RGBA8_UINT");
        eq(topk.weightBytes(), 1024L, "TOPK adds a 4-byte weight texture");
        // The weight bytes must sum to 255 wherever anything is known.
        int sumSeam = 0;
        for (int k = 0; k < 4; k++) sumSeam += (weights[bf.idx(4, 0, 0)] >> (k * 8)) & 0xFF;
        eq(sumSeam, 255, "quantised weights sum to 255, so the shader does not need to renormalise");

        section("TOPK refuses to truncate ids");
        final BiomeRegistry big = new BiomeRegistry();
        for (int i = 0; i < 300; i++) {
            big.add(new BiomeRecord(i, "mod:biome_" + i, "BIOME_MOD_" + i, BiomeCategory.CAT_NONE,
                    BiomePrecipitation.PPT_NONE, 0.5f, 0.5f, new float[] { 0.2f, 0.4f, 0.8f }));
        }
        final BiomeField bigField = new BiomeField();
        bigField.allocate(2, 2, 2);
        throwsNamed(new Runnable() { @Override public void run() {
            new BiomeFieldPacker(bigField, BiomeFieldPacker.Layout.TOPK_RGBA8UI, big);
        }}, "cannot represent biome id", "a registry too large for one byte per id is refused with the reason");
        check(new BiomeFieldPacker(bigField, BiomeFieldPacker.Layout.ID_R32UI, big) != null,
                "the same registry is fine on the R32UI layout");
        receipt("registry capacity: TOPK tops out at " + BiomeFieldPacker.MAX_TOPK_IDS
                + " ids; 300 modded biomes require ID_R32UI");

        section("dirty regions coalesce");
        List<DirtyRegion> cells = new ArrayList<DirtyRegion>();
        // One 16x16x16 chunk section arriving: 4x4x4 = 64 single-cell regions.
        for (int z = 0; z < 4; z++)
            for (int y = 0; y < 4; y++)
                for (int x = 0; x < 4; x++)
                    cells.add(DirtyRegion.cell(x, y, z));
        eq(cells.size(), 64, "a chunk section produces 64 single-cell regions");
        List<DirtyRegion> coalesced = DirtyRegion.coalesce(cells, 8, 1);
        eq(coalesced.size(), 1, "64 adjacent cells coalesce into one 4x4x4 box");
        eq(coalesced.get(0).sizeX, 4, "the box is 4 cells wide");
        eq(coalesced.get(0).volume(), 64L, "the box volume equals the sum of the cells");
        // Determinism: the same input twice must produce the same output.
        List<DirtyRegion> again = DirtyRegion.coalesce(cells, 8, 1);
        eq(again.size(), coalesced.size(), "coalescing is deterministic in count");
        eq(again.get(0).toString(), coalesced.get(0).toString(), "coalescing is deterministic in content");
        // A budget below the natural count forces larger boxes.
        List<DirtyRegion> far = new ArrayList<DirtyRegion>();
        far.add(DirtyRegion.cell(0, 0, 0));
        far.add(DirtyRegion.cell(100, 100, 100));
        far.add(DirtyRegion.cell(200, 200, 200));
        eq(DirtyRegion.coalesce(far, 8, 1).size(), 3, "far-apart cells are not merged");
        eq(DirtyRegion.coalesce(far, 2, 1).size(), 2, "a budget of 2 forces one merge");
        eq(DirtyRegion.coalesce(far, 1, 1).size(), 1, "a budget of 1 forces a single box");
        receipt("coalesce: 64 cells -> " + coalesced.get(0));

        section("sub-image clipping");
        List<int[]> boxes = r32.subImageBoxes(coalesced);
        eq(boxes.size(), 1, "the region lies inside the field, so one box survives");
        eq(boxes.get(0)[3], 4, "the box is 4 cells wide in the upload");
        eq(r32.bytesForBoxes(boxes), 256L, "64 cells at 4 bytes");
        List<DirtyRegion> outside = new ArrayList<DirtyRegion>();
        outside.add(DirtyRegion.cell(500, 500, 500));
        eq(r32.subImageBoxes(outside).size(), 0, "a region entirely outside the window uploads nothing");
        List<DirtyRegion> partial = new ArrayList<DirtyRegion>();
        partial.add(new DirtyRegion(6, 0, 0, 8, 4, 8));
        List<int[]> clipped = r32.subImageBoxes(partial);
        eq(clipped.size(), 1, "a region straddling the edge is clipped, not dropped");
        eq(clipped.get(0)[0] + clipped.get(0)[3], 8, "the clipped box ends at the window edge");

        section("bake: a legacy expression becomes a volume");
        // The chain is AuraLite-Shaders' own, unmodified.
        CustomUniformTable table = new CustomUniformTable();
        table.parseLine("variable.float.waterColorR = " + UniformSuite.WATER_R, "shaders.properties:1");
        table.parseLine("variable.float.waterColorG = " + UniformSuite.WATER_G, "shaders.properties:2");
        table.parseLine("variable.float.waterColorB = " + UniformSuite.WATER_B, "shaders.properties:3");
        table.parseLine("uniform.vec3.waterColor = vec3(waterColorR, waterColorG, waterColorB)",
                "shaders.properties:4");
        eq(table.size(), 4, "the chain parses into 4 definitions");
        check(BiomeBake.isBiomeDependent(table.find("waterColorR")), "waterColorR is detected as biome-dependent");

        ExprEnv env = new ExprEnv(new FunctionTable());
        reg.bindConstants(env);
        // Variables must be evaluated first so the uniform can see them.
        Map<String, dev.auralite.render.uniform.Value> scratch = new LinkedHashMap<String, dev.auralite.render.uniform.Value>();
        BiomeBake.Result bakedR = BiomeBake.bake(table.find("waterColorR"), bf, reg, env);
        BiomeBake.Result bakedG = BiomeBake.bake(table.find("waterColorG"), bf, reg, env);
        BiomeBake.Result bakedB = BiomeBake.bake(table.find("waterColorB"), bf, reg, env);
        eq(bakedR.cellsX, 8, "the volume matches the field width");
        eq(bakedR.cellsY, 4, "the volume matches the field height");
        eq(bakedR.cellsZ, 8, "the volume matches the field depth");
        eq(bakedR.evaluations, 256, "one evaluation per cell");
        eq(bakedR.unknownCells, 0, "no cell was unknown");
        eq(bakedR.sizeBytes(), 256L * 4 * 4, "the volume is 256 RGBA32F texels");
        near(bakedR.get(0, 0, 0, 0), 0.380, 1e-6, "cell (0,0,0) baked the SWAMP red");
        near(bakedR.get(7, 0, 0, 0), 0.247, 1e-6, "cell (7,0,0) baked the PLAINS red");
        near(bakedG.get(0, 0, 0, 0), 0.482, 1e-6, "cell (0,0,0) baked the SWAMP green");
        near(bakedB.get(7, 0, 0, 0), 0.894, 1e-6, "cell (7,0,0) baked the PLAINS blue");
        check(bakedR.warnings.isEmpty(), "no warnings for a chain that does not use smooth()");
        eq(scratch.size(), 0, "the bake did not leak state into the caller's map");

        // Count how many distinct values the baked volume holds: this is the
        // number Iris can express, which is exactly one.
        Set<String> bakedDistinct = new LinkedHashSet<String>();
        for (int z = 0; z < bakedR.cellsZ; z++)
            for (int y = 0; y < bakedR.cellsY; y++)
                for (int x = 0; x < bakedR.cellsX; x++)
                    bakedDistinct.add(String.format(java.util.Locale.ROOT, "%.4f/%.4f/%.4f",
                            bakedR.get(x, y, z, 0), bakedG.get(x, y, z, 0), bakedB.get(x, y, z, 0)));
        eq(bakedDistinct.size(), 2, "the baked volume holds 2 distinct water colours");
        receipt("bake: one unmodified legacy expression -> " + bakedDistinct.size()
                + " spatially distinct water colours in a 8x4x8 volume ("
                + bakedR.sizeBytes() + " B). Iris can express 1.");
        receipt(bakedR.receipt().trim().replace("\n", "\n  "));

        section("bake warns when it freezes temporal smoothing");
        CustomUniformTable smoothTable = new CustomUniformTable();
        smoothTable.parseLine("uniform.float.heat = smooth(heat, if(temperature > 1.0, 1.0, 0.0), 2, 0.25)",
                "shaders.properties:9");
        BiomeBake.Result bakedSmooth = BiomeBake.bake(smoothTable.find("heat"), bf, reg, env);
        eq(bakedSmooth.warnings.size(), 1, "freezing smooth() is reported, not done silently");
        TestRig.contains(bakedSmooth.warnings.get(0), "smooth()", "the warning names the function");
        TestRig.contains(bakedSmooth.warnings.get(0), "frozen", "the warning says what happened to it");

        section("ingest from chunk sections");
        BiomeField live = new BiomeField();
        BiomeFieldBuilder builder = new BiomeFieldBuilder(live);
        builder.sizeForRenderDistance(2, -64, 320);
        receipt("window for render distance 2: " + live.sizeCX() + "x" + live.sizeCY() + "x" + live.sizeCZ()
                + " cells = " + live.sizeBytes() + " B");
        BiomeFieldBuilder.SectionBiomes sec = new BiomeFieldBuilder.SectionBiomes(0, 4, 0);
        sec.fill(swampId);
        sec.valid = true;
        builder.recentre(8, 64, 8);
        check(builder.ingest(sec), "a section inside the window is accepted");
        eq(builder.ingestedSections(), 1L, "one section was ingested");
        eq(live.idAtWorld(2, 66, 2), swampId, "the ingested biome is readable at its world position");
        BiomeFieldBuilder.SectionBiomes farSection = new BiomeFieldBuilder.SectionBiomes(500, 4, 500);
        farSection.fill(plainsId);
        farSection.valid = true;
        builder.recentre(8, 64, 8);
        check(!builder.ingest(farSection), "a section outside the window is refused");
        eq(builder.ingestedSections(), 1L, "a refused section is not counted as accepted");
        BiomeFieldBuilder.SectionBiomes invalid = new BiomeFieldBuilder.SectionBiomes(0, 4, 0);
        invalid.valid = false;
        check(!builder.ingest(invalid), "an unloaded section is refused rather than ingested as UNKNOWN");
        eq(builder.rejectedSections(), 2L, "both refusals were counted, one per reason");
        eq(builder.cachedSectionCount(), 1, "a refused section is not cached either");
        builder.recentre(50000, 64, 50000);
        int evicted = builder.evictOutsideWindow();
        eq(evicted, 1, "moving the window far away makes the one cached section evictable");
        eq(builder.cachedSectionCount(), 0, "the cache is empty after eviction");
        TestRig.contains(builder.receipt(), "sections accepted", "the ingest receipt names its counts");
        receipt(builder.receipt());

        negativeControl("multi-biome gate is real", new Runnable() { @Override public void run() {
            // If the sampler were still returning one biome for the whole frame,
            // this would be true -- and the gate above would be proving nothing.
            BiomeRegistry r2 = BiomeRegistry.vanillaDefaults();
            BiomeField f2 = borderField(r2);
            BiomeSampler s2 = new BiomeSampler(f2, r2);
            if (s2.sample(6, 6, 6).waterColor()[0] != s2.sample(26, 6, 6).waterColor()[0]) {
                throw new IllegalStateException("negative control: two positions in one frame "
                        + "gave different water colours, as they must");
            }
        }});
    }
}
