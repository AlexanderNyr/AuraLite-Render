package dev.auralite.render.backend.nulldev;

import dev.auralite.render.rhi.DeviceLimits;
import dev.auralite.render.rhi.GfxBuffer;
import dev.auralite.render.rhi.GfxCapability;
import dev.auralite.render.rhi.GfxCommandBuffer;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxDevice;
import dev.auralite.render.rhi.GfxEnums.BackendType;
import dev.auralite.render.rhi.GfxEnums.QueueType;
import dev.auralite.render.rhi.GfxException;
import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxImage;
import dev.auralite.render.rhi.GfxPipeline;
import dev.auralite.render.rhi.GfxResource;
import dev.auralite.render.rhi.GfxSampler;
import dev.auralite.render.rhi.GfxShaderModule;
import dev.auralite.render.rhi.GfxStage;
import dev.auralite.render.rhi.ShaderSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A recording backend with no GPU behind it.
 *
 * <p>Every command is validated and counted, so the whole pipeline -- graph
 * compilation, barrier derivation, pass bodies, descriptor writes, dispatch
 * limits -- is exercised by {@code make test} on a machine with no display and
 * no driver. This is the same trick the AuraLite emulator uses for its device
 * models: the mock is not a stub, it is an oracle with a receipt.
 *
 * <p>What it does <b>not</b> check is shader semantics. It validates that GLSL
 * has an entry point and that SPIR-V carries the right magic word; it cannot
 * tell you the lighting is wrong. Those lanes need a real driver and are
 * marked SKIP honestly.
 */
public final class NullDevice implements GfxDevice {

    /** Deliberately conservative so an over-requesting pack is caught here. */
    public static DeviceLimits conservativeLimits() {
        DeviceLimits l = new DeviceLimits();
        l.maxTexture2DSize = 16384;
        l.maxTexture3DSize = 2048;
        l.maxColorAttachments = 8;
        l.maxBoundDescriptorSets = 4;
        l.maxPerStageSamplers = 32;
        l.maxPushConstantSize = 128;
        l.maxComputeWorkGroupInvocations = 1024;
        l.maxComputeSharedMemorySize = 49152;
        l.subgroupSize = 32;
        l.minUniformBufferOffsetAlignment = 256;
        l.swapchainImageCount = 3;
        return l;
    }

    /** Every capability a modern GL46/VK13 device is expected to have. */
    public static Set<GfxCapability> fullCapabilities() {
        return EnumSet.allOf(GfxCapability.class);
    }

    // ------------------------------------------------------------- resources
    private abstract static class BaseResource implements GfxResource {
        final long handle;
        final String debugName;
        final long sizeBytes;
        boolean closed;
        BaseResource(long handle, String debugName, long sizeBytes) {
            this.handle = handle; this.debugName = debugName; this.sizeBytes = sizeBytes;
        }
        @Override public long handle() { return handle; }
        @Override public String debugName() { return debugName; }
        @Override public long sizeBytes() { return sizeBytes; }
    }

    static final class NullBuffer extends BaseResource implements GfxBuffer {
        private final int usage;
        private final dev.auralite.render.rhi.GfxEnums.MemoryDomain domain;
        private final byte[] shadow;
        NullBuffer(long handle, GfxDesc.Buffer d) {
            super(handle, d.debugName, d.sizeBytes);
            this.usage = d.usage;
            this.domain = d.domain;
            // A host-visible buffer keeps a shadow copy so the upload gate can
            // read back what was written. Device-local ones do not, on purpose.
            this.shadow = d.domain == dev.auralite.render.rhi.GfxEnums.MemoryDomain.DEVICE_LOCAL
                    ? null : new byte[(int) Math.min(d.sizeBytes, 1 << 22)];
        }
        @Override public int usage() { return usage; }
        @Override public dev.auralite.render.rhi.GfxEnums.MemoryDomain domain() { return domain; }
        @Override public boolean hostVisible() { return shadow != null; }
        byte[] shadow() { return shadow; }
        @Override public void close() { closed = true; }
    }

