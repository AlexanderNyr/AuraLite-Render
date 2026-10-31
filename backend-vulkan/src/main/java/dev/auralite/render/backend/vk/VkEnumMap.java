package dev.auralite.render.backend.vk;

import dev.auralite.render.rhi.GfxEnums.AddressMode;
import dev.auralite.render.rhi.GfxEnums.BlendFactor;
import dev.auralite.render.rhi.GfxEnums.BlendOp;
import dev.auralite.render.rhi.GfxEnums.CompareOp;
import dev.auralite.render.rhi.GfxEnums.CullMode;
import dev.auralite.render.rhi.GfxEnums.Filter;
import dev.auralite.render.rhi.GfxEnums.FrontFace;
import dev.auralite.render.rhi.GfxEnums.ImageType;
import dev.auralite.render.rhi.GfxEnums.LoadOp;
import dev.auralite.render.rhi.GfxEnums.MemoryDomain;
import dev.auralite.render.rhi.GfxEnums.MipmapMode;
import dev.auralite.render.rhi.GfxEnums.ResourceState;
import dev.auralite.render.rhi.GfxEnums.StoreOp;
import dev.auralite.render.rhi.GfxEnums.Topology;
import dev.auralite.render.rhi.GfxStage;
import dev.auralite.render.rhi.GfxUsage;
import dev.auralite.render.util.Checks;

