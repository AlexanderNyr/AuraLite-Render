package dev.auralite.render.pack;

import dev.auralite.render.rhi.GfxCapability;
import dev.auralite.render.rhi.GfxDevice;
import dev.auralite.render.rhi.GfxStage;
import dev.auralite.render.rhi.ShaderSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads a pack from a {@link PackSource} into a {@link PackManifest}.
 *
 * <p>Detection, parsing, program discovery and validation happen here and only
 * here, so a pack that cannot run is refused with a named reason before a
 * single shader is compiled. In particular the capability contract is checked
 * against the device up front: a pack that needs {@code COMPUTE_SHADERS} on a
 * driver that does not have them is told so, rather than producing a black
 * screen and a confused log line three passes in.
 */
public final class PackLoader {

    /** Dimensions a legacy pack may specialise for, matching Iris's directory names. */
    public static final List<String> LEGACY_WORLD_DIRS =
            Collections.unmodifiableList(Arrays.asList("", "world-1/", "world1/", "world0/"));

    private final PackSource source;
    private final PackManifest manifest = new PackManifest();
    private final PackLoadReport report = new PackLoadReport();

    public PackLoader(PackSource source) {
        this.source = source;
        this.manifest.origin = source.origin();
    }

    public PackManifest manifest() { return manifest; }
    public PackLoadReport report() { return report; }

    /** Detects the format without loading anything. */
    public static PackFormat detect(PackSource source) {
        if (source.exists(PackFormat.NATIVE.markerFile())) return PackFormat.NATIVE;
        if (source.exists("shaders/" + PackFormat.LEGACY_IRIS.markerFile())) return PackFormat.LEGACY_IRIS;
        if (source.exists(PackFormat.LEGACY_IRIS.markerFile())) return PackFormat.LEGACY_IRIS;
        // A pack with neither marker is still loadable as legacy if it has programs.
        if (!source.list("shaders/").isEmpty()) return PackFormat.LEGACY_IRIS;
        return null;
    }

    /** Full load. Returns false when the pack was refused. */
    public boolean load() {
        return load(null);
    }

    /**
     * Full load, validating against {@code device} when one is supplied.
     * Tests pass {@code null} and get format-level validation only.
     */
    public boolean load(GfxDevice device) {
        long t0 = System.nanoTime();
        PackFormat format = detect(source);
        if (format == null) {
            report.error(source.origin(), "neither '" + PackFormat.NATIVE.markerFile() + "' nor a '"
                    + "shaders/' tree was found; this does not look like a shader pack");
            report.setLoadMicros((System.nanoTime() - t0) / 1000L);
            return false;
        }
        manifest.format = format;

        if (format == PackFormat.NATIVE) {
            if (source.exists("shaders/" + PackFormat.LEGACY_IRIS.markerFile())) {
                report.note("pack ships both trees; loaded as NATIVE and the legacy tree was ignored");
            }
            NativePackParser.parse(source.readText(PackFormat.NATIVE.markerFile()), manifest, report);
        } else {
            String props = source.readText("shaders/" + PackFormat.LEGACY_IRIS.markerFile());
            if (props == null) props = source.readText(PackFormat.LEGACY_IRIS.markerFile());
            LegacyPropertiesParser.parse(props, manifest, report);
            applyLegacyDefaults(manifest);
        }

        if (manifest.apiLevel > PackManifest.CURRENT_API_LEVEL) {
            report.error("meta", "pack requires API level " + manifest.apiLevel
                    + " but this loader implements " + PackManifest.CURRENT_API_LEVEL);
        }

        discoverPrograms();
        validate(device);

        report.setLoadMicros((System.nanoTime() - t0) / 1000L);
        if (manifest.name.equals("<unnamed>")) manifest.name = guessName();
        return report.ok();
    }

    private String guessName() {
        String o = source.origin();
        int slash = Math.max(o.lastIndexOf('/'), o.lastIndexOf('\\'));
        String base = slash >= 0 ? o.substring(slash + 1) : o;
        if (base.endsWith(".zip")) base = base.substring(0, base.length() - 4);
        return base.isEmpty() ? "<unnamed>" : base;
    }