    static final class NullImage extends BaseResource implements GfxImage {
        private final GfxDesc.Image desc;
        NullImage(long handle, GfxDesc.Image d) {
            super(handle, d.debugName, d.approxBytes());
            this.desc = d;
        }
        @Override public GfxDesc.Image desc() { return desc; }
        @Override public GfxFormat format() { return desc.format; }
        @Override public int width() { return desc.width; }
        @Override public int height() { return desc.height; }
        @Override public int depth() { return desc.depth; }
        @Override public int mipLevels() { return desc.mipLevels; }
        @Override public int arrayLayers() { return desc.arrayLayers; }
        @Override public int samples() { return desc.samples; }
        @Override public dev.auralite.render.rhi.GfxEnums.ImageType type() { return desc.type; }
        @Override public void close() { closed = true; }
    }

    static final class NullSampler extends BaseResource implements GfxSampler {
        private final GfxDesc.Sampler desc;
        NullSampler(long handle, GfxDesc.Sampler d) {
            super(handle, d.debugName, 0);
            this.desc = d;
        }
        @Override public GfxDesc.Sampler desc() { return desc; }
        @Override public void close() { closed = true; }
    }

    static final class NullShaderModule extends BaseResource implements GfxShaderModule {
        private final GfxStage stage;
        private final String entryPoint;
        private final boolean valid;
        private final String infoLog;
        private final long sourceHash;
        NullShaderModule(long handle, GfxStage stage, String entryPoint, boolean valid,
                         String infoLog, long sourceHash) {
            super(handle, stage.name().toLowerCase(java.util.Locale.ROOT), 0);
            this.stage = stage; this.entryPoint = entryPoint;
            this.valid = valid; this.infoLog = infoLog; this.sourceHash = sourceHash;
        }
        @Override public GfxStage stage() { return stage; }
        @Override public String entryPoint() { return entryPoint; }
        @Override public boolean isValid() { return valid; }
        @Override public String infoLog() { return infoLog; }
        @Override public long sourceHash() { return sourceHash; }
        @Override public void close() { closed = true; }
    }

    static final class NullPipeline extends BaseResource implements GfxPipeline {
        private final boolean compute;
        private final boolean valid;
        private final String infoLog;
        private final GfxDesc.PipelineLayout layout;
        NullPipeline(long handle, boolean compute, boolean valid, String infoLog,
                     GfxDesc.PipelineLayout layout, String name) {
            super(handle, name, 0);
            this.compute = compute; this.valid = valid;
            this.infoLog = infoLog; this.layout = layout;
        }
        @Override public boolean isCompute() { return compute; }
        @Override public boolean isValid() { return valid; }
        @Override public String infoLog() { return infoLog; }
        @Override public GfxDesc.PipelineLayout layout() { return layout; }
        @Override public void close() { closed = true; }
    }

    // ----------------------------------------------------------- device state
    private final DeviceLimits limits;
    private final Set<GfxCapability> capabilities;
    private final List<String> violations = new ArrayList<String>();
    private final List<String> log = new ArrayList<String>();
    private final boolean failFast;
    private long nextHandle = 1;
    private long frameId;
    private String lastError = "";
    private final long createdNanos = System.nanoTime();
    private long cpuNanos;

    private final List<NullBuffer> buffers = new ArrayList<NullBuffer>();
    private final List<NullImage> images = new ArrayList<NullImage>();
    private final List<NullSampler> samplers = new ArrayList<NullSampler>();
    private final List<NullShaderModule> modules = new ArrayList<NullShaderModule>();
    private final List<NullPipeline> pipelines = new ArrayList<NullPipeline>();
    private final List<RecordingCommandBuffer> commandBuffers = new ArrayList<RecordingCommandBuffer>();

    private int uploads;
    private long uploadedBytes;
    private int submissions;
    private int waitIdleCalls;

    public NullDevice() { this(conservativeLimits(), fullCapabilities(), true); }

    public NullDevice(DeviceLimits limits, Set<GfxCapability> capabilities, boolean failFast) {
        this.limits = limits;
        this.capabilities = Collections.unmodifiableSet(new LinkedHashSet<GfxCapability>(capabilities));
        this.failFast = failFast;
    }

