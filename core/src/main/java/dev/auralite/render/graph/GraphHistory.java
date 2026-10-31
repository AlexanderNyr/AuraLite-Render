package dev.auralite.render.graph;

import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxImage;

/**
 * A ping-pong pair, which is how feedback stays acyclic.
 *
 * <p>TAA, reprojection, temporal cloud noise and accumulated SSR all need to
 * read last frame's result. Declaring that as "pass X reads what pass X
 * writes" is a genuine cycle, so the graph exposes it honestly as two
 * resources: a pass reads {@code previous()} and writes {@code current()}, and
 * the frame swaps the two bindings at the end. Two frames later the bindings
 * are back where they started, which is exactly what the B-lane gate asserts.
 */
public final class GraphHistory {
    private final String logicalName;
    private final GraphResource a;
    private final GraphResource b;
    private int phase;
    private int swaps;

    GraphHistory(String logicalName, GraphResource a, GraphResource b) {
        this.logicalName = logicalName;
        this.a = a;
        this.b = b;
    }

    public String logicalName() { return logicalName; }

    /**
     * The two physical sides, independent of the current phase.
     *
     * <p>The compiler needs both, because an edge exists between a writer of one
     * side and a reader of the other regardless of which side is "current" this
     * frame -- that edge is what keeps a temporal producer alive during culling.
     */
    public GraphResource sideA() { return a; }
    public GraphResource sideB() { return b; }
    public GraphResource current() { return phase == 0 ? a : b; }
    public GraphResource previous() { return phase == 0 ? b : a; }
    public String currentName() { return current().name(); }
    public String previousName() { return previous().name(); }
    public int phase() { return phase; }
    public int swapCount() { return swaps; }

    /** Exchanges the two GPU bindings. Called once per frame after execution. */
    void swapBindings() {
        GfxImage ia = a.image(), ib = b.image();
        a.bindImage(ib);
        b.bindImage(ia);
        phase ^= 1;
        swaps++;
    }

    /** Registers the pair on a graph; the descriptor is used for both sides. */
    static GraphHistory create(RenderGraph graph, String name, GfxDesc.Image desc) {
        GfxDesc.Image da = desc;
        GfxDesc.Image db = GfxDesc.Image.tex2d(desc.width, desc.height, desc.format, desc.usage);
        db.type = desc.type; db.depth = desc.depth; db.mipLevels = desc.mipLevels;
        db.arrayLayers = desc.arrayLayers; db.samples = desc.samples;
        db.domain = desc.domain;
        GraphResource ra = graph.addTexture(name + ".0", da);
        GraphResource rb = graph.addTexture(name + ".1", db);
        return new GraphHistory(name, ra, rb);
    }
}
