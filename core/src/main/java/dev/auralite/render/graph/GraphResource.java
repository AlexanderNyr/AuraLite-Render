package dev.auralite.render.graph;

import dev.auralite.render.rhi.GfxBuffer;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxImage;
import dev.auralite.render.rhi.GfxUsage;

/**
 * A named graph resource: either a texture the graph owns, a buffer it owns, or
 * something imported from outside (the vanilla framebuffer, a pack-supplied
 * texture, the swapchain image).
 *
 * <p>Imported resources are never reallocated by the graph and never aliased,
 * which is how the Minecraft glue hands the renderer a framebuffer it does not
 * own.
 */
public final class GraphResource {
    public enum ResType { TEXTURE, BUFFER }

    private final String name;
    private final ResType type;
    private final GfxDesc.Image imageDesc;
    private final GfxDesc.Buffer bufferDesc;
    private final boolean imported;
    private final boolean transientAttachment;

    private GfxImage image;
    private GfxBuffer buffer;
    private int index = -1;

    GraphResource(String name, ResType type, GfxDesc.Image imageDesc, GfxDesc.Buffer bufferDesc,
                  boolean imported, boolean transientAttachment) {
        this.name = name;
        this.type = type;
        this.imageDesc = imageDesc;
        this.bufferDesc = bufferDesc;
        this.imported = imported;
        this.transientAttachment = transientAttachment;
    }

    static GraphResource texture(String name, GfxDesc.Image desc) {
        boolean tr = (desc.usage & GfxUsage.IMAGE_TRANSIENT_ATTACHMENT) != 0;
        return new GraphResource(name, ResType.TEXTURE, desc, null, false, tr);
    }

    static GraphResource buffer(String name, GfxDesc.Buffer desc) {
        return new GraphResource(name, ResType.BUFFER, null, desc, false, false);
    }

    static GraphResource importedTexture(String name, GfxDesc.Image desc) {
        return new GraphResource(name, ResType.TEXTURE, desc, null, true, false);
    }

    static GraphResource importedBuffer(String name, GfxDesc.Buffer desc) {
        return new GraphResource(name, ResType.BUFFER, null, desc, true, false);
    }

    public String name() { return name; }
    public ResType type() { return type; }
    public boolean isTexture() { return type == ResType.TEXTURE; }
    public boolean isBuffer() { return type == ResType.BUFFER; }
    public boolean isImported() { return imported; }
    public boolean isTransient() { return transientAttachment; }
    public GfxDesc.Image imageDesc() { return imageDesc; }
    public GfxDesc.Buffer bufferDesc() { return bufferDesc; }
    public GfxFormat format() { return imageDesc == null ? GfxFormat.UNDEFINED : imageDesc.format; }
    public int index() { return index; }
    void setIndex(int i) { this.index = i; }

    public GfxImage image() { return image; }
    public GfxBuffer buffer() { return buffer; }
    public void bindImage(GfxImage img) { this.image = img; }
    public void bindBuffer(GfxBuffer buf) { this.buffer = buf; }

    /** Device bytes this resource occupies, 0 while unallocated. */
    public long sizeBytes() {
        if (image != null) return image.sizeBytes();
        if (buffer != null) return buffer.sizeBytes();
        if (imageDesc != null) return imageDesc.approxBytes();
        return bufferDesc == null ? 0 : bufferDesc.sizeBytes;
    }

    @Override public String toString() { return name + (imported ? "(imported)" : ""); }
}
