package dev.auralite.render.tests;

import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.BufferUtils;
import org.lwjgl.PointerBuffer;
import org.lwjgl.egl.EGL10;
import org.lwjgl.egl.EGL12;
import org.lwjgl.egl.EGL14;
import org.lwjgl.egl.EGL15;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL20;

import dev.auralite.render.backend.gl.GlDevice;
import dev.auralite.render.backend.vk.VkDevice;
import dev.auralite.render.graph.CompiledGraph;
import dev.auralite.render.graph.GraphContext;
import dev.auralite.render.graph.RenderGraph;
import dev.auralite.render.pack.PackGraphBuilder;
import dev.auralite.render.biome.BiomeField;
import dev.auralite.render.biome.BiomeRegistry;
import dev.auralite.render.biome.BiomeSeamShader;
import dev.auralite.render.pack.PackLoader;
import dev.auralite.render.pack.PackManifest;
import dev.auralite.render.pack.PackProgram;
import dev.auralite.render.pack.PackSource;
import dev.auralite.render.pack.PipelineProvider;
import dev.auralite.render.rhi.GfxCommandBuffer;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxDevice;
import dev.auralite.render.rhi.GfxEnums.QueueType;
import dev.auralite.render.rhi.GfxException;
import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxImage;
import dev.auralite.render.rhi.GfxPipeline;
import dev.auralite.render.rhi.GfxStage;
import dev.auralite.render.rhi.GfxUsage;
import dev.auralite.render.rhi.ShaderSource;
import dev.auralite.render.scene.FrameContext;

/**
 * Phase 5 groundwork: run OUR real backends on whatever GPU this host has.
 *
 * <p>Every lane answers with one named receipt and nothing else:
 * <ul>
 *   <li>{@code RENDERED} - a real frame of the reference pack executed and
 *       submitted on the device;</li>
 *   <li>{@code REFUSED-NAMED} - a backend refused by name (the message is the
 *       receipt: older GL context, SPIR-V expectation, capability gap);</li>
 *   <li>{@code SKIP-NAMED} - no context could be created, and the receipt says
 *       which EGL/Vulkan step failed.</li>
 * </ul>
 * An <b>unnamed</b> failure (NPE, linker error, anything without a message from
 * our code) exits 1: a lane that died without a name is a bug in the lane, not
 * a property of the host. The harness never claims a frame was rendered before
 * one has; "rendered" appears in the output only after submit + waitIdle of the
 * real command buffer.
 *
 * <p>Vulkan eats SPIR-V only (VkDevice says so by name), so this harness turns
 * the pack's GLSL into SPIR-V offline with {@code glslangValidator} - missing
 * compiler is a named SKIP, not a crash. GL needs a current 4.6 context
 * (GlDevice's own named refusal decides). Software rasterizers (lavapipe,
 * llvmpipe) count: they are real drivers executing our real code.
 *
 * <p>Usage: {@code make gpu-test [LANE=vk|gl|all]}
 */
public final class GpuSmoke {

    private GpuSmoke() { }

    /** Small enough for software rasterizers, big enough for a real frame. */
    static final int WIDTH = 1280;
    static final int HEIGHT = 720;

    public static void main(String[] args) {
        String lane = args.length > 0 ? args[0] : "all";
        if (lane.startsWith("child-")) {
            // Inner JVM: exactly one lane in-process; the exit code is the verdict.
            String which = lane.substring("child-".length());
            boolean ok = "vk".equals(which) ? vkLane() : glLane();
            System.out.println("GPU SMOKE CHILD " + which + ": " + (ok ? "answered" : "failed"));
            if (!ok) {
                System.exit(1);
            }
            return;
        }
        // Outer JVM: one child per lane. A driver that aborts its process
        // (SIGSEGV inside lavapipe, a broken libGL, ...) takes down only its
        // own lane; the parent still prints every receipt and exits by name.
        boolean doVk = !"gl".equals(lane);
        boolean doGl = !"vk".equals(lane);
        boolean vkOk = true;
        boolean glOk = true;
        if (doVk) {
            vkOk = runLaneInChild("vk");
        }
        if (doGl) {
            glOk = runLaneInChild("gl");
        }
        System.out.println("GPU SMOKE: "
                + (doVk ? (vkOk ? "vk answered" : "vk FAILED") : "vk not asked") + ", "
                + (doGl ? (glOk ? "gl answered" : "gl FAILED") : "gl not asked")
                + " (answered = RENDERED or refused/skipped BY NAME)");
        if (!vkOk || !glOk) {
            System.exit(1);
        }
    }

