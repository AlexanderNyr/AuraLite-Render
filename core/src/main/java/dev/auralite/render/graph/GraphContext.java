package dev.auralite.render.graph;

import dev.auralite.render.rhi.GfxBuffer;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxCommandBuffer;
import dev.auralite.render.rhi.GfxDevice;
import dev.auralite.render.rhi.GfxImage;
import dev.auralite.render.scene.FrameContext;

import java.util.HashMap;
import java.util.Map;

/**
 * What a pass body receives.
 *
 * <p>Nothing here is backend specific, so the same pass body runs on the NULL,
 * GL46 and Vulkan lanes. The {@code scratch} map is where the Minecraft glue
 * hands pack code per-frame values (camera matrices, the biome field, the
 * lightmap texture) without them becoming part of the graph's resource set.
 */
public final class GraphContext {
    private final GfxDevice device;
    private final GfxCommandBuffer cmd;
    private final CompiledGraph graph;
    private final FrameContext frame;
    private final Map<String, Object> scratch = new HashMap<String, Object>();
    private GraphPass currentPass;
    private int areaW, areaH;

    public GraphContext(GfxDevice device, GfxCommandBuffer cmd, CompiledGraph graph, FrameContext frame) {
        this.device = device;
        this.cmd = cmd;
        this.graph = graph;
        this.frame = frame;
    }

    public GfxDevice device() { return device; }
    public GfxCommandBuffer cmd() { return cmd; }
    public CompiledGraph graph() { return graph; }
    public FrameContext frame() { return frame; }
    public int width() { return graph.width(); }
    public int height() { return graph.height(); }
    public long frameId() { return frame == null ? 0 : frame.frameId(); }

    public GraphPass currentPass() { return currentPass; }
    void setCurrentPass(GraphPass p) { this.currentPass = p; }

    public GfxImage image(String resourceName) {
        GraphResource r = graph.graph().require(resourceName);
        GfxImage img = r.image();
        if (img == null) {
            throw new GraphException("resource '" + resourceName + "' is not a texture or is not allocated",
                    currentPass == null ? "" : currentPass.name());
        }
        return img;
    }

    public GfxBuffer buffer(String resourceName) {
        GraphResource r = graph.graph().require(resourceName);
        GfxBuffer b = r.buffer();
        if (b == null) {
            throw new GraphException("resource '" + resourceName + "' is not a buffer or is not allocated",
                    currentPass == null ? "" : currentPass.name());
        }
        return b;
    }

    public GraphResource resource(String resourceName) { return graph.graph().require(resourceName); }

    /**
     * Builds and begins the render pass implied by the current pass's declared
     * attachments, so a pack body never spells out its own target list.
     *
     * <p>The render area comes from the attachments, not from the framebuffer:
     * a 256x256 cloud-shadow map and a 2048x2048 shadow map are passes in the
     * same graph as the 1920x1080 lighting pass, and each one's area is the
     * smallest attachment it writes, mip level accounted for.
     */
    public void beginRasterPass() {
        if (currentPass == null) throw new GraphException("beginRasterPass outside a pass body");
        int[] area = renderArea();
        int w = area[0], h = area[1];
        GfxDesc.RenderPass rp = GfxDesc.RenderPass.of(w, h).named(currentPass.name());
        for (GraphAccess a : currentPass.colorAttachments()) {
            rp.color(GfxDesc.Attachment.of(image(a.resource.name())));
        }
        GraphAccess d = currentPass.depthAttachment();
        if (d != null) rp.depth(GfxDesc.Attachment.of(image(d.resource.name())));
        cmd.beginRenderPass(rp);
        // The render area follows the attachments, so the viewport must too: a
        // 256x256 cloud-shadow pass with a 1920x1080 viewport draws nothing.
        cmd.setViewport(0, 0, w, h, 0.0f, 1.0f);
        cmd.setScissor(0, 0, w, h);
        areaW = w; areaH = h;
    }

    /**
     * The render area the current pass will use, as {@code {width, height}},
     * without beginning anything.
     *
     * <p>A pass body needs this before it builds a pipeline: a pipeline created
     * against the framebuffer's 1920x1080 is the wrong pipeline for the 256x256
     * cloud-shadow pass and for the 2048x2048 shadow map that sit in the same
     * graph. The rule is the smallest attached image, mip level accounted for,
     * falling back to the graph size only when a pass attaches nothing.
     */
    public int[] renderArea() {
        if (currentPass == null) return new int[] { graph.width(), graph.height() };
        int w = graph.width(), h = graph.height();
        boolean any = false;
        for (GraphAccess a : currentPass.colorAttachments()) {
            GfxImage img = image(a.resource.name());
            int shift = Math.max(0, a.mipLevel);
            int iw = Math.max(1, img.width() >> shift), ih = Math.max(1, img.height() >> shift);
            w = any ? Math.min(w, iw) : iw;
            h = any ? Math.min(h, ih) : ih;
            any = true;
        }
        GraphAccess d = currentPass.depthAttachment();
        if (d != null) {
            GfxImage img = image(d.resource.name());
            w = any ? Math.min(w, img.width()) : img.width();
            h = any ? Math.min(h, img.height()) : img.height();
        }
        return new int[] { w, h };
    }

    /** Width of the area the last {@link #beginRasterPass()} used. */
    public int renderAreaWidth() { return areaW; }

    /** Height of the area the last {@link #beginRasterPass()} used. */
    public int renderAreaHeight() { return areaH; }

    public void endRasterPass() { cmd.endRenderPass(); }

    public void put(String key, Object value) { scratch.put(key, value); }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) { return (T) scratch.get(key); }

    public boolean has(String key) { return scratch.containsKey(key); }
}
