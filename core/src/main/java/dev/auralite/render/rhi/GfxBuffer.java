package dev.auralite.render.rhi;

import dev.auralite.render.rhi.GfxEnums.MemoryDomain;

public interface GfxBuffer extends GfxResource {
    int usage();
    MemoryDomain domain();
    /** True when the host may map this buffer directly. */
    boolean hostVisible();
}
