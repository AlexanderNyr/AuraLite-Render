package dev.auralite.render.uniform;

import java.util.Arrays;

/**
 * A scalar or small vector produced by a pack expression.
 *
 * <p>Iris's evaluator is scalar-plus-vec2/3/4-constructor; this keeps exactly
 * that surface so LEGACY packs evaluate identically, and adds nothing that
 * would let a legacy expression silently change meaning.
 */
public final class Value {
    public enum Kind { BOOL, INT, FLOAT, VEC2, VEC3, VEC4 }

    public static final Value TRUE = new Value(Kind.BOOL, new double[] { 1 });
    public static final Value FALSE = new Value(Kind.BOOL, new double[] { 0 });
    public static final Value ZERO_F = new Value(Kind.FLOAT, new double[] { 0 });
    public static final Value ONE_F = new Value(Kind.FLOAT, new double[] { 1 });

    private final Kind kind;
    private final double[] components;

    private Value(Kind kind, double[] components) {
        this.kind = kind;
        this.components = components;
    }

    public static Value bool(boolean b) { return b ? TRUE : FALSE; }
    public static Value ofInt(int i) { return new Value(Kind.INT, new double[] { i }); }
    public static Value ofFloat(double d) { return new Value(Kind.FLOAT, new double[] { d }); }
    public static Value vec(double... c) {
        switch (c.length) {
            case 1: return new Value(Kind.FLOAT, new double[] { c[0] });
            case 2: return new Value(Kind.VEC2, c.clone());
            case 3: return new Value(Kind.VEC3, c.clone());
            case 4: return new Value(Kind.VEC4, c.clone());
            default: throw new ExprException("vector literal needs 1..4 components, got " + c.length);
        }
    }
    public static Value ofKind(Kind k, double[] c) { return new Value(k, c.clone()); }

    public Kind kind() { return kind; }
    public double[] components() { return components.clone(); }
    public int size() { return components.length; }

    public boolean asBool() { return components[0] != 0; }
    public int asInt() { return (int) Math.round(components[0]); }
    public double asDouble() { return components[0]; }
    public float asFloat() { return (float) components[0]; }
    public double component(int i) { return components[i]; }

    public boolean isScalar() { return kind == Kind.BOOL || kind == Kind.INT || kind == Kind.FLOAT; }
    public boolean isNumeric() { return kind == Kind.INT || kind == Kind.FLOAT; }

    /**
     * Numeric promotion: bool and int become float, vectors keep their width.
     *
     * <p>Collapse-to-scalar here was a real defect and the vector-arithmetic
     * gate caught it: {@code vec3(1,2,3) * 2} returned the scalar 2 instead of
     * {@code vec3(2,4,6)} because the promotion threw away components 1 and 2.
     */
    public Value toFloat() {
        if (kind == Kind.FLOAT || kind == Kind.VEC2 || kind == Kind.VEC3 || kind == Kind.VEC4) return this;
        return new Value(Kind.FLOAT, new double[] { components[0] });
    }

    /** Component-wise widen to n components, repeating the last like GLSL does. */
    public Value widen(int n) {
        if (components.length == n) return this;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = components[Math.min(i, components.length - 1)];
        return new Value(kindForWidth(n), out);
    }

    private Kind kindForWidth(int n) {
        switch (n) {
            case 1: return kind == Kind.BOOL ? Kind.FLOAT : (kind == Kind.INT ? Kind.INT : Kind.FLOAT);
            case 2: return Kind.VEC2;
            case 3: return Kind.VEC3;
            default: return Kind.VEC4;
        }
    }

    @Override public String toString() {
        if (kind == Kind.BOOL) return asBool() ? "true" : "false";
        StringBuilder sb = new StringBuilder(kind.name().toLowerCase(java.util.Locale.ROOT));
        sb.append('(');
        for (int i = 0; i < components.length; i++) {
            if (i > 0) sb.append(", ");
            double d = components[i];
            if (kind == Kind.INT) sb.append((long) d);
            else sb.append(trim(d));
        }
        return sb.append(')').toString();
    }

    private static String trim(double d) {
        String s = Double.toString(d);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s;
    }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Value)) return false;
        Value v = (Value) o;
        return kind == v.kind && Arrays.equals(components, v.components);
    }

    @Override public int hashCode() { return kind.hashCode() * 31 + Arrays.hashCode(components); }
}
