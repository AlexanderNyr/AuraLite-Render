package dev.auralite.render.rhi;

public interface GfxPipeline extends GfxResource {
    boolean isCompute();
    boolean isValid();
    String infoLog();
    GfxDesc.PipelineLayout layout();
}
