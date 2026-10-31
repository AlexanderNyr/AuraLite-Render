package dev.auralite.render.backend.gl;

import dev.auralite.render.rhi.GfxEnums.AddressMode;
import dev.auralite.render.rhi.GfxEnums.BlendFactor;
import dev.auralite.render.rhi.GfxEnums.BlendOp;
import dev.auralite.render.rhi.GfxEnums.CompareOp;
import dev.auralite.render.rhi.GfxEnums.CullMode;
import dev.auralite.render.rhi.GfxEnums.Filter;
import dev.auralite.render.rhi.GfxEnums.FrontFace;
import dev.auralite.render.rhi.GfxEnums.ImageType;
import dev.auralite.render.rhi.GfxEnums.MipmapMode;
import dev.auralite.render.rhi.GfxEnums.Topology;
import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxStage;
import dev.auralite.render.util.Checks;

import static org.lwjgl.opengl.GL46C.GL_ALWAYS;
import static org.lwjgl.opengl.GL46C.GL_BACK;
import static org.lwjgl.opengl.GL46C.GL_CCW;
import static org.lwjgl.opengl.GL46C.GL_CLAMP_TO_BORDER;
import static org.lwjgl.opengl.GL46C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL46C.GL_COMPUTE_SHADER;
import static org.lwjgl.opengl.GL46C.GL_CONSTANT_COLOR;
import static org.lwjgl.opengl.GL46C.GL_CW;
import static org.lwjgl.opengl.GL46C.GL_DST_ALPHA;
import static org.lwjgl.opengl.GL46C.GL_DST_COLOR;
import static org.lwjgl.opengl.GL46C.GL_EQUAL;
import static org.lwjgl.opengl.GL46C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL46C.GL_FRONT;
import static org.lwjgl.opengl.GL46C.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL46C.GL_FUNC_ADD;
import static org.lwjgl.opengl.GL46C.GL_FUNC_REVERSE_SUBTRACT;
import static org.lwjgl.opengl.GL46C.GL_FUNC_SUBTRACT;
import static org.lwjgl.opengl.GL46C.GL_GEOMETRY_SHADER;
import static org.lwjgl.opengl.GL46C.GL_GEQUAL;
import static org.lwjgl.opengl.GL46C.GL_GREATER;
import static org.lwjgl.opengl.GL46C.GL_LEQUAL;
import static org.lwjgl.opengl.GL46C.GL_LESS;
import static org.lwjgl.opengl.GL46C.GL_LINES;
import static org.lwjgl.opengl.GL46C.GL_LINE_STRIP;
import static org.lwjgl.opengl.GL46C.GL_LINEAR;
import static org.lwjgl.opengl.GL46C.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL46C.GL_LINEAR_MIPMAP_NEAREST;
import static org.lwjgl.opengl.GL46C.GL_MAX;
import static org.lwjgl.opengl.GL46C.GL_MIN;
import static org.lwjgl.opengl.GL46C.GL_MIRRORED_REPEAT;
import static org.lwjgl.opengl.GL46C.GL_NEAREST;
import static org.lwjgl.opengl.GL46C.GL_NEAREST_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL46C.GL_NEAREST_MIPMAP_NEAREST;
import static org.lwjgl.opengl.GL46C.GL_NEVER;
import static org.lwjgl.opengl.GL46C.GL_NOTEQUAL;
import static org.lwjgl.opengl.GL46C.GL_ONE;
import static org.lwjgl.opengl.GL46C.GL_ONE_MINUS_CONSTANT_COLOR;
import static org.lwjgl.opengl.GL46C.GL_ONE_MINUS_DST_ALPHA;
import static org.lwjgl.opengl.GL46C.GL_ONE_MINUS_DST_COLOR;
import static org.lwjgl.opengl.GL46C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL46C.GL_ONE_MINUS_SRC_COLOR;
import static org.lwjgl.opengl.GL46C.GL_PATCHES;
import static org.lwjgl.opengl.GL46C.GL_POINTS;
import static org.lwjgl.opengl.GL46C.GL_REPEAT;
import static org.lwjgl.opengl.GL46C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL46C.GL_SRC_ALPHA_SATURATE;
import static org.lwjgl.opengl.GL46C.GL_SRC_COLOR;
import static org.lwjgl.opengl.GL46C.GL_TESS_CONTROL_SHADER;
import static org.lwjgl.opengl.GL46C.GL_TESS_EVALUATION_SHADER;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_1D;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_1D_ARRAY;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_CUBE_MAP_ARRAY;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_2D_MULTISAMPLE;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_3D;
import static org.lwjgl.opengl.GL46C.GL_TEXTURE_CUBE_MAP;
import static org.lwjgl.opengl.GL46C.GL_TRIANGLE_FAN;
import static org.lwjgl.opengl.GL46C.GL_TRIANGLE_STRIP;
import static org.lwjgl.opengl.GL46C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL46C.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL46C.GL_ZERO;

