package dev.auralite.render.tests;

import dev.auralite.render.backend.nulldev.NullDevice;
import dev.auralite.render.backend.nulldev.RecordingCommandBuffer;
import dev.auralite.render.graph.Barrier;
import dev.auralite.render.graph.CompiledGraph;
import dev.auralite.render.graph.GraphContext;
import dev.auralite.render.graph.GraphException;
import dev.auralite.render.graph.GraphHistory;
import dev.auralite.render.graph.GraphResource;
import dev.auralite.render.graph.RenderGraph;
import dev.auralite.render.graph.ResourceLifetime;
import dev.auralite.render.rhi.DeviceLimits;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxEnums.Access;
import dev.auralite.render.rhi.GfxEnums.QueueType;
import dev.auralite.render.rhi.GfxEnums.ResourceState;
import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxImage;
import dev.auralite.render.rhi.GfxUsage;
import dev.auralite.render.scene.FrameContext;

import java.util.List;

import static dev.auralite.render.tests.TestRig.check;
import static dev.auralite.render.tests.TestRig.eq;
import static dev.auralite.render.tests.TestRig.negativeControl;
import static dev.auralite.render.tests.TestRig.notNull;
import static dev.auralite.render.tests.TestRig.receipt;
import static dev.auralite.render.tests.TestRig.section;
import static dev.auralite.render.tests.TestRig.suite;
import static dev.auralite.render.tests.TestRig.throwsNamed;

/**
 * P0/P1 gate: the graph compiles, orders, culls, refuses cycles, derives
 * barriers and lifetimes, and executes end to end on the NULL backend.
 */
public final class GraphSuite {
    private GraphSuite() { }

    private static final int W = 1920, H = 1080;

    private static GfxDesc.Image color(String name) {
        return GfxDesc.Image.tex2d(0, 0, GfxFormat.RGBA16F,
                GfxUsage.IMAGE_COLOR_ATTACHMENT | GfxUsage.IMAGE_SAMPLED).named(name);
    }

    private static GfxDesc.Image depth(String name) {
        return GfxDesc.Image.tex2d(0, 0, GfxFormat.D32_SFLOAT,
                GfxUsage.IMAGE_DEPTH_STENCIL_ATTACHMENT | GfxUsage.IMAGE_SAMPLED).named(name);
    }

    /** A representative frame: shadow, terrain, two compute passes, TAA, present. */
    static RenderGraph sampleFrame() {
        RenderGraph g = new RenderGraph("frame");
        g.addTexture("scene_color", color("scene_color"));
        g.addTexture("scene_depth", depth("scene_depth"));
        g.addTexture("shadow_depth", GfxDesc.Image.tex2d(2048, 2048, GfxFormat.D32_SFLOAT,
                GfxUsage.IMAGE_DEPTH_STENCIL_ATTACHMENT | GfxUsage.IMAGE_SAMPLED).named("shadow_depth"));
        g.addTexture("ao", GfxDesc.Image.tex2d(0, 0, GfxFormat.R16F,
                GfxUsage.IMAGE_STORAGE | GfxUsage.IMAGE_SAMPLED).named("ao"));
        g.addTexture("cloud_shadow", GfxDesc.Image.tex2d(256, 256, GfxFormat.R16F,
                GfxUsage.IMAGE_COLOR_ATTACHMENT | GfxUsage.IMAGE_SAMPLED).named("cloud_shadow"));
        GraphHistory taa = g.addHistory("taa", color("taa"));
        // The swapchain image is imported: the graph must not allocate, resize or
        // alias it, and its initial state is PRESENT rather than UNDEFINED.
        g.importTexture("backbuffer", GfxDesc.Image.tex2d(W, H, GfxFormat.RGBA8_UNORM,
                GfxUsage.IMAGE_COLOR_ATTACHMENT | GfxUsage.IMAGE_PRESENT).named("backbuffer"));

        g.addRasterPass("cloud_shadow")
                .color("cloud_shadow")
                .body(noop());

        g.addRasterPass("shadow_map")
                .depth("shadow_depth", true)
                .read("cloud_shadow", Access.SHADER_READ)
                .body(noop());

        g.addRasterPass("terrain")
                .color("scene_color", 1)
                .depth("scene_depth", true)
                .read("shadow_depth", Access.SHADER_READ)
                .body(noop());

        g.addComputePass("ssao")
                .write("ao", Access.SHADER_WRITE)
                .read("scene_depth", Access.SHADER_READ)
                .dispatch((W + 7) / 8, (H + 7) / 8, 1)
                .body(noop());

        g.addRasterPass("lighting")
                .color("scene_color")
                .read("scene_depth", Access.SHADER_READ)
                .read("ao", Access.SHADER_READ)
                .body(noop());

        g.addRasterPass("taa_resolve")
                .colorHistory(taa)
                .readHistory(taa, Access.SHADER_READ)
                .read("scene_color", Access.SHADER_READ)
                .body(noop());

        g.addRasterPass("present")
                .color("backbuffer")
                .readHistory(taa, Access.SHADER_READ)
                .external()
                .body(noop());

        // Declared but never consumed: must be culled, and the receipt must say so.
        g.addComputePass("unused_debug")
                .write("ao", Access.SHADER_WRITE)
                .when("DEBUG_VIEW", false)
                .body(noop());

        return g;
    }

