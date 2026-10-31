package dev.auralite.render.rhi;

/** Common behaviour of every GPU-owned handle. */
public interface GfxResource extends AutoCloseable {
    /** Backend-private handle; stable for the lifetime of the resource. */
    long handle();

    /** Human-readable name, taken from the pack declaration when available. */
    String debugName();

    /** Approximate device memory owned by this resource, in bytes. */
    long sizeBytes();

    @Override void close();
}
