package dev.auralite.render.fabric;

import dev.auralite.render.backend.gl.GlDevice;
import dev.auralite.render.rhi.GfxDevice;
import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxImage;
import dev.auralite.render.rhi.GfxUsage;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;

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
 * <p>Two lanes, one blit: on the GL device {@link #colorTexture()} hands the
 * graph's own texture straight over; on the VK device the graph's target is
 * a Vulkan image the game's GL context cannot sample, so each frame's
 * {@code readImage} bytes are stored here and uploaded into a preview
 * texture on the present call (game context). The upload does NOT flip
 * rows: the r33 windowed run proved the VK readback's row 0 already is
 * the screen's bottom - the same physical order GL's glTexImage2D expects
 * (the earlier "VK row 0 is the top" comment was a pre-first-frame
 * guess; flipping on top of it turned the world upside down, r33).
 *
 * <p>Resize policy: a size change invalidates the compiled graph (the loop
 * recompiles at the new extents and rebinds the backbuffer); the texture
 * itself is re-created lazily at the new size on the next image() call.
 */
public final class WindowSwapchain {

    private final GfxDevice device;
    private int width;
    private int height;
    private GfxImage image;    // offscreen backbuffer, recreated on size change
    private int previewTex;    // VK lane: GL texture for the present blit; 0 = none
    private int previewW;      // preview texture extent (storage exists at this size)
    private int previewH;
    private byte[] stored;     // VK lane: this frame's readImage bytes, bottom row first
    private byte[] lastPixels; // the bytes the LAST present uploaded (receipt source)
    private boolean everFilled;// VK lane: at least one readback has been uploaded
    private ByteBuffer uploadScratch; // persistent direct staging for the upload

    public WindowSwapchain(GfxDevice device, int width, int height) {
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

    /**
     * VK lane: this frame's readback, stored until the next present (the
     * render graph runs on the device side, the upload must run on the game
     * context). Row 0 of the array is the bottom of the screen - verified
     * by the r33 windowed run (a row flip on upload inverted the world).
     */
    public void storePresentPixels(byte[] bottomRowFirst) {
        this.stored = bottomRowFirst;
    }

    /**
     * Texture object name for the present blit (game context reads it).
     * GL lane: the graph's own texture. VK lane: the preview texture, fed
     * from the stored readback as-is - both lanes hand row 0 (the screen's
     * bottom) to glTexImage2D, so one blit serves both.
     */
    public int colorTexture() {
        if (device instanceof GlDevice gl) {
            return gl.textureName(image());
        }
        if (previewTex == 0) {
            previewTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previewTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            // Allocate storage NOW: the first F8 frames have no readback yet
            // (present is deferred one frame), and a texture without storage
            // makes the present FBO incomplete (r36: 0x8cd6 -> frames stop).
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            previewW = width;
            previewH = height;
            everFilled = false;
        } else if (previewW != width || previewH != height) {
            // Resize: previous pixels are the wrong extent - re-allocate and
            // wait for the first readback at the new size.
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previewTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            previewW = width;
            previewH = height;
            everFilled = false;
        }
        if (stored != null && stored.length == width * height * 4) {
            // No row flip: the r33 run showed the readback's row order
            // already matches what glTexSubImage2D + the 1:1 present blit need
            // (flipping here inverted the world on screen).
            //
            // r38: this used to be a fresh glTexImage2D + a fresh 3.6 MB
            // BufferUtils allocation every frame - and hs_err_pid9540 puts the
            // nvoglv64 AV (0xC0000409, the r35 crash PC) squarely inside that
            // call from colorTexture()+308 on driver 475.56.0. Storage for
            // this extent already exists (the null upload at create/resize),
            // so the pixels now go in through glTexSubImage2D - no per-frame
            // reallocation in the driver - from one reused scratch buffer,
            // with the unpack state pinned (vanilla may leave ROW_LENGTH or
            // SKIP_* set, which would make the driver read past the buffer).
            if (uploadScratch == null || uploadScratch.capacity() < stored.length) {
                uploadScratch = BufferUtils.createByteBuffer(stored.length);
            }
            uploadScratch.clear();
            uploadScratch.put(stored).flip();
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previewTex);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, height,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, uploadScratch);
            everFilled = true;
            lastPixels = stored;
        }
        stored = null;
        return previewTex;
    }

    /**
     * True once there are pixels to blit: the GL lane's device texture
     * always has them; the VK lane waits for its first deferred readback.
     */
    public boolean everFilled() {
        return device instanceof GlDevice || everFilled;
    }

    /**
     * The exact bytes the most recent present uploaded, or null. A receipt
     * hashed from this is a receipt of what went to the window, not of what
     * the CPU hoped the GPU had - which is the difference between a claim and
     * a measurement (standing rule 4).
     */
    public byte[] lastPresentPixels() { return lastPixels; }

    /** Release the graph's image; must run on the device's context. */
    public void destroy() {
        if (image != null) {
            image.close();
            image = null;
        }
        // previewTex belongs to the game context and is deliberately kept:
        // like SceneCapture's FBO it is one GL object per process, reused by
        // a retry and freed by the OS at exit (deleting it needs the game
        // context current, which shutdown does not guarantee).
    }

    public int width() { return width; }
    public int height() { return height; }
}
