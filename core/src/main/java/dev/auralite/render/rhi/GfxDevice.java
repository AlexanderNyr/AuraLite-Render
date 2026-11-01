package dev.auralite.render.rhi;

import dev.auralite.render.rhi.GfxEnums.BackendType;
import dev.auralite.render.rhi.GfxEnums.QueueType;

import java.nio.ByteBuffer;
import java.util.Set;

/**
 * The whole backend surface.
 *
 * <p>Implementations: {@code NullDevice} (host tests), {@code Gl46Device}
 * (OpenGL 4.6 core), {@code Vk13Device} (Vulkan 1.3). A Minecraft frame is
 * expressed once against this interface and runs on any of the three.
 */
public interface GfxDevice extends AutoCloseable {

    BackendType backendType();
    String backendName();
    String deviceName();
    String driverVersion();
    DeviceLimits limits();
    Set<GfxCapability> capabilities();
    boolean hasCapability(GfxCapability c);

    // ---- creation -----------------------------------------------------
    GfxBuffer createBuffer(GfxDesc.Buffer desc);
    GfxImage createImage(GfxDesc.Image desc);
    GfxSampler createSampler(GfxDesc.Sampler desc);
    GfxShaderModule createShaderModule(ShaderSource source);
    GfxPipeline createGraphicsPipeline(GfxDesc.GraphicsPipeline desc);
    GfxPipeline createComputePipeline(GfxDesc.ComputePipeline desc);
    GfxCommandBuffer createCommandBuffer(QueueType queue);

    // ---- uploads ------------------------------------------------------
    void uploadBuffer(GfxBuffer dst, ByteBuffer src, long dstOffsetBytes);
    void uploadBuffer(GfxBuffer dst, float[] src, long dstOffsetBytes);
    void uploadBuffer(GfxBuffer dst, int[] src, long dstOffsetBytes);
    void uploadImage(GfxImage dst, int mipLevel, ByteBuffer src);
    void uploadImageRegion(GfxImage dst, int mipLevel, int x, int y, int z,
                           int w, int h, int d, ByteBuffer src);

    // ---- frame --------------------------------------------------------
    // ---- readback --------------------------------------------------------
    /**
     * The image's pixels exactly as the device holds them: mip 0, row-major
     * RGBA8, {@code width * height * 4} bytes. The GPU gate hashes these
     * bytes - a blank result means no frame reached the image. The RGBA8
     * family only; anything else refuses by name, and the null device
     * records a violation (it has no pixels to hash).
     */
    byte[] readImage(GfxImage image);

    /** Begins a frame; returns a monotonically increasing frame id. */
    long beginFrame();
    void submit(QueueType queue, GfxCommandBuffer cmd);
    void submitAndWait(QueueType queue, GfxCommandBuffer cmd);
    void endFrame();
    /** Blocks until all submitted work is done. Used at pack reload. */
    void waitIdle();

    // ---- diagnostics --------------------------------------------------
    /** Live GPU object counts, for the leak gate. */
    int liveBufferCount();
    int liveImageCount();
    int livePipelineCount();
    int liveShaderModuleCount();
    /** Last backend error string, empty when clean. */
    String lastError();
    /** Milliseconds spent inside this device since creation (CPU-side, honest). */
    long cpuTimeMillis();
    /**
     * GPU timestamps (nanoseconds) written during the last frame, in write
     * order - the gate asserts they are strictly increasing. Empty when the
     * frame wrote none; the null device has no clock to read and returns
     * empty too.
     */
    long[] frameTimestamps();

    @Override void close();
}
