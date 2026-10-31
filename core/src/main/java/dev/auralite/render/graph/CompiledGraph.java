package dev.auralite.render.graph;

import dev.auralite.render.rhi.DeviceLimits;
import dev.auralite.render.rhi.GfxBuffer;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxDevice;
import dev.auralite.render.rhi.GfxImage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * An executable frame: ordered passes, the barriers each one needs, resource
 * lifetimes and the compile receipts.
 *
 * <p>Immutable after construction except for the resource bindings, which
 * {@link #allocate(GfxDevice)} fills in and {@link #release()} clears. One
 * compiled graph is reused for every frame at a given size; a resize or an
 * option change recompiles.
 */
public final class CompiledGraph {
    private final RenderGraph graph;
    private final int width;
    private final int height;
    private final DeviceLimits limits;
    private final List<GraphPass> ordered;
    private final List<List<Barrier>> barriersByPass;
    private final List<ResourceLifetime> lifetimes;
    private final Map<GraphResource, GfxDesc.Image> effectiveDescs;
    private final GraphStats stats;
    private final List<GraphHistory> histories;

    private boolean allocated;
    private GfxDevice device;
    private final List<AutoCloseable> owned = new ArrayList<AutoCloseable>();
    private long executions;
    private long barriersEmitted;
    private long passesExecuted;

    CompiledGraph(RenderGraph graph, int width, int height, DeviceLimits limits,
                  List<GraphPass> ordered, List<List<Barrier>> barriersByPass,
                  List<ResourceLifetime> lifetimes, Map<GraphResource, GfxDesc.Image> effectiveDescs,
                  GraphStats stats) {
        this.graph = graph;
        this.width = width;
        this.height = height;
        this.limits = limits;
        this.ordered = Collections.unmodifiableList(ordered);
        this.barriersByPass = barriersByPass;
        this.lifetimes = Collections.unmodifiableList(lifetimes);
        this.effectiveDescs = effectiveDescs;
        this.stats = stats;
        this.histories = Collections.unmodifiableList(graph.histories());
    }

    public RenderGraph graph() { return graph; }
    public int width() { return width; }
    public int height() { return height; }
    public DeviceLimits limits() { return limits; }
    public GraphStats stats() { return stats; }
    public List<GraphPass> passes() { return ordered; }
    public List<ResourceLifetime> lifetimes() { return lifetimes; }
    public int passCount() { return ordered.size(); }
    public long executions() { return executions; }
    public long barriersEmitted() { return barriersEmitted; }
    public long passesExecuted() { return passesExecuted; }
    public boolean isAllocated() { return allocated; }

    public List<String> passOrder() {
        List<String> out = new ArrayList<String>();
        for (GraphPass p : ordered) out.add(p.name());
        return out;
    }

    public List<Barrier> barriersBefore(int passIndex) {
        if (passIndex < 0 || passIndex >= barriersByPass.size()) return Collections.emptyList();
        return Collections.unmodifiableList(barriersByPass.get(passIndex));
    }

    public int totalBarriers() {
        int n = 0;
        for (List<Barrier> l : barriersByPass) n += l.size();
        return n;
    }

    public ResourceLifetime lifetimeOf(String resourceName) {
        for (ResourceLifetime l : lifetimes) if (l.resource.name().equals(resourceName)) return l;
        return null;
    }

    /** The descriptor actually used at allocation time, with relative sizes resolved. */
    public GfxDesc.Image effectiveDesc(String resourceName) {
        return effectiveDescs.get(graph.require(resourceName));
    }

    // ------------------------------------------------------------ allocation
    /** Creates every owned resource on {@code dev} and binds it. Idempotent. */
    public void allocate(GfxDevice dev) {
        if (allocated) return;
        this.device = dev;
        for (GraphResource r : graph.resources()) {
            if (r.isImported()) continue;
            if (r.isTexture()) {
                GfxDesc.Image d = effectiveDescs.get(r);
                if (d == null) continue;
                GfxDesc.Image c = GfxDesc.Image.tex2d(d.width, d.height, d.format, d.usage);
                c.type = d.type; c.depth = d.depth; c.mipLevels = d.mipLevels;
                c.arrayLayers = d.arrayLayers; c.samples = d.samples; c.domain = d.domain;
                c.debugName = r.name();
                GfxImage img = dev.createImage(c);
                r.bindImage(img);
                owned.add(img);
            } else {
                GfxDesc.Buffer d = r.bufferDesc();
                if (d == null) continue;
                GfxDesc.Buffer c = GfxDesc.Buffer.of(d.sizeBytes, d.usage).domain(d.domain).named(r.name());
                GfxBuffer b = dev.createBuffer(c);
                r.bindBuffer(b);
                owned.add(b);
            }
        }
        allocated = true;
    }

    /**
     * Binds an imported resource to a handle the host owns.
     *
     * <p>The graph never allocates, resizes or frees an imported resource; it
     * only requires that one is bound before the pass that uses it runs. Inside
     * Minecraft the glue binds the vanilla framebuffer here every frame.
     */
    public void bindImported(String resourceName, GfxImage image) {
        GraphResource r = graph.require(resourceName);
        if (!r.isImported()) {
            throw new GraphException("resource '" + resourceName + "' is owned by the graph, not imported");
        }
        r.bindImage(image);
    }

    public void bindImported(String resourceName, GfxBuffer buffer) {
        GraphResource r = graph.require(resourceName);
        if (!r.isImported()) {
            throw new GraphException("resource '" + resourceName + "' is owned by the graph, not imported");
        }
        r.bindBuffer(buffer);
    }

    /** Checks that every resource the ordered passes touch is bound. */
    private void preflight() {
        for (int i = 0; i < ordered.size(); i++) {
            GraphPass p = ordered.get(i);
            for (GraphAccess a : p.accesses()) {
                boolean bound = a.resource.isBuffer() ? a.resource.buffer() != null : a.resource.image() != null;
                if (!bound) {
                    throw new GraphException("resource '" + a.resource.name() + "' is not bound"
                            + (a.resource.isImported()
                                    ? "; imported resources must be bound by the host via bindImported()"
                                    : ""), p.name());
                }
            }
        }
    }

    public void release() {
        for (int i = owned.size() - 1; i >= 0; i--) {
            try { owned.get(i).close(); } catch (Exception e) { /* recorded by the device */ }
        }
        owned.clear();
        for (GraphResource r : graph.resources()) {
            if (!r.isImported()) { r.bindImage(null); r.bindBuffer(null); }
        }
        allocated = false;
        device = null;
    }

    // ------------------------------------------------------------- execution
    /** Runs the frame. Barriers first, then the pass body, in compiled order. */
    public void execute(GraphContext ctx) {
        if (!allocated) {
            throw new GraphException("graph executed before allocate(); no resources are bound");
        }
        preflight();
        executions++;
        for (int i = 0; i < ordered.size(); i++) {
            GraphPass p = ordered.get(i);
            for (Barrier b : barriersByPass.get(i)) {
                if (b.target == Barrier.Target.IMAGE) {
                    GfxImage img = b.resource.image();
                    if (img != null) {
                        ctx.cmd().barrier(img, b.from, b.to, b.srcAccess, b.dstAccess);
                        barriersEmitted++;
                    }
                } else {
                    GfxBuffer buf = b.resource.buffer();
                    if (buf != null) {
                        ctx.cmd().barrier(buf, b.srcAccess, b.dstAccess);
                        barriersEmitted++;
                    }
                }
            }
            ctx.setCurrentPass(p);
            // The graph owns pass begin/end and the viewport, not the pass body.
            // A body that had to remember to close its own render pass was a
            // footgun the NULL backend caught immediately; now a body only binds
            // pipelines and issues draws or dispatches.
            boolean raster = p.type() == dev.auralite.render.rhi.GfxEnums.PassType.RASTER;
            if (raster) ctx.beginRasterPass();
            if (p.executor() != null) {
                p.executor().execute(ctx);
                passesExecuted++;
            }
            if (raster) ctx.endRasterPass();
            ctx.setCurrentPass(null);
        }
        swapHistories();
    }

    /** Ping-pongs every history pair; called by {@link #execute} at frame end. */
    public void swapHistories() {
        for (GraphHistory h : histories) h.swapBindings();
    }

    /** One line per fact, for the plan receipts and CI diffs. */
    public String receipt() {
        StringBuilder sb = new StringBuilder();
        sb.append("graph '").append(graph.name()).append("' ").append(width).append('x').append(height).append('\n');
        sb.append(stats.receipt());
        sb.append("order: ").append(passOrder()).append('\n');
        sb.append("barriers: ").append(totalBarriers()).append(" planned\n");
        for (int i = 0; i < ordered.size(); i++) {
            List<Barrier> b = barriersByPass.get(i);
            if (!b.isEmpty()) {
                sb.append("  before ").append(ordered.get(i).name()).append(": ");
                for (int k = 0; k < b.size(); k++) {
                    if (k > 0) sb.append(", ");
                    sb.append(b.get(k));
                }
                sb.append('\n');
            }
        }
        sb.append("lifetimes:\n");
        for (ResourceLifetime l : lifetimes) sb.append("  ").append(l).append('\n');
        return sb.toString();
    }
}
