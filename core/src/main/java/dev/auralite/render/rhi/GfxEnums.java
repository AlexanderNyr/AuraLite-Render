package dev.auralite.render.rhi;

/** Small, closed enumerations shared by descriptors and the render graph. */
public final class GfxEnums {
    private GfxEnums() { }

    public enum BackendType {
        /** Recording backend: no GPU, used by every host-side test gate. */
        NULL,
        OPENGL_46,
        VULKAN_13
    }

    public enum ImageType { TEXTURE_1D, TEXTURE_2D, TEXTURE_3D, CUBE }

    public enum LoadOp { LOAD, CLEAR, DONT_CARE }
    public enum StoreOp { STORE, DONT_CARE, RESOLVE }

    public enum Filter { NEAREST, LINEAR }
    public enum MipmapMode { NEAREST, LINEAR }
    public enum AddressMode { REPEAT, MIRRORED_REPEAT, CLAMP_TO_EDGE, CLAMP_TO_BORDER }

    public enum CompareOp {
        NEVER, LESS, EQUAL, LESS_OR_EQUAL, GREATER, NOT_EQUAL, GREATER_OR_EQUAL, ALWAYS
    }

    public enum BlendFactor {
        ZERO, ONE,
        SRC_COLOR, ONE_MINUS_SRC_COLOR,
        DST_COLOR, ONE_MINUS_DST_COLOR,
        SRC_ALPHA, ONE_MINUS_SRC_ALPHA,
        DST_ALPHA, ONE_MINUS_DST_ALPHA,
        CONSTANT_COLOR, ONE_MINUS_CONSTANT_COLOR,
        SRC_ALPHA_SATURATE
    }

    public enum BlendOp { ADD, SUBTRACT, REVERSE_SUBTRACT, MIN, MAX }

    public enum CullMode { NONE, FRONT, BACK, FRONT_AND_BACK }
    public enum FrontFace { COUNTER_CLOCKWISE, CLOCKWISE }

    public enum Topology {
        POINT_LIST, LINE_LIST, LINE_STRIP, TRIANGLE_LIST, TRIANGLE_STRIP,
        TRIANGLE_FAN, PATCH_LIST
    }

    public enum MemoryDomain {
        DEVICE_LOCAL, HOST_VISIBLE, HOST_VISIBLE_DEVICE_LOCAL
    }

    public enum QueueType { GRAPHICS, COMPUTE, TRANSFER }

    /**
     * State a resource is in, for layout/barrier inference.
     *
     * <p>OpenGL hides this behind driver-side tracking; Vulkan requires it
     * explicitly. The render graph infers it once and the GL backend simply
     * ignores the transitions, which is why both backends can share one graph.
     */
    public enum ResourceState {
        UNDEFINED,
        COLOR_ATTACHMENT_WRITE,
        DEPTH_STENCIL_WRITE,
        DEPTH_STENCIL_READ,
        SHADER_READ,
        STORAGE_WRITE,
        STORAGE_READ,
        COPY_SOURCE,
        COPY_DEST,
        PRESENT,
        HOST_READ
    }

    /** Access mask used for barrier generation. Bitwise-OR-able. */
    public static final class Access {
        private Access() { }
        public static final int NONE = 0;
        public static final int INPUT_ATTACHMENT_READ = 1;
        public static final int SHADER_READ = 1 << 1;
        public static final int SHADER_WRITE = 1 << 2;
        public static final int COLOR_ATTACHMENT_READ = 1 << 3;
        public static final int COLOR_ATTACHMENT_WRITE = 1 << 4;
        public static final int DEPTH_STENCIL_READ = 1 << 5;
        public static final int DEPTH_STENCIL_WRITE = 1 << 6;
        public static final int TRANSFER_READ = 1 << 7;
        public static final int TRANSFER_WRITE = 1 << 8;
        public static final int HOST_READ = 1 << 9;
        public static final int HOST_WRITE = 1 << 10;
        public static final int MEMORY_READ = 1 << 11;
        public static final int MEMORY_WRITE = 1 << 12;

        public static boolean isWrite(int mask) {
            return (mask & (SHADER_WRITE | COLOR_ATTACHMENT_WRITE | DEPTH_STENCIL_WRITE
                    | TRANSFER_WRITE | HOST_WRITE | MEMORY_WRITE)) != 0;
        }
    }

    /** Pass kind, drives which pipeline type a graph pass must bind. */
    public enum PassType {
        RASTER, COMPUTE, COPY, TRANSFER_BLIT, EXTERNAL
    }
}