import static org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_MESH_BIT_EXT;
import static org.lwjgl.vulkan.EXTMeshShader.VK_SHADER_STAGE_TASK_BIT_EXT;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_INTERSECTION_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_MISS_BIT_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_SHADER_STAGE_RAYGEN_BIT_KHR;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_HOST_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_HOST_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_INPUT_ATTACHMENT_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_MEMORY_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_MEMORY_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_SHADER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_SHADER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_TRANSFER_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_TRANSFER_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_ATTACHMENT_LOAD_OP_CLEAR;
import static org.lwjgl.vulkan.VK13.VK_ATTACHMENT_LOAD_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK13.VK_ATTACHMENT_LOAD_OP_LOAD;
import static org.lwjgl.vulkan.VK13.VK_ATTACHMENT_STORE_OP_DONT_CARE;
import static org.lwjgl.vulkan.VK13.VK_ATTACHMENT_STORE_OP_STORE;
import static org.lwjgl.vulkan.VK13.VK_BLEND_FACTOR_CONSTANT_COLOR;
import static org.lwjgl.vulkan.VK13.VK_BLEND_FACTOR_DST_ALPHA;
import static org.lwjgl.vulkan.VK13.VK_BLEND_FACTOR_DST_COLOR;
import static org.lwjgl.vulkan.VK13.VK_BLEND_FACTOR_ONE;
import static org.lwjgl.vulkan.VK13.VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
import static org.lwjgl.vulkan.VK13.VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
import static org.lwjgl.vulkan.VK13.VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
import static org.lwjgl.vulkan.VK13.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.vulkan.VK13.VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
import static org.lwjgl.vulkan.VK13.VK_BLEND_FACTOR_SRC_ALPHA;
import static org.lwjgl.vulkan.VK13.VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
import static org.lwjgl.vulkan.VK13.VK_BLEND_FACTOR_SRC_COLOR;
import static org.lwjgl.vulkan.VK13.VK_BLEND_FACTOR_ZERO;
import static org.lwjgl.vulkan.VK13.VK_BLEND_OP_ADD;
import static org.lwjgl.vulkan.VK13.VK_BLEND_OP_MAX;
import static org.lwjgl.vulkan.VK13.VK_BLEND_OP_MIN;
import static org.lwjgl.vulkan.VK13.VK_BLEND_OP_REVERSE_SUBTRACT;
import static org.lwjgl.vulkan.VK13.VK_BLEND_OP_SUBTRACT;
import static org.lwjgl.vulkan.VK13.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK13.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK13.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_COMPARE_OP_ALWAYS;
import static org.lwjgl.vulkan.VK13.VK_COMPARE_OP_EQUAL;
import static org.lwjgl.vulkan.VK13.VK_COMPARE_OP_GREATER;
import static org.lwjgl.vulkan.VK13.VK_COMPARE_OP_GREATER_OR_EQUAL;
import static org.lwjgl.vulkan.VK13.VK_COMPARE_OP_LESS;
import static org.lwjgl.vulkan.VK13.VK_COMPARE_OP_LESS_OR_EQUAL;
import static org.lwjgl.vulkan.VK13.VK_COMPARE_OP_NEVER;
import static org.lwjgl.vulkan.VK13.VK_COMPARE_OP_NOT_EQUAL;
import static org.lwjgl.vulkan.VK13.VK_CULL_MODE_BACK_BIT;
import static org.lwjgl.vulkan.VK13.VK_CULL_MODE_FRONT_AND_BACK;
import static org.lwjgl.vulkan.VK13.VK_CULL_MODE_FRONT_BIT;
import static org.lwjgl.vulkan.VK13.VK_CULL_MODE_NONE;
import static org.lwjgl.vulkan.VK13.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK13.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK13.VK_FRONT_FACE_CLOCKWISE;
import static org.lwjgl.vulkan.VK13.VK_FRONT_FACE_COUNTER_CLOCKWISE;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_GENERAL;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_TYPE_1D;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_TYPE_2D;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_TYPE_3D;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_VIEW_TYPE_1D;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_VIEW_TYPE_1D_ARRAY;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_VIEW_TYPE_2D;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_VIEW_TYPE_2D_ARRAY;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_VIEW_TYPE_3D;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_VIEW_TYPE_CUBE;
import static org.lwjgl.vulkan.VK13.VK_IMAGE_VIEW_TYPE_CUBE_ARRAY;
import static org.lwjgl.vulkan.VK13.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
import static org.lwjgl.vulkan.VK13.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
import static org.lwjgl.vulkan.VK13.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_HOST_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_TRANSFER_BIT;
import static org.lwjgl.vulkan.VK13.VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
import static org.lwjgl.vulkan.VK13.VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
import static org.lwjgl.vulkan.VK13.VK_PRIMITIVE_TOPOLOGY_PATCH_LIST;
import static org.lwjgl.vulkan.VK13.VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
import static org.lwjgl.vulkan.VK13.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
import static org.lwjgl.vulkan.VK13.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
import static org.lwjgl.vulkan.VK13.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
import static org.lwjgl.vulkan.VK13.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
import static org.lwjgl.vulkan.VK13.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
import static org.lwjgl.vulkan.VK13.VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
import static org.lwjgl.vulkan.VK13.VK_SAMPLER_ADDRESS_MODE_REPEAT;
import static org.lwjgl.vulkan.VK13.VK_SAMPLER_MIPMAP_MODE_LINEAR;
import static org.lwjgl.vulkan.VK13.VK_SAMPLER_MIPMAP_MODE_NEAREST;
import static org.lwjgl.vulkan.VK13.VK_SHADER_STAGE_COMPUTE_BIT;
import static org.lwjgl.vulkan.VK13.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK13.VK_SHADER_STAGE_GEOMETRY_BIT;
import static org.lwjgl.vulkan.VK13.VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT;
import static org.lwjgl.vulkan.VK13.VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT;
import static org.lwjgl.vulkan.VK13.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

/**
 * Every small RHI enumeration as its Vulkan constant. Pure functions, no device.
 *
 * <p>Mesh/ray stages map to their EXT/KHR bits: the RHI exposes what Iris cannot
 * express, and the Vulkan lane is where those stages actually run. A device
 * without the extension refuses the pipeline at creation time, never mid-frame.
 */
public final class VkEnumMap {
    private VkEnumMap() { }

    public static int shaderStage(GfxStage stage) {
        Checks.notNull(stage, "stage");
        switch (stage) {
            case VERTEX: return VK_SHADER_STAGE_VERTEX_BIT;
            case TESSELLATION_CONTROL: return VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT;
            case TESSELLATION_EVALUATION: return VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT;
            case GEOMETRY: return VK_SHADER_STAGE_GEOMETRY_BIT;
            case FRAGMENT: return VK_SHADER_STAGE_FRAGMENT_BIT;
            case COMPUTE: return VK_SHADER_STAGE_COMPUTE_BIT;
            case TASK: return VK_SHADER_STAGE_TASK_BIT_EXT;
            case MESH: return VK_SHADER_STAGE_MESH_BIT_EXT;
            case RAYGEN: return VK_SHADER_STAGE_RAYGEN_BIT_KHR;
            case MISS: return VK_SHADER_STAGE_MISS_BIT_KHR;
            case ANY_HIT: return VK_SHADER_STAGE_ANY_HIT_BIT_KHR;
            case CLOSEST_HIT: return VK_SHADER_STAGE_CLOSEST_HIT_BIT_KHR;
            case INTERSECTION: return VK_SHADER_STAGE_INTERSECTION_BIT_KHR;
            default: break;
        }
        throw new AssertionError("unmapped GfxStage: " + stage);
    }