    /**
     * A pass body. It binds nothing and draws nothing: the graph opens and
     * closes the render pass and sets the viewport around it, so all the body
     * has to do is exist. The NULL backend still records the pass begin/end and
     * the viewport, which is what the assertions below count.
     */
    private static dev.auralite.render.graph.GraphPass.Executor noop() {
        return new dev.auralite.render.graph.GraphPass.Executor() {
            @Override public void execute(GraphContext ctx) {
                ctx.put("ran:" + ctx.currentPass().name(), Boolean.TRUE);
            }
        };
    }

    public static void run() {
        suite("graph");

        section("declaration");
        RenderGraph g = sampleFrame();
        eq(g.resourceCount(), 8, "sample frame declares 8 resources (taa counts as its two sides)");
        eq(g.passCount(), 8, "sample frame declares 8 passes");
        eq(g.histories().size(), 1, "sample frame declares 1 history pair");

        section("validation");
        throwsNamed(new Runnable() { @Override public void run() {
            RenderGraph x = new RenderGraph("bad");
            x.addTexture("c", color("c"));
            x.addRasterPass("empty").body(noop());
            x.compile(W, H);
        }}, "no resource access", "a pass with no access is refused");

        throwsNamed(new Runnable() { @Override public void run() {
            RenderGraph x = new RenderGraph("bad");
            x.addTexture("c", color("c"));
            x.addRasterPass("noattach").read("c", Access.SHADER_READ).body(noop());
            x.compile(W, H);
        }}, "neither a colour nor a depth attachment", "a raster pass with no attachment is refused");

        throwsNamed(new Runnable() { @Override public void run() {
            RenderGraph x = new RenderGraph("bad");
            x.addTexture("c", color("c"));
            x.addRasterPass("a").color("c").body(noop());
            x.addRasterPass("a").color("c").body(noop());
        }}, "declared twice", "a duplicate pass name is refused");

        throwsNamed(new Runnable() { @Override public void run() {
            RenderGraph x = new RenderGraph("bad");
            x.addTexture("bad name!", color("c"));
        }}, "illegal character", "an illegal resource name is refused");

        throwsNamed(new Runnable() { @Override public void run() {
            RenderGraph x = new RenderGraph("bad");
            x.addTexture("c", GfxDesc.Image.tex2d(64, 64, GfxFormat.RGBA16F, 0));
            x.addRasterPass("p").depth("c", true).body(noop());
            x.compile(W, H);
        }}, "not a depth format", "a colour format as a depth attachment is refused");

        throwsNamed(new Runnable() { @Override public void run() {
            RenderGraph x = new RenderGraph("bad");
            x.addTexture("c", color("c"));
            x.addRasterPass("p").read("missing", Access.SHADER_READ).color("c").body(noop());
            x.compile(W, H);
        }}, "unknown resource", "an unknown resource reference is refused");

        throwsNamed(new Runnable() { @Override public void run() {
            DeviceLimits tight = new DeviceLimits();
            tight.maxColorAttachments = 2;
            RenderGraph x = new RenderGraph("bad");
            x.addTexture("a", color("a")); x.addTexture("b", color("b")); x.addTexture("c", color("c"));
            x.addRasterPass("p").color("a").color("b").color("c").body(noop());
            x.compile(W, H, tight);
        }}, "device limit is 2", "exceeding maxColorAttachments is refused at compile");

        section("ordering");
        CompiledGraph c = g.compile(W, H, NullDevice.conservativeLimits());
        List<String> order = c.passOrder();
        receipt("order: " + order);
        check(order.indexOf("cloud_shadow") < order.indexOf("shadow_map"), "cloud_shadow precedes shadow_map");
        check(order.indexOf("shadow_map") < order.indexOf("terrain"), "shadow_map precedes terrain");
        check(order.indexOf("terrain") < order.indexOf("ssao"), "terrain precedes ssao");
        check(order.indexOf("ssao") < order.indexOf("lighting"), "ssao precedes lighting");
        check(order.indexOf("lighting") < order.indexOf("taa_resolve"), "lighting precedes taa_resolve");
        check(order.indexOf("taa_resolve") < order.indexOf("present"), "taa_resolve precedes present");
        eq(order.size(), 7, "7 passes survive to the order");

        section("disabled by option");
        eq(c.stats().culledPasses, 0, "nothing in the sample frame is unreachable");
        eq(c.stats().disabled.size(), 1, "one pass was disabled by an option");
        check(c.stats().disabled.get(0).contains("unused_debug"), "the disabled pass is named");
        check(c.stats().disabled.get(0).contains("DEBUG_VIEW"), "the disabled pass names its option");
        check(!c.passOrder().contains("unused_debug"), "a disabled pass is absent from the order");

        section("culling");
        RenderGraph cg = new RenderGraph("cull");
        cg.addTexture("live", color("live"));
        cg.addTexture("dead", color("dead"));
        cg.addRasterPass("producer_live").color("live").body(noop());
        cg.addRasterPass("producer_dead").color("dead").body(noop());
        cg.addRasterPass("consumer").color("live").read("live", Access.SHADER_READ)
                .external().body(noop());
        CompiledGraph cc = cg.compile(W, H, NullDevice.conservativeLimits());
        eq(cc.stats().culledPasses, 1, "exactly one pass is unreachable");
        check(cc.stats().culled.get(0).contains("producer_dead"), "the unreachable producer is the one culled");
        check(cc.passOrder().contains("producer_live"), "the live producer survives");
        eq(cc.passOrder().size(), 2, "two passes remain after culling");
        receipt("culling: " + cc.stats().culled.get(0));

        section("declaration order does not decide execution order");
        RenderGraph og = new RenderGraph("out-of-order");
        og.addTexture("mid", color("mid"));
        og.addTexture("screen", color("screen"));
        // Declared last-but-run-last, and declared first-but-run-last: the
        // dependency, not the file order, decides.
        og.addRasterPass("blit").color("screen").read("mid", Access.SHADER_READ).external().body(noop());
        og.addRasterPass("produce").color("mid").body(noop());
        CompiledGraph oc = og.compile(W, H, NullDevice.conservativeLimits());
        eq(oc.passOrder().get(0), "produce", "the producer runs first despite being declared second");
        eq(oc.passOrder().get(1), "blit", "the consumer runs second despite being declared first");

        section("determinism");
        CompiledGraph c2 = sampleFrame().compile(W, H, NullDevice.conservativeLimits());
        eq(c2.passOrder(), order, "a second compile of the same graph yields the identical order");
        eq(c2.totalBarriers(), c.totalBarriers(), "a second compile yields the identical barrier count");
        eq(c2.receipt(), c.receipt(), "a second compile yields a byte-identical receipt");

        section("cycle detection");
        throwsNamed(new Runnable() { @Override public void run() {
            RenderGraph x = new RenderGraph("cycle");
            x.addTexture("p", color("p"));
            x.addTexture("q", color("q"));
            x.addComputePass("a").write("p", Access.SHADER_WRITE).read("q", Access.SHADER_READ).body(noop());
            x.addComputePass("b").write("q", Access.SHADER_WRITE).read("p", Access.SHADER_READ).body(noop());
            x.addRasterPass("out").color("q").external().body(noop());
            x.compile(W, H);
        }}, "dependency cycle: a -> b -> a", "a two-pass cycle is refused and the path is named");

        section("barriers");
        int total = c.totalBarriers();
        check(total > 0, "the compiled graph emits barriers");
        receipt("barriers: " + total + " planned, " + c.stats().noopBarriersSuppressed + " suppressed");
        boolean sawColorToShaderRead = false;
        boolean sawUndefinedToDepthWrite = false;
        boolean importedStartedUndefined = false;
        for (int i = 0; i < order.size(); i++) {
            for (Barrier b : c.barriersBefore(i)) {
                if (b.from == ResourceState.COLOR_ATTACHMENT_WRITE && b.to == ResourceState.SHADER_READ) {
                    sawColorToShaderRead = true;
                }
                if (b.from == ResourceState.UNDEFINED && b.to == ResourceState.DEPTH_STENCIL_WRITE) {
                    sawUndefinedToDepthWrite = true;
                }
                if (b.resource.name().equals("backbuffer") && b.from == ResourceState.UNDEFINED) {
                    importedStartedUndefined = true;
                }
                check(b.requiredBy != null && !b.requiredBy.isEmpty(), "every barrier names the pass that needed it");
            }
        }
        check(sawColorToShaderRead, "a colour target transitions to SHADER_READ before being sampled");
        check(sawUndefinedToDepthWrite, "a fresh depth attachment transitions out of UNDEFINED");
        check(!importedStartedUndefined, "an imported resource never starts in UNDEFINED");

        section("lifetimes");
        ResourceLifetime shadow = c.lifetimeOf("shadow_depth");
        notNull(shadow, "shadow_depth has a lifetime");
        check(shadow.firstPass <= shadow.lastPass, "a lifetime's first pass precedes its last");
        eq(c.lifetimes().size(), 8, "one lifetime per declared resource, imported ones included");
        receipt("lifetime shadow_depth: " + shadow);

        section("relative sizes");
        GfxDesc.Image eff = c.effectiveDesc("scene_color");
        eq(eff.width, W, "a resource declared 0 wide resolves to the framebuffer width");
        eq(eff.height, H, "a resource declared 0 high resolves to the framebuffer height");
        eq(c.effectiveDesc("shadow_depth").width, 2048, "an explicitly sized resource keeps its size");

        section("aliasing");
        RenderGraph ag = new RenderGraph("alias");
        ag.addTexture("scratchA", GfxDesc.Image.tex2d(512, 512, GfxFormat.RGBA16F,
                GfxUsage.IMAGE_COLOR_ATTACHMENT | GfxUsage.IMAGE_SAMPLED));
        ag.addTexture("scratchB", GfxDesc.Image.tex2d(512, 512, GfxFormat.RGBA16F,
                GfxUsage.IMAGE_COLOR_ATTACHMENT | GfxUsage.IMAGE_SAMPLED));
        ag.addTexture("out", GfxDesc.Image.tex2d(512, 512, GfxFormat.RGBA16F,
                GfxUsage.IMAGE_COLOR_ATTACHMENT | GfxUsage.IMAGE_SAMPLED));
        ag.addRasterPass("useA").color("scratchA").body(noop());
        ag.addRasterPass("readA").color("scratchB").read("scratchA", Access.SHADER_READ).body(noop());
        ag.addRasterPass("readB").color("out").read("scratchB", Access.SHADER_READ).external().body(noop());
        CompiledGraph ac = ag.compile(512, 512, NullDevice.conservativeLimits());
        check(ac.stats().aliasGroups >= 1, "disjoint-lifetime resources form an alias group");
        check(ac.stats().aliasSavingsBytes >= 512L * 512 * 8,
                "the reported alias saving is at least one 512x512 RGBA16F image");
        receipt("aliasing: " + ac.stats().aliasGroups + " groups, "
                + ac.stats().aliasSavingsBytes + " B reclaimable");

        section("history");
        GraphHistory h = g.histories().get(0);
        eq(h.currentName(), "taa.0", "history starts on side 0 as current");
        eq(h.previousName(), "taa.1", "history starts on side 1 as previous");
        CompiledGraph hc = sampleFrame().compile(W, H, NullDevice.conservativeLimits());
        NullDevice dev = new NullDevice();
        hc.allocate(dev);
        GfxImage cur0 = hc.graph().require("taa.0").image();
        GfxImage prev0 = hc.graph().require("taa.1").image();
        notNull(cur0, "history side 0 is allocated");
        hc.swapHistories();
        eq(hc.graph().require("taa.0").image(), prev0, "after one swap side 0 holds the previous image");
        hc.swapHistories();
        eq(hc.graph().require("taa.0").image(), cur0, "after two swaps the bindings are back where they started");
        hc.release();
        dev.close();

        section("execute on the null backend");
        NullDevice device = new NullDevice();
        CompiledGraph exec = sampleFrame().compile(W, H, device.limits());
        exec.allocate(device);
        eq(device.liveImageCount(), 7, "allocation created 7 images; the imported backbuffer is not ours");
        // Imported resources are the host's to bind, exactly as the Minecraft glue
        // binds the vanilla framebuffer each frame.
        GfxImage backbuffer = device.createImage(GfxDesc.Image.tex2d(W, H, GfxFormat.RGBA8_UNORM,
                GfxUsage.IMAGE_COLOR_ATTACHMENT | GfxUsage.IMAGE_PRESENT).named("vanilla-backbuffer"));
        exec.bindImported("backbuffer", backbuffer);
        check(c.stats().importedResources == 1, "exactly one resource is imported");
        RecordingCommandBuffer cb = (RecordingCommandBuffer) device.createCommandBuffer(QueueType.GRAPHICS);
        cb.begin();
        GraphContext ctx = new GraphContext(device, cb, exec, new FrameContext());
        exec.execute(ctx);
        cb.end();
        device.submit(QueueType.GRAPHICS, cb);
        eq(exec.executions(), 1L, "the graph reports one execution");
        eq(exec.passesExecuted(), (long) exec.passCount(), "every ordered pass ran a body");
        check(exec.barriersEmitted() > 0, "barriers reached the command buffer");
        eq(cb.count(RecordingCommandBuffer.Op.BEGIN_PASS), 6, "6 raster passes began a render pass");
        eq(cb.count(RecordingCommandBuffer.Op.END_PASS), 6, "6 raster passes ended a render pass");
        eq(c.stats().rasterPasses, 6, "the sample frame has 6 raster passes");
        eq(c.stats().computePasses, 1, "the sample frame has 1 compute pass");
        eq(cb.count(RecordingCommandBuffer.Op.VIEWPORT), 6,
                "the graph sets a viewport for every raster pass, at that pass's own render area");
        eq(cb.count(RecordingCommandBuffer.Op.SCISSOR), 6, "and a matching scissor");
        check(ctx.has("ran:present"), "the present pass body ran");
        check(!ctx.has("ran:unused_debug"), "the disabled pass body did not run");
        eq(device.violations().size(), 0, "the null backend recorded no contract violations");
        receipt(exec.stats().receipt().trim().replace("\n", "\n  "));
        receipt("null device: images=" + device.liveImageCount()
                + " commands=" + cb.commandCount()
                + " barriers=" + exec.barriersEmitted());
        exec.release();
        eq(device.liveImageCount(), 1, "release() closed the graph's images but not the host's backbuffer");
        check(exec.graph().require("backbuffer").image() != null,
                "release() leaves imported bindings alone");
        backbuffer.close();
        device.close();

        section("unbound imports are refused");
        throwsNamed(new Runnable() { @Override public void run() {
            CompiledGraph fresh = sampleFrame().compile(W, H);
            NullDevice d3 = new NullDevice();
            fresh.allocate(d3);
            RecordingCommandBuffer cb3 = (RecordingCommandBuffer) d3.createCommandBuffer(QueueType.GRAPHICS);
            cb3.begin();
            fresh.execute(new GraphContext(d3, cb3, fresh, new FrameContext()));
        }}, "must be bound by the host", "executing with an unbound import names the contract");

        throwsNamed(new Runnable() { @Override public void run() {
            CompiledGraph fresh = sampleFrame().compile(W, H);
            fresh.bindImported("scene_color", (GfxImage) null);
        }}, "owned by the graph, not imported", "binding an owned resource as an import is refused");

        section("unallocated execution is refused");
        throwsNamed(new Runnable() { @Override public void run() {
            CompiledGraph fresh = sampleFrame().compile(W, H);
            NullDevice d2 = new NullDevice();
            RecordingCommandBuffer cb2 = (RecordingCommandBuffer) d2.createCommandBuffer(QueueType.GRAPHICS);
            cb2.begin();
            fresh.execute(new GraphContext(d2, cb2, fresh, new FrameContext()));
        }}, "before allocate()", "executing an unallocated graph is refused with a reason");

        negativeControl("cull gate is real", new Runnable() { @Override public void run() {
            RenderGraph x = new RenderGraph("nc");
            x.addTexture("c", color("c"));
            // Not external, nothing downstream: must be culled. Asserting it is
            // NOT culled must fail, which is what proves the cull gate bites.
            x.addRasterPass("dead").color("c").body(noop());
            CompiledGraph cc = x.compile(W, H);
            if (cc.passOrder().contains("dead")) return;
            throw new GraphException("negative control: the dead pass was culled as expected");
        }});
    }
}
