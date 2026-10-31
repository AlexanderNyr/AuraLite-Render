package dev.auralite.render.rhi;

/** Bitwise usage flags. Both backends translate these into their native masks. */
public final class GfxUsage {
    private GfxUsage() { }

    // ---- buffer ----
    public static final int BUFFER_VERTEX = 1;
    public static final int BUFFER_INDEX = 1 << 1;
    public static final int BUFFER_UNIFORM = 1 << 2;
    public static final int BUFFER_STORAGE = 1 << 3;
    public static final int BUFFER_INDIRECT = 1 << 4;
    public static final int BUFFER_TRANSFER_SRC = 1 << 5;
    public static final int BUFFER_TRANSFER_DST = 1 << 6;
    public static final int BUFFER_SHADER_DEVICE_ADDRESS = 1 << 7;

    // ---- image ----
    public static final int IMAGE_SAMPLED = 1;
    public static final int IMAGE_STORAGE = 1 << 1;
    public static final int IMAGE_COLOR_ATTACHMENT = 1 << 2;
    public static final int IMAGE_DEPTH_STENCIL_ATTACHMENT = 1 << 3;
    public static final int IMAGE_TRANSFER_SRC = 1 << 4;
    public static final int IMAGE_TRANSFER_DST = 1 << 5;
    public static final int IMAGE_INPUT_ATTACHMENT = 1 << 6;
    public static final int IMAGE_TRANSIENT_ATTACHMENT = 1 << 7;
    public static final int IMAGE_PRESENT = 1 << 8;
    public static final int IMAGE_ACCELERATION_STRUCTURE = 1 << 9;

    public static String describe(int flags) {
        StringBuilder sb = new StringBuilder();
        append(sb, flags, BUFFER_VERTEX, "vertex");
        append(sb, flags, BUFFER_INDEX, "index");
        append(sb, flags, BUFFER_UNIFORM, "uniform");
        append(sb, flags, BUFFER_STORAGE, "storage");
        append(sb, flags, BUFFER_INDIRECT, "indirect");
        append(sb, flags, IMAGE_SAMPLED, "sampled");
        append(sb, flags, IMAGE_STORAGE, "storage-image");
        append(sb, flags, IMAGE_COLOR_ATTACHMENT, "color-attach");
        append(sb, flags, IMAGE_DEPTH_STENCIL_ATTACHMENT, "depth-attach");
        append(sb, flags, IMAGE_PRESENT, "present");
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static void append(StringBuilder sb, int flags, int bit, String name) {
        if ((flags & bit) != 0) {
            if (sb.length() > 0) sb.append('|');
            sb.append(name);
        }
    }
}
