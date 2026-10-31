package dev.auralite.render.graph;

import dev.auralite.render.rhi.GfxEnums.ResourceState;

/**
 * One transition the backends must honour.
 *
 * <p>Vulkan turns this into a {@code VkImageMemoryBarrier2}; OpenGL turns it
 * into at most a {@code glMemoryBarrier} with the matching bit, and into
 * nothing at all when the driver's implicit tracking already covers it. The
 * graph emits the same list either way, so both lanes are driven by one
 * derivation and can be diffed against each other in the parity gate.
 */
public final class Barrier {
    public enum Target { IMAGE, BUFFER }

    public final Target target;
    public final GraphResource resource;
    public final ResourceState from;
    public final ResourceState to;
    public final int srcAccess;
    public final int dstAccess;
    /** Pass that required the transition; used in the receipts. */
    public final String requiredBy;

    public Barrier(Target target, GraphResource resource, ResourceState from, ResourceState to,
                   int srcAccess, int dstAccess, String requiredBy) {
        this.target = target;
        this.resource = resource;
        this.from = from;
        this.to = to;
        this.srcAccess = srcAccess;
        this.dstAccess = dstAccess;
        this.requiredBy = requiredBy;
    }

    /** True when nothing actually changes and the backend may skip the call. */
    public boolean isNoop() { return from == to && srcAccess == dstAccess; }

    @Override public String toString() {
        return resource.name() + ": " + from + " -> " + to + " @" + requiredBy;
    }
}
