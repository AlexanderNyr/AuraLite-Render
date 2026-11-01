package dev.auralite.render.backend.gl;

import dev.auralite.render.rhi.DeviceLimits;
import dev.auralite.render.rhi.GfxBuffer;
import dev.auralite.render.rhi.GfxCapability;
import dev.auralite.render.rhi.GfxCommandBuffer;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxDevice;
import dev.auralite.render.rhi.GfxEnums.BackendType;
import dev.auralite.render.rhi.GfxEnums.ImageType;
import dev.auralite.render.rhi.GfxEnums.MemoryDomain;
import dev.auralite.render.rhi.GfxEnums.QueueType;
import dev.auralite.render.rhi.GfxException;
import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxImage;
import dev.auralite.render.rhi.GfxPipeline;
import dev.auralite.render.rhi.GfxSampler;
import dev.auralite.render.rhi.GfxShaderModule;
import dev.auralite.render.rhi.GfxStage;
import dev.auralite.render.rhi.GfxUsage;
import dev.auralite.render.rhi.ShaderSource;
import dev.auralite.render.util.Checks;
import dev.auralite.render.util.MathUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL46C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL46C.GL_COLOR;
import static org.lwjgl.opengl.GL46C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL46C.GL_COMPARE_REF_TO_TEXTURE;
import static org.lwjgl.opengl.GL46C.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL46C.GL_DEPTH_STENCIL;
import static org.lwjgl.opengl.GL46C.GL_DEPTH_ATTACHMENT;
import static org.lwjgl.opengl.GL46C.GL_DEPTH_STENCIL_ATTACHMENT;
import static org.lwjgl.opengl.GL46C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL46C.GL_DYNAMIC_STORAGE_BIT;
import static org.lwjgl.opengl.GL46C.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL46C.GL_MAP_READ_BIT;
import static org.lwjgl.opengl.GL46C.GL_MAP_WRITE_BIT;
import static org.lwjgl.opengl.GL46C.GL_MAX_3D_TEXTURE_SIZE;
import static org.lwjgl.opengl.GL46C.GL_MAX_ARRAY_TEXTURE_LAYERS;
import static org.lwjgl.opengl.GL46C.GL_MAX_COLOR_ATTACHMENTS;
import static org.lwjgl.opengl.GL46C.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE;
import static org.lwjgl.opengl.GL46C.GL_MAX_COMPUTE_WORK_GROUP_COUNT;
import static org.lwjgl.opengl.GL46C.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS;
import static org.lwjgl.opengl.GL46C.GL_MAX_COMPUTE_WORK_GROUP_SIZE;
import static org.lwjgl.opengl.GL46C.GL_MAX_FRAMEBUFFER_HEIGHT;
import static org.lwjgl.opengl.GL46C.GL_MAX_FRAMEBUFFER_WIDTH;
import static org.lwjgl.opengl.GL46C.GL_MAX_IMAGE_UNITS;
import static org.lwjgl.opengl.GL46C.GL_MAX_SAMPLES;
import static org.lwjgl.opengl.GL46C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE;
import static org.lwjgl.opengl.GL46C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS;
import static org.lwjgl.opengl.GL46C.GL_MAX_TEXTURE_IMAGE_UNITS;
import static org.lwjgl.opengl.GL46C.GL_MAX_TEXTURE_SIZE;
import static org.lwjgl.opengl.GL46C.GL_MAX_UNIFORM_BLOCK_SIZE;
import static org.lwjgl.opengl.GL46C.GL_MAX_UNIFORM_BUFFER_BINDINGS;
import static org.lwjgl.opengl.GL46C.GL_MAX_VERTEX_ATTRIBS;
import static org.lwjgl.opengl.GL46C.GL_MAX_VIEWPORT_DIMS;
import static org.lwjgl.opengl.GL46C.GL_MAX_VIEWPORTS;
import static org.lwjgl.opengl.GL46C.GL_NONE;
import static org.lwjgl.opengl.GL46C.GL_RENDERER;
import static org.lwjgl.opengl.GL46C.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT;
import static org.lwjgl.opengl.GL46C.GL_STENCIL_BUFFER_BIT;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_BORDER_COLOR;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_COMPARE_FUNC;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_COMPARE_MODE;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_MAX_ANISOTROPY;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_MAX_LOD;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_MIN_LOD;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_WRAP_R;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL46C.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT;
import static org.lwjgl.opengl.GL46C.GL_VERSION;
import static org.lwjgl.opengl.GL46C.glCheckNamedFramebufferStatus;
import static org.lwjgl.opengl.GL46C.glClearColor;
import static org.lwjgl.opengl.GL46C.glClearStencil;
import static org.lwjgl.opengl.GL46C.glCompressedTextureSubImage2D;
import static org.lwjgl.opengl.GL46C.glCompressedTextureSubImage3D;
import static org.lwjgl.opengl.GL46C.glCreateBuffers;
import static org.lwjgl.opengl.GL46C.glCreateFramebuffers;
import static org.lwjgl.opengl.GL46C.glCreateProgram;
import static org.lwjgl.opengl.GL46C.glCreateQueries;
import static org.lwjgl.opengl.GL46C.glCreateSamplers;
import static org.lwjgl.opengl.GL46C.glCreateShader;
import static org.lwjgl.opengl.GL46C.glCreateTextures;
import static org.lwjgl.opengl.GL46C.glDeleteBuffers;
import static org.lwjgl.opengl.GL46C.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL46C.glDeleteProgram;
import static org.lwjgl.opengl.GL46C.glDeleteQueries;
import static org.lwjgl.opengl.GL46C.glDeleteSamplers;
import static org.lwjgl.opengl.GL46C.glDeleteShader;
import static org.lwjgl.opengl.GL46C.glDeleteTextures;
import static org.lwjgl.opengl.GL46C.glFinish;
import static org.lwjgl.opengl.GL46C.glFlush;
import static org.lwjgl.opengl.GL46C.glGetFloat;
import static org.lwjgl.opengl.GL46C.glGetInteger;
import static org.lwjgl.opengl.GL46C.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL46C.glGetProgrami;
import static org.lwjgl.opengl.GL46C.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL46C.glGetShaderi;
import static org.lwjgl.opengl.GL46C.glGetString;
import static org.lwjgl.opengl.GL46C.glLinkProgram;
import static org.lwjgl.opengl.GL46C.glNamedBufferStorage;
import static org.lwjgl.opengl.GL46C.glNamedBufferSubData;
import static org.lwjgl.opengl.GL46C.glNamedFramebufferDrawBuffers;
import static org.lwjgl.opengl.GL46C.glNamedFramebufferTexture;
import static org.lwjgl.opengl.GL46C.glNamedFramebufferTextureLayer;
import static org.lwjgl.opengl.GL46C.glShaderSource;
import static org.lwjgl.opengl.GL46C.glTextureStorage1D;
import static org.lwjgl.opengl.GL46C.glTextureStorage2D;
import static org.lwjgl.opengl.GL46C.glTextureStorage2DMultisample;
import static org.lwjgl.opengl.GL46C.glTextureStorage3D;
import static org.lwjgl.opengl.GL46C.glTextureSubImage1D;
import static org.lwjgl.opengl.GL46C.glTextureSubImage2D;
import static org.lwjgl.opengl.GL46C.glTextureSubImage3D;

