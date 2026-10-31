package dev.auralite.render.rhi;

/**
 * Optional device capabilities.
 *
 * <p>A pack declares the set it requires; the loader intersects that with
 * {@link GfxDevice#capabilities()} and refuses the pack with a named reason
 * before any shader is compiled. This is the honest alternative to the Iris
 * behaviour of compiling a program that then fails at first use.
 */
public enum GfxCapability {
    /** Compute shaders. Absent from the Iris pack API entirely. */
    COMPUTE_SHADERS,
    /** 3D textures, needed by the biome field and volumetric LUTs. */
    TEXTURE_3D,
    /** Integer (usampler/isampler) textures, needed by the biome id field. */
    INTEGER_TEXTURES,
    /** Shader storage buffers. */
    STORAGE_BUFFERS,
    /** Writable storage images (imageStore) outside compute. */
    FRAGMENT_STORAGE_IMAGES,
    /** Indirect draw from a GPU buffer. */
    INDIRECT_DRAW,
    /** glMultiDrawElementsIndirect / vkCmdDrawIndexedIndirectCount. */
    MULTI_DRAW_INDIRECT,
    /** Indirect dispatch with a count buffer. */
    INDIRECT_DISPATCH,
    /** Bindless texture handles. */
    BINDLESS_TEXTURES,
    /** Ray query from a compute or fragment stage (no separate RT pipeline). */
    RAY_QUERY,
    /** Full ray-tracing pipelines. */
    RAY_TRACING_PIPELINE,
    /** Task + mesh stages. */
    MESH_SHADERS,
    /** A second queue family usable concurrently with graphics. */
    ASYNC_COMPUTE,
    /** Subgroup/wave intrinsics with a known subgroup size. */
    SUBGROUP_OPS,
    /** Geometry shaders. */
    GEOMETRY_SHADERS,
    /** Tessellation control + evaluation. */
    TESSELLATION,
    /** gl_FragCoord-order-independent programmable blending. */
    FRAGMENT_SHADER_ORDERING,
    /** Conservative rasterisation. */
    CONSERVATIVE_RASTER,
    /** Barycentric coordinate / perspective-correct attribute access in fragment. */
    BARYCENTRICS,
    /** Device-side clock (VK_KHR_shader_clock, GL_ARB_shader_clock). */
    SHADER_CLOCK,
    /** Sparse/resident texture binding. */
    SPARSE_BINDING,
    /** Viewport and scissor arrays. */
    VIEWPORT_ARRAY,
    /** Sampler-less buffer textures (samplerBuffer / texel buffers). */
    BUFFER_TEXTURES,
    /** Explicit LOD and derivative control. */
    DERIVATIVE_CONTROL,
    /** Dual-source blending. */
    DUAL_SOURCE_BLEND,
    /** Timestamp queries usable for honest per-pass profiling. */
    TIMESTAMPS,
    /** Pipeline statistics queries (clips, primitives). */
    PIPELINE_STATISTICS;
}
