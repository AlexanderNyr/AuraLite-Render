package dev.auralite.render.fabric;

import dev.auralite.render.backend.gl.GlDevice;
import dev.auralite.render.rhi.GfxDevice;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxImage;
import dev.auralite.render.rhi.GfxUsage;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;

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
 *
 * <p>Two lanes: on the GL device the blit lands straight in the device
 * texture (the device texture IS a GL texture). On the VK device the graph's
 * image is a Vulkan image the game context cannot attach, so the screen copy
 * lands in this class's own GL texture, is read back with glGetTexImage and
 * uploaded into the VK image. No row flip on the upload: both GL and Vulkan
 * samplers read row 0 at v=0, and glGetTexImage hands row 0 (the screen's
 * bottom) first - so the VK image sees the same v=0 row the GL texture had.
 */
public final class SceneCapture {

    private final GfxDevice device;
    private int width;
    private int height;
    private GfxImage image;   // the imported "scene" texture (device object)
    private int fbo;          // GL-lane FBO wrapping the device texture
    private int vkCopyTex;    // VK-lane: screen copy lives in this GL texture
    private int vkCopyFbo;    // VK-lane FBO wrapping vkCopyTex
    private final int[] packPbo = new int[2]; // VK-lane readback ring (bottom row first)
    private int pboWrite;     // ring slot this frame's glReadPixels targets
    private int pboFrames;    // frames queued (0 until the first upload is due)
    private int pboBytes;     // allocated PBO size; resize forces a rebuild

    public SceneCapture(GfxDevice device, int width, int height) {
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
        if (device instanceof GlDevice gl) {
            glLaneCapture(gl);
        } else {
            vkLaneCapture();
        }
    }

    /** GL lane: the device texture is a GL texture - blit straight into it. */
    private void glLaneCapture(GlDevice gl) {
        if (fbo == 0) fbo = GL30.glGenFramebuffers();
        int tex = gl.textureName(image());
        blitScreenInto(fbo, tex);
    }

    /**
     * VK lane: screen -> this class's GL texture -> glReadPixels into a
     * PIXEL_PACK PBO -> uploadImage into the VK image. The readback/upload
     * runs on the render thread with the game context current; the Vulkan
     * device does not care which GL context is current.
     *
     * <p>Ring of two PBOs, one frame of latency: frame N queues its screen
     * copy into PBO[N] (the GPU finishes it while the next vanilla draw
     * runs) and uploads the previous frame's PBO[N-1], which by then is
     * done. That removes the per-frame stall the synchronous glGetTexImage
     * + upload caused (r35: ~25 fps with hangs vs 45 on the GL lane), and
     * glReadPixels-into-PBO replaces glGetTexImage outright - the r35
     * crash was an AV inside nvoglv64.dll. Row order is unchanged: GL
     * reads bottom row first, exactly what uploadImage took before.
     */
    private void vkLaneCapture() {
        if (vkCopyTex == 0) {
            vkCopyTex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, vkCopyTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                    GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        }
        if (vkCopyFbo == 0) vkCopyFbo = GL30.glGenFramebuffers();
        blitScreenInto(vkCopyFbo, vkCopyTex);

        int bytes = width * height * 4;
        if (packPbo[0] != 0 && pboBytes != bytes) {
            // Resize: the ring holds the old extent - drop it and refill
            // from scratch (pboFrames = 0 skips one upload frame).
            GL15.glDeleteBuffers(packPbo);
            packPbo[0] = 0;
            packPbo[1] = 0;
            pboFrames = 0;
        }
        if (packPbo[0] == 0) {
            packPbo[0] = GL15.glGenBuffers();
            packPbo[1] = GL15.glGenBuffers();
            for (int i = 0; i < 2; i++) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packPbo[i]);
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, bytes, GL15.GL_STREAM_DRAW);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            pboBytes = bytes;
        }
        // Upload the previous frame's copy (done while this frame's vanilla
        // draw ran - the map does not wait), then queue this frame's read.
        if (pboFrames > 0) {
            int prev = 1 - pboWrite;
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packPbo[prev]);
            ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
            if (mapped != null) {
                device.uploadImage(image(), 0, mapped);
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, vkCopyFbo);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packPbo[pboWrite]);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        pboWrite = 1 - pboWrite;
        pboFrames++;
    }

    /**
     * Screen (READ = FB0, the vanilla frame) into {@code fbo} wrapping
     * {@code tex} (DRAW). Game context only.
     */
    private void blitScreenInto(int fbo, int tex) {
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
     * Device context: release the device texture. The GL FBOs are
     * deliberately kept: they belong to the game context, which is not
     * current here, and a retry reuses them (one set per process, freed by
     * the OS at exit).
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
