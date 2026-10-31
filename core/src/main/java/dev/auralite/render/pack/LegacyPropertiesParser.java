package dev.auralite.render.pack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parser for {@code shaders.properties}, the OptiFine/Iris pack configuration.
 *
 * <p>The lines that matter are handled explicitly and everything else is
 * reported as unparsed rather than dropped silently, so a pack that relies on
 * something this loader does not implement yet says so at load time.
 */
public final class LegacyPropertiesParser {

    /** Setting keys copied verbatim into {@link PackManifest#settings()}. */
    private static final List<String> SETTINGS = Arrays.asList(
            "shadowDistance", "shadowDistanceRenderMul", "shadowIntervalSize", "shadowMapResolution",
            "shadowMapFov", "shadowHardwareFiltering", "shadowculling", "separateAo", "oldLighting",
            "underwaterOverlay", "oldHandLight", "dynamicHandLight", "sunPathRotation", "cloudShadow",
            "prepareAhead", "rain.depth", "customtex", "vignette", "customTex", "maxTAA",
            "sliders", "screen.columns", "screen.order", "hand.depth", "water.shadow", "ambientOcclusionLevel");

    private LegacyPropertiesParser() { }

    /** Parses the whole file into {@code manifest}, recording findings in {@code report}. */
    public static void parse(String text, PackManifest manifest, PackLoadReport report) {
        if (text == null) {
            report.warning("shaders.properties", "absent; the pack has no configuration, defaults apply");
            return;
        }
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = stripComment(lines[i]).trim();
            if (raw.isEmpty()) continue;
            String where = "shaders.properties:" + (i + 1);
            if (!parseLine(raw, where, manifest, report)) report.unparsed(where, raw);
        }
    }

    /** Removes a trailing {@code #} comment but not one inside a quoted label. */
    static String stripComment(String line) {
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' || c == '\'') inQuote = !inQuote;
            else if (c == '#' && !inQuote) return line.substring(0, i);
        }
        return line;
    }

    private static boolean parseLine(String line, String where, PackManifest m, PackLoadReport report) {
        int eq = line.indexOf('=');
        if (eq < 0) return false;
        String key = line.substring(0, eq).trim();
        String value = line.substring(eq + 1).trim();

        // Custom uniforms and variables: handed to the expression table, which
        // also owns the "<name>.transition" form.
        if (key.startsWith("uniform.") || key.startsWith("variable.") || key.endsWith(".transition")) {
            return m.customUniforms.parseLine(line, where);
        }

        if (key.equals("sliders")) {
            for (String name : splitWs(value)) {
                m.sliderOrder().add(name);
                PackOption o = m.option(name);
                if (o == null) { o = m.addOption(new PackOption(name, PackOption.Kind.DEFINE)); }
            }
            return true;
        }

        if (key.startsWith("screen")) {
            // "screen", "screen.Atmosphere", "screen.columns", "screen.order"
            String tab = "";
            if (key.startsWith("screen.")) {
                String rest = key.substring("screen.".length());
                if (!rest.equals("columns") && !rest.equals("order")) tab = rest;
                else { m.settings().put(key, value); return true; }
            }
            List<String> list = m.screen().get(tab);
            if (list == null) { list = new ArrayList<String>(); m.screen().put(tab, list); }
            for (String token : splitWs(value)) {
                if (token.equals("<empty>") || token.equals("<profile>")) continue;
                if (token.startsWith("[") && token.endsWith("]")) continue;   // sub-column marker
                list.add(token);
                PackOption o = m.option(token);
                if (o == null) {
                    o = new PackOption(token, PackOption.Kind.DEFINE);
                    o.tab(tab);
                    m.addOption(o);
                } else if (tab != null && !tab.isEmpty() && o.screenTab.isEmpty()) {
                    o.tab(tab);
                }
            }
            return true;
        }

        if (key.startsWith("program.")) {
            String[] parts = key.split("\\.");
            if (parts.length < 3) return false;
            String programName = parts[1];
            String setting = parts[2];
            PackProgram p = m.program(programName);
            if (p == null) { p = new PackProgram(programName); m.addProgram(p); }
            if (setting.equals("enabled")) {
                boolean on = value.equalsIgnoreCase("true") || value.equals("1");
                p.explicitlyDisabled = !on;
                p.enabled = on;
                if (!on) p.disableReason = "program." + programName + ".enabled=false";
            } else if (setting.equals("blend")) {
                String[] f = splitWs(value);
                if (f.length >= 2) { p.blendSrc = f[0]; p.blendDst = f[1]; }
                else if (f.length == 1 && f[0].equalsIgnoreCase("off")) { p.blendSrc = ""; p.blendDst = ""; }
            } else if (setting.equals("composite")) {
                // "program.composite.composite.<k>=<v>" collapses to key/value.
                m.settings().put("program." + programName + "." + setting, value);
            } else {
                m.settings().put("program." + programName + "." + setting, value);
            }
            return true;
        }

        if (key.startsWith("colortex") && key.endsWith("Format")) {
            m.legacyFormats().put(key, value);
            return true;
        }

        if (key.startsWith("customTexture.")) {
            m.customTextures().put(key.substring("customTexture.".length()), value);
            return true;
        }

        // "<OPTION>.enabled = <expr>": option visibility, evaluated on the CPU.
        if (key.endsWith(".enabled")) {
            String opt = key.substring(0, key.length() - ".enabled".length());
            PackOption o = m.option(opt);
            if (o == null) { o = new PackOption(opt, PackOption.Kind.DEFINE); m.addOption(o); }
            o.comment("visible when " + value);
            return true;
        }

        for (String s : SETTINGS) {
            if (key.equals(s)) { m.settings().put(key, value); return true; }
        }

        // Block and item id maps live in their own files but some packs inline them.
        if (key.startsWith("block.") || key.startsWith("item.") || key.startsWith("entity.")) {
            m.settings().put(key, value);
            return true;
        }

        return false;
    }

    static String[] splitWs(String s) {
        if (s == null) return new String[0];
        String t = s.trim();
        if (t.isEmpty()) return new String[0];
        return t.split("\\s+");
    }
}
