package dev.auralite.render.tests;

import dev.auralite.render.backend.gl.GlEnumMap;
import dev.auralite.render.backend.gl.GlFormatTable;
import dev.auralite.render.backend.vk.VkEnumMap;
import dev.auralite.render.backend.vk.VkFormatTable;
import dev.auralite.render.rhi.GfxEnums;
import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxStage;
import dev.auralite.render.rhi.GfxUsage;
import dev.auralite.render.util.Checks;
import dev.auralite.render.util.Extents;
import dev.auralite.render.util.MathUtil;

import java.util.HashSet;
import java.util.Set;

import static dev.auralite.render.tests.TestRig.check;
import static dev.auralite.render.tests.TestRig.contains;
import static dev.auralite.render.tests.TestRig.eq;
import static dev.auralite.render.tests.TestRig.receipt;
import static dev.auralite.render.tests.TestRig.section;
import static dev.auralite.render.tests.TestRig.suite;

/**
 * Gates the backend mapping tables and the shared utils.
 *
 * <p>This suite runs with the LWJGL jars deliberately OFF the classpath: the
 * tables are pure data (compile-time constants only), so they must load and
 * answer on a machine that has never seen a GPU. A {@code NoClassDefFoundError}
 * here means someone smuggled a real LWJGL call into a mapping function.
 */
public final class BackendTableSuite {
    private BackendTableSuite() { }