/**
 * The OpenGL 4.6 backend. Direct-state-access throughout, no bind-to-edit.
 *
 * <p>Requires a current GL 4.6 context on the calling thread; construction
 * fails loudly otherwise. GL executes commands immediately, so the command
 * buffer runs each call as it arrives and {@code submit} is the flush point.
 * Usage flags are enforced exactly like the NULL backend enforces them, so a
 * pack that runs here runs on Vulkan too.
 */
public final class GlDevice implements GfxDevice {

    private final Set<GfxCapability> capabilities;
    private final DeviceLimits limits = new DeviceLimits();
    private final String renderer;
    private final String version;
    private final int maxSamples;
    private final int maxAnisotropy;
    private final int pushBinding;
    private final boolean spirvSupported;

    private final Set<GlBuffer> liveBuffers = new HashSet<GlBuffer>();
    private final Set<GlImage> liveImages = new HashSet<GlImage>();
    private final Set<GlSampler> liveSamplers = new HashSet<GlSampler>();
    private final Set<GlPipeline> livePipelines = new HashSet<GlPipeline>();
    private final Set<GlShaderModule> liveModules = new HashSet<GlShaderModule>();
    private final List<GlCommandBuffer> commandBuffers = new ArrayList<GlCommandBuffer>();

    private final Map<Long, Integer> framebuffers = new HashMap<Long, Integer>();
    private final int pushUbo;
    private final int[] timestampRing = new int[64];
    private int timestampCursor;
    private final java.util.List<Integer> frameTimestampQueries = new java.util.ArrayList<Integer>();
    private final List<Integer> scratchQueries = new ArrayList<Integer>();

    private long frameId;
    private long totalCreated;
    private String lastError = "";
    private final long createdMillis = System.currentTimeMillis();
    private boolean closed;

    public GlDevice() {
        GLCapabilities caps;
        try {
            caps = GL.getCapabilities();
        } catch (IllegalStateException e) {
            throw new GfxException("the GL backend needs a current OpenGL context on this thread", e);
        }
        if (caps == null || !caps.OpenGL46) {
            throw new GfxException("the GL backend needs OpenGL 4.6; this context is older or missing");
        }
        renderer = glGetString(GL_RENDERER);
        version = glGetString(GL_VERSION);
        spirvSupported = caps.GL_ARB_gl_spirv;

        EnumSet<GfxCapability> c = EnumSet.of(
                GfxCapability.COMPUTE_SHADERS,
                GfxCapability.TEXTURE_3D,
                GfxCapability.INTEGER_TEXTURES,
                GfxCapability.STORAGE_BUFFERS,
                GfxCapability.FRAGMENT_STORAGE_IMAGES,
                GfxCapability.INDIRECT_DRAW,
                GfxCapability.MULTI_DRAW_INDIRECT,
                GfxCapability.INDIRECT_DISPATCH,
                GfxCapability.GEOMETRY_SHADERS,
                GfxCapability.TESSELLATION,
                GfxCapability.TIMESTAMPS,
                GfxCapability.DERIVATIVE_CONTROL);
        if (caps.GL_ARB_bindless_texture) c.add(GfxCapability.BINDLESS_TEXTURES);
        capabilities = java.util.Collections.unmodifiableSet(c);

        // Limits are queried, not assumed: a pack that fits an RTX card must be
        // refused on Intel UHD at creation time, not halfway through a frame.
        limits.maxTexture2DSize = glGetInteger(GL_MAX_TEXTURE_SIZE);
        limits.maxTexture3DSize = glGetInteger(GL_MAX_3D_TEXTURE_SIZE);
        limits.maxTextureLayers = glGetInteger(GL_MAX_ARRAY_TEXTURE_LAYERS);
        limits.maxColorAttachments = Math.min(8, glGetInteger(GL_MAX_COLOR_ATTACHMENTS));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer dims = stack.mallocInt(2);
            org.lwjgl.opengl.GL46C.glGetIntegerv(GL_MAX_VIEWPORT_DIMS, dims);
            limits.maxViewportWidth = dims.get(0);
            IntBuffer counts = stack.mallocInt(3);
            org.lwjgl.opengl.GL46C.glGetIntegerv(GL_MAX_COMPUTE_WORK_GROUP_COUNT, counts);
            limits.maxComputeWorkGroupCountX = counts.get(0);
            limits.maxComputeWorkGroupCountY = counts.get(1);
            limits.maxComputeWorkGroupCountZ = counts.get(2);
            IntBuffer sizes = stack.mallocInt(3);
            org.lwjgl.opengl.GL46C.glGetIntegerv(GL_MAX_COMPUTE_WORK_GROUP_SIZE, sizes);
            limits.maxComputeWorkGroupSizeX = sizes.get(0);
            limits.maxComputeWorkGroupSizeY = sizes.get(1);
            limits.maxComputeWorkGroupSizeZ = sizes.get(2);
        }
        limits.maxFramebufferWidth = glGetInteger(GL_MAX_FRAMEBUFFER_WIDTH);
        limits.maxFramebufferHeight = glGetInteger(GL_MAX_FRAMEBUFFER_HEIGHT);
        limits.maxUniformBufferRange = glGetInteger(GL_MAX_UNIFORM_BLOCK_SIZE);
        limits.maxStorageBufferRange = glGetInteger(GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        limits.maxPushConstantSize = 128;
        limits.maxBoundDescriptorSets = 4;
        limits.maxPerStageSamplers = glGetInteger(GL_MAX_TEXTURE_IMAGE_UNITS);
        limits.maxPerStageStorageImages = glGetInteger(GL_MAX_IMAGE_UNITS);
        limits.maxPerStageStorageBuffers = glGetInteger(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS);
        limits.maxVertexAttributes = glGetInteger(GL_MAX_VERTEX_ATTRIBS);
        limits.maxViewports = glGetInteger(GL_MAX_VIEWPORTS);
        limits.maxComputeWorkGroupInvocations = glGetInteger(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS);
        limits.maxComputeSharedMemorySize = glGetInteger(GL_MAX_COMPUTE_SHARED_MEMORY_SIZE);
        maxSamples = glGetInteger(GL_MAX_SAMPLES);
        maxAnisotropy = caps.GL_EXT_texture_filter_anisotropic
                ? (int) glGetFloat(GL_TEXTURE_MAX_ANISOTROPY) : 1;
        limits.maxAnisotropy = maxAnisotropy;
        limits.minUniformBufferOffsetAlignment = glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT);
        limits.minStorageBufferOffsetAlignment = glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
        // GL mappings are coherent; the field exists for the Vulkan lane.
        limits.nonCoherentAtomSize = 1;

