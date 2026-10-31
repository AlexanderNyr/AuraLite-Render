package dev.auralite.render.graph;

import dev.auralite.render.rhi.DeviceLimits;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxEnums.Access;
import dev.auralite.render.rhi.GfxEnums.PassType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builder for one frame's dependency graph.
 *
 * <p>Rebuilt cheaply whenever the pack, the window size or the enabled options
 * change, then compiled once and executed every frame. Compilation is pure CPU
 * work with no device calls, which is what makes the whole thing testable on a
 * machine with no GPU.
 */
public final class RenderGraph {

    /** Fluent per-pass declaration handed to a setup callback. */
    public interface Setup {
        void setup(PassBuilder pass);
    }

    /** Per-pass builder: declares accesses, then the body. */
    public static final class PassBuilder {
        private final GraphPass pass;
        private final RenderGraph graph;
        private int nextColorSlot = 0;

        PassBuilder(RenderGraph graph, GraphPass pass) {
            this.graph = graph;
            this.pass = pass;
        }

        public PassBuilder read(String resource, int access) {
            return read(graph.require(resource), access);
        }

        public PassBuilder read(GraphResource r, int access) {
            pass.addAccess(new GraphAccess(r, access, -1, 0));
            return this;
        }

        public PassBuilder write(String resource, int access) {
            return write(graph.require(resource), access);
        }

        public PassBuilder write(GraphResource r, int access) {
            pass.addAccess(new GraphAccess(r, access, -1, 0));
            return this;
        }

        /** Declares a colour attachment in declaration order (slot 0, 1, 2...). */
        public PassBuilder color(String resource, int loadOpClear) {
            GraphResource r = graph.require(resource);
            int access = Access.COLOR_ATTACHMENT_WRITE | (loadOpClear != 0 ? Access.COLOR_ATTACHMENT_READ : 0);
            pass.addAccess(new GraphAccess(r, access, nextColorSlot++, 0));
            return this;
        }

        public PassBuilder color(String resource) { return color(resource, 0); }

        /** Declares a depth attachment; at most one per pass. */
        public PassBuilder depth(String resource, boolean write) {
            GraphResource r = graph.require(resource);
            int access = Access.DEPTH_STENCIL_READ | (write ? Access.DEPTH_STENCIL_WRITE : 0);
            pass.addAccess(new GraphAccess(r, access, -1, 0));
            return this;
        }

        /** Declares a mip level write, used by the bloom pyramid and the cloud LUTs. */
        public PassBuilder writeMip(String resource, int mip, int access) {
            pass.addAccess(new GraphAccess(graph.require(resource), access, -1, mip));
            return this;
        }

        /** Reads the previous frame's side of a history pair. */
        public PassBuilder readHistory(GraphHistory h, int access) {
            return read(h.previous(), access);
        }

        /**
         * Attaches this frame's side of a history pair as a colour target.
         *
         * <p>Distinct from {@link #writeHistory}: this one registers the
         * attachment slot, so a raster pass that resolves into a TAA history is
         * a legal raster pass and {@code beginRasterPass()} finds its target.
         */
        public PassBuilder colorHistory(GraphHistory h) {
            return color(h.currentName());
        }

        /** Writes this frame's side of a history pair, for compute and copy passes. */
        public PassBuilder writeHistory(GraphHistory h, int access) {
            return write(h.current(), access);
        }

        /** Compute work-group counts; validated against device limits at compile. */
        public PassBuilder dispatch(int gx, int gy, int gz) {
            pass.setDispatch(gx, gy, gz);
            return this;
        }

        /** The pass only exists when this pack option is enabled. */
        public PassBuilder when(String optionName, boolean enabled) {
            pass.addConditional(optionName);
            pass.setEnabled(enabled, enabled ? "" : "option '" + optionName + "' is off");
            return this;
        }

        /** Marks the pass as producing something consumed outside the graph. */
        /**
         * Marks this pass as load-bearing even though nothing in the graph
         * reads its output. See {@link GraphPass#isKept()}.
         */
        public PassBuilder keep(String why) { pass.setKept(true, why); return this; }

        public PassBuilder external() {
            pass.setExternal(true);
            return this;
        }

        public PassBuilder body(GraphPass.Executor e) {
            pass.setExecutor(e);
            return this;
        }
    }

