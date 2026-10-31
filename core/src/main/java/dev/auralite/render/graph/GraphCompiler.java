package dev.auralite.render.graph;

import dev.auralite.render.rhi.DeviceLimits;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxEnums.Access;
import dev.auralite.render.rhi.GfxEnums.PassType;
import dev.auralite.render.rhi.GfxEnums.ResourceState;
import dev.auralite.render.rhi.GfxUsage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a declared {@link RenderGraph} into an executable {@link CompiledGraph}.
 *
 * <p>Six derivations, in order, each with a host-side gate:
 * <ol>
 *   <li>validation -- a broken pack is refused with a named reason here, not at
 *       first draw;</li>
 *   <li>dependency edges from the read/write declarations (RAW, WAR and WAW are
 *       all just "consecutive accesses where one side writes");</li>
 *   <li>cycle detection, reporting the offending path;</li>
 *   <li>culling of passes with no external output, no kept consumer and no
 *       {@link GraphPass#isKept()} marker of its own;</li>
 *   <li>deterministic topological order (Kahn, ties broken by declaration
 *       index, so two runs of the same pack compile byte-identically);</li>
 *   <li>resource lifetimes, alias groups and the barrier list.</li>
 * </ol>
 */
public final class GraphCompiler {

    private final RenderGraph graph;
    private final int width;
    private final int height;
    private final DeviceLimits limits;

    private final List<GraphPass> enabled = new ArrayList<GraphPass>();
    private final List<List<Integer>> successors = new ArrayList<List<Integer>>();
    private final List<List<Integer>> predecessors = new ArrayList<List<Integer>>();
    private final GraphStats stats = new GraphStats();
    private final Map<GraphResource, GfxDesc.Image> effectiveDescs =
            new LinkedHashMap<GraphResource, GfxDesc.Image>();

    public GraphCompiler(RenderGraph graph, int width, int height, DeviceLimits limits) {
        this.graph = graph;
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.limits = limits == null ? new DeviceLimits() : limits;
    }

    public CompiledGraph compile() {
        long t0 = System.nanoTime();
        stats.declaredPasses = graph.passCount();
        stats.declaredResources = graph.resourceCount();

        for (GraphPass p : graph.passes()) {
            if (!p.isEnabled()) {
                stats.disabled.add(p.name() + " -- " + p.disableReason());
                continue;
            }
            enabled.add(p);
        }
        stats.enabledPasses = enabled.size();
        for (int i = 0; i < enabled.size(); i++) {
            successors.add(new ArrayList<Integer>());
            predecessors.add(new ArrayList<Integer>());
        }

        validate();
        resolveEffectiveDescs();
        buildEdges();
        detectCycles();

        int[] order = cullAndOrder();

        List<GraphPass> ordered = new ArrayList<GraphPass>();
        for (int idx : order) ordered.add(enabled.get(idx));

        Map<GraphResource, int[]> accessSlots = indexPassPositions(ordered);
        List<ResourceLifetime> lifetimes = computeLifetimes(ordered, accessSlots);
        List<List<Barrier>> barriers = deriveBarriers(ordered);
        computeAliasing(lifetimes);

        stats.compileMicros = (System.nanoTime() - t0) / 1000L;
        return new CompiledGraph(graph, width, height, limits, ordered, barriers,
                lifetimes, effectiveDescs, stats);
    }

    // ------------------------------------------------------------- validation
    private void validate() {
        for (GraphPass p : enabled) {
            if (p.accesses().isEmpty()) {
                throw new GraphException("pass declares no resource access; a pass must read or write "
                        + "something or be marked external()", p.name());
            }
            if (p.executor() == null && !p.isExternal()) {
                throw new GraphException("pass has no body and is not external()", p.name());
            }
            if (p.type() == PassType.RASTER) {
                List<GraphAccess> colors = p.colorAttachments();
                if (colors.isEmpty() && p.depthAttachment() == null) {
                    throw new GraphException("raster pass has neither a colour nor a depth attachment", p.name());
                }
                if (colors.size() > limits.maxColorAttachments) {
                    throw new GraphException("raster pass uses " + colors.size() + " colour attachments, "
                            + "device limit is " + limits.maxColorAttachments, p.name());
                }
                Set<Integer> slots = new HashSet<Integer>();
                for (GraphAccess a : colors) {
                    if (!a.resource.isTexture()) {
                        throw new GraphException("colour attachment '" + a.resource.name()
                                + "' is a buffer", p.name());
                    }
                    if (!slots.add(a.attachmentSlot)) {
                        throw new GraphException("duplicate colour attachment slot " + a.attachmentSlot, p.name());
                    }
                }
                GraphAccess d = p.depthAttachment();
                if (d != null && !d.resource.format().isDepthStencil()) {
                    throw new GraphException("depth attachment '" + d.resource.name() + "' has format "
                            + d.resource.format() + ", which is not a depth format", p.name());
                }
                stats.rasterPasses++;
            } else if (p.type() == PassType.COMPUTE) {
                boolean writes = false;
                for (GraphAccess a : p.accesses()) if (a.isWrite()) writes = true;
                if (!writes) {
                    stats.warnings.add("compute pass '" + p.name() + "' writes nothing");
                }
                if (p.groupsX() > limits.maxComputeWorkGroupCountX
                        || p.groupsY() > limits.maxComputeWorkGroupCountY
                        || p.groupsZ() > limits.maxComputeWorkGroupCountZ) {
                    throw new GraphException("dispatch (" + p.groupsX() + "," + p.groupsY() + ","
                            + p.groupsZ() + ") exceeds the device work-group count limit", p.name());
                }
                stats.computePasses++;
            } else if (p.type() == PassType.COPY) {
                stats.copyPasses++;
            }
        }

        for (GraphResource r : graph.resources()) {
            if (r.isImported()) { stats.importedResources++; continue; }
            if (r.isTransient()) stats.transientResources++;
            GfxDesc.Image d = r.imageDesc();
            if (d != null) {
                int maxDim = d.type == dev.auralite.render.rhi.GfxEnums.ImageType.TEXTURE_3D
                        ? limits.maxTexture3DSize : limits.maxTexture2DSize;
                if (d.width > maxDim || d.height > maxDim) {
                    throw new GraphException("resource '" + r.name() + "' is " + d.width + "x" + d.height
                            + ", device limit is " + maxDim);
                }
            }
        }

        // Reads that no enabled pass produces. Not fatal: a pack option may be off
        // and the consumer may fall back to a default, but it is always reported.
        Set<GraphResource> written = new HashSet<GraphResource>();
        for (GraphPass p : enabled) {
            for (GraphAccess a : p.accesses()) if (a.isWrite()) written.add(a.resource);
        }
        Set<GraphResource> read = new HashSet<GraphResource>();
        for (GraphPass p : enabled) {
            for (GraphAccess a : p.accesses()) if (a.isRead()) read.add(a.resource);
        }
        java.util.Set<GraphResource> historySides = new java.util.HashSet<GraphResource>();
        for (GraphHistory h : graph.histories()) {
            historySides.add(h.sideA());
            historySides.add(h.sideB());
        }
        for (GraphResource r : read) {
            if (written.contains(r) || r.isImported()) continue;
            if (historySides.contains(r)) {
                // Written on the previous frame by construction; not a defect.
                stats.warnings.add("resource '" + r.name() + "' is a history side: written last frame, "
                        + "read this frame");
                continue;
            }
            stats.warnings.add("resource '" + r.name() + "' is read but never written by an enabled pass");
        }
    }

    /** Expands framebuffer-relative sizes (0 means "match the framebuffer"). */
    private void resolveEffectiveDescs() {
        for (GraphResource r : graph.resources()) {
            GfxDesc.Image d = r.imageDesc();
            if (d == null) continue;
            if (d.width == 0 || d.height == 0) {
                GfxDesc.Image e = GfxDesc.Image.tex2d(d.width == 0 ? width : d.width,
                        d.height == 0 ? height : d.height, d.format, d.usage);
                e.type = d.type;
                e.depth = d.depth;
                e.mipLevels = d.mipLevels;
                e.arrayLayers = d.arrayLayers;
                e.samples = d.samples;
                e.domain = d.domain;
                e.debugName = d.debugName;
                effectiveDescs.put(r, e);
            } else {
                effectiveDescs.put(r, d);
            }
        }
        for (GraphResource r : graph.resources()) {
            if (r.isImported()) {
                GfxDesc.Image d = effectiveDescs.get(r);
                long b = d == null ? (r.bufferDesc() == null ? 0 : r.bufferDesc().sizeBytes) : d.approxBytes();
                stats.importedTextureBytes += b;
            } else if (r.isTexture()) {
                GfxDesc.Image d = effectiveDescs.get(r);
                stats.ownedTextureBytes += d == null ? 0 : d.approxBytes();
            } else {
                stats.bufferBytes += r.bufferDesc() == null ? 0 : r.bufferDesc().sizeBytes;
            }
        }
    }

    // ------------------------------------------------------------------ edges
    /**
     * Derives dependency edges from the read/write declarations.
     *
     * <p>Three edge kinds, and the direction comes from the semantics rather
     * than from the order the pack happened to declare things in -- a pack may
     * declare {@code present} first and the compiler still puts it last:
     *
     * <ul>
     * <li><b>RAW</b> writer &rarr; reader of the same resource;</li>
     * <li><b>WAW</b> earlier-declared writer &rarr; later-declared writer.</li>
     * </ul>
     *
     * <p>WAR is deliberately <b>not</b> an edge. A read that must happen before a
     * later write to the same resource is a false dependency: making it an edge
     * contradicts the RAW edge for the same pair and manufactures a cycle out of
     * an ordinary "read the imported framebuffer, then overwrite it" pass. Those
     * cases are ordered by the deterministic tie-break in the topological sort,
     * which prefers the lower declaration index. That was measured, not assumed:
     * with a WAR edge the two-pass graph {@code blit(reads mid) -> produce(writes
     * mid)} reported a cycle.
     *
     * <p>History pairs get a third rule: a writer of one side &rarr; a reader of
     * the other. Without it the temporal producer has no downstream consumer in
     * this frame's graph and culling deletes it.
     */
    private void buildEdges() {
        Map<GraphResource, List<Integer>> writers = new LinkedHashMap<GraphResource, List<Integer>>();
        Map<GraphResource, List<Integer>> readers = new LinkedHashMap<GraphResource, List<Integer>>();

        for (int i = 0; i < enabled.size(); i++) {
            for (GraphAccess a : enabled.get(i).accesses()) {
                List<Integer> w = writers.get(a.resource);
                if (w == null) { w = new ArrayList<Integer>(); writers.put(a.resource, w); }
                List<Integer> r = readers.get(a.resource);
                if (r == null) { r = new ArrayList<Integer>(); readers.put(a.resource, r); }
                if (a.isWrite()) {
                    if (!w.contains(i)) w.add(i);
                } else if (!r.contains(i)) {
                    r.add(i);
                }
            }
            // A pass that both reads and writes a resource is a writer only; the
            // read is inside the same pass and must not become a self-edge.
            for (GraphAccess a : enabled.get(i).accesses()) {
                if (a.isWrite()) readers.get(a.resource).remove(Integer.valueOf(i));
            }
        }

        int n = enabled.size();
        boolean[][] adj = new boolean[n][n];

        for (GraphResource res : writers.keySet()) {
            List<Integer> w = writers.get(res);
            List<Integer> r = readers.get(res);
            for (int wi : w) for (int ri : r) if (wi != ri) adj[wi][ri] = true;          // RAW
            for (int i = 0; i < w.size(); i++)                                            // WAW
                for (int j = i + 1; j < w.size(); j++) adj[w.get(i)][w.get(j)] = true;
        }

        for (GraphHistory h : graph.histories()) {
            linkSides(adj, writers, readers, h.sideA(), h.sideB());
            linkSides(adj, writers, readers, h.sideB(), h.sideA());
        }

        List<List<Integer>> succ = new ArrayList<List<Integer>>();
        List<List<Integer>> pred = new ArrayList<List<Integer>>();
        for (int i = 0; i < n; i++) { succ.add(new ArrayList<Integer>()); pred.add(new ArrayList<Integer>()); }
        long edges = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i != j && adj[i][j]) { succ.get(i).add(j); pred.get(j).add(i); edges++; }
            }
        }
        successors.clear(); successors.addAll(succ);
        predecessors.clear(); predecessors.addAll(pred);
        stats.edgeCount = edges;
    }

    private static void linkSides(boolean[][] adj, Map<GraphResource, List<Integer>> writers,
                                  Map<GraphResource, List<Integer>> readers,
                                  GraphResource from, GraphResource to) {
        List<Integer> w = writers.get(from);
        List<Integer> r = readers.get(to);
        if (w == null || r == null) return;
        for (int wi : w) for (int ri : r) if (wi != ri) adj[wi][ri] = true;
    }

    // ----------------------------------------------------------------- cycles
    private void detectCycles() {
        int n = enabled.size();
        int[] color = new int[n]; // 0 white, 1 grey, 2 black
        int[] parent = new int[n];
        Arrays.fill(parent, -1);
        for (int i = 0; i < n; i++) {
            if (color[i] == 0) {
                String cycle = dfs(i, color, parent, new ArrayList<Integer>());
                if (cycle != null) {
                    throw new GraphException("dependency cycle: " + cycle);
                }
            }
        }
    }

    private String dfs(int node, int[] color, int[] parent, List<Integer> stack) {
        color[node] = 1;
        stack.add(node);
        for (int next : successors.get(node)) {
            if (color[next] == 1) {
                StringBuilder sb = new StringBuilder();
                int from = stack.indexOf(next);
                for (int i = from; i < stack.size(); i++) {
                    if (sb.length() > 0) sb.append(" -> ");
                    sb.append(enabled.get(stack.get(i)).name());
                }
                sb.append(" -> ").append(enabled.get(next).name());
                return sb.toString();
            }
            if (color[next] == 0) {
                parent[next] = node;
                String r = dfs(next, color, parent, stack);
                if (r != null) return r;
            }
        }
        stack.remove(stack.size() - 1);
        color[node] = 2;
        return null;
    }

    // ---------------------------------------------------------- cull + order
    private int[] cullAndOrder() {
        int n = enabled.size();
        boolean[] keep = new boolean[n];
        List<Integer> work = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            if (enabled.get(i).isExternal() || enabled.get(i).isKept()) { keep[i] = true; work.add(i); }
        }
        while (!work.isEmpty()) {
            int cur = work.remove(work.size() - 1);
            for (int p : predecessors.get(cur)) {
                if (!keep[p]) { keep[p] = true; work.add(p); }
            }
        }
        List<Integer> alive = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) alive.add(i);
            else stats.culled.add(enabled.get(i).name()
                    + " -- no external output and no kept consumer");
        }
        stats.culledPasses = stats.culled.size();

        // Kahn over the kept subgraph, restricted to kept edges only.
        Set<Integer> aliveSet = new HashSet<Integer>(alive);
        Map<Integer, Integer> indeg = new HashMap<Integer, Integer>();
        for (int i : alive) {
            int d = 0;
            for (int p : predecessors.get(i)) if (aliveSet.contains(p)) d++;
            indeg.put(i, d);
        }
        List<Integer> ready = new ArrayList<Integer>();
        for (int i : alive) if (indeg.get(i) == 0) ready.add(i);
        java.util.Collections.sort(ready);

        int[] order = new int[alive.size()];
        int written = 0;
        while (!ready.isEmpty()) {
            int cur = ready.remove(0);
            order[written++] = cur;
            List<Integer> newly = new ArrayList<Integer>();
            for (int s : successors.get(cur)) {
                if (!aliveSet.contains(s)) continue;
                int d = indeg.get(s) - 1;
                indeg.put(s, d);
                if (d == 0) newly.add(s);
            }
            if (!newly.isEmpty()) {
                ready.addAll(newly);
                java.util.Collections.sort(ready);
            }
        }
        if (written != alive.size()) {
            throw new GraphException("topological sort stalled after " + written + " of "
                    + alive.size() + " passes; a cycle survived detection");
        }
        stats.orderedPasses = written;
        return order;
    }

    // -------------------------------------------------------------- lifetimes
    /** First and last pass index that touch each resource, in ordered position. */
    private Map<GraphResource, int[]> indexPassPositions(List<GraphPass> ordered) {
        Map<GraphResource, int[]> slots = new LinkedHashMap<GraphResource, int[]>();
        for (int i = 0; i < ordered.size(); i++) {
            for (GraphAccess a : ordered.get(i).accesses()) {
                int[] s = slots.get(a.resource);
                if (s == null) {
                    slots.put(a.resource, new int[] { i, i });
                } else {
                    s[0] = Math.min(s[0], i);
                    s[1] = Math.max(s[1], i);
                }
            }
        }
        return slots;
    }

    private List<ResourceLifetime> computeLifetimes(List<GraphPass> ordered,
                                                    Map<GraphResource, int[]> slots) {
        List<ResourceLifetime> out = new ArrayList<ResourceLifetime>();
        int last = Math.max(0, ordered.size() - 1);
        for (GraphResource r : graph.resources()) {
            int[] s = slots.get(r);
            int first = r.isImported() ? 0 : (s == null ? 0 : s[0]);
            int end = r.isImported() ? last : (s == null ? 0 : s[1]);
            out.add(new ResourceLifetime(r, first, end, -1));
        }
        return out;
    }

    // --------------------------------------------------------------- barriers
    private List<List<Barrier>> deriveBarriers(List<GraphPass> ordered) {
        Map<GraphResource, ResourceState> state = new HashMap<GraphResource, ResourceState>();
        Map<GraphResource, Integer> accessMask = new HashMap<GraphResource, Integer>();
        for (GraphResource r : graph.resources()) {
            if (r.isImported()) {
                boolean present = r.imageDesc() != null
                        && (r.imageDesc().usage & GfxUsage.IMAGE_PRESENT) != 0;
                state.put(r, present ? ResourceState.PRESENT : ResourceState.SHADER_READ);
                accessMask.put(r, present ? Access.MEMORY_READ : Access.SHADER_READ);
            } else {
                state.put(r, ResourceState.UNDEFINED);
                accessMask.put(r, Access.NONE);
            }
        }

        List<List<Barrier>> out = new ArrayList<List<Barrier>>();
        for (GraphPass p : ordered) {
            List<Barrier> before = new ArrayList<Barrier>();
            // Sort accesses so attachments are transitioned in slot order: the
            // resulting barrier list is stable across runs.
            List<GraphAccess> sorted = new ArrayList<GraphAccess>(p.accesses());
            java.util.Collections.sort(sorted, (x, y) -> {
                int c = Integer.compare(x.resource.index(), y.resource.index());
                if (c != 0) return c;
                return Integer.compare(x.attachmentSlot, y.attachmentSlot);
            });
            for (GraphAccess a : sorted) {
                ResourceState want = a.impliedState();
                if (want == ResourceState.UNDEFINED) continue;
                ResourceState have = state.get(a.resource);
                int srcAccess = accessMask.get(a.resource);
                if (have != want) {
                    Barrier b = new Barrier(a.resource.isBuffer() ? Barrier.Target.BUFFER : Barrier.Target.IMAGE,
                            a.resource, have, want, srcAccess, a.access, p.name());
                    if (b.isNoop()) stats.noopBarriersSuppressed++;
                    else { before.add(b); stats.barriers++; }
                    state.put(a.resource, want);
                }
                accessMask.put(a.resource, a.access);
            }
            out.add(before);
        }

        // Anything presented must end in PRESENT.
        List<Barrier> tail = new ArrayList<Barrier>();
        for (Map.Entry<GraphResource, ResourceState> e : state.entrySet()) {
            GraphResource r = e.getKey();
            boolean present = r.imageDesc() != null && (r.imageDesc().usage & GfxUsage.IMAGE_PRESENT) != 0;
            if (present && e.getValue() != ResourceState.PRESENT) {
                tail.add(new Barrier(Barrier.Target.IMAGE, r, e.getValue(), ResourceState.PRESENT,
                        accessMask.get(r), Access.MEMORY_READ, "<present>"));
                stats.barriers++;
            }
        }
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }

    // --------------------------------------------------------------- aliasing
    private void computeAliasing(List<ResourceLifetime> lifetimes) {
        List<ResourceLifetime> candidates = new ArrayList<ResourceLifetime>();
        for (ResourceLifetime l : lifetimes) {
            if (l.resource.isImported()) continue;
            if (!l.resource.isTexture()) continue;
            if (l.resource.isTransient()) continue;
            if (l.span() <= 0) continue;
            candidates.add(l);
        }
        // Longest-lived first: they anchor groups, short-lived ones pack around them.
        java.util.Collections.sort(candidates, (a, b) -> {
            int c = Integer.compare(b.span(), a.span());
            if (c != 0) return c;
            return Long.compare(b.resource.sizeBytes(), a.resource.sizeBytes());
        });

        List<List<ResourceLifetime>> groups = new ArrayList<List<ResourceLifetime>>();
        for (ResourceLifetime cand : candidates) {
            boolean placed = false;
            for (List<ResourceLifetime> g : groups) {
                boolean fits = true;
                for (ResourceLifetime m : g) {
                    if (m.overlaps(cand) || !compatible(m.resource, cand.resource)) { fits = false; break; }
                }
                if (fits) { g.add(cand); placed = true; break; }
            }
            if (!placed) {
                List<ResourceLifetime> g = new ArrayList<ResourceLifetime>();
                g.add(cand);
                groups.add(g);
            }
        }

        int realGroups = 0;
        long savings = 0;
        for (int i = 0; i < groups.size(); i++) {
            List<ResourceLifetime> g = groups.get(i);
            long max = 0, sum = 0;
            for (ResourceLifetime m : g) {
                m.setAliasGroup(g.size() > 1 ? i : -1);
                long sz = m.resource.sizeBytes();
                sum += sz;
                max = Math.max(max, sz);
            }
            if (g.size() > 1) { realGroups++; savings += sum - max; }
        }
        stats.aliasGroups = realGroups;
        stats.aliasSavingsBytes = savings;
        if (savings > 0) {
            stats.warnings.add("aliasing computed but allocations are not yet shared (PIPELINE_PLAN P4)");
        }
    }

    private static boolean compatible(GraphResource a, GraphResource b) {
        GfxDesc.Image da = a.imageDesc(), db = b.imageDesc();
        if (da == null || db == null) return false;
        return da.type == db.type && da.format == db.format && da.samples == db.samples
                && da.mipLevels == db.mipLevels;
    }
}
