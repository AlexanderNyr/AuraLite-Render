package dev.auralite.render.fabric;

import dev.auralite.render.backend.gl.GlDevice;
import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxImage;
import dev.auralite.render.rhi.GfxUsage;

/**
 * The frame's present target: an offscreen RGBA8 texture the graph renders
 * into, blitted to the game framebuffer by {@link RendererLoop#present()}.
 *
 * <p>Why offscreen and not handle 0: the graph now runs on a private 4.6
 * context (see {@link HiddenGlContext}) whose FB0 is a hidden 1x1 window -
 * the real window surface is only touched from the game's context during
 * present. The texture carries IMAGE_TRANSFER_SRC so the readback gate can
 * hash it, exactly like the gpu-test lane's named "window" image.
 *
 * <p>Resize policy: a size change invalidates the compiled graph (the loop
 * recompiles at the new extents and rebinds the backbuffer); the texture
 * itself is re-created lazily at the new size on the next image() call.
 */
public final class WindowSwapchain {

    private final GlDevice device;
    private int width;
    private int height;
    private GfxImage image;   // offscreen backbuffer, recreated on size change

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
            AuraLiteRenderMod.LOGGER.info("swapchain resize: {}x{}", w, h);
            return true;
        }
        return false;
    }

    /** The graph's imported backbuffer: offscreen, at the current size. */
    public GfxImage image() {
        if (image == null || image.width() != width || image.height() != height) {
            if (image != null) image.close();
            image = device.createImage(dev.auralite.render.rhi.GfxDesc.Image
                    .tex2d(width, height, GfxFormat.RGBA8_UNORM,
                            GfxUsage.IMAGE_COLOR_ATTACHMENT
                            | GfxUsage.IMAGE_PRESENT
                            | GfxUsage.IMAGE_TRANSFER_SRC)
                    .named("window"));
        }
        return image;
    }

    /** Texture object name for the present blit (game context reads it). */
    public int colorTexture() {
        return device.textureName(image());
    }

    /** Release the texture; must run on the device's context. */
    public void destroy() {
        if (image != null) {
            image.close();
            image = null;
        }
    }

    public int width() { return width; }
    public int height() { return height; }
}
