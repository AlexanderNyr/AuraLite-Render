package dev.auralite.render.graph;

import dev.auralite.render.rhi.GfxEnums.PassType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One node of the render graph.
 *
 * <p>A pass declares what it reads and what it writes and nothing else: the
 * compiler derives ordering, barriers, lifetimes and aliasing from those
 * declarations. That is the whole point of having a graph instead of Iris's
 * fixed {@code composite0..N} list -- a pack may declare a DAG with compute
 * nodes, feedback edges through history buffers, and passes that only exist
 * when an option is on.
 */
public final class GraphPass {
    /** Body executed when the pass runs. */
    public interface Executor {
        void execute(GraphContext ctx);
    }

    private final String name;
    private final PassType type;
    private final List<GraphAccess> accesses = new ArrayList<GraphAccess>();
    private final List<String> conditionals = new ArrayList<String>();
    private Executor executor;
    private int declarationIndex;
    private boolean external;
    private boolean kept;
    private String keepReason = "";
    private boolean enabled = true;
    private String disableReason = "";
    /** Compute work-group size declared by the pack, 0 when not a compute pass. */
    private int groupsX, groupsY, groupsZ;
    /** Measured cost, filled in by the profiling lane; -1 until measured. */
    private double lastCostMillis = -1.0;

    GraphPass(String name, PassType type, int declarationIndex) {
        this.name = name;
        this.type = type;
        this.declarationIndex = declarationIndex;
    }

    void addAccess(GraphAccess a) { accesses.add(a); }
    void setExecutor(Executor e) { this.executor = e; }
    void setExternal(boolean e) { this.external = e; }
    void setKept(boolean k, String why) { this.kept = k; this.keepReason = k && why != null ? why : ""; }
    void setEnabled(boolean e, String reason) { this.enabled = e; this.disableReason = reason == null ? "" : reason; }
    void addConditional(String optionName) { conditionals.add(optionName); }
    void setDispatch(int x, int y, int z) { this.groupsX = x; this.groupsY = y; this.groupsZ = z; }
    public void recordCost(double millis) { this.lastCostMillis = millis; }

    public String name() { return name; }
    public PassType type() { return type; }
    public List<GraphAccess> accesses() { return Collections.unmodifiableList(accesses); }
    public List<String> conditionals() { return Collections.unmodifiableList(conditionals); }
    public Executor executor() { return executor; }
    public int declarationIndex() { return declarationIndex; }
    public boolean isExternal() { return external; }

    /**
     * True when this pass has consumers the graph cannot see.
     *
     * <p>A legacy Iris pack samples {@code shadowtex0}, {@code shadowtex1},
     * {@code shadowcolor0} and {@code shadowcolor1} from any later program
     * through implicit bindings that no {@code shaders.properties} line ever
     * declares. Reachability from the external output would therefore cull the
     * shadow passes as dead, which is correct graph reasoning and a broken
     * frame. {@code keep} records that the pass is load-bearing anyway.
     */
    public boolean isKept() { return kept; }

    /** Why this pass was kept, for the compile receipt. Empty when not kept. */
    public String keepReason() { return keepReason; }
    public boolean isEnabled() { return enabled; }
    public String disableReason() { return disableReason; }
    public boolean isCompute() { return type == PassType.COMPUTE; }
    public int groupsX() { return groupsX; }
    public int groupsY() { return groupsY; }
    public int groupsZ() { return groupsZ; }
    public double lastCostMillis() { return lastCostMillis; }

    public List<GraphAccess> reads() {
        List<GraphAccess> out = new ArrayList<GraphAccess>();
        for (GraphAccess a : accesses) if (a.isRead()) out.add(a);
        return out;
    }

    public List<GraphAccess> writes() {
        List<GraphAccess> out = new ArrayList<GraphAccess>();
        for (GraphAccess a : accesses) if (a.isWrite()) out.add(a);
        return out;
    }

    /** Colour attachments in slot order, for render-pass construction. */
    public List<GraphAccess> colorAttachments() {
        List<GraphAccess> out = new ArrayList<GraphAccess>();
        for (GraphAccess a : accesses) if (a.attachmentSlot >= 0) out.add(a);
        Collections.sort(out, (x, y) -> Integer.compare(x.attachmentSlot, y.attachmentSlot));
        return out;
    }

    public GraphAccess depthAttachment() {
        for (GraphAccess a : accesses) {
            int m = a.access & (dev.auralite.render.rhi.GfxEnums.Access.DEPTH_STENCIL_WRITE
                    | dev.auralite.render.rhi.GfxEnums.Access.DEPTH_STENCIL_READ);
            if (m != 0) return a;
        }
        return null;
    }

    @Override public String toString() { return name + "(" + type + ")"; }
}
