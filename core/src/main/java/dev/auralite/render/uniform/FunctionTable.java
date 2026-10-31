package dev.auralite.render.uniform;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The CPU-side function set a pack expression may call.
 *
 * <p>Superset of what Iris/OptiFine accept: {@code if}, {@code in},
 * {@code smooth}, the vector constructors and the usual maths, plus
 * {@code blendWeights} which only exists so a LEGACY pack's
 * {@code variable.float.waterColorR} chain can be re-evaluated per biome cell
 * instead of once per frame. Everything else is behaviour-compatible on
 * purpose; the parity gate diffs this table's output against the recorded Iris
 * results for the same expressions.
 */
public final class FunctionTable {

    /** State of one {@code smooth(id, expr, rate, rateDown)} call site. */
    public static final class SmoothState {
        public final String id;
        public double value;
        public boolean initialised;
        public long lastFrameId = -1;
        public SmoothState(String id) { this.id = id; }
    }

    private final Map<String, SmoothState> smoothStates = new HashMap<String, SmoothState>();
    private final Set<String> knownNames = new LinkedHashSet<String>();
    private long currentFrameId;
    private double frameDeltaSeconds = 1.0 / 60.0;

    public static FunctionTable standard() { return new FunctionTable(); }

    public FunctionTable() {
        knownNames.addAll(Arrays.asList(
                "if", "in", "smooth", "select",
                "vec2", "vec3", "vec4",
                "abs", "sign", "floor", "ceil", "round", "fract", "trunc",
                "min", "max", "clamp", "mix", "lerp", "smoothstep", "step",
                "sin", "cos", "tan", "asin", "acos", "atan", "atan2",
                "pow", "exp", "exp2", "log", "log2", "sqrt", "inversesqrt",
                "mod", "length", "distance", "dot",
                "radians", "degrees",
                "blendWeights"));
    }

    public Set<String> knownNames() { return knownNames; }
    public boolean isKnown(String name) { return knownNames.contains(name); }
    public int smoothStateCount() { return smoothStates.size(); }
    public SmoothState smoothState(String id) { return smoothStates.get(id); }

    public void setFrame(long frameId, double deltaSeconds) {
        this.currentFrameId = frameId;
        if (deltaSeconds > 0) this.frameDeltaSeconds = deltaSeconds;
    }
    public long frameId() { return currentFrameId; }
    public double frameDeltaSeconds() { return frameDeltaSeconds; }

    public Value invoke(String name, List<Expr> args, ExprEnv env) {
        int n = args.size();
        if (name.equals("if") || name.equals("select")) {
            return chainedIf(name, args, env);
        }
        if (name.equals("in")) {
            if (n < 2) throw new ExprException("in() takes a value and at least one candidate");
            Value subject = args.get(0).eval(env);
            for (int i = 1; i < n; i++) {
                if (equalish(subject, args.get(i).eval(env))) return Value.TRUE;
            }
            return Value.FALSE;
        }
        if (name.equals("smooth")) {
            if (n < 3) throw new ExprException("smooth() takes (id, expression, rate[, rateDown])");
            String id = args.get(0).source();
            SmoothState st = smoothStates.get(id);
            if (st == null) { st = new SmoothState(id); smoothStates.put(id, st); }
            double target = args.get(1).eval(env).toFloat().component(0);
            double rate = Math.max(1e-6, args.get(2).eval(env).toFloat().component(0));
            double rateDown = n >= 4 ? Math.max(1e-6, args.get(3).eval(env).toFloat().component(0)) : rate;
            if (!st.initialised) {
                st.value = target;
                st.initialised = true;
            } else if (st.lastFrameId != currentFrameId) {
                double r = target > st.value ? rate : rateDown;
                // OptiFine's smoothing is an exponential approach scaled by frame time.
                double k = 1.0 - Math.exp(-r * frameDeltaSeconds);
                st.value += (target - st.value) * k;
            }
            st.lastFrameId = currentFrameId;
            return Value.ofFloat(st.value);
        }
        if (name.equals("vec2") || name.equals("vec3") || name.equals("vec4")) {
            int want = name.charAt(3) - '0';
            if (n != want) throw new ExprException(name + "() takes " + want + " arguments, got " + n);
            double[] c = new double[want];
            for (int i = 0; i < want; i++) c[i] = args.get(i).eval(env).toFloat().component(0);
            return Value.vec(c);
        }
        if (name.equals("blendWeights")) {
            // AuraLite-Render extension: normalises an arbitrary weight list so a
            // legacy per-biome chain can be evaluated per cell and blended.
            if (n < 1) throw new ExprException("blendWeights() takes at least one weight");
            double sum = 0;
            double[] c = new double[n];
            for (int i = 0; i < n; i++) {
                c[i] = Math.max(0, args.get(i).eval(env).toFloat().component(0));
                sum += c[i];
            }
            if (sum <= 0) { Arrays.fill(c, 1.0 / n); return Value.vec(c); }
            for (int i = 0; i < n; i++) c[i] /= sum;
            return Value.vec(c);
        }

        Value[] a = new Value[n];
        for (int i = 0; i < n; i++) a[i] = args.get(i).eval(env);
        return scalarMath(name, a);
    }

