package dev.auralite.render.pack;

import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxEnums.Access;
import dev.auralite.render.rhi.GfxEnums.ImageType;
import dev.auralite.render.rhi.GfxEnums.PassType;
import dev.auralite.render.rhi.GfxUsage;
import dev.auralite.render.graph.RenderGraph;
import dev.auralite.render.uniform.ExprEnv;
import dev.auralite.render.uniform.ExprParser;
import dev.auralite.render.uniform.FunctionTable;
import dev.auralite.render.uniform.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manifest to {@link RenderGraph}.
 *
 * <p>Two entry points, one output. {@link #buildNative} walks the pack's
 * declared DAG; {@link #buildLegacy} synthesises the Iris-shaped frame from the
 * programs a legacy pack happens to contain. Both produce the same kind of
 * compiled graph, so a legacy pack runs on the new pipeline -- barriers,
 * lifetimes and all -- without being converted, and the two can be diffed
 * against each other pass by pass.
 */
public final class PackGraphBuilder {

    private static final Pattern COMPOSITE_INDEX = Pattern.compile("^composite(\\d*)$");
    private static final Pattern DEFERRED_INDEX = Pattern.compile("^deferred(\\d*)$");

    private final PackManifest manifest;
    private final PipelineProvider pipelines;

    public PackGraphBuilder(PackManifest manifest, PipelineProvider pipelines) {
        this.manifest = manifest;
        this.pipelines = pipelines;
    }

    public PackManifest manifest() { return manifest; }

    /** Dispatches on the manifest's format. */
    public RenderGraph build(int width, int height, int shadowMapSize, int[] biomeCells) {
        return manifest.format == PackFormat.NATIVE
                ? buildNative(width, height, shadowMapSize, biomeCells)
                : buildLegacy(width, height, shadowMapSize);
    }

    // ----------------------------------------------------------------- native
    public RenderGraph buildNative(int width, int height, int shadowMapSize, int[] biomeCells) {
        RenderGraph g = new RenderGraph("native:" + manifest.name);
        this.activeGraph = g;
        GraphSpec spec = manifest.graph;

        for (GraphSpec.ResourceSpec r : spec.resources()) {
            int[] ext = r.extent(width, height, shadowMapSize, biomeCells);
            GfxDesc.Image d;
            if (r.is3d) {
                d = GfxDesc.Image.tex3d(ext[0], ext[1], ext[2], r.format, r.usage);
            } else {
                d = GfxDesc.Image.tex2d(ext[0], ext[1], r.format, r.usage);
            }
            d.type = r.is3d ? ImageType.TEXTURE_3D : ImageType.TEXTURE_2D;
            d.mipLevels = r.mipLevels;
            d.arrayLayers = r.arrayLayers;
            if (r.imported) g.importTexture(r.name, d);
            else if (r.history) g.addHistory(r.name, d);
            else g.addTexture(r.name, d);
        }

        ExprEnv env = new ExprEnv(new FunctionTable());
        for (GraphSpec.PassSpec p : spec.passes()) {
            PassType type = p.isCompute() ? PassType.COMPUTE : (p.isCopy() ? PassType.COPY : PassType.RASTER);
            RenderGraph.PassBuilder b = g.addPass(p.name, type);

            for (int i = 0; i < p.colors.size(); i++) {
                String c = resolveHistoryName(p.colors.get(i));
                b.color(c);
            }
            if (!p.depth.isEmpty()) b.depth(resolveHistoryName(p.depth), true);
            for (String r : p.reads) b.read(resolveHistoryName(r), Access.SHADER_READ);
            for (String w : p.writes) {
                String name = resolveHistoryName(w);
                if (g.require(name).isTexture()) b.write(name, Access.SHADER_WRITE);
                else b.write(name, Access.TRANSFER_WRITE);
            }
            if (p.isCompute()) {
                int[] d = resolveDispatch(p, env, width, height);
                b.dispatch(d[0], d[1], d[2]);
            }
            if (!p.when.isEmpty()) {
                b.when(p.when, manifest.optionEnabled(p.when));
            }
            if (p.external) b.external();

            final String programName = p.program;
            final boolean compute = p.isCompute();
            b.body(new dev.auralite.render.graph.GraphPass.Executor() {
                @Override public void execute(dev.auralite.render.graph.GraphContext ctx) {
                    if (programName.isEmpty() || pipelines == null) return;
                    // The graph opens and closes the render pass around this
                    // body and sets the viewport to that pass's own area, so all
                    // a body does is bind its pipeline and issue its work.
                    if (compute) {
                        dev.auralite.render.rhi.GfxPipeline pipe = pipelines.computePipeline(programName);
                        if (pipe != null) {
                            ctx.cmd().bindComputePipeline(pipe);
                            ctx.cmd().dispatch(ctx.currentPass().groupsX(),
                                    ctx.currentPass().groupsY(), ctx.currentPass().groupsZ());
                        }
                    } else {
                        dev.auralite.render.rhi.GfxPipeline pipe =
                                pipelines.rasterPipeline(programName, currentPassDesc(ctx));
                        if (pipe != null) ctx.cmd().bindGraphicsPipeline(pipe);
                    }
                }
            });
        }
        return g;
    }

    /**
     * The render pass a body's pipeline must be compatible with.
     *
     * <p>It is this pass's own area, not the framebuffer's: building the
     * 256x256 cloud-shadow pipeline against 1920x1080 is wrong on Vulkan
     * (render-pass compatibility) and silently wrong on OpenGL (the viewport
     * would cover pixels the target does not have).
     */
    private GfxDesc.RenderPass currentPassDesc(dev.auralite.render.graph.GraphContext ctx) {
        int[] area = ctx.renderArea();
        return GfxDesc.RenderPass.of(area[0], area[1]).named(ctx.currentPass().name());
    }

    /** Maps {@code foo.prev} / {@code foo.cur} onto the history pair's real names. */
    private String resolveHistoryName(String declared) {
        for (dev.auralite.render.graph.GraphHistory h : historyList()) {
            if (declared.equals(h.logicalName() + ".prev")) return h.previousName();
            if (declared.equals(h.logicalName() + ".cur")) return h.currentName();
            if (declared.equals(h.logicalName())) return h.currentName();
        }
        return declared;
    }

    /** The graph currently being built, so history names resolve against it. */
    private RenderGraph activeGraph;

    private List<dev.auralite.render.graph.GraphHistory> historyList() {
        return activeGraph == null
                ? Collections.<dev.auralite.render.graph.GraphHistory>emptyList()
                : activeGraph.histories();
    }

    // ----------------------------------------------------------------- legacy
    /**
     * Synthesises the Iris-shaped frame from the programs present.
     *
     * <p>Order: {@code shadow} first, then the terrain-family gbuffers in Iris's
     * fixed sequence, then {@code deferred*}, {@code composite*} and
     * {@code final}. Programs the pack does not ship are skipped, which is how
     * Iris behaves too.
     */
    public RenderGraph buildLegacy(int width, int height, int shadowMapSize) {
        RenderGraph g = new RenderGraph("legacy:" + manifest.name);
        this.activeGraph = g;

        GfxDesc.Image colorDesc = GfxDesc.Image.tex2d(width, height,
                dev.auralite.render.rhi.GfxFormat.RGBA16F,
                GfxUsage.IMAGE_COLOR_ATTACHMENT | GfxUsage.IMAGE_SAMPLED);
        GfxDesc.Image depthDesc = GfxDesc.Image.tex2d(width, height,
                dev.auralite.render.rhi.GfxFormat.D32_SFLOAT,
                GfxUsage.IMAGE_DEPTH_STENCIL_ATTACHMENT | GfxUsage.IMAGE_SAMPLED);
        GfxDesc.Image shadowDesc = GfxDesc.Image.tex2d(shadowMapSize, shadowMapSize,
                dev.auralite.render.rhi.GfxFormat.D32_SFLOAT,
                GfxUsage.IMAGE_DEPTH_STENCIL_ATTACHMENT | GfxUsage.IMAGE_SAMPLED);

        g.addTexture("scene_color", colorDesc);
        g.addTexture("scene_depth", depthDesc);
        if (manifest.hasProgram("shadow") || manifest.hasProgram("shadow_solid")) {
            g.addTexture("shadow_depth", shadowDesc);
        }

        if (g.find("shadow_depth") != null) {
            g.addRasterPass("shadow_map")
                    .depth("shadow_depth", true)
                    // Nothing in the graph reads shadow_depth, and culling it
                    // would be correct graph reasoning and a frame with no
                    // shadows: legacy packs sample shadowtex0/shadowcolor0 from
                    // any later program through implicit bindings.
                    .keep("legacy packs sample shadowtex0/shadowcolor0 implicitly")
                    .body(noopBody("shadow"));
        }

        String[] terrainFamily = {
                "gbuffers_terrain", "gbuffers_block", "gbuffers_water", "gbuffers_weather",
                "gbuffers_clouds", "gbuffers_entities", "gbuffers_textured", "gbuffers_basic",
                "gbuffers_hand", "gbuffers_hand_water"
        };
        boolean first = true;
        for (String program : terrainFamily) {
            if (!usable(program)) continue;
            RenderGraph.PassBuilder b = g.addRasterPass(program)
                    .color("scene_color", first ? 1 : 0);
            if (g.find("scene_depth") != null) b.depth("scene_depth", first);
            b.body(noopBody(program));
            first = false;
        }

        for (String program : indexedPrograms(DEFERRED_INDEX)) {
            if (!usable(program)) continue;
            g.addRasterPass(program)
                    .color("scene_color", 0)
                    .read("scene_color", Access.SHADER_READ)
                    .body(noopBody(program));
        }

        for (String program : indexedPrograms(COMPOSITE_INDEX)) {
            if (!usable(program)) continue;
            g.addRasterPass(program)
                    .color("scene_color", 0)
                    .read("scene_color", Access.SHADER_READ)
                    .body(noopBody(program));
        }

        String last = manifest.hasProgram("final") ? "final"
                : (manifest.hasProgram("composite") ? "composite" : null);
        if (last != null) {
            g.addRasterPass("present")
                    .color("scene_color", 0)
                    .read("scene_color", Access.SHADER_READ)
                    .external()
                    .body(noopBody(last));
        }
        return g;
    }

    private boolean usable(String program) {
        PackProgram p = manifest.program(program);
        return p != null && p.enabled && p.stageCount() > 0;
    }

    /** Program names matching {@code pattern}, ordered by their numeric suffix. */
    private List<String> indexedPrograms(Pattern pattern) {
        List<int[]> withIndex = new ArrayList<int[]>();
        List<String> names = new ArrayList<String>();
        for (PackProgram p : manifest.programs()) {
            Matcher m = pattern.matcher(p.name);
            if (!m.matches()) continue;
            String digits = m.group(1);
            int idx = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            withIndex.add(new int[] { idx, withIndex.size() });
            names.add(p.name);
        }
        List<Integer> order = new ArrayList<Integer>();
        for (int i = 0; i < withIndex.size(); i++) order.add(i);
        Collections.sort(order, (a, b) -> Integer.compare(withIndex.get(a)[0], withIndex.get(b)[0]));
        List<String> out = new ArrayList<String>();
        for (int i : order) out.add(names.get(i));
        return out;
    }

    private dev.auralite.render.graph.GraphPass.Executor noopBody(final String program) {
        return new dev.auralite.render.graph.GraphPass.Executor() {
            @Override public void execute(dev.auralite.render.graph.GraphContext ctx) {
                if (pipelines == null) return;
                dev.auralite.render.rhi.GfxPipeline pipe = pipelines.rasterPipeline(program, null);
                if (pipe != null) ctx.cmd().bindGraphicsPipeline(pipe);
            }
        };
    }

    // --------------------------------------------------------------- dispatch
    /** Evaluates a dispatch clause such as {@code ceil(width/8),ceil(height/8),1}. */
    public static int[] resolveDispatch(GraphSpec.PassSpec p, ExprEnv env, int width, int height) {
        if (p.dispatchX > 0) return new int[] { p.dispatchX, Math.max(1, p.dispatchY), Math.max(1, p.dispatchZ) };
        if (p.dispatchExpr == null || p.dispatchExpr.trim().isEmpty()) {
            return new int[] { 1, 1, 1 };
        }
        env.bindInt("width", width);
        env.bindInt("height", height);
        List<String> parts = splitTopLevel(p.dispatchExpr, ',');
        int[] out = new int[] { 1, 1, 1 };
        for (int i = 0; i < Math.min(3, parts.size()); i++) {
            try {
                Value v = ExprParser.parse(parts.get(i).trim()).eval(env);
                out[i] = Math.max(1, v.asInt());
            } catch (RuntimeException e) {
                out[i] = 1;
            }
        }
        return out;
    }

    /** Splits on {@code sep} ignoring separators nested in parentheses. */
    public static List<String> splitTopLevel(String s, char sep) {
        List<String> out = new ArrayList<String>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == sep && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }
}
