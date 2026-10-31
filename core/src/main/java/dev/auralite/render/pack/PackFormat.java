package dev.auralite.render.pack;

/**
 * The two pack modes.
 *
 * <p>{@link #LEGACY_IRIS} reads an OptiFine/Iris pack exactly as shipped:
 * {@code shaders.properties}, filename-derived program names, scalar
 * CPU-evaluated custom uniforms. It exists so AuraLite-Shaders, Complementary
 * and BSL keep working on day one, and it is the lane the parity gate measures
 * against.
 *
 * <p>{@link #NATIVE} reads {@code pack.aurapack}: an explicit resource table, a
 * declared render-graph DAG, compute passes, a required-capability list and a
 * versioned API level. Nothing is inferred from filenames.
 *
 * <p>A directory containing both is loaded as NATIVE and the legacy tree is
 * ignored, with that decision recorded in the load report rather than made
 * silently.
 */
public enum PackFormat {
    LEGACY_IRIS("shaders.properties", "Iris/OptiFine compatible"),
    NATIVE("pack.aurapack", "AuraLite-Render native");

    private final String markerFile;
    private final String description;

    PackFormat(String markerFile, String description) {
        this.markerFile = markerFile;
        this.description = description;
    }

    public String markerFile() { return markerFile; }
    public String description() { return description; }
}
