package dev.auralite.render.graph;

import dev.auralite.render.rhi.GfxEnums.Access;
import dev.auralite.render.rhi.GfxEnums.ResourceState;

/** One resource usage inside one pass. */
public final class GraphAccess {
    public final GraphResource resource;
    public final int access;
    /** Attachment slot when the resource is a colour attachment, else -1. */
    public final int attachmentSlot;
    public final int mipLevel;

    public GraphAccess(GraphResource resource, int access, int attachmentSlot, int mipLevel) {
        this.resource = resource;
        this.access = access;
        this.attachmentSlot = attachmentSlot;
        this.mipLevel = mipLevel;
    }

    public boolean isWrite() { return Access.isWrite(access); }
    public boolean isRead() { return !isWrite(); }

    /** The state a resource must be in for this access. */
    public ResourceState impliedState() {
        if ((access & Access.COLOR_ATTACHMENT_WRITE) != 0) return ResourceState.COLOR_ATTACHMENT_WRITE;
        if ((access & Access.COLOR_ATTACHMENT_READ) != 0) return ResourceState.COLOR_ATTACHMENT_WRITE;
        if ((access & Access.DEPTH_STENCIL_WRITE) != 0) return ResourceState.DEPTH_STENCIL_WRITE;
        if ((access & Access.DEPTH_STENCIL_READ) != 0) return ResourceState.DEPTH_STENCIL_READ;
        if ((access & Access.SHADER_WRITE) != 0) return ResourceState.STORAGE_WRITE;
        if ((access & Access.SHADER_READ) != 0) return ResourceState.SHADER_READ;
        if ((access & Access.INPUT_ATTACHMENT_READ) != 0) return ResourceState.SHADER_READ;
        if ((access & Access.TRANSFER_READ) != 0) return ResourceState.COPY_SOURCE;
        if ((access & Access.TRANSFER_WRITE) != 0) return ResourceState.COPY_DEST;
        if ((access & Access.HOST_READ) != 0) return ResourceState.HOST_READ;
        return ResourceState.UNDEFINED;
    }

    @Override public String toString() {
        return resource.name() + (isWrite() ? "[w]" : "[r]") + "->" + impliedState();
    }
}
