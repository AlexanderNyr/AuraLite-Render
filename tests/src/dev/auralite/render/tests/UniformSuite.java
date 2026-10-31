package dev.auralite.render.tests;

import dev.auralite.render.biome.BiomeRegistry;
import dev.auralite.render.uniform.CustomUniformTable;
import dev.auralite.render.uniform.ExprEnv;
import dev.auralite.render.uniform.ExprParser;
import dev.auralite.render.uniform.FunctionTable;
import dev.auralite.render.uniform.Value;

import java.util.LinkedHashMap;
import java.util.Map;

import static dev.auralite.render.tests.TestRig.check;
import static dev.auralite.render.tests.TestRig.eq;
import static dev.auralite.render.tests.TestRig.near;
import static dev.auralite.render.tests.TestRig.receipt;
import static dev.auralite.render.tests.TestRig.section;
import static dev.auralite.render.tests.TestRig.suite;
import static dev.auralite.render.tests.TestRig.throwsNamed;

/**
 * C1 gate: the CPU expression evaluator that LEGACY packs depend on, measured
 * against the exact chain AuraLite-Shaders v1.2.0 ships.
 */
public final class UniformSuite {
    private UniformSuite() { }

    /** The water-colour chain as it appears in AuraLite-Shaders' shaders.properties. */
    static final String WATER_R =
            "if(in(biome, BIOME_SWAMP), 0.38, "
          + "in(biome, BIOME_LUKEWARM_OCEAN, BIOME_DEEP_LUKEWARM_OCEAN), 0.271, "
          + "in(biome, BIOME_WARM_OCEAN), 0.263, "
          + "in(biome, BIOME_COLD_OCEAN, BIOME_DEEP_COLD_OCEAN, BIOME_SNOWY_TAIGA, BIOME_SNOWY_BEACH), 0.239, "
          + "in(biome, BIOME_FROZEN_RIVER, BIOME_FROZEN_OCEAN, BIOME_DEEP_FROZEN_OCEAN), 0.224, "
          + "in(biome, BIOME_MEADOW), 0.055, "
          + "in(biome, BIOME_MANGROVE_SWAMP), 0.227, 0.247)";
    static final String WATER_G =
            "if(in(biome, BIOME_SWAMP), 0.482, "
          + "in(biome, BIOME_LUKEWARM_OCEAN, BIOME_DEEP_LUKEWARM_OCEAN), 0.678, "
          + "in(biome, BIOME_WARM_OCEAN), 0.835, "
          + "in(biome, BIOME_COLD_OCEAN, BIOME_DEEP_COLD_OCEAN, BIOME_SNOWY_TAIGA, BIOME_SNOWY_BEACH), 0.341, "
          + "in(biome, BIOME_FROZEN_RIVER, BIOME_FROZEN_OCEAN, BIOME_DEEP_FROZEN_OCEAN), 0.22, "
          + "in(biome, BIOME_MEADOW), 0.306, "
          + "in(biome, BIOME_MANGROVE_SWAMP), 0.478, 0.463)";
    static final String WATER_B =
            "if(in(biome, BIOME_SWAMP), 0.392, "
          + "in(biome, BIOME_LUKEWARM_OCEAN, BIOME_DEEP_LUKEWARM_OCEAN), 0.416, "
          + "in(biome, BIOME_WARM_OCEAN), 0.933, "
          + "in(biome, BIOME_COLD_OCEAN, BIOME_DEEP_COLD_OCEAN, BIOME_SNOWY_TAIGA, BIOME_SNOWY_BEACH), 0.839, "
          + "in(biome, BIOME_FROZEN_RIVER, BIOME_FROZEN_OCEAN, BIOME_DEEP_FROZEN_OCEAN), 0.788, "
          + "in(biome, BIOME_MEADOW), 0.812, "
          + "in(biome, BIOME_MANGROVE_SWAMP), 0.416, 0.894)";

    private static ExprEnv envWith(BiomeRegistry reg) {
        ExprEnv env = new ExprEnv(new FunctionTable());
        reg.bindConstants(env);
        return env;
    }

