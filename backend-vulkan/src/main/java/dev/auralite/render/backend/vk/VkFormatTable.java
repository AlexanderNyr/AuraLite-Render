package dev.auralite.render.backend.vk;

import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.util.Checks;

import static org.lwjgl.vulkan.VK13.VK_FORMAT_A2B10G10R10_UNORM_PACK32;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_B10G11R11_UFLOAT_PACK32;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_B8G8R8A8_UNORM;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_BC1_RGB_SRGB_BLOCK;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_BC3_SRGB_BLOCK;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_BC5_UNORM_BLOCK;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_BC7_SRGB_BLOCK;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_BC7_UNORM_BLOCK;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_D16_UNORM;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_D24_UNORM_S8_UINT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_D32_SFLOAT_S8_UINT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R16_SFLOAT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R16_UNORM;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R16G16_SFLOAT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R16G16B16A16_UINT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R16G16B16A16_UNORM;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R32_SFLOAT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R32_SINT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R32_UINT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R32G32_SFLOAT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R32G32_UINT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R32G32B32_SFLOAT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R32G32B32A32_SFLOAT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R32G32B32A32_UINT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R8_SINT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R8_SRGB;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R8_UINT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R8_UNORM;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R8G8_SRGB;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R8G8_UINT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R8G8_UNORM;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R8G8B8A8_SINT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R8G8B8A8_SNORM;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R8G8B8A8_SRGB;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R8G8B8A8_UINT;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_R8_SNORM;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_UNDEFINED;
import static org.lwjgl.vulkan.VK13.VK_FORMAT_X8_D24_UNORM_PACK32;

/**
 * Every {@link GfxFormat} as its {@code VkFormat} number.
 *
 * <p>Pure data, no Vulkan device required -- gated by the host test suite like
 * the GL table. Unlike GL, Vulkan defines every format including one- and
 * two-channel sRGB, so the only unmapped input is {@code UNDEFINED} itself.
 */
public final class VkFormatTable {
    private VkFormatTable() { }

    /** Maps a format to its {@code VkFormat} value. */
    public static int of(GfxFormat f) {
        Checks.notNull(f, "format");
        switch (f) {
            case R8_UNORM: return VK_FORMAT_R8_UNORM;
            case R8_SNORM: return VK_FORMAT_R8_SNORM;
            case R8_UINT: return VK_FORMAT_R8_UINT;
            case R8_SINT: return VK_FORMAT_R8_SINT;
            case R8_SRGB: return VK_FORMAT_R8_SRGB;
            case RG8_UNORM: return VK_FORMAT_R8G8_UNORM;
            case RG8_UINT: return VK_FORMAT_R8G8_UINT;
            case RG8_SRGB: return VK_FORMAT_R8G8_SRGB;
            case RGBA8_UNORM: return VK_FORMAT_R8G8B8A8_UNORM;
            case RGBA8_SNORM: return VK_FORMAT_R8G8B8A8_SNORM;
            case RGBA8_UINT: return VK_FORMAT_R8G8B8A8_UINT;
            case RGBA8_SINT: return VK_FORMAT_R8G8B8A8_SINT;
            case RGBA8_SRGB: return VK_FORMAT_R8G8B8A8_SRGB;
            case BGRA8_UNORM: return VK_FORMAT_B8G8R8A8_UNORM;
            case R16_UNORM: return VK_FORMAT_R16_UNORM;
            case R16F: return VK_FORMAT_R16_SFLOAT;
            case RG16F: return VK_FORMAT_R16G16_SFLOAT;
            case RGBA16F: return VK_FORMAT_R16G16B16A16_SFLOAT;
            case RGBA16_UNORM: return VK_FORMAT_R16G16B16A16_UNORM;
            case RGBA16_UINT: return VK_FORMAT_R16G16B16A16_UINT;
            case R32_UINT: return VK_FORMAT_R32_UINT;
            case R32_SINT: return VK_FORMAT_R32_SINT;
            case R32F: return VK_FORMAT_R32_SFLOAT;
            case RG32F: return VK_FORMAT_R32G32_SFLOAT;
            case RGB32F: return VK_FORMAT_R32G32B32_SFLOAT;
            case RGBA32_UINT: return VK_FORMAT_R32G32B32A32_UINT;
            case RGBA32F: return VK_FORMAT_R32G32B32A32_SFLOAT;
            case RGB10A2_UNORM: return VK_FORMAT_A2B10G10R10_UNORM_PACK32;
            case RG11B10F: return VK_FORMAT_B10G11R11_UFLOAT_PACK32;
            case R32G32_UINT: return VK_FORMAT_R32G32_UINT;
            case R32G32B32A32_UINT: return VK_FORMAT_R32G32B32A32_UINT;
            case D16_UNORM: return VK_FORMAT_D16_UNORM;
            case D24_UNORM: return VK_FORMAT_X8_D24_UNORM_PACK32;
            case D32_SFLOAT: return VK_FORMAT_D32_SFLOAT;
            case D24_UNORM_S8_UINT: return VK_FORMAT_D24_UNORM_S8_UINT;
            case D32_SFLOAT_S8_UINT: return VK_FORMAT_D32_SFLOAT_S8_UINT;
            case BC1_RGB_SRGB: return VK_FORMAT_BC1_RGB_SRGB_BLOCK;
            case BC3_SRGB: return VK_FORMAT_BC3_SRGB_BLOCK;
            case BC5_UNORM: return VK_FORMAT_BC5_UNORM_BLOCK;
            case BC7_SRGB: return VK_FORMAT_BC7_SRGB_BLOCK;
            case BC7_UNORM: return VK_FORMAT_BC7_UNORM_BLOCK;
            case UNDEFINED: return VK_FORMAT_UNDEFINED;
            default: break;
        }
        throw new AssertionError("unmapped GfxFormat: " + f);
    }
}
