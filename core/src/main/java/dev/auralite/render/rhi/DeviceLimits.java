package dev.auralite.render.rhi;

/**
 * Measured device limits, filled in by each backend at device creation.
 *
 * <p>Nothing here is assumed: {@code NullDevice} reports a deliberately
 * conservative profile so a pack that over-requests is caught by the host test
 * gates rather than on a player's machine.
 */
public final class DeviceLimits {
    public int maxTexture2DSize = 16384;
    public int maxTexture3DSize = 2048;
    public int maxTextureLayers = 2048;
    public int maxColorAttachments = 8;
    public int maxViewportWidth = 16384;
    public int maxFramebufferWidth = 16384;
    public int maxFramebufferHeight = 16384;
    public int maxUniformBufferRange = 65536;
    public int maxStorageBufferRange = 1 << 27;
    public int maxPushConstantSize = 128;
    public int maxBoundDescriptorSets = 4;
    public int maxPerStageSamplers = 32;
    public int maxPerStageStorageImages = 8;
    public int maxPerStageStorageBuffers = 12;
    public int maxVertexAttributes = 16;
    public int maxViewports = 16;
    public int maxComputeWorkGroupCountX = 65535;
    public int maxComputeWorkGroupCountY = 65535;
    public int maxComputeWorkGroupCountZ = 65535;
    public int maxComputeWorkGroupSizeX = 1024;
    public int maxComputeWorkGroupSizeY = 1024;
    public int maxComputeWorkGroupSizeZ = 64;
    public int maxComputeWorkGroupInvocations = 1024;
    public int maxComputeSharedMemorySize = 49152;
    public int maxAnisotropy = 16;
    public int subgroupSize = 32;
    public int minUniformBufferOffsetAlignment = 256;
    public int minStorageBufferOffsetAlignment = 256;
    public int nonCoherentAtomSize = 256;
    public boolean timestampsSupported = true;
    public boolean wideLines = true;
    public int maxRayQueryStackSize = 0;
    /** Number of swapchain images the presentation engine can supply. */
    public int swapchainImageCount = 3;

    /** Rounds {@code size} up to {@code alignment}, matching both APIs' offset rules. */
    public static long align(long size, long alignment) {
        if (alignment <= 1) return size;
        long m = size % alignment;
        return m == 0 ? size : size + (alignment - m);
    }

    /** True when a requested dispatch fits the device's work-group limits. */
    public boolean supportsDispatch(int gx, int gy, int gz, int localX, int localY, int localZ) {
        if (gx > maxComputeWorkGroupCountX || gy > maxComputeWorkGroupCountY || gz > maxComputeWorkGroupCountZ) {
            return false;
        }
        if (localX > maxComputeWorkGroupSizeX || localY > maxComputeWorkGroupSizeY || localZ > maxComputeWorkGroupSizeZ) {
            return false;
        }
        return (long) localX * localY * localZ <= maxComputeWorkGroupInvocations;
    }

    @Override public String toString() {
        return "DeviceLimits{tex2d=" + maxTexture2DSize + ", tex3d=" + maxTexture3DSize
                + ", colorAttach=" + maxColorAttachments + ", sets=" + maxBoundDescriptorSets
                + ", subgroup=" + subgroupSize + ", push=" + maxPushConstantSize + "}";
    }
}
