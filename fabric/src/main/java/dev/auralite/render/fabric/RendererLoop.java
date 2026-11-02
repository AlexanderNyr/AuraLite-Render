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
    private static boolean stopped;             // set by shutdown(): never re-init after it
    private static boolean presentLogged;       // one present receipt per session
    // OFF by default: the game must be playable out of the box; F8 (or the
    // GUI button) demonstrates the pack's output when asked for.
    private static boolean presentEnabled = false;
    // The vanilla frame as a texture the graph reads (imported "scene").
    private static SceneCapture capture;
    // Option values this session forced onto the pack (F9 cycles DEBUG_VIEW);
    // re-applied after every reload so a hot-reload from disk keeps them.
    private static final java.util.Map<String, String> hostOptions =
            new java.util.LinkedHashMap<String, String>();
    private static int debugViewIndex;          // which DEBUG_VIEW value F9 set

    private RendererLoop() { }

    /** Called from the render-thread frame hook with the current framebuffer size. */
    public static void frame(boolean tick, int fbWidth, int fbHeight) {
        if (stopped || failed) return;
        try {
            if (device == null) init(fbWidth, fbHeight);

            boolean reloadDue = watcher != null && watcher.pollReloadDue();
            if (swapchain.resize(fbWidth, fbHeight) || reloadDue) {
                onDeviceContext(RendererLoop::rebuild);
            }

            // Vanilla just drew the world into the default framebuffer and the
            // swap has not flipped it yet - copy it into the pack's imported
            // scene texture. Game context: that is where FB0 is the screen
            // (see present()'s note on why FBOs cannot cross contexts).
            capture.capture();

            pacer.beginFrame();
            long now = System.nanoTime();
            float deltaSeconds = lastFrameNanos < 0 ? 1.0f / 60.0f
                    : Math.min(1.0f, (now - lastFrameNanos) / 1.0e9f);
            lastFrameNanos = now;

            net.minecraft.client.MinecraftClient client =
                    net.minecraft.client.MinecraftClient.getInstance();
            final int graphWidth = fbWidth, graphHeight = fbHeight;
            onDeviceContext(() -> renderGraph(deltaSeconds, now, client,
                    graphWidth, graphHeight));
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
    private static void renderGraph(float deltaSeconds, long now,
                                    net.minecraft.client.MinecraftClient client,
                                    int fbWidth, int fbHeight) {
        long id = device.beginFrame();
        frame.beginFrame(id, now / 1.0e9f, deltaSeconds, now);
        // After beginFrame (previous-frame snapshot first), before the graph:
        // the pack's passes read the frame context this call fills.
        FrameBridge.fill(frame, client, fbWidth, fbHeight);
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
        if (!presentEnabled) return;   // vanilla frame stays: MC's draw already filled the back buffer
        if (presentFbo == 0) presentFbo = GL30.glGenFramebuffers();
        int tex = swapchain.colorTexture();
        // glBlitFramebuffer writes into the DRAW binding: keep the source on
        // READ (the FBO wrapping the shared texture) and the destination on
        // DRAW = 0 (the screen's back buffer). Binding GL_FRAMEBUFFER for
        // both made earlier revisions blit into the source and leave the
        // screen untouched (r10-r12 receipts).
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, presentFbo);
        GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, tex, 0);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            throw new RuntimeException("present FBO incomplete: 0x" + Integer.toHexString(status));
        }
        GL30.glBlitFramebuffer(0, 0, swapchain.width(), swapchain.height(),
                0, 0, swapchain.width(), swapchain.height(),
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        // Second landing: the game's own main target. MC's F2 reads
        // MinecraftClient.getFramebuffer() (handleInputEvents, render offset
        // 363) - a buffer our screen blit never touched, so screenshots came
        // out vanilla while the window showed the pack (the r20 run's
        // complaint). The screen path above is untouched; this copy only
        // feeds the screenshot buffer between this present and the next
        // frame's draw. F8-off skips both: screenshot then matches the
        // vanilla frame, as the button promises.
        int mainFbo = MinecraftClient.getInstance().getFramebuffer().fbo;
        if (mainFbo != 0) {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, mainFbo);
            GL30.glBlitFramebuffer(0, 0, swapchain.width(), swapchain.height(),
                    0, 0, MinecraftClient.getInstance().getFramebuffer().textureWidth,
                    MinecraftClient.getInstance().getFramebuffer().textureHeight,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        if (!presentLogged) {
            presentLogged = true;
            long gameWindow = MinecraftClient.getInstance().getWindow().getHandle();
            long ctx = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
            java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(4);
            // read the DEFAULT framebuffer - the screen's back buffer - not the source FBO
            GL11.glReadPixels(swapchain.width() / 2, swapchain.height() / 2, 1, 1,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
            AuraLiteRenderMod.LOGGER.info("present: blit {}x{} -> default fb {} (FBO {}); "
                            + "ctx game={} current={}; backbuffer center rgba=({},{},{},{})",
                    swapchain.width(), swapchain.height(), tex, presentFbo,
                    gameWindow == ctx ? "yes" : "NO", ctx,
                    px.get(0) & 0xff, px.get(1) & 0xff, px.get(2) & 0xff, px.get(3) & 0xff);
        }
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
        pacer = new FramePacer(Double.parseDouble(System.getProperty("auralite.targetFps", "120")));
        labels = RenderDocLabels.fromProperties();
        frame = new FrameContext();
        try {
            watcher = new PackWatcher(packDir());
        } catch (Exception e) {
            AuraLiteRenderMod.LOGGER.warn("pack watcher disabled: {}", e.getMessage());
            watcher = null;
        }
        // Try the game's own context first (4.6+ makes the hidden one redundant);
        // otherwise stand the shared hidden context up BEFORE any device work.
        try {
            device = new GlDevice();
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
        // Everything device-side (images, shader modules, pack compile) must run
        // on the device context - the game context is 3.2 and cannot compile GLSL 460.
        onDeviceContext(() -> {
            swapchain = new WindowSwapchain(device, fbWidth, fbHeight);
            capture = new SceneCapture(device, fbWidth, fbHeight);
            reloadPack();
        });
        AuraLiteRenderMod.LOGGER.info("renderer ready: {} | {}", device.deviceName(), device.driverVersion());
        AuraLiteRenderMod.LOGGER.info("present {} (F8 toggles the pack output, F9 cycles the debug view, "
                + "Right Shift - status screen)",
                presentEnabled ? "enabled" : "disabled; vanilla frame shows");
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
        // Session-forced options survive reloads from disk (F9's DEBUG_VIEW
        // must not snap back to the file's default on hot-reload).
        for (java.util.Map.Entry<String, String> e : hostOptions.entrySet()) {
            manifest.setOption(e.getKey(), e.getValue());
        }
        pipelines = new GlPipelineProvider(device, manifest);
        capture.resize(swapchain.width(), swapchain.height());
        RenderGraph rg = new PackGraphBuilder(manifest, pipelines)
                .build(swapchain.width(), swapchain.height(), manifest.shadowMapResolution(),
                        new int[] { 23, 97, 23 });
        CompiledGraph fresh = rg.compile(swapchain.width(), swapchain.height(), device.limits());
        fresh.allocate(device);
        fresh.bindImported("backbuffer", swapchain.image());
        // The scene resource is optional on purpose: a pack on disk that
        // predates the capture (config/auralite-render/pack not yet updated
        // from the kit) renders as before instead of refusing to start.
        if (manifest.graph.findResource("scene") != null) {
            fresh.bindImported("scene", capture.image());
        } else {
            AuraLiteRenderMod.LOGGER.warn("pack declares no 'scene' resource; "
                    + "vanilla capture idle - update config/auralite-render/pack "
                    + "from the kit's pack/ to see the world through the pack");
        }
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

    /** Whether the pack's output is blitted over the screen each frame. */
    public static boolean presentEnabled() { return presentEnabled; }

    /** GUI button: show the pack output / let Minecraft's frame through. */
    public static void togglePresent() {
        presentEnabled = !presentEnabled;
        AuraLiteRenderMod.LOGGER.info("present {}", presentEnabled ? "enabled" : "disabled; vanilla frame shows");
    }

    /** The four DEBUG_VIEW values the reference pack's final stage knows. */
    private static final String[] DEBUG_VIEWS = {"0", "1", "2", "3"};
    private static final String[] DEBUG_VIEW_NAMES = {"scene", "luma", "rgb", "grid"};

    /**
     * F9: cycle the pack's const debug view and rebuild so the new value
     * reaches the shader as a define. The capture keeps running either way;
     * this only changes how the final stage displays it.
     */
    public static void cycleDebugView() {
        if (failed || device == null) {
            AuraLiteRenderMod.LOGGER.warn("debug view cycle with no live device; ignoring");
            return;
        }
        debugViewIndex = (debugViewIndex + 1) % DEBUG_VIEWS.length;
        String value = DEBUG_VIEWS[debugViewIndex];
        hostOptions.put("DEBUG_VIEW", value);
        try {
            onDeviceContext(RendererLoop::rebuild);
            AuraLiteRenderMod.LOGGER.info("debug view -> {} ({})", value, DEBUG_VIEW_NAMES[debugViewIndex]);
        } catch (RuntimeException e) {
            failed = true;
            lastError = e.toString();
            AuraLiteRenderMod.LOGGER.error("debug view rebuild failed; frames stop:", e);
        }
    }

    /** Last loop failure as a single line, or null when healthy. */
    public static String lastError() { return lastError; }

    /** Full restart from the screen button: close everything, arm a fresh attempt. */
    public static void retry() {
        shutdown();
        stopped = false;
        presentLogged = false;
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
        stopped = true;
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
        if (capture != null) {
            onDeviceContext(capture::destroy);
            capture = null;
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
