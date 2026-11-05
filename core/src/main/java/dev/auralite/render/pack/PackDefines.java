package dev.auralite.render.pack;

import java.util.List;

/**
 * Puts a manifest's option {@code #define}s into a shader's source.
 *
 * <h2>Why this is a class and not three lines in the harness</h2>
 *
 * <p>{@link PackManifest#activeDefines()} has existed and been tested since the
 * options lane landed, and the format doc promises that options "become
 * {@code #define} lines". They did -- into a list nobody compiled with. The GPU
 * lane fed {@code src.text()} to glslangValidator verbatim, so every pack option
 * was inert in a shader, and the failure mode is the quiet kind: the shader
 * compiles, the frame renders, and the value the pack asked for is silently the
 * one baked into the source. It was found by a probe that changed a radius and
 * got a byte-identical frame.
 *
 * <p>Two rules keep the insertion honest. The defines go <b>after</b> the
 * {@code #version} line, because GLSL requires the version first. And a shader
 * with no {@code #version} is refused by name rather than quietly prefixed,
 * because a prefix before the version is a compile error the author would have
 * to decode.
 */
public final class PackDefines {

    private PackDefines() { }

    /**
     * Returns {@code source} with {@code defines} inserted after its version
     * line, in the order given.
     *
     * @throws IllegalArgumentException when the source has no {@code #version}
     *         line -- a shader that cannot be defined honestly
     */
    public static String apply(String source, List<String> defines) {
        if (source == null) throw new IllegalArgumentException("PackDefines.apply needs a source");
        if (defines == null || defines.isEmpty()) return source;
        int versionEnd = versionLineEnd(source);
        if (versionEnd < 0) {
            throw new IllegalArgumentException("the shader has no #version line, so option defines"
                    + " cannot be placed legally: " + source.substring(0, Math.min(40, source.length())));
        }
        StringBuilder b = new StringBuilder(source.length() + defines.size() * 32);
        b.append(source, 0, versionEnd);
        for (String d : defines) {
            b.append(d).append('\n');
        }
        b.append(source, versionEnd, source.length());
        return b.toString();
    }

    /** Index just past the version line's newline, or -1 when there is none. */
    private static int versionLineEnd(String source) {
        int at = 0;
        while (at < source.length()) {
            int eol = source.indexOf('\n', at);
            if (eol < 0) eol = source.length();
            String line = source.substring(at, eol).trim();
            if (line.startsWith("#version")) {
                return Math.min(eol + 1, source.length());
            }
            // A shader may open with comments or whitespace, but not with code.
            if (!line.isEmpty() && !line.startsWith("//") && !line.startsWith("/*")
                    && !line.startsWith("*")) {
                return -1;
            }
            if (eol == source.length()) break;
            at = eol + 1;
        }
        return -1;
    }
}
