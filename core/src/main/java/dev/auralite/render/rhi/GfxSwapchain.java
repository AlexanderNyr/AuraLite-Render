package dev.auralite.render.rhi;

/**
 * Presentation surface.
 *
 * <p>Inside Minecraft the "swapchain" is whatever framebuffer the vanilla
 * renderer owns, so the Fabric glue supplies an implementation that wraps it
 * instead of creating one. The standalone harness creates a real one.
 */
public interface GfxSwapchain {
    int width();
    int height();
    GfxFormat format();
    int imageCount();
    /** Acquires the next image; returns its index. */
    int acquireNextImage();
    GfxImage image(int index);
    void present(int index);
    /** True when the surface size changed and resources must be recreated. */
    boolean needsRecreate();
    void recreate(int width, int height);
}
