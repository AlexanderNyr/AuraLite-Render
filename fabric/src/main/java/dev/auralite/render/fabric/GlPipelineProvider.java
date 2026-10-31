package dev.auralite.render.fabric;

import dev.auralite.render.backend.gl.GlDevice;
import dev.auralite.render.pack.PackManifest;
import dev.auralite.render.pack.PackProgram;
import dev.auralite.render.pack.PipelineProvider;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxPipeline;
import dev.auralite.render.rhi.GfxStage;

import java.util.HashMap;
import java.util.Map;

/**
 * The "real renderer" PipelineProvider the pack-builder javadoc promises:
 * pack programs resolved into GL pipelines, with a cache in front.
 *
 * <p>Cache keys include the pass shape (colour count + depth presence),
 * because a pipeline created against one attachment layout is the wrong
 * pipeline for another - the 256x256 cloud-shadow pass and the 1920x1080
 * lighting pass must not share a key just because they share a program.
 * Shader stages come from the pack's own sources: GL compiles GLSL directly
 * and takes the SPIR-V path where {@code GL_ARB_gl_spirv} exists (RHI.md).
 *
 * <p>Compile- and behaviour-verified only on a lane with a JDK 21 and a GPU
 * (ROADMAP Phase 4/5); in this sandbox it is source-reviewed, not run.
 */
public final class GlPipelineProvider implements PipelineProvider {

    private final GlDevice device;
    private final PackManifest pack;
    private final Map<String, GfxPipeline> cache = new HashMap<String, GfxPipeline>();

    public GlPipelineProvider(GlDevice device, PackManifest pack) {
        this.device = device;
        this.pack = pack;
    }

    @Override
    public GfxPipeline rasterPipeline(String programName, GfxDesc.RenderPass pass) {
        String key = "r:" + programName + ":" + pass.colorCount()
                + ":" + (pass.depth != null ? "d" : "-")
                + ":" + pass.width + "x" + pass.height;
        GfxPipeline hit = cache.get(key);
        if (hit != null) return hit;

        PackProgram p = pack.program(programName);
        GfxDesc.GraphicsPipeline desc = new GfxDesc.GraphicsPipeline().named(programName);
        if (p != null && p.stage(GfxStage.VERTEX) != null) {
            desc.stage(device.createShaderModule(p.stage(GfxStage.VERTEX)));
        }
        if (p != null && p.stage(GfxStage.FRAGMENT) != null) {
            desc.stage(device.createShaderModule(p.stage(GfxStage.FRAGMENT)));
        }
        GfxPipeline pipe = device.createGraphicsPipeline(desc);
        cache.put(key, pipe);
        return pipe;
    }

    @Override
    public GfxPipeline computePipeline(String programName) {
        String key = "c:" + programName;
        GfxPipeline hit = cache.get(key);
        if (hit != null) return hit;

        PackProgram p = pack.program(programName);
        if (p == null || p.stage(GfxStage.COMPUTE) == null) return null;
        GfxPipeline pipe = device.createComputePipeline(GfxDesc.ComputePipeline
                .of(device.createShaderModule(p.stage(GfxStage.COMPUTE)))
                .localSize(p.localSizeX, p.localSizeY, p.localSizeZ)
                .named(programName));
        cache.put(key, pipe);
        return pipe;
    }

    @Override
    public boolean has(String programName) {
        PackProgram p = pack.program(programName);
        return p != null && p.stageCount() > 0;
    }

    /** Called on pack reload/resize: pipelines are pack- and size-bound. */
    public void invalidate() {
        for (GfxPipeline pipe : cache.values()) pipe.close();
        cache.clear();
    }
}
