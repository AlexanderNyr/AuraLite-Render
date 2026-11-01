package dev.auralite.render.tests;

import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
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
        ProcessBuilder pb = new ProcessBuilder(
                java, "-cp", System.getProperty("java.class.path"),
                GpuSmoke.class.getName(), "child-" + lane);
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
        byte[] pixels = dev.readImage(backbuffer);
        int nonzero = 0;
        for (byte b : pixels) if (b != 0) nonzero++;
        if (pixels.length == 0 || nonzero == 0) {
            throw new GfxException("gpu smoke: readback of the backbuffer is "
                    + (pixels.length == 0 ? "empty" : "all " + pixels.length + " bytes zero")
                    + " - no frame reached the image");
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
        String receipt = cmd.commandCount() + " commands, " + compiled.totalBarriers()
                + " barriers, " + compiled.passOrder().size() + " passes, 0 violations (none raised), "
                + WIDTH + "x" + HEIGHT + " on \"" + dev.deviceName() + "\""
                + "; pixels sha256=" + sha256Hex(pixels)
                + " nonzero=" + nonzero + "/" + pixels.length
                + "; timestamps " + ts[0] + " < " + ts[ts.length - 1];
        compiled.release();
        return receipt;
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
                ShaderSource vs = p.stage(GfxStage.VERTEX);
                ShaderSource fs = p.stage(GfxStage.FRAGMENT);
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
                        .of(dev.createShaderModule(p.stage(GfxStage.COMPUTE)))
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
        final PipelineProvider glsl = glslProvider(dev, manifest);
        return new PipelineProvider() {
            @Override public GfxPipeline rasterPipeline(String name, GfxDesc.RenderPass pass) {
                PackProgram p = requireProgram(manifest, name);
                GfxDesc.GraphicsPipeline gp = new GfxDesc.GraphicsPipeline().renderPass(pass).layout(p.layout()).named(name);
                ShaderSource vs = toSpirv(dir, cache, p.stage(GfxStage.VERTEX));
                ShaderSource fs = toSpirv(dir, cache, p.stage(GfxStage.FRAGMENT));
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
                ShaderSource comp = toSpirv(dir, cache, p.stage(GfxStage.COMPUTE));
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

    private static ShaderSource toSpirv(Path dir, Map<String, ShaderSource> cache, ShaderSource src) {
        if (src == null) {
            return null;
        }
        if (src.kind() != ShaderSource.Kind.GLSL) {
            return src;
        }
        String key = src.origin() + "|" + src.stage();
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
            Files.write(in, src.text().getBytes(StandardCharsets.UTF_8));
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
}
