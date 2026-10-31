package dev.auralite.render.rhi;

import dev.auralite.render.rhi.GfxEnums.PassType;

import java.nio.ByteBuffer;

/**
 * Recording surface. One interface, two implementations, no GL/VK types leak.
 *
 * <p>Every method here has a measured host-side gate in the NULL backend: the
 * NULL device records the call stream and validates ordering, so a pipeline bug
 * is caught by {@code make test} on a machine with no GPU at all.
 */
public interface GfxCommandBuffer {

    void begin();
    void end();
    boolean isRecording();
    PassType passType();

    // ---- raster -------------------------------------------------------
    void beginRenderPass(GfxDesc.RenderPass pass);
    void endRenderPass();
    void nextSubpass();
    void setViewport(float x, float y, float w, float h, float minDepth, float maxDepth);
    void setScissor(int x, int y, int w, int h);
    void bindGraphicsPipeline(GfxPipeline pipeline);
    void bindVertexBuffer(int binding, GfxBuffer buffer, long offset);
    void bindIndexBuffer(GfxBuffer buffer, long offset, boolean index32);
    void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance);
    void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance);
    void drawIndirect(GfxBuffer args, long offset, int drawCount, int stride);
    void drawIndexedIndirect(GfxBuffer args, long offset, int drawCount, int stride);
    void drawIndexedIndirectCount(GfxBuffer args, long argsOffset, GfxBuffer count, long countOffset,
                                  int maxDrawCount, int stride);

    // ---- compute ------------------------------------------------------
    void bindComputePipeline(GfxPipeline pipeline);
    void dispatch(int groupsX, int groupsY, int groupsZ);
    void dispatchIndirect(GfxBuffer args, long offset);

    // ---- resources ----------------------------------------------------
    void bindDescriptorSet(int setIndex, GfxDescriptorSet set);
    void pushConstants(int offset, ByteBuffer data);
    void pushConstants(int offset, float[] values);
    void copyBuffer(GfxBuffer src, long srcOffset, GfxBuffer dst, long dstOffset, long sizeBytes);
    void copyBufferToImage(GfxBuffer src, GfxImage dst, int mipLevel);
    void copyImage(GfxImage src, int srcMip, GfxImage dst, int dstMip, int width, int height, int depth);
    void blitImage(GfxImage src, GfxImage dst, boolean linearFilter);
    void generateMipmaps(GfxImage image);
    void barrier(GfxImage image, GfxEnums.ResourceState from, GfxEnums.ResourceState to, int srcAccess, int dstAccess);
    void barrier(GfxBuffer buffer, int srcAccess, int dstAccess);
    void memoryBarrierAll();
    void writeTimestamp(int queryIndex);

    /** Number of recorded commands; the NULL backend's receipts come from this. */
    int commandCount();
}
