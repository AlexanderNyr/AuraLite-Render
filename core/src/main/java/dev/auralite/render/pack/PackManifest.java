package dev.auralite.render.pack;

import dev.auralite.render.rhi.GfxCapability;
import dev.auralite.render.uniform.CustomUniformTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything a pack declares, in one object, whichever mode it came from. */
public final class PackManifest {
    /** API level this loader implements; packs above it are refused. */
    public static final int CURRENT_API_LEVEL = 1;

    public String name = "<unnamed>";
    public String version = "0.0.0";
    public String author = "";
    public String description = "";
    public int apiLevel = 1;
    public PackFormat format = PackFormat.LEGACY_IRIS;
    public String origin = "";

    // ---- capability contract -------------------------------------------
    public final Set<GfxCapability> requiredCapabilities = EnumSet.noneOf(GfxCapability.class);

    // ---- options --------------------------------------------------------
    private final Map<String, PackOption> options = new LinkedHashMap<String, PackOption>();
    private final Map<String, String> optionValues = new LinkedHashMap<String, String>();

    // ---- programs -------------------------------------------------------
    private final Map<String, PackProgram> programs = new LinkedHashMap<String, PackProgram>();

    // ---- legacy surface -------------------------------------------------
    public final CustomUniformTable customUniforms = new CustomUniformTable();
    /** Iris {@code colortex<N>Format=}. */
    private final Map<String, String> legacyFormats = new LinkedHashMap<String, String>();
    /** Iris {@code customTexture.<name>=<path>}. */
    private final Map<String, String> customTextures = new LinkedHashMap<String, String>();
    /** Iris {@code shadowDistance}, {@code shadowMapResolution} and friends. */
    private final Map<String, String> settings = new LinkedHashMap<String, String>();
    /** Iris {@code sliders=} list, in declared order. */
    private final List<String> sliderOrder = new ArrayList<String>();
    /** Iris {@code screen[Tab]=} layout, tab -> option names in order. */
    private final Map<String, List<String>> screen = new LinkedHashMap<String, List<String>>();

    // ---- native surface -------------------------------------------------
    public final GraphSpec graph = new GraphSpec();
    /** AuraLite-Render extension: evaluate biome-dependent legacy uniforms per cell. */
    public boolean spatialBiomeUniforms;
    /** How many biomes the pack wants exposed per sample (1..4). */
    public int biomeSampleEntries = 4;

    // ---- options API ----------------------------------------------------
    public PackOption addOption(PackOption o) {
        options.put(o.name, o);
        if (!optionValues.containsKey(o.name)) optionValues.put(o.name, o.defaultValue);
        return o;
    }
    public PackOption option(String name) { return options.get(name); }
    public boolean hasOption(String name) { return options.containsKey(name); }
    public List<PackOption> options() {
        return Collections.unmodifiableList(new ArrayList<PackOption>(options.values()));
    }
    public int optionCount() { return options.size(); }

    public String optionValue(String name) {
        String v = optionValues.get(name);
        if (v != null) return v;
        PackOption o = options.get(name);
        return o == null ? "" : o.defaultValue;
    }

    public PackManifest setOption(String name, String value) {
        PackOption o = options.get(name);
        if (o != null && !o.accepts(value)) {
            throw new IllegalArgumentException("option '" + name + "' does not accept '" + value + "'");
        }
        optionValues.put(name, value);
        return this;
    }

    public Map<String, String> resolvedOptions() { return Collections.unmodifiableMap(optionValues); }

    /** True when a boolean/define option is currently on. */
    public boolean optionEnabled(String name) {
        String v = optionValue(name);
        return "true".equals(v) || "1".equals(v) || "on".equalsIgnoreCase(v);
    }

    /** The {@code #define} lines for the current option selection. */
    public List<String> activeDefines() {
        List<String> out = new ArrayList<String>();
        for (Map.Entry<String, String> e : optionValues.entrySet()) {
            PackOption o = options.get(e.getKey());
            if (o == null) continue;
            String d = o.defineFor(e.getValue());
            if (!d.isEmpty()) out.add(d);
        }
        return out;
    }

    // ---- programs API ---------------------------------------------------
    public PackProgram addProgram(PackProgram p) { programs.put(p.name, p); return p; }
    public PackProgram program(String name) { return programs.get(name); }
    public boolean hasProgram(String name) { return programs.containsKey(name); }
    public List<PackProgram> programs() {
        return Collections.unmodifiableList(new ArrayList<PackProgram>(programs.values()));
    }
    public int programCount() { return programs.size(); }

    // ---- legacy maps ----------------------------------------------------
    public Map<String, String> legacyFormats() { return legacyFormats; }
    public Map<String, String> customTextures() { return customTextures; }
    public Map<String, String> settings() { return settings; }
    public List<String> sliderOrder() { return sliderOrder; }
    public Map<String, List<String>> screen() { return screen; }

    public int shadowDistance() {
        String v = settings.get("shadowDistance");
        try { return v == null ? 128 : (int) Double.parseDouble(v.trim()); }
        catch (NumberFormatException e) { return 128; }
    }

    public int shadowMapResolution() {
        String v = settings.get("shadowMapResolution");
        try { return v == null ? 2048 : Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return 2048; }
    }

    @Override public String toString() {
        return name + " v" + version + " (" + format + ", api " + apiLevel + ", "
                + programs.size() + " programs, " + options.size() + " options)";
    }
}
