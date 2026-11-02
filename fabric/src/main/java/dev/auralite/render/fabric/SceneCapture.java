package dev.auralite.render.fabric;

import dev.auralite.render.backend.gl.GlDevice;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxImage;
import dev.auralite.render.rhi.GfxUsage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * The vanilla frame as a texture: blits the game's default framebuffer (the
 * world Minecraft just drew - the frame hook runs after Framebuffer.draw and
 * before the swap) into an RGBA8 image the graph binds as the imported
 * {@code scene} resource. The pack's final stage reads it instead of a test
 * pattern; capture runs every frame so the pack always sees the live world.
 *
 * <p>Context discipline, same as {@link RendererLoop#present()}: framebuffer
 * objects are not shared between contexts (only the texture is), so the FBO
 * is created and used on the game context, while the image itself is a
 * device-context object. {@link #capture()} therefore must run with the game
 * context current - which is exactly when the frame hook fires.
 */
public final class SceneCapture {

    private final GlDevice device;
    private int width;
    private int height;
    private GfxImage image;   // the imported "scene" texture (device context)
    private int fbo;          // game-context FBO wrapping it; 0 until first use

    public SceneCapture(GlDevice device, int width, int height) {
        this.device = device;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    /**
     * Device context: (re)create the texture at the current framebuffer size.
     * Returns true when the image was recreated (callers must rebind the
     * imported resource afterwards).
     */
    public boolean resize(int newWidth, int newHeight) {
        int w = Math.max(1, newWidth), h = Math.max(1, newHeight);
        if (w == width && h == height && image != null) return false;
        width = w;
        height = h;
        if (image != null) {
            image.close();
            image = null;
        }
        return true;
    }

    /** Device context: the imported scene image, lazily at the current size. */
    public GfxImage image() {
        if (image == null || image.width() != width || image.height() != height) {
            if (image != null) image.close();
            image = device.createImage(GfxDesc.Image
                    .tex2d(width, height, GfxFormat.RGBA8_UNORM,
                            GfxUsage.IMAGE_STORAGE
                            | GfxUsage.IMAGE_SAMPLED
                            | GfxUsage.IMAGE_COLOR_ATTACHMENT)
                    .named("scene"));
        }
        return image;
    }

    /** Game context: blit the default framebuffer (the vanilla frame) in. */
    public void capture() {
        if (fbo == 0) fbo = GL30.glGenFramebuffers();
        int tex = device.textureName(image());
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                GL11.GL_TEXTURE_2D, tex, 0);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            throw new RuntimeException("scene capture FBO incomplete: 0x" + Integer.toHexString(status));
        }
        // READ = 0: the game's back buffer holds the world just drawn
        // (Framebuffer.draw already ran; the swap has not). DRAW = our FBO.
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
        GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    /**
     * Device context: release the texture. The FBO is deliberately kept: it
     * belongs to the game context, which is not current here, and a retry
     * reuses it (one live FBO per process, freed by the OS at exit).
     */
    public void destroy() {
        if (image != null) {
            image.close();
            image = null;
        }
    }

    public int width() { return width; }
    public int height() { return height; }
}