    public static int topology(Topology t) {
        Checks.notNull(t, "topology");
        switch (t) {
            case POINT_LIST: return VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
            case LINE_LIST: return VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case LINE_STRIP: return VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            case TRIANGLE_LIST: return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            case TRIANGLE_STRIP: return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case TRIANGLE_FAN: return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
            case PATCH_LIST: return VK_PRIMITIVE_TOPOLOGY_PATCH_LIST;
            default: break;
        }
        throw new AssertionError("unmapped Topology: " + t);
    }

    public static int blendFactor(BlendFactor f) {
        Checks.notNull(f, "blendFactor");
        switch (f) {
            case ZERO: return VK_BLEND_FACTOR_ZERO;
            case ONE: return VK_BLEND_FACTOR_ONE;
            case SRC_COLOR: return VK_BLEND_FACTOR_SRC_COLOR;
            case ONE_MINUS_SRC_COLOR: return VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case DST_COLOR: return VK_BLEND_FACTOR_DST_COLOR;
            case ONE_MINUS_DST_COLOR: return VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case SRC_ALPHA: return VK_BLEND_FACTOR_SRC_ALPHA;
            case ONE_MINUS_SRC_ALPHA: return VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case DST_ALPHA: return VK_BLEND_FACTOR_DST_ALPHA;
            case ONE_MINUS_DST_ALPHA: return VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case CONSTANT_COLOR: return VK_BLEND_FACTOR_CONSTANT_COLOR;
            case ONE_MINUS_CONSTANT_COLOR: return VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case SRC_ALPHA_SATURATE: return VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            default: break;
        }
        throw new AssertionError("unmapped BlendFactor: " + f);
    }

    public static int blendOp(BlendOp op) {
        Checks.notNull(op, "blendOp");
        switch (op) {
            case ADD: return VK_BLEND_OP_ADD;
            case SUBTRACT: return VK_BLEND_OP_SUBTRACT;
            case REVERSE_SUBTRACT: return VK_BLEND_OP_REVERSE_SUBTRACT;
            case MIN: return VK_BLEND_OP_MIN;
            case MAX: return VK_BLEND_OP_MAX;
            default: break;
        }
        throw new AssertionError("unmapped BlendOp: " + op);
    }

    public static int compareOp(CompareOp op) {
        Checks.notNull(op, "compareOp");
        switch (op) {
            case NEVER: return VK_COMPARE_OP_NEVER;
            case LESS: return VK_COMPARE_OP_LESS;
            case EQUAL: return VK_COMPARE_OP_EQUAL;
            case LESS_OR_EQUAL: return VK_COMPARE_OP_LESS_OR_EQUAL;
            case GREATER: return VK_COMPARE_OP_GREATER;
            case NOT_EQUAL: return VK_COMPARE_OP_NOT_EQUAL;
            case GREATER_OR_EQUAL: return VK_COMPARE_OP_GREATER_OR_EQUAL;
            case ALWAYS: return VK_COMPARE_OP_ALWAYS;
            default: break;
        }
        throw new AssertionError("unmapped CompareOp: " + op);
    }

    public static int filter(Filter f) {
        Checks.notNull(f, "filter");
        return f == Filter.NEAREST ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
    }

    public static int mipmapMode(MipmapMode m) {
        Checks.notNull(m, "mipmapMode");
        return m == MipmapMode.NEAREST ? VK_SAMPLER_MIPMAP_MODE_NEAREST : VK_SAMPLER_MIPMAP_MODE_LINEAR;
    }