    /** LEGACY packs get the settings Iris assumes when the file does not say. */
    private static void applyLegacyDefaults(PackManifest m) {
        if (!m.settings().containsKey("shadowDistance")) m.settings().put("shadowDistance", "128");
        if (!m.settings().containsKey("shadowMapResolution")) m.settings().put("shadowMapResolution", "2048");
        // Iris exposes these to every legacy pack; declaring them keeps the
        // option table honest about what a pack can actually toggle.
        for (String[] def : new String[][] {
                { "AURALITE_SPATIAL_BIOME", "false", "Evaluate biome-dependent uniforms per cell" } }) {
            if (!m.hasOption(def[0])) {
                PackOption o = new PackOption(def[0], PackOption.Kind.BOOL)
                        .label(def[2]).defaultValue(def[1]).tab("AuraLite-Render");
                o.choice("true", "On");
                o.choice("false", "Off");
                m.addOption(o);
            }
        }
    }

    // -------------------------------------------------------------- programs
    private void discoverPrograms() {
        Set<String> names = new LinkedHashSet<String>();
        if (manifest.format == PackFormat.NATIVE) {
            // A native pack declares its programs and their stages explicitly in
            // [programs]. Inferring them from filenames would reintroduce exactly
            // the coupling the format exists to remove, and would silently bind
            // files the pack never mentioned.
            bindNativeProgramSources();
            if (manifest.programCount() == 0) {
                report.warning("[programs]", "no programs declared");
            }
            return;
        }
        for (String dir : LEGACY_WORLD_DIRS) {
            for (String path : source.list("shaders/" + dir)) {
                String file = path.substring(path.lastIndexOf('/') + 1);
                int dot = file.lastIndexOf('.');
                if (dot <= 0) continue;
                String ext = file.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
                GfxStage stage = GfxStage.fromExtension(ext);
                if (stage == null) continue;
                String base = file.substring(0, dot);
                names.add(base);
                PackProgram p = manifest.program(base);
                if (p == null) { p = new PackProgram(base); manifest.addProgram(p); }
                String text = source.readText(path);
                if (text == null) {
                    report.warning(path, "listed but unreadable");
                    continue;
                }
                report.countProgramFile(text.length());
                p.stage(stage, ShaderSource.glsl(stage, text, path));
            }
        }
        if (names.isEmpty() && manifest.programCount() == 0) {
            report.warning("shaders/", "no program sources found");
        }
    }

    /** Resolves every {@code [programs]} declaration to real shader sources. */
    private void bindNativeProgramSources() {
        for (PackProgram p : manifest.programs()) {
            for (String path : p.sourcePaths) {
                String file = path.substring(path.lastIndexOf('/') + 1);
                int dot = file.lastIndexOf('.');
                if (dot <= 0) {
                    report.error(path, "program source has no stage extension");
                    continue;
                }
                GfxStage stage = GfxStage.fromExtension(file.substring(dot + 1));
                if (stage == null) {
                    report.error(path, "unknown stage extension in '" + file + "'");
                    continue;
                }
                String text = source.readText(path);
                if (text == null) {
                    report.error(path, "declared by [programs] but not present in the pack");
                    continue;
                }
                report.countProgramFile(text.length());
                p.stage(stage, ShaderSource.glsl(stage, text, path));
            }
        }
        for (GraphSpec.PassSpec pass : manifest.graph.passes()) {
            if (pass.program.isEmpty()) continue;
            PackProgram p = manifest.program(pass.program);
            if (p == null) {
                report.error("[graph]", "pass '" + pass.name + "' refers to program '"
                        + pass.program + "', which is not declared");
                continue;
            }
            if (p.boundPass.isEmpty()) p.boundPass = pass.name;
            if (pass.isCompute() && !p.hasStage(GfxStage.COMPUTE)) {
                report.error("[graph]", "compute pass '" + pass.name + "' needs program '"
                        + pass.program + "' with a compute stage");
            }
            if (!pass.isCompute() && !pass.isCopy() && !p.isRasterComplete()) {
                // A raster program with no vertex stage cannot issue geometry at
                // all, on either backend, so it is a malformed pack rather than
                // a thin one. A missing fragment stage is normal and legal: the
                // shadow passes of a legacy pack are depth-only by design.
                if (!p.hasStage(GfxStage.VERTEX)) {
                    report.error("[graph]", "raster pass '" + pass.name + "' is bound to program '"
                            + pass.program + "', which is missing a vertex stage and cannot draw");
                } else {
                    report.warning("[graph]", "pass '" + pass.name + "' program '" + pass.program
                            + "' is missing: " + p.missingStages());
                }
            }
        }
        for (String ref : manifest.graph.danglingReferences()) {
            report.error("[graph]", "pass references an undeclared resource: " + ref);
        }
    }

