package dev.auralite.render.uniform;

import java.util.List;

/** AST node. Immutable, evaluated against an {@link ExprEnv}. */
public abstract class Expr {

    public abstract Value eval(ExprEnv env);

    /** Rendered back to source form; the pack linter diffs this to prove a reparse is stable. */
    public abstract String source();

    public static final class Num extends Expr {
        public final double value;
        public final boolean integer;
        public Num(double value, boolean integer) { this.value = value; this.integer = integer; }
        @Override public Value eval(ExprEnv env) {
            return integer ? Value.ofInt((int) value) : Value.ofFloat(value);
        }
        @Override public String source() { return integer ? String.valueOf((long) value) : String.valueOf(value); }
    }

    public static final class Var extends Expr {
        public final String name;
        public Var(String name) { this.name = name; }
        @Override public Value eval(ExprEnv env) {
            Value v = env.lookup(name);
            if (v == null) {
                throw new ExprException("unknown identifier '" + name + "'", env.expressionText(), -1);
            }
            return v;
        }
        @Override public String source() { return name; }
    }

    public static final class Neg extends Expr {
        public final Expr operand;
        public Neg(Expr operand) { this.operand = operand; }
        @Override public Value eval(ExprEnv env) {
            Value v = operand.eval(env).toFloat();
            double[] c = v.components();
            for (int i = 0; i < c.length; i++) c[i] = -c[i];
            return Value.ofKind(v.kind(), c);
        }
        @Override public String source() { return "-" + operand.source(); }
    }

    public static final class Not extends Expr {
        public final Expr operand;
        public Not(Expr operand) { this.operand = operand; }
        @Override public Value eval(ExprEnv env) { return Value.bool(!operand.eval(env).asBool()); }
        @Override public String source() { return "!" + operand.source(); }
    }

    public static final class Binary extends Expr {
        public final String op;
        public final Expr left, right;
        public Binary(String op, Expr left, Expr right) { this.op = op; this.left = left; this.right = right; }

        @Override public Value eval(ExprEnv env) {
            // Short-circuit the logical operators exactly as GLSL does not, but as
            // Iris's CPU evaluator does: both sides are evaluated, no shortcut.
            Value a = left.eval(env), b = right.eval(env);
            switch (op) {
                case "&&": return Value.bool(a.asBool() && b.asBool());
                case "||": return Value.bool(a.asBool() || b.asBool());
                case "==": return Value.bool(equalish(a, b));
                case "!=": return Value.bool(!equalish(a, b));
                case ">": return Value.bool(a.toFloat().component(0) > b.toFloat().component(0));
                case ">=": return Value.bool(a.toFloat().component(0) >= b.toFloat().component(0));
                case "<": return Value.bool(a.toFloat().component(0) < b.toFloat().component(0));
                case "<=": return Value.bool(a.toFloat().component(0) <= b.toFloat().component(0));
                default: break;
            }
            Value fa = a.toFloat(), fb = b.toFloat();
            int n = Math.max(fa.size(), fb.size());
            double[] ca = fa.widen(n).components();
            double[] cb = fb.widen(n).components();
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                switch (op) {
                    case "+": out[i] = ca[i] + cb[i]; break;
                    case "-": out[i] = ca[i] - cb[i]; break;
                    case "*": out[i] = ca[i] * cb[i]; break;
                    case "/": out[i] = cb[i] == 0 ? Double.NaN : ca[i] / cb[i]; break;
                    case "%": out[i] = cb[i] == 0 ? Double.NaN : ca[i] - Math.floor(ca[i] / cb[i]) * cb[i]; break;
                    default: throw new ExprException("unsupported operator '" + op + "'");
                }
            }
            // Two ints stay ints for + - * %, matching GLSL. They do NOT for
            // '/', because GLSL refuses int/int outright and every pack author
            // who writes ceil(width/16) means float division. Flooring first
            // made ceil() a no-op and dropped the last partial row and column
            // of every compute dispatch -- at 1920x1080 with a 16x16 group the
            // bottom 8 rows of AO were never computed, and nothing complained.
            boolean integral = a.kind() == Value.Kind.INT && b.kind() == Value.Kind.INT
                    && !op.equals("/");
            if (integral) {
                for (int i = 0; i < n; i++) out[i] = Math.floor(out[i]);
                return Value.ofKind(n == 1 ? Value.Kind.INT : kindFor(n), out);
            }
            return Value.ofKind(n == 1 ? Value.Kind.FLOAT : kindFor(n), out);
        }

        private static Value.Kind kindFor(int n) {
            switch (n) { case 2: return Value.Kind.VEC2; case 3: return Value.Kind.VEC3; default: return Value.Kind.VEC4; }
        }

        private static boolean equalish(Value a, Value b) {
            if (a.kind() == Value.Kind.BOOL || b.kind() == Value.Kind.BOOL) return a.asBool() == b.asBool();
            double[] ca = a.toFloat().components(), cb = b.toFloat().components();
            if (ca.length != cb.length) return false;
            for (int i = 0; i < ca.length; i++) if (Math.abs(ca[i] - cb[i]) > 1e-9) return false;
            return true;
        }

        @Override public String source() { return left.source() + " " + op + " " + right.source(); }
    }

    public static final class Call extends Expr {
        public final String name;
        public final List<Expr> args;
        public Call(String name, List<Expr> args) { this.name = name; this.args = args; }

        @Override public Value eval(ExprEnv env) { return env.callFunction(name, args); }

        @Override public String source() {
            StringBuilder sb = new StringBuilder(name).append('(');
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(args.get(i).source());
            }
            return sb.append(')').toString();
        }
    }
}
