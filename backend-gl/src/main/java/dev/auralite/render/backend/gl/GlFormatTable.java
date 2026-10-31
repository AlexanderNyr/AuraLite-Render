package dev.auralite.render.backend.gl;

import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.util.Checks;

import static org.lwjgl.opengl.EXTTextureSRGB.GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT5_EXT;
import static org.lwjgl.opengl.EXTTextureSRGB.GL_COMPRESSED_SRGB_S3TC_DXT1_EXT;
import static org.lwjgl.opengl.GL46C.GL_BGRA;
import static org.lwjgl.opengl.GL46C.GL_BYTE;
import static org.lwjgl.opengl.GL46C.GL_COMPRESSED_RGBA_BPTC_UNORM;
import static org.lwjgl.opengl.GL46C.GL_COMPRESSED_RG_RGTC2;
import static org.lwjgl.opengl.GL46C.GL_COMPRESSED_SRGB_ALPHA_BPTC_UNORM;
import static org.lwjgl.opengl.GL46C.GL_DEPTH_COMPONENT;
import static org.lwjgl.opengl.GL46C.GL_DEPTH_COMPONENT16;
import static org.lwjgl.opengl.GL46C.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL46C.GL_DEPTH_COMPONENT32F;
import static org.lwjgl.opengl.GL46C.GL_DEPTH_STENCIL;
import static org.lwjgl.opengl.GL46C.GL_DEPTH24_STENCIL8;
import static org.lwjgl.opengl.GL46C.GL_DEPTH32F_STENCIL8;
import static org.lwjgl.opengl.GL46C.GL_FLOAT;
import static org.lwjgl.opengl.GL46C.GL_FLOAT_32_UNSIGNED_INT_24_8_REV;
import static org.lwjgl.opengl.GL46C.GL_HALF_FLOAT;
import static org.lwjgl.opengl.GL46C.GL_INT;
import static org.lwjgl.opengl.GL46C.GL_R11F_G11F_B10F;
import static org.lwjgl.opengl.GL46C.GL_R16;
import static org.lwjgl.opengl.GL46C.GL_R16F;
import static org.lwjgl.opengl.GL46C.GL_R32F;
import static org.lwjgl.opengl.GL46C.GL_R32I;
import static org.lwjgl.opengl.GL46C.GL_R32UI;
import static org.lwjgl.opengl.GL46C.GL_R8;
import static org.lwjgl.opengl.GL46C.GL_R8_SNORM;
import static org.lwjgl.opengl.GL46C.GL_R8I;
import static org.lwjgl.opengl.GL46C.GL_R8UI;
import static org.lwjgl.opengl.GL46C.GL_RED;
import static org.lwjgl.opengl.GL46C.GL_RED_INTEGER;
import static org.lwjgl.opengl.GL46C.GL_RG;
import static org.lwjgl.opengl.GL46C.GL_RG_INTEGER;
import static org.lwjgl.opengl.GL46C.GL_RG16F;
import static org.lwjgl.opengl.GL46C.GL_RG32F;
import static org.lwjgl.opengl.GL46C.GL_RG32UI;
import static org.lwjgl.opengl.GL46C.GL_RG8;
import static org.lwjgl.opengl.GL46C.GL_RG8UI;
import static org.lwjgl.opengl.GL46C.GL_RGB;
import static org.lwjgl.opengl.GL46C.GL_RGB10_A2;
import static org.lwjgl.opengl.GL46C.GL_RGB32F;
import static org.lwjgl.opengl.GL46C.GL_RGBA;
import static org.lwjgl.opengl.GL46C.GL_RGBA_INTEGER;
import static org.lwjgl.opengl.GL46C.GL_RGBA16;
import static org.lwjgl.opengl.GL46C.GL_RGBA16F;
import static org.lwjgl.opengl.GL46C.GL_RGBA16UI;
import static org.lwjgl.opengl.GL46C.GL_RGBA32F;
import static org.lwjgl.opengl.GL46C.GL_RGBA32UI;
import static org.lwjgl.opengl.GL46C.GL_RGBA8;
import static org.lwjgl.opengl.GL46C.GL_RGBA8_SNORM;
import static org.lwjgl.opengl.GL46C.GL_RGBA8I;
import static org.lwjgl.opengl.GL46C.GL_RGBA8UI;
import static org.lwjgl.opengl.GL46C.GL_SRGB8_ALPHA8;
import static org.lwjgl.opengl.GL46C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL46C.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL46C.GL_UNSIGNED_INT_10F_11F_11F_REV;
import static org.lwjgl.opengl.GL46C.GL_UNSIGNED_INT_24_8;
import static org.lwjgl.opengl.GL46C.GL_UNSIGNED_INT_2_10_10_10_REV;
import static org.lwjgl.opengl.GL46C.GL_UNSIGNED_SHORT;

/**
 * Every {@link GfxFormat} as the three GL enums texture creation needs:
 * sized internal format, pixel format, pixel type.
 *
 * <p>Pure data, no GL context required: the values are compile-time constants,
 * so this table is gated by the host test suite on machines with no GPU. Two
 * formats are honestly unsupported -- core OpenGL has no sized one- or
 * two-channel sRGB internal format, so {@code R8_SRGB} and {@code RG8_SRGB}
 * report {@code supported == false} and image creation refuses them by name
 * instead of silently allocating the wrong storage.
 */
public final class GlFormatTable {
    private GlFormatTable() { }

    public static final class Mapping {
        public final int internalFormat;
        public final int format;
        public final int type;
        public final boolean compressed;
        public final boolean supported;

