package dev.auralite.render.uniform;

/** Carries the expression text and the offset, so a pack author can find the typo. */
public class ExprException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String expression;
    private final int offset;

    public ExprException(String message) { this(message, "", -1); }

    public ExprException(String message, String expression, int offset) {
        super(offset >= 0
                ? message + " at offset " + offset + " in '" + expression + "'"
                : (expression.isEmpty() ? message : message + " in '" + expression + "'"));
        this.expression = expression;
        this.offset = offset;
    }

    public String expression() { return expression; }
    public int offset() { return offset; }
}
