package dev.auralite.render.uniform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code uniform.*} / {@code variable.*} half of {@code shaders.properties}.
 *
 * <p>Definitions are evaluated in declaration order because a {@code variable}
 * may feed a later {@code uniform} -- that is exactly how AuraLite-Shaders
 * builds its water colour -- so order is part of the semantics and is preserved
 * verbatim in both pack modes.
 */
public final class CustomUniformTable {

    public static final class Definition {
        public final boolean variable;
        public final String type;
        public final String name;
        public final String source;
        public final Expr expression;
        /** OptiFine biome transition time in seconds, 0 when absent. */
        public double transitionSeconds;
        public Value lastValue;
        public int evaluations;

        Definition(boolean variable, String type, String name, String source, Expr expression) {
            this.variable = variable;
            this.type = type;
            this.name = name;
            this.source = source;
            this.expression = expression;
        }

        public String qualifiedName() { return (variable ? "variable." : "uniform.") + type + "." + name; }

        @Override public String toString() { return qualifiedName() + " = " + source; }
    }

    private final List<Definition> definitions = new ArrayList<Definition>();
    private final Map<String, Definition> byName = new LinkedHashMap<String, Definition>();
    private final List<String> problems = new ArrayList<String>();

    public List<Definition> definitions() { return Collections.unmodifiableList(definitions); }
    public List<String> problems() { return Collections.unmodifiableList(problems); }
    public int size() { return definitions.size(); }
    public Definition find(String name) { return byName.get(name); }
    public boolean has(String name) { return byName.containsKey(name); }

    /**
     * Parses one {@code shaders.properties} line.
     *
     * @return true when the line was a custom uniform or variable definition
     */
    public boolean parseLine(String rawLine, String origin) {
        if (rawLine == null) return false;
        String line = rawLine.trim();
        if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '/') return false;

        // "<name>.transition = <seconds>" attaches to an earlier definition.
        if (line.endsWith(".transition") || line.contains(".transition ") || line.contains(".transition=")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                String key = line.substring(0, eq).trim();
                if (key.endsWith(".transition")) {
                    String name = key.substring(0, key.length() - ".transition".length());
                    Definition d = byName.get(name);
                    try {
                        double secs = Double.parseDouble(line.substring(eq + 1).trim());
                        if (d != null) d.transitionSeconds = secs;
                        else problems.add(origin + ": transition for unknown uniform '" + name + "'");
                    } catch (NumberFormatException e) {
                        problems.add(origin + ": malformed transition value in '" + line + "'");
                    }
                    return true;
                }
            }
            return false;
        }

        int eq = line.indexOf('=');
        if (eq <= 0) return false;
        String key = line.substring(0, eq).trim();
        String body = line.substring(eq + 1).trim();

        boolean variable = key.startsWith("variable.");
        boolean uniform = key.startsWith("uniform.");
        if (!variable && !uniform) return false;

        String[] parts = key.split("\\.");
        if (parts.length != 3) {
            problems.add(origin + ": expected '" + (variable ? "variable" : "uniform")
                    + ".<type>.<name>', got '" + key + "'");
            return true;
        }
        String type = parts[1].trim();
        String name = parts[2].trim();
        if (!isLegalType(type)) {
            problems.add(origin + ": illegal custom uniform type '" + type + "' in '" + key + "'");
            return true;
        }
        if (byName.containsKey(name)) {
            problems.add(origin + ": '" + name + "' is defined twice");
            return true;
        }
        Expr expr;
        try {
            expr = ExprParser.parse(body);
        } catch (ExprException e) {
            problems.add(origin + ": " + e.getMessage());
            return true;
        }
        Definition d = new Definition(variable, type, name, body, expr);
        definitions.add(d);
        byName.put(name, d);
        return true;
    }

    private static boolean isLegalType(String t) {
        return t.equals("bool") || t.equals("int") || t.equals("float")
                || t.equals("vec2") || t.equals("vec3") || t.equals("vec4");
    }

    /**
     * Evaluates every definition in order, exposing variables to later
     * definitions. Values land in {@code out} keyed by name.
     */
    public void evaluate(ExprEnv env, Map<String, Value> out) {
        for (Definition d : definitions) {
            env.expressionText(d.source);
            Value v;
            try {
                v = d.expression.eval(env);
            } catch (ExprException e) {
                if (!problems.contains("evaluate: " + e.getMessage())) {
                    problems.add("evaluate: " + e.getMessage());
                }
                continue;
            }
            v = coerce(v, d.type);
            d.lastValue = v;
            d.evaluations++;
            out.put(d.name, v);
            // A variable is visible to later definitions and to the shader too;
            // a uniform is only visible to the shader. Both are bound here so
            // that ordering matches Iris, which binds as it goes.
            env.bind(d.name, v);
        }
    }

    /** Evaluates a single definition against a supplied biome id, for the bake lane. */
    public Value evaluateForBiome(Definition d, ExprEnv env, int biomeId) {
        env.bindInt("biome", biomeId);
        env.expressionText(d.source);
        return coerce(d.expression.eval(env), d.type);
    }

    private static Value coerce(Value v, String type) {
        if (type.equals("bool")) return Value.bool(v.asBool());
        if (type.equals("int")) return Value.ofInt(v.asInt());
        if (type.equals("float")) return Value.ofFloat(v.toFloat().component(0));
        if (type.equals("vec2")) return vectorOf(v, 2);
        if (type.equals("vec3")) return vectorOf(v, 3);
        if (type.equals("vec4")) return vectorOf(v, 4);
        return v;
    }

    private static Value vectorOf(Value v, int n) {
        if (v.size() == n) return v;
        double[] c = new double[n];
        for (int i = 0; i < n; i++) c[i] = i < v.size() ? v.component(i) : v.component(v.size() - 1);
        return Value.vec(c);
    }

    /** One line per definition, for the receipts and the pack linter. */
    public String receipt() {
        StringBuilder sb = new StringBuilder();
        sb.append("custom uniforms: ").append(definitions.size()).append(" definitions\n");
        for (Definition d : definitions) {
            sb.append("  ").append(d.qualifiedName()).append(" = ").append(d.source);
            if (d.transitionSeconds > 0) sb.append("   [transition ").append(d.transitionSeconds).append("s]");
            if (d.lastValue != null) sb.append("   -> ").append(d.lastValue);
            sb.append('\n');
        }
        for (String p : problems) sb.append("  problem: ").append(p).append('\n');
        return sb.toString();
    }
}
