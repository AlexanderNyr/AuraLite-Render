package dev.auralite.render.pack;

import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxPipeline;

/**
 * Resolves a pack program name into a device pipeline.
 *
 * <p>Separated from the graph builder so the builder stays testable: the host
 * gates supply a provider backed by the NULL device, the real renderer supplies
 * one backed by GL46 or Vulkan with a pipeline cache in front.
 */
public interface PipelineProvider {
    /** A raster pipeline for {@code programName} against a concrete pass shape. */
    GfxPipeline rasterPipeline(String programName, GfxDesc.RenderPass pass);

    /** A compute pipeline for {@code programName}, or {@code null} when absent. */
    GfxPipeline computePipeline(String programName);

    /** True when the pack supplies this program at all. */
    boolean has(String programName);

    /**
     * Drops every pipeline and shader module this provider created. Called on
     * pack reload, resize and debug-view rebuilds: pipelines are pack- and
     * size-bound. The no-op default keeps the host test providers (null
     * device, no cache) interface-compatible.
     */
    default void invalidate() { }
}
