package dev.auralite.render.graph;

/** Always carries a named reason and, where useful, the offending path. */
public class GraphException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String passName;

    public GraphException(String message) { this(message, ""); }

    public GraphException(String message, String passName) {
        super(passName == null || passName.isEmpty() ? message : "[" + passName + "] " + message);
        this.passName = passName == null ? "" : passName;
    }

    public String passName() { return passName; }
}
