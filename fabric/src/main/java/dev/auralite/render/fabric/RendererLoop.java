package dev.auralite.render.fabric;

import dev.auralite.render.backend.gl.GlDevice;
import dev.auralite.render.graph.CompiledGraph;
import dev.auralite.render.graph.GraphContext;
import dev.auralite.render.graph.RenderGraph;
import dev.auralite.render.pack.PackFormat;
import dev.auralite.render.pack.PackGraphBuilder;
import dev.auralite.render.pack.PackLoader;
import dev.auralite.render.pack.PackManifest;
import dev.auralite.render.pack.PackSource;
import dev.auralite.render.rhi.GfxCommandBuffer;
import dev.auralite.render.rhi.GfxEnums.QueueType;
import dev.auralite.render.scene.FrameContext;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * One frame of the AuraLite-Render pipeline on the fabric lane.
 *
 * <p>Order of business, every frame: consume a hot-reload if one is due,
 * notice a resize, then beginFrame &rarr; execute the compiled graph with
 * frame labels &rarr; submit &rarr; endFrame &rarr; pace. The host suites
 * exercise this exact sequence against the NULL device (the same
 * begin/execute/submit/end order, the same graph); what this class adds is
 * the window, the watcher and the pacer - precisely the parts that need a
 * GPU to prove. Standing rule 4: no frame is claimed presented until one
 * has been.
 *
 * <p>Lazy init on the first frame: that is the first moment the render
 * thread is guaranteed to hold a current GL context.
 */
public final class RendererLoop {

    private static GlDevice device;
    private static WindowSwapchain swapchain;
    private static FramePacer pacer;
    private static PackWatcher watcher;          // null when the watch failed
    private static RenderDocLabels labels;
    private static FrameContext frame;
    private static PackManifest manifest;
    private static GlPipelineProvider pipelines;
    private static CompiledGraph graph;
    private static PackSource source;
    private static boolean failed;
    private static String lastError;           // null while healthy; GUI + receipt
    private static long frames;                // successful frames since process start
    private static long lastFrameNanos = -1L;

    private RendererLoop() { }

    /** Called from the render-thread frame hook with the current framebuffer size. */
    public static void frame(boolean tick, int fbWidth, int fbHeight) {
        if (failed) return;
        try {
            if (device == null) init(fbWidth, fbHeight);

            boolean reloadDue = watcher != null && watcher.pollReloadDue();
            if (swapchain.resize(fbWidth, fbHeight) || reloadDue) {
                rebuild();
            }

            pacer.beginFrame();
            long now = System.nanoTime();
            float deltaSeconds = lastFrameNanos < 0 ? 1.0f / 60.0f
                    : Math.min(1.0f, (now - lastFrameNanos) / 1.0e9f);
            lastFrameNanos = now;

            long id = device.beginFrame();
            frame.beginFrame(id, now / 1.0e9f, deltaSeconds, now);
            GfxCommandBuffer cmd = device.createCommandBuffer(QueueType.GRAPHICS);
            cmd.begin();
            labels.beginFrame(cmd);
            graph.execute(new GraphContext(device, cmd, graph, frame));
            labels.endFrame(cmd);
            cmd.end();
            device.submit(QueueType.GRAPHICS, cmd);
            device.endFrame();
            pacer.endFrame();
            frames++;
        } catch (RuntimeException e) {
            failed = true;
            lastError = e.toString();
            AuraLiteRenderMod.LOGGER.error("renderer loop failed; frames stop instead of "
                    + "spamming a broken device:", e);
        }
    }

    /** One-line state for the in-game screen: RUNNING / FAILED / IDLE. */
    public static String statusLine() {
        if (failed) return "FAILED: " + (lastError != null ? lastError : "unknown error");
        if (device == null) return "IDLE: waiting for the first rendered frame";
        return "RUNNING: " + device.deviceName() + " | driver " + device.driverVersion();
    }

    /** Successful frames since process start (0 until the first one lands). */
    public static long framesRendered() { return frames; }

