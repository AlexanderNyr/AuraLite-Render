package dev.auralite.render.fabric;

import dev.auralite.render.backend.gl.GlDevice;
import dev.auralite.render.rhi.GfxImage;

/**
 * The window-system backbuffer, as the graph's imported present target.
 *
 * <p>Neither backend owns a swapchain (RHI.md): presentation is this glue.
 * On GL the window framebuffer is the device's handle-0 image
 * ({@link GlDevice#defaultFramebuffer}); the graph never reallocates it and
 * refuses to mix it with offscreen attachments, which is exactly the rule
 * the NULL backend enforces too.
 *
 * <p>Resize policy: a size change invalidates the compiled graph (the host
 * recompiles at the new extents and rebinds the backbuffer), never the
 * window image itself - handle 0 always refers to "whatever the window is
 * now".
 */
public final class WindowSwapchain {

    private final GlDevice device;
    private int width;
    private int height;
    private boolean resized;

    public WindowSwapchain(GlDevice device, int width, int height) {
        this.device = device;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    /** Tracks the framebuffer size; returns true when the graph must rebuild. */
    public boolean resize(int newWidth, int newHeight) {
        int w = Math.max(1, newWidth), h = Math.max(1, newHeight);
        if (w != width || h != height) {
            width = w;
            height = h;
            resized = true;
            AuraLiteRenderMod.LOGGER.info("swapchain resize: {}x{}", w, h);
            return true;
        }
        return false;
    }

    /** True once after a resize, consumed by the frame loop before rebuild. */
    public boolean consumeResized() {
        boolean r = resized;
        resized = false;
        return r;
    }

    public GfxImage image() {
        return device.defaultFramebuffer(width, height);
    }

    public int width() { return width; }
    public int height() { return height; }
}
