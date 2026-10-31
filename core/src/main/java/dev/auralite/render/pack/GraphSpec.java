package dev.auralite.render.pack;

import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxUsage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The declarative half of a NATIVE pack: what resources exist and which pass
 * touches which.
 *
 * <p>This is the thing Iris has no equivalent of. Iris hard-codes the frame as
 * {@code shadow -> gbuffers_* -> deferred -> composite0..N -> final} and a pack
 * can only add or skip steps inside that shape. Here the pack declares the
 * shape, including compute nodes and temporal feedback, and the compiler
 * derives order, barriers and memory from it.
 */
public final class GraphSpec {

    /** How a resource's extent is decided. */
    public enum SizeKind {
        /** Matches the framebuffer; recreated on resize. */
        FULLSCREEN,
        /** A fraction of the framebuffer, e.g. half-res bloom. */
        SCALED,
        /** Fixed pixel extent. */
        FIXED,
        /** Sized from the render distance, e.g. the shadow map or biome volume. */
        SHADOW,
        /** Sized from the biome field window. */
        BIOME_VOLUME
    }

    public static final class ResourceSpec {
        public final String name;
        public GfxFormat format = GfxFormat.RGBA16F;
        public SizeKind sizeKind = SizeKind.FULLSCREEN;
        public double scale = 1.0;
        public int fixedWidth = 1, fixedHeight = 1, fixedDepth = 1;
        public int mipLevels = 1;
        public int arrayLayers = 1;
        public int usage = GfxUsage.IMAGE_SAMPLED | GfxUsage.IMAGE_COLOR_ATTACHMENT;
        public boolean history;
        public boolean imported;
        public boolean is3d;
        public String comment = "";

        public ResourceSpec(String name) { this.name = name; }

        /** Expands the declared size against a concrete framebuffer and shadow distance. */
        public int[] extent(int fbWidth, int fbHeight, int shadowMapSize, int[] biomeCells) {
            switch (sizeKind) {
                case FULLSCREEN: return new int[] { fbWidth, fbHeight, 1 };
                case SCALED: return new int[] {
                        Math.max(1, (int) Math.round(fbWidth * scale)),
                        Math.max(1, (int) Math.round(fbHeight * scale)), 1 };
                case FIXED: return new int[] { fixedWidth, fixedHeight, Math.max(1, fixedDepth) };
                case SHADOW: return new int[] { shadowMapSize, shadowMapSize, 1 };
                case BIOME_VOLUME: return biomeCells == null
                        ? new int[] { 1, 1, 1 }
                        : new int[] { biomeCells[0], biomeCells[1], biomeCells[2] };
                default: return new int[] { fbWidth, fbHeight, 1 };
            }
        }
    }

    public static final class PassSpec {
        public final String name;
        public String type = "raster";
        /** Colour attachments in slot order. */
        public final List<String> colors = new ArrayList<String>();
        public String depth = "";
        public final List<String> reads = new ArrayList<String>();
        public final List<String> writes = new ArrayList<String>();
        public String program = "";
        public String when = "";
        public boolean external;
        public int dispatchX, dispatchY, dispatchZ;
        /** Dispatch expressed as an expression of width/height, e.g. "ceil(width/8)". */
        public String dispatchExpr = "";
        public String comment = "";

        public PassSpec(String name) { this.name = name; }
        public boolean isCompute() { return "compute".equals(type); }
        public boolean isCopy() { return "copy".equals(type); }
    }

    private final List<ResourceSpec> resources = new ArrayList<ResourceSpec>();
    private final List<PassSpec> passes = new ArrayList<PassSpec>();
    /** Name of the resource that ends up on screen. */
    public String presentResource = "";

    public List<ResourceSpec> resources() { return Collections.unmodifiableList(resources); }
    public List<PassSpec> passes() { return Collections.unmodifiableList(passes); }

    public ResourceSpec addResource(ResourceSpec r) { resources.add(r); return r; }
    public PassSpec addPass(PassSpec p) { passes.add(p); return p; }

    public ResourceSpec findResource(String name) {
        for (ResourceSpec r : resources) if (r.name.equals(name)) return r;
        return null;
    }

    public PassSpec findPass(String name) {
        for (PassSpec p : passes) if (p.name.equals(name)) return p;
        return null;
    }

    /** Every name referenced by a pass that no resource declares. */
    public List<String> danglingReferences() {
        List<String> out = new ArrayList<String>();
        for (PassSpec p : passes) {
            check(p.name, p.depth, out);
            for (String s : p.colors) check(p.name, s, out);
            for (String s : p.reads) check(p.name, s, out);
            for (String s : p.writes) check(p.name, s, out);
        }
        return out;
    }

    private void check(String passName, String resource, List<String> out) {
        if (resource == null || resource.isEmpty()) return;
        String base = resource;
        int dot = base.indexOf(".cur");
        if (dot > 0) base = base.substring(0, dot);
        if (base.endsWith(".prev")) base = base.substring(0, base.length() - 5);
        if (findResource(base) == null) out.add(passName + " -> " + resource);
    }
}
