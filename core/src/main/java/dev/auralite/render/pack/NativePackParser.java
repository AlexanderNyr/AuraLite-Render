package dev.auralite.render.pack;

import dev.auralite.render.rhi.GfxCapability;
import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxUsage;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for {@code pack.aurapack}, the native format.
 *
 * <p>Line-oriented with {@code [section]} headers, {@code key = value} bodies
 * and {@code #} comments. Deliberately not a general-purpose markup language:
 * a pack author edits it in a text editor, diffs it in git, and gets a line
 * number in every error.
 *
 * <pre>
 * [meta]
 * name = AuraLite Native
 * version = 1.0.0
 * auralite.api = 1
 *
 * [capabilities]
 * require = COMPUTE_SHADERS, TEXTURE_3D, INTEGER_TEXTURES
 *
 * [biome]
 * spatialUniforms = true
 * sampleEntries = 4
 *
 * [resources]
 * scene_color  = rgba16f fullscreen
 * scene_depth  = d32_sfloat fullscreen usage=depth
 * cloud_shadow = r16f 2048x2048
 * taa          = rgba16f fullscreen history
 * biome_volume = rgba8_uint biome-volume 3d usage=sampled
 *
 * [options]
 * CLOUD_QUALITY = select "Raymarch steps" { 14=Cheap, 24=Standard, 36=High } default=24 tab=Atmosphere
 * SPATIAL_BIOME = bool "Spatial biome field" default=true
 *
 * [graph]
 * pass shadow_map  raster  depth=scene_depth            program=shadow
 * pass terrain     raster  color=scene_color depth=scene_depth program=terrain
 * pass biome_field compute write=biome_volume           program=biome_field dispatch=ceil(width/8),ceil(height/8),1
 * pass taa         raster  read=scene_color,taa.prev    color=taa.cur  program=taa
 * pass present     raster  read=taa.cur external
 * present = taa
 *
 * [programs]
 * terrain     = shaders/terrain.vert, shaders/terrain.frag
 * biome_field = shaders/biome_field.comp  local_size=8x8x1
 *
 * [screen]
 * Atmosphere = CLOUD_QUALITY
 * </pre>
 */
public final class NativePackParser {

    private NativePackParser() { }

    public static void parse(String text, PackManifest m, PackLoadReport report) {
        if (text == null) {
            report.error("pack.aurapack", "absent; a native pack must declare one");
            return;
        }
        m.format = PackFormat.NATIVE;
        String section = "";
        String[] lines = text.split("\r?\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String raw = LegacyPropertiesParser.stripComment(lines[i]).trim();
            if (raw.isEmpty()) continue;
            String where = "pack.aurapack:" + (i + 1);
            if (raw.charAt(0) == '[') {
                if (!raw.endsWith("]")) { report.error(where, "section header is not closed: " + raw); continue; }
                section = raw.substring(1, raw.length() - 1).trim().toLowerCase(java.util.Locale.ROOT);
                continue;
            }
            // A "[graph] pass ..." line carries '=' inside its own clause list
            // (color=..., program=...), so it must be recognised before the
            // key/value split -- splitting first read the whole line as one key.
            if (section.equals("graph") && raw.startsWith("pass")) {
                parsePass(raw, where, m, report);
                continue;
            }
            int eq = raw.indexOf('=');
            if (eq < 0) {
                report.error(where, "expected 'key = value', got '" + raw + "'");
                continue;
            }
            String key = raw.substring(0, eq).trim();
            String value = raw.substring(eq + 1).trim();
            if (section.equals("graph") && key.equals("pass")) {
                parsePass("pass " + value, where, m, report);
                continue;
            }
            dispatchSection(section, key, value, where, m, report);
        }
    }

    private static void dispatchSection(String section, String key, String value, String where,
                                        PackManifest m, PackLoadReport report) {
        if (section.equals("meta")) {
            if (key.equals("name")) m.name = value;
            else if (key.equals("version")) m.version = value;
            else if (key.equals("author")) m.author = value;
            else if (key.equals("description")) m.description = value;
            else if (key.equals("auralite.api") || key.equals("api")) {
                try { m.apiLevel = Integer.parseInt(value); }
                catch (NumberFormatException e) { report.error(where, "api level is not an integer: " + value); }
            } else report.warning(where, "unknown [meta] key '" + key + "'");
            return;
        }
        if (section.equals("capabilities")) {
            if (!key.equals("require") && !key.equals("optional")) {
                report.warning(where, "unknown [capabilities] key '" + key + "'");
                return;
            }
            for (String tok : value.split(",")) {
                String name = tok.trim();
                if (name.isEmpty()) continue;
                try {
                    GfxCapability c = GfxCapability.valueOf(name.toUpperCase(java.util.Locale.ROOT));
                    if (key.equals("require")) m.requiredCapabilities.add(c);
                    else report.note("optional capability recorded: " + c);
                } catch (IllegalArgumentException e) {
                    report.error(where, "unknown capability '" + name + "'");
                }
            }
            return;
        }
        if (section.equals("biome")) {
            if (key.equals("spatialUniforms")) m.spatialBiomeUniforms = truthy(value);
            else if (key.equals("sampleEntries")) {
                try {
                    m.biomeSampleEntries = Integer.parseInt(value);
                    if (m.biomeSampleEntries < 1 || m.biomeSampleEntries > 4) {
                        report.error(where, "sampleEntries must be 1..4, got " + value);
                    }
                } catch (NumberFormatException e) { report.error(where, "sampleEntries is not an integer"); }
            } else report.warning(where, "unknown [biome] key '" + key + "'");
            return;
        }
        if (section.equals("resources")) { parseResource(key, value, where, m, report); return; }
        if (section.equals("options")) { parseOption(key, value, where, m, report); return; }
        if (section.equals("programs")) { parseProgramDecl(key, value, where, m, report); return; }
        if (section.equals("graph")) {
            if (key.equals("present")) { m.graph.presentResource = value; return; }
            report.warning(where, "unknown [graph] key '" + key + "'");
            return;
        }
        if (section.equals("screen")) {
            List<String> list = m.screen().get(key);
            if (list == null) { list = new ArrayList<String>(); m.screen().put(key, list); }
            for (String tok : LegacyPropertiesParser.splitWs(value)) {
                list.add(tok);
                PackOption o = m.option(tok);
                if (o != null) o.tab(key);
            }
            return;
        }
        if (section.isEmpty()) { report.error(where, "'" + key + "' appears before any [section]"); return; }
        report.warning(where, "unknown section [" + section + "]");
    }

    private static boolean truthy(String v) {
        String s = v.trim().toLowerCase(java.util.Locale.ROOT);
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on");
    }

    // ------------------------------------------------------------- resources
    private static void parseResource(String name, String value, String where,
                                      PackManifest m, PackLoadReport report) {
        GraphSpec.ResourceSpec r = new GraphSpec.ResourceSpec(name);
        String[] tokens = LegacyPropertiesParser.splitWs(value);
        if (tokens.length == 0) { report.error(where, "resource '" + name + "' has no declaration"); return; }
        r.format = GfxFormat.fromName(tokens[0]);
        if (r.format == GfxFormat.UNDEFINED) {
            report.error(where, "resource '" + name + "' has unknown format '" + tokens[0] + "'");
            return;
        }
        int usage = 0;
        for (int i = 1; i < tokens.length; i++) {
            String t = tokens[i];
            int eqi = t.indexOf('=');
            String flag = eqi > 0 ? t.substring(0, eqi) : t;
            String arg = eqi > 0 ? t.substring(eqi + 1) : "";
            if (flag.equals("fullscreen")) { r.sizeKind = GraphSpec.SizeKind.FULLSCREEN; continue; }
            if (flag.equals("shadow")) {
                r.sizeKind = GraphSpec.SizeKind.SHADOW;
                usage |= GfxUsage.IMAGE_DEPTH_STENCIL_ATTACHMENT;
                continue;
            }
            if (flag.equals("biome-volume") || flag.equals("biome_volume")) {
                r.sizeKind = GraphSpec.SizeKind.BIOME_VOLUME;
                r.is3d = true;
                usage |= GfxUsage.IMAGE_SAMPLED;
                continue;
            }
            if (flag.equals("scaled")) {
                r.sizeKind = GraphSpec.SizeKind.SCALED;
                try { r.scale = Double.parseDouble(arg.isEmpty() ? tokens[++i] : arg); }
                catch (RuntimeException e) { report.error(where, "scaled needs a factor: '" + t + "'"); }
                continue;
            }
            if (flag.equals("history")) { r.history = true; continue; }
            if (flag.equals("imported")) { r.imported = true; continue; }
            if (flag.equals("3d")) { r.is3d = true; continue; }
            if (flag.equals("mips")) {
                try { r.mipLevels = Integer.parseInt(arg.isEmpty() ? tokens[++i] : arg); }
                catch (RuntimeException e) { report.error(where, "mips needs a count: '" + t + "'"); }
                continue;
            }
            if (flag.equals("layers")) {
                try { r.arrayLayers = Integer.parseInt(arg.isEmpty() ? tokens[++i] : arg); }
                catch (RuntimeException e) { report.error(where, "layers needs a count: '" + t + "'"); }
                continue;
            }
            if (flag.equals("usage")) {
                for (String u : arg.split("\\|")) usage |= parseUsage(u.trim(), where, report);
                continue;
            }
            // Bare "WxH" or "WxHxD".
            int[] dims = dev.auralite.render.util.Extents.parse(flag);
            if (dims != null && !flag.contains(",")) {
                r.sizeKind = GraphSpec.SizeKind.FIXED;
                r.fixedWidth = dims[0];
                r.fixedHeight = dims[1];
                r.fixedDepth = dims[2];
                continue;
            }
            report.error(where, "resource '" + name + "' has unknown token '" + t + "'");
        }
        if (usage == 0) {
            usage = r.format.isDepthStencil()
                    ? GfxUsage.IMAGE_SAMPLED | GfxUsage.IMAGE_DEPTH_STENCIL_ATTACHMENT
                    : GfxUsage.IMAGE_SAMPLED | GfxUsage.IMAGE_COLOR_ATTACHMENT;
        }
        r.usage = usage;
        m.graph.addResource(r);
    }

    private static int parseUsage(String u, String where, PackLoadReport report) {
        String k = u.toLowerCase(java.util.Locale.ROOT);
        if (k.equals("sampled")) return GfxUsage.IMAGE_SAMPLED;
        if (k.equals("storage")) return GfxUsage.IMAGE_STORAGE;
        if (k.equals("color")) return GfxUsage.IMAGE_COLOR_ATTACHMENT;
        if (k.equals("depth")) return GfxUsage.IMAGE_DEPTH_STENCIL_ATTACHMENT;
        if (k.equals("present")) return GfxUsage.IMAGE_PRESENT;
        if (k.equals("transient")) return GfxUsage.IMAGE_TRANSIENT_ATTACHMENT;
        if (k.equals("input")) return GfxUsage.IMAGE_INPUT_ATTACHMENT;
        if (k.equals("transfer-src")) return GfxUsage.IMAGE_TRANSFER_SRC;
        if (k.equals("transfer-dst")) return GfxUsage.IMAGE_TRANSFER_DST;
        report.warning(where, "unknown usage flag '" + u + "'");
        return 0;
    }

    // ---------------------------------------------------------------- options
    private static void parseOption(String name, String value, String where,
                                    PackManifest m, PackLoadReport report) {
        // <kind> "<label>" [ { v=Label, ... } ] [default=<v>] [tab=<t>] [min..max step s]
        int q1 = value.indexOf('"');
        String head = q1 >= 0 ? value.substring(0, q1).trim() : value;
        String label = "";
        String rest = value;
        if (q1 >= 0) {
            int q2 = value.indexOf('"', q1 + 1);
            if (q2 < 0) { report.error(where, "option '" + name + "' has an unterminated label"); return; }
            label = value.substring(q1 + 1, q2);
            rest = value.substring(q2 + 1).trim();
        }
        String kindToken = head.isEmpty() ? "define" : head.split("\\s+")[0].toLowerCase(java.util.Locale.ROOT);
        PackOption.Kind kind;
        if (kindToken.equals("bool")) kind = PackOption.Kind.BOOL;
        else if (kindToken.equals("const")) kind = PackOption.Kind.CONST;
        else if (kindToken.equals("slider")) kind = PackOption.Kind.SLIDER;
        else if (kindToken.equals("select") || kindToken.equals("selector")) kind = PackOption.Kind.SELECTOR;
        else if (kindToken.equals("define")) kind = PackOption.Kind.DEFINE;
        else { report.error(where, "option '" + name + "' has unknown kind '" + kindToken + "'"); return; }

        PackOption o = new PackOption(name, kind).label(label);
        if (kind == PackOption.Kind.BOOL) { o.choice("true", "On"); o.choice("false", "Off"); }

        // Slider range embedded in the head: "slider 0.0..2.0 step 0.05"
        if (kind == PackOption.Kind.SLIDER) {
            String[] hp = head.split("\\s+");
            for (int i = 1; i < hp.length; i++) {
                if (hp[i].contains("..")) {
                    String[] r = hp[i].split("\\.\\.");
                    try { o.slider(Double.parseDouble(r[0]), Double.parseDouble(r[1]), 1.0); }
                    catch (RuntimeException e) { report.error(where, "bad slider range '" + hp[i] + "'"); }
                }
                if (hp[i].equals("step") && i + 1 < hp.length) {
                    try { o.sliderStep = Double.parseDouble(hp[++i]); }
                    catch (RuntimeException e) { report.error(where, "bad slider step"); }
                }
            }
        }

        // Choice block "{ 14=Cheap, 24=Standard }"
        int b1 = rest.indexOf('{');
        if (b1 >= 0) {
            int b2 = rest.indexOf('}', b1);
            if (b2 < 0) { report.error(where, "option '" + name + "' has an unterminated choice block"); return; }
            String body = rest.substring(b1 + 1, b2);
            for (String part : body.split(",")) {
                String p = part.trim();
                if (p.isEmpty()) continue;
                int e2 = p.indexOf('=');
                if (e2 < 0) o.choice(p, p);
                else o.choice(p.substring(0, e2).trim(), p.substring(e2 + 1).trim());
            }
            rest = rest.substring(b2 + 1).trim();
        }

        for (String tok : LegacyPropertiesParser.splitWs(rest)) {
            int e2 = tok.indexOf('=');
            if (e2 < 0) { report.warning(where, "option '" + name + "' ignores token '" + tok + "'"); continue; }
            String k = tok.substring(0, e2).trim(), v = tok.substring(e2 + 1).trim();
            if (k.equals("default")) o.defaultValue(v);
            else if (k.equals("tab")) o.tab(v);
            else if (k.equals("comment")) o.comment(v);
            else report.warning(where, "option '" + name + "' has unknown attribute '" + k + "'");
        }
        if (o.defaultValue.equals("") && !o.choices().isEmpty()) {
            o.defaultValue(o.choices().get(0).value);
        }
        if (!o.defaultValue.isEmpty() && !o.accepts(o.defaultValue)) {
            report.error(where, "option '" + name + "' default '" + o.defaultValue
                    + "' is not one of its declared values");
            return;
        }
        m.addOption(o);
    }

    // -------------------------------------------------------------- programs
    private static void parseProgramDecl(String name, String value, String where,
                                         PackManifest m, PackLoadReport report) {
        PackProgram p = m.program(name);
        if (p == null) { p = new PackProgram(name); m.addProgram(p); }
        // Grammar: "path[, path ...] [key=value ...]". A token is an attribute
        // if and only if it carries '=', otherwise it is a source path. Anything
        // cleverer is ambiguous: splitting only on spaces read
        // "shaders/ssao.comp local_size=16x16x1" as one path plus a nonsense
        // attribute, and splitting only on '=' read the path as the attribute
        // name. This rule is total and the pack author can see it in one line.
        List<String> paths = new ArrayList<String>();
        String[] parts = value.split(",");
        for (int i = 0; i < parts.length; i++) {
            String[] tokens = parts[i].trim().split("\\s+");
            for (int j = 0; j < tokens.length; j++) {
                String tok = tokens[j];
                if (tok.isEmpty()) continue;
                int eq = tok.indexOf('=');
                if (eq < 0) { paths.add(tok); continue; }
                String attr = tok.substring(0, eq);
                String arg = tok.substring(eq + 1);
                if (attr.equals("local_size") || attr.equals("localSize")) {
                    int[] d = dev.auralite.render.util.Extents.parse(arg);
                    if (d == null) report.error(where, "bad local_size '" + arg + "'");
                    else { p.localSizeX = d[0]; p.localSizeY = d[1]; p.localSizeZ = d[2]; }
                    continue;
                }
                if (attr.equals("pass")) { p.boundPass = arg; continue; }
                report.warning(where, "program '" + name + "' ignores attribute '" + attr + "'");
            }
        }
        p.sourcePaths.addAll(paths);
    }

    // ------------------------------------------------------------------ graph
    private static void parsePass(String raw, String where, PackManifest m, PackLoadReport report) {
        String[] t = LegacyPropertiesParser.splitWs(raw);
        if (t.length < 3) { report.error(where, "'pass' needs a name and a type"); return; }
        GraphSpec.PassSpec p = new GraphSpec.PassSpec(t[1]);
        p.type = t[2].toLowerCase(java.util.Locale.ROOT);
        if (!p.type.equals("raster") && !p.type.equals("compute") && !p.type.equals("copy")) {
            report.error(where, "pass '" + p.name + "' has unknown type '" + p.type + "'");
            return;
        }
        for (int i = 3; i < t.length; i++) {
            String tok = t[i];
            int eq = tok.indexOf('=');
            if (eq < 0) {
                if (tok.equals("external")) { p.external = true; continue; }
                report.error(where, "pass '" + p.name + "' has a clause without a value: '" + tok + "'");
                continue;
            }
            String k = tok.substring(0, eq), v = tok.substring(eq + 1);
            if (k.equals("color") || k.equals("colors")) addAll(p.colors, v);
            else if (k.equals("depth")) p.depth = v;
            else if (k.equals("read") || k.equals("reads")) addAll(p.reads, v);
            else if (k.equals("write") || k.equals("writes")) addAll(p.writes, v);
            else if (k.equals("program")) p.program = v;
            else if (k.equals("when")) p.when = v;
            else if (k.equals("dispatch")) {
                if (v.contains("(") || v.contains("width") || v.contains("height")) {
                    p.dispatchExpr = v;
                } else {
                    String[] d = v.split(",");
                    try {
                        p.dispatchX = Integer.parseInt(d[0].trim());
                        p.dispatchY = d.length > 1 ? Integer.parseInt(d[1].trim()) : 1;
                        p.dispatchZ = d.length > 2 ? Integer.parseInt(d[2].trim()) : 1;
                    } catch (RuntimeException e) { report.error(where, "bad dispatch '" + v + "'"); }
                }
            } else report.warning(where, "pass '" + p.name + "' ignores clause '" + k + "'");
        }
        if (m.graph.findPass(p.name) != null) {
            report.error(where, "pass '" + p.name + "' is declared twice");
            return;
        }
        m.graph.addPass(p);
    }

    private static void addAll(List<String> into, String csv) {
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) into.add(t);
        }
    }
}
