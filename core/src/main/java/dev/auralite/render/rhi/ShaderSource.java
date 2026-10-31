package dev.auralite.render.rhi;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shader text or SPIR-V plus the metadata both backends need.
 *
 * <p>The {@code Kind} matters: the GL46 backend can only take GLSL, the Vulkan
 * backend can only take SPIR-V. A native pack may ship either; the loader
 * resolves it per backend and reports a named reason when the pack shipped only
 * the other one. That check is done at load, not at first draw.
 */
public final class ShaderSource {
    public enum Kind { GLSL, SPIRV }

    private final Kind kind;
    private final GfxStage stage;
    private final String entryPoint;
    private final String text;
    private final byte[] spirv;
    private final List<String> defines;
    private final long hash;
    private final String origin;

    private ShaderSource(Kind kind, GfxStage stage, String entryPoint, String text,
                         byte[] spirv, List<String> defines, String origin) {
        this.kind = kind;
        this.stage = stage;
        this.entryPoint = entryPoint;
        this.text = text;
        this.spirv = spirv;
        this.defines = Collections.unmodifiableList(new ArrayList<String>(defines));
        this.hash = computeHash();
        this.origin = origin;
    }

    public static ShaderSource glsl(GfxStage stage, String source, String origin) {
        return new ShaderSource(Kind.GLSL, stage, "main", source, null,
                Collections.<String>emptyList(), origin);
    }

    public static ShaderSource glsl(GfxStage stage, String source, String origin, List<String> defines) {
        return new ShaderSource(Kind.GLSL, stage, "main", source, null, defines, origin);
    }

    public static ShaderSource spirv(GfxStage stage, byte[] code, String entryPoint, String origin) {
        return new ShaderSource(Kind.SPIRV, stage, entryPoint, null, code,
                Collections.<String>emptyList(), origin);
    }

    /** Prepends {@code #define} lines, the pack's option mechanism in both formats. */
    public ShaderSource withDefines(List<String> extra) {
        if (kind != Kind.GLSL) {
            throw new IllegalStateException("SPIR-V cannot take preprocessor defines; "
                    + "recompile the stage or ship a GLSL variant");
        }
        List<String> all = new ArrayList<String>(defines);
        all.addAll(extra);
        return new ShaderSource(kind, stage, entryPoint, text, spirv, all, origin);
    }

    /** GLSL with the defines physically applied, which is what both compilers see. */
    public String preprocessed() {
        if (kind != Kind.GLSL) return "";
        if (defines.isEmpty()) return text;
        StringBuilder sb = new StringBuilder(text.length() + defines.size() * 24);
        sb.append("#version 460 core\n");
        for (String d : defines) sb.append("#define ").append(d).append('\n');
        sb.append(stripVersion(text));
        return sb.toString();
    }

    /** Removes a pack-supplied {@code #version} line so the loader controls it. */
    public static String stripVersion(String src) {
        if (src == null) return "";
        int i = src.indexOf("#version");
        if (i < 0) return src;
        int nl = src.indexOf('\n', i);
        if (nl < 0) return src.substring(0, i);
        return src.substring(0, i) + src.substring(nl + 1);
    }

    private long computeHash() {
        byte[] data = kind == Kind.GLSL
                ? (text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8))
                : (spirv == null ? new byte[0] : spirv);
        long h = 1469598103934665603L;
        for (byte b : data) h = (h ^ (b & 0xFF)) * 1099511628211L;
        h = (h ^ stage.ordinal()) * 1099511628211L;
        for (String d : defines) {
            for (int i = 0; i < d.length(); i++) h = (h ^ d.charAt(i)) * 1099511628211L;
        }
        return h;
    }

    public Kind kind() { return kind; }
    public GfxStage stage() { return stage; }
    public String entryPoint() { return entryPoint; }
    public String text() { return text == null ? "" : text; }
    public byte[] spirv() { return spirv == null ? new byte[0] : spirv.clone(); }
    public List<String> defines() { return defines; }
    public long hash() { return hash; }
    public String origin() { return origin == null ? "<unknown>" : origin; }
    public int byteLength() {
        return kind == Kind.GLSL ? (text == null ? 0 : text.length()) : (spirv == null ? 0 : spirv.length);
    }
}