    /** Records a contract violation and, in fail-fast mode, raises it. */
    void violate(String reason) {
        lastError = reason;
        violations.add(reason);
        if (failFast) throw new GfxException("null backend contract violation: " + reason);
    }

    void note(String s) {
        if (log.size() < 100000) log.add(s);
    }

    public List<String> violations() { return Collections.unmodifiableList(violations); }
    public List<String> log() { return Collections.unmodifiableList(log); }
    public int uploadCount() { return uploads; }
    public long uploadedBytes() { return uploadedBytes; }
    public int submissionCount() { return submissions; }
    public int waitIdleCount() { return waitIdleCalls; }
    public List<RecordingCommandBuffer> commandBuffers() {
        return Collections.unmodifiableList(commandBuffers);
    }

    private long newHandle() { return nextHandle++; }

    // ------------------------------------------------------------- GfxDevice
    @Override public BackendType backendType() { return BackendType.NULL; }
    @Override public String backendName() { return "AuraLite-Render NULL (recording)"; }
    @Override public String deviceName() { return "NullDevice"; }
    @Override public String driverVersion() { return "0.0.0-host"; }
    @Override public DeviceLimits limits() { return limits; }
    @Override public Set<GfxCapability> capabilities() { return capabilities; }
    @Override public boolean hasCapability(GfxCapability c) { return capabilities.contains(c); }

    @Override public GfxBuffer createBuffer(GfxDesc.Buffer desc) {
        int violationsBefore = violations.size();
        if (desc.sizeBytes <= 0) violate("buffer '" + desc.debugName + "' has size " + desc.sizeBytes);
        NullBuffer b = new NullBuffer(newHandle(), desc);
        if (refused(violationsBefore)) b.closed = true;
        buffers.add(b);
        note("createBuffer " + desc.debugName + " " + desc.sizeBytes + "B "
                + dev.auralite.render.rhi.GfxUsage.describe(desc.usage));
        return b;
    }

    @Override public GfxImage createImage(GfxDesc.Image desc) {
        int violationsBefore = violations.size();
        if (desc.width <= 0 || desc.height <= 0) {
            violate("image '" + desc.debugName + "' has extent " + desc.width + "x" + desc.height);
        }
        if (desc.type == dev.auralite.render.rhi.GfxEnums.ImageType.TEXTURE_3D
                && desc.width > limits.maxTexture3DSize) {
            violate("3D image '" + desc.debugName + "' width " + desc.width
                    + " exceeds maxTexture3DSize " + limits.maxTexture3DSize);
        }
        if (desc.width > limits.maxTexture2DSize || desc.height > limits.maxTexture2DSize) {
            violate("image '" + desc.debugName + "' exceeds maxTexture2DSize " + limits.maxTexture2DSize);
        }
        if (desc.samples > 1 && desc.mipLevels > 1) {
            violate("image '" + desc.debugName + "' is both multisampled and mipmapped");
        }
        NullImage i = new NullImage(newHandle(), desc);
        // The handle is still returned, so the caller sees the named violation
        // here instead of an NPE three frames later. But a rejected image is
        // dead on arrival: marking it closed keeps it out of liveImageCount(),
        // so the leak gate never reports a leak the device refused to create,
        // and every later use of the handle is caught by the same closed check.
        if (refused(violationsBefore)) i.closed = true;
        images.add(i);
        note("createImage " + desc.debugName + " " + desc.width + "x" + desc.height + "x" + desc.depth
                + " " + desc.format + " mips=" + desc.mipLevels);
        return i;
    }

    @Override public GfxSampler createSampler(GfxDesc.Sampler desc) {
        if (desc.maxAnisotropy > limits.maxAnisotropy) {
            violate("sampler requests anisotropy " + desc.maxAnisotropy
                    + ", device limit is " + limits.maxAnisotropy);
        }
        NullSampler s = new NullSampler(newHandle(), desc);
        samplers.add(s);
        note("createSampler " + desc.debugName);
        return s;
    }

    @Override public GfxShaderModule createShaderModule(ShaderSource source) {
        String problem = validateSource(source);
        NullShaderModule m = new NullShaderModule(newHandle(), source.stage(), source.entryPoint(),
                problem == null, problem == null ? "" : problem, source.hash());
        modules.add(m);
        note("createShaderModule " + source.stage() + " " + source.origin()
                + (problem == null ? " ok" : " INVALID: " + problem));
        return m;
    }