    private final String name;
    private final Map<String, GraphResource> resources = new LinkedHashMap<String, GraphResource>();
    private final List<GraphPass> passes = new ArrayList<GraphPass>();
    private final List<GraphHistory> histories = new ArrayList<GraphHistory>();

    public RenderGraph(String name) { this.name = name; }

    public String name() { return name; }

    // ------------------------------------------------------------- resources
    public GraphResource addTexture(String name, GfxDesc.Image desc) {
        checkName(name);
        if (resources.containsKey(name)) {
            throw new GraphException("resource '" + name + "' declared twice");
        }
        GraphResource r = GraphResource.texture(name, desc);
        r.setIndex(resources.size());
        resources.put(name, r);
        return r;
    }

    public GraphResource addBuffer(String name, GfxDesc.Buffer desc) {
        checkName(name);
        if (resources.containsKey(name)) {
            throw new GraphException("resource '" + name + "' declared twice");
        }
        GraphResource r = GraphResource.buffer(name, desc);
        r.setIndex(resources.size());
        resources.put(name, r);
        return r;
    }

    /** A texture the graph does not own: the vanilla framebuffer, a pack asset. */
    public GraphResource importTexture(String name, GfxDesc.Image desc) {
        checkName(name);
        if (resources.containsKey(name)) {
            throw new GraphException("resource '" + name + "' declared twice");
        }
        GraphResource r = GraphResource.importedTexture(name, desc);
        r.setIndex(resources.size());
        resources.put(name, r);
        return r;
    }

    public GraphResource importBuffer(String name, GfxDesc.Buffer desc) {
        checkName(name);
        if (resources.containsKey(name)) {
            throw new GraphException("resource '" + name + "' declared twice");
        }
        GraphResource r = GraphResource.importedBuffer(name, desc);
        r.setIndex(resources.size());
        resources.put(name, r);
        return r;
    }

    public GraphResource find(String name) { return resources.get(name); }

    public GraphResource require(String name) {
        GraphResource r = resources.get(name);
        if (r == null) {
            throw new GraphException("unknown resource '" + name + "'");
        }
        return r;
    }

    public boolean hasResource(String name) { return resources.containsKey(name); }
    public List<GraphResource> resources() {
        return Collections.unmodifiableList(new ArrayList<GraphResource>(resources.values()));
    }
    public int resourceCount() { return resources.size(); }

    // ---------------------------------------------------------------- passes
    public PassBuilder addPass(String name, PassType type) {
        checkName(name);
        for (GraphPass p : passes) {
            if (p.name().equals(name)) throw new GraphException("pass '" + name + "' declared twice");
        }
        GraphPass p = new GraphPass(name, type, passes.size());
        passes.add(p);
        return new PassBuilder(this, p);
    }

    public PassBuilder addRasterPass(String name) { return addPass(name, PassType.RASTER); }
    public PassBuilder addComputePass(String name) { return addPass(name, PassType.COMPUTE); }
    public PassBuilder addCopyPass(String name) { return addPass(name, PassType.COPY); }

    public PassBuilder addPass(String name, PassType type, Setup setup) {
        PassBuilder b = addPass(name, type);
        setup.setup(b);
        return b;
    }

    // --------------------------------------------------------------- history
    /**
     * Declares a ping-pong pair for temporal feedback (TAA, reprojection,
     * accumulated SSR, temporal cloud noise). Reading last frame's output is
     * not a cycle in this graph; it is two resources and a swap at frame end.
     */
    public GraphHistory addHistory(String name, GfxDesc.Image desc) {
        GraphHistory h = GraphHistory.create(this, name, desc);
        histories.add(h);
        return h;
    }

    public List<GraphHistory> histories() { return Collections.unmodifiableList(histories); }

    public List<GraphPass> passes() { return Collections.unmodifiableList(passes); }
    public int passCount() { return passes.size(); }

    // --------------------------------------------------------------- compile
    /** Compiles against a conservative limit profile; used by tests and lint. */
    public CompiledGraph compile(int width, int height) {
        return compile(width, height, new DeviceLimits());
    }

    public CompiledGraph compile(int width, int height, DeviceLimits limits) {
        return new GraphCompiler(this, width, height, limits).compile();
    }

    private static void checkName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new GraphException("empty resource or pass name");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '/';
            if (!ok) {
                throw new GraphException("illegal character '" + c + "' in name '" + name
                        + "'; names are used as GLSL identifiers and debug markers");
            }
        }
    }
}