    public static void run() {
        suite("uniform");

        section("arithmetic and precedence");
        eq(eval("2 + 3 * 4").asDouble(), 14.0, "multiplication binds tighter than addition");
        eq(eval("(2 + 3) * 4").asDouble(), 20.0, "parentheses override precedence");
        eq(eval("10 - 4 - 3").asDouble(), 3.0, "subtraction is left-associative");
        eq(eval("100 / 10 / 2").asDouble(), 5.0, "division is left-associative");
        near(eval("7 % 3").asDouble(), 1.0, 1e-12, "modulo");
        near(eval("-7 % 3").asDouble(), 2.0, 1e-12, "modulo follows GLSL, not Java: the sign of the divisor");
        eq(eval("-3 + 5").asDouble(), 2.0, "unary minus");
        eq(eval("--3").asDouble(), 3.0, "double unary minus");
        near(eval("pi").asDouble(), Math.PI, 1e-12, "pi is predefined");
        eq(eval("0x10").asDouble(), 16.0, "hexadecimal literal");
        eq(eval("1e3").asDouble(), 1000.0, "exponent literal");
        eq(eval("2.5f").asDouble(), 2.5, "a trailing f suffix is accepted");

        section("comparison and logic");
        check(eval("1 < 2").asBool(), "less-than");
        check(!eval("2 < 1").asBool(), "less-than, false case");
        check(eval("2 >= 2").asBool(), "greater-or-equal");
        check(eval("3 == 3").asBool(), "equality");
        check(eval("3 != 4").asBool(), "inequality");
        check(eval("true && !false").asBool(), "logical and / not");
        check(!eval("true && false").asBool(), "logical and, false case");
        check(eval("false || true").asBool(), "logical or");
        eq(eval("1 + 2 == 3").asBool(), true, "comparison binds looser than addition");
        eq(eval("1 < 2 == true").asBool(), true, "equality binds looser than comparison");

        section("functions");
        eq(eval("max(3, 7)").asDouble(), 7.0, "max");
        eq(eval("min(3, 7)").asDouble(), 3.0, "min");
        eq(eval("clamp(9, 0, 1)").asDouble(), 1.0, "clamp");
        eq(eval("abs(-4)").asDouble(), 4.0, "abs");
        eq(eval("floor(2.7)").asDouble(), 2.0, "floor");
        eq(eval("ceil(2.1)").asDouble(), 3.0, "ceil");
        eq(eval("round(2.5)").asDouble(), 2.0, "round uses banker's rounding, as Math.rint does");
        eq(eval("mix(0, 10, 0.25)").asDouble(), 2.5, "mix");
        near(eval("smoothstep(0, 1, 0.5)").asDouble(), 0.5, 1e-9, "smoothstep midpoint");
        near(eval("sqrt(16)").asDouble(), 4.0, 1e-12, "sqrt");
        near(eval("sin(pi / 2)").asDouble(), 1.0, 1e-12, "sin");
        near(eval("atan2(1, 1)").asDouble(), Math.PI / 4, 1e-12, "atan2");
        eq(eval("if(true, 10, 20)").asDouble(), 10.0, "if, true branch");
        eq(eval("if(false, 10, 20)").asDouble(), 20.0, "if, false branch");
        check(eval("in(3, 1, 2, 3, 4)").asBool(), "in, present");
        check(!eval("in(9, 1, 2, 3)").asBool(), "in, absent");
        eq(eval("vec3(1, 2, 3)").size(), 3, "vec3 has three components");
        near(eval("vec3(1, 2, 3)").component(2), 3.0, 1e-12, "vec3 component access");
        near(eval("length(vec3(3, 4, 0))").asDouble(), 5.0, 1e-12, "length of a vector");
        near(eval("dot(vec2(1,2), vec2(3,4))").asDouble(), 11.0, 1e-12, "dot product");

        section("vector arithmetic");
        Value v = eval("vec3(1, 2, 3) * 2");
        eq(v.size(), 3, "scalar * vector keeps the vector size");
        near(v.component(1), 4.0, 1e-12, "component-wise multiply");
        Value w = eval("vec2(1, 2) + vec2(10, 20)");
        near(w.component(0), 11.0, 1e-12, "vector + vector");

        section("errors are named");
        throwsNamed(new Runnable() { @Override public void run() { eval("2 +"); } },
                "unexpected end of expression", "a truncated expression names the problem");
        throwsNamed(new Runnable() { @Override public void run() { eval("1 2"); } },
                "trailing input", "trailing input is reported");
        throwsNamed(new Runnable() { @Override public void run() { eval("(1 + 2"); } },
                "expected ')'", "an unbalanced parenthesis is reported");
        throwsNamed(new Runnable() { @Override public void run() { eval("nope(1)"); } },
                "unsupported function", "an unknown function is reported");
        throwsNamed(new Runnable() { @Override public void run() { eval("max(1)"); } },
                "takes 2 arguments", "an arity mismatch is reported");
        ExprEnv bare = new ExprEnv(new FunctionTable());
        throwsNamed(new Runnable() { @Override public void run() {
            ExprParser.parse("missingVar + 1").eval(bare);
        }}, "unknown identifier 'missingVar'", "an unbound identifier names itself");

        section("smooth() is temporal, not spatial");
        FunctionTable ft = new FunctionTable();
        ExprEnv senv = new ExprEnv(ft);
        dev.auralite.render.uniform.Expr e = ExprParser.parse("smooth(t1, target, 4.0)");
        senv.bindFloat("target", 0.0);
        ft.setFrame(0, 1.0 / 60.0);
        double first = e.eval(senv).asDouble();
        senv.bindFloat("target", 1.0);
        ft.setFrame(1, 1.0 / 60.0);
        double second = e.eval(senv).asDouble();
        ft.setFrame(2, 1.0 / 60.0);
        double third = e.eval(senv).asDouble();
        near(first, 0.0, 1e-9, "smooth() starts at the first target");
        check(second > first && second < 1.0, "smooth() moves toward the target without reaching it");
        check(third > second, "smooth() keeps approaching");
        eq(ft.smoothStateCount(), 1, "one smooth state is retained per call site");
        // Two evaluations inside the same frame must not advance the filter.
        ft.setFrame(2, 1.0 / 60.0);
        double again = e.eval(senv).asDouble();
        near(again, third, 1e-12, "smooth() does not advance twice in one frame");
        receipt("smooth(t1, 0->1, rate=4): frame1=" + trim(second) + " frame2=" + trim(third));

        section("custom uniform table");
        BiomeRegistry reg = BiomeRegistry.vanillaDefaults();
        CustomUniformTable t = new CustomUniformTable();
        String[] lines = {
                "# AuraLite-Shaders water colour, verbatim",
                "variable.float.waterColorR = " + WATER_R,
                "variable.float.waterColorG = " + WATER_G,
                "variable.float.waterColorB = " + WATER_B,
                "uniform.vec3.waterColor = vec3(waterColorR, waterColorG, waterColorB)",
                "uniform.bool.isSwamp = in(biome, BIOME_SWAMP)",
                "uniform.float.heat = smooth(heat, if(temperature > 1.0, 1.0, 0.0), 2, 0.25)",
                "isSwamp.transition = 2.5",
                "sliders = EXPOSURE CONTRAST",
        };
        int parsed = 0;
        for (String line : lines) if (t.parseLine(line, "test")) parsed++;
        eq(parsed, 7, "7 of 9 lines are definitions or transitions");
        eq(t.size(), 6, "6 definitions were registered (the transition line attaches, it does not add)");
        check(t.problems().isEmpty(), "no problems reported: " + t.problems());
        eq(t.find("waterColorR").transitionSeconds, 0.0, "waterColorR has no transition");
        near(t.find("isSwamp").transitionSeconds, 2.5, 1e-9, "isSwamp.transition was parsed");

        ExprEnv env = envWith(reg);
        int swamp = reg.constantId("BIOME_SWAMP");
        int plains = reg.constantId("BIOME_PLAINS");
        int meadow = reg.constantId("BIOME_MEADOW");
        int warm = reg.constantId("BIOME_WARM_OCEAN");
        check(swamp >= 0 && plains >= 0 && meadow >= 0 && warm >= 0, "all four test biomes exist in the registry");

        Map<String, Value> out = new LinkedHashMap<String, Value>();
        env.bindInt("biome", swamp);
        env.bindFloat("temperature", reg.byId(swamp).temperature);
        t.evaluate(env, out);
        Value wc = out.get("waterColor");
        eq(wc.size(), 3, "waterColor is a vec3");
        near(wc.component(0), 0.38, 1e-9, "swamp water red matches AuraLite-Shaders");
        near(wc.component(1), 0.482, 1e-9, "swamp water green matches AuraLite-Shaders");
        near(wc.component(2), 0.392, 1e-9, "swamp water blue matches AuraLite-Shaders");
        check(out.get("isSwamp").asBool(), "isSwamp is true in a swamp");

        out.clear();
        env.bindInt("biome", plains);
        env.bindFloat("temperature", reg.byId(plains).temperature);
        t.evaluate(env, out);
        near(out.get("waterColor").component(0), 0.247, 1e-9, "plains falls through to the default red");
        near(out.get("waterColor").component(2), 0.894, 1e-9, "plains falls through to the default blue");
        check(!out.get("isSwamp").asBool(), "isSwamp is false on the plains");

        out.clear();
        env.bindInt("biome", meadow);
        t.evaluate(env, out);
        near(out.get("waterColor").component(0), 0.055, 1e-9, "meadow water red");

        out.clear();
        env.bindInt("biome", warm);
        t.evaluate(env, out);
        near(out.get("waterColor").component(1), 0.835, 1e-9, "warm ocean water green");

        receipt("waterColor(swamp)  = " + trim3(evalWater(t, envWith(reg), reg, swamp)));
        receipt("waterColor(plains) = " + trim3(evalWater(t, envWith(reg), reg, plains)));
        receipt("waterColor(meadow) = " + trim3(evalWater(t, envWith(reg), reg, meadow)));
        receipt("waterColor(warm_ocean) = " + trim3(evalWater(t, envWith(reg), reg, warm)));

        section("if() is variadic, as OptiFine defines it");
        eq(eval("if(false, 1, true, 2, 3)").asDouble(), 2.0, "the second condition wins");
        eq(eval("if(false, 1, false, 2, 99)").asDouble(), 99.0, "no condition matches, so the else wins");
        eq(eval("if(true, 1, true, 2, 3)").asDouble(), 1.0, "the first true condition wins");
        eq(eval("if(false, 1, 2)").asDouble(), 2.0, "the plain ternary form still works");
        throwsNamed(new Runnable() { @Override public void run() { eval("if(true, 1)"); } },
                "odd number of arguments", "an even argument count is refused with the rule stated");
        receipt("if(): variadic chain of " + (WATER_R.split(",").length) + " comma-separated parts accepted");

        section("declaration order is semantics");
        CustomUniformTable ordered = new CustomUniformTable();
        ordered.parseLine("variable.float.base = 2.0", "test");
        ordered.parseLine("variable.float.doubled = base * 2", "test");
        ordered.parseLine("uniform.float.result = doubled + 1", "test");
        Map<String, Value> o2 = new LinkedHashMap<String, Value>();
        ordered.evaluate(new ExprEnv(new FunctionTable()), o2);
        near(o2.get("result").asDouble(), 5.0, 1e-9, "a later definition sees an earlier variable");

        CustomUniformTable backwards = new CustomUniformTable();
        backwards.parseLine("uniform.float.result = doubled + 1", "test");
        backwards.parseLine("variable.float.doubled = 2 * 2", "test");
        Map<String, Value> o3 = new LinkedHashMap<String, Value>();
        backwards.evaluate(new ExprEnv(new FunctionTable()), o3);
        check(!backwards.problems().isEmpty(), "using a variable before its definition is reported, not guessed");
        contains(backwards.problems().get(0), "doubled", "the problem names the missing variable");

        section("malformed definitions are reported");
        CustomUniformTable bad = new CustomUniformTable();
        bad.parseLine("uniform.float.x = 1 +", "test:1");
        check(!bad.problems().isEmpty(), "a malformed expression is reported");
        contains(bad.problems().get(0), "test:1", "the report carries the origin line");
        CustomUniformTable bad2 = new CustomUniformTable();
        bad2.parseLine("uniform.matrix.x = 1", "test:2");
        contains(bad2.problems().get(0), "illegal custom uniform type", "an illegal type is named");
        CustomUniformTable bad3 = new CustomUniformTable();
        bad3.parseLine("uniform.float.dup = 1", "test:3");
        bad3.parseLine("uniform.float.dup = 2", "test:4");
        contains(bad3.problems().get(0), "defined twice", "a duplicate name is named");
        CustomUniformTable bad4 = new CustomUniformTable();
        bad4.parseLine("ghost.transition = 1.5", "test:5");
        contains(bad4.problems().get(0), "unknown uniform 'ghost'", "a transition for a missing uniform is named");

        section("reparsing is stable");
        for (CustomUniformTable.Definition d : t.definitions()) {
            String src = d.expression.source();
            dev.auralite.render.uniform.Expr reparsed = ExprParser.parse(src);
            eq(reparsed.source(), src, "expression '" + d.name + "' round-trips through the parser");
        }
    }

    private static Value eval(String s) {
        return ExprParser.parse(s).eval(new ExprEnv(new FunctionTable()));
    }

    private static Value evalWater(CustomUniformTable t, ExprEnv env, BiomeRegistry reg, int biomeId) {
        Map<String, Value> out = new LinkedHashMap<String, Value>();
        env.bindInt("biome", biomeId);
        env.bindFloat("temperature", reg.byId(biomeId).temperature);
        t.evaluate(env, out);
        return out.get("waterColor");
    }

    private static String trim(double d) { return String.format(java.util.Locale.ROOT, "%.6f", d); }

    private static String trim3(Value v) {
        if (v == null) return "<null>";
        return String.format(java.util.Locale.ROOT, "(%.3f, %.3f, %.3f)",
                v.component(0), v.component(1), v.component(2));
    }

    private static void contains(String haystack, String needle, String what) {
        TestRig.contains(haystack, needle, what);
    }
}
