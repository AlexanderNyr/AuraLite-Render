package dev.auralite.render.fabric;

import dev.auralite.render.pack.PackManifest;
import dev.auralite.render.pack.PackProgram;
import dev.auralite.render.pack.PipelineProvider;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxDevice;
import dev.auralite.render.rhi.GfxPipeline;
import dev.auralite.render.rhi.GfxShaderModule;
import dev.auralite.render.rhi.GfxStage;
import dev.auralite.render.rhi.ShaderSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The VK twin of {@link GlPipelineProvider}: pack programs resolved into
 * Vulkan pipelines with the same cache keys (pass shape + size) and the same
 * option-define lane - except every GLSL stage is compiled to SPIR-V through
 * {@link SpirvCompiler} first, because the Vulkan backend takes SPIR-V only.
 *
 * <p>The pipeline description mirrors the gpu-test VK lane's provider
 * (renderPass + layout), which is the path the discrete-gt710 gate ran.
 * Defines are prepended before compilation: F9 and hot-reload change the
 * active option set, and the compiler's text-hash cache key keeps the
 * variants apart.
 *
 * <p>Behaviour-verified on a GPU lane only (ROADMAP Phase 4 VK-windowed);
 * in this sandbox it is source-reviewed, like its GL twin was before its lane.
 */
public final class VulkanPipelineProvider implements PipelineProvider {

    private final GfxDevice device;
    private final PackManifest pack;
    private final SpirvCompiler compiler = new SpirvCompiler();
    private final Map<String, GfxPipeline> cache = new HashMap<String, GfxPipeline>();
    /** Stages this provider created - closed with the cache on invalidate. */
    private final List<GfxShaderModule> modules = new ArrayList<GfxShaderModule>();

    public VulkanPipelineProvider(GfxDevice device, PackManifest pack) {
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
        GfxDesc.GraphicsPipeline desc = new GfxDesc.GraphicsPipeline()
                .renderPass(pass)
                .layout(p != null ? p.layout() : null)
                .named(programName);
        if (p != null && p.stage(GfxStage.VERTEX) != null) {
            GfxShaderModule vs = device.createShaderModule(
                    compiler.toSpirv(optionDefines(p.stage(GfxStage.VERTEX))));
            modules.add(vs);
            desc.stage(vs);
        }
        if (p != null && p.stage(GfxStage.FRAGMENT) != null) {
            GfxShaderModule fs = device.createShaderModule(
                    compiler.toSpirv(optionDefines(p.stage(GfxStage.FRAGMENT))));
            modules.add(fs);
            desc.stage(fs);
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
        GfxShaderModule cs = device.createShaderModule(
                compiler.toSpirv(optionDefines(p.stage(GfxStage.COMPUTE))));
        modules.add(cs);
        GfxPipeline pipe = device.createComputePipeline(GfxDesc.ComputePipeline
                .of(cs)
                .localSize(p.localSizeX, p.localSizeY, p.localSizeZ)
                .layout(p.layout())
                .named(programName));
        cache.put(key, pipe);
        return pipe;
    }

    @Override
    public boolean has(String programName) {
        PackProgram p = pack.program(programName);
        return p != null && p.stageCount() > 0;
    }

    /**
     * The stage with the pack's active option defines prepended - the option
     * lane reaching glslangValidator. Same stripping as GlPipelineProvider:
     * activeDefines() hands out complete "#define NAME value" lines, so the
     * "#define " prefix comes off before withDefines re-adds it.
     */
    private ShaderSource optionDefines(ShaderSource src) {
        if (src == null || src.kind() != ShaderSource.Kind.GLSL) return src;
        List<String> bare = new ArrayList<String>();
        for (String line : pack.activeDefines()) {
            if (line.startsWith("#define ")) line = line.substring("#define ".length());
            if (!line.isEmpty()) bare.add(line);
        }
        return bare.isEmpty() ? src : src.withDefines(bare);
    }

    /** Called on pack reload/resize/debug-view: pipelines and stages die. */
    @Override
    public void invalidate() {
        for (GfxPipeline pipe : cache.values()) pipe.close();
        cache.clear();
        for (GfxShaderModule m : modules) m.close();
        modules.clear();
        compiler.clearCache();
    }
}