    private static Value scalarMath(String name, Value[] a) {
        if (name.equals("abs")) { req(a, 1, name); return Value.ofFloat(Math.abs(f(a[0]))); }
        if (name.equals("sign")) { req(a, 1, name); return Value.ofFloat(Math.signum(f(a[0]))); }
        if (name.equals("floor")) { req(a, 1, name); return Value.ofFloat(Math.floor(f(a[0]))); }
        if (name.equals("ceil")) { req(a, 1, name); return Value.ofFloat(Math.ceil(f(a[0]))); }
        if (name.equals("trunc")) { req(a, 1, name); return Value.ofFloat((long) f(a[0])); }
        if (name.equals("round")) { req(a, 1, name); return Value.ofFloat(Math.rint(f(a[0]))); }
        if (name.equals("fract")) { req(a, 1, name); double x = f(a[0]); return Value.ofFloat(x - Math.floor(x)); }
        if (name.equals("sin")) { req(a, 1, name); return Value.ofFloat(Math.sin(f(a[0]))); }
        if (name.equals("cos")) { req(a, 1, name); return Value.ofFloat(Math.cos(f(a[0]))); }
        if (name.equals("tan")) { req(a, 1, name); return Value.ofFloat(Math.tan(f(a[0]))); }
        if (name.equals("asin")) { req(a, 1, name); return Value.ofFloat(Math.asin(f(a[0]))); }
        if (name.equals("acos")) { req(a, 1, name); return Value.ofFloat(Math.acos(f(a[0]))); }
        if (name.equals("atan")) {
            if (a.length == 1) return Value.ofFloat(Math.atan(f(a[0])));
            req(a, 2, name);
            return Value.ofFloat(Math.atan2(f(a[0]), f(a[1])));
        }
        if (name.equals("atan2")) { req(a, 2, name); return Value.ofFloat(Math.atan2(f(a[0]), f(a[1]))); }
        if (name.equals("exp")) { req(a, 1, name); return Value.ofFloat(Math.exp(f(a[0]))); }
        if (name.equals("exp2")) { req(a, 1, name); return Value.ofFloat(Math.pow(2, f(a[0]))); }
        if (name.equals("log")) { req(a, 1, name); return Value.ofFloat(Math.log(f(a[0]))); }
        if (name.equals("log2")) { req(a, 1, name); return Value.ofFloat(Math.log(f(a[0])) / Math.log(2)); }
        if (name.equals("sqrt")) { req(a, 1, name); return Value.ofFloat(Math.sqrt(f(a[0]))); }
        if (name.equals("inversesqrt")) { req(a, 1, name); return Value.ofFloat(1.0 / Math.sqrt(f(a[0]))); }
        if (name.equals("pow")) { req(a, 2, name); return Value.ofFloat(Math.pow(f(a[0]), f(a[1]))); }
        if (name.equals("mod")) {
            req(a, 2, name);
            double x = f(a[0]), y = f(a[1]);
            return Value.ofFloat(y == 0 ? Double.NaN : x - Math.floor(x / y) * y);
        }
        if (name.equals("min")) { req(a, 2, name); return Value.ofFloat(Math.min(f(a[0]), f(a[1]))); }
        if (name.equals("max")) { req(a, 2, name); return Value.ofFloat(Math.max(f(a[0]), f(a[1]))); }
        if (name.equals("clamp")) {
            req(a, 3, name);
            return Value.ofFloat(Math.min(Math.max(f(a[0]), f(a[1])), f(a[2])));
        }
        if (name.equals("mix") || name.equals("lerp")) {
            req(a, 3, name);
            return Value.ofFloat(f(a[0]) + (f(a[1]) - f(a[0])) * f(a[2]));
        }
        if (name.equals("step")) { req(a, 2, name); return Value.ofFloat(f(a[0]) <= f(a[1]) ? 1 : 0); }
        if (name.equals("smoothstep")) {
            req(a, 3, name);
            double e0 = f(a[0]), e1 = f(a[1]), x = f(a[2]);
            if (e1 == e0) return Value.ofFloat(x < e0 ? 0 : 1);
            double t = Math.min(Math.max((x - e0) / (e1 - e0), 0), 1);
            return Value.ofFloat(t * t * (3 - 2 * t));
        }
        if (name.equals("radians")) { req(a, 1, name); return Value.ofFloat(Math.toRadians(f(a[0]))); }
        if (name.equals("degrees")) { req(a, 1, name); return Value.ofFloat(Math.toDegrees(f(a[0]))); }
        if (name.equals("length")) {
            if (a.length == 1 && a[0].size() > 1) {
                double[] c = a[0].components();
                double s = 0;
                for (double d : c) s += d * d;
                return Value.ofFloat(Math.sqrt(s));
            }
            req(a, 1, name);
            return Value.ofFloat(Math.abs(f(a[0])));
        }
        if (name.equals("distance")) {
            req(a, 2, name);
            double[] x = a[0].components(), y = a[1].components();
            int n = Math.min(x.length, y.length);
            double s = 0;
            for (int i = 0; i < n; i++) { double d = x[i] - y[i]; s += d * d; }
            return Value.ofFloat(Math.sqrt(s));
        }
        if (name.equals("dot")) {
            req(a, 2, name);
            double[] x = a[0].components(), y = a[1].components();
            int n = Math.min(x.length, y.length);
            double s = 0;
            for (int i = 0; i < n; i++) s += x[i] * y[i];
            return Value.ofFloat(s);
        }
        throw new ExprException("unsupported function '" + name + "' with " + a.length + " argument(s)");
    }

