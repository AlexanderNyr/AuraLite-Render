package dev.auralite.render.fabric;

import dev.auralite.render.backend.gl.GlDevice;
import dev.auralite.render.backend.vk.VkDevice;
import dev.auralite.render.biome.BiomeRegistry;
import dev.auralite.render.biome.BiomeSeamVolume;
import dev.auralite.render.graph.CompiledGraph;
import dev.auralite.render.graph.GraphContext;
import dev.auralite.render.graph.RenderGraph;
import dev.auralite.render.pack.PackFormat;
import dev.auralite.render.pack.PackGraphBuilder;
import dev.auralite.render.pack.PackLoader;
import dev.auralite.render.pack.PackManifest;
import dev.auralite.render.pack.PackSource;
import dev.auralite.render.pack.PipelineProvider;
import dev.auralite.render.rhi.GfxCommandBuffer;
import dev.auralite.render.rhi.GfxDevice;
import dev.auralite.render.rhi.GfxEnums.BackendType;
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

    private static GfxDevice device;
    private static HiddenGlContext hidden;      // null when the game context is 4.6+
    private static WindowSwapchain swapchain;
    private static FramePacer pacer;
    private static PackWatcher watcher;          // null when the watch failed
    private static RenderDocLabels labels;
    private static FrameContext frame;
    /** The live world's biomes, and the widened seam baked from them each rebuild. */
    private static WorldBiomeSource biomes;
    private static BiomeSeamVolume seamVolume;
    private static long frameIndex;
    private static boolean biomeReceiptLogged;
    private static PackManifest manifest;
    private static PipelineProvider pipelines;
    private static CompiledGraph graph;
    private static PackSource source;
    private static int presentFbo;               // game-context FBO for the blit
    private static boolean failed;
    private static String lastError;           // null while healthy; GUI + receipt
    private static long frames;                // successful frames since process start
    private static long lastFrameNanos = -1L;
    private static boolean stopped;             // set by shutdown(): never re-init after it
    private static boolean presentLogged;       // one present receipt per session
    // VK lane: the readImage below is deferred one frame - reading right
    // after submit made runOneShot wait for the whole pack (r35 stalls:
    // 10-15 fps with F8 on). The next frame's vanilla draw gives the GPU
    // time to finish, so the wait returns immediately.
    private static boolean presentReadPending;
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
    private static boolean crashHookInstalled;

    private RendererLoop() { }

    /**
     * Crash telemetry hook (r40). A 0xC0000409 kills the process from inside
     * the display driver, so the JVM often writes no hs_err at all (runs 2
     * and 3 produced none). The hook is the only place guaranteed to run
     * while the process is going down: it records the last stage, the frame
     * count and the position inside GL, then flushes to the log.
     */
    private static void installCrashHook() {
        if (crashHookInstalled) return;
        crashHookInstalled = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (stopped && !failed) return;   // clean shutdown: shutdown() already receipted
            try {
                AuraLiteRenderMod.LOGGER.error("CRASH TELEMETRY: {} | {}",
                        crashTelemetry(), lastStage());
                java.nio.file.Path out = java.nio.file.Paths.get(
                        net.fabricmc.loader.api.FabricLoader.getInstance()
                                .getGameDir().toString(), "auralite-crash.txt");
                try (java.io.PrintWriter w = new java.io.PrintWriter(
                        java.nio.file.Files.newBufferedWriter(out,
                                java.nio.charset.StandardCharsets.UTF_8))) {
                    w.println("when=" + java.time.Instant.now());
                    w.println("telemetry=" + crashTelemetry());
                    w.println("lastStage=" + lastStage());
                    if (device != null) {
                        w.println("device=" + device.deviceName() + " | "
                                + device.driverVersion() + " | " + device.backendName());
                        w.println("live buffers=" + device.liveBufferCount() + " images="
                                + device.liveImageCount() + " pipelines="
                                + device.livePipelineCount() + " modules="
                                + device.liveShaderModuleCount());
                    }
                    w.println();
                    w.println("--- java stacks ---");
                    for (java.util.Map.Entry<Thread, StackTraceElement[]> e
                            : Thread.getAllStackTraces().entrySet()) {
                        w.println("\"" + e.getKey().getName() + "\" state=" + e.getKey().getState());
                        for (StackTraceElement el : e.getValue()) w.println("    at " + el);
                    }
                    w.println();
                    w.println("--- GL state at the last heartbeat ---");
                    w.println(glState);
                }
                AuraLiteRenderMod.LOGGER.error("CRASH TELEMETRY written to {}",
                        out.toAbsolutePath());
            } catch (Throwable t) {
                AuraLiteRenderMod.LOGGER.error("crash telemetry itself failed", t);
            }
        }, "auralite-crash-telemetry"));
    }

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
            stage = "frame:begin";
            long now = System.nanoTime();
            float deltaSeconds = lastFrameNanos < 0 ? 1.0f / 60.0f
                    : Math.min(1.0f, (now - lastFrameNanos) / 1.0e9f);
            lastFrameNanos = now;

            net.minecraft.client.MinecraftClient client =
                    net.minecraft.client.MinecraftClient.getInstance();
            final int graphWidth = fbWidth, graphHeight = fbHeight;
            stage = "frame:graph";
            onDeviceContext(() -> renderGraph(deltaSeconds, now, client,
                    graphWidth, graphHeight));
            stage = "frame:present";
            present();
            pacer.endFrame();
            frames++;
            if (framesSinceRebuild >= 0) framesSinceRebuild++;
            if (framesSinceRebuild >= 0 && framesSinceRebuild <= 3) {
                trace("frame after rebuild " + framesSinceRebuild);
            }
            if (frames % 20 == 0) {
                trace("run");
            }
            if (frames % 600 == 0) {
                sampleGlState();
                AuraLiteRenderMod.LOGGER.info("heartbeat: {} | {} | {}",
                        frames, crashTelemetry(), glState);
            }
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
        // Deferred present readback: pixels of the PREVIOUS frame. The GPU
        // has had this frame's vanilla draw to finish the previous pack
        // work, so readImage no longer stalls on a live queue.
        if (presentReadPending) {
            presentReadPending = false;
            swapchain.storePresentPixels(device.readImage(swapchain.image()));
        }
        long id = device.beginFrame();
        frame.beginFrame(id, now / 1.0e9f, deltaSeconds, now);
        // After beginFrame (previous-frame snapshot first), before the graph:
        // the pack's passes read the frame context this call fills.
        FrameBridge.fill(frame, client, fbWidth, fbHeight);
        // The world's BIOMES reach the graph here too: the player's surroundings
        // are read into a field, baked into the widened seam, and bound so the
        // graph's freshness gate has a record it did not invent (r58). Cheap
        // when nothing changed - a rebuild needs a cell crossing and 20 frames.
        updateBiomes();
        GfxCommandBuffer cmd = device.createCommandBuffer(QueueType.GRAPHICS);
        cmd.begin();
        labels.beginFrame(cmd);
        graph.execute(new GraphContext(device, cmd, graph, frame));
        labels.endFrame(cmd);
        cmd.end();
        device.submit(QueueType.GRAPHICS, cmd);
        device.endFrame();
        // VK lane: the graph's target is a Vulkan image the game context
        // cannot sample. When the pack output is on, arm the readback for
        // the NEXT frame (see presentReadPending): pulling the pixels here
        // would make readImage wait for the pack we just submitted. GL lane
        // needs no readback: its texture already is the blit source.
        presentReadPending = presentEnabled
                && device.backendType() == BackendType.VULKAN_13;
    }

    /**
     * The live world reaches the pack HERE: the player's surroundings are read
     * into a {@link dev.auralite.render.biome.BiomeField}, baked into the
     * widened seam, and bound to the frame so the graph's freshness gate has a
     * record to check.
     *
     * <p>Before this existed the fabric lane never filled a field at all, so
     * every live frame baked an empty volume and the seam could not appear
     * however correct the bake was. The synthetic split the GPU lane uses is a
     * fixture; this is the terrain the player is standing on.
     *
     * <p>Cheap when nothing changed: the field is rebuilt only when the player
     * crosses a cell boundary and no more often than
     * {@link dev.auralite.render.biome.BiomeWindowSource#MIN_FRAMES_BETWEEN_REBUILDS}
     * frames, and the bake runs only on a rebuild or when the volume has no
     * record yet.
     */
    private static void updateBiomes() {
        frameIndex++;
        MinecraftClient client = MinecraftClient.getInstance();
        if (frame.biomeVolume("biome_volume") != null && biomes == null) return;   // pack wants none
        if (biomes == null) {
            biomes = new WorldBiomeSource(BiomeRegistry.vanillaDefaults());
        }
        if (client == null || client.player == null || client.world == null) return;
        double px = client.player.getX();
        double py = client.player.getY();
        double pz = client.player.getZ();
        int changed = biomes.updateFrom(client.world, px, py, pz, frameIndex);

        // The volume object is reused across frames: a bake allocates two int
        // arrays the size of the window, and doing that per frame would hand
        // the collector 400 kB every 20 frames for no new information.
        if (seamVolume == null) {
            seamVolume = new BiomeSeamVolume(biomes.field());
        }
        if (changed == 0 && seamVolume.bakeNanos() > 0 && frame.biomeVolume("biome_volume") != null) {
            return;
        }
        seamVolume.radius(manifest != null ? manifest.biomeSeamRadius : 2);
        seamVolume.bake(biomes.registry());
        frame.bindBiomeVolume("biome_volume", seamVolume);

        // The volume the GPU holds is not the volume the CPU just baked until
        // someone uploads it, and the host is that someone. The upload itself
        // happens in the graph's allocate path today (the pack declares the
        // resource); what is recorded here is the fact the gate must not
        // guess: this bake is the one the frame is allowed to read.
        if (!biomeReceiptLogged) {
            biomeReceiptLogged = true;
            AuraLiteRenderMod.LOGGER.info("biome/bake: {} | {} | {} ids packed ({} B), {} weights",
                    biomes.receipt(), volume.receipt(),
                    volume.ids().length, volume.ids().length * 4L, volume.weights().length);
        }
    }

    /**
     * Blit the shared offscreen backbuffer into the game's default
     * framebuffer. Runs in the game's context (makeCurrent state restored),
     * so the FBO below belongs to the game context: framebuffer objects are
     * not shared between contexts, the color texture is.
     */
    private static void present() {
        if (!presentEnabled) return;   // vanilla frame stays: MC's draw already filled the back buffer
        // Consume the stored readback first (sets everFilled when pixels
        // land). Until the deferred readback delivers, no blit: the vanilla
        // frame keeps showing instead of failing on a texture that has no
        // pixels yet (r36: present FBO incomplete 0x8cd6 -> frames stop).
        int tex = swapchain.colorTexture();
        if (!swapchain.everFilled()) return;
        if (presentFbo == 0) presentFbo = GL30.glGenFramebuffers();
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
        if (!presentLogged && swapchain.everFilled()) {
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
        installCrashHook();
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
        // Backend lane: -Dauralite.backend=vk (or AURALITE_BACKEND=vulkan) runs
        // the pack on the discrete Vulkan device (Phase 4 VK-windowed); the
        // default stays the GL lane the windowed gate already closed.
        String backend = System.getProperty("auralite.backend",
                System.getenv().getOrDefault("AURALITE_BACKEND", "gl"))
                .trim().toLowerCase(java.util.Locale.ROOT);
        if ("vk".equals(backend) || "vulkan".equals(backend)) {
            device = new VkDevice();
            AuraLiteRenderMod.LOGGER.info("device = Vulkan ({})", device.deviceName());
        } else {
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
        }
        // Everything device-side (images, shader modules, pack compile) must run
        // on the device context - the game context is 3.2 and cannot compile GLSL 460.
        onDeviceContext(() -> {
            swapchain = new WindowSwapchain(device, fbWidth, fbHeight);
            capture = new SceneCapture(device, fbWidth, fbHeight);
            reloadPack();
        });
        AuraLiteRenderMod.LOGGER.info("renderer ready: {} | {} | backend {}",
                device.deviceName(), device.driverVersion(), device.backendName());
        AuraLiteRenderMod.LOGGER.info("present {} (F8 toggles the pack output, F9 cycles the debug view, "
                + "Right Shift - status screen)",
                presentEnabled ? "enabled" : "disabled; vanilla frame shows");
    }

    /** Snapshot of the last completed stage, for the crash telemetry. */
    private static volatile String stage = "init";
    /** Frames drawn since the last rebuild; -1 while a rebuild is in flight. */
    private static volatile int framesSinceRebuild = -1;

    /**
     * Crash telemetry (r40). Prism cannot be relied on to leave an hs_err on
     * this machine (runs 2 and 3 produced none at all), so the run has to
     * describe its own death: a heartbeat per stage, and a shutdown hook
     * that dumps whatever the runtime can still report at the moment the
     * process goes down.
     */
    static String crashTelemetry() {
        StringBuilder sb = new StringBuilder();
        sb.append("stage=").append(stage)
          .append(" framesSinceRebuild=").append(framesSinceRebuild)
          .append(" frames=").append(frames)
          .append(" present=").append(presentEnabled)
          .append(" debugView=").append(hostOptions.getOrDefault("DEBUG_VIEW", "-"));
        return sb.toString();
    }

    /**
     * Unbuffered, self-contained trace line (r41).
     *
     * <p>The r40 run proved the JVM gets no chance to say anything: the
     * launcher reports the process died, no hs_err appears, the crash hook
     * never ran, and even ordinary log lines stop - the log4j appender keeps
     * its tail in memory, so a hard kill takes the last seconds with it. This
     * writes one line per interesting stage straight to disk (open, write,
     * close - no buffer to lose) in the game directory, so the next run's
     * file ends at the exact stage the process was killed in.
     */
    private static void trace(String what) {
        try {
            String line = System.currentTimeMillis() + " " + stage + " frames=" + frames
                    + " sinceRebuild=" + framesSinceRebuild + " " + what + "\n";
            java.nio.file.Path f = java.nio.file.Paths.get(
                    net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toString(),
                    "auralite-trace.txt");
            java.nio.file.Files.write(f, line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // tracing must never take the renderer down with it
        }
    }

    /** GL state sampled at the last heartbeat, printed by the crash hook. */
    private static volatile String glState = "(not sampled yet)";

    /**
     * Sample the GL state a faulting driver usually trips over: a buffer
     * left bound to PIXEL_PACK/UNPACK (a stale PBO makes the next
     * glTexImage2D read from a pointer the driver does not expect), the
     * bound framebuffers and the bound texture. Sampled on the game
     * context (the frame hook runs there), never from the hook itself -
     * touching GL while the process is going down would fault again.
     */
    private static void sampleGlState() {
        try {
            int err = org.lwjgl.opengl.GL11.glGetError();
            int packBuf = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            int unpackBuf = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            int drawFbo = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int readFbo = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING);
            int tex = org.lwjgl.opengl.GL11.glGetInteger(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
            glState = "err=" + err + " packPbo=" + packBuf + " unpackPbo=" + unpackBuf
                    + " drawFbo=" + drawFbo + " readFbo=" + readFbo + " tex=" + tex;
        } catch (Throwable t) {
            glState = "sample failed: " + t;
        }
    }

    /** Last stage, readable by the shutdown hook after the loop dies. */
    static String lastStage() {
        return stage + " | frames=" + frames;
    }

    /** Wait for the GPU, drop pack-bound objects, load and rebuild. */
    private static void rebuild() {
        device.waitIdle();
        if (pipelines != null) pipelines.invalidate();
        reloadPack();
    }

    private static void reloadPack() {
        stage = "reload:source";
        trace("rebuild start");
        AuraLiteRenderMod.LOGGER.info("reload stage: pack source");
        if (source != null) source.close();
        source = new PackSource.Directory(packDir());
        PackLoader loader = new PackLoader(source);
        if (!loader.load(device)) {
            throw new IllegalStateException("pack refused:\n" + loader.report().receipt());
        }
        manifest = loader.manifest();
        stage = "reload:parsed";
        AuraLiteRenderMod.LOGGER.info("reload stage: pack parsed ({})", manifest.name);
        // Session-forced options survive reloads from disk (F9's DEBUG_VIEW
        // must not snap back to the file's default on hot-reload).
        for (java.util.Map.Entry<String, String> e : hostOptions.entrySet()) {
            manifest.setOption(e.getKey(), e.getValue());
        }
        pipelines = device instanceof GlDevice glDevice
                ? new GlPipelineProvider(glDevice, manifest)
                : new VulkanPipelineProvider(device, manifest);
        stage = "reload:pipelines";
        trace("pack parsed");
        AuraLiteRenderMod.LOGGER.info("reload stage: pipelines");
        capture.resize(swapchain.width(), swapchain.height());
        RenderGraph rg = new PackGraphBuilder(manifest, pipelines)
                .build(swapchain.width(), swapchain.height(), manifest.shadowMapResolution(),
                        new int[] { 23, 97, 23 });
        CompiledGraph fresh = rg.compile(swapchain.width(), swapchain.height(), device.limits());
        stage = "reload:compiled";
        trace("pipelines built");
        AuraLiteRenderMod.LOGGER.info("reload stage: graph compiled, allocating");
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
        stage = "reload:allocated";
        trace("graph compiled");
        AuraLiteRenderMod.LOGGER.info("reload stage: graph ready, releasing the old one");
        if (graph != null) graph.release();
        graph = fresh;
        AuraLiteRenderMod.LOGGER.info("reload stage: done");
        stage = "run";
        framesSinceRebuild = 0;
        // Leak receipt on the real device: the null-backend rebuild test
        // proves the graph itself does not leak, so what is left to watch is
        // the actual backend. These numbers must be identical rebuild to
        // rebuild; a climb is the leak, a constant is a dead end.
        String vkDetail = "";
        if (device instanceof dev.auralite.render.backend.vk.VkDevice vkd) {
            vkDetail = " fbos=" + vkd.liveFramebufferCount()
                    + " viewOwners=" + vkd.liveAttachmentViewOwners()
                    + " invalidations=" + vkd.takeAttachmentInvalidationCount();
        }
        AuraLiteRenderMod.LOGGER.info("device after rebuild: buffers={} images={} pipelines={} modules={}{}",
                device.liveBufferCount(), device.liveImageCount(),
                device.livePipelineCount(), device.liveShaderModuleCount(), vkDetail);
        trace("rebuild done buffers=" + device.liveBufferCount() + " images="
                + device.liveImageCount() + " pipelines=" + device.livePipelineCount()
                + " modules=" + device.liveShaderModuleCount());
        labels.compiled(graph);
        AuraLiteRenderMod.LOGGER.info("pack loaded: {} ({}) - {}",
                manifest.name, manifest.format == PackFormat.NATIVE ? "native" : "legacy",
                loader.report().receipt().replace('\n', ' '));
        // Receipt for the option lane (F9): the exact #define set the next
        // compile sees. An empty list means no options are active - the
        // screen would show the scene view no matter what F9 logs.
        java.util.List<String> defs = manifest.activeDefines();
        AuraLiteRenderMod.LOGGER.info("pack defines: {}",
                defs.isEmpty() ? "(none)" : String.join(" ", defs));
    }

    /** One-line state for the in-game screen: RUNNING / FAILED / IDLE. */
    public static String statusLine() {
        if (failed) return "FAILED: " + (lastError != null ? lastError : "unknown error");
        if (device == null) return "IDLE: waiting for the first rendered frame";
        return "RUNNING: " + device.deviceName() + " | " + device.backendName()
                + " | driver " + device.driverVersion();
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
        // Step logs: the r33 exit died with 0xC0000409 and no receipt line,
        // so the next run shows exactly which shutdown step (if any) ran.
        AuraLiteRenderMod.LOGGER.info("shutdown: begin");
        onDeviceContext(() -> {
            if (device == null) return;
            device.waitIdle();
            AuraLiteRenderMod.LOGGER.info("shutdown: waitIdle ok");
            if (graph != null) graph.release();
            if (pipelines != null) pipelines.invalidate();
            AuraLiteRenderMod.LOGGER.info("shutdown: {} | live buffers={} images={} pipelines={} modules={}",
                    labels.receipt(), device.liveBufferCount(), device.liveImageCount(),
                    device.livePipelineCount(), device.liveShaderModuleCount());
            device.close();
            device = null;
            AuraLiteRenderMod.LOGGER.info("shutdown: device closed");
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