    public static int addressMode(AddressMode m) {
        Checks.notNull(m, "addressMode");
        switch (m) {
            case REPEAT: return VK_SAMPLER_ADDRESS_MODE_REPEAT;
            case MIRRORED_REPEAT: return VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
            case CLAMP_TO_EDGE: return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            case CLAMP_TO_BORDER: return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
            default: break;
        }
        throw new AssertionError("unmapped AddressMode: " + m);
    }

    public static int cullMode(CullMode c) {
        Checks.notNull(c, "cullMode");
        switch (c) {
            case NONE: return VK_CULL_MODE_NONE;
            case FRONT: return VK_CULL_MODE_FRONT_BIT;
            case BACK: return VK_CULL_MODE_BACK_BIT;
            case FRONT_AND_BACK: return VK_CULL_MODE_FRONT_AND_BACK;
            default: break;
        }
        throw new AssertionError("unmapped CullMode: " + c);
    }

    public static int frontFace(FrontFace f) {
        Checks.notNull(f, "frontFace");
        return f == FrontFace.CLOCKWISE ? VK_FRONT_FACE_CLOCKWISE : VK_FRONT_FACE_COUNTER_CLOCKWISE;
    }

    /** Image dimensionality. A cube is a 2D image; the view says CUBE. */
    public static int imageType(ImageType type) {
        Checks.notNull(type, "imageType");
        switch (type) {
            case TEXTURE_1D: return VK_IMAGE_TYPE_1D;
            case TEXTURE_2D: return VK_IMAGE_TYPE_2D;
            case TEXTURE_3D: return VK_IMAGE_TYPE_3D;
            case CUBE: return VK_IMAGE_TYPE_2D;
            default: break;
        }
        throw new AssertionError("unmapped ImageType: " + type);
    }

    public static int viewType(ImageType type, boolean array) {
        Checks.notNull(type, "imageType");
        switch (type) {
            case TEXTURE_1D: return array ? VK_IMAGE_VIEW_TYPE_1D_ARRAY : VK_IMAGE_VIEW_TYPE_1D;
            case TEXTURE_2D: return array ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK_IMAGE_VIEW_TYPE_2D;
            case TEXTURE_3D: return VK_IMAGE_VIEW_TYPE_3D;
            case CUBE: return array ? VK_IMAGE_VIEW_TYPE_CUBE_ARRAY : VK_IMAGE_VIEW_TYPE_CUBE;
            default: break;
        }
        throw new AssertionError("unmapped ImageType: " + type);
    }

    public static int loadOp(LoadOp op) {
        Checks.notNull(op, "loadOp");
        switch (op) {
            case LOAD: return VK_ATTACHMENT_LOAD_OP_LOAD;
            case CLEAR: return VK_ATTACHMENT_LOAD_OP_CLEAR;
            case DONT_CARE: return VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            default: break;
        }
        throw new AssertionError("unmapped LoadOp: " + op);
    }

    /**
     * Store op. RESOLVE has no store enumerant: on Vulkan a resolve is a
     * separate attachment, so the multisampled image itself simply stores.
     */
    public static int storeOp(StoreOp op) {
        Checks.notNull(op, "storeOp");
        switch (op) {
            case STORE: return VK_ATTACHMENT_STORE_OP_STORE;
            case DONT_CARE: return VK_ATTACHMENT_STORE_OP_DONT_CARE;
            case RESOLVE: return VK_ATTACHMENT_STORE_OP_STORE;
            default: break;
        }
        throw new AssertionError("unmapped StoreOp: " + op);
    }

    /** The layout each graph-inferred resource state executes in. */
    public static int imageLayout(ResourceState state) {
        Checks.notNull(state, "resourceState");
        switch (state) {
            case UNDEFINED: return VK_IMAGE_LAYOUT_UNDEFINED;
            case COLOR_ATTACHMENT_WRITE: return VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
            case DEPTH_STENCIL_WRITE: return VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
            case DEPTH_STENCIL_READ: return VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL;
            case SHADER_READ: return VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            case STORAGE_WRITE: return VK_IMAGE_LAYOUT_GENERAL;
            case STORAGE_READ: return VK_IMAGE_LAYOUT_GENERAL;
            case COPY_SOURCE: return VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            case COPY_DEST: return VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            case PRESENT: return VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
            case HOST_READ: return VK_IMAGE_LAYOUT_GENERAL;
            default: break;
        }
        throw new AssertionError("unmapped ResourceState: " + state);
    }