    // ------------------------------------------------------------ validation
    private void validate(GfxDevice device) {
        if (device == null) {
            report.note("no device supplied; capability validation skipped");
        } else {
            List<GfxCapability> missing = new ArrayList<GfxCapability>();
            for (GfxCapability c : manifest.requiredCapabilities) {
                if (!device.hasCapability(c)) missing.add(c);
            }
            if (!missing.isEmpty()) {
                report.error("capabilities", "device '" + device.deviceName() + "' lacks " + missing
                        + "; this pack cannot run here");
            } else if (!manifest.requiredCapabilities.isEmpty()) {
                report.note("all " + manifest.requiredCapabilities.size() + " required capabilities present");
            }
        }

        if (manifest.format == PackFormat.NATIVE) {
            if (manifest.graph.passes().isEmpty()) {
                report.error("[graph]", "a native pack must declare at least one pass");
            }
            if (manifest.graph.presentResource.isEmpty()) {
                report.warning("[graph]", "no 'present =' resource declared; the last external pass is used");
            }
            boolean anyCompute = false;
            for (GraphSpec.PassSpec p : manifest.graph.passes()) if (p.isCompute()) anyCompute = true;
            if (anyCompute) manifest.requiredCapabilities.add(GfxCapability.COMPUTE_SHADERS);
            for (GraphSpec.ResourceSpec r : manifest.graph.resources()) {
                if (r.is3d) manifest.requiredCapabilities.add(GfxCapability.TEXTURE_3D);
                if (r.format.isInteger()) manifest.requiredCapabilities.add(GfxCapability.INTEGER_TEXTURES);
            }
            if (device != null) {
                for (GfxCapability c : manifest.requiredCapabilities) {
                    if (!device.hasCapability(c)) {
                        report.error("capabilities", "derived requirement " + c
                                + " is not supported by '" + device.deviceName() + "'");
                    }
                }
            }
        } else {
            if (manifest.programCount() == 0) {
                report.error("shaders/", "a legacy pack must contain at least one program");
            }
            boolean hasFinal = manifest.hasProgram("final") || manifest.hasProgram("composite");
            if (!hasFinal) {
                report.warning("shaders/", "no 'final' or 'composite' program; the pack cannot reach the screen");
            }
        }

        for (String problem : manifest.customUniforms.problems()) {
            report.warning("custom uniforms", problem);
        }
    }

    /** Programs a LEGACY pack is expected to have, used by the parity gate. */
    /**
     * The program names an OptiFine/Iris pack can actually ship, spelled the way
     * they appear on disk -- which is also the way this loader discovers them.
     *
     * <p>An earlier version of this list mixed in the renderer's internal names
     * ("terrain_solid", "sky_basic"), which no pack ever writes as a filename.
     * Coverage against that list reported 3 of 21 for a pack that shipped 6, so
     * the number was not merely imprecise, it was measuring the wrong thing.
     */
    public static final List<String> LEGACY_CORE_PROGRAMS = Collections.unmodifiableList(Arrays.asList(
            "shadow", "shadow_solid", "shadow_cutout", "shadow_water", "shadow_terrain",
            "shadow_entities", "shadow_block", "shadow_beam",
            "gbuffers_basic", "gbuffers_textured", "gbuffers_textured_lit",
            "gbuffers_skybasic", "gbuffers_skytextured", "gbuffers_skybasic_textured",
            "gbuffers_clouds", "gbuffers_weather", "gbuffers_water", "gbuffers_block",
            "gbuffers_beaconbeam", "gbuffers_entity", "gbuffers_entities_translucent",
            "gbuffers_armor_glint", "gbuffers_spidereyes",
            "gbuffers_terrain", "gbuffers_terrain_solid", "gbuffers_terrain_cutout",
            "gbuffers_terrain_cutout_mip", "gbuffers_hand", "gbuffers_hand_water",
            "gbuffers_crumbling", "gbuffers_damagedblock", "gbuffers_item", "gbuffers_line",
            "gbuffers_particles", "gbuffers_particles_translucent", "gbuffers_setblock",
            "gbuffers_screen",
            "deferred", "composite", "final"));

    /** Which of the expected legacy programs this pack actually supplies. */
    public List<String> legacyCoverage() {
        List<String> out = new ArrayList<String>();
        for (String n : LEGACY_CORE_PROGRAMS) {
            PackProgram p = manifest.program(n);
            if (p != null && p.stageCount() > 0) out.add(n);
        }
        return out;
    }
}
