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
import java.util.HashMap;
import java.util.Map;
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
    // The frame extents of the build in progress: only the dispatch-base
    // resolution needs them after the resource loop has run.
    private int lastWidth = 1, lastHeight = 1;
    private int[] lastBiomeCells;
    private int lastShadowSize;

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
        lastWidth = width;
        lastHeight = height;
        lastShadowSize = shadowMapSize;
        lastBiomeCells = biomeCells;
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

        // A history resource is a DOUBLE BUFFER: the graph keeps two resources
        // (`taa.0`, `taa.1`) and swaps the IMAGES behind them once per frame, so
        // the resource a pass writes keeps its NAME forever while the buffers
        // alternate. Resolving a shader's `taa_cur` against the current phase at
        // BIND time therefore answers with the other frame's side - which read
        // as "the final pass sampled an empty buffer while the drawn one sat
        // next to it". The answer the passes themselves used was fixed here, at
        // build time, so the shader binds must use that same answer.
        this.historyAtBuild = new HashMap<String, String>();
        for (dev.auralite.render.graph.GraphHistory h : g.histories()) {
            String log = h.logicalName();
            historyAtBuild.put(log + ".cur", h.currentName());
            historyAtBuild.put(log + "_cur", h.currentName());
            historyAtBuild.put(log + ".prev", h.previousName());
            historyAtBuild.put(log + "_prev", h.previousName());
            historyAtBuild.put(log, h.currentName());
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
                int[] d = resolveDispatch(p, env, width, height, dispatchBase(p));
                b.dispatch(d[0], d[1], d[2]);
            }
            if (!p.when.isEmpty()) {
                b.when(p.when, manifest.optionEnabled(p.when));
            }
            if (p.external) b.external();

            final String programName = p.program;
            final boolean compute = p.isCompute();
            // The widened seam's freshness gate, wired where the plan said it
            // belongs: a pass that READS the volume refuses a stale one by name
            // before recording a single command. A pack that declares no volume
            // is untouched.
            //
            // What this deliberately does NOT do is mark the volume current when
            // the producer pass dispatches. A dispatch is not proof of a bake:
            // whether the GPU's copy was rebuilt from the field the CPU currently
            // holds depends on the host's upload, which happens outside the graph
            // and outside this file. Marking on dispatch would let the gate certify
            // itself, which is the one failure mode worth designing against. The
            // record belongs to whoever actually baked (BiomeSeamVolume.bake), and
            // the graph's job is only to refuse to read past it.
            final String seamRead = biomeVolumeReadBy(p);
            b.body(new dev.auralite.render.graph.GraphPass.Executor() {
                @Override public void execute(dev.auralite.render.graph.GraphContext ctx) {
                    if (seamRead != null) {
                        dev.auralite.render.biome.BiomeSeamVolume vol =
                                ctx.frame() == null ? null : ctx.frame().biomeVolume(seamRead);
                        if (vol != null) {
                            // BIOME_VOLUME_STALE, thrown before a single
                            // command is recorded: a stale seam looks
                            // plausible, so it must not reach the screen.
                            vol.freshness().checkFresh(ctx.currentPass().name());
                        }
                    }
                    if (programName.isEmpty() || pipelines == null) return;
                    // The graph opens and closes the render pass around this
                    // body and sets the viewport to that pass's own area, so all
                    // a body does is bind its pipeline and issue its work.
                    if (compute) {
                        dev.auralite.render.rhi.GfxPipeline pipe = pipelines.computePipeline(programName);
                        if (pipe != null) {
                            ctx.cmd().bindComputePipeline(pipe);
                            bindImageSets(ctx, programName);
                            ctx.cmd().dispatch(ctx.currentPass().groupsX(),
                                    ctx.currentPass().groupsY(), ctx.currentPass().groupsZ());
                        }
                    } else {
                        dev.auralite.render.rhi.GfxPipeline pipe =
                                pipelines.rasterPipeline(programName, currentPassDesc(ctx));
                        if (pipe != null) {
                            ctx.cmd().bindGraphicsPipeline(pipe);
                            bindImageSets(ctx, programName);
                            // One fullscreen triangle per raster pass: the pack
                            // shaders carry the gl_VertexIndex convention (the
                            // reference pack's final.vert builds the whole quad
                            // from it), and stages that declare vertex inputs
                            // read the standard unbound-attribute constant.
                            ctx.cmd().draw(3, 1, 0, 0);
                        }
                    }
                }
            });
        }
        return g;
    }

    /**
     * Binds one descriptor set per set index the program's image declarations
     * use. Names come from {@link PackProgram#imageDecls()} and resolve
     * against graph resources by exact equality - a name the graph did not
     * allocate throws by name here, and a provider that never attached
     * {@link PackProgram#layout()} is caught by the device's bind validation.
     * Both are deliberate: the binding contract fails loud, never silent.
     */
    private void bindImageSets(dev.auralite.render.graph.GraphContext ctx, String programName) {
        PackProgram program = manifest.program(programName);
        if (program == null) return;
        java.util.List<PackProgram.ImageDecl> decls = program.imageDecls();
        if (decls.isEmpty()) return;
        java.util.Map<Integer, dev.auralite.render.rhi.GfxDescriptorSet> sets =
                new java.util.TreeMap<Integer, dev.auralite.render.rhi.GfxDescriptorSet>();
        for (PackProgram.ImageDecl d : decls) {
            dev.auralite.render.rhi.GfxDescriptorSet set = sets.get(d.set);
            if (set == null) {
                set = new dev.auralite.render.rhi.GfxDescriptorSet(d.set);
                sets.put(d.set, set);
            }
            // A history resource is a double buffer in the graph (`taa.0`/`taa.1`)
            // but the shader names the logical one (`taa_cur`). Resolving here
            // is the same rule the pass declaration follows, so a stage and a
            // `read=` line cannot disagree about which side they mean.
            String resolved = historyAtBuild != null && historyAtBuild.containsKey(d.name)
                    ? historyAtBuild.get(d.name)
                    : resolveHistoryName(d.name, ctx.graph().graph());
            if (d.kind == dev.auralite.render.rhi.GfxDesc.PipelineLayout.Kind.STORAGE_IMAGE) {
                set.add(dev.auralite.render.rhi.GfxDescriptorSet.Write.storageImage(
                        d.binding, ctx.image(resolved)));
            } else {
                // A pack that wants to SAMPLE an image declares a sampler/texture
                // and binds by resource name, the same way a storage image does.
                // This used to throw: the bridge wired storage images only, so a
                // pass like the reference pack's `terrain` - declared
                // `read=biome_volume` since step 2 - could not read a texel of it
                // even if its shader had asked. A sampler is a property of the
                // READ, not of the resource, so it is created here and filtered
                // NEAREST: hardware filtering would interpolate packed id bytes,
                // and the average of two ids is a third id.
                set.add(dev.auralite.render.rhi.GfxDescriptorSet.Write.sampler(
                        d.binding, ctx.image(resolved), samplerNearest(ctx.device())));
            }
        }
        for (dev.auralite.render.rhi.GfxDescriptorSet set : sets.values()) {
            ctx.cmd().bindDescriptorSet(set.setIndex(), set);
        }
    }

    /** One nearest-clamp sampler per device, shared by every sampled read. */
    private dev.auralite.render.rhi.GfxSampler samplerNearest(
            dev.auralite.render.rhi.GfxDevice device) {
        if (nearestSampler == null) {
            nearestSampler = device.createSampler(dev.auralite.render.rhi.GfxDesc.Sampler
                    .nearestClamp().named("pack-sampled-nearest"));
        }
        return nearestSampler;
    }

    private dev.auralite.render.rhi.GfxSampler nearestSampler;

    /** History bindings as the passes resolved them when the graph was built. */
    private Map<String, String> historyAtBuild;

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
        GfxDesc.RenderPass rp = GfxDesc.RenderPass.of(area[0], area[1])
                .named(ctx.currentPass().name());
        for (dev.auralite.render.graph.GraphAccess a
                : ctx.currentPass().colorAttachments()) {
            rp.color(GfxDesc.Attachment.of(ctx.image(a.resource.name())));
        }
        dev.auralite.render.graph.GraphAccess d =
                ctx.currentPass().depthAttachment();
        if (d != null) rp.depth(GfxDesc.Attachment.of(ctx.image(d.resource.name())));
        return rp;
    }

    /**
     * The widened-seam resource this pass reads, or null. The write side is
     * deliberately absent: a dispatch is not a bake, so the graph has nothing to
     * record when the producer runs (see the note at the pass body).
     */
    private String biomeVolumeReadBy(GraphSpec.PassSpec p) {
        for (String r : p.reads) {
            if (isBiomeSeamResource(r)) return r;
        }
        return null;
    }

    /** A resource is a widened seam when the pack declared it at biome-volume size. */
    private boolean isBiomeSeamResource(String name) {
        GraphSpec.ResourceSpec r = manifest.graph.findResource(name);
        return r != null && r.sizeKind == GraphSpec.SizeKind.BIOME_VOLUME;
    }

    /** Maps {@code foo.prev} / {@code foo.cur} onto the history pair's real names. */
    /**
     * The same resolution, against a graph handed in at call time.
     *
     * <p>Needed because descriptor binding happens while the frame RUNS, when
     * the builder no longer holds the graph it built: resolving against
     * {@link #activeGraph} there always answered "no such history" and the bind
     * failed with `unknown resource 'taa_cur'` on a pack that had just been
     * compiled successfully. A lookup that depends on build-time state cannot
     * serve a frame-time caller.
     */
    private String resolveHistoryName(String declared, dev.auralite.render.graph.RenderGraph graph) {
        if (graph == null) return declared;
        for (dev.auralite.render.graph.GraphHistory h : graph.histories()) {
            // Both spellings: a pass declaration writes `taa.cur` (the pack
            // format's dotted form) while a GLSL uniform cannot contain a dot,
            // so a shader that samples the history writes `taa_cur`. A resolver
            // that only knew the dotted form answered "no such resource" for a
            // stage that had compiled and bound fine everywhere else.
            if (declared.equals(h.logicalName() + ".prev")
                    || declared.equals(h.logicalName() + "_prev")) return h.previousName();
            if (declared.equals(h.logicalName() + ".cur")
                    || declared.equals(h.logicalName() + "_cur")) return h.currentName();
            if (declared.equals(h.logicalName())) return h.currentName();
        }
        return declared;
    }

    private String resolveHistoryName(String declared) {
        for (dev.auralite.render.graph.GraphHistory h : historyList()) {
            // Both spellings: a pass declaration writes `taa.cur` (the pack
            // format's dotted form) while a GLSL uniform cannot contain a dot,
            // so a shader that samples the history writes `taa_cur`. A resolver
            // that only knew the dotted form answered "no such resource" for a
            // stage that had compiled and bound fine everywhere else.
            if (declared.equals(h.logicalName() + ".prev")
                    || declared.equals(h.logicalName() + "_prev")) return h.previousName();
            if (declared.equals(h.logicalName() + ".cur")
                    || declared.equals(h.logicalName() + "_cur")) return h.currentName();
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
    /**
     * The extent {@code width}/{@code height} in a pass's dispatch expression.
     * A bake is sized by the image it fills, not by the screen; anything else
     * leaves it wide enough to dispatch thousands of out-of-range invocations,
     * which reads as "the volume did not update" rather than as an error.
     */
    private int[] dispatchBase(GraphSpec.PassSpec p) {
        if (!"write".equals(p.dispatchBase) || p.writes.isEmpty()) return null;
        GraphSpec.ResourceSpec r = manifest.graph.findResource(p.writes.get(0));
        if (r == null) return null;
        int[] ext = r.extent(lastWidth, lastHeight, lastShadowSize, lastBiomeCells);
        return ext;
    }

    public static int[] resolveDispatch(GraphSpec.PassSpec p, ExprEnv env, int width, int height) {
        return resolveDispatch(p, env, width, height, null);
    }

    public static int[] resolveDispatch(GraphSpec.PassSpec p, ExprEnv env, int width, int height,
                                        int[] base) {
        if (p.dispatchX > 0) return new int[] { p.dispatchX, Math.max(1, p.dispatchY), Math.max(1, p.dispatchZ) };
        if (p.dispatchExpr == null || p.dispatchExpr.trim().isEmpty()) {
            return new int[] { 1, 1, 1 };
        }
        // width/height/depth are the extents a pass dispatches over. Without a
        // resource base they are the framebuffer's, which has no depth; with
        // one they are the target image's, which for a 3D bake is the whole
        // point -- a dispatch of z = 1 leaves every layer above the first
        // unwritten, and the frame then samples exactly those layers.
        env.bindInt("width", base != null ? base[0] : width);
        env.bindInt("height", base != null ? base[1] : height);
        env.bindInt("depth", base != null ? base[2] : 1);
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
