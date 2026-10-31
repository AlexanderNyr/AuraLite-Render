package dev.auralite.render.uniform;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for pack expressions.
 *
 * <p>Precedence, lowest first: {@code ||}, {@code &&}, equality, relational,
 * additive, multiplicative, unary, primary. Identifiers, decimal and
 * hexadecimal literals, {@code pi}, {@code true}/{@code false}, function calls
 * and parentheses.
 */
public final class ExprParser {
    private final String src;
    private int pos;

    public ExprParser(String src) {
        this.src = src == null ? "" : src;
    }

    public static Expr parse(String src) {
        ExprParser p = new ExprParser(src);
        Expr e = p.parseExpression();
        p.skipWs();
        if (p.pos < p.src.length()) {
            throw new ExprException("trailing input '" + p.src.substring(p.pos) + "'", src, p.pos);
        }
        return e;
    }

    // --------------------------------------------------------------- levels
    public Expr parseExpression() { return parseOr(); }

    private Expr parseOr() {
        Expr left = parseAnd();
        for (;;) {
            skipWs();
            if (match("||")) left = new Expr.Binary("||", left, parseAnd());
            else return left;
        }
    }

    private Expr parseAnd() {
        Expr left = parseEquality();
        for (;;) {
            skipWs();
            if (match("&&")) left = new Expr.Binary("&&", left, parseEquality());
            else return left;
        }
    }

    private Expr parseEquality() {
        Expr left = parseRelational();
        for (;;) {
            skipWs();
            if (match("==")) left = new Expr.Binary("==", left, parseRelational());
            else if (match("!=")) left = new Expr.Binary("!=", left, parseRelational());
            else return left;
        }
    }

    private Expr parseRelational() {
        Expr left = parseAdditive();
        for (;;) {
            skipWs();
            if (match(">=")) left = new Expr.Binary(">=", left, parseAdditive());
            else if (match("<=")) left = new Expr.Binary("<=", left, parseAdditive());
            else if (match(">")) left = new Expr.Binary(">", left, parseAdditive());
            else if (match("<")) left = new Expr.Binary("<", left, parseAdditive());
            else return left;
        }
    }

    private Expr parseAdditive() {
        Expr left = parseMultiplicative();
        for (;;) {
            skipWs();
            if (match("+")) left = new Expr.Binary("+", left, parseMultiplicative());
            else if (match("-")) left = new Expr.Binary("-", left, parseMultiplicative());
            else return left;
        }
    }

    private Expr parseMultiplicative() {
        Expr left = parseUnary();
        for (;;) {
            skipWs();
            if (match("*")) left = new Expr.Binary("*", left, parseUnary());
            else if (match("/")) left = new Expr.Binary("/", left, parseUnary());
            else if (match("%")) left = new Expr.Binary("%", left, parseUnary());
            else return left;
        }
    }

    private Expr parseUnary() {
        skipWs();
        if (match("-")) return new Expr.Neg(parseUnary());
        if (match("+")) return parseUnary();
        if (match("!")) return new Expr.Not(parseUnary());
        return parsePrimary();
    }

    private Expr parsePrimary() {
        skipWs();
        if (pos >= src.length()) throw new ExprException("unexpected end of expression", src, pos);
        char c = src.charAt(pos);
        if (c == '(') {
            pos++;
            Expr e = parseExpression();
            skipWs();
            if (!match(")")) throw new ExprException("expected ')'", src, pos);
            return e;
        }
        if (Character.isDigit(c) || (c == '.' && pos + 1 < src.length() && Character.isDigit(src.charAt(pos + 1)))) {
            return parseNumber();
        }
        if (Character.isLetter(c) || c == '_') return parseIdentifierOrCall();
        throw new ExprException("unexpected character '" + c + "'", src, pos);
    }

    private Expr parseNumber() {
        int start = pos;
        if (pos + 1 < src.length() && src.charAt(pos) == '0'
                && (src.charAt(pos + 1) == 'x' || src.charAt(pos + 1) == 'X')) {
            pos += 2;
            int hs = pos;
            while (pos < src.length() && isHex(src.charAt(pos))) pos++;
            if (pos == hs) throw new ExprException("empty hex literal", src, start);
            return new Expr.Num(Long.parseLong(src.substring(hs, pos), 16), true);
        }
        boolean integer = true;
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        if (pos < src.length() && src.charAt(pos) == '.') { integer = false; pos++; }
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
        if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
            integer = false;
            pos++;
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
            int es = pos;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            if (pos == es) throw new ExprException("empty exponent", src, start);
        }
        // Trailing type suffixes legacy packs sometimes carry.
        if (pos < src.length() && (src.charAt(pos) == 'f' || src.charAt(pos) == 'F')) pos++;
        String text = src.substring(start, pos);
        try {
            double d = Double.parseDouble(text.replace("f", "").replace("F", ""));
            return new Expr.Num(d, integer);
        } catch (NumberFormatException e) {
            throw new ExprException("malformed number '" + text + "'", src, start);
        }
    }

    private Expr parseIdentifierOrCall() {
        int start = pos;
        while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_'
                || src.charAt(pos) == '.')) {
            pos++;
        }
        String name = src.substring(start, pos);
        skipWs();
        if (pos < src.length() && src.charAt(pos) == '(') {
            pos++;
            List<Expr> args = new ArrayList<Expr>();
            skipWs();
            if (!match(")")) {
                for (;;) {
                    args.add(parseExpression());
                    skipWs();
                    if (match(",")) continue;
                    if (match(")")) break;
                    throw new ExprException("expected ',' or ')' in argument list of " + name, src, pos);
                }
            }
            return new Expr.Call(name, args);
        }
        if (name.equals("pi") || name.equals("PI")) return new Expr.Num(Math.PI, false);
        if (name.equals("true")) return new Expr.Num(1, true);
        if (name.equals("false")) return new Expr.Num(0, true);
        if (name.equals("e") || name.equals("E")) return new Expr.Num(Math.E, false);
        return new Expr.Var(name);
    }

    // --------------------------------------------------------------- helpers
    private void skipWs() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
    }

    private boolean match(String tok) {
        if (src.startsWith(tok, pos)) { pos += tok.length(); return true; }
        return false;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