    /** Last loop failure as a single line, or null when healthy. */
    public static String lastError() { return lastError; }

    /** Arm a fresh device attempt on the next frame (screen button). */
    public static void retry() {
        if (device != null) {
            try { device.waitIdle(); } catch (RuntimeException ignored) { }
            if (graph != null) { graph.release(); graph = null; }
            if (pipelines != null) { pipelines.invalidate(); pipelines = null; }
            try { device.close(); } catch (RuntimeException ignored) { }
            device = null;
        }
        swapchain = null;
        pacer = null;
        failed = false;
        lastError = null;
        AuraLiteRenderMod.LOGGER.info("renderer retry armed; the next frame re-creates the device");
    }

    /** Hot-reload from the screen button; refused by name when no device. */
    public static void requestPackReload() {
        if (failed || device == null) {
            AuraLiteRenderMod.LOGGER.warn("pack reload requested with no live device; ignoring");
            return;
        }
        try {
            rebuild();
            AuraLiteRenderMod.LOGGER.info("pack reload requested: done");
        } catch (RuntimeException e) {
            failed = true;
            lastError = e.toString();
            AuraLiteRenderMod.LOGGER.error("pack reload failed; frames stop:", e);
        }
    }

    private static Path packDir() {
        return Paths.get(System.getProperty(AuraLiteRenderMod.PACK_DIR_PROPERTY,
                "config/auralite-render/pack"));
    }

    private static void init(int fbWidth, int fbHeight) {
        device = new GlDevice();
        swapchain = new WindowSwapchain(device, fbWidth, fbHeight);
        pacer = new FramePacer(Double.parseDouble(System.getProperty("auralite.targetFps", "120")));
        labels = RenderDocLabels.fromProperties();
        frame = new FrameContext();
        try {
            watcher = new PackWatcher(packDir());
        } catch (Exception e) {
            AuraLiteRenderMod.LOGGER.warn("pack watcher disabled: {}", e.getMessage());
            watcher = null;
        }
        reloadPack();
        AuraLiteRenderMod.LOGGER.info("renderer ready: {} | {}", device.deviceName(), device.driverVersion());
    }

    /** Wait for the GPU, drop pack-bound objects, load and rebuild. */
    private static void rebuild() {
        device.waitIdle();
        if (pipelines != null) pipelines.invalidate();
        reloadPack();
    }

    private static void reloadPack() {
        if (source != null) source.close();
        source = new PackSource.Directory(packDir());
        PackLoader loader = new PackLoader(source);
        if (!loader.load(device)) {
            throw new IllegalStateException("pack refused:\n" + loader.report().receipt());
        }
        manifest = loader.manifest();
        pipelines = new GlPipelineProvider(device, manifest);
        RenderGraph rg = new PackGraphBuilder(manifest, pipelines)
                .build(swapchain.width(), swapchain.height(), manifest.shadowMapResolution(),
                        new int[] { 23, 97, 23 });
        CompiledGraph fresh = rg.compile(swapchain.width(), swapchain.height(), device.limits());
        fresh.allocate(device);
        fresh.bindImported("backbuffer", swapchain.image());
        if (graph != null) graph.release();
        graph = fresh;
        labels.compiled(graph);
        AuraLiteRenderMod.LOGGER.info("pack loaded: {} ({}) - {}",
                manifest.name, manifest.format == PackFormat.NATIVE ? "native" : "legacy",
                loader.report().receipt().replace('\n', ' '));
    }

    public static void shutdown() {
        if (device == null) return;
        device.waitIdle();
        if (graph != null) graph.release();
        if (pipelines != null) pipelines.invalidate();
        if (watcher != null) watcher.close();
        if (source != null) source.close();
        AuraLiteRenderMod.LOGGER.info("shutdown: {} | live buffers={} images={} pipelines={} modules={}",
                labels.receipt(), device.liveBufferCount(), device.liveImageCount(),
                device.livePipelineCount(), device.liveShaderModuleCount());
        device.close();
        device = null;
    }
}