/**
 * Every small RHI enumeration as its GL constant. Pure functions, no context.
 *
 * <p>Task/mesh and ray-tracing stages have no core GL 4.6 enumerant and map to
 * -1: the GL device refuses those pipelines by name, which is why the
 * capability query exists rather than a crash inside the driver.
 */
public final class GlEnumMap {
    private GlEnumMap() { }

    /** GL shader type, or -1 when core GL 4.6 cannot express the stage. */
    public static int shaderType(GfxStage stage) {
        Checks.notNull(stage, "stage");
        switch (stage) {
            case VERTEX: return GL_VERTEX_SHADER;
            case TESSELLATION_CONTROL: return GL_TESS_CONTROL_SHADER;
            case TESSELLATION_EVALUATION: return GL_TESS_EVALUATION_SHADER;
            case GEOMETRY: return GL_GEOMETRY_SHADER;
            case FRAGMENT: return GL_FRAGMENT_SHADER;
            case COMPUTE: return GL_COMPUTE_SHADER;
            case TASK: case MESH:
            case RAYGEN: case MISS: case ANY_HIT: case CLOSEST_HIT: case INTERSECTION:
                return -1;
            default: break;
        }
        throw new AssertionError("unmapped GfxStage: " + stage);
    }

    public static boolean stageSupported(GfxStage stage) {
        return shaderType(stage) != -1;
    }

    public static int topology(Topology t) {
        Checks.notNull(t, "topology");
        switch (t) {
            case POINT_LIST: return GL_POINTS;
            case LINE_LIST: return GL_LINES;
            case LINE_STRIP: return GL_LINE_STRIP;
            case TRIANGLE_LIST: return GL_TRIANGLES;
            case TRIANGLE_STRIP: return GL_TRIANGLE_STRIP;
            case TRIANGLE_FAN: return GL_TRIANGLE_FAN;
            case PATCH_LIST: return GL_PATCHES;
            default: break;
        }
        throw new AssertionError("unmapped Topology: " + t);
    }

    public static int blendFactor(BlendFactor f) {
        Checks.notNull(f, "blendFactor");
        switch (f) {
            case ZERO: return GL_ZERO;
            case ONE: return GL_ONE;
            case SRC_COLOR: return GL_SRC_COLOR;
            case ONE_MINUS_SRC_COLOR: return GL_ONE_MINUS_SRC_COLOR;
            case DST_COLOR: return GL_DST_COLOR;
            case ONE_MINUS_DST_COLOR: return GL_ONE_MINUS_DST_COLOR;
            case SRC_ALPHA: return GL_SRC_ALPHA;
            case ONE_MINUS_SRC_ALPHA: return GL_ONE_MINUS_SRC_ALPHA;
            case DST_ALPHA: return GL_DST_ALPHA;
            case ONE_MINUS_DST_ALPHA: return GL_ONE_MINUS_DST_ALPHA;
            case CONSTANT_COLOR: return GL_CONSTANT_COLOR;
            case ONE_MINUS_CONSTANT_COLOR: return GL_ONE_MINUS_CONSTANT_COLOR;
            case SRC_ALPHA_SATURATE: return GL_SRC_ALPHA_SATURATE;
            default: break;
        }
        throw new AssertionError("unmapped BlendFactor: " + f);
    }

    public static int blendOp(BlendOp op) {
        Checks.notNull(op, "blendOp");
        switch (op) {
            case ADD: return GL_FUNC_ADD;
            case SUBTRACT: return GL_FUNC_SUBTRACT;
            case REVERSE_SUBTRACT: return GL_FUNC_REVERSE_SUBTRACT;
            case MIN: return GL_MIN;
            case MAX: return GL_MAX;
            default: break;
        }
        throw new AssertionError("unmapped BlendOp: " + op);
    }

    public static int compareOp(CompareOp op) {
        Checks.notNull(op, "compareOp");
        switch (op) {
            case NEVER: return GL_NEVER;
            case LESS: return GL_LESS;
            case EQUAL: return GL_EQUAL;
            case LESS_OR_EQUAL: return GL_LEQUAL;
            case GREATER: return GL_GREATER;
            case NOT_EQUAL: return GL_NOTEQUAL;
            case GREATER_OR_EQUAL: return GL_GEQUAL;
            case ALWAYS: return GL_ALWAYS;
            default: break;
        }
        throw new AssertionError("unmapped CompareOp: " + op);
    }

