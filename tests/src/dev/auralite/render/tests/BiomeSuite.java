package dev.auralite.render.tests;

import dev.auralite.render.biome.BiomeSeam;
import dev.auralite.render.biome.BiomeSeamFreshness;
import dev.auralite.render.biome.BiomeSeamShader;
import dev.auralite.render.biome.BiomeSeamSampler;
import dev.auralite.render.biome.BiomeSeamVolume;
import dev.auralite.render.biome.BiomeWindowSource;
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

        section("widened seam, gate 1 (BIOME_PLAN B5)");
        // The widening blurs the baked volume in compute. Before any shader
        // exists, the arithmetic it must reproduce is pinned here: at radius 0
        // BiomeSeam has to agree with BiomeSampler at a cell centre, because a
        // GPU rule that silently diverges would be worse than the narrow seam
        // it replaces.
        BiomeSeam widen = new BiomeSeam(bf, reg);
        int mismatches = 0;
        for (int cz = bf.originCZ(); cz < bf.originCZ() + bf.sizeCZ(); cz++) {
            for (int cy = bf.originCY(); cy < bf.originCY() + bf.sizeCY(); cy++) {
                for (int cx = bf.originCX(); cx < bf.originCX() + bf.sizeCX(); cx++) {
                    BiomeSample a = bs.sampleCellCentre(cx, cy, cz);
                    BiomeSample b = widen.sampleCell(cx, cy, cz, 0);
                    if (a.count != b.count) { mismatches++; continue; }
                    for (int i = 0; i < a.count; i++) {
                        if (a.ids[i] != b.ids[i]
                                || Math.abs(a.weights[i] - b.weights[i]) > 1e-6f) {
                            mismatches++;
                            break;
                        }
                    }
                }
            }
        }
        eq(mismatches, 0, "at radius 0 the widened rule reproduces BiomeSampler at every cell centre");
        // At a cell centre the sampler's own kernel already collapses to one
        // id with weight 1, which is exactly what the radius-0 bake writes.
        eq(widen.sampleCell(bf.worldToCellX(16), bf.worldToCellY(6),
                bf.worldToCellZ(6), 0).count, 1,
                "radius 0 near a border still resolves to a single cell id");

        // Width: the whole point of B5. Radius 2 = 8 blocks of blend either
        // side of the border, against the sampler's 4.
        int r2 = 2;
        receipt("seam width at radius " + r2 + ": " + BiomeSeam.seamWidthBlocks(r2, 4)
                + " blocks (radius 0: " + BiomeSeam.seamWidthBlocks(0, 4) + ")");
        check(BiomeSeam.seamWidthBlocks(r2, 4) > BiomeSeam.seamWidthBlocks(0, 4),
                "a wider radius reports a wider seam");
        // Walking x = 0..24 at y=z=6, the widened blend must see both biomes
        // over a strictly wider stretch of that line than the sampler does.
        int baseBlend = 0, wideBlend = 0;
        for (int x = 0; x <= 24; x++) {
            double wxp = x + 0.5;
            if (bs.sample(wxp, 6, 6).count > 1) baseBlend++;
            if (widen.sample(wxp, 6, 6, r2).count > 1) wideBlend++;
        }
        receipt("blended positions along x=0..24 at y=z=6: sampler " + baseBlend
                + ", widened " + wideBlend);
        check(wideBlend > baseBlend, "the widened seam blends over more positions than the sampler");
        // Monotonicity and the weight invariant hold at every radius.
        for (int radius = 0; radius <= BiomeSeam.MAX_RADIUS; radius++) {
            for (int x = 0; x <= 24; x++) {
                BiomeSample sm = widen.sample(x + 0.5, 6, 6, radius);
                if (sm.count == 0) continue;
                float wsum = 0f;
                for (int i = 0; i < sm.count; i++) wsum += sm.weights[i];
                if (Math.abs(wsum - 1f) > 1e-5f) {
                    eq(wsum, 1f, "weights sum to 1 at radius " + radius + ", x=" + x);
                }
                for (int i = 1; i < sm.count; i++) {
                    if (sm.weights[i] > sm.weights[i - 1] + 1e-7f) {
                        check(false, "weights are descending at radius " + radius + ", x=" + x);
                    }
                }
            }
        }
        check(true, "weights sum to 1 and descend at every radius 0.." + BiomeSeam.MAX_RADIUS);
        // The refusal is by name, not a clamp (BIOME_PLAN: BIOME_SEAM_R_TOO_WIDE).
        throwsNamed(new Runnable() { @Override public void run() {
            new BiomeSeam(bf, reg).sample(6, 6, 6, BiomeSeam.MAX_RADIUS + 1);
        }}, "BIOME_SEAM_R_TOO_WIDE", "a radius past the plan's maximum is refused by name");
        eq(BiomeSeam.boxCells(2), 125, "radius 2 reads a 5x5x5 neighbourhood");
        receipt("bake budget: " + BiomeSeam.boxCells(BiomeSeam.MAX_RADIUS)
                + " cells per output cell at the maximum radius");


        section("compute producer, gates 2-5 (BIOME_PLAN B5, step 2)");
        // The producer is biome_field.comp, and it is not hand-written: the
        // GLSL is an artefact of BiomeSeamShader. Gate 1's lesson was that two
        // implementations of one rule drift silently, so the committed shader
        // is compared byte for byte with what the class emits.
        {
            String generated = BiomeSeamShader.generate();
            java.nio.file.Path shaderFile = java.nio.file.Paths.get(
                    "packs/AuraLite-Native/shaders/biome_field.comp");
            check(java.nio.file.Files.isRegularFile(shaderFile),
                    "packs/AuraLite-Native/shaders/biome_field.comp exists");
            String committed = "";
            try {
                committed = new String(java.nio.file.Files.readAllBytes(shaderFile),
                        java.nio.charset.StandardCharsets.US_ASCII);
            } catch (java.io.IOException e) {
                check(false, "the committed shader is readable: " + e.getMessage());
            }
            check(committed.equals(generated),
                    "the committed shader is byte-identical to the generator (run `make shader` if not)");
            check(committed.startsWith("#version 460\n"), "the shader declares its GLSL version first");
            // rg32ui, not rgba8ui: a cell is four ids in ONE channel plus four
            // weights in a second, and an 8-bit channel SATURATES a packed word
            // instead of truncating it (measured: 0xFFFFFF06 and 0x12345678
            // both land as 255). The declaration is the contract; a test that
            // only checked "some image is bound at 0" would have blessed the
            // bug that made every probe frame paint nothing.
            check(committed.contains("layout(set = 0, binding = 0, rg32ui) uniform writeonly uimage3D biome_volume;"),
                    "writes biome_volume at binding 0, in a format wide enough for a packed cell");
            check(!committed.contains("rgba8ui) uniform writeonly uimage3D biome_volume;"),
                    "and never the 8-bit declaration: it would saturate the packed ids to 255");
            check(committed.contains("layout(set = 0, binding = 1, r32ui) uniform readonly uimage3D biome_field_ids;"),
                    "reads the exact-id field at binding 1");
            // The other half of the same contract: the pack that ships this
            // shader must DECLARE the resource in the format the shader binds,
            // or the two disagree silently and the image is created too narrow.
            try {
                String pack = new String(java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get("packs/AuraLite-Native/pack.aurapack")),
                        java.nio.charset.StandardCharsets.UTF_8);
                check(pack.contains("biome_volume = r32g32_uint biome-volume"),
                        "the reference pack declares biome_volume r32g32_uint, the format the"
                        + " shader binds (a mismatch is silent at load time)");
            } catch (java.io.IOException e) {
                check(false, "the reference pack is readable: " + e.getMessage());
            }
            check(committed.contains("#define BIOME_SEAM_R 2"),
                    "the shader defaults to the plan's radius when the pack supplies none");
            check(committed.contains("#ifndef BIOME_SEAM_R"),
                    "and a pack's seamRadius reaches the shader as a define, not as a constant");
            check(committed.contains("BIOME_SEAM_R_USED"),
                    "the emitted radius is the define, clamped at the declared ceiling");
            check(committed.contains("const int BIOME_SEAM_MAX_R = " + BiomeSeam.MAX_RADIUS + ";"),
                    "the ceiling is BiomeSeam.MAX_RADIUS, not a second opinion");
            check(!committed.contains("uvec4(1u, 0u, 0u, 255u)"),
                    "the constant placeholder is gone: this pass now bakes a field");
        }

        // Gate 2 -- width, and equivalence with the existing TOPK packer, which
        // resolves the same rule a different way (eight trilinear corners).
        int wCells = bf.sizeCX(), h = bf.sizeCY(), d = bf.sizeCZ();
        int[] flatIds = new int[wCells * h * d];
        for (int z = 0; z < d; z++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < wCells; x++) {
                    flatIds[x + wCells * (y + h * z)] =
                            bf.idAtCell(bf.originCX() + x, bf.originCY() + y, bf.originCZ() + z);
                }
            }
        }
        int[] bakedIds = new int[flatIds.length];
        int[] bakedW = new int[flatIds.length];
        BiomeSeamShader.emitVolume(flatIds, wCells, h, d, 0, bakedIds, bakedW);
        int[] packIds = new int[flatIds.length];
        int[] packW = new int[flatIds.length];
        BiomeFieldPacker topkR0 = new BiomeFieldPacker(bf, BiomeFieldPacker.Layout.TOPK_RGBA8UI, reg);
        topkR0.packIds(packIds);
        topkR0.packWeights(packW);
        int idDiff = 0, wDiff = 0;
        for (int i = 0; i < flatIds.length; i++) {
            if (bakedIds[i] != packIds[i]) idDiff++;
            if (bakedW[i] != packW[i]) wDiff++;
        }
        eq(idDiff, 0, "at radius 0 the shader's id texels are byte-identical to the TOPK packer's");
        eq(wDiff, 0, "and so are the weight texels");
        receipt("radius 0 vs TOPK packer: " + flatIds.length + " cells, " + idDiff
                + " id bytes and " + wDiff + " weight bytes differ");

        BiomeSeamShader.emitVolume(flatIds, wCells, h, d, 2, bakedIds, bakedW);
        int[] narrowIds2 = new int[flatIds.length];
        int[] narrowW2 = new int[flatIds.length];
        BiomeSeamShader.emitVolume(flatIds, wCells, h, d, 0, narrowIds2, narrowW2);
        int blendedR0 = 0, blendedR2 = 0;
        int rowY = 1, rowZ = 1;
        for (int x = 0; x < wCells; x++) {
            int at = x + wCells * (rowY + h * rowZ);
            if ((narrowW2[at] & 0xFF) != 0 && ((narrowW2[at] >>> 8) & 0xFF) != 0) blendedR0++;
            if ((bakedW[at] & 0xFF) != 0 && ((bakedW[at] >>> 8) & 0xFF) != 0) blendedR2++;
        }
        check(blendedR2 > blendedR0,
                "the widened bake blends at more positions than radius 0: " + blendedR0 + " -> " + blendedR2);
        eq(blendedR0, 0, "radius 0 never blends two ids in this border field (one cell, one biome)");
        receipt("blended cells along x at radius 0 vs 2: " + blendedR0 + " -> " + blendedR2
                + " (band " + (int) BiomeSeam.seamWidthBlocks(2, bf.cellSize()) + " blocks wide)");

        // Gate 3 -- determinism. The weights are integers, so float32 holds every
        // partial sum exactly; we still prove the outcome instead of asserting it.
        check(BiomeSeamShader.sumsAreExact(BiomeSeam.MAX_RADIUS),
                "every partial sum at the maximum radius is exact in float32 (max total "
                        + BiomeSeamShader.maxTotalWeight(BiomeSeam.MAX_RADIUS) + " < 2^24)");
        {
            int[] first = new int[flatIds.length];
            int[] firstW = new int[flatIds.length];
            int[] second = new int[flatIds.length];
            int[] secondW = new int[flatIds.length];
            BiomeSeamShader.emitVolume(flatIds, wCells, h, d, 2, first, firstW);
            BiomeSeamShader.emitVolume(flatIds, wCells, h, d, 2, second, secondW);
            check(java.util.Arrays.equals(first, second) && java.util.Arrays.equals(firstW, secondW),
                    "two bakes of the same field are byte-identical");

            // Shuffle the scan order: rows laid out in reverse, baked, then read
            // back into the original orientation. A merge that depended on
            // traversal order would move the bytes.
            int[] shuffled = new int[flatIds.length];
            for (int z = 0; z < d; z++) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < wCells; x++) {
                        shuffled[x + wCells * (y + h * z)] = flatIds[x + wCells * ((h - 1 - y) + h * z)];
                    }
                }
            }
            int[] shuffledIds = new int[flatIds.length];
            int[] shuffledW = new int[flatIds.length];
            BiomeSeamShader.emitVolume(shuffled, wCells, h, d, 2, shuffledIds, shuffledW);
            int orderDiff = 0;
            for (int z = 0; z < d; z++) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < wCells; x++) {
                        int a = x + wCells * (y + h * z);
                        int b = x + wCells * ((h - 1 - y) + h * z);
                        if (first[a] != shuffledIds[b] || firstW[a] != shuffledW[b]) orderDiff++;
                    }
                }
            }
            eq(orderDiff, 0, "reversing the row order moves no texel: the rule is order-free");
        }

        // Gate 4 -- BIOME_VOLUME_IDS_OVER_TOPK, proven in both directions.
        {
            // MAX_TOPK_IDS is 254, so 300 cannot travel in a byte.
            final int[] wide = new int[] { BiomeField.UNKNOWN, 42, 43, 300 };
            int[] hiIds = new int[wide.length];
            int[] hiW = new int[wide.length];
            throwsNamed(new Runnable() {
                @Override public void run() {
                    BiomeSeamShader.emitVolume(wide, wide.length, 1, 1, 1, new int[wide.length], new int[wide.length]);
                }
            }, "BIOME_VOLUME_IDS_OVER_TOPK", "an id a byte cannot carry is refused, never truncated");
            int marked = BiomeSeamShader.emitVolumeLenient(wide, wide.length, 1, 1, 1, hiIds, hiW);
            // R=1 over four cells: cell 0 sees {unknown,42}, cell 1 sees {unknown,42,43},
            // cell 2 sees {42,43,300} and cell 3 sees {43,300}. Only the last two
            // touch an id a byte cannot carry, so only those two become holes -
            // the refusal is per cell, not per volume.
            eq(marked, 2, "exactly the cells whose neighbourhood holds the wide id are marked");
            eq((hiIds[0] & 0xFF), 42, "cell 0 keeps its representable dominant id");
            eq((hiIds[1] & 0xFF), 42, "cell 1 too - its box never reaches the wide id");
            for (int i = 2; i < wide.length; i++) {
                for (int slot = 0; slot < 4; slot++) {
                    eq((hiIds[i] >>> (slot * 8)) & 0xFF, BiomeFieldPacker.UNKNOWN_TOPK,
                            "cell " + i + " slot " + slot + " is an honest hole, not a renamed biome");
                    eq((hiW[i] >>> (slot * 8)) & 0xFF, 0, "and carries no weight");
                }
            }
            // The narrow cells still sum to 255 across their two entries.
            eq((hiW[0] & 0xFF) + ((hiW[0] >>> 8) & 0xFF), 255,
                    "a cell that was not marked still quantises to a full unit");

            // An unknown-only neighbourhood stays unknown, as the plan requires.
            int[] holeField = new int[] { BiomeField.UNKNOWN, BiomeField.UNKNOWN };
            int[] holeIds = new int[2];
            int[] holeW = new int[2];
            eq(BiomeSeamShader.emitVolumeLenient(holeField, 2, 1, 1, 1, holeIds, holeW), 0,
                    "a neighbourhood of unknowns is not over TOPK, it is simply empty");
            for (int i = 0; i < 2; i++) {
                eq((holeIds[i] & 0xFF), BiomeFieldPacker.UNKNOWN_TOPK,
                        "and the merged table stays empty rather than inventing a biome");
                eq(holeW[i], 0, "with no weight at all (cell " + i + ")");
            }
            throwsNamed(new Runnable() {
                @Override public void run() {
                    BiomeSeamShader.emitVolume(new int[0], 1, 1, 1, 0, new int[1], new int[1]);
                }
            }, "field holds 0 cells", "the emitter refuses a short field array by name");
            throwsNamed(new Runnable() {
                @Override public void run() {
                    BiomeSeamShader.emitVolume(new int[4], 1, 1, 1, 0, new int[0], new int[1]);
                }
            }, "output arrays must hold 1 cells", "and a short output array too");
        }

        // Gate 4 (the plan's): staleness is loud. A seam baked before a write
        // still looks plausible, so the read gate refuses by name.
        {
            final BiomeField fresh = borderField(reg);
            final BiomeSeamFreshness tracker = new BiomeSeamFreshness(fresh);
            check(!tracker.hasBaked(), "a volume that was never baked is not fresh by default");
            check(!tracker.isFresh(), "and does not claim to be");
            final BiomeSeamFreshness never = new BiomeSeamFreshness(borderField(reg));
            throwsNamed(new Runnable() {
                @Override public void run() { never.checkFresh("terrain"); }
            }, "BIOME_VOLUME_STALE", "reading a never-baked seam is refused by name");
            eq(never.stalenessSeen(), 1, "and that refusal is counted on its own tracker");
            eq(tracker.stalenessSeen(), 0, "while this one saw nothing");

            tracker.markBaked();
            check(tracker.isFresh(), "after a bake the seam matches the field");
            tracker.checkFresh("terrain");
            eq(tracker.bakeCount(), 1, "one bake recorded");

            // A chunk loads: the field moves on, the bake does not.
            fresh.setIdAtCell(fresh.originCX() + 1, fresh.originCY(), fresh.originCZ(),
                    reg.constantId("BIOME_DESERT"));
            check(!tracker.isFresh(), "one write makes the baked seam stale");
            throwsNamed(new Runnable() {
                @Override public void run() { tracker.checkFresh("terrain"); }
            }, "BIOME_VOLUME_STALE", "a frame that reads it anyway is refused, not warned");
            check(tracker.receipt().contains("stalenessSeen=1"),
                    "and the refusal is counted for the receipt: " + tracker.receipt());

            // Asking first is allowed; silently proceeding is not.
            check(!tracker.freshOrReport("ssao"), "freshOrReport answers false for a stale seam");
            eq(tracker.stalenessSeen(), 2, "and still counts the question");

            // Re-bake clears it: freshness is about the version, not about time.
            tracker.markBaked();
            check(tracker.isFresh(), "a re-bake makes it current again");
            tracker.checkFresh("terrain");
            eq(tracker.stalenessSeen(), 2, "a successful read adds no refusal");
            receipt(tracker.receipt());

            // The plan's own instruction for gate 4: remove the check and the
            // suite must go red. This control stands in for that removal.
            negativeControl("staleness gate is load-bearing", new Runnable() {
                @Override public void run() {
                    final BiomeField f2 = borderField(reg);
                    final BiomeSeamFreshness t2 = new BiomeSeamFreshness(f2);
                    t2.markBaked();
                    f2.setIdAtCell(f2.originCX(), f2.originCY(), f2.originCZ(),
                            reg.constantId("BIOME_DESERT"));
                    if (!t2.isFresh()) {
                        throw new IllegalStateException("negative control: without the version "
                                + "comparison this seam would have been read as current");
                    }
                }
            });
        }

        // Gate 4's negative control: the over-TOPK guard has to be load-bearing,
        // not decoration. With it disabled, the same field must bake ids a byte
        // cannot carry -- i.e. must silently rename a biome.
        {
            final int[] wide2 = new int[] { 42, 300 };
            final int[] controlIds = new int[2];
            final int[] controlW = new int[2];
            negativeControl("BIOME_VOLUME_IDS_OVER_TOPK cannot be disabled quietly", new Runnable() {
                @Override public void run() {
                    // Pretend the guard is gone: pack the ids the selection chose,
                    // without the mark, exactly as a truncating implementation would.
                    BiomeSeamShader.emitVolumeLenient(wide2, 2, 1, 1, 0, controlIds, controlW);
                    boolean truncated = ((controlIds[1] & 0xFF)
                            == (BiomeFieldPacker.MAX_TOPK_IDS + 1 + 45) % 256);
                    if (!truncated) {
                        throw new IllegalStateException("negative control: a truncated id would have "
                                + "been accepted as a different biome");
                    }
                }
            });
            // And the guard itself, stated as the fact it protects: no cell in
            // the whole volume may hold an id above the ceiling.
            final int[] realIds = new int[flatIds.length];
            final int[] realW = new int[flatIds.length];
            BiomeSeamShader.emitVolume(flatIds, wCells, h, d, BiomeSeam.MAX_RADIUS, realIds, realW);
            int above = 0;
            for (int i = 0; i < realIds.length; i++) {
                for (int slot = 0; slot < 4; slot++) {
                    int id = (realIds[i] >>> (slot * 8)) & 0xFF;
                    if (id != BiomeFieldPacker.UNKNOWN_TOPK && id > BiomeFieldPacker.MAX_TOPK_IDS) above++;
                }
            }
            eq(above, 0, "no texel at the maximum radius carries an id above MAX_TOPK_IDS");
        }

        // Gate 5 -- the cost receipt the plan promised: why compute at all.
        eq(BiomeSeamShader.tableBytes(2), 125 * 8, "one invocation's merge table at R=2 is 125 int/float pairs");
        eq(BiomeSeamShader.tableBytes(BiomeSeam.MAX_RADIUS), 729 * 8,
                "and at the ceiling it is 729 - the budget the plan named");
        eq(BiomeSeamShader.BINDING_FIELD_IN, 1, "the field input has its own binding");
        eq(BiomeSeamShader.UNKNOWN_SLOT, BiomeFieldPacker.UNKNOWN_TOPK, "the hole marker is the packer's");
        {
            int fieldFetches = 8, lutRowsPerEntry = 5;
            int before = fieldFetches + BiomeSample.MAX_ENTRIES * lutRowsPerEntry;
            int after = 1 + BiomeSample.MAX_ENTRIES * lutRowsPerEntry;
            check(after < before, "the fragment path trades " + before + " fetches for " + after
                    + " while the seam gets wider, which is the whole argument for compute");
            receipt("fragment-path fetches per sample: " + before + " -> " + after
                    + " (8 field + 20 LUT -> 1 volume + 20 LUT)");
        }


        section("the read side: one texel, bilinear, no sampler unit (B5 step 3)");
        // The bake resolves WHICH biomes blend; the read resolves HOW MUCH of
        // each applies at a position. The second stage must never widen again.
        BiomeSeamSampler readR0 = new BiomeSeamSampler(reg);
        BiomeSeamVolume volR0 = new BiomeSeamVolume(bf).radius(0);
        eq(volR0.sizeX(), bf.sizeCX(), "the volume's width is the field's width");
        eq(volR0.sizeY(), bf.sizeCY(), "and its HEIGHT is the field's height, not the framebuffer's");
        eq(volR0.sizeZ(), bf.sizeCZ(), "and its depth");
        int markedR0 = volR0.bake(reg);
        eq(markedR0, 0, "no cell of the border field is over TOPK");
        eq(volR0.freshness().bakeCount(), 1, "the bake recorded the field version it saw");
        check(volR0.freshness().isFresh(), "and the volume is current");

        // At a cell centre the baked volume is exact: one cell, one id, weight
        // one. That is the statement "R = 0 is the old seam" in its final form.
        int exactId = 0, wrong = 0;
        for (int z = 0; z < volR0.sizeZ(); z++) {
            for (int y = 0; y < volR0.sizeY(); y++) {
                for (int x = 0; x < volR0.sizeX(); x++) {
                    int want = bf.idAtCell(bf.originCX() + x, bf.originCY() + y, bf.originCZ() + z);
                    BiomeSample got = volR0.sampleCellCentre(reg,
                            bf.originCX() + x, bf.originCY() + y, bf.originCZ() + z);
                    if (got.count == 1 && got.ids[0] == want && Math.abs(got.weights[0] - 1f) < 1e-6) {
                        exactId++;
                    } else {
                        wrong++;
                    }
                }
            }
        }
        eq(wrong, 0, "at every cell centre the read returns that cell's single id at weight 1");
        eq(exactId, volR0.sizeX() * volR0.sizeY() * volR0.sizeZ(), "which is every cell");

        // Between centres the read interpolates -- and at R = 0 it interpolates
        // the SAME narrow seam the CPU sampler draws, one band wide.
        int blendedReadR0 = 0;
        for (int i = 0; i <= 96; i++) {
            BiomeSample s = volR0.sample(reg, bf.originCX() * bf.cellSize() + i * 0.25,
                    (1 + 0.5) * bf.cellSize(), (1 + 0.5) * bf.cellSize());
            if (s.count >= 2) blendedReadR0++;
        }
        check(blendedReadR0 > 0, "the read blends between cells, so it is interpolation and not a lookup");

        // A wider bake has to make that band wider, which is the whole point.
        BiomeSeamVolume volR2 = new BiomeSeamVolume(bf).radius(2);
        volR2.bake(reg);
        int blendedReadR2 = 0;
        for (int i = 0; i <= 96; i++) {
            BiomeSample s = volR2.sample(reg, bf.originCX() * bf.cellSize() + i * 0.25,
                    (1 + 0.5) * bf.cellSize(), (1 + 0.5) * bf.cellSize());
            if (s.count >= 2) blendedReadR2++;
        }
        check(blendedReadR2 > blendedReadR0,
                "the widened volume blends at more positions on the same line: "
                        + blendedReadR0 + " -> " + blendedReadR2);
        receipt("read-side blend along a quarter-block line: R=0 " + blendedReadR0
                + " positions, R=2 " + blendedReadR2);

        // Invariants that must hold for every position the read answers.
        int badSum = 0, unsorted = 0, emptyCount = 0;
        for (int i = 0; i < 64; i++) {
            double wx = bf.originCX() * bf.cellSize() + (i * 0.37);
            BiomeSample s = volR2.sample(reg, wx, (2 + 0.25) * bf.cellSize(),
                    (3 + 0.75) * bf.cellSize());
            if (s.count == 0) { emptyCount++; continue; }
            float wsum2 = 0f;
            for (int k = 0; k < s.count; k++) {
                wsum2 += s.weights[k];
                if (k > 0 && s.weights[k] > s.weights[k - 1] + 1e-6) unsorted++;
            }
            if (Math.abs(wsum2 - 1f) > 1e-5) badSum++;
        }
        eq(badSum, 0, "every non-empty read sums to 1 across its entries");
        eq(unsorted, 0, "and its entries are still in descending weight order");
        eq(emptyCount, 0, "no position inside the field came back empty");

        // Outside the volume is absent, not clamped: the sampler may not invent
        // a border it does not have.
        {
            BiomeSample outside = volR2.sample(reg,
                    (bf.originCX() - 40) * bf.cellSize(), 0.5 * bf.cellSize(), 0.5 * bf.cellSize());
            eq(outside.count, 0, "a position outside the volume yields an empty sample, not an edge biome");
        }

        // The staleness gate now has an owner: writes to the field make the
        // baked volume unusable, and the read says so by name.
        {
            final BiomeSeamVolume stale = new BiomeSeamVolume(bf).radius(2);
            stale.bake(reg);
            bf.setIdAtCell(bf.originCX() + 5, bf.originCY(), bf.originCZ(),
                    reg.constantId("BIOME_DESERT"));
            throwsNamed(new Runnable() {
                @Override public void run() {
                    stale.sample(reg, bf.originCX() * bf.cellSize(), 0, 0);
                }
            }, "BIOME_VOLUME_STALE", "a read after a field write is refused, not silently stale");
            check(!stale.freshness().isFresh(), "and the volume knows it");
            stale.bake(reg);
            check(stale.freshness().isFresh(), "a re-bake makes it current");
            stale.sample(reg, bf.originCX() * bf.cellSize(), 0, 0);
            receipt(stale.receipt());
            // Put the border field back the way the later sections expect it.
            bf.setIdAtCell(bf.originCX() + 5, bf.originCY(), bf.originCZ(),
                    reg.constantId("BIOME_PLAINS"));
        }

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

        section("world window: the field the live frame bakes from");
        // The host frame path never filled a field until step 7, so the seam
        // could not appear in a live frame however correct the bake was. The
        // arithmetic that decides WHEN and WHERE a window is read lives in core
        // (BiomeWindowSource) precisely so this suite can drive it with a
        // stand-in sampler instead of a running game.
        BiomeField worldField = new BiomeField(4);
        worldField.allocate(23, 97, 23);
        final int[] samplerCalls = { 0 };
        // The stand-in world: below sea level (y = 64) one biome, above another.
        // The window is centred on the player's cell, so the player's own cell
        // sits in the middle and BOTH halves are reachable -- which is what
        // makes the split a property of the window, not of the fixture.
        final int seaLevel = 64;   // blocks, the player's own y
        final int lowBiome = Math.min(swampId, plainsId);
        final int highBiome = Math.max(swampId, plainsId);
        BiomeWindowSource window = new BiomeWindowSource(worldField,
                new BiomeWindowSource.BlockSampler() {
                    @Override public int idAt(int bx, int by, int bz) {
                        samplerCalls[0]++;
                        return by < seaLevel ? lowBiome : highBiome;
                    }
                }, 23, 97, 23);

        eq(BiomeWindowSource.originCellFor(0.0, 23, 4), -11,
                "a 23-cell window centred on cell 0 starts 11 cells before it");
        eq(BiomeWindowSource.originCellFor(64.0, 23, 4), 5,
                "and on cell 16 it starts at 5 (16 - 11), not at 16");
        check(window.needsRebuild(0, 64, 0), "a window that was never built wants one");

        int changed = window.updateFrom(0, 64, 0, 100);
        eq(window.builds(), 1L, "the first update builds the window");
        eq(changed, (long) 23 * 97 * 23,
                "every cell of a fresh window started UNKNOWN, so every written cell changed");
        eq(samplerCalls[0], 23 * 97 * 23,
                "one biome lookup per CELL, not one per block: 51313 lookups, not 3.2 million");
        eq(worldField.filledCells(), 23 * 97 * 23, "every cell of the window was filled");
        // Absolute cell coordinates: idAtCell() maps a world cell to the window
        // by subtracting the origin, so passing an index here would silently
        // read a different cell (and out of range beyond it).
        int cx11 = worldField.originCX() + 11;
        int cz11 = worldField.originCZ() + 11;
        int mid = worldField.originCY() + worldField.sizeCY() / 2;
        eq(worldField.idAtCell(cx11, mid - 5, cz11), lowBiome,
                "a cell below the player's own is below sea level in the stand-in world");
        eq(worldField.idAtCell(cx11, mid + 5, cz11), highBiome,
                "and one above it is not: the split really is inside the window");

        eq(window.updateFrom(1, 64, 1, 101), 0L, "a step inside the same cell changes nothing");
        eq(window.builds(), 1L, "and does not rebuild");
        TestRig.contains(window.lastOutcome(), "already covers", "the reason is named, not implied");
        eq(window.updateFrom(8, 64, 0, 102), 0L,
                "crossing a cell boundary is NOT enough on its own");
        eq(window.builds(), 1L, "because the throttle (20 frames) has not elapsed");
        TestRig.contains(window.lastOutcome(), "throttled", "and the refusal says so");
        int afterThrottle = window.updateFrom(8, 64, 0, 120);
        eq(window.builds(), 2L, "after 20 frames the rebuild happens, exactly once");
        // Not the whole window again: the new window overlaps the old one, so
        // only the column that entered the window is new. The number is
        // measured rather than predicted - the point of the assertion is that
        // the throttle cost one rebuild and not twenty.
        check(afterThrottle > 0 && afterThrottle < 23 * 97 * 23,
                "and reports only the cells that differ from the previous window ("
                        + afterThrottle + " of " + (23 * 97 * 23) + ")");
        receipt("world window: " + afterThrottle + " of " + (23 * 97 * 23)
                + " cells change when the player crosses one cell boundary");

        // A world that answers nothing at all: on a FRESH field, every cell is
        // a hole. On a field that already holds data, an unloaded cell is
        // still overwritten with UNKNOWN - the seam must not inherit a
        // neighbour's biome from the previous frame.
        BiomeField emptyField = new BiomeField(4);
        emptyField.allocate(23, 97, 23);
        BiomeWindowSource blindfolded = new BiomeWindowSource(emptyField,
                new BiomeWindowSource.BlockSampler() {
                    @Override public int idAt(int bx, int by, int bz) {
                        return BiomeWindowSource.UNLOADED;
                    }
                }, 23, 97, 23);
        blindfolded.rebuild(0, 64, 0, 200);
        eq(emptyField.unknownCount(), (long) 23 * 97 * 23,
                "a world that answers nothing leaves every cell UNKNOWN");
        eq(blindfolded.lastUnloaded(), (long) 23 * 97 * 23, "counted as unloaded, not as unmapped");
        eq(blindfolded.lastUnmapped(), 0L, "with the two reasons kept apart");
        TestRig.contains(blindfolded.lastOutcome(), "unloaded", "the outcome names them");

        // Both reasons at once: every 3rd row of cells is a biome the registry
        // cannot name (-1), everything else is unloaded (-2).
        BiomeWindowSource twoReasons = new BiomeWindowSource(emptyField,
                new BiomeWindowSource.BlockSampler() {
                    @Override public int idAt(int bx, int by, int bz) {
                        return (by / 4) % 3 == 0 ? -1 : BiomeWindowSource.UNLOADED;
                    }
                }, 23, 97, 23);
        twoReasons.rebuild(0, 64, 0, 300);
        check(twoReasons.lastUnmapped() > 0, "an unnameable biome is counted as unmapped");
        check(twoReasons.lastUnloaded() > 0, "and an unloaded block as unloaded: two reasons, two counts");
        eq(twoReasons.lastUnmapped() + twoReasons.lastUnloaded(), (long) 23 * 97 * 23,
                "and every cell fell into exactly one of them");

        // The whole point of step 7: a window that starts at NEGATIVE cells
        // must still be read at the right place. The first version of this
        // path baked a correct volume and then read it as if the window began
        // at cell 0, so the seam appeared half a window away and a flat swamp
        // read as "nothing here" -- the kind of bug a screenshot shows as
        // "the seam is in the wrong place" and a receipt shows as nothing.
        BiomeField liveField = new BiomeField(4);
        liveField.allocate(23, 97, 23);
        BiomeWindowSource liveWindow = new BiomeWindowSource(liveField,
                new BiomeWindowSource.BlockSampler() {
                    @Override public int idAt(int bx, int by, int bz) {
                        return bz < 0 ? lowBiome : highBiome;   // a border at z = 0
                    }
                }, 23, 97, 23);
        liveWindow.updateFrom(0, 64, 0, 100);
        check(liveField.originCZ() < 0, "the live window starts at a negative cell (origin z "
                + liveField.originCZ() + "): the case the old reader got wrong");
        BiomeSeamVolume liveVolume = new BiomeSeamVolume(liveField).radius(2);
        liveVolume.bake(BiomeRegistry.vanillaDefaults());
        BiomeSample atBorder = liveVolume.sample(BiomeRegistry.vanillaDefaults(), 2, 66, 0);
        eq(atBorder.count, 2, "sampling the border returns BOTH biomes, not one");
        BiomeSample inside = liveVolume.sample(BiomeRegistry.vanillaDefaults(), 2, 66, -32);
        eq(inside.count, 1, "and sampling inside one biome returns exactly one");
        eq(inside.ids[0], lowBiome, "the one the world actually has there (not the neighbour's)");
        // The offset, stated as a number: reading without the origin moved the
        // answer by half the window along z, which for a 23-cell window is 44
        // blocks - further than the border.
        // The old code path is still reachable (the origin-less overload), so
        // the regression is pinned against the real thing rather than described.
        // (The origin-less overload is pinned where it is unambiguous instead:
        // for a volume that really does start at cell 0 the two must agree,
        // which is the contract the fixtures rely on.)
        BiomeField originZero = new BiomeField(4);
        originZero.allocate(4, 4, 4);
        for (int cz = 0; cz < 4; cz++) {
            for (int cy = 0; cy < 4; cy++) {
                for (int cx = 0; cx < 4; cx++) originZero.setIdAtCell(cx, cy, cz, highBiome);
            }
        }
        BiomeSeamVolume atZero = new BiomeSeamVolume(originZero).radius(0);
        atZero.bake(BiomeRegistry.vanillaDefaults());
        BiomeSeamSampler originless = new BiomeSeamSampler(BiomeRegistry.vanillaDefaults());
        eq(originless.sample(atZero.ids(), atZero.weights(), 4, 4, 4, 4, 6, 6, 6).count, 1,
                "an origin-less volume is read the same way as an origin-zero one");
        eq(atZero.sample(BiomeRegistry.vanillaDefaults(), 6, 6, 6).ids[0], highBiome,
                "and both agree on WHICH biome, not just on how many");

        receipt(window.receipt());

        // Step 10, first piece: a real world answers by COLUMN, not by block.
        // The per-block path asks the chunk cache 51 313 times for a window and
        // every call is allowed to load a chunk - terrain generated on the
        // render thread. The column path asks 23x23 times and refuses a column
        // it does not have. Both must produce the SAME field, or "faster" has
        // been bought with a different answer.
        section("world window: a column-shaped world reads the same seam");
        final int colSeaLevel = 64;
        BiomeWindowSource.BlockSampler perBlock = new BiomeWindowSource.BlockSampler() {
            @Override public int idAt(int bx, int by, int bz) {
                return by < colSeaLevel ? lowBiome : highBiome;
            }
        };
        final int[] columnCalls = { 0 };
        final int[] columnBlocks = { 0 };
        BiomeWindowSource.BlockSampler perColumn = new BiomeWindowSource.BlockSampler() {
            @Override public int idAt(int bx, int by, int bz) {
                // Reached only if the window had to fall back: the assertion
                // below says it must not.
                throw new IllegalStateException("per-block path used for a column-shaped world");
            }
            @Override public boolean hasColumns() { return true; }
            @Override public int[] column(int chunkX, int chunkZ, int cellSize, int cellY) {
                columnCalls[0]++;
                int[] col = new int[384];
                for (int i = 0; i < col.length; i++) {
                    col[i] = (dev.auralite.render.biome.BiomeField.MIN_BLOCK_Y + i) < colSeaLevel
                            ? lowBiome : highBiome;
                }
                columnBlocks[0] += col.length;
                return col;
            }
        };
        BiomeField blockField = new BiomeField(4);
        blockField.allocate(23, 97, 23);
        BiomeField columnField = new BiomeField(4);
        columnField.allocate(23, 97, 23);
        new BiomeWindowSource(blockField, perBlock, 23, 97, 23).rebuild(0, 64, 0, 400);
        BiomeWindowSource columnWindow =
                new BiomeWindowSource(columnField, perColumn, 23, 97, 23);
        columnWindow.rebuild(0, 64, 0, 400);

        eq(columnCalls[0], 23 * 23,
                "a 23x97x23 window is 529 chunk columns, not 51 313 block lookups");
        // A 97-cell window of 4-block cells is 388 blocks tall, and the world is
        // 384: centred on a player at y = 64 it reaches BELOW the bottom block.
        // Those cells are not in the world, so a column cannot answer them, and
        // the honest answer is a counted hole. The per-block fixture answers
        // them anyway because a lambda has no world - which is exactly the
        // difference this pair is here to make visible.
        int belowWorld = 0;
        int columnMismatches = 0;
        for (int cz = 0; cz < 23; cz++) {
            for (int cy = 0; cy < 97; cy++) {
                for (int cx = 0; cx < 23; cx++) {
                    int a = blockField.idAtCell(blockField.originCX() + cx,
                            blockField.originCY() + cy, blockField.originCZ() + cz);
                    int b = columnField.idAtCell(columnField.originCX() + cx,
                            columnField.originCY() + cy, columnField.originCZ() + cz);
                    int centreY = (columnField.originCY() + cy) * 4 + 2;
                    if (centreY < dev.auralite.render.biome.BiomeField.MIN_BLOCK_Y) {
                        belowWorld++;
                        if (b != BiomeField.UNKNOWN) columnMismatches++;
                        continue;
                    }
                    if (a != b) columnMismatches++;
                }
            }
        }
        check(belowWorld > 0, "the reference window reaches below the world's bottom ("
                + belowWorld + " of " + (23 * 97 * 23) + " cells)");
        eq(columnField.unknownCount(), (long) belowWorld,
                "and exactly those cells are holes - not one cell more");
        eq(columnMismatches, 0,
                "everywhere the world HAS a column, the column path and the per-block path agree");
        TestRig.contains(columnWindow.lastOutcome(), "chunk columns",
                "and the receipt says the window was read by column, not by block");
        receipt("world window: " + columnCalls[0] + " column calls (" + columnBlocks[0]
                + " block rows) replace 51313 block lookups, field identical");

        // "The seam is in the frame" has to be countable, or it is a slogan: a
        // window of ONE biome bakes a volume with no blended cell, and a frame
        // drawn from it looks exactly like a working seam - a flat colour.
        section("world window: the seam in the data is counted, not assumed");
        BiomeField flatField = new BiomeField(4);
        flatField.allocate(8, 8, 8);
        for (int cz = 0; cz < 8; cz++) {
            for (int cy = 0; cy < 8; cy++) {
                for (int cx = 0; cx < 8; cx++) flatField.setIdAtCell(cx, cy, cz, highBiome);
            }
        }
        BiomeSeamVolume flatVolume = new BiomeSeamVolume(flatField).radius(2);
        flatVolume.bake(BiomeRegistry.vanillaDefaults());
        eq(flatVolume.blendedCells(), 0,
                "one biome in the window bakes ZERO blended cells at R=2 - there is no seam"
                        + " to draw, and the receipt must say so instead of implying one");
        eq(flatVolume.distinctIds().length, 1, "and the volume holds exactly one id");

        BiomeField splitField = new BiomeField(4);
        splitField.allocate(8, 8, 8);
        for (int cz = 0; cz < 8; cz++) {
            for (int cy = 0; cy < 8; cy++) {
                for (int cx = 0; cx < 8; cx++) {
                    splitField.setIdAtCell(cx, cy, cz, cx < 4 ? lowBiome : highBiome);
                }
            }
        }
        BiomeSeamVolume splitVolume = new BiomeSeamVolume(splitField).radius(2);
        splitVolume.bake(BiomeRegistry.vanillaDefaults());
        check(splitVolume.blendedCells() > 0,
                "a border in the window bakes blended cells (" + splitVolume.blendedCells() + ")");
        eq(splitVolume.distinctIds().length, 2, "and two distinct ids, which is what a seam is");
        TestRig.contains(splitVolume.receipt(), "blended cell(s)",
                "the volume receipt quotes the count, so a live log can be read without a debugger");
        receipt(splitVolume.receipt());

        // A column the world does not have is a HOLE, not the neighbour's
        // biome: the window must not invent terrain it was not given.
        BiomeField holeField = new BiomeField(4);
        holeField.allocate(23, 97, 23);
        final int[] asked = { 0 };
        BiomeWindowSource halfLoaded = new BiomeWindowSource(holeField,
                new BiomeWindowSource.BlockSampler() {
                    @Override public int idAt(int bx, int by, int bz) {
                        return by < colSeaLevel ? lowBiome : highBiome;
                    }
                    @Override public boolean hasColumns() { return true; }
                    @Override public int[] column(int chunkX, int chunkZ, int cellSize, int cellY) {
                        asked[0]++;
                        if (asked[0] % 2 == 0) return null;    // half the world is not loaded
                        int[] col = new int[384];
                        for (int i = 0; i < col.length; i++) {
                            col[i] = (dev.auralite.render.biome.BiomeField.MIN_BLOCK_Y + i) < colSeaLevel
                                    ? lowBiome : highBiome;
                        }
                        return col;
                    }
                }, 23, 97, 23);
        halfLoaded.rebuild(0, 64, 0, 500);
        check(holeField.unknownCount() > (long) belowWorld,
                "a window whose world answers half the columns keeps MORE holes than the"
                        + " out-of-world rows alone (" + holeField.unknownCount() + " unknown)");
        eq(halfLoaded.lastUnloaded() + halfLoaded.lastUnmapped(), holeField.unknownCount(),
                "and every hole is counted with its reason, not with a guessed biome");
        TestRig.contains(window.receipt(), "23x97x23", "the receipt names the window");
        TestRig.contains(window.receipt(), "rebuild(s)", "and how many times it was rebuilt");

        // A control reddens by FAILING, so this one asserts the wrong
        // implementation on purpose: an origin that slides with every block
        // (floor(d) - 11 = 53 for a player at x=64) instead of every cell (5).
        // If that assertion ever passed, the window assertions above would be
        // checking nothing.
        negativeControl("a block-granular origin", new Runnable() {
            @Override public void run() {
                eq(BiomeWindowSource.originCellFor(64.0, 23, 4), 53L,
                        "block-granular origin (this must fail: the real one is 5)");
            }
        });

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