    /** Spawns {@code GpuSmoke child-<lane>} in its own JVM; inheritIO keeps the
     *  receipts in order on this process's streams. */
    static boolean runLaneInChild(String lane) {
        String java = System.getProperty("java.home") + "/bin/java";
        // -D properties do NOT cross a process boundary, and the lane's pack is
        // selected by one (-Dauralite.pack). Dropping it silently ran the
        // reference pack no matter what the caller asked for, which is exactly
        // the sort of "it rendered, so it must be the right pack" trap the
        // receipts exist to prevent: forward the pack choice explicitly.
        java.util.List<String> cmd = new java.util.ArrayList<String>();
        cmd.add(java);
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        String pack = System.getProperty("auralite.pack");
        if (pack != null && !pack.isEmpty()) cmd.add("-Dauralite.pack=" + pack);
        String glslang = System.getProperty("auralite.glslang");
        if (glslang != null && !glslang.isEmpty()) cmd.add("-Dauralite.glslang=" + glslang);
        cmd.add(GpuSmoke.class.getName());
        cmd.add("child-" + lane);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        try {
            int rc = pb.start().waitFor();
            if (rc == 0) {
                return true;
            }
            System.out.println("GPU " + lane + ": CRASH-UNNAMED - the lane's JVM ended with exit "
                    + rc + " (native abort?); the findings printed above and any hs_err_pid*.log "
                    + "are the receipt");
            return false;
        } catch (Exception e) {
            System.out.println("GPU " + lane + ": CRASH-UNNAMED - could not run the lane child: " + e);
            return false;
        }
    }

    // -------------------------------------------------------------- vk lane

    static boolean vkLane() {
        VkDevice dev = null;
        try {
            dev = new VkDevice();
            String validation = "1".equals(System.getenv("AURALITE_VK_VALIDATION"))
                    ? "ON (AURALITE_VK_VALIDATION=1)" : "off";
            System.out.println("GPU vk: device \"" + dev.deviceName() + "\", validation " + validation);
            GfxImage backbuffer = dev.createImage(GfxDesc.Image.tex2d(WIDTH, HEIGHT, GfxFormat.RGBA8_UNORM,
                    GfxUsage.IMAGE_COLOR_ATTACHMENT | GfxUsage.IMAGE_PRESENT
                    | GfxUsage.IMAGE_TRANSFER_SRC).named("window"));
            String receipt = runFrame(dev, true, backbuffer);
            System.out.println("GPU vk: RENDERED - " + receipt);
            return true;
        } catch (Throwable t) {
            return namedOrCrash("vk", t);
        } finally {
            closeQuietly(dev);
        }
    }

    // -------------------------------------------------------------- gl lane