    /**
     * Structural validation only, and honest about it: an entry point for GLSL,
     * the SPIR-V magic word and a plausible size for binary.
     */
    private String validateSource(ShaderSource src) {
        if (src == null) return "null shader source";
        if (src.kind() == ShaderSource.Kind.GLSL) {
            String s = src.text();
            if (s == null || s.trim().isEmpty()) return "empty GLSL source";
            if (!s.contains("void main(") && !s.contains("void main (")) {
                return "GLSL for stage " + src.stage() + " has no 'void main(' entry point";
            }
            return null;
        }
        byte[] b = src.spirv();
        if (b.length < 20) return "SPIR-V blob is " + b.length + " bytes, too short for a module header";
        int magic = ByteBuffer.wrap(b, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (magic != 0x07230203) {
            return String.format(java.util.Locale.ROOT,
                    "SPIR-V magic is 0x%08x, expected 0x07230203", magic);
        }
        return null;
    }

    @Override public GfxPipeline createGraphicsPipeline(GfxDesc.GraphicsPipeline desc) {
        String problem = null;
        boolean hasVertex = false, hasFragment = false;
        for (GfxShaderModule m : desc.stages) {
            if (!m.isValid()) { problem = "stage " + m.stage() + " failed to compile: " + m.infoLog(); break; }
            if (m.stage() == GfxStage.VERTEX) hasVertex = true;
            if (m.stage() == GfxStage.FRAGMENT) hasFragment = true;
            if (m.stage() == GfxStage.COMPUTE) { problem = "compute stage in a graphics pipeline"; break; }
            if ((m.stage() == GfxStage.TESSELLATION_CONTROL || m.stage() == GfxStage.TESSELLATION_EVALUATION)
                    && !hasCapability(GfxCapability.TESSELLATION)) {
                problem = "tessellation stage requested but the device lacks TESSELLATION";
                break;
            }
            if (m.stage() == GfxStage.GEOMETRY && !hasCapability(GfxCapability.GEOMETRY_SHADERS)) {
                problem = "geometry stage requested but the device lacks GEOMETRY_SHADERS";
                break;
            }
            if ((m.stage() == GfxStage.MESH || m.stage() == GfxStage.TASK)
                    && !hasCapability(GfxCapability.MESH_SHADERS)) {
                problem = "mesh/task stage requested but the device lacks MESH_SHADERS";
                break;
            }
        }
        if (problem == null && !hasVertex && !hasCapability(GfxCapability.MESH_SHADERS)) {
            problem = "graphics pipeline has no vertex stage";
        }
        if (problem == null && !hasFragment) problem = "graphics pipeline has no fragment stage";
        if (problem == null && desc.renderPass != null
                && desc.renderPass.colorCount() > limits.maxColorAttachments) {
            problem = "render pass uses " + desc.renderPass.colorCount()
                    + " colour attachments, limit is " + limits.maxColorAttachments;
        }
        if (problem == null && desc.layout != null
                && desc.layout.pushConstantBytes > limits.maxPushConstantSize) {
            problem = "push constants are " + desc.layout.pushConstantBytes
                    + " bytes, limit is " + limits.maxPushConstantSize;
        }
        NullPipeline p = new NullPipeline(newHandle(), false, problem == null,
                problem == null ? "" : problem, desc.layout, desc.debugName);
        pipelines.add(p);
        note("createGraphicsPipeline " + desc.debugName + (problem == null ? " ok" : " INVALID: " + problem));
        if (problem != null) lastError = problem;
        return p;
    }

    @Override public GfxPipeline createComputePipeline(GfxDesc.ComputePipeline desc) {
        String problem = null;
        if (desc.stage == null) problem = "compute pipeline has no stage";
        else if (desc.stage.stage() != GfxStage.COMPUTE) problem = "stage is " + desc.stage.stage() + ", not COMPUTE";
        else if (!desc.stage.isValid()) problem = "compute stage failed to compile: " + desc.stage.infoLog();
        if (problem == null && !hasCapability(GfxCapability.COMPUTE_SHADERS)) {
            problem = "device lacks COMPUTE_SHADERS";
        }
        if (problem == null && (long) desc.localSizeX * desc.localSizeY * desc.localSizeZ
                > limits.maxComputeWorkGroupInvocations) {
            problem = "local size " + desc.localSizeX + "x" + desc.localSizeY + "x" + desc.localSizeZ
                    + " = " + ((long) desc.localSizeX * desc.localSizeY * desc.localSizeZ)
                    + " invocations, exceeds maxComputeWorkGroupInvocations "
                    + limits.maxComputeWorkGroupInvocations;
        }
        NullPipeline p = new NullPipeline(newHandle(), true, problem == null,
                problem == null ? "" : problem, desc.layout, desc.debugName);
        pipelines.add(p);
        note("createComputePipeline " + desc.debugName + (problem == null ? " ok" : " INVALID: " + problem));
        if (problem != null) lastError = problem;
        return p;
    }

    @Override public GfxCommandBuffer createCommandBuffer(QueueType queue) {
        if (queue == QueueType.COMPUTE && !hasCapability(GfxCapability.ASYNC_COMPUTE)) {
            violate("compute queue requested but the device lacks ASYNC_COMPUTE");
        }
        RecordingCommandBuffer cb = new RecordingCommandBuffer(this, queue);
        commandBuffers.add(cb);
        note("createCommandBuffer " + queue);
        return cb;
    }

    @Override public byte[] readImage(GfxImage image) {
        violate("readImage: the null device has no pixels to hash (image '"
                + (image == null ? "?" : image.desc().debugName) + "')");
        return new byte[0];
    }

    @Override public void uploadBuffer(GfxBuffer dst, ByteBuffer src, long dstOffsetBytes) {
        long t = System.nanoTime();
        if (!(dst instanceof NullBuffer)) { violate("uploadBuffer to a foreign buffer"); return; }
        NullBuffer b = (NullBuffer) dst;
        int n = src.remaining();
        if (dstOffsetBytes + n > b.sizeBytes()) {
            violate("uploadBuffer of " + n + "B at offset " + dstOffsetBytes
                    + " overruns buffer '" + b.debugName + "' of " + b.sizeBytes() + "B");
            return;
        }
        if (b.shadow() != null) {
            int pos = src.position();
            src.duplicate().get(b.shadow(), (int) dstOffsetBytes, n);
            src.position(pos);
        }
        uploads++;
        uploadedBytes += n;
        note("uploadBuffer " + b.debugName + " +" + dstOffsetBytes + " " + n + "B");
        cpuNanos += System.nanoTime() - t;
    }

    @Override public void uploadBuffer(GfxBuffer dst, float[] src, long dstOffsetBytes) {
        ByteBuffer bb = ByteBuffer.allocateDirect(src.length * 4).order(ByteOrder.nativeOrder());
        for (float f : src) bb.putFloat(f);
        bb.flip();
        uploadBuffer(dst, bb, dstOffsetBytes);
    }

    @Override public void uploadBuffer(GfxBuffer dst, int[] src, long dstOffsetBytes) {
        ByteBuffer bb = ByteBuffer.allocateDirect(src.length * 4).order(ByteOrder.nativeOrder());
        for (int i : src) bb.putInt(i);
        bb.flip();
        uploadBuffer(dst, bb, dstOffsetBytes);
    }

    @Override public void uploadImage(GfxImage dst, int mipLevel, ByteBuffer src) {
        uploadImageRegion(dst, mipLevel, 0, 0, 0, dst.width(), dst.height(), dst.depth(), src);
    }

    @Override public void uploadImageRegion(GfxImage dst, int mipLevel, int x, int y, int z,
                                            int w, int h, int d, ByteBuffer src) {
        long t = System.nanoTime();
        if (!(dst instanceof NullImage)) { violate("uploadImage to a foreign image"); return; }
        NullImage img = (NullImage) dst;
        if (mipLevel >= img.mipLevels()) {
            violate("uploadImage mip " + mipLevel + " of " + img.mipLevels() + " on '" + img.debugName + "'");
            return;
        }
        if (x + w > img.width() || y + h > img.height() || z + d > img.depth()) {
            violate("uploadImage region " + x + "," + y + "," + z + " +" + w + "x" + h + "x" + d
                    + " exceeds '" + img.debugName + "' extent "
                    + img.width() + "x" + img.height() + "x" + img.depth());
            return;
        }
        long expected = (long) w * h * d * img.format().bytesPerTexel();
        if (expected > 0 && src.remaining() < expected) {
            violate("uploadImage needs " + expected + "B but the source holds " + src.remaining() + "B");
            return;
        }
        uploads++;
        uploadedBytes += expected;
        note("uploadImage " + img.debugName + " mip" + mipLevel + " " + x + "," + y + "," + z
                + " +" + w + "x" + h + "x" + d);
        cpuNanos += System.nanoTime() - t;
    }

    @Override public long beginFrame() { return ++frameId; }

    @Override public long[] frameTimestamps() { return new long[0]; }

    @Override public void submit(QueueType queue, GfxCommandBuffer cmd) {
        long t = System.nanoTime();
        if (cmd.isRecording()) violate("submit while '" + cmd + "' is still recording");
        submissions++;
        note("submit " + queue);
        cpuNanos += System.nanoTime() - t;
    }

    @Override public void submitAndWait(QueueType queue, GfxCommandBuffer cmd) { submit(queue, cmd); }

    @Override public void endFrame() { note("endFrame " + frameId); }

    @Override public void waitIdle() { waitIdleCalls++; note("waitIdle"); }

    @Override public int liveBufferCount() { return countLive(buffers); }
    @Override public int liveImageCount() { return countLive(images); }
    @Override public int livePipelineCount() { return countLive(pipelines); }
    @Override public int liveShaderModuleCount() { return countLive(modules); }

    /** True when this call added a violation, i.e. the resource was refused. */
    private boolean refused(int violationsBefore) { return violations.size() > violationsBefore; }

    private static int countLive(List<? extends BaseResource> l) {
        int n = 0;
        for (BaseResource r : l) if (!r.closed) n++;
        return n;
    }

    public int liveSamplerCount() { return countLive(samplers); }
    public int totalCreatedHandles() { return (int) (nextHandle - 1); }

    @Override public String lastError() { return lastError; }

    @Override public long cpuTimeMillis() { return cpuNanos / 1000000L; }

    @Override public void close() {
        for (NullPipeline p : pipelines) p.close();
        for (NullShaderModule m : modules) m.close();
        for (NullSampler s : samplers) s.close();
        for (NullImage i : images) i.close();
        for (NullBuffer b : buffers) b.close();
        note("device closed");
    }

    /** One line per counted fact; the test gates assert on this. */
    public String receipt() {
        StringBuilder sb = new StringBuilder();
        sb.append("null device: frames=").append(frameId)
          .append(" submissions=").append(submissions)
          .append(" uploads=").append(uploads).append(" (").append(uploadedBytes).append(" B)")
          .append(" cmdBuffers=").append(commandBuffers.size()).append('\n');
        sb.append("live: buffers=").append(liveBufferCount())
          .append(" images=").append(liveImageCount())
          .append(" samplers=").append(liveSamplerCount())
          .append(" pipelines=").append(livePipelineCount())
          .append(" modules=").append(liveShaderModuleCount()).append('\n');
        int cmds = 0;
        for (RecordingCommandBuffer cb : commandBuffers) cmds += cb.commandCount();
        sb.append("commands: ").append(cmds).append(" recorded, violations=").append(violations.size()).append('\n');
        for (String v : violations) sb.append("  VIOLATION: ").append(v).append('\n');
        return sb.toString();
    }

    /** Reads back a host-visible buffer, for the upload gates. */
    public byte[] readBack(GfxBuffer b) {
        if (!(b instanceof NullBuffer)) throw new GfxException("readBack on a foreign buffer");
        byte[] s = ((NullBuffer) b).shadow();
        return s == null ? null : s.clone();
    }

    static GfxResource check(GfxResource r, String what) {
        if (r == null) throw new GfxException(what + " is null");
        return r;
    }
}