    /**
     * OptiFine/Iris {@code if} is variadic, not ternary.
     *
     * <p>{@code if(c1, v1, c2, v2, ..., else)} walks condition/value pairs and
     * returns the value of the first true condition, or the trailing else when
     * none matched. The argument count is therefore odd and at least 3. This is
     * exactly the shape AuraLite-Shaders uses for its water colour chain, and
     * requiring three arguments -- as a first implementation did -- refused the
     * pack outright. The gate below evaluates that chain verbatim.
     *
     * <p>Branches are evaluated lazily, pair by pair, so a later pair may read a
     * variable that an earlier pair would have failed on; that matches how the
     * CPU-side evaluator behaves in practice and keeps {@code smooth()} state
     * from advancing on a branch that was not taken.
     */
    private static Value chainedIf(String name, List<Expr> args, ExprEnv env) {
        int n = args.size();
        if (n < 3 || (n & 1) == 0) {
            throw new ExprException(name + "() takes an odd number of arguments >= 3 "
                    + "(condition, value, ..., else), got " + n);
        }
        for (int i = 0; i + 1 < n; i += 2) {
            if (args.get(i).eval(env).asBool()) return args.get(i + 1).eval(env);
        }
        return args.get(n - 1).eval(env);
    }

    private static void req(Value[] a, int n, String name) {
        if (a.length != n) throw new ExprException(name + "() takes " + n + " arguments, got " + a.length);
    }

    private static double f(Value v) { return v.toFloat().component(0); }

    private static boolean equalish(Value a, Value b) {
        if (a.kind() == Value.Kind.BOOL || b.kind() == Value.Kind.BOOL) return a.asBool() == b.asBool();
        return Math.abs(a.toFloat().component(0) - b.toFloat().component(0)) < 1e-9;
    }
}
