package dev.auralite.render.util;

/**
 * Parses the {@code W}, {@code WxH} and {@code WxHxD} extent spellings both pack
 * formats use for fixed-size resources and compute local sizes.
 *
 * <p>Returns {@code null} on any failure instead of throwing, so a parser can
 * report the failure with the file and line number it already holds rather than
 * catching an exception whose message has neither.
 */
public final class Extents {
    private Extents() { }

    /**
     * Parses {@code text} into {@code {width, height, depth}}, defaulting absent
     * dimensions to 1. Separators are {@code x}, {@code X}, {@code *} and comma;
     * surrounding whitespace is tolerated. Every dimension must be a positive
     * integer; anything else -- an empty part, a zero, a fourth dimension,
     * trailing garbage -- returns {@code null}.
     */
    public static int[] parse(String text) {
        if (text == null) return null;
        String[] parts = text.trim().split("[xX*,]");
        if (parts.length < 1 || parts.length > 3) return null;
        int[] out = new int[] { 1, 1, 1 };
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) return null;
            for (int j = 0; j < p.length(); j++) {
                if (!Character.isDigit(p.charAt(j))) return null;
            }
            try {
                out[i] = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return null;
            }
            if (out[i] <= 0) return null;
        }
        return out;
    }
}
