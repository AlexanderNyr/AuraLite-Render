package dev.auralite.render.rhi;

/** Raised with a named reason; never a bare RuntimeException with no message. */
public class GfxException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String reason;

    public GfxException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public GfxException(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    public String reason() { return reason; }
}