        pushBinding = glGetInteger(GL_MAX_UNIFORM_BUFFER_BINDINGS) - 1;
        pushUbo = glCreateBuffers();
        glNamedBufferStorage(pushUbo, 128, GL_DYNAMIC_STORAGE_BIT);

        for (int i = 0; i < timestampRing.length; i++) {
            timestampRing[i] = glCreateQueries(org.lwjgl.opengl.GL46C.GL_TIMESTAMP);
        }
    }

    @Override public BackendType backendType() { return BackendType.OPENGL_46; }
    @Override public String backendName() { return "AuraLite-Render GL46 (direct state access)"; }
    @Override public String deviceName() { return renderer; }
    @Override public String driverVersion() { return version; }
    @Override public DeviceLimits limits() { return limits; }
    @Override public Set<GfxCapability> capabilities() { return capabilities; }
    @Override public boolean hasCapability(GfxCapability c) { return capabilities.contains(c); }

    // ------------------------------------------------------------ creation ---

    @Override public GfxBuffer createBuffer(GfxDesc.Buffer desc) {
        Checks.notNull(desc, "desc");
        if (desc.sizeBytes <= 0) return refuse("buffer '" + desc.debugName + "' has size " + desc.sizeBytes);
        GlBuffer b = new GlBuffer(this, desc);
        liveBuffers.add(b);
        totalCreated++;
        return b;
    }

    @Override public GfxImage createImage(GfxDesc.Image desc) {
        Checks.notNull(desc, "desc");
        GlFormatTable.Mapping m = GlFormatTable.of(desc.format);
        if (!m.supported) {
            return refuse("image '" + desc.debugName + "' uses " + desc.format
                    + ", which core OpenGL cannot store (no sized sRGB format with that channel count)");
        }
        if (desc.width <= 0 || desc.height <= 0 || desc.depth <= 0) {
            return refuse("image '" + desc.debugName + "' has extent "
                    + desc.width + "x" + desc.height + "x" + desc.depth);
        }
        if (desc.samples > maxSamples) {
            return refuse("image '" + desc.debugName + "' asks for " + desc.samples
                    + "x MSAA but this device tops out at " + maxSamples + "x");
        }
        if (desc.samples > 1 && desc.mipLevels > 1) {
            return refuse("image '" + desc.debugName + "' is both multisampled and mipmapped");
        }
        if (desc.type == ImageType.TEXTURE_3D && desc.samples > 1) {
            return refuse("image '" + desc.debugName + "': OpenGL has no multisampled 3D textures");
        }
        if (desc.type == ImageType.CUBE && (desc.width != desc.height || desc.arrayLayers % 6 != 0)) {
            return refuse("image '" + desc.debugName + "': a cube needs a square face and 6 layers per cube");
        }
        GlImage img = new GlImage(this, desc, m);
        liveImages.add(img);
        totalCreated++;
        return img;
    }

    @Override public GfxSampler createSampler(GfxDesc.Sampler desc) {
        Checks.notNull(desc, "desc");
        GlSampler s = new GlSampler(this, desc);
        liveSamplers.add(s);
        totalCreated++;
        return s;
    }

    @Override public GfxShaderModule createShaderModule(ShaderSource source) {
        Checks.notNull(source, "source");
        if (!GlEnumMap.stageSupported(source.stage())) {
            return refuse("the GL backend cannot express a " + source.stage()
                    + " stage; that program needs the Vulkan lane");
        }
        GlShaderModule m = new GlShaderModule(this, source);
        liveModules.add(m);
        totalCreated++;
        return m;
    }

    @Override public GfxPipeline createGraphicsPipeline(GfxDesc.GraphicsPipeline desc) {
        Checks.notNull(desc, "desc");
        for (GfxShaderModule s : desc.stages) {
            GlShaderModule m = ownModule(s);
            if (!m.isValid()) {
                return refuse("pipeline '" + desc.debugName + "' uses the failed " + m.stage()
                        + " module from " + m.origin + ": " + m.infoLog());
            }
        }
        if (!desc.hasStage(GfxStage.VERTEX) || !desc.hasStage(GfxStage.FRAGMENT)) {
            return refuse("pipeline '" + desc.debugName + "' needs a vertex and a fragment stage on GL");
        }
        GlPipeline p = new GlPipeline(this, desc, false);
        if (!p.valid) return refuse(p.infoLog);
        livePipelines.add(p);
        totalCreated++;
        return p;
    }

    @Override public GfxPipeline createComputePipeline(GfxDesc.ComputePipeline desc) {
        Checks.notNull(desc, "desc");
        GlShaderModule m = ownModule(desc.stage);
        if (m.stage() != GfxStage.COMPUTE) {
            return refuse("compute pipeline '" + desc.debugName + "' is bound to a " + m.stage() + " module");
        }
        if (!m.isValid()) {
            return refuse("compute pipeline '" + desc.debugName + "' uses the failed module from "
                    + m.origin + ": " + m.infoLog());
        }
        if ((long) desc.localSizeX * desc.localSizeY * desc.localSizeZ > limits.maxComputeWorkGroupInvocations) {
            return refuse("compute pipeline '" + desc.debugName + "' wants "
                    + (desc.localSizeX * desc.localSizeY * desc.localSizeZ)
                    + " invocations, this device allows " + limits.maxComputeWorkGroupInvocations);
        }
        GlPipeline p = new GlPipeline(this, desc);
        if (!p.valid) return refuse(p.infoLog);
        livePipelines.add(p);
        totalCreated++;
        return p;
    }

    @Override public GfxCommandBuffer createCommandBuffer(QueueType queue) {
        Checks.notNull(queue, "queue");
        // GL has one queue per context; COMPUTE and TRANSFER requests run there too.
        GlCommandBuffer cb = new GlCommandBuffer(this, queue);
        commandBuffers.add(cb);
        return cb;
    }

    private <T> T refuse(String message) {
        lastError = message;
        throw new GfxException("gl backend: " + message);
    }

    GlShaderModule ownModule(GfxShaderModule s) {
        if (!(s instanceof GlShaderModule) || ((GlShaderModule) s).device != this) {
            throw new GfxException("gl backend: a shader module from another device was submitted here");
        }
        return (GlShaderModule) s;
    }

    GlBuffer ownBuffer(GfxBuffer b) {
        if (!(b instanceof GlBuffer) || ((GlBuffer) b).device != this) {
            throw new GfxException("gl backend: a buffer from another device was submitted here");
        }
        return (GlBuffer) b;
    }

    GlImage ownImage(GfxImage i) {
        if (!(i instanceof GlImage) || ((GlImage) i).device != this) {
            throw new GfxException("gl backend: an image from another device was submitted here");
        }
        return (GlImage) i;
    }

    // ------------------------------------------------------------- uploads ---

    @Override public byte[] readImage(GfxImage image) {
        if (!(image instanceof GlImage)) {
            throw new GfxException("gl backend: readImage of a foreign image ("
                    + image.getClass().getName() + ")");
        }
        if (((GlImage) image).name == 0) {
            throw new GfxException("gl backend: readImage of the window-system framebuffer"
                    + " (handle 0) - a surfaceless default framebuffer has no storage to"
                    + " hash; bind an offscreen image instead");
        }
        GfxFormat f = image.format();
        if (f != GfxFormat.RGBA8_UNORM && f != GfxFormat.RGBA8_SRGB) {
            throw new GfxException("gl backend: readImage supports the RGBA8 family only, got "
                    + f + " for image '" + image.desc().debugName + "'");
        }
        int w = image.width(), h = image.height();
        int bytes = w * h * 4;
        java.nio.ByteBuffer buf = org.lwjgl.BufferUtils.createByteBuffer(bytes);
        org.lwjgl.opengl.GL46C.glGetTextureSubImage(((GlImage) image).name, 0, 0, 0, 0,
                w, h, 1, org.lwjgl.opengl.GL46C.GL_RGBA,
                org.lwjgl.opengl.GL46C.GL_UNSIGNED_BYTE, buf);
        byte[] out = new byte[bytes];
        buf.get(out);
        return out;
    }

    @Override public void uploadBuffer(GfxBuffer dst, ByteBuffer src, long dstOffsetBytes) {
        GlBuffer b = ownBuffer(dst);
        Checks.notNull(src, "src");
        b.checkRange(dstOffsetBytes, src.remaining(), "upload");
        glNamedBufferSubData(b.name, dstOffsetBytes, src);
    }

    @Override public void uploadBuffer(GfxBuffer dst, float[] src, long dstOffsetBytes) {
        Checks.notNull(src, "src");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bb = stack.malloc(src.length * 4);
            bb.asFloatBuffer().put(src);
            uploadBuffer(dst, bb, dstOffsetBytes);
        }
    }

    @Override public void uploadBuffer(GfxBuffer dst, int[] src, long dstOffsetBytes) {
        Checks.notNull(src, "src");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bb = stack.malloc(src.length * 4);
            bb.asIntBuffer().put(src);
            uploadBuffer(dst, bb, dstOffsetBytes);
        }
    }

    @Override public void uploadImage(GfxImage dst, int mipLevel, ByteBuffer src) {
        GlImage img = ownImage(dst);
        img.checkLevel(mipLevel);
        Checks.notNull(src, "src");
        int w = MathUtil.mipExtent(img.desc.width, mipLevel);
        int h = MathUtil.mipExtent(img.desc.height, mipLevel);
        int d = img.desc.type == ImageType.TEXTURE_3D ? MathUtil.mipExtent(img.desc.depth, mipLevel) : 1;
        img.subImage(mipLevel, 0, 0, 0, w, h, d, src);
    }

    @Override public void uploadImageRegion(GfxImage dst, int mipLevel, int x, int y, int z,
                                            int w, int h, int d, ByteBuffer src) {
        GlImage img = ownImage(dst);
        img.checkLevel(mipLevel);
        Checks.notNull(src, "src");
        img.subImage(mipLevel, x, y, z, w, h, d, src);
    }

    // --------------------------------------------------------------- frame ---

    @Override public long beginFrame() {
        frameTimestampQueries.clear();
        return ++frameId;
    }

    @Override public void submit(QueueType queue, GfxCommandBuffer cmd) {
        Checks.notNull(cmd, "cmd");
        if (!(cmd instanceof GlCommandBuffer) || ((GlCommandBuffer) cmd).device != this) {
            throw new GfxException("gl backend: a foreign command buffer was submitted here");
        }
        // GL ran every command as it was recorded; the submit is the flush point.
        glFlush();
    }

    @Override public void submitAndWait(QueueType queue, GfxCommandBuffer cmd) {
        submit(queue, cmd);
        glFinish();
    }

    @Override public void endFrame() { }

    @Override public void waitIdle() { glFinish(); }

    // --------------------------------------------------------- diagnostics ---

    @Override public int liveBufferCount() { return liveBuffers.size(); }
    @Override public int liveImageCount() { return liveImages.size(); }
    @Override public int livePipelineCount() { return livePipelines.size(); }
    @Override public int liveShaderModuleCount() { return liveModules.size(); }
    @Override public String lastError() { return lastError; }
    @Override public long cpuTimeMillis() { return System.currentTimeMillis() - createdMillis; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (GlCommandBuffer cb : new ArrayList<GlCommandBuffer>(commandBuffers)) cb.dispose();
        commandBuffers.clear();
        for (GlPipeline p : new ArrayList<GlPipeline>(livePipelines)) p.close();
        for (GlShaderModule m : new ArrayList<GlShaderModule>(liveModules)) m.close();
        for (GlSampler s : new ArrayList<GlSampler>(liveSamplers)) s.close();
        for (GlImage i : new ArrayList<GlImage>(liveImages)) i.close();
        for (GlBuffer b : new ArrayList<GlBuffer>(liveBuffers)) b.close();
        for (int fbo : framebuffers.values()) glDeleteFramebuffers(fbo);
        framebuffers.clear();
        glDeleteBuffers(pushUbo);
        for (int q : timestampRing) glDeleteQueries(q);
        for (int q : scratchQueries) glDeleteQueries(q);
        scratchQueries.clear();
    }

    // -------------------------------------------------- framebuffer cache ---

    /**
     * The window-system backbuffer as an RHI image. Handle 0 means the default
     * framebuffer; a pass may not mix it with offscreen attachments.
     */
    /** Texture object name of a device image (fabric present blit reads it). */
    public int textureName(GfxImage image) {
        if (!(image instanceof GlImage)) {
            throw new GfxException("textureName: not a GL image of this device");
        }
        return ((GlImage) image).name;
    }

    public GfxImage defaultFramebuffer(int width, int height) {
        GfxDesc.Image desc = GfxDesc.Image.tex2d(width, height, GfxFormat.RGBA8_UNORM,
                GfxUsage.IMAGE_COLOR_ATTACHMENT | GfxUsage.IMAGE_PRESENT).named("window");
        return new GlImage(this, desc, null, 0);
    }

    int framebufferFor(GfxDesc.RenderPass pass) {
        boolean anyDefault = false, anyOffscreen = false;
        for (GfxDesc.Attachment a : pass.colors) {
            if (ownImage(a.image).name == 0) anyDefault = true; else anyOffscreen = true;
        }
        if (pass.depth != null) {
            if (ownImage(pass.depth.image).name == 0) anyDefault = true; else anyOffscreen = true;
        }
        if (anyDefault && anyOffscreen) {
            throw new GfxException("gl backend: pass '" + pass.debugName
                    + "' mixes the window backbuffer with offscreen attachments");
        }
        if (anyDefault) return 0;
        long key = framebufferKey(pass);
        Integer fbo = framebuffers.get(key);
        if (fbo != null) return fbo;
        int created = buildFramebuffer(pass);
        framebuffers.put(key, created);
        return created;
    }

    private long framebufferKey(GfxDesc.RenderPass pass) {
        long h = 1469598103934665603L;
        h = mix(h, pass.width); h = mix(h, pass.height);
        for (GfxDesc.Attachment a : pass.colors) {
            GlImage img = ownImage(a.image);
            h = mix(h, img.name); h = mix(h, a.mipLevel); h = mix(h, a.baseLayer); h = mix(h, a.layerCount);
        }
        if (pass.depth != null) {
            h = mix(h, ownImage(pass.depth.image).name);
            h = mix(h, pass.depth.mipLevel); h = mix(h, pass.depth.baseLayer);
        }
        return h;
    }

    private static long mix(long h, long v) { return (h ^ v) * 1099511628211L; }

    private int buildFramebuffer(GfxDesc.RenderPass pass) {
        int fbo = glCreateFramebuffers();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer draw = stack.mallocInt(Math.max(1, pass.colors.size()));
            for (int i = 0; i < pass.colors.size(); i++) {
                GfxDesc.Attachment a = pass.colors.get(i);
                GlImage img = ownImage(a.image);
                attach(fbo, GL_COLOR_ATTACHMENT0 + i, img, a);
                draw.put(i, GL_COLOR_ATTACHMENT0 + i);
            }
            if (pass.colors.isEmpty()) {
                draw.put(0, GL_NONE);
            }
            glNamedFramebufferDrawBuffers(fbo, draw);
        }
        if (pass.depth != null) {
            GlImage img = ownImage(pass.depth.image);
            int point = img.desc.format.isStencil() ? GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT;
            attach(fbo, point, img, pass.depth);
        }
        int status = glCheckNamedFramebufferStatus(fbo, GL_DRAW_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            glDeleteFramebuffers(fbo);
            throw new GfxException("gl backend: pass '" + pass.debugName
                    + "' is not framebuffer-complete: 0x" + Integer.toHexString(status));
        }
        return fbo;
    }

    private static void attach(int fbo, int point, GlImage img, GfxDesc.Attachment a) {
        boolean layered = img.desc.type == ImageType.TEXTURE_3D
                || img.desc.type == ImageType.CUBE
                || img.desc.arrayLayers > 1
                || a.layerCount > 1;
        if (layered) {
            glNamedFramebufferTextureLayer(fbo, point, img.name, a.mipLevel, a.baseLayer);
        } else {
            glNamedFramebufferTexture(fbo, point, img.name, a.mipLevel);
        }
    }

    void clearFramebuffer(int fbo, GfxDesc.RenderPass pass) {
        if (fbo == 0) {
            int mask = 0;
            for (GfxDesc.Attachment a : pass.colors) {
                if (a.loadOp != dev.auralite.render.rhi.GfxEnums.LoadOp.CLEAR) continue;
                glClearColor(a.clearColor[0], a.clearColor[1], a.clearColor[2], a.clearColor[3]);
                mask |= GL_COLOR_BUFFER_BIT;
            }
            if (pass.depth != null
                    && pass.depth.loadOp == dev.auralite.render.rhi.GfxEnums.LoadOp.CLEAR) {
                org.lwjgl.opengl.GL46C.glClearDepth(pass.depth.clearDepth);
                glClearStencil(pass.depth.clearStencil);
                mask |= GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT;
            }
            if (mask != 0) org.lwjgl.opengl.GL46C.glClear(mask);
            return;
        }
        for (int i = 0; i < pass.colors.size(); i++) {
            GfxDesc.Attachment a = pass.colors.get(i);
            if (a.loadOp != dev.auralite.render.rhi.GfxEnums.LoadOp.CLEAR) continue;
            GlImage img = ownImage(a.image);
            if (img.desc.format.isInteger()) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer v = stack.mallocInt(4);
                    v.put(0, (int) a.clearColor[0]).put(1, (int) a.clearColor[1])
                     .put(2, (int) a.clearColor[2]).put(3, (int) a.clearColor[3]);
                    org.lwjgl.opengl.GL46C.glClearNamedFramebufferiv(fbo, GL_COLOR, i, v);
                }
            } else {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    FloatBuffer v = stack.mallocFloat(4);
                    v.put(0, a.clearColor[0]).put(1, a.clearColor[1])
                     .put(2, a.clearColor[2]).put(3, a.clearColor[3]);
                    org.lwjgl.opengl.GL46C.glClearNamedFramebufferfv(fbo, GL_COLOR, i, v);
                }
            }
        }
        if (pass.depth != null && pass.depth.loadOp == dev.auralite.render.rhi.GfxEnums.LoadOp.CLEAR) {
            org.lwjgl.opengl.GL46C.glClearNamedFramebufferfi(fbo, GL_DEPTH_STENCIL, 0,
                    pass.depth.clearDepth, pass.depth.clearStencil);
        }
    }

    int nextTimestampQuery() {
        int q = timestampRing[timestampCursor];
        timestampCursor = (timestampCursor + 1) % timestampRing.length;
        frameTimestampQueries.add(q);
        return q;
    }

    @Override public long[] frameTimestamps() {
        long[] ts = new long[frameTimestampQueries.size()];
        java.nio.LongBuffer one = org.lwjgl.BufferUtils.createLongBuffer(1);
        for (int i = 0; i < ts.length; i++) {
            one.clear();
            org.lwjgl.opengl.GL46C.glGetQueryObjectui64v(
                    frameTimestampQueries.get(i).intValue(),
                    org.lwjgl.opengl.GL46C.GL_QUERY_RESULT, one);
            ts[i] = one.get(0);
        }
        return ts;
    }

    int pushUbo() { return pushUbo; }
    int pushBinding() { return pushBinding; }
    long totalCreatedHandles() { return totalCreated; }

    // --------------------------------------------------------------- buffer ---

    static final class GlBuffer implements GfxBuffer {
        final GlDevice device;
        final GfxDesc.Buffer desc;
        final int name;
        private boolean closed;

        GlBuffer(GlDevice device, GfxDesc.Buffer desc) {
            this.device = device;
            this.desc = desc;
            this.name = glCreateBuffers();
            // Host-visible buffers stay mappable; device-local ones are still
            // updatable through glNamedBufferSubData, which is all the RHI needs.
            int flags = GL_DYNAMIC_STORAGE_BIT;
            if (desc.domain != MemoryDomain.DEVICE_LOCAL) flags |= GL_MAP_READ_BIT | GL_MAP_WRITE_BIT;
            glNamedBufferStorage(name, desc.sizeBytes, flags);
        }

        @Override public long handle() { return name; }
        @Override public String debugName() { return desc.debugName; }
        @Override public long sizeBytes() { return desc.sizeBytes; }
        @Override public int usage() { return desc.usage; }
        @Override public MemoryDomain domain() { return desc.domain; }
        @Override public boolean hostVisible() { return desc.domain != MemoryDomain.DEVICE_LOCAL; }

        void checkRange(long offset, long length, String what) {
            if (offset < 0 || length < 0 || offset + length > desc.sizeBytes) {
                throw new GfxException("gl backend: " + what + " on buffer '" + desc.debugName
                        + "' runs [" + offset + ".." + (offset + length) + ") past its "
                        + desc.sizeBytes + " bytes");
            }
        }

        void requireUsage(int flag, String flagName, String what) {
            if ((desc.usage & flag) == 0) {
                throw new GfxException("gl backend: " + what + " needs " + flagName
                        + " on buffer '" + desc.debugName + "' (" + GfxUsage.describe(desc.usage) + ")");
            }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            device.liveBuffers.remove(this);
            glDeleteBuffers(name);
        }
    }

    // ---------------------------------------------------------------- image ---

    static final class GlImage implements GfxImage {
        final GlDevice device;
        final GfxDesc.Image desc;
        final GlFormatTable.Mapping mapping;
        final int name;
        final int target;
        private boolean closed;

        GlImage(GlDevice device, GfxDesc.Image desc, GlFormatTable.Mapping mapping) {
            this.device = device;
            this.desc = desc;
            this.mapping = mapping;
            this.target = GlEnumMap.textureTarget(desc.type, desc.samples, desc.arrayLayers);
            this.name = glCreateTextures(target);
            allocate();
        }

        /** Handle 0 is the window-system default framebuffer; storage is theirs. */
        GlImage(GlDevice device, GfxDesc.Image desc, GlFormatTable.Mapping mapping, int name) {
            this.device = device;
            this.desc = desc;
            this.mapping = mapping;
            this.target = GlEnumMap.textureTarget(desc.type, desc.samples);
            this.name = name;
        }

        private void allocate() {
            int levels = mapping.compressed ? 1 : desc.mipLevels;
            switch (desc.type) {
                case TEXTURE_1D:
                    glTextureStorage1D(name, levels, mapping.internalFormat, desc.width);
                    break;
                case TEXTURE_2D:
                    if (desc.samples > 1) {
                        glTextureStorage2DMultisample(name, desc.samples, mapping.internalFormat,
                                desc.width, desc.height, true);
                    } else if (desc.arrayLayers > 1) {
                        glTextureStorage3D(name, levels, mapping.internalFormat,
                                desc.width, desc.height, desc.arrayLayers);
                    } else {
                        glTextureStorage2D(name, levels, mapping.internalFormat, desc.width, desc.height);
                    }
                    break;
                case CUBE:
                    if (desc.arrayLayers > 6) {
                        glTextureStorage3D(name, levels, mapping.internalFormat,
                                desc.width, desc.height, desc.arrayLayers / 6 * 6);
                    } else {
                        glTextureStorage2D(name, levels, mapping.internalFormat, desc.width, desc.height);
                    }
                    break;
                case TEXTURE_3D:
                    glTextureStorage3D(name, levels, mapping.internalFormat,
                            desc.width, desc.height, desc.depth);
                    break;
                default:
                    throw new GfxException("gl backend: cannot store image type " + desc.type);
            }
        }

        @Override public long handle() { return name; }
        @Override public String debugName() { return desc.debugName; }
        @Override public long sizeBytes() { return desc.approxBytes(); }
        @Override public GfxDesc.Image desc() { return desc; }
        @Override public GfxFormat format() { return desc.format; }
        @Override public int width() { return desc.width; }
        @Override public int height() { return desc.height; }
        @Override public int depth() { return desc.depth; }
        @Override public int mipLevels() { return desc.mipLevels; }
        @Override public int arrayLayers() { return desc.arrayLayers; }
        @Override public int samples() { return desc.samples; }
        @Override public ImageType type() { return desc.type; }

        void checkLevel(int level) {
            if (level < 0 || level >= desc.mipLevels) {
                throw new GfxException("gl backend: mip level " + level + " on image '"
                        + desc.debugName + "', which has " + desc.mipLevels);
            }
        }

        void requireUsage(int flag, String flagName, String what) {
            if ((desc.usage & flag) == 0) {
                throw new GfxException("gl backend: " + what + " needs " + flagName
                        + " on image '" + desc.debugName + "' (" + GfxUsage.describe(desc.usage) + ")");
            }
        }

        void subImage(int level, int x, int y, int z, int w, int h, int d, ByteBuffer src) {
            if (mapping.compressed) {
                switch (desc.type) {
                    case TEXTURE_2D:
                    case CUBE:
                        glCompressedTextureSubImage2D(name, level, x, y, w, h,
                                mapping.internalFormat, src);
                        return;
                    case TEXTURE_3D:
                        glCompressedTextureSubImage3D(name, level, x, y, z, w, h, d,
                                mapping.internalFormat, src);
                        return;
                    default: break;
                }
                throw new GfxException("gl backend: cannot upload into compressed 1D image '"
                        + desc.debugName + "'");
            }
            switch (desc.type) {
                case TEXTURE_1D:
                    glTextureSubImage1D(name, level, x, w, mapping.format, mapping.type, src);
                    break;
                case TEXTURE_2D:
                case CUBE:
                    glTextureSubImage2D(name, level, x, y, w, h, mapping.format, mapping.type, src);
                    break;
                case TEXTURE_3D:
                    glTextureSubImage3D(name, level, x, y, z, w, h, d, mapping.format, mapping.type, src);
                    break;
                default:
                    throw new GfxException("gl backend: cannot upload into image type " + desc.type);
            }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            device.liveImages.remove(this);
            if (name != 0) glDeleteTextures(name);
        }
    }

    // -------------------------------------------------------------- sampler ---

    static final class GlSampler implements GfxSampler {
        final GlDevice device;
        final GfxDesc.Sampler desc;
        final int name;
        private boolean closed;

        GlSampler(GlDevice device, GfxDesc.Sampler desc) {
            this.device = device;
            this.desc = desc;
            this.name = glCreateSamplers();
            boolean mipmapped = desc.maxLod > desc.minLod;
            org.lwjgl.opengl.GL46C.glSamplerParameteri(name, GL_TEXTURE_MIN_FILTER,
                    GlEnumMap.minFilter(desc.minFilter, desc.mipmapMode, mipmapped));
            org.lwjgl.opengl.GL46C.glSamplerParameteri(name, GL_TEXTURE_MAG_FILTER,
                    GlEnumMap.magFilter(desc.magFilter));
            org.lwjgl.opengl.GL46C.glSamplerParameteri(name, GL_TEXTURE_WRAP_S,
                    GlEnumMap.addressMode(desc.addressU));
            org.lwjgl.opengl.GL46C.glSamplerParameteri(name, GL_TEXTURE_WRAP_T,
                    GlEnumMap.addressMode(desc.addressV));
            org.lwjgl.opengl.GL46C.glSamplerParameteri(name, GL_TEXTURE_WRAP_R,
                    GlEnumMap.addressMode(desc.addressW));
            org.lwjgl.opengl.GL46C.glSamplerParameterf(name, GL_TEXTURE_MIN_LOD, desc.minLod);
            org.lwjgl.opengl.GL46C.glSamplerParameterf(name, GL_TEXTURE_MAX_LOD, desc.maxLod);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer border = stack.mallocFloat(4);
                border.put(0, desc.borderColor[0]).put(1, desc.borderColor[1])
                      .put(2, desc.borderColor[2]).put(3, desc.borderColor[3]);
                org.lwjgl.opengl.GL46C.glSamplerParameterfv(name, GL_TEXTURE_BORDER_COLOR, border);
            }
            if (desc.compareEnable) {
                org.lwjgl.opengl.GL46C.glSamplerParameteri(name, GL_TEXTURE_COMPARE_MODE,
                        GL_COMPARE_REF_TO_TEXTURE);
                org.lwjgl.opengl.GL46C.glSamplerParameteri(name, GL_TEXTURE_COMPARE_FUNC,
                        GlEnumMap.compareOp(desc.compareOp));
            }
            if (desc.maxAnisotropy > 1.0f && device.maxAnisotropy > 1) {
                org.lwjgl.opengl.GL46C.glSamplerParameterf(name, GL_TEXTURE_MAX_ANISOTROPY,
                        Math.min(desc.maxAnisotropy, device.maxAnisotropy));
            }
        }

        @Override public long handle() { return name; }
        @Override public String debugName() { return desc.debugName; }
        @Override public long sizeBytes() { return 0; }
        @Override public GfxDesc.Sampler desc() { return desc; }

        @Override public void close() {
            if (closed) return;
            closed = true;
            device.liveSamplers.remove(this);
            glDeleteSamplers(name);
        }
    }

    // ---------------------------------------------------------------- module ---

    static final class GlShaderModule implements GfxShaderModule {
        final GlDevice device;
        final GfxStage stage;
        final String entry;
        final String origin;
        final long hash;
        final int name;
        final boolean valid;
        final String infoLog;
        private boolean closed;

        /**
         * The pack dialect writes Vulkan descriptor sets ({@code layout(set =
         * N, binding = B)}); GL's namespace is flat and our bridge binds a
         * write at {@code unit = set * SET_STRIDE + binding} (RHI.md), so the
         * GLSL handed to GL gets the same packing at compile time. GL-only:
         * the SPIR-V path keeps its sets for the driver to consume. The same
         * pass renames {@code gl_VertexIndex} onto GL's {@code gl_VertexID} -
         * both sides of one fullscreen-triangle convention.
         */
        static String packDescriptorSets(String glsl) {
            // The pack's fullscreen-triangle convention spells Vulkan's
            // gl_VertexIndex; GL's builtin of the same meaning is gl_VertexID.
            // Applies even to shaders with no image declarations.
            glsl = glsl.replaceAll("\\bgl_VertexIndex\\b", "gl_VertexID");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("layout\\s*\\(\\s*set\\s*=\\s*(\\d+)\\s*,\\s*binding\\s*=\\s*(\\d+)")
                    .matcher(glsl);
            if (!m.find()) return glsl;
            StringBuffer out = new StringBuffer(glsl.length());
            do {
                int set = Integer.parseInt(m.group(1));
                int binding = Integer.parseInt(m.group(2));
                m.appendReplacement(out,
                        "layout(binding = " + (set * GlCommandBuffer.SET_STRIDE + binding));
            } while (m.find());
            m.appendTail(out);
            return out.toString();
        }

        GlShaderModule(GlDevice device, ShaderSource source) {
            this.device = device;
            this.stage = source.stage();
            this.entry = source.entryPoint();
            this.origin = source.origin();
            this.hash = source.hash();
            this.name = glCreateShader(GlEnumMap.shaderType(stage));
            if (source.kind() == ShaderSource.Kind.SPIRV) {
                this.valid = specialize(source);
                this.infoLog = valid ? "" : device.lastError();
            } else {
                glShaderSource(name, packDescriptorSets(source.preprocessed()));
                org.lwjgl.opengl.GL46C.glCompileShader(name);
                this.valid = glGetShaderi(name, org.lwjgl.opengl.GL46C.GL_COMPILE_STATUS)
                        == org.lwjgl.opengl.GL46C.GL_TRUE;
                this.infoLog = valid ? "" : glGetShaderInfoLog(name).trim();
                if (!valid) device.lastError = infoLog;
            }
        }

        private boolean specialize(ShaderSource source) {
            if (!device.spirvSupported) {
                device.lastError = "SPIR-V module '" + origin
                        + "' needs GL_ARB_gl_spirv, which this driver does not offer";
                return false;
            }
            byte[] code = source.spirv();
            ByteBuffer bin = MemoryUtil.memAlloc(code.length);
            try {
                bin.put(code).flip();
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer shaders = stack.mallocInt(1).put(0, name);
                    org.lwjgl.opengl.GL46C.glShaderBinary(shaders,
                            org.lwjgl.opengl.GL46C.GL_SHADER_BINARY_FORMAT_SPIR_V, bin);
                }
                org.lwjgl.opengl.GL46C.glSpecializeShader(name, entry.isEmpty() ? "main" : entry,
                        (java.nio.IntBuffer) null, (java.nio.IntBuffer) null);
            } catch (RuntimeException e) {
                device.lastError = "SPIR-V specialization of '" + origin + "' failed: " + e.getMessage();
                return false;
            } finally {
                MemoryUtil.memFree(bin);
            }
            boolean ok = glGetShaderi(name, org.lwjgl.opengl.GL46C.GL_COMPILE_STATUS)
                    == org.lwjgl.opengl.GL46C.GL_TRUE;
            if (!ok) device.lastError = glGetShaderInfoLog(name).trim();
            return ok;
        }

        @Override public long handle() { return name; }
        @Override public String debugName() { return origin; }
        @Override public long sizeBytes() { return 0; }
        @Override public GfxStage stage() { return stage; }
        @Override public String entryPoint() { return entry; }
        @Override public boolean isValid() { return valid; }
        @Override public String infoLog() { return infoLog; }
        @Override public long sourceHash() { return hash; }

        @Override public void close() {
            if (closed) return;
            closed = true;
            device.liveModules.remove(this);
            glDeleteShader(name);
        }
    }

    // -------------------------------------------------------------- pipeline ---

    static final class GlPipeline implements GfxPipeline {
        final GlDevice device;
        final boolean compute;
        final int program;
        final boolean valid;
        final String infoLog;
        final GfxDesc.PipelineLayout layout;
        final GfxDesc.VertexLayout vertexLayout;
        final GfxDesc.Raster raster;
        final GfxDesc.DepthStencil depthStencil;
        final String name;
        private boolean closed;

        GlPipeline(GlDevice device, GfxDesc.GraphicsPipeline desc, boolean ignored) {
            this.device = device;
            this.compute = false;
            this.layout = desc.layout;
            this.vertexLayout = desc.vertexLayout;
            this.raster = desc.raster;
            this.depthStencil = desc.depthStencil;
            this.name = desc.debugName;
            this.program = glCreateProgram();
            for (GfxShaderModule s : desc.stages) {
                org.lwjgl.opengl.GL46C.glAttachShader(program, (int) s.handle());
            }
            glLinkProgram(program);
            this.valid = glGetProgrami(program, org.lwjgl.opengl.GL46C.GL_LINK_STATUS)
                    == org.lwjgl.opengl.GL46C.GL_TRUE;
            this.infoLog = valid ? "" : glGetProgramInfoLog(program).trim();
            for (GfxShaderModule s : desc.stages) {
                org.lwjgl.opengl.GL46C.glDetachShader(program, (int) s.handle());
            }
            if (!valid) device.lastError = infoLog;
        }

        GlPipeline(GlDevice device, GfxDesc.ComputePipeline desc) {
            this.device = device;
            this.compute = true;
            this.layout = desc.layout;
            this.vertexLayout = GfxDesc.VertexLayout.empty();
            this.raster = new GfxDesc.Raster();
            this.depthStencil = new GfxDesc.DepthStencil();
            this.name = desc.debugName;
            this.program = glCreateProgram();
            org.lwjgl.opengl.GL46C.glAttachShader(program, (int) desc.stage.handle());
            glLinkProgram(program);
            this.valid = glGetProgrami(program, org.lwjgl.opengl.GL46C.GL_LINK_STATUS)
                    == org.lwjgl.opengl.GL46C.GL_TRUE;
            this.infoLog = valid ? "" : glGetProgramInfoLog(program).trim();
            org.lwjgl.opengl.GL46C.glDetachShader(program, (int) desc.stage.handle());
            if (!valid) device.lastError = infoLog;
        }

        @Override public long handle() { return program; }
        @Override public String debugName() { return name; }
        @Override public long sizeBytes() { return 0; }
        @Override public boolean isCompute() { return compute; }
        @Override public boolean isValid() { return valid; }
        @Override public String infoLog() { return infoLog; }
        @Override public GfxDesc.PipelineLayout layout() { return layout; }

        @Override public void close() {
            if (closed) return;
            closed = true;
            device.livePipelines.remove(this);
            glDeleteProgram(program);
        }
    }
}
