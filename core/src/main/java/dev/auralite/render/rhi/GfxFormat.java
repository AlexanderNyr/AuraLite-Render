package dev.auralite.render.rhi;

/**
 * Surface and buffer formats understood by every AuraLite-Render backend.
 *
 * <p>Core carries no GL/VK constants on purpose: each backend maps this enum to
 * its own native table, so a pack that asks for {@code RGBA16F} gets
 * {@code GL_RGBA16F} on the GL46 lane and {@code VK_FORMAT_R16G16B16A16_SFLOAT}
 * on the Vulkan lane from one declaration.
 */
public enum GfxFormat {
    UNDEFINED(0, 0),
    R8_UNORM(1, F.NORM),
    R8_SNORM(1, F.NORM),
    R8_UINT(1, F.INT),
    R8_SINT(1, F.INT),
    R8_SRGB(1, F.NORM | F.SRGB),
    RG8_UNORM(2, F.NORM),
    RG8_UINT(2, F.INT),
    RG8_SRGB(2, F.NORM | F.SRGB),
    RGBA8_UNORM(4, F.NORM),
    RGBA8_SNORM(4, F.NORM),
    RGBA8_UINT(4, F.INT),
    RGBA8_SINT(4, F.INT),
    RGBA8_SRGB(4, F.NORM | F.SRGB),
    BGRA8_UNORM(4, F.NORM),
    R16_UNORM(2, F.NORM),
    R16F(2, F.FLOAT),
    RG16F(4, F.FLOAT),
    RGBA16F(8, F.FLOAT),
    RGBA16_UNORM(8, F.NORM),
    RGBA16_UINT(8, F.INT),
    R32_UINT(4, F.INT),
    R32_SINT(4, F.INT),
    R32F(4, F.FLOAT),
    RG32F(8, F.FLOAT),
    RGB32F(12, F.FLOAT),
    RGBA32_UINT(16, F.INT),
    RGBA32F(16, F.FLOAT),
    RGB10A2_UNORM(4, F.NORM),
    RG11B10F(4, F.FLOAT),
    R32G32_UINT(8, F.INT),
    R32G32B32A32_UINT(16, F.INT),
    D16_UNORM(2, F.DEPTH),
    D24_UNORM(4, F.DEPTH),
    D32_SFLOAT(4, F.DEPTH | F.FLOAT),
    D24_UNORM_S8_UINT(4, F.DEPTH | F.STENCIL),
    D32_SFLOAT_S8_UINT(8, F.DEPTH | F.STENCIL | F.FLOAT),
    BC1_RGB_SRGB(0, F.SRGB | F.COMPRESSED),
    BC3_SRGB(0, F.SRGB | F.COMPRESSED),
    BC5_UNORM(0, F.NORM | F.COMPRESSED),
    BC7_SRGB(0, F.SRGB | F.COMPRESSED),
    BC7_UNORM(0, F.NORM | F.COMPRESSED);

    /**
     * Compile-time constant holder. These live in a nested type on purpose: an
     * enum constant's arguments may not forward-reference a static field of its
     * own class, so the flags are declared where the constants can see them.
     */
    public interface F {
        int NORM = 1;
        int INT = 1 << 1;
        int FLOAT = 1 << 2;
        int SRGB = 1 << 3;
        int DEPTH = 1 << 4;
        int STENCIL = 1 << 5;
        int COMPRESSED = 1 << 6;
    }

    /** Public aliases so callers read {@code GfxFormat.FLAG_FLOAT}. */
    public static final int FLAG_NORM = F.NORM;
    public static final int FLAG_INT = F.INT;
    public static final int FLAG_FLOAT = F.FLOAT;
    public static final int FLAG_SRGB = F.SRGB;
    public static final int FLAG_DEPTH = F.DEPTH;
    public static final int FLAG_STENCIL = F.STENCIL;
    public static final int FLAG_COMPRESSED = F.COMPRESSED;

    private final int bytesPerTexel;
    private final int flags;

    GfxFormat(int bytesPerTexel, int flags) {
        this.bytesPerTexel = bytesPerTexel;
        this.flags = flags;
    }

    public int bytesPerTexel() { return bytesPerTexel; }
    public int flags() { return flags; }

    public boolean isSrgb() { return (flags & FLAG_SRGB) != 0; }
    public boolean isInteger() { return (flags & FLAG_INT) != 0; }
    public boolean isFloat() { return (flags & FLAG_FLOAT) != 0; }
    public boolean isNormalized() { return (flags & FLAG_NORM) != 0; }
    public boolean isDepth() { return (flags & FLAG_DEPTH) != 0; }
    public boolean isStencil() { return (flags & FLAG_STENCIL) != 0; }
    public boolean isDepthStencil() { return isDepth() || isStencil(); }
    public boolean isCompressed() { return (flags & FLAG_COMPRESSED) != 0; }

    /** True when the format can be attached to a raster pass as a colour target. */
    public boolean isColorAttachment() {
        return !isDepthStencil() && !isCompressed() && this != UNDEFINED;
    }

    /** True when an integer format must be sampled with texelFetch semantics. */
    public boolean requiresIntegerSampler() { return isInteger(); }

    /** Formats a pack may legally use for a writable compute storage image. */
    public boolean isStorableImage() { return !isCompressed() && !isDepthStencil() && this != UNDEFINED; }

    /** Case-insensitive lookup used by both pack parsers; returns UNDEFINED on a miss. */
    public static GfxFormat fromName(String name) {
        if (name == null) return UNDEFINED;
        String n = name.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
        for (GfxFormat f : values()) {
            if (f.name().toLowerCase(java.util.Locale.ROOT).equals(n)) return f;
        }
        // Legacy pack spellings: "rgba16f", "rg11b10f", "r8" are how Iris writes them.
        switch (n) {
            case "rgba16": return RGBA16F;
            case "rgba32": return RGBA32F;
            case "rgba8": return RGBA8_UNORM;
            case "r8": return R8_UNORM;
            case "r16": return R16F;
            case "r32": return R32F;
            case "rg16": return RG16F;
            case "rg32": return RG32F;
            case "rg11b10": return RG11B10F;
            case "depth": case "depth32": return D32_SFLOAT;
            case "rgb10_a2": return RGB10A2_UNORM;
            default: return UNDEFINED;
        }
    }
}