    public static int magFilter(Filter f) {
        Checks.notNull(f, "filter");
        return f == Filter.NEAREST ? GL_NEAREST : GL_LINEAR;
    }

    /** Minification filter, folding the mip mode in the way GL requires. */
    public static int minFilter(Filter f, MipmapMode m, boolean mipmapped) {
        Checks.notNull(f, "filter");
        Checks.notNull(m, "mipmapMode");
        if (!mipmapped) return magFilter(f);
        if (f == Filter.NEAREST) return m == MipmapMode.NEAREST ? GL_NEAREST_MIPMAP_NEAREST : GL_NEAREST_MIPMAP_LINEAR;
        return m == MipmapMode.NEAREST ? GL_LINEAR_MIPMAP_NEAREST : GL_LINEAR_MIPMAP_LINEAR;
    }

    public static int addressMode(AddressMode m) {
        Checks.notNull(m, "addressMode");
        switch (m) {
            case REPEAT: return GL_REPEAT;
            case MIRRORED_REPEAT: return GL_MIRRORED_REPEAT;
            case CLAMP_TO_EDGE: return GL_CLAMP_TO_EDGE;
            case CLAMP_TO_BORDER: return GL_CLAMP_TO_BORDER;
            default: break;
        }
        throw new AssertionError("unmapped AddressMode: " + m);
    }

    public static boolean cullEnabled(CullMode c) {
        Checks.notNull(c, "cullMode");
        return c != CullMode.NONE;
    }

    /** Cull face; only meaningful when {@link #cullEnabled} is true. */
    public static int cullFace(CullMode c) {
        Checks.notNull(c, "cullMode");
        switch (c) {
            case FRONT: return GL_FRONT;
            case BACK: return GL_BACK;
            case FRONT_AND_BACK: return GL_FRONT_AND_BACK;
            case NONE: return GL_BACK;
            default: break;
        }
        throw new AssertionError("unmapped CullMode: " + c);
    }

    public static int frontFace(FrontFace f) {
        Checks.notNull(f, "frontFace");
        return f == FrontFace.CLOCKWISE ? GL_CW : GL_CCW;
    }

    public static int textureTarget(ImageType type, int samples) {
        return textureTarget(type, samples, 1);
    }

    /**
     * Texture target, honouring sample count AND layer count: a 6-layer cube is
     * still one cube map, but a 12-layer cube is a cube array, and a layered 2D
     * image is a 2D array, never a tall 2D texture.
     */
    public static int textureTarget(ImageType type, int samples, int layers) {
        Checks.notNull(type, "imageType");
        switch (type) {
            case TEXTURE_1D: return layers > 1 ? GL_TEXTURE_1D_ARRAY : GL_TEXTURE_1D;
            case TEXTURE_2D:
                if (samples > 1) return GL_TEXTURE_2D_MULTISAMPLE;
                return layers > 1 ? GL_TEXTURE_2D_ARRAY : GL_TEXTURE_2D;
            case TEXTURE_3D: return GL_TEXTURE_3D;
            case CUBE: return layers > 6 ? GL_TEXTURE_CUBE_MAP_ARRAY : GL_TEXTURE_CUBE_MAP;
            default: break;
        }
        throw new AssertionError("unmapped ImageType: " + type);
    }

    /** How a {@link GfxFormat} is fed to {@code glVertexAttribPointer}. */
    public static final class AttributeInfo {
        /** Component count, or GL_BGRA for the packed BGRA vertex format. */
        public final int size;
        public final int type;
        public final boolean normalized;
        public final boolean integer;
        AttributeInfo(int size, int type, boolean normalized, boolean integer) {
            this.size = size; this.type = type;
            this.normalized = normalized; this.integer = integer;
        }
    }