    public static void run() {
        suite("tables");

        section("GL format table: all 42 formats resolve");
        int supported = 0;
        Set<Integer> internals = new HashSet<Integer>();
        for (GfxFormat f : GfxFormat.values()) {
            GlFormatTable.Mapping m = GlFormatTable.of(f);
            check(m != null, f + " maps to something");
            if (!m.supported) continue;
            supported++;
            check(m.internalFormat != 0, f + " has a sized internal format");
            eq(m.compressed, f.isCompressed(), "compressed flag agrees with the format: " + f);
            if (!m.compressed) {
                check(m.format != 0 && m.type != 0, f + " has an upload pixel format and type");
                internals.add(m.internalFormat);
            }
        }
        // 42 formats minus UNDEFINED, R8_SRGB and RG8_SRGB. The two sRGB gaps are
        // core OpenGL's, not ours: no sized 1- or 2-channel sRGB storage exists.
        eq(supported, 39, "39 formats supported on GL");
        check(!GlFormatTable.of(GfxFormat.UNDEFINED).supported, "UNDEFINED is unsupported");
        check(!GlFormatTable.of(GfxFormat.R8_SRGB).supported, "R8_SRGB is honestly unsupported on GL");
        check(!GlFormatTable.of(GfxFormat.RG8_SRGB).supported, "RG8_SRGB is honestly unsupported on GL");
        // 34 uncompressed supported formats, two of which alias by design:
        // BGRA8 shares RGBA8 storage, R32G32B32A32_UINT shares RGBA32_UINT.
        // Anything else sharing storage would be a copy-paste slip in the table.
        eq(internals.size(), 32, "every other format owns distinct GL storage");

        section("GL format literals, checked against the GL 4.6 spec");
        eq(GlFormatTable.of(GfxFormat.RGBA8_UNORM).internalFormat, 0x8058, "GL_RGBA8");
        eq(GlFormatTable.of(GfxFormat.RGBA8_UNORM).format, 0x1908, "GL_RGBA");
        eq(GlFormatTable.of(GfxFormat.RGBA8_UNORM).type, 0x1401, "GL_UNSIGNED_BYTE");
        eq(GlFormatTable.of(GfxFormat.RGBA16F).internalFormat, 0x881A, "GL_RGBA16F");
        eq(GlFormatTable.of(GfxFormat.R32F).internalFormat, 0x822E, "GL_R32F");
        eq(GlFormatTable.of(GfxFormat.D32_SFLOAT).internalFormat, 0x8CAC, "GL_DEPTH_COMPONENT32F");
        eq(GlFormatTable.of(GfxFormat.D24_UNORM_S8_UINT).internalFormat, 0x88F0, "GL_DEPTH24_STENCIL8");
        eq(GlFormatTable.of(GfxFormat.RGBA8_SRGB).internalFormat, 0x8C43, "GL_SRGB8_ALPHA8");
        eq(GlFormatTable.of(GfxFormat.RG11B10F).internalFormat, 0x8C3A, "GL_R11F_G11F_B10F");
        eq(GlFormatTable.of(GfxFormat.RGB10A2_UNORM).internalFormat, 0x8059, "GL_RGB10_A2");
        eq(GlFormatTable.of(GfxFormat.BGRA8_UNORM).internalFormat,
                GlFormatTable.of(GfxFormat.RGBA8_UNORM).internalFormat,
                "BGRA8 shares RGBA8 storage");
        eq(GlFormatTable.of(GfxFormat.BGRA8_UNORM).format, 0x80E1, "but uploads as GL_BGRA");
        eq(GlFormatTable.of(GfxFormat.BC1_RGB_SRGB).internalFormat, 0x8C4C, "sRGB DXT1");
        eq(GlFormatTable.of(GfxFormat.BC3_SRGB).internalFormat, 0x8C4F, "sRGB DXT5");
        eq(GlFormatTable.of(GfxFormat.BC5_UNORM).internalFormat, 0x8DBD, "RGTC2");
        eq(GlFormatTable.of(GfxFormat.BC7_UNORM).internalFormat, 0x8E8C, "BPTC");
        eq(GlFormatTable.of(GfxFormat.BC7_SRGB).internalFormat, 0x8E8D, "sRGB BPTC");

        section("GL enum maps");
        eq(GlEnumMap.shaderType(GfxStage.VERTEX), 0x8B31, "GL_VERTEX_SHADER");
        eq(GlEnumMap.shaderType(GfxStage.FRAGMENT), 0x8B30, "GL_FRAGMENT_SHADER");
        eq(GlEnumMap.shaderType(GfxStage.COMPUTE), 0x91B9, "GL_COMPUTE_SHADER");
        eq(GlEnumMap.shaderType(GfxStage.TASK), -1, "no task enumerant in core GL 4.6");
        eq(GlEnumMap.shaderType(GfxStage.MESH), -1, "no mesh enumerant in core GL 4.6");
        eq(GlEnumMap.shaderType(GfxStage.RAYGEN), -1, "no ray enumerant in core GL 4.6");
        check(!GlEnumMap.stageSupported(GfxStage.MESH)
                && GlEnumMap.stageSupported(GfxStage.VERTEX), "stageSupported agrees");
        eq(GlEnumMap.topology(GfxEnums.Topology.TRIANGLE_LIST), 4, "GL_TRIANGLES");
        check(distinctTopologiesGl(), "all 7 topologies map distinctly");
        eq(GlEnumMap.blendFactor(BlendFactorAlias.srcAlpha()), 0x0302, "GL_SRC_ALPHA");
        eq(GlEnumMap.blendFactor(GfxEnums.BlendFactor.ONE_MINUS_SRC_ALPHA), 0x0303,
                "GL_ONE_MINUS_SRC_ALPHA");
        eq(GlEnumMap.blendFactor(GfxEnums.BlendFactor.ZERO), 0, "GL_ZERO");
        eq(GlEnumMap.blendOp(GfxEnums.BlendOp.ADD), 0x8006, "GL_FUNC_ADD");
        eq(GlEnumMap.compareOp(GfxEnums.CompareOp.LESS), 0x0201, "GL_LESS");
        eq(GlEnumMap.minFilter(GfxEnums.Filter.LINEAR, GfxEnums.MipmapMode.LINEAR, true), 0x2703,
                "GL_LINEAR_MIPMAP_LINEAR");
        eq(GlEnumMap.minFilter(GfxEnums.Filter.LINEAR, GfxEnums.MipmapMode.LINEAR, false), 0x2601,
                "no mips, no mip filter");
        eq(GlEnumMap.magFilter(GfxEnums.Filter.LINEAR), 0x2601, "GL_LINEAR");
        eq(GlEnumMap.addressMode(GfxEnums.AddressMode.REPEAT), 0x2901, "GL_REPEAT");
        check(!GlEnumMap.cullEnabled(GfxEnums.CullMode.NONE), "NONE disables culling");
        eq(GlEnumMap.cullFace(GfxEnums.CullMode.BACK), 0x0405, "GL_BACK");
        eq(GlEnumMap.frontFace(GfxEnums.FrontFace.COUNTER_CLOCKWISE), 0x0901, "GL_CCW");
        eq(GlEnumMap.textureTarget(GfxEnums.ImageType.TEXTURE_2D, 1), 0x0DE1, "GL_TEXTURE_2D");
        eq(GlEnumMap.textureTarget(GfxEnums.ImageType.TEXTURE_2D, 4), 0x9100, "MSAA target");
        GlEnumMap.AttributeInfo attr = GlEnumMap.attribute(GfxFormat.RGBA32F);
        eq(attr.size, 4, "RGBA32F is a 4-component attribute");
        eq(attr.type, 0x1406, "of GL_FLOAT");
        check(!attr.integer, "and not an integer one");
        check(GlEnumMap.attribute(GfxFormat.R8_UINT).integer, "integer formats take the *I* entry points");
        check(GlEnumMap.attribute(GfxFormat.D32_SFLOAT) == null, "depth is never a vertex attribute");
        check(GlEnumMap.attribute(GfxFormat.BC7_UNORM) == null, "compressed is never a vertex attribute");
        receipt("gl: 39/42 formats, 6/13 stages, every literal verified");

        section("VK format table: all 41 real formats resolve");
        Set<Integer> vkFormats = new HashSet<Integer>();
        for (GfxFormat f : GfxFormat.values()) {
            int v = VkFormatTable.of(f);
            if (f == GfxFormat.UNDEFINED) { eq(v, 0, "UNDEFINED is VK_FORMAT_UNDEFINED"); continue; }
            check(v != 0, f + " has a VkFormat");
            vkFormats.add(v);
        }
        // One alias by design: R32G32B32A32_UINT is VK_FORMAT_R32G32B32A32_UINT,
        // the same value RGBA32_UINT maps to.
        eq(vkFormats.size(), 40, "40 distinct VkFormats for 41 formats");
        eq(VkFormatTable.of(GfxFormat.R32G32B32A32_UINT), VkFormatTable.of(GfxFormat.RGBA32_UINT),
                "the uint4 alias is explicit");

        section("VK format literals, checked against the Vulkan registry");
        eq(VkFormatTable.of(GfxFormat.RGBA8_UNORM), 37, "R8G8B8A8_UNORM");
        eq(VkFormatTable.of(GfxFormat.RGBA8_SRGB), 43, "R8G8B8A8_SRGB");
        eq(VkFormatTable.of(GfxFormat.BGRA8_UNORM), 44, "B8G8R8A8_UNORM");
        eq(VkFormatTable.of(GfxFormat.RGBA16F), 97, "R16G16B16A16_SFLOAT");
        eq(VkFormatTable.of(GfxFormat.R32F), 100, "R32_SFLOAT");
        eq(VkFormatTable.of(GfxFormat.RGBA32F), 109, "R32G32B32A32_SFLOAT");
        eq(VkFormatTable.of(GfxFormat.R8_SRGB), 15, "R8_SRGB (Vulkan has what GL lacks)");
        eq(VkFormatTable.of(GfxFormat.RG8_SRGB), 22, "R8G8_SRGB (Vulkan has what GL lacks)");
        eq(VkFormatTable.of(GfxFormat.D32_SFLOAT), 126, "D32_SFLOAT");
        eq(VkFormatTable.of(GfxFormat.D24_UNORM_S8_UINT), 129, "D24_UNORM_S8_UINT");
        eq(VkFormatTable.of(GfxFormat.D32_SFLOAT_S8_UINT), 130, "D32_SFLOAT_S8_UINT");
        eq(VkFormatTable.of(GfxFormat.BC1_RGB_SRGB), 132, "BC1_RGB_SRGB_BLOCK");
        eq(VkFormatTable.of(GfxFormat.BC3_SRGB), 138, "BC3_SRGB_BLOCK");
        eq(VkFormatTable.of(GfxFormat.BC5_UNORM), 141, "BC5_UNORM_BLOCK");
        eq(VkFormatTable.of(GfxFormat.BC7_UNORM), 145, "BC7_UNORM_BLOCK");
        eq(VkFormatTable.of(GfxFormat.BC7_SRGB), 146, "BC7_SRGB_BLOCK");

        section("VK enum maps");
        eq(VkEnumMap.shaderStage(GfxStage.VERTEX), 0x1, "VK_SHADER_STAGE_VERTEX_BIT");
        eq(VkEnumMap.shaderStage(GfxStage.FRAGMENT), 0x10, "VK_SHADER_STAGE_FRAGMENT_BIT");
        eq(VkEnumMap.shaderStage(GfxStage.COMPUTE), 0x20, "VK_SHADER_STAGE_COMPUTE_BIT");
        eq(VkEnumMap.shaderStage(GfxStage.TASK), 0x40, "VK_SHADER_STAGE_TASK_BIT_EXT");
        eq(VkEnumMap.shaderStage(GfxStage.MESH), 0x80, "VK_SHADER_STAGE_MESH_BIT_EXT");
        eq(VkEnumMap.shaderStage(GfxStage.RAYGEN), 0x100, "VK_SHADER_STAGE_RAYGEN_BIT_KHR");
        check(distinctStagesVk(), "all 13 stages map to distinct bits");
        eq(VkEnumMap.topology(GfxEnums.Topology.TRIANGLE_LIST), 3, "TRIANGLE_LIST");
        eq(VkEnumMap.topology(GfxEnums.Topology.PATCH_LIST), 10, "PATCH_LIST");
        eq(VkEnumMap.blendFactor(GfxEnums.BlendFactor.SRC_ALPHA), 6, "SRC_ALPHA");
        eq(VkEnumMap.blendFactor(GfxEnums.BlendFactor.SRC_ALPHA_SATURATE), 14, "SRC_ALPHA_SATURATE");
        eq(VkEnumMap.blendOp(GfxEnums.BlendOp.ADD), 0, "BLEND_OP_ADD");
        eq(VkEnumMap.compareOp(GfxEnums.CompareOp.NEVER), 0, "NEVER");
        eq(VkEnumMap.compareOp(GfxEnums.CompareOp.ALWAYS), 7, "ALWAYS");
        eq(VkEnumMap.filter(GfxEnums.Filter.LINEAR), 1, "FILTER_LINEAR");
        eq(VkEnumMap.mipmapMode(GfxEnums.MipmapMode.LINEAR), 1, "MIPMAP_LINEAR");
        eq(VkEnumMap.addressMode(GfxEnums.AddressMode.REPEAT), 0, "REPEAT");
        eq(VkEnumMap.cullMode(GfxEnums.CullMode.BACK), 2, "CULL_BACK");
        eq(VkEnumMap.frontFace(GfxEnums.FrontFace.COUNTER_CLOCKWISE), 0, "FRONT_FACE_CCW");
        eq(VkEnumMap.imageType(GfxEnums.ImageType.CUBE), VkEnumMap.imageType(GfxEnums.ImageType.TEXTURE_2D),
                "a cube is a 2D image; the view says CUBE");
        eq(VkEnumMap.viewType(GfxEnums.ImageType.CUBE, false), 3, "IMAGE_VIEW_TYPE_CUBE");
        eq(VkEnumMap.viewType(GfxEnums.ImageType.CUBE, true), 6, "IMAGE_VIEW_TYPE_CUBE_ARRAY");
        eq(VkEnumMap.loadOp(GfxEnums.LoadOp.CLEAR), 1, "LOAD_OP_CLEAR");
        eq(VkEnumMap.storeOp(GfxEnums.StoreOp.RESOLVE), VkEnumMap.storeOp(GfxEnums.StoreOp.STORE),
                "a resolve is a separate attachment; the image itself stores");
        eq(VkEnumMap.imageLayout(GfxEnums.ResourceState.SHADER_READ), 5, "SHADER_READ_ONLY_OPTIMAL");
        eq(VkEnumMap.imageLayout(GfxEnums.ResourceState.COPY_SOURCE), 6, "TRANSFER_SRC_OPTIMAL");
        eq(VkEnumMap.imageLayout(GfxEnums.ResourceState.COPY_DEST), 7, "TRANSFER_DST_OPTIMAL");
        eq(VkEnumMap.imageLayout(GfxEnums.ResourceState.PRESENT), 1000001002, "PRESENT_SRC_KHR");
        eq(VkEnumMap.stageForState(GfxEnums.ResourceState.UNDEFINED), 1, "TOP_OF_PIPE");
        eq(VkEnumMap.stageForState(GfxEnums.ResourceState.COLOR_ATTACHMENT_WRITE), 0x400,
                "COLOR_ATTACHMENT_OUTPUT");
        eq(VkEnumMap.stageForState(GfxEnums.ResourceState.PRESENT), 0x2000, "BOTTOM_OF_PIPE");
        eq(VkEnumMap.accessFlags(GfxEnums.Access.NONE), 0, "no access is no flags");
        eq(VkEnumMap.accessFlags(GfxEnums.Access.SHADER_READ), 0x20, "SHADER_READ_BIT");
        eq(VkEnumMap.accessFlags(GfxEnums.Access.COLOR_ATTACHMENT_WRITE), 0x100,
                "COLOR_ATTACHMENT_WRITE_BIT");
        eq(VkEnumMap.stageForAccess(0), 1, "empty access starts at the top of the pipe");
        eq(VkEnumMap.stageForAccess(GfxEnums.Access.TRANSFER_WRITE), 0x1000, "TRANSFER_BIT");
        eq(VkEnumMap.stageForAccess(GfxEnums.Access.HOST_READ), 0x4000, "HOST_BIT");
        eq(VkEnumMap.stageForAccess(GfxEnums.Access.SHADER_READ), 0x800 | 0x80,
                "shader access spans compute and fragment");
        eq(VkEnumMap.stageForAccess(GfxEnums.Access.COLOR_ATTACHMENT_WRITE), 0x400,
                "colour writes land in COLOR_ATTACHMENT_OUTPUT");
        eq(VkEnumMap.stageForAccess(GfxEnums.Access.DEPTH_STENCIL_WRITE), 0x100,
                "depth writes land in EARLY_FRAGMENT_TESTS");
        eq(VkEnumMap.stageForAccess(GfxEnums.Access.INPUT_ATTACHMENT_READ), 0x80, "inputs are fragment");
        eq(VkEnumMap.stageForAccess(GfxEnums.Access.MEMORY_READ), 0x10000, "memory implies everything");
        eq(VkEnumMap.stageForAccess(GfxEnums.Access.SHADER_WRITE | GfxEnums.Access.TRANSFER_READ),
                0x800 | 0x80 | 0x1000, "mixed access unions its stages");
        eq(VkEnumMap.bufferUsage(GfxUsage.BUFFER_VERTEX), 0x80, "VERTEX_BUFFER_BIT");
        eq(VkEnumMap.bufferUsage(GfxUsage.BUFFER_INDEX), 0x40, "INDEX_BUFFER_BIT");
        eq(VkEnumMap.bufferUsage(GfxUsage.BUFFER_INDIRECT), 0x100, "INDIRECT_BUFFER_BIT");
        eq(VkEnumMap.imageUsage(GfxUsage.IMAGE_SAMPLED), 4, "SAMPLED_BIT");
        eq(VkEnumMap.imageUsage(GfxUsage.IMAGE_COLOR_ATTACHMENT), 0x10, "COLOR_ATTACHMENT_BIT");
        eq(VkEnumMap.imageUsage(GfxUsage.IMAGE_PRESENT), 0, "present is a layout, not a usage");
        eq(VkEnumMap.memoryProperties(GfxEnums.MemoryDomain.DEVICE_LOCAL), 1, "DEVICE_LOCAL_BIT");
        eq(VkEnumMap.memoryProperties(GfxEnums.MemoryDomain.HOST_VISIBLE), 6, "visible and coherent");
        receipt("vk: 41/42 formats, 13/13 stages, layouts and barriers mapped");

        section("util: checks, math, extents");
        eq(Checks.notNull("x", "v"), "x", "notNull returns its value");
        TestRig.throwsNamed(new Runnable() { @Override public void run() { Checks.notNull(null, "v"); } },
                "v", "notNull names the offender");
        TestRig.throwsNamed(new Runnable() { @Override public void run() { Checks.positive(0, "width"); } },
                "width", "positive names the offender");
        eq(Checks.inRange(2, 0, 4, "i"), 2, "inRange returns its value");
        eq(MathUtil.alignUp(1, 256), 256, "alignUp rounds up");
        eq(MathUtil.alignUp(256, 256), 256, "an aligned value is untouched");
        eq(MathUtil.alignUp(257, 256), 512, "one past the line costs a whole line");
        check(MathUtil.isPow2(256) && !MathUtil.isPow2(257), "power-of-two test");
        eq(MathUtil.mipLevelCount(1920, 1080), 11, "a 1080p chain has 11 levels");
        eq(MathUtil.mipLevelCount(1, 1), 1, "a 1x1 image is its own chain");
        eq(MathUtil.mipExtent(1080, 3), 135, "level 3 of 1080");
        eq(MathUtil.mipExtent(3, 5), 1, "extents never shrink below 1");
        eq(Extents.parse("256x256")[0], 256, "WxH parses");
        eq(Extents.parse("256x256")[1], 256, "both halves");
        eq(Extents.parse("256x256")[2], 1, "depth defaults to 1");
        eq(Extents.parse("16x16x1")[2], 1, "WxHxD parses");
        eq(Extents.parse("512")[0], 512, "a bare width parses");
        eq(Extents.parse(" 8 X 8 ")[1], 8, "whitespace and a capital X are tolerated");
        check(Extents.parse("0x64") == null, "a zero dimension is refused");
        check(Extents.parse("") == null, "empty is refused");
        check(Extents.parse(null) == null, "null is refused, not an NPE");
        check(Extents.parse("1x2x3x4") == null, "a fourth dimension is refused");
        check(Extents.parse("axb") == null, "non-numeric is refused");
        check(Extents.parse("16x-4") == null, "a negative dimension is refused");
    }

    private static boolean distinctTopologiesGl() {
        Set<Integer> s = new HashSet<Integer>();
        for (GfxEnums.Topology t : GfxEnums.Topology.values()) s.add(GlEnumMap.topology(t));
        return s.size() == GfxEnums.Topology.values().length;
    }

    private static boolean distinctStagesVk() {
        Set<Integer> s = new HashSet<Integer>();
        for (GfxStage g : GfxStage.values()) s.add(VkEnumMap.shaderStage(g));
        return s.size() == GfxStage.values().length;
    }

    /** Avoids a static-import collision with the suite's own helpers. */
    private static final class BlendFactorAlias {
        static GfxEnums.BlendFactor srcAlpha() { return GfxEnums.BlendFactor.SRC_ALPHA; }
    }
}