        private Mapping(int internalFormat, int format, int type, boolean compressed, boolean supported) {
            this.internalFormat = internalFormat;
            this.format = format;
            this.type = type;
            this.compressed = compressed;
            this.supported = supported;
        }
    }

    private static Mapping m(int internalFormat, int format, int type) {
        return new Mapping(internalFormat, format, type, false, true);
    }

    private static Mapping compressed(int internalFormat) {
        return new Mapping(internalFormat, 0, 0, true, true);
    }

    private static final Mapping UNSUPPORTED = new Mapping(0, 0, 0, false, false);

    /** Maps a format. Never returns null; check {@link Mapping#supported}. */
    public static Mapping of(GfxFormat f) {
        Checks.notNull(f, "format");
        switch (f) {
            case R8_UNORM: return m(GL_R8, GL_RED, GL_UNSIGNED_BYTE);
            case R8_SNORM: return m(GL_R8_SNORM, GL_RED, GL_BYTE);
            case R8_UINT: return m(GL_R8UI, GL_RED_INTEGER, GL_UNSIGNED_BYTE);
            case R8_SINT: return m(GL_R8I, GL_RED_INTEGER, GL_BYTE);
            case R8_SRGB: return UNSUPPORTED;
            case RG8_UNORM: return m(GL_RG8, GL_RG, GL_UNSIGNED_BYTE);
            case RG8_UINT: return m(GL_RG8UI, GL_RG_INTEGER, GL_UNSIGNED_BYTE);
            case RG8_SRGB: return UNSUPPORTED;
            case RGBA8_UNORM: return m(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE);
            case RGBA8_SNORM: return m(GL_RGBA8_SNORM, GL_RGBA, GL_BYTE);
            case RGBA8_UINT: return m(GL_RGBA8UI, GL_RGBA_INTEGER, GL_UNSIGNED_BYTE);
            case RGBA8_SINT: return m(GL_RGBA8I, GL_RGBA_INTEGER, GL_BYTE);
            case RGBA8_SRGB: return m(GL_SRGB8_ALPHA8, GL_RGBA, GL_UNSIGNED_BYTE);
            // Same storage as RGBA8; the upload pixel format does the swizzle.
            case BGRA8_UNORM: return m(GL_RGBA8, GL_BGRA, GL_UNSIGNED_BYTE);
            case R16_UNORM: return m(GL_R16, GL_RED, GL_UNSIGNED_SHORT);
            case R16F: return m(GL_R16F, GL_RED, GL_HALF_FLOAT);
            case RG16F: return m(GL_RG16F, GL_RG, GL_HALF_FLOAT);
            case RGBA16F: return m(GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT);
            case RGBA16_UNORM: return m(GL_RGBA16, GL_RGBA, GL_UNSIGNED_SHORT);
            case RGBA16_UINT: return m(GL_RGBA16UI, GL_RGBA_INTEGER, GL_UNSIGNED_SHORT);
            case R32_UINT: return m(GL_R32UI, GL_RED_INTEGER, GL_UNSIGNED_INT);
            case R32_SINT: return m(GL_R32I, GL_RED_INTEGER, GL_INT);
            case R32F: return m(GL_R32F, GL_RED, GL_FLOAT);
            case RG32F: return m(GL_RG32F, GL_RG, GL_FLOAT);
            case RGB32F: return m(GL_RGB32F, GL_RGB, GL_FLOAT);
            case RGBA32_UINT: return m(GL_RGBA32UI, GL_RGBA_INTEGER, GL_UNSIGNED_INT);
            case RGBA32F: return m(GL_RGBA32F, GL_RGBA, GL_FLOAT);
            case RGB10A2_UNORM: return m(GL_RGB10_A2, GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV);
            case RG11B10F: return m(GL_R11F_G11F_B10F, GL_RGB, GL_UNSIGNED_INT_10F_11F_11F_REV);
            case R32G32_UINT: return m(GL_RG32UI, GL_RG_INTEGER, GL_UNSIGNED_INT);
            case R32G32B32A32_UINT: return m(GL_RGBA32UI, GL_RGBA_INTEGER, GL_UNSIGNED_INT);
            case D16_UNORM: return m(GL_DEPTH_COMPONENT16, GL_DEPTH_COMPONENT, GL_UNSIGNED_SHORT);
            case D24_UNORM: return m(GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT);
            case D32_SFLOAT: return m(GL_DEPTH_COMPONENT32F, GL_DEPTH_COMPONENT, GL_FLOAT);
            case D24_UNORM_S8_UINT: return m(GL_DEPTH24_STENCIL8, GL_DEPTH_STENCIL, GL_UNSIGNED_INT_24_8);
            case D32_SFLOAT_S8_UINT:
                return m(GL_DEPTH32F_STENCIL8, GL_DEPTH_STENCIL, GL_FLOAT_32_UNSIGNED_INT_24_8_REV);
            case BC1_RGB_SRGB: return compressed(GL_COMPRESSED_SRGB_S3TC_DXT1_EXT);
            case BC3_SRGB: return compressed(GL_COMPRESSED_SRGB_ALPHA_S3TC_DXT5_EXT);
            case BC5_UNORM: return compressed(GL_COMPRESSED_RG_RGTC2);
            case BC7_SRGB: return compressed(GL_COMPRESSED_SRGB_ALPHA_BPTC_UNORM);
            case BC7_UNORM: return compressed(GL_COMPRESSED_RGBA_BPTC_UNORM);
            case UNDEFINED: return UNSUPPORTED;
            default: break;
        }
        throw new AssertionError("unmapped GfxFormat: " + f);
    }
}