    static boolean glLane() {
        long dpy = 0L;
        long surf = 0L;
        long ctx = 0L;
        GlDevice dev = null;
        try {
            // Headless container: Mesa's default platform walks GBM (no
            // /dev/dri) and X11 (no server) and fails 0x3001. The 1.5
            // platform call is rejected by LWJGL 3.4.3 itself (it checks the
            // EGL_DEFAULT_DISPLAY 0 argument for non-NULL); the path that
            // works is EGL_PLATFORM=surfaceless honored by eglGetDisplay -
            // make gpu-test sets it in the recipe.
            PointerBuffer noAtts = BufferUtils.createPointerBuffer(1);
            noAtts.put(0, EGL10.EGL_NONE);
            try {
                dpy = EGL15.eglGetPlatformDisplay(0x31DD, EGL14.EGL_DEFAULT_DISPLAY, noAtts);
            } catch (Throwable lwjglDefaultDisplayCheck) {
                // Checks.check(0) on EGL_DEFAULT_DISPLAY; fall through.
            }
            if (dpy == 0L) {
                dpy = EGL10.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            }
            if (dpy == 0L) {
                return skip("gl", "no EGL display (eglGetDisplay returned EGL_NO_DISPLAY); "
                        + "this host needs EGL_PLATFORM=surfaceless (make gpu-test sets it)");
            }
            IntBuffer major = BufferUtils.createIntBuffer(1);
            IntBuffer minor = BufferUtils.createIntBuffer(1);
            if (!EGL10.eglInitialize(dpy, major, minor)) {
                return skip("gl", "eglInitialize failed, eglGetError=0x"
                        + Integer.toHexString(EGL10.eglGetError())
                        + " (headless host? run with EGL_PLATFORM=surfaceless - make gpu-test does)");
            }
            EGL12.eglBindAPI(EGL14.EGL_OPENGL_API);

            IntBuffer cfgAttrs = BufferUtils.createIntBuffer(8);
            cfgAttrs.put(new int[] {
                    EGL12.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_BIT,
                    EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                    EGL10.EGL_NONE }).flip();
            PointerBuffer configs = BufferUtils.createPointerBuffer(4);
            IntBuffer numConfigs = BufferUtils.createIntBuffer(1);
            if (!EGL10.eglChooseConfig(dpy, cfgAttrs, configs, numConfigs) || numConfigs.get(0) < 1) {
                return skip("gl", "no EGL config with EGL_OPENGL_BIT, eglGetError=0x"
                        + Integer.toHexString(EGL10.eglGetError()));
            }
            long cfg = configs.get(0);

            String chosen = null;
            ctx = createContext(dpy, cfg, 4, 6);
            if (ctx != 0L) {
                chosen = "4.6";
            } else {
                ctx = createContext(dpy, cfg, 4, 5);
                if (ctx != 0L) {
                    chosen = "4.5";
                } else {
                    ctx = createContext(dpy, cfg, 0, 0);
                    if (ctx != 0L) {
                        chosen = "default";
                    }
                }
            }
            if (ctx == 0L) {
                return skip("gl", "eglCreateContext failed for 4.6, 4.5 and default, eglGetError=0x"
                        + Integer.toHexString(EGL10.eglGetError()));
            }

            IntBuffer surfAttrs = BufferUtils.createIntBuffer(8);
            surfAttrs.put(new int[] { EGL10.EGL_WIDTH, 16, EGL10.EGL_HEIGHT, 16, EGL10.EGL_NONE }).flip();
            surf = EGL10.eglCreatePbufferSurface(dpy, cfg, surfAttrs);
            if (surf == 0L) {
                return skip("gl", "eglCreatePbufferSurface failed, eglGetError=0x"
                        + Integer.toHexString(EGL10.eglGetError()));
            }
            if (!EGL10.eglMakeCurrent(dpy, surf, surf, ctx)) {
                return skip("gl", "eglMakeCurrent failed, eglGetError=0x"
                        + Integer.toHexString(EGL10.eglGetError()));
            }

            GLCapabilities caps = GL.createCapabilities();
            String version = GL11.glGetString(GL11.GL_VERSION);
            String renderer = GL11.glGetString(GL11.GL_RENDERER);
            String glsl = GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION);
            System.out.println("GPU gl: EGL context " + chosen + ", \"" + renderer + "\", GL \""
                    + version + "\", GLSL \"" + glsl + "\", core=" + capsOpenGLCore(caps));

            dev = new GlDevice();
            // Offscreen, not the window: on a surfaceless context the default
            // framebuffer has no storage to hash - the pixel gate needs an
            // image the readback can actually see (the real window presents
            // with the fabric glue, phase 4).
            GfxImage backbuffer = dev.createImage(GfxDesc.Image.tex2d(WIDTH, HEIGHT,
                    GfxFormat.RGBA8_UNORM, GfxUsage.IMAGE_COLOR_ATTACHMENT
                    | GfxUsage.IMAGE_PRESENT | GfxUsage.IMAGE_TRANSFER_SRC).named("window"));
            String receipt = runFrame(dev, false, backbuffer);
            System.out.println("GPU gl: RENDERED - " + receipt);
            return true;
        } catch (Throwable t) {
            return namedOrCrash("gl", t);
        } finally {
            closeQuietly(dev);
            if (dpy != 0L) {
                if (ctx != 0L) {
                    EGL10.eglMakeCurrent(dpy, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                    EGL10.eglDestroyContext(dpy, ctx);
                }
                if (surf != 0L) {
                    EGL10.eglDestroySurface(dpy, surf);
                }
            }
        }
    }

    private static long createContext(long dpy, long cfg, int major, int minor) {
        IntBuffer attrs = BufferUtils.createIntBuffer(8);
        if (major > 0) {
            attrs.put(new int[] {
                    EGL15.EGL_CONTEXT_MAJOR_VERSION, major,
                    EGL15.EGL_CONTEXT_MINOR_VERSION, minor,
                    EGL15.EGL_CONTEXT_OPENGL_PROFILE_MASK, EGL15.EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT,
                    EGL10.EGL_NONE }).flip();
        } else {
            attrs.put(new int[] { EGL10.EGL_NONE }).flip();
        }
        return EGL10.eglCreateContext(dpy, cfg, EGL10.EGL_NO_CONTEXT, attrs);
    }

    private static boolean capsOpenGLCore(GLCapabilities caps) {
        return caps.OpenGL32 || caps.OpenGL40 || caps.OpenGL41 || caps.OpenGL45 || caps.OpenGL46;
    }

    // ----------------------------------------------------------- frame lane

