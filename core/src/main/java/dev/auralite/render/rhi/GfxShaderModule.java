package dev.auralite.render.rhi;

/**
 * A compiled shader stage.
 *
 * <p>The GL backend compiles GLSL 460 on the driver; the Vulkan backend
 * consumes SPIR-V. Both report the same interface, and both keep the original
 * source (or its hash) so the pack hot-reload path can decide whether a file
 * edit actually needs a recompile.
 */
public interface GfxShaderModule extends GfxResource {
    GfxStage stage();
    String entryPoint();
    /** True when compilation succeeded. A failed module never reaches a pipeline. */
    boolean isValid();
    /** Compiler info log, empty on success. Surfaced verbatim to the pack author. */
    String infoLog();
    /** Source hash used by hot reload. */
    long sourceHash();
}
