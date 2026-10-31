package dev.auralite.render.biome;

import dev.auralite.render.uniform.CustomUniformTable;
import dev.auralite.render.uniform.ExprEnv;
import dev.auralite.render.uniform.FunctionTable;
import dev.auralite.render.uniform.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * Bakes a LEGACY pack's own biome-dependent expression into a 3D volume.
 *
 * <p>This is the compatibility story for "more than one biome", and it needs no
 * pack edits. A legacy pack writes something like
 *
 * <pre>
 * variable.float.waterColorR = if(in(biome, BIOME_SWAMP), 0.38, in(biome, BIOME_LUKEWARM_OCEAN), 0.271, 0.247)
 * uniform.vec3.waterColor    = vec3(waterColorR, waterColorG, waterColorB)
 * </pre>
 *
 * and Iris evaluates that once per frame with {@code biome} bound to the
 * camera's biome, so the whole screen gets one water colour. Here the same
 * expression is evaluated once per field cell with {@code biome} bound to that
 * cell's id, and the result is uploaded as an RGBA32F volume the fragment
 * shader samples at its own world position. Under
 * {@code auralite.spatialBiomeUniforms = true} the pack gets spatially varying
 * water colour, fog and foliage without a single line changed.
 *
 * <p>Two semantic differences are recorded rather than hidden: {@code smooth()}
 * is evaluated once and frozen, because a per-cell temporal filter is not what
 * the pack author meant; and {@code .transition} times are ignored, because the
 * spatial blend replaces them.
 */
public final class BiomeBake {

    public static final class Result {
        public int cellsX, cellsY, cellsZ;
        public float[] rgba;
        public int evaluations;
        public int unknownCells;
        public final List<String> warnings = new ArrayList<String>();

        public long texelCount() { return (long) cellsX * cellsY * cellsZ; }
        public long sizeBytes() { return (long) rgba.length * 4; }

        public float get(int x, int y, int z, int component) {
            return rgba[((z * cellsY + y) * cellsX + x) * 4 + component];
        }

        public String receipt() {
            StringBuilder sb = new StringBuilder();
            sb.append("bake: ").append(cellsX).append('x').append(cellsY).append('x').append(cellsZ)
              .append(" cells, ").append(evaluations).append(" evaluations, ")
              .append(unknownCells).append(" unknown, ").append(sizeBytes()).append(" B\n");
            for (String w : warnings) sb.append("  warning: ").append(w).append('\n');
            return sb.toString();
        }
    }

    /** Bakes one definition across the field, writing 4 components per cell. */
    public static Result bake(CustomUniformTable.Definition def, BiomeField field,
                              BiomeRegistry registry, ExprEnv baseEnv) {
        Result r = new Result();
        r.cellsX = field.sizeCX();
        r.cellsY = field.sizeCY();
        r.cellsZ = field.sizeCZ();
        r.rgba = new float[(int) r.texelCount() * 4];

        ExprEnv env = baseEnv != null ? baseEnv : new ExprEnv(new FunctionTable());
        BiomeRecord fallback = registry.fallback();
        int fallbackId = fallback == null ? 0 : fallback.id;
        boolean frozenSmooth = def.source.contains("smooth(");
        if (frozenSmooth) {
            r.warnings.add("'" + def.name + "' uses smooth(); temporal smoothing is evaluated once "
                    + "and frozen in the baked volume -- the spatial blend replaces it");
        }

        int sx = field.sizeCX(), sy = field.sizeCY();
        for (int z = 0; z < field.sizeCZ(); z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    int cell = field.rawIds()[(z * sy + y) * sx + x];
                    int id = cell == BiomeField.UNKNOWN ? fallbackId : cell;
                    if (cell == BiomeField.UNKNOWN) r.unknownCells++;
                    Value v = registryById(env, def, registry, id);
                    r.evaluations++;
                    int base = ((z * sy + y) * sx + x) * 4;
                    double[] c = v.components();
                    r.rgba[base] = (float) c[0];
                    r.rgba[base + 1] = c.length > 1 ? (float) c[1] : 0f;
                    r.rgba[base + 2] = c.length > 2 ? (float) c[2] : 0f;
                    r.rgba[base + 3] = c.length > 3 ? (float) c[3] : 1f;
                }
            }
        }
        return r;
    }

    private static Value registryById(ExprEnv env, CustomUniformTable.Definition def,
                                      BiomeRegistry registry, int biomeId) {
        env.bindInt("biome", biomeId);
        BiomeRecord rec = registry.byId(biomeId);
        if (rec != null) {
            // Bind the derived scalars too, so an expression that reads
            // `temperature` or `rainfall` bakes correctly as well.
            env.bindFloat("temperature", rec.temperature);
            env.bindFloat("rainfall", rec.downfall);
            env.bindInt("biome_category", rec.category.id());
            env.bindInt("biome_precipitation", rec.precipitation.id());
        }
        try {
            return def.expression.eval(env);
        } catch (RuntimeException e) {
            return Value.ofFloat(0);
        }
    }

    /** True when a definition actually depends on the biome and is worth baking. */
    public static boolean isBiomeDependent(CustomUniformTable.Definition def) {
        String s = def.source;
        return s.contains("biome") || s.contains("temperature") || s.contains("rainfall")
                || s.contains("BIOME_") || s.contains("CAT_") || s.contains("PPT_");
    }
}