    /**
     * The pipeline stage that produces or consumes a state, for barrier
     * construction. Deliberately coarse -- one stage per state, the earliest
     * writer or the latest reader -- because a missed hazard is a GPU hang and
     * an over-broad barrier is a few idle cycles.
     */
    public static int stageForState(ResourceState state) {
        Checks.notNull(state, "resourceState");
        switch (state) {
            case UNDEFINED: return VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            case COLOR_ATTACHMENT_WRITE: return VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            case DEPTH_STENCIL_WRITE:
                return VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            case DEPTH_STENCIL_READ: return VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
            case SHADER_READ: return VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            case STORAGE_WRITE: return VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
            case STORAGE_READ: return VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
            case COPY_SOURCE: return VK_PIPELINE_STAGE_TRANSFER_BIT;
            case COPY_DEST: return VK_PIPELINE_STAGE_TRANSFER_BIT;
            case PRESENT: return VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
            case HOST_READ: return VK_PIPELINE_STAGE_HOST_BIT;
            default: break;
        }
        throw new AssertionError("unmapped ResourceState: " + state);
    }

    /**
     * The pipeline stages that can produce an access mask, for buffer barriers,
     * which carry no resource states. Coarse on purpose: a stage that is too
     * early makes the access invisible, a stage that is too broad costs cycles.
     */
    public static int stageForAccess(int mask) {
        if (mask == 0) return VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        int out = 0;
        int m = dev.auralite.render.rhi.GfxEnums.Access.MEMORY_READ
                | dev.auralite.render.rhi.GfxEnums.Access.MEMORY_WRITE;
        if ((mask & m) != 0) return VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        if ((mask & (dev.auralite.render.rhi.GfxEnums.Access.TRANSFER_READ
                | dev.auralite.render.rhi.GfxEnums.Access.TRANSFER_WRITE)) != 0) {
            out |= VK_PIPELINE_STAGE_TRANSFER_BIT;
        }
        if ((mask & (dev.auralite.render.rhi.GfxEnums.Access.HOST_READ
                | dev.auralite.render.rhi.GfxEnums.Access.HOST_WRITE)) != 0) {
            out |= VK_PIPELINE_STAGE_HOST_BIT;
        }
        if ((mask & (dev.auralite.render.rhi.GfxEnums.Access.SHADER_READ
                | dev.auralite.render.rhi.GfxEnums.Access.SHADER_WRITE)) != 0) {
            out |= VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        }
        if ((mask & (dev.auralite.render.rhi.GfxEnums.Access.COLOR_ATTACHMENT_READ
                | dev.auralite.render.rhi.GfxEnums.Access.COLOR_ATTACHMENT_WRITE)) != 0) {
            out |= VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        }
        if ((mask & (dev.auralite.render.rhi.GfxEnums.Access.DEPTH_STENCIL_READ
                | dev.auralite.render.rhi.GfxEnums.Access.DEPTH_STENCIL_WRITE)) != 0) {
            out |= VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
        }
        if ((mask & dev.auralite.render.rhi.GfxEnums.Access.INPUT_ATTACHMENT_READ) != 0) {
            out |= VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        }
        return out == 0 ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : out;
    }

