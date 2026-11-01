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
import net.minecraft.client.MinecraftClient;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * One frame of the AuraLite-Render pipeline on the fabric lane.
 *
 * <p>Order of business, every frame: consume a hot-reload if one is due,
 * notice a resize, then (on the private 4.6 context) beginFrame &rarr;
 * execute the compiled graph with frame labels &rarr; submit &rarr;
 * endFrame, then present: blit the shared offscreen backbuffer into the
 * game's framebuffer from the game's own context, and pace.
 *
 * <p>Context discipline: Minecraft asks GLFW for OpenGL 3.2, the RHI wants
 * 4.6 - so the loop runs on a hidden shared context ({@link HiddenGlContext})
 * and only present() touches the game context. The host suites exercise
 * begin/execute/submit/end against the NULL device; what needs a GPU to
 * prove is the window, the watcher, the pacer and this present path.
 * Standing rule 4: no frame is claimed presented until one has been.
 *
 * <p>Lazy init on the first frame: the first moment the render thread is
 * guaranteed to hold a current GL context (the game's).
 */
public final class RendererLoop {

    private static GlDevice device;
    private static HiddenGlContext hidden;      // null when the game context is 4.6+
    private static WindowSwapchain swapchain;
    private static FramePacer pacer;
    private static PackWatcher watcher;          // null when the watch failed
    private static RenderDocLabels labels;
    private static FrameContext frame;
    private static PackManifest manifest;
    private static GlPipelineProvider pipelines;
    private static CompiledGraph graph;
    private static PackSource source;
    private static int presentFbo;               // game-context FBO for the blit
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
                onDeviceContext(RendererLoop::rebuild);
            }

            pacer.beginFrame();
            long now = System.nanoTime();
            float deltaSeconds = lastFrameNanos < 0 ? 1.0f / 60.0f
                    : Math.min(1.0f, (now - lastFrameNanos) / 1.0e9f);
            lastFrameNanos = now;

            onDeviceContext(() -> renderGraph(deltaSeconds, now));
            present();
            pacer.endFrame();
            frames++;
        } catch (RuntimeException e) {
            failed = true;
            lastError = e.toString();
            AuraLiteRenderMod.LOGGER.error("renderer loop failed; frames stop instead of "
                    + "spamming a broken device:", e);
        }
    }

    /** The whole device-side frame; must run with the device context current. */
    private static void renderGraph(float deltaSeconds, long now) {
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
    }

    /**
     * Blit the shared offscreen backbuffer into the game's default
     * framebuffer. Runs in the game's context (makeCurrent state restored),
     * so the FBO below belongs to the game context: framebuffer objects are
     * not shared between contexts, the color texture is.
     */
    private static void present() {
        if (presentFbo == 0) presentFbo = GL30.glGenFramebuffers();
        int tex = swapchain.colorTexture();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, presentFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, tex, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            throw new RuntimeException("present FBO incomplete: 0x" + Integer.toHexString(status));
        }
        GL30.glBlitFramebuffer(0, 0, swapchain.width(), swapchain.height(),
                0, 0, swapchain.width(), swapchain.height(),
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /** Run a device-side action on the device context (hidden 4.6 or the game's). */
    private static void onDeviceContext(Runnable action) {
        if (hidden == null) {
            action.run();
            return;
        }
        hidden.makeCurrent();
        try {
            action.run();
        } finally {
            hidden.restore();
        }
    }

    private static Path packDir() {
        return Paths.get(System.getProperty(AuraLiteRenderMod.PACK_DIR_PROPERTY,
                "config/auralite-render/pack"));
    }

    private static void init(int fbWidth, int fbHeight) {
        long gameWindow = MinecraftClient.getInstance().getWindow().getHandle();
        hidden = null;   // re-created cleanly on retry
        pacer = new FramePacer(Double.parseDouble(System.getProperty("auralite.targetFps", "120")));
        labels = RenderDocLabels.fromProperties();
        frame = new FrameContext();
        try {
            watcher = new PackWatcher(packDir());
        } catch (Exception e) {
            AuraLiteRenderMod.LOGGER.warn("pack watcher disabled: {}", e.getMessage());
            watcher = null;
        }
        onDeviceContext(() -> {
            try {
                device = new GlDevice();   // game context first: 4.6+ makes hidden redundant
                AuraLiteRenderMod.LOGGER.info("device on the game's GL context");
            } catch (RuntimeException gameCtxRefused) {
                AuraLiteRenderMod.LOGGER.info("game context refused ({}); falling back to a "
                        + "hidden shared 4.6 context", gameCtxRefused.getMessage());
                hidden = new HiddenGlContext(gameWindow);
                hidden.makeCurrent();
                try {
                    device = new GlDevice();
                } finally {
                    hidden.restore();
                }
            }
            swapchain = new WindowSwapchain(device, fbWidth, fbHeight);
            reloadPack();
        });
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

    /** Full restart from the screen button: close everything, arm a fresh attempt. */
    public static void retry() {
        shutdown();
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
            onDeviceContext(RendererLoop::rebuild);
            AuraLiteRenderMod.LOGGER.info("pack reload requested: done");
        } catch (RuntimeException e) {
            failed = true;
            lastError = e.toString();
            AuraLiteRenderMod.LOGGER.error("pack reload failed; frames stop:", e);
        }
    }

    public static void shutdown() {
        onDeviceContext(() -> {
            if (device == null) return;
            device.waitIdle();
            if (graph != null) graph.release();
            if (pipelines != null) pipelines.invalidate();
            AuraLiteRenderMod.LOGGER.info("shutdown: {} | live buffers={} images={} pipelines={} modules={}",
                    labels.receipt(), device.liveBufferCount(), device.liveImageCount(),
                    device.livePipelineCount(), device.liveShaderModuleCount());
            device.close();
            device = null;
        });
        if (swapchain != null) {
            onDeviceContext(swapchain::destroy);
            swapchain = null;
        }
        if (watcher != null) watcher.close();
        if (source != null) source.close();
        watcher = null;
        source = null;
        graph = null;
        pipelines = null;
        if (presentFbo != 0) {
            GL30.glDeleteFramebuffers(presentFbo);
            presentFbo = 0;
        }
        if (hidden != null) {
            hidden.destroy();
            hidden = null;
        }
    }
}
