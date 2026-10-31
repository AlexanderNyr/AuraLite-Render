package dev.auralite.render.rhi;

/**
 * Pipeline stages a pack may supply.
 *
 * <p>Everything past {@code FRAGMENT} is exactly what Iris cannot express: a
 * shader pack in AuraLite-Render may ship compute, task/mesh and ray-tracing
 * stages, and the native pack format declares which of them it needs so the
 * loader can refuse the pack on hardware that lacks the capability instead of
 * failing at first draw.
 */
public enum GfxStage {
    VERTEX("vert", false),
    TESSELLATION_CONTROL("tesc", true),
    TESSELLATION_EVALUATION("tese", true),
    GEOMETRY("geom", true),
    FRAGMENT("frag", false),
    COMPUTE("comp", false),
    TASK("task", true),
    MESH("mesh", true),
    RAYGEN("rgen", true),
    MISS("miss", true),
    ANY_HIT("rahit", true),
    CLOSEST_HIT("rchit", true),
    INTERSECTION("rint", true);

    private final String extension;
    private final boolean optional;

    GfxStage(String extension, boolean optional) {
        this.extension = extension;
        this.optional = optional;
    }

    /** File extension used by both pack formats ({@code water.comp}, {@code water.frag}). */
    public String extension() { return extension; }

    /** Stages a graphics pipeline must have: VERTEX and FRAGMENT. */
    public boolean optional() { return optional; }

    public boolean isGraphics() {
        switch (this) {
            case VERTEX: case TESSELLATION_CONTROL: case TESSELLATION_EVALUATION:
            case GEOMETRY: case FRAGMENT: case TASK: case MESH:
                return true;
            default:
                return false;
        }
    }

    public boolean isRayTracing() {
        switch (this) {
            case RAYGEN: case MISS: case ANY_HIT: case CLOSEST_HIT: case INTERSECTION:
                return true;
            default:
                return false;
        }
    }

    /** Bit used in capability bitmasks and pipeline layout hashing. */
    public int bit() { return 1 << ordinal(); }

    public static GfxStage fromExtension(String ext) {
        if (ext == null) return null;
        String e = ext.trim().toLowerCase(java.util.Locale.ROOT);
        if (e.startsWith(".")) e = e.substring(1);
        // Iris/OptiFine legacy spellings first, so LEGACY mode keeps working.
        if (e.equals("vsh") || e.equals("vs") || e.equals("glsl.vert")) return VERTEX;
        if (e.equals("fsh") || e.equals("fs") || e.equals("glsl.frag")) return FRAGMENT;
        if (e.equals("gsh")) return GEOMETRY;
        if (e.equals("csh")) return COMPUTE;
        for (GfxStage s : values()) if (s.extension.equals(e)) return s;
        return null;
    }
}