    /** ORs the RHI access mask into {@code VkAccessFlags}. */
    public static int accessFlags(int mask) {
        int out = 0;
        if ((mask & dev.auralite.render.rhi.GfxEnums.Access.INPUT_ATTACHMENT_READ) != 0) {
            out |= VK_ACCESS_INPUT_ATTACHMENT_READ_BIT;
        }
        if ((mask & dev.auralite.render.rhi.GfxEnums.Access.SHADER_READ) != 0) out |= VK_ACCESS_SHADER_READ_BIT;
        if ((mask & dev.auralite.render.rhi.GfxEnums.Access.SHADER_WRITE) != 0) out |= VK_ACCESS_SHADER_WRITE_BIT;
        if ((mask & dev.auralite.render.rhi.GfxEnums.Access.COLOR_ATTACHMENT_READ) != 0) {
            out |= VK_ACCESS_COLOR_ATTACHMENT_READ_BIT;
        }
        if ((mask & dev.auralite.render.rhi.GfxEnums.Access.COLOR_ATTACHMENT_WRITE) != 0) {
            out |= VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
        }
        if ((mask & dev.auralite.render.rhi.GfxEnums.Access.DEPTH_STENCIL_READ) != 0) {
            out |= VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT;
        }
        if ((mask & dev.auralite.render.rhi.GfxEnums.Access.DEPTH_STENCIL_WRITE) != 0) {
            out |= VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
        }
        if ((mask & dev.auralite.render.rhi.GfxEnums.Access.TRANSFER_READ) != 0) out |= VK_ACCESS_TRANSFER_READ_BIT;
        if ((mask & dev.auralite.render.rhi.GfxEnums.Access.TRANSFER_WRITE) != 0) out |= VK_ACCESS_TRANSFER_WRITE_BIT;
        if ((mask & dev.auralite.render.rhi.GfxEnums.Access.HOST_READ) != 0) out |= VK_ACCESS_HOST_READ_BIT;
        if ((mask & dev.auralite.render.rhi.GfxEnums.Access.HOST_WRITE) != 0) out |= VK_ACCESS_HOST_WRITE_BIT;
        if ((mask & dev.auralite.render.rhi.GfxEnums.Access.MEMORY_READ) != 0) out |= VK_ACCESS_MEMORY_READ_BIT;
        if ((mask & dev.auralite.render.rhi.GfxEnums.Access.MEMORY_WRITE) != 0) out |= VK_ACCESS_MEMORY_WRITE_BIT;
        return out;
    }

    /** ORs the RHI buffer usage mask into {@code VkBufferUsageFlags}. */
    public static int bufferUsage(int usage) {
        int out = 0;
        if ((usage & GfxUsage.BUFFER_VERTEX) != 0) out |= VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
        if ((usage & GfxUsage.BUFFER_INDEX) != 0) out |= VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
        if ((usage & GfxUsage.BUFFER_UNIFORM) != 0) out |= VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
        if ((usage & GfxUsage.BUFFER_STORAGE) != 0) out |= VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        if ((usage & GfxUsage.BUFFER_INDIRECT) != 0) out |= VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
        if ((usage & GfxUsage.BUFFER_TRANSFER_SRC) != 0) out |= VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        if ((usage & GfxUsage.BUFFER_TRANSFER_DST) != 0) out |= VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if ((usage & GfxUsage.BUFFER_SHADER_DEVICE_ADDRESS) != 0) {
            out |= VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
        }
        return out;
    }

    /**
     * ORs the RHI image usage mask into {@code VkImageUsageFlags}. PRESENT
     * contributes nothing: presenting is a layout, not a usage. Acceleration
     * structures live in buffers on Vulkan, so that flag contributes nothing
     * here either.
     */
    public static int imageUsage(int usage) {
        int out = 0;
        if ((usage & GfxUsage.IMAGE_SAMPLED) != 0) out |= VK_IMAGE_USAGE_SAMPLED_BIT;
        if ((usage & GfxUsage.IMAGE_STORAGE) != 0) out |= VK_IMAGE_USAGE_STORAGE_BIT;
        if ((usage & GfxUsage.IMAGE_COLOR_ATTACHMENT) != 0) out |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        if ((usage & GfxUsage.IMAGE_DEPTH_STENCIL_ATTACHMENT) != 0) {
            out |= VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
        }
        if ((usage & GfxUsage.IMAGE_TRANSFER_SRC) != 0) out |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        if ((usage & GfxUsage.IMAGE_TRANSFER_DST) != 0) out |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        if ((usage & GfxUsage.IMAGE_INPUT_ATTACHMENT) != 0) out |= VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;
        if ((usage & GfxUsage.IMAGE_TRANSIENT_ATTACHMENT) != 0) out |= VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT;
        return out;
    }

    /** Memory properties every domain requires. Host-visible is always coherent. */
    public static int memoryProperties(MemoryDomain domain) {
        Checks.notNull(domain, "memoryDomain");
        switch (domain) {
            case DEVICE_LOCAL: return VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            case HOST_VISIBLE:
                return VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            case HOST_VISIBLE_DEVICE_LOCAL:
                return VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                        | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            default: break;
        }
        throw new AssertionError("unmapped MemoryDomain: " + domain);
    }

}