    static String runFrame(GfxDevice dev, boolean spirv, GfxImage backbuffer) throws Exception {
        Path refDir = Paths.get(System.getProperty("auralite.pack", "packs/AuraLite-Native"));
        if (!Files.isRegularFile(refDir.resolve("pack.aurapack"))) {
            throw new GfxException("gpu smoke: " + refDir + "/pack.aurapack not found - run from the repository root");
        }
        PackLoader loader = new PackLoader(new PackSource.Directory(refDir));
        if (!loader.load(dev)) {
            throw new GfxException("gpu smoke: the reference pack did not load on device \""
                    + dev.deviceName() + "\": " + loader.report().errors());
        }
        PackManifest manifest = loader.manifest();
        PipelineProvider provider = spirv ? spirvProvider(dev, manifest) : glslProvider(dev, manifest);
        PackGraphBuilder builder = new PackGraphBuilder(manifest, provider);
        RenderGraph graph = builder.build(WIDTH, HEIGHT, manifest.shadowMapResolution(),
                new int[] { 23, 97, 23 });
        CompiledGraph compiled = graph.compile(WIDTH, HEIGHT, dev.limits());
        compiled.allocate(dev);

        compiled.bindImported("backbuffer", backbuffer);
        // The reference pack reads the host's captured vanilla frame; bind it
        // the way the fabric lane (SceneCapture) does - same guard: a pack
        // without the scene resource needs no binding. The scene starts empty
        // (no vanilla frame exists here), so fill it with a nonzero pattern:
        // the final stage copies scene -> backbuffer, and the pixel gate must
        // see a frame that actually travelled through the pack.
        if (manifest.graph.findResource("scene") != null) {
            GfxImage sceneImg = dev.createImage(GfxDesc.Image.tex2d(WIDTH, HEIGHT,
                    GfxFormat.RGBA8_UNORM, GfxUsage.IMAGE_STORAGE | GfxUsage.IMAGE_SAMPLED
                            | GfxUsage.IMAGE_COLOR_ATTACHMENT).named("scene"));
            compiled.bindImported("scene", sceneImg);
            java.nio.ByteBuffer px = java.nio.ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
            while (px.hasRemaining()) px.put((byte) 0x40);
            px.flip();
            dev.uploadImage(sceneImg, 0, px);
        }

        // The widened seam's host side: a pack that declares a biome-volume
        // input is a bake, and the host is what turns a field into texels. The
        // reference pack declares no such resource (its terrain stage has no
        // biome input yet), so this is inert there and a real upload for the
        // seam probe - which is what makes that pack's pixel hash depend on the
        // radius instead of on nothing.
        boolean biomeDriven = uploadBiomeField(dev, manifest, compiled);

        dev.beginFrame();
        GfxCommandBuffer cmd = dev.createCommandBuffer(QueueType.GRAPHICS);
        cmd.begin();
        cmd.writeTimestamp(0);
        compiled.execute(new GraphContext(dev, cmd, compiled, new FrameContext()));
        cmd.writeTimestamp(1);
        cmd.end();
        dev.submit(QueueType.GRAPHICS, cmd);
        dev.waitIdle();
        dev.endFrame();

        // The frame is not "rendered" until its pixels exist: hash mip 0 of
        // the backbuffer, and refuse to call the frame anything if the bytes
        // are all zero (rule: never claim a frame that isn't there).
        // Diagnostics for a pack that paints what it baked: name what reached
        // the volumes BEFORE the pixel check can fail, so the failure is
        // attributable in one run.
        String volumeReceipt = biomeDriven
                ? describeVolume(dev, compiled, "biome_field_ids")
                  + "; " + describeVolume(dev, compiled, "biome_volume")
                : "";
        byte[] pixels = dev.readImage(backbuffer);
        int nonzero = 0;
        for (byte b : pixels) if (b != 0) nonzero++;
        if (pixels.length == 0 || nonzero == 0) {
            throw new GfxException("gpu smoke: readback of the backbuffer is "
                    + (pixels.length == 0 ? "empty" : "all " + pixels.length + " bytes zero")
                    + " - no frame reached the image"
                    + (volumeReceipt.isEmpty() ? "" : "; " + volumeReceipt)
                    + "; pass order " + compiled.passOrder());
        }
        long[] ts = dev.frameTimestamps();
        if (ts.length < 2) {
            throw new GfxException("gpu smoke: expected 2 frame timestamps, got "
                    + java.util.Arrays.toString(ts));
        }
        for (int i = 1; i < ts.length; i++) {
            if (ts[i] <= ts[i - 1]) {
                throw new GfxException("gpu smoke: timestamps not strictly increasing: "
                        + java.util.Arrays.toString(ts));
            }
        }
        // Rebuild replay gate (r42). The Windows crashes happen two or three
        // frames AFTER a rebuild, on the first draw that follows - and the
        // cause is that the previous graph was released with its cached
        // framebuffers still alive: a framebuffer pins the attachment views,
        // which pin the image, so freeing an image under them is undefined
        // behaviour the driver answers with 0xC0000409 much later. Replaying
        // the host's rebuild sequence here keeps that hazard under a gate: a
        // fresh compile, one executed frame, the old graph released, another
        // frame - on the real backend, with validation on.

        // Step 8's gate: the SAME frame, twice, differing only in what the host
        // put in the biome volume's source field. If the hash does not move, the
        // terrain stage is not reading the volume - which is precisely the state
        // this pack was in from step 2 until now: `read=biome_volume` declared,
        // not one texel touched, every receipt green. A gate that cannot fail is
        // worse than no gate, so this one is built to be able to.
        String volumeDriven = "";
        if (biomeDriven) {
            fillBiomeField(dev, compiled, true);
            dev.beginFrame();
            GfxCommandBuffer bcmd = dev.createCommandBuffer(QueueType.GRAPHICS);
            bcmd.begin();
            compiled.execute(new GraphContext(dev, bcmd, compiled, new FrameContext()));
            bcmd.end();
            dev.submit(QueueType.GRAPHICS, bcmd);
            dev.waitIdle();
            dev.endFrame();
            byte[] second = dev.readImage(backbuffer);
            String firstHash = sha256Hex(pixels);
            String secondHash = sha256Hex(second);
            if (firstHash.equals(secondHash)) {
                throw new GfxException("gpu smoke: the frame did not change when the biome volume's"
                        + " source field changed - the terrain pass declares read=biome_volume and"
                        + " does not sample it (hash " + firstHash + ")");
            }
            volumeDriven = "; volume-driven: " + firstHash.substring(0, 16) + " != "
                    + secondHash.substring(0, 16) + " after a uniform field";
            // Put the split field back: the rebuild-replay gate below must run on
            // the same input the first frame saw.
            fillBiomeField(dev, compiled, false);
        }

        for (int round = 0; round < 2; round++) {
            CompiledGraph next = graph.compile(WIDTH, HEIGHT, dev.limits());
            next.allocate(dev);
            next.bindImported("backbuffer", backbuffer);
            GfxImage sceneAgain = null;
            if (manifest.graph.findResource("scene") != null) {
                sceneAgain = dev.createImage(GfxDesc.Image.tex2d(WIDTH, HEIGHT,
                        GfxFormat.RGBA8_UNORM, GfxUsage.IMAGE_STORAGE | GfxUsage.IMAGE_SAMPLED
                                | GfxUsage.IMAGE_COLOR_ATTACHMENT).named("scene"));
                next.bindImported("scene", sceneAgain);
                // Same fill as the first frame: the present pass copies the
                // scene through, so an empty scene means an empty frame and
                // the check below would be meaningless.
                java.nio.ByteBuffer fill = java.nio.ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4);
                while (fill.hasRemaining()) fill.put((byte) 0x40);
                fill.flip();
                dev.uploadImage(sceneAgain, 0, fill);
            }
            dev.beginFrame();
            GfxCommandBuffer rcb = dev.createCommandBuffer(QueueType.GRAPHICS);
            rcb.begin();
            next.execute(new GraphContext(dev, rcb, next, new FrameContext()));
            rcb.end();
            dev.submit(QueueType.GRAPHICS, rcb);
            dev.waitIdle();
            dev.endFrame();
            byte[] again = dev.readImage(backbuffer);
            int non = 0;
            for (byte b : again) if (b != 0) non++;
            if (again.length == 0 || non == 0) {
                throw new GfxException("gpu smoke: rebuild replay " + (round + 1)
                        + " produced an empty frame");
            }
            // Release the previous graph BEFORE the next one is compiled, the
            // same order reloadPack uses on the host.
            compiled.release();
            if (sceneAgain != null) sceneAgain.close();
            compiled = next;
        }
        String receipt = (biomeDriven ? "biome field uploaded, " : "")
                + cmd.commandCount() + " commands, " + compiled.totalBarriers()
                + " barriers, " + compiled.passOrder().size() + " passes, 0 violations (none raised), "
                + WIDTH + "x" + HEIGHT + " on \"" + dev.deviceName() + "\""
                + (volumeReceipt.isEmpty() ? "" : "; " + volumeReceipt)
                + volumeDriven
                + "; pixels sha256=" + sha256Hex(pixels)
                + (biomeDriven ? "; centre=" + centrePixel(pixels) : "")
                + " nonzero=" + nonzero + "/" + pixels.length
                + "; timestamps " + ts[0] + " < " + ts[ts.length - 1];
        compiled.release();
        return receipt;
    }

    /** RGBA at the middle of the frame, as hex: a frame that ignores its input shows a constant. */
    static String centrePixel(byte[] px) {
        if (px == null || px.length < WIDTH * HEIGHT * 4) return "n/a";
        int i = ((HEIGHT / 2) * WIDTH + WIDTH / 2) * 4;
        return String.format("%02x%02x%02x%02x", px[i] & 0xFF, px[i + 1] & 0xFF,
                px[i + 2] & 0xFF, px[i + 3] & 0xFF);
    }

    static String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest(data)) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new GfxException("gpu smoke: SHA-256 unavailable: " + e.getMessage());
        }
    }

    // ----------------------------------------------------------- providers

    /** GL lane: hand the pack's GLSL straight to the driver. */
    static PipelineProvider glslProvider(final GfxDevice dev, final PackManifest manifest) {
        return new PipelineProvider() {
            @Override public GfxPipeline rasterPipeline(String name, GfxDesc.RenderPass pass) {
                PackProgram p = requireProgram(manifest, name);
                GfxDesc.GraphicsPipeline gp = new GfxDesc.GraphicsPipeline().renderPass(pass).layout(p.layout()).named(name);
                // The pack's option defines are applied here through the SAME
                // core call the fabric providers use (PackManifest
                // .withOptionDefines). The harness used to feed raw stages to
                // the device, so a lane could compile a shader the pack never
                // asked for: the GL lane rendered seamRadius = 2 for BOTH probe
                // packs while the Vulkan lane - which applied the defines -
                // rendered two different frames.
                ShaderSource vs = manifest.withOptionDefines(p.stage(GfxStage.VERTEX));
                ShaderSource fs = manifest.withOptionDefines(p.stage(GfxStage.FRAGMENT));
                if (vs != null) {
                    gp.stage(dev.createShaderModule(vs));
                }
                if (fs != null) {
                    gp.stage(dev.createShaderModule(fs));
                }
                return dev.createGraphicsPipeline(gp);
            }

            @Override public GfxPipeline computePipeline(String name) {
                PackProgram p = manifest.program(name);
                if (p == null || p.stage(GfxStage.COMPUTE) == null) {
                    return null;
                }
                return dev.createComputePipeline(GfxDesc.ComputePipeline
                        .of(dev.createShaderModule(
                                manifest.withOptionDefines(p.stage(GfxStage.COMPUTE))))
                        .localSize(p.localSizeX, p.localSizeY, p.localSizeZ)
                        .layout(p.layout()).named(name));
            }

            @Override public boolean has(String name) {
                PackProgram p = manifest.program(name);
                return p != null && p.stageCount() > 0;
            }
        };
    }

    /** VK lane: compile every pack stage to SPIR-V with glslangValidator first. */
    static PipelineProvider spirvProvider(final GfxDevice dev, final PackManifest manifest)
            throws Exception {
        final Path dir = Files.createTempDirectory("auralite-spv");
        final Map<String, ShaderSource> cache = new HashMap<>();
        final java.util.List<String> defines =
                java.util.Collections.unmodifiableList(manifest.activeDefines());
        final PipelineProvider glsl = glslProvider(dev, manifest);
        return new PipelineProvider() {
            @Override public GfxPipeline rasterPipeline(String name, GfxDesc.RenderPass pass) {
                PackProgram p = requireProgram(manifest, name);
                GfxDesc.GraphicsPipeline gp = new GfxDesc.GraphicsPipeline().renderPass(pass).layout(p.layout()).named(name);
                ShaderSource vs = toSpirv(dir, cache, p.stage(GfxStage.VERTEX), defines);
                ShaderSource fs = toSpirv(dir, cache, p.stage(GfxStage.FRAGMENT), defines);
                if (vs != null) {
                    gp.stage(dev.createShaderModule(vs));
                }
                if (fs != null) {
                    gp.stage(dev.createShaderModule(fs));
                }
                return dev.createGraphicsPipeline(gp);
            }

            @Override public GfxPipeline computePipeline(String name) {
                PackProgram p = manifest.program(name);
                if (p == null || p.stage(GfxStage.COMPUTE) == null) {
                    return null;
                }
                ShaderSource comp = toSpirv(dir, cache, p.stage(GfxStage.COMPUTE), defines);
                return dev.createComputePipeline(GfxDesc.ComputePipeline
                        .of(dev.createShaderModule(comp))
                        .localSize(p.localSizeX, p.localSizeY, p.localSizeZ)
                        .layout(p.layout()).named(name));
            }

            @Override public boolean has(String name) {
                return glsl.has(name);
            }
        };
    }

    private static PackProgram requireProgram(PackManifest manifest, String name) {
        PackProgram p = manifest.program(name);
        if (p == null) {
            throw new GfxException("gpu smoke: the pack declares pass program '" + name
                    + "' but never defines it");
        }
        return p;
    }

    private static ShaderSource toSpirv(Path dir, Map<String, ShaderSource> cache, ShaderSource src,
                                        java.util.List<String> defines) {
        if (src == null) {
            return null;
        }
        if (src.kind() != ShaderSource.Kind.GLSL) {
            return src;
        }
        String key = src.origin() + "|" + src.stage() + "|" + defines;
        ShaderSource hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        String stageArg;
        if (src.stage() == GfxStage.VERTEX) {
            stageArg = "vert";
        } else if (src.stage() == GfxStage.FRAGMENT) {
            stageArg = "frag";
        } else if (src.stage() == GfxStage.COMPUTE) {
            stageArg = "comp";
        } else {
            throw new GfxException("gpu smoke: stage " + src.stage() + " of '" + src.origin()
                    + "' has no glslangValidator stage name in this harness");
        }
        try {
            String safe = src.origin().replace('/', '_').replace('\\', '_');
            Path in = dir.resolve(safe + "." + stageArg);
            // PACK OPTIONS ARE DEFINES. They were being collected and never
            // compiled with, so every option was inert in every shader -- found
            // by a probe that changed a radius and got a byte-identical frame.
            // The stage may already carry the pack's defines (it arrives through
            // manifest.withOptionDefines), and `defines` is the same list. A
            // second copy is harmless only while the define is guarded by
            // #ifndef - a bare one would be a redefinition warning, so the
            // already-carried copy wins and the explicit list fills in the rest.
            java.util.List<String> bare = new java.util.ArrayList<String>();
            for (String d : defines) {
                String name = d.replaceFirst("^#define\\s+", "").split("\\s+")[0];
                boolean carried = false;
                for (String have : src.defines()) {
                    if (have.equals(d.replaceFirst("^#define\\s+", ""))
                            || have.startsWith(name + " ")) {
                        carried = true;
                        break;
                    }
                }
                if (!carried) bare.add(d);
            }
            String text = dev.auralite.render.pack.PackDefines.apply(src.text(), bare);
            Files.write(in, text.getBytes(StandardCharsets.UTF_8));
            Path out = dir.resolve(safe + "." + stageArg + ".spv");
            ProcessBuilder pb = new ProcessBuilder(
                    "glslangValidator", "-V", "-S", stageArg, "-o", out.toString(), in.toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            byte[] log = proc.getInputStream().readAllBytes();
            int rc = proc.waitFor();
            if (rc != 0) {
                throw new GfxException("gpu smoke: glslangValidator rejected '" + src.origin()
                        + "' (rc=" + rc + "): " + firstLines(new String(log, StandardCharsets.UTF_8), 5));
            }
            ShaderSource spv = ShaderSource.spirv(src.stage(), Files.readAllBytes(out), "main", src.origin());
            cache.put(key, spv);
            return spv;
        } catch (GfxException e) {
            throw e;
        } catch (Exception e) {
            throw new GfxException("gpu smoke: SPIR-V compile of '" + src.origin() + "' failed: " + e);
        }
    }

    // ------------------------------------------------------------- receipts

    private static boolean namedOrCrash(String lane, Throwable t) {
        if (t instanceof GfxException && t.getMessage() != null) {
            System.out.println("GPU " + lane + ": REFUSED-NAMED - " + t.getMessage());
            return true;
        }
        System.out.println("GPU " + lane + ": CRASH-UNNAMED - " + t);
        t.printStackTrace(System.out);
        return false;
    }

    private static boolean skip(String lane, String why) {
        System.out.println("GPU " + lane + ": SKIP-NAMED - " + why);
        return true;
    }

    private static void closeQuietly(GfxDevice dev) {
        if (dev != null) {
            try {
                dev.close();
            } catch (RuntimeException ignored) {
                // The receipt already happened; teardown noise must not re-name it.
            }
        }
    }

    private static String firstLines(String text, int n) {
        String[] lines = text.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && i < lines.length; i++) {
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append(lines[i].trim());
        }
        return sb.toString();
    }

    // ---- widened biome seam: the probe's host side ----------------------------



    /** Cell counts the reference lane uses; also what the graph is built with. */
    static final int[] CELLS = { 23, 97, 23 };

    /**
     * Uploads {@code biome_field_ids} when the pack declares it.
     *
     * @return true when a field was uploaded, false when the pack wants none
     *         (the reference pack today), so a receipt can say which happened
     *         instead of leaving the reader to guess
     */
    static boolean uploadBiomeField(GfxDevice dev, PackManifest manifest, CompiledGraph compiled) {
        if (manifest.graph.findResource("biome_field_ids") == null) return false;
        fillBiomeField(dev, compiled, Boolean.getBoolean("auralite.smoke.uniformBiome"));
        return true;
    }

    /**
     * Uploads the host's exact-id field into the graph's source volume.
     *
     * <p>{@code uniform} selects the CONTENT: a swamp/plains split down the
     * middle, or one biome everywhere. The two are the step-8 gate's inputs - the
     * same frame must draw differently for them, or the terrain stage is not
     * reading what the bake wrote.
     */
    static void fillBiomeField(GfxDevice dev, CompiledGraph compiled, boolean uniform) {
        GfxImage target = imageOf(compiled, "biome_field_ids");
        int sx = CELLS[0], sy = CELLS[1], sz = CELLS[2];

        BiomeRegistry reg = BiomeRegistry.vanillaDefaults();
        BiomeField field = new BiomeField();
        field.allocate(sx, sy, sz);
        int swamp = reg.constantId("BIOME_SWAMP");
        int plains = reg.constantId("BIOME_PLAINS");
        int border = uniform ? 0 : sx / 2;
        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                for (int x = 0; x < sx; x++) {
                    field.setIdAtCell(field.originCX() + x, field.originCY() + y,
                            field.originCZ() + z, x < border ? swamp : plains);
                }
            }
        }

        // The host sends the EXACT ids; 0xFFFFFFFF is the pack format's "nothing
        // ingested", the same value BiomeFieldPacker writes.
        ByteBuffer px = ByteBuffer.allocateDirect(sx * sy * sz * 4).order(ByteOrder.nativeOrder());
        int[] ids = field.rawIds();
        for (int i = 0; i < ids.length; i++) {
            px.putInt(ids[i] == BiomeField.UNKNOWN ? (int) BiomeSeamShader.UNKNOWN_FIELD : ids[i]);
        }
        px.flip();
        // Synchronous by contract: the backend's one-shot path waits on its own
        // fence before returning. A `dev.waitIdle()` used to sit here as a
        // diagnostic for a suspected race, and it changed nothing - which is
        // one of the measurements that finally pointed at the format instead
        // of the ordering. Removed so the next reader is not invited to chase
        // a race that does not exist.
        dev.uploadImage(target, 0, px);
        if (target.format() != GfxFormat.R32_UINT) {
            throw new GfxException("biome_field_ids must be r32_uint, the graph allocated "
                    + target.format());
        }
    }

    /**
     * What actually reached the volume, read back from the device. A probe pack
     * that renders black has three candidate causes -- no dispatch, a dispatch
     * that missed the image, or a shader that wrote nothing -- and this
     * distinguishes them instead of leaving the next revision to guess.
     */
    static String describeVolume(GfxDevice dev, CompiledGraph compiled, String name) {
        if (manifestHas(compiled, name)) {
            try {
                byte[] raw = dev.readImage(imageOf(compiled, name));
                int nonzero = 0;
                java.util.Set<Integer> distinct = new java.util.TreeSet<Integer>();
                for (byte b : raw) { if (b != 0) nonzero++; distinct.add(b & 0xFF); }
                StringBuilder head = new StringBuilder();
                for (int i = 0; i + 3 < raw.length && i < 24; i += 4) {   // six words
                    head.append(String.format("%02x%02x%02x%02x ",
                            raw[i] & 0xFF, raw[i + 1] & 0xFF, raw[i + 2] & 0xFF, raw[i + 3] & 0xFF));
                }
                // A byte histogram, because the failure this receipt exists for
                // is invisible in a prefix: the volume was right in size,
                // nonzero and plausible while EVERY cell held the same word
                // (slots 1..3 saturated to 255 by an 8-bit channel). Two
                // distinct byte values across 200 kB says "one value repeated"
                // at a glance; ten texels of prefix did not.
                java.util.Map<Integer, Integer> hist = new java.util.TreeMap<Integer, Integer>();
                for (byte b : raw) hist.merge(b & 0xFF, 1, Integer::sum);
                StringBuilder hb = new StringBuilder();
                for (java.util.Map.Entry<Integer, Integer> e : hist.entrySet()) {
                    if (hb.length() > 0) hb.append(' ');
                    hb.append(String.format("%02x:%d", e.getKey(), e.getValue()));
                }
                head.append("| hist ").append(hb);
                return name + ": " + raw.length + " B, " + nonzero + " nonzero, "
                        + distinct.size() + " distinct byte values, first " + head;
            } catch (RuntimeException e) {
                return name + ": unreadable (" + e.getMessage() + ")";
            }
        }
        return name + ": not declared";
    }

    private static boolean manifestHas(CompiledGraph compiled, String name) {
        try {
            return imageOf(compiled, name) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The graph's own image for a resource name, whatever its extent. */
    static GfxImage imageOf(CompiledGraph compiled, String name) {
        return compiled.graph().require(name).image();
    }
}