    /**
     * Decodes a format as a vertex attribute, or null when the format cannot be
     * one: depth, compressed and UNDEFINED formats never reach an attribute.
     * Integer formats need the {@code *I*} entry points; the flag says so.
     */
    public static AttributeInfo attribute(GfxFormat f) {
        Checks.notNull(f, "format");
        switch (f) {
            case R8_UNORM: return new AttributeInfo(1, org.lwjgl.opengl.GL46C.GL_UNSIGNED_BYTE, true, false);
            case R8_SNORM: return new AttributeInfo(1, org.lwjgl.opengl.GL46C.GL_BYTE, true, false);
            case R8_UINT: return new AttributeInfo(1, org.lwjgl.opengl.GL46C.GL_UNSIGNED_BYTE, false, true);
            case R8_SINT: return new AttributeInfo(1, org.lwjgl.opengl.GL46C.GL_BYTE, false, true);
            case R8_SRGB: return new AttributeInfo(1, org.lwjgl.opengl.GL46C.GL_UNSIGNED_BYTE, true, false);
            case RG8_UNORM: return new AttributeInfo(2, org.lwjgl.opengl.GL46C.GL_UNSIGNED_BYTE, true, false);
            case RG8_UINT: return new AttributeInfo(2, org.lwjgl.opengl.GL46C.GL_UNSIGNED_BYTE, false, true);
            case RG8_SRGB: return new AttributeInfo(2, org.lwjgl.opengl.GL46C.GL_UNSIGNED_BYTE, true, false);
            case RGBA8_UNORM: return new AttributeInfo(4, org.lwjgl.opengl.GL46C.GL_UNSIGNED_BYTE, true, false);
            case RGBA8_SNORM: return new AttributeInfo(4, org.lwjgl.opengl.GL46C.GL_BYTE, true, false);
            case RGBA8_UINT: return new AttributeInfo(4, org.lwjgl.opengl.GL46C.GL_UNSIGNED_BYTE, false, true);
            case RGBA8_SINT: return new AttributeInfo(4, org.lwjgl.opengl.GL46C.GL_BYTE, false, true);
            case RGBA8_SRGB: return new AttributeInfo(4, org.lwjgl.opengl.GL46C.GL_UNSIGNED_BYTE, true, false);
            case BGRA8_UNORM:
                // GL_BGRA is a legal "size" for glVertexAttribPointer, not just uploads.
                return new AttributeInfo(org.lwjgl.opengl.GL46C.GL_BGRA,
                        org.lwjgl.opengl.GL46C.GL_UNSIGNED_BYTE, true, false);
            case R16_UNORM: return new AttributeInfo(1, org.lwjgl.opengl.GL46C.GL_UNSIGNED_SHORT, true, false);
            case R16F: return new AttributeInfo(1, org.lwjgl.opengl.GL46C.GL_HALF_FLOAT, false, false);
            case RG16F: return new AttributeInfo(2, org.lwjgl.opengl.GL46C.GL_HALF_FLOAT, false, false);
            case RGBA16F: return new AttributeInfo(4, org.lwjgl.opengl.GL46C.GL_HALF_FLOAT, false, false);
            case RGBA16_UNORM:
                return new AttributeInfo(4, org.lwjgl.opengl.GL46C.GL_UNSIGNED_SHORT, true, false);
            case RGBA16_UINT:
                return new AttributeInfo(4, org.lwjgl.opengl.GL46C.GL_UNSIGNED_SHORT, false, true);
            case R32_UINT: return new AttributeInfo(1, org.lwjgl.opengl.GL46C.GL_UNSIGNED_INT, false, true);
            case R32_SINT: return new AttributeInfo(1, org.lwjgl.opengl.GL46C.GL_INT, false, true);
            case R32F: return new AttributeInfo(1, org.lwjgl.opengl.GL46C.GL_FLOAT, false, false);
            case RG32F: return new AttributeInfo(2, org.lwjgl.opengl.GL46C.GL_FLOAT, false, false);
            case RGB32F: return new AttributeInfo(3, org.lwjgl.opengl.GL46C.GL_FLOAT, false, false);
            case RGBA32_UINT: return new AttributeInfo(4, org.lwjgl.opengl.GL46C.GL_UNSIGNED_INT, false, true);
            case RGBA32F: return new AttributeInfo(4, org.lwjgl.opengl.GL46C.GL_FLOAT, false, false);
            case RGB10A2_UNORM: return new AttributeInfo(4,
                    org.lwjgl.opengl.GL46C.GL_UNSIGNED_INT_2_10_10_10_REV, true, false);
            case RG11B10F: return new AttributeInfo(3,
                    org.lwjgl.opengl.GL46C.GL_UNSIGNED_INT_10F_11F_11F_REV, false, false);
            case R32G32_UINT: return new AttributeInfo(2, org.lwjgl.opengl.GL46C.GL_UNSIGNED_INT, false, true);
            case R32G32B32A32_UINT:
                return new AttributeInfo(4, org.lwjgl.opengl.GL46C.GL_UNSIGNED_INT, false, true);
            default: return null;
        }
    }
}
