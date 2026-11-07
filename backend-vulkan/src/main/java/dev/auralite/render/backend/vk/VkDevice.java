package dev.auralite.render.backend.vk;

import dev.auralite.render.rhi.DeviceLimits;
import dev.auralite.render.rhi.GfxBuffer;
import dev.auralite.render.rhi.GfxCapability;
import dev.auralite.render.rhi.GfxCommandBuffer;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxDevice;
import dev.auralite.render.rhi.GfxEnums.BackendType;
import dev.auralite.render.rhi.GfxEnums.ImageType;
import dev.auralite.render.rhi.GfxEnums.MemoryDomain;
import dev.auralite.render.rhi.GfxEnums.QueueType;
import dev.auralite.render.rhi.GfxException;
import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxImage;
import dev.auralite.render.rhi.GfxPipeline;
import dev.auralite.render.rhi.GfxSampler;
import dev.auralite.render.rhi.GfxShaderModule;
import dev.auralite.render.rhi.GfxStage;
import dev.auralite.render.rhi.GfxUsage;
import dev.auralite.render.rhi.ShaderSource;
import dev.auralite.render.util.Checks;
import dev.auralite.render.util.MathUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import dev.auralite.render.util.ResourceCacheOwnership;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT;
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceLayers;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceHostQueryResetFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceMeshShaderFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceRayTracingPipelineFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Properties;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineTessellationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDebugUtils.vkCreateDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT;
import static org.lwjgl.vulkan.EXTMeshShader.VK_EXT_MESH_SHADER_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTMeshShader.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.EXTHostQueryReset.VK_EXT_HOST_QUERY_RESET_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTHostQueryReset.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_QUERY_RESET_FEATURES_EXT;
import static org.lwjgl.vulkan.KHRDeferredHostOperations.VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayQuery.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRRayTracingPipeline.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR;
import static org.lwjgl.vulkan.VK13.*;

/**
 * The Vulkan 1.3 backend. Owns its instance, physical-device pick, logical
 * device, queues, descriptor pools, render-pass cache and layout tracker.
 *
 * <p>Deliberate v1 limits, all documented where they bite: one allocation per
 * resource (no suballocator), grow-only descriptor pools, frame-serialized
 * submits, no swapchain (presentation belongs to the Fabric glue, which owns
 * the window). None of them changes rendering results; all of them are fenced
 * behind these walls so a later version can fix them without touching callers.
 *
 * <p>Set {@code AURALITE_VK_VALIDATION=1} to enable the Khronos validation
 * layer, which prints its findings to stderr and makes every call return a
 * failing result code ({@code make gpu-test} runs with it ON by default).
 * Every message it prints is a bug in this file until proven otherwise. There
 * is deliberately no debug messenger - see {@link #validationEnabled()}.
 */
public final class VkDevice implements GfxDevice {

    /**
     * VK_EXT_vertex_attribute_robustness, by name: the constants that would
     * spell it live in LWJGL versions newer than the game's pinned 3.3.3, and a
     * jar that compiles against them dies on a real client.
     */
    private static final String EXT_VERTEX_ATTRIBUTE_ROBUSTNESS =
            "VK_EXT_vertex_attribute_robustness";

    private final VkInstance instance;
    private final VkPhysicalDevice physicalDevice;
    private final org.lwjgl.vulkan.VkDevice device;
    /** Whether the Khronos validation layer was enabled for this instance. */
    private final boolean validationLayerEnabled;

    private final int graphicsFamily;
    private final int computeFamily;
    private final int transferFamily;
    private final VkQueue graphicsQueue;
    private final VkQueue computeQueue;
    private final VkQueue transferQueue;

    private final Set<GfxCapability> capabilities;
    private final DeviceLimits limits = new DeviceLimits();
    private final String gpuName;
    private final String driverVersion;
    private final boolean meshSupported;
    private final boolean rayQuerySupported;
    private final boolean rayPipelineSupported;

    private final Set<VkBuffer> liveBuffers = new HashSet<VkBuffer>();
    private final Set<VkImage> liveImages = new HashSet<VkImage>();
    private final Set<VkSampler> liveSamplers = new HashSet<VkSampler>();
    private final Set<VkPipeline> livePipelines = new HashSet<VkPipeline>();
    private final Set<VkShaderModule> liveModules = new HashSet<VkShaderModule>();
    private final List<VkCommandBuffer> commandBuffers = new ArrayList<VkCommandBuffer>();
    private final List<Long> descriptorPools = new ArrayList<Long>();
    private final List<Long> setLayouts = new ArrayList<Long>();
    private final List<Long> pipelineLayouts = new ArrayList<Long>();
    private final List<Long> renderPasses = new ArrayList<Long>();
    private final List<Long> framebuffers = new ArrayList<Long>();
    private final List<Long> inFlightFences = new ArrayList<Long>();

    private final Map<Long, Long> renderPassCache = new HashMap<Long, Long>();
    private final Map<Long, Long> framebufferCache = new HashMap<Long, Long>();
    private final Map<Long, Long> viewCache = new HashMap<Long, Long>();
    /** Image handle -> current VkImageLayout, for barrier construction. */
    private final Map<Long, Integer> imageLayouts = new HashMap<Long, Integer>();

    /**
     * The r42 teardown, restored. Every cached attachment view and every cached
     * framebuffer names the image handles it was built from, so closing an image
     * drops the entries that reference it instead of leaving a live view of a
     * freed VkImage in a device-wide cache. Keys are the same hashes
     * {@link #viewFor} / {@link #framebufferFor} already compute; ownership is
     * tracked by handle because a handle is unique among LIVE images, which is
     * exactly the window in which a stale hit would be a use-after-free.
     */
    private final ResourceCacheOwnership<Long, Long> viewOwners =
            new ResourceCacheOwnership<Long, Long>();
    private final ResourceCacheOwnership<Long, Long> framebufferOwners =
            new ResourceCacheOwnership<Long, Long>();
    private long imagesReleased;
    private long viewsTorn;
    private long fbosTorn;
    private long viewsUnattributed;

    private final long transientPool;
    private final long graphicsPool;
    private final Map<Integer, Long> familyPools = new HashMap<Integer, Long>();
    private final long timestampPool;
    private int timestampIndex;
    private boolean hostQueryResetSupported;

    /**
     * VK_KHR_swapchain is never enabled on this device (presentation belongs
     * to the Fabric glue, which owns the surface). Until that lands, the
     * PRESENT resource state must translate to a legal stand-in layout -
     * PRESENT_SRC_KHR without the extension is a validation violation.
     */
    boolean swapchainEnabled() { return false; }

    private long frameId;
    private long totalCreated;
    private String lastError = "";
    private final long createdMillis = System.currentTimeMillis();
    private boolean closed;

    public VkDevice() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean validation = "1".equals(System.getenv("AURALITE_VK_VALIDATION"));
            this.validationLayerEnabled = validation;
            this.instance = createInstance(stack, validation);
            this.physicalDevice = pickPhysicalDevice(stack);
            int[] families = findQueueFamilies(stack);
            this.graphicsFamily = families[0];
            this.computeFamily = families[1];
            this.transferFamily = families[2];
            Set<String> availableExt = deviceExtensions(stack);
            boolean wantMesh = availableExt.contains(VK_EXT_MESH_SHADER_EXTENSION_NAME);
            boolean wantRay = availableExt.contains(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME)
                    && availableExt.contains(VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME)
                    && availableExt.contains(VK_KHR_RAY_QUERY_EXTENSION_NAME)
                    && availableExt.contains(VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);
            this.meshSupported = wantMesh;
            this.rayQuerySupported = wantRay;
            this.rayPipelineSupported = wantRay;
            this.device = createLogicalDevice(stack, validation, wantMesh, wantRay, availableExt);
            this.graphicsQueue = getQueue(graphicsFamily);
            this.computeQueue = getQueue(computeFamily);
            this.transferQueue = getQueue(transferFamily);
            this.capabilities = probeCapabilities(stack, wantMesh, wantRay);
            probeLimits(stack);
            this.graphicsPool = createPool(graphicsFamily, 0);
            this.transientPool = createPool(graphicsFamily, VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);
            this.timestampPool = createTimestampPool();
            this.gpuName = gpuName(stack);
            this.driverVersion = driverVersion(stack);
        }
    }

    // ------------------------------------------------------------- bring-up ---

    private static VkInstance createInstance(MemoryStack stack, boolean validation) {
        VkApplicationInfo app = VkApplicationInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8("AuraLite-Render"))
                .applicationVersion(VK_MAKE_VERSION(0, 1, 0))
                .pEngineName(stack.UTF8("AuraLite-Render"))
                .engineVersion(VK_MAKE_VERSION(0, 1, 0))
                .apiVersion(VK_API_VERSION_1_3);
        PointerBuffer extNames = extNames(stack, validation);
        PointerBuffer layerNames = validation && hasValidationLayer(stack)
                ? stack.mallocPointer(1).put(0, stack.UTF8("VK_LAYER_KHRONOS_validation")) : null;
        VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(app);
        if (extNames != null) ci.ppEnabledExtensionNames(extNames);
        if (layerNames != null) ci.ppEnabledLayerNames(layerNames);
        PointerBuffer p = stack.mallocPointer(1);
        check(vkCreateInstance(ci, null, p), "vkCreateInstance");
        return new VkInstance(p.get(0), ci);
    }

    private static PointerBuffer extNames(MemoryStack stack, boolean validation) {
        if (!validation) return null;
        return stack.mallocPointer(1).put(0, stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
    }

    private static boolean hasValidationLayer(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateInstanceLayerProperties(count, null);
        VkLayerProperties.Buffer layers = VkLayerProperties.calloc(count.get(0), stack);
        vkEnumerateInstanceLayerProperties(count, layers);
        for (int i = 0; i < layers.capacity(); i++) {
            if ("VK_LAYER_KHRONOS_validation".equals(layers.get(i).layerNameString())) return true;
        }
        return false;
    }

    /**
     * Whether the Khronos validation layer was enabled for this instance. The
     * layer prints its own findings to stderr, which is the receipt
     * {@code make gpu-test} quotes; a game session that sets the flag gets the
     * layer without any work from us.
     */
    public boolean validationEnabled() {
        return validationLayerEnabled;
    }

    /*
     * No debug messenger, and that is a decision rather than an omission.
     * The LWJGL the game ships (3.3.3) cannot be handed a NULL callback
     * through the generated struct - pfnUserCallback(null) dereferences the
     * callback and throws before any Vulkan call, which is what the r62 tree
     * shipped: AURALITE_VK_VALIDATION=1 died inside createMessenger, so the
     * validation path was the one path that could not run, and the only gate
     * that exercises it (make gpu-test, which defaults the flag ON) had been
     * skipped for two revisions. Building a real closure is not available on
     * that LWJGL either - VkDebugUtilsMessengerCallbackEXTI is not a
     * functional interface there, so the anonymous class the fix would need
     * does not compile.
     *
     * What the layer still does without a messenger: validate every call and
     * print its findings to stderr, and return a failing result code to us -
     * which check() turns into a named GfxException. Losing the in-process
     * printf costs a prettier log line; keeping a crash-on-enable path costs
     * the gate.
     */

        private VkPhysicalDevice pickPhysicalDevice(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices(count)");
        if (count.get(0) == 0) throw new GfxException("vulkan backend: no physical devices at all");
        PointerBuffer devs = stack.mallocPointer(count.get(0));
        check(vkEnumeratePhysicalDevices(instance, count, devs), "vkEnumeratePhysicalDevices");
        VkPhysicalDevice first = null;
        for (int i = 0; i < devs.capacity(); i++) {
            VkPhysicalDevice d = new VkPhysicalDevice(devs.get(i), instance);
            if (first == null) first = d;
            VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2);
            vkGetPhysicalDeviceProperties2(d, props);
            if (props.properties().deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) return d;
        }
        return first;
    }

    private int[] findQueueFamilies(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, null);
        VkQueueFamilyProperties.Buffer fams = VkQueueFamilyProperties.calloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, fams);
        int graphics = -1, compute = -1, transfer = -1;
        for (int i = 0; i < fams.capacity(); i++) {
            int flags = fams.get(i).queueFlags();
            if (graphics < 0 && (flags & VK_QUEUE_GRAPHICS_BIT) != 0) graphics = i;
        }
        for (int i = 0; i < fams.capacity(); i++) {
            int flags = fams.get(i).queueFlags();
            if ((flags & VK_QUEUE_COMPUTE_BIT) == 0) continue;
            if ((flags & VK_QUEUE_GRAPHICS_BIT) == 0) { compute = i; break; }
            if (compute < 0) compute = i;
        }
        for (int i = 0; i < fams.capacity(); i++) {
            int flags = fams.get(i).queueFlags();
            if ((flags & VK_QUEUE_TRANSFER_BIT) == 0) continue;
            if ((flags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) == 0) { transfer = i; break; }
            if (transfer < 0) transfer = i;
        }
        if (graphics < 0) throw new GfxException("vulkan backend: no graphics queue family");
        if (compute < 0) compute = graphics;
        if (transfer < 0) transfer = graphics;
        return new int[] { graphics, compute, transfer };
    }

    private Set<String> deviceExtensions(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, count, null);
        VkExtensionProperties.Buffer exts = VkExtensionProperties.calloc(count.get(0), stack);
        vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, count, exts);
        Set<String> out = new HashSet<String>();
        for (int i = 0; i < exts.capacity(); i++) out.add(exts.get(i).extensionNameString());
        return out;
    }

    private org.lwjgl.vulkan.VkDevice createLogicalDevice(MemoryStack stack, boolean validation,
                                         boolean mesh, boolean ray, Set<String> availableExt) {
        Set<Integer> families = new HashSet<Integer>();
        families.add(graphicsFamily); families.add(computeFamily); families.add(transferFamily);
        VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(families.size(), stack);
        int qi = 0;
        for (int family : families) {
            FloatBuffer prio = stack.mallocFloat(1).put(0, 1.0f);
            queues.get(qi).sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(family).pQueuePriorities(prio);
            qi++;
        }
        boolean hostReset = availableExt.contains(VK_EXT_HOST_QUERY_RESET_EXTENSION_NAME);
        this.hostQueryResetSupported = hostReset;
        // Fullscreen-triangle packs declare vertex inputs they do not fill yet
        // (the world mesh is future work); GL returns the standard unbound
        // constant there, so Vulkan asks for the matching robustness feature
        // instead of leaving the read undefined (VUID Input-07904 named it).
        boolean vertexRobust = availableExt.contains(EXT_VERTEX_ATTRIBUTE_ROBUSTNESS);
        List<String> want = new ArrayList<String>();
        if (hostReset) want.add(VK_EXT_HOST_QUERY_RESET_EXTENSION_NAME);
        if (vertexRobust) want.add(EXT_VERTEX_ATTRIBUTE_ROBUSTNESS);
        if (mesh) want.add(VK_EXT_MESH_SHADER_EXTENSION_NAME);
        if (ray) {
            want.add(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME);
            want.add(VK_KHR_RAY_TRACING_PIPELINE_EXTENSION_NAME);
            want.add(VK_KHR_RAY_QUERY_EXTENSION_NAME);
            want.add(VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);
        }
        PointerBuffer extNames = stack.mallocPointer(want.size());
        for (int i = 0; i < want.size(); i++) extNames.put(i, stack.UTF8(want.get(i)));

        VkPhysicalDeviceFeatures supported = VkPhysicalDeviceFeatures.calloc(stack);
        vkGetPhysicalDeviceFeatures(physicalDevice, supported);
        VkPhysicalDeviceFeatures enabled = VkPhysicalDeviceFeatures.calloc(stack);
        if (supported.geometryShader()) enabled.geometryShader(true);
        if (supported.tessellationShader()) enabled.tessellationShader(true);
        if (supported.multiDrawIndirect()) enabled.multiDrawIndirect(true);
        if (supported.drawIndirectFirstInstance()) enabled.drawIndirectFirstInstance(true);
        if (supported.depthClamp()) enabled.depthClamp(true);
        if (supported.samplerAnisotropy()) enabled.samplerAnisotropy(true);
        if (supported.multiViewport()) enabled.multiViewport(true);
        if (supported.pipelineStatisticsQuery()) enabled.pipelineStatisticsQuery(true);
        if (supported.sparseBinding()) enabled.sparseBinding(true);
        if (supported.shaderInt64()) enabled.shaderInt64(true);
        if (supported.shaderFloat64()) enabled.shaderFloat64(true);
        if (supported.shaderSampledImageArrayDynamicIndexing()) {
            enabled.shaderSampledImageArrayDynamicIndexing(true);
        }
        if (supported.dualSrcBlend()) enabled.dualSrcBlend(true);

        long head = 0L;
        VkPhysicalDeviceMeshShaderFeaturesEXT meshFeatures = null;
        VkPhysicalDeviceRayQueryFeaturesKHR rayQueryFeatures = null;
        VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayPipeFeatures = null;
        VkPhysicalDeviceAccelerationStructureFeaturesKHR asFeatures = null;
        if (mesh) {
            meshFeatures = VkPhysicalDeviceMeshShaderFeaturesEXT.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_MESH_SHADER_FEATURES_EXT)
                    .taskShader(true).meshShader(true);
            meshFeatures.pNext(head); head = meshFeatures.address();
        }
        if (ray) {
            rayQueryFeatures = VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR)
                    .rayQuery(true);
            rayQueryFeatures.pNext(head); head = rayQueryFeatures.address();
            rayPipeFeatures = VkPhysicalDeviceRayTracingPipelineFeaturesKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR)
                    .rayTracingPipeline(true);
            rayPipeFeatures.pNext(head); head = rayPipeFeatures.address();
            asFeatures = VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR)
                    .accelerationStructure(true);
            asFeatures.pNext(head); head = asFeatures.address();
        }
        if (hostReset) {
            VkPhysicalDeviceHostQueryResetFeaturesEXT hostResetFeatures =
                    VkPhysicalDeviceHostQueryResetFeaturesEXT.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_QUERY_RESET_FEATURES_EXT)
                            .hostQueryReset(true);
            hostResetFeatures.pNext(head); head = hostResetFeatures.address();
        }
        // VK_EXT_vertex_attribute_robustness has no bindings in the LWJGL the
        // game ships (3.3.3): the extension name and its feature struct arrived
        // later than that. The extension is still REQUESTED by name when the
        // device advertises it, which is what the enable does; the pNext feature
        // struct cannot be built through these bindings, so it is not built -
        // stated here rather than left as a compile-time surprise for whoever
        // wires the next LWJGL bump.
        VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                .features(enabled);
        features2.pNext(head);

        VkDeviceCreateInfo ci = VkDeviceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                .pQueueCreateInfos(queues);
        ci.ppEnabledExtensionNames(extNames);
        // Everything rides the Features2 chain: pEnabledFeatures must stay NULL
        // whenever that chain is present, so the core features live in the
        // chain's head struct and the mesh/ray features behind it.
        ci.pNext(features2.address());
        PointerBuffer p = stack.mallocPointer(1);
        int rc = vkCreateDevice(physicalDevice, ci, null, p);
        if (rc != VK_SUCCESS) {
            throw new GfxException("vulkan backend: vkCreateDevice failed: " + rc);
        }
        return new org.lwjgl.vulkan.VkDevice(p.get(0), physicalDevice, ci);
    }

    private VkQueue getQueue(int family) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer p = stack.mallocPointer(1);
            vkGetDeviceQueue(device, family, 0, p);
            return new VkQueue(p.get(0), device);
        }
    }

    private Set<GfxCapability> probeCapabilities(MemoryStack stack, boolean mesh, boolean ray) {
        EnumSet<GfxCapability> c = EnumSet.of(
                GfxCapability.COMPUTE_SHADERS,
                GfxCapability.TEXTURE_3D,
                GfxCapability.INTEGER_TEXTURES,
                GfxCapability.STORAGE_BUFFERS,
                GfxCapability.FRAGMENT_STORAGE_IMAGES,
                GfxCapability.INDIRECT_DRAW,
                GfxCapability.MULTI_DRAW_INDIRECT,
                GfxCapability.INDIRECT_DISPATCH,
                GfxCapability.GEOMETRY_SHADERS,
                GfxCapability.TESSELLATION,
                GfxCapability.TIMESTAMPS,
                GfxCapability.SUBGROUP_OPS,
                GfxCapability.BINDLESS_TEXTURES,
                GfxCapability.DERIVATIVE_CONTROL);
        if (computeFamily != graphicsFamily) c.add(GfxCapability.ASYNC_COMPUTE);
        VkPhysicalDeviceFeatures supported = VkPhysicalDeviceFeatures.calloc(stack);
        vkGetPhysicalDeviceFeatures(physicalDevice, supported);
        if (supported.multiViewport()) c.add(GfxCapability.VIEWPORT_ARRAY);
        if (supported.pipelineStatisticsQuery()) c.add(GfxCapability.PIPELINE_STATISTICS);
        if (supported.sparseBinding()) c.add(GfxCapability.SPARSE_BINDING);
        if (supported.dualSrcBlend()) c.add(GfxCapability.DUAL_SOURCE_BLEND);
        if (mesh) c.add(GfxCapability.MESH_SHADERS);
        if (ray) {
            c.add(GfxCapability.RAY_QUERY);
            c.add(GfxCapability.RAY_TRACING_PIPELINE);
        }
        return java.util.Collections.unmodifiableSet(c);
    }

    private void probeLimits(MemoryStack stack) {
        VkPhysicalDeviceVulkan11Properties v11 = VkPhysicalDeviceVulkan11Properties.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_PROPERTIES);
        VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2);
        props.pNext(v11.address());
        vkGetPhysicalDeviceProperties2(physicalDevice, props);
        org.lwjgl.vulkan.VkPhysicalDeviceLimits l = props.properties().limits();
        limits.maxTexture2DSize = l.maxImageDimension2D();
        limits.maxTexture3DSize = l.maxImageDimension3D();
        limits.maxTextureLayers = l.maxImageArrayLayers();
        limits.maxColorAttachments = l.maxColorAttachments();
        limits.maxViewportWidth = (int) l.maxViewportDimensions(0);
        limits.maxFramebufferWidth = l.maxFramebufferWidth();
        limits.maxFramebufferHeight = l.maxFramebufferHeight();
        limits.maxUniformBufferRange = l.maxUniformBufferRange();
        limits.maxStorageBufferRange = l.maxStorageBufferRange();
        limits.maxPushConstantSize = l.maxPushConstantsSize();
        limits.maxBoundDescriptorSets = l.maxBoundDescriptorSets();
        limits.maxPerStageSamplers = l.maxPerStageDescriptorSamplers();
        limits.maxPerStageStorageImages = l.maxPerStageDescriptorStorageImages();
        limits.maxPerStageStorageBuffers = l.maxPerStageDescriptorStorageBuffers();
        limits.maxVertexAttributes = l.maxVertexInputAttributes();
        limits.maxViewports = l.maxViewports();
        limits.maxComputeWorkGroupCountX = l.maxComputeWorkGroupCount(0);
        limits.maxComputeWorkGroupCountY = l.maxComputeWorkGroupCount(1);
        limits.maxComputeWorkGroupCountZ = l.maxComputeWorkGroupCount(2);
        limits.maxComputeWorkGroupSizeX = l.maxComputeWorkGroupSize(0);
        limits.maxComputeWorkGroupSizeY = l.maxComputeWorkGroupSize(1);
        limits.maxComputeWorkGroupSizeZ = l.maxComputeWorkGroupSize(2);
        limits.maxComputeWorkGroupInvocations = l.maxComputeWorkGroupInvocations();
        limits.maxComputeSharedMemorySize = l.maxComputeSharedMemorySize();
        limits.maxAnisotropy = (int) l.maxSamplerAnisotropy();
        limits.subgroupSize = v11.subgroupSize();
        limits.minUniformBufferOffsetAlignment = (int) l.minUniformBufferOffsetAlignment();
        limits.minStorageBufferOffsetAlignment = (int) l.minStorageBufferOffsetAlignment();
        limits.nonCoherentAtomSize = (int) l.nonCoherentAtomSize();
    }

    private String gpuName(MemoryStack stack) {
        VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2);
        vkGetPhysicalDeviceProperties2(physicalDevice, props);
        return props.properties().deviceNameString();
    }

    private String driverVersion(MemoryStack stack) {
        VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2);
        vkGetPhysicalDeviceProperties2(physicalDevice, props);
        int v = props.properties().driverVersion();
        return VK_VERSION_MAJOR(v) + "." + VK_VERSION_MINOR(v) + "." + VK_VERSION_PATCH(v);
    }

    private static void check(int rc, String what) {
        if (rc != VK_SUCCESS) throw new GfxException("vulkan backend: " + what + " failed: " + rc);
    }

    @Override public BackendType backendType() { return BackendType.VULKAN_13; }
    @Override public String backendName() { return "AuraLite-Render VK13"; }
    @Override public String deviceName() { return gpuName; }
    @Override public String driverVersion() { return driverVersion; }
    @Override public DeviceLimits limits() { return limits; }
    @Override public Set<GfxCapability> capabilities() { return capabilities; }
    @Override public boolean hasCapability(GfxCapability c) { return capabilities.contains(c); }

    boolean meshSupported() { return meshSupported; }
    boolean raySupported() { return rayPipelineSupported; }

    // ------------------------------------------------------------ creation ---

    @Override public GfxBuffer createBuffer(GfxDesc.Buffer desc) {
        Checks.notNull(desc, "desc");
        if (desc.sizeBytes <= 0) {
            throw new GfxException("vulkan backend: buffer '" + desc.debugName
                    + "' has size " + desc.sizeBytes);
        }
        VkBuffer b = new VkBuffer(this, desc);
        liveBuffers.add(b);
        totalCreated++;
        return b;
    }

    @Override public GfxImage createImage(GfxDesc.Image desc) {
        Checks.notNull(desc, "desc");
        int format = VkFormatTable.of(desc.format);
        if (format == VK_FORMAT_UNDEFINED) {
            throw new GfxException("vulkan backend: image '" + desc.debugName
                    + "' has format UNDEFINED");
        }
        if (desc.width <= 0 || desc.height <= 0 || desc.depth <= 0) {
            throw new GfxException("vulkan backend: image '" + desc.debugName + "' has extent "
                    + desc.width + "x" + desc.height + "x" + desc.depth);
        }
        if (desc.samples > 1 && desc.mipLevels > 1) {
            throw new GfxException("vulkan backend: image '" + desc.debugName
                    + "' is both multisampled and mipmapped");
        }
        if (desc.type == ImageType.CUBE && (desc.width != desc.height || desc.arrayLayers % 6 != 0)) {
            throw new GfxException("vulkan backend: image '" + desc.debugName
                    + "': a cube needs a square face and 6 layers per cube");
        }
        if (desc.samples != 1 && desc.samples != 2 && desc.samples != 4 && desc.samples != 8
                && desc.samples != 16) {
            throw new GfxException("vulkan backend: image '" + desc.debugName + "' asks for "
                    + desc.samples + "x MSAA, which Vulkan cannot express");
        }
        if (desc.format.isCompressed() && (desc.mipLevels > 1 || desc.samples > 1)) {
            throw new GfxException("vulkan backend: compressed image '" + desc.debugName
                    + "' must be single-level and single-sampled in v1");
        }
        VkImage img = new VkImage(this, desc, format);
        liveImages.add(img);
        totalCreated++;
        return img;
    }

    @Override public GfxSampler createSampler(GfxDesc.Sampler desc) {
        Checks.notNull(desc, "desc");
        VkSampler s = new VkSampler(this, desc);
        liveSamplers.add(s);
        totalCreated++;
        return s;
    }

    @Override public GfxShaderModule createShaderModule(ShaderSource source) {
        Checks.notNull(source, "source");
        if (source.kind() != ShaderSource.Kind.SPIRV) {
            throw new GfxException("vulkan backend: '" + source.origin()
                    + "' is GLSL, and Vulkan eats SPIR-V only -- compile it offline with"
                    + " glslangValidator or shaderc, or run it on the GL lane");
        }
        VkShaderModule m = new VkShaderModule(this, source);
        liveModules.add(m);
        totalCreated++;
        return m;
    }

    @Override public GfxPipeline createGraphicsPipeline(GfxDesc.GraphicsPipeline desc) {
        Checks.notNull(desc, "desc");
        for (GfxShaderModule s : desc.stages) {
            VkShaderModule m = ownModule(s);
            if (m.stage.isRayTracing() && !rayPipelineSupported) {
                throw new GfxException("vulkan backend: pipeline '" + desc.debugName
                        + "' needs ray tracing, which this device does not offer");
            }
            if ((m.stage == GfxStage.TASK || m.stage == GfxStage.MESH) && !meshSupported) {
                throw new GfxException("vulkan backend: pipeline '" + desc.debugName
                        + "' needs mesh shaders, which this device does not offer");
            }
        }
        VkPipeline p = new VkPipeline(this, desc);
        livePipelines.add(p);
        totalCreated++;
        return p;
    }

    @Override public GfxPipeline createComputePipeline(GfxDesc.ComputePipeline desc) {
        Checks.notNull(desc, "desc");
        VkShaderModule m = ownModule(desc.stage);
        if (m.stage != GfxStage.COMPUTE) {
            throw new GfxException("vulkan backend: compute pipeline '" + desc.debugName
                    + "' is bound to a " + m.stage + " module");
        }
        long invocations = (long) desc.localSizeX * desc.localSizeY * desc.localSizeZ;
        if (invocations > limits.maxComputeWorkGroupInvocations) {
            throw new GfxException("vulkan backend: compute pipeline '" + desc.debugName + "' wants "
                    + invocations + " invocations, this device allows "
                    + limits.maxComputeWorkGroupInvocations);
        }
        VkPipeline p = new VkPipeline(this, desc);
        livePipelines.add(p);
        totalCreated++;
        return p;
    }

    @Override public GfxCommandBuffer createCommandBuffer(QueueType queue) {
        Checks.notNull(queue, "queue");
        VkCommandBuffer cmd = new VkCommandBuffer(this, queue);
        commandBuffers.add(cmd);
        return cmd;
    }

    VkShaderModule ownModule(GfxShaderModule s) {
        if (!(s instanceof VkShaderModule) || ((VkShaderModule) s).device != this) {
            throw new GfxException("vulkan backend: a shader module from another device was submitted here");
        }
        return (VkShaderModule) s;
    }

    VkBuffer ownBuffer(GfxBuffer b) {
        if (!(b instanceof VkBuffer) || ((VkBuffer) b).device != this) {
            throw new GfxException("vulkan backend: a buffer from another device was submitted here");
        }
        return (VkBuffer) b;
    }

    VkImage ownImage(GfxImage i) {
        if (!(i instanceof VkImage) || ((VkImage) i).device != this) {
            throw new GfxException("vulkan backend: an image from another device was submitted here");
        }
        return (VkImage) i;
    }

    // -------------------------------------------------------------- memory ---

    long allocateMemory(long size, int typeFilter, int properties, String what) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties props =
                    org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, props);
            for (int i = 0; i < props.memoryTypeCount(); i++) {
                boolean suitable = (typeFilter & (1 << i)) != 0;
                boolean capable = (props.memoryTypes(i).propertyFlags() & properties) == properties;
                if (suitable && capable) {
                    VkMemoryAllocateInfo ai = VkMemoryAllocateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                            .allocationSize(size)
                            .memoryTypeIndex(i);
                    LongBuffer p = stack.mallocLong(1);
                    check(vkAllocateMemory(device, ai, null, p), "vkAllocateMemory(" + what + ")");
                    return p.get(0);
                }
            }
        }
        throw new GfxException("vulkan backend: no memory type for " + what
                + " (HOST_VISIBLE_DEVICE_LOCAL rarely exists on discrete GPUs;"
                + " stage through HOST_VISIBLE instead)");
    }

    int sampleBits(int samples) {
        switch (samples) {
            case 1: return VK_SAMPLE_COUNT_1_BIT;
            case 2: return VK_SAMPLE_COUNT_2_BIT;
            case 4: return VK_SAMPLE_COUNT_4_BIT;
            case 8: return VK_SAMPLE_COUNT_8_BIT;
            case 16: return VK_SAMPLE_COUNT_16_BIT;
            default: throw new GfxException("vulkan backend: " + samples + "x MSAA is not expressible");
        }
    }

    // ------------------------------------------------------------- uploads ---

    @Override public byte[] readImage(GfxImage image) {
        VkImage img = ownImage(image);
        GfxFormat f = image.format();
        if (f != GfxFormat.RGBA8_UNORM && f != GfxFormat.RGBA8_SRGB) {
            throw new GfxException("vulkan backend: readImage supports the RGBA8 family only, got "
                    + f + " for image '" + img.desc.debugName + "'");
        }
        if ((img.desc.usage & GfxUsage.IMAGE_TRANSFER_SRC) == 0) {
            throw new GfxException("vulkan backend: readImage needs IMAGE_TRANSFER_SRC on image '"
                    + img.desc.debugName + "' (" + GfxUsage.describe(img.desc.usage) + ")");
        }
        int w = img.desc.width, h = img.desc.height;
        int bytes = w * h * 4;
        VkBuffer staging = new VkBuffer(this, GfxDesc.Buffer.of(bytes, GfxUsage.BUFFER_TRANSFER_DST)
                .domain(MemoryDomain.HOST_VISIBLE).named("readback"));
        try {
            runOneShot(new OneShot() {
                @Override public void record(org.lwjgl.vulkan.VkCommandBuffer cmd) {
                    int before = currentLayout(img.handle);
                    transition(cmd, img, before, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            0, VK_ACCESS_TRANSFER_READ_BIT,
                            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                    try (MemoryStack inner = MemoryStack.stackPush()) {
                        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, inner);
                        region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                        region.get(0).imageSubresource().aspectMask(img.aspect()).mipLevel(0)
                                .baseArrayLayer(0).layerCount(img.desc.arrayLayers);
                        region.get(0).imageOffset().set(0, 0, 0);
                        region.get(0).imageExtent().set(w, h, 1);
                        vkCmdCopyImageToBuffer(cmd, img.handle,
                                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, staging.handle, region);
                    }
                    transition(cmd, img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, before,
                            VK_ACCESS_TRANSFER_READ_BIT,
                            VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);
                    setLayout(img.handle, before);
                }
            });
            // 1280x720x4 is far past MemoryStack's capacity - this buffer
            // lives on the LWJGL heap, not the native stack.
            ByteBuffer out = org.lwjgl.BufferUtils.createByteBuffer(bytes);
            staging.readHost(0, out);
            byte[] arr = new byte[bytes];
            out.flip();
            out.get(arr);
            return arr;
        } finally {
            staging.destroy();
        }
    }

    @Override public void uploadBuffer(GfxBuffer dst, ByteBuffer src, long dstOffsetBytes) {
        VkBuffer d = ownBuffer(dst);
        Checks.notNull(src, "src");
        // writeHost advances the source position, so the byte count is captured
        // first; reading remaining() afterwards copies nothing, silently.
        int bytes = src.remaining();
        d.checkRange(dstOffsetBytes, bytes, "upload");
        if (d.hostVisible) {
            d.writeHost(dstOffsetBytes, src);
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBuffer staging = new VkBuffer(this, GfxDesc.Buffer.of(bytes,
                    GfxUsage.BUFFER_TRANSFER_SRC).domain(MemoryDomain.HOST_VISIBLE).named("staging"));
            try {
                staging.writeHost(0, src);
                runOneShot(new OneShot() {
                    @Override public void record(org.lwjgl.vulkan.VkCommandBuffer cmd) {
                        try (MemoryStack inner = MemoryStack.stackPush()) {
                            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, inner)
                                    .srcOffset(0).dstOffset(dstOffsetBytes).size(bytes);
                            vkCmdCopyBuffer(cmd, staging.handle, d.handle, region);
                        }
                    }
                });
            } finally {
                staging.destroy();
            }
        }
    }

    @Override public void uploadBuffer(GfxBuffer dst, float[] src, long dstOffsetBytes) {
        Checks.notNull(src, "src");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bb = stack.malloc(src.length * 4);
            bb.asFloatBuffer().put(src);
            uploadBuffer(dst, bb, dstOffsetBytes);
        }
    }

    @Override public void uploadBuffer(GfxBuffer dst, int[] src, long dstOffsetBytes) {
        Checks.notNull(src, "src");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bb = stack.malloc(src.length * 4);
            bb.asIntBuffer().put(src);
            uploadBuffer(dst, bb, dstOffsetBytes);
        }
    }

    @Override public void uploadImage(GfxImage dst, int mipLevel, ByteBuffer src) {
        VkImage img = ownImage(dst);
        img.checkLevel(mipLevel);
        Checks.notNull(src, "src");
        int w = MathUtil.mipExtent(img.desc.width, mipLevel);
        int h = MathUtil.mipExtent(img.desc.height, mipLevel);
        int dep = img.desc.type == ImageType.TEXTURE_3D ? MathUtil.mipExtent(img.desc.depth, mipLevel) : 1;
        uploadImageRegion(dst, mipLevel, 0, 0, 0, w, h, dep, src);
    }

    @Override public void uploadImageRegion(GfxImage dst, int mipLevel, int x, int y, int z,
                                            int w, int h, int d, ByteBuffer src) {
        final VkImage img = ownImage(dst);
        img.checkLevel(mipLevel);
        Checks.notNull(src, "src");
        if (img.desc.format.isCompressed()) {
            throw new GfxException("vulkan backend: compressed upload into '" + img.desc.debugName
                    + "' needs block-size math this version does not have yet");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBuffer staging = new VkBuffer(this, GfxDesc.Buffer.of(src.remaining(),
                    GfxUsage.BUFFER_TRANSFER_SRC).domain(MemoryDomain.HOST_VISIBLE).named("staging"));
            try {
                staging.writeHost(0, src);
                final int fw = w, fh = h, fd = d, fx = x, fy = y, fz = z, fl = mipLevel;
                runOneShot(new OneShot() {
                    @Override public void record(org.lwjgl.vulkan.VkCommandBuffer cmd) {
                        try (MemoryStack inner = MemoryStack.stackPush()) {
                            int before = currentLayout(img.handle);
                            transition(cmd, img, before, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                    0, VK_ACCESS_TRANSFER_WRITE_BIT,
                                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, inner);
                            region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                            region.get(0).imageSubresource().aspectMask(img.aspect())
                                    .mipLevel(fl).baseArrayLayer(0).layerCount(img.desc.arrayLayers);
                            region.get(0).imageOffset().set(fx, fy, fz);
                            region.get(0).imageExtent().set(fw, fh, fd);
                            vkCmdCopyBufferToImage(cmd, staging.handle, img.handle,
                                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
                            int after = img.desc.format.isDepthStencil()
                                    ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                                    : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                            transition(cmd, img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, after,
                                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
                            setLayout(img.handle, after);
                        }
                    }
                });
            } finally {
                staging.destroy();
            }
        }
    }

    /** Records a throwaway command buffer, runs it, waits, frees it. Uploads live here. */
    interface OneShot {
        void record(org.lwjgl.vulkan.VkCommandBuffer cmd);
    }

    void runOneShot(OneShot body) {
        org.lwjgl.vulkan.VkCommandBuffer cmd;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(transientPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer p = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, ai, p), "vkAllocateCommandBuffers(one-shot)");
            cmd = new org.lwjgl.vulkan.VkCommandBuffer(p.get(0), device);
            org.lwjgl.vulkan.VkCommandBufferBeginInfo bi =
                    org.lwjgl.vulkan.VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(cmd, bi), "vkBeginCommandBuffer(one-shot)");
            body.record(cmd);
            check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer(one-shot)");
            VkSubmitInfo si = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(cmd.address()));
            VkFenceCreateInfo fi = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            LongBuffer pf = stack.mallocLong(1);
            check(vkCreateFence(device, fi, null, pf), "vkCreateFence(one-shot)");
            long fence = pf.get(0);
            check(vkQueueSubmit(graphicsQueue, si, fence), "vkQueueSubmit(one-shot)");
            LongBuffer wait = stack.mallocLong(1).put(0, fence);
            check(vkWaitForFences(device, wait, true, 5_000_000_000L), "vkWaitForFences(one-shot)");
            vkDestroyFence(device, fence, null);
            vkFreeCommandBuffers(device, transientPool, cmd);
        }
    }

    // --------------------------------------------------------------- frame ---

    @Override public long beginFrame() {
        // v1 serializes frames: every earlier submit is waited on here, so the
        // timestamp pool and the descriptor pools can be reused without tracking.
        // A later version fences per frame and keeps N pools in flight.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (long fence : inFlightFences) {
                LongBuffer wait = stack.mallocLong(1).put(0, fence);
                vkWaitForFences(device, wait, true, 5_000_000_000L);
                vkDestroyFence(device, fence, null);
            }
        }
        inFlightFences.clear();
        if (hostQueryResetSupported) {
            vkResetQueryPool(device, timestampPool, 0, 256);
            timestampIndex = 0;
        } else {
            // No VK_EXT_host_query_reset on this device: core forbids the host
            // reset without the feature (VUID-vkResetQueryPool-None-02665), so
            // the pool keeps last frame's values until a device that offers it.
            timestampIndex = 0;
        }
        return ++frameId;
    }

    @Override public void submit(QueueType queue, GfxCommandBuffer cmd) {
        if (!(cmd instanceof VkCommandBuffer) || ((VkCommandBuffer) cmd).device != this) {
            throw new GfxException("vulkan backend: a foreign command buffer was submitted here");
        }
        VkCommandBuffer c = (VkCommandBuffer) cmd;
        if (!c.ended()) throw new GfxException("vulkan backend: submitting a command buffer that never ended");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo si = VkSubmitInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                    .pCommandBuffers(stack.pointers(c.nativeHandle().address()));
            VkFenceCreateInfo fi = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            LongBuffer pf = stack.mallocLong(1);
            check(vkCreateFence(device, fi, null, pf), "vkCreateFence(submit)");
            long fence = pf.get(0);
            check(vkQueueSubmit(queueFor(queue), si, fence), "vkQueueSubmit");
            inFlightFences.add(fence);
        }
    }

    @Override public void submitAndWait(QueueType queue, GfxCommandBuffer cmd) {
        int before = inFlightFences.size();
        submit(queue, cmd);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = before; i < inFlightFences.size(); i++) {
                long fence = inFlightFences.get(i);
                LongBuffer wait = stack.mallocLong(1).put(0, fence);
                vkWaitForFences(device, wait, true, 5_000_000_000L);
                vkDestroyFence(device, fence, null);
            }
        }
        inFlightFences.subList(before, inFlightFences.size()).clear();
    }

    @Override public void endFrame() { }

    @Override public void waitIdle() { check(vkDeviceWaitIdle(device), "vkDeviceWaitIdle"); }

    VkQueue queueFor(QueueType queue) {
        if (queue == QueueType.COMPUTE) return computeQueue;
        if (queue == QueueType.TRANSFER) return transferQueue;
        return graphicsQueue;
    }

    int familyFor(QueueType queue) {
        if (queue == QueueType.COMPUTE) return computeFamily;
        if (queue == QueueType.TRANSFER) return transferFamily;
        return graphicsFamily;
    }

    // --------------------------------------------------------- diagnostics ---

    @Override public int liveBufferCount() { return liveBuffers.size(); }
    @Override public int liveImageCount() { return liveImages.size(); }
    @Override public int livePipelineCount() { return livePipelines.size(); }
    @Override public int liveShaderModuleCount() { return liveModules.size(); }
    @Override public String lastError() { return lastError; }
    @Override public long[] frameTimestamps() {
        int count = timestampIndex;
        if (count == 0) return new long[0];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer out = stack.mallocLong(count);
            check(vkGetQueryPoolResults(device, timestampPool, 0, count,
                    out, Long.BYTES,
                    VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT),
                    "vkGetQueryPoolResults(timestamps)");
            long[] ts = new long[count];
            for (int i = 0; i < count; i++) ts[i] = out.get(i);
            return ts;
        }
    }

    @Override public long cpuTimeMillis() { return System.currentTimeMillis() - createdMillis; }

    /**
     * The teardown receipt for the host's rebuild log: live image count, the
     * sizes of the two entry caches, and what has been torn down so far. A
     * rebuild that frees images must move {@code viewsTorn} and {@code fbosTorn}
     * - a rebuild that frees nine images and tears nothing is the r61/r62
     * regression, and this line is what shows it in a session log.
     */
    public String cacheReceipt() {
        return "imagesLive=" + liveImages.size()
                + " cachedViews=" + viewCache.size() + " cachedFbos=" + framebufferCache.size()
                + " | released=" + imagesReleased + " viewsTorn=" + viewsTorn
                + " fbosTorn=" + fbosTorn
                + (viewsUnattributed == 0 ? ""
                        : " viewsUnattributed=" + viewsUnattributed);
    }

    /**
     * Drops every cached view and framebuffer built from {@code handle} and
     * destroys the objects. Called from {@code VkImage.close()}: the caches are
     * device-wide and outlive a graph, so without this a released graph leaves
     * live views of freed images behind, and the driver's first draw through
     * one is a fault with no Vulkan error and no Java exception.
     */
    void purgeCachesForImage(long handle) {
        imagesReleased++;
        // Identity, not membership: an ownership entry is dropped only when it
        // still names the object the cache holds under that key. A cache key
        // that has since been overwritten by another object must not drag this
        // one down with it - destroying a live view is the very fault this
        // purge exists to prevent.
        // `viewCache` is keyed by the VIEW KEY (image handle + subresource range)
        // while `viewOwners.purge` answers with the HANDLES it held for the dying
        // image, so r63's `viewCache.remove(view)` evicted nothing: the views of a
        // freed image stayed cached under a key nobody would ever ask with again,
        // their destroy was skipped, and the log read `viewsTorn=0` while
        // cachedViews climbed 5 -> 21 rebuild after rebuild. A cache of views onto
        // freed memory is the state nvoglv64.dll faults in, which is what the
        // 0xC0000409 at the 5th reload was. Sweep by VALUE against the purge answer
        // - each view destroyed once, by the sweep that also drops its key - and
        // count the case where ownership named nothing, so a broken ATTACH side
        // cannot hide behind a zero that looks like "nothing to do".
        java.util.List<Long> dying = viewOwners.purge(Long.valueOf(handle));
        if (!dying.isEmpty()) {
            java.util.Iterator<java.util.Map.Entry<Long, Long>> views =
                    viewCache.entrySet().iterator();
            while (views.hasNext()) {
                java.util.Map.Entry<Long, Long> e = views.next();
                if (!dying.contains(e.getValue())) continue;
                views.remove();
                vkDestroyImageView(device, e.getValue(), null);
                viewsTorn++;
            }
        } else if (viewCache.size() > 0) {
            viewsUnattributed++;
        }
        for (Long fb : framebufferOwners.purge(Long.valueOf(handle))) {
            if (!framebuffers.remove(fb)) continue;
            vkDestroyFramebuffer(device, fb, null);
            fbosTorn++;
            for (java.util.Iterator<Map.Entry<Long, Long>> it = framebufferCache.entrySet().iterator();
                 it.hasNext();) {
                if (fb.equals(it.next().getValue())) it.remove();
            }
        }
        imageLayouts.remove(handle);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        vkDeviceWaitIdle(device);
        for (VkCommandBuffer cb : new ArrayList<VkCommandBuffer>(commandBuffers)) cb.dispose();
        commandBuffers.clear();
        for (long fence : inFlightFences) vkDestroyFence(device, fence, null);
        inFlightFences.clear();
        for (VkPipeline p : new ArrayList<VkPipeline>(livePipelines)) p.close();
        for (VkShaderModule m : new ArrayList<VkShaderModule>(liveModules)) m.close();
        for (VkSampler s : new ArrayList<VkSampler>(liveSamplers)) s.close();
        for (VkImage i : new ArrayList<VkImage>(liveImages)) i.close();
        for (VkBuffer b : new ArrayList<VkBuffer>(liveBuffers)) b.close();
        for (long fb : framebuffers) vkDestroyFramebuffer(device, fb, null);
        framebuffers.clear();
        framebufferCache.clear();
        for (long rp : renderPasses) vkDestroyRenderPass(device, rp, null);
        renderPasses.clear();
        renderPassCache.clear();
        for (long view : viewCache.values()) vkDestroyImageView(device, view, null);
        viewCache.clear();
        for (long layout : pipelineLayouts) vkDestroyPipelineLayout(device, layout, null);
        pipelineLayouts.clear();
        for (long layout : setLayouts) vkDestroyDescriptorSetLayout(device, layout, null);
        setLayouts.clear();
        for (long pool : descriptorPools) vkDestroyDescriptorPool(device, pool, null);
        descriptorPools.clear();
        vkDestroyQueryPool(device, timestampPool, null);
        vkDestroyCommandPool(device, transientPool, null);
        vkDestroyCommandPool(device, graphicsPool, null);
        for (long pool : familyPools.values()) {
            if (pool != graphicsPool) vkDestroyCommandPool(device, pool, null);
        }
        familyPools.clear();
        vkDestroyDevice(device, null);
        vkDestroyInstance(instance, null);
    }

    // ------------------------------------------------------- layout tracker ---

    int currentLayout(long image) {
        Integer l = imageLayouts.get(image);
        return l == null ? VK_IMAGE_LAYOUT_UNDEFINED : l;
    }

    void setLayout(long image, int layout) { imageLayouts.put(image, layout); }

    static void transition(org.lwjgl.vulkan.VkCommandBuffer cmd, VkImage img,
                           int oldLayout, int newLayout, int srcAccess, int dstAccess,
                           int srcStage, int dstStage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                    .oldLayout(oldLayout).newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(img.handle);
            barrier.subresourceRange().aspectMask(img.aspect())
                    .baseMipLevel(0).levelCount(img.desc.mipLevels)
                    .baseArrayLayer(0).layerCount(img.desc.arrayLayers);
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    // ---------------------------------------------------- render pass cache ---

    long renderPassFor(GfxDesc.RenderPass pass) {
        long key = passKey(pass);
        Long cached = renderPassCache.get(key);
        if (cached != null) return cached;
        long rp = buildRenderPass(pass);
        renderPassCache.put(key, rp);
        renderPasses.add(rp);
        return rp;
    }

    private long passKey(GfxDesc.RenderPass pass) {
        // The incoming layouts are part of the key: a LOAD pass reached from
        // SHADER_READ needs a different baked initialLayout than the same pass
        // reached from UNDEFINED on the first frame. Layouts are not part of
        // render-pass compatibility, so pipelines stay valid across the variants.
        long h = 1469598103934665603L;
        for (GfxDesc.Attachment a : pass.colors) {
            h = mix(h, VkFormatTable.of(a.image.format())); h = mix(h, a.image.samples());
            h = mix(h, a.loadOp.ordinal()); h = mix(h, a.storeOp.ordinal());
            h = mix(h, a.image.desc().usage & GfxUsage.IMAGE_PRESENT);
            h = mix(h, initialFor(a.image));
        }
        if (pass.depth != null) {
            h = mix(h, 0xD0000L + VkFormatTable.of(pass.depth.image.format()));
            h = mix(h, pass.depth.image.samples());
            h = mix(h, pass.depth.loadOp.ordinal()); h = mix(h, pass.depth.storeOp.ordinal());
            h = mix(h, initialFor(pass.depth.image));
        }
        return h;
    }

    /**
     * Notes that a pass ran: every attachment now sits in its final layout.
     * Called from beginRenderPass at EXECUTION time -- never from pipeline
     * creation, which also resolves render passes but must not claim their
     * images already transitioned.
     */
    void notePassExecuted(GfxDesc.RenderPass pass) {
        for (GfxDesc.Attachment a : pass.colors) {
            trackAfterPass(a.image, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        }
        if (pass.depth != null) {
            trackAfterPass(pass.depth.image, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        }
    }

    /** The layout an attachment starts the pass in: tracked, or UNDEFINED for stand-ins. */
    private int initialFor(GfxImage image) {
        if (image instanceof VkImage) return currentLayout(image.handle());
        return VK_IMAGE_LAYOUT_UNDEFINED;
    }

    private void trackAfterPass(GfxImage image, int layout) {
        if (image instanceof VkImage) setLayout(image.handle(), layout);
    }

    private static long mix(long h, long v) { return (h ^ v) * 1099511628211L; }

    private long buildRenderPass(GfxDesc.RenderPass pass) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int colors = pass.colors.size();
            int hasDepth = pass.depth == null ? 0 : 1;
            VkAttachmentDescription.Buffer descs = VkAttachmentDescription.calloc(colors + hasDepth, stack);
            VkAttachmentReference.Buffer colorRefs = VkAttachmentReference.calloc(colors, stack);
            for (int i = 0; i < colors; i++) {
                GfxDesc.Attachment a = pass.colors.get(i);
                // Passes bracket attachments in attachment-optimal layouts; every
                // other transition is an explicit graph barrier. That is the whole
                // deal: the graph owns the layout timeline, passes only borrow it.
                descs.get(i).flags(0)
                        .format(VkFormatTable.of(a.image.format()))
                        .samples(sampleBits(a.image.samples()))
                        .loadOp(VkEnumMap.loadOp(a.loadOp))
                        .storeOp(VkEnumMap.storeOp(a.storeOp))
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(initialFor(a.image))
                        .finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                colorRefs.get(i).attachment(i).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            }
            VkAttachmentReference.Buffer depthRef = null;
            if (pass.depth != null) {
                GfxDesc.Attachment a = pass.depth;
                boolean stencil = a.image.format().isStencil();
                descs.get(colors).flags(0)
                        .format(VkFormatTable.of(a.image.format()))
                        .samples(sampleBits(a.image.samples()))
                        .loadOp(VkEnumMap.loadOp(a.loadOp))
                        .storeOp(VkEnumMap.storeOp(a.storeOp))
                        .stencilLoadOp(stencil ? VkEnumMap.loadOp(a.loadOp)
                                : VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(stencil ? VkEnumMap.storeOp(a.storeOp)
                                : VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(initialFor(a.image))
                        .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
                depthRef = VkAttachmentReference.calloc(1, stack);
                depthRef.get(0).attachment(colors)
                        .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }
            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(colors);
            // A depth-only shadow pass has no colour refs at all; handing Vulkan
            // a zero-length array for them is legal but pointless, so skip it.
            if (colors > 0) subpass.get(0).pColorAttachments(colorRefs);
            if (depthRef != null) subpass.get(0).pDepthStencilAttachment(depthRef.get(0));
            VkSubpassDependency.Buffer dep = VkSubpassDependency.calloc(1, stack);
            dep.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                            | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                    .srcAccessMask(0)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                            | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT)
                    .dependencyFlags(0);
            VkRenderPassCreateInfo ci = VkRenderPassCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                    .pAttachments(descs)
                    .pSubpasses(subpass)
                    .pDependencies(dep);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateRenderPass(device, ci, null, p), "vkCreateRenderPass");
            return p.get(0);
        }
    }

    long framebufferFor(GfxDesc.RenderPass pass, long renderPass) {
        long key = framebufferKey(pass, renderPass);
        Long cached = framebufferCache.get(key);
        if (cached != null) return cached;
        java.util.List<Long> attachmentHandles =
                new java.util.ArrayList<Long>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int colors = pass.colors.size();
            int hasDepth = pass.depth == null ? 0 : 1;
            LongBuffer views = stack.mallocLong(colors + hasDepth);
            for (int i = 0; i < colors; i++) {
                GfxDesc.Attachment a = pass.colors.get(i);
                VkImage img = ownImage(a.image);
                attachmentHandles.add(img.handle);
                views.put(i, viewFor(img, a.mipLevel, a.baseLayer, a.layerCount));
            }
            if (pass.depth != null) {
                GfxDesc.Attachment a = pass.depth;
                VkImage img = ownImage(a.image);
                attachmentHandles.add(img.handle);
                views.put(colors, viewFor(img, a.mipLevel, a.baseLayer, a.layerCount));
            }
            VkFramebufferCreateInfo ci = VkFramebufferCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                    .renderPass(renderPass)
                    .pAttachments(views)
                    .width(pass.width).height(pass.height).layers(1);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateFramebuffer(device, ci, null, p), "vkCreateFramebuffer");
            framebuffers.add(p.get(0));
            framebufferCache.put(key, p.get(0));
            framebufferOwners.attach(Long.valueOf(key), p.get(0), attachmentHandles);
            return p.get(0);
        }
    }

    private long framebufferKey(GfxDesc.RenderPass pass, long renderPass) {
        long h = 1469598103934665603L ^ renderPass;
        h = mix(h, pass.width); h = mix(h, pass.height);
        for (GfxDesc.Attachment a : pass.colors) {
            VkImage img = ownImage(a.image);
            h = mix(h, img.handle); h = mix(h, a.mipLevel); h = mix(h, a.baseLayer);
            h = mix(h, a.layerCount);
        }
        if (pass.depth != null) {
            h = mix(h, ownImage(pass.depth.image).handle);
            h = mix(h, pass.depth.mipLevel); h = mix(h, pass.depth.baseLayer);
        }
        return h;
    }

    /** A view of one mip level (and layer range) of an image, cached. */
    long viewFor(VkImage img, int mipLevel, int baseLayer, int layerCount) {
        long key = img.handle * 1000003L + mipLevel * 1009L + baseLayer * 131L + layerCount;
        Long cached = viewCache.get(key);
        if (cached != null) return cached;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // A cube face or one array slice attaches as a plain 2D view: cube
            // and array views are never valid framebuffer attachments.
            int viewType;
            if (layerCount == 1) {
                viewType = img.desc.type == ImageType.TEXTURE_1D ? VK_IMAGE_VIEW_TYPE_1D
                        : VK_IMAGE_VIEW_TYPE_2D;
            } else {
                viewType = VkEnumMap.viewType(img.desc.type, true);
            }
            VkImageViewCreateInfo ci = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(img.handle)
                    .viewType(viewType)
                    .format(img.format);
            ci.subresourceRange().aspectMask(img.aspect())
                    .baseMipLevel(mipLevel).levelCount(1)
                    .baseArrayLayer(baseLayer).layerCount(layerCount);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateImageView(device, ci, null, p), "vkCreateImageView(attachment)");
            viewCache.put(key, p.get(0));
            viewOwners.attach(Long.valueOf(key), p.get(0),
                    java.util.Collections.singletonList(Long.valueOf(img.handle)));
            return p.get(0);
        }
    }

    private long createPool(int family, int flags) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo ci = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .queueFamilyIndex(family)
                    .flags(flags | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateCommandPool(device, ci, null, p), "vkCreateCommandPool");
            return p.get(0);
        }
    }

    private long createTimestampPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkQueryPoolCreateInfo ci = VkQueryPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO)
                    .queryType(VK_QUERY_TYPE_TIMESTAMP)
                    .queryCount(256);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateQueryPool(device, ci, null, p), "vkCreateQueryPool");
            return p.get(0);
        }
    }

    long timestampPool() { return timestampPool; }

    int nextTimestampIndex() {
        int i = timestampIndex;
        timestampIndex = (timestampIndex + 1) % 256;
        return i;
    }

    long graphicsPool() { return graphicsPool; }

    /** The native handle. Package-visible: the command buffer needs it, callers do not. */
    org.lwjgl.vulkan.VkDevice nativeDevice() { return device; }

    /**
     * Command pool for a queue family, created on demand. A command buffer may
     * only be submitted to its own family, so compute and transfer work gets
     * real pools instead of borrowing the graphics one.
     */
    long poolForFamily(int family) {
        Long pool = familyPools.get(family);
        if (pool == null) {
            pool = createPool(family, 0);
            familyPools.put(family, pool);
        }
        return pool;
    }

    // ---------------------------------------------------------- descriptors ---

    long allocateSet(long setLayout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(currentDescriptorPool())
                    .pSetLayouts(stack.mallocLong(1).put(0, setLayout));
            LongBuffer p = stack.mallocLong(1);
            int rc = vkAllocateDescriptorSets(device, ai, p);
            if (rc == VK_ERROR_OUT_OF_POOL_MEMORY || rc == VK_ERROR_FRAGMENTED_POOL) {
                growDescriptorPool();
                ai.descriptorPool(currentDescriptorPool());
                check(vkAllocateDescriptorSets(device, ai, p), "vkAllocateDescriptorSets(retry)");
            } else {
                check(rc, "vkAllocateDescriptorSets");
            }
            return p.get(0);
        }
    }

    private long currentDescriptorPool() {
        if (descriptorPools.isEmpty()) growDescriptorPool();
        return descriptorPools.get(descriptorPools.size() - 1);
    }

    private void growDescriptorPool() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(6, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(512);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(512);
            sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(512);
            sizes.get(3).type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).descriptorCount(256);
            sizes.get(4).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(256);
            sizes.get(5).type(VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT).descriptorCount(64);
            VkDescriptorPoolCreateInfo ci = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .maxSets(1024)
                    .pPoolSizes(sizes);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateDescriptorPool(device, ci, null, p), "vkCreateDescriptorPool");
            descriptorPools.add(p.get(0));
        }
    }

    void writeSet(long set, dev.auralite.render.rhi.GfxDescriptorSet writes) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer ws = VkWriteDescriptorSet.calloc(writes.size(), stack);
            for (int i = 0; i < writes.size(); i++) {
                dev.auralite.render.rhi.GfxDescriptorSet.Write w = writes.writes().get(i);
                VkWriteDescriptorSet dst = ws.get(i)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(set)
                        .dstBinding(w.binding)
                        .dstArrayElement(w.arrayIndex)
                        .descriptorCount(1)
                        .descriptorType(descriptorType(w.kind));
                switch (w.kind) {
                    case UNIFORM_BUFFER:
                    case STORAGE_BUFFER: {
                        VkBuffer b = ownBuffer((GfxBuffer) w.resource);
                        VkDescriptorBufferInfo.Buffer bi = VkDescriptorBufferInfo.calloc(1, stack)
                                .buffer(b.handle).offset(w.offset)
                                .range(w.range <= 0 ? VK_WHOLE_SIZE : w.range);
                        dst.pBufferInfo(bi);
                        break;
                    }
                    case COMBINED_IMAGE_SAMPLER: {
                        VkImage img = ownImage((GfxImage) w.resource);
                        if (!(w.combinedSampler instanceof VkSampler)) {
                            throw new GfxException("vulkan backend: descriptor binding " + w.binding
                                    + " is a combined sampler with no Vulkan sampler");
                        }
                        VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, stack)
                                .sampler(((VkSampler) w.combinedSampler).handle)
                                .imageView(img.fullView)
                                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                        dst.pImageInfo(ii);
                        break;
                    }
                    case SAMPLED_IMAGE:
                    case INPUT_ATTACHMENT: {
                        VkImage img = ownImage((GfxImage) w.resource);
                        // True subpass inputs would need subpass structure the graph
                        // does not emit yet; until then an input attachment is read
                        // as a regular sampled image, outside any pass.
                        VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, stack)
                                .sampler(0L)
                                .imageView(img.fullView)
                                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                        dst.pImageInfo(ii);
                        break;
                    }
                    case SAMPLER: {
                        if (!(w.resource instanceof VkSampler)) {
                            throw new GfxException("vulkan backend: descriptor binding " + w.binding
                                    + " holds a foreign sampler");
                        }
                        VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, stack)
                                .sampler(((VkSampler) w.resource).handle)
                                .imageView(0L)
                                .imageLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                        dst.pImageInfo(ii);
                        break;
                    }
                    case STORAGE_IMAGE: {
                        VkImage img = ownImage((GfxImage) w.resource);
                        VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, stack)
                                .sampler(0L)
                                .imageView(img.fullView)
                                .imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                        dst.pImageInfo(ii);
                        break;
                    }
                    default:
                        throw new GfxException("vulkan backend: descriptor kind " + w.kind
                                + " cannot be written here");
                }
            }
            vkUpdateDescriptorSets(device, ws, null);
        }
    }

    private int descriptorType(GfxDesc.PipelineLayout.Kind kind) {
        switch (kind) {
            case UNIFORM_BUFFER: return VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case STORAGE_BUFFER: return VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case COMBINED_IMAGE_SAMPLER: return VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case SAMPLED_IMAGE: return VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
            case SAMPLER: return VK_DESCRIPTOR_TYPE_SAMPLER;
            case STORAGE_IMAGE: return VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            case INPUT_ATTACHMENT: return VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT;
            case ACCELERATION_STRUCTURE:
                if (!rayPipelineSupported) {
                    throw new GfxException("vulkan backend: acceleration structures need ray tracing,"
                            + " which this device does not offer");
                }
                return VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
            default: throw new GfxException("vulkan backend: descriptor kind " + kind);
        }
    }

    // --------------------------------------------------------------- buffer ---

    static final class VkBuffer implements GfxBuffer {
        final VkDevice device;
        final GfxDesc.Buffer desc;
        final long handle;
        final long memory;
        final boolean hostVisible;
        private boolean closed;

        VkBuffer(VkDevice device, GfxDesc.Buffer desc) {
            this.device = device;
            this.desc = desc;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCreateInfo ci = VkBufferCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                        .size(desc.sizeBytes)
                        .usage(VkEnumMap.bufferUsage(desc.usage))
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                LongBuffer p = stack.mallocLong(1);
                check(vkCreateBuffer(device.device, ci, null, p), "vkCreateBuffer");
                handle = p.get(0);
                VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
                vkGetBufferMemoryRequirements(device.device, handle, req);
                int props = VkEnumMap.memoryProperties(desc.domain);
                memory = device.allocateMemory(req.size(), req.memoryTypeBits(), props,
                        "buffer '" + desc.debugName + "'");
                check(vkBindBufferMemory(device.device, handle, memory, 0), "vkBindBufferMemory");
                hostVisible = desc.domain != MemoryDomain.DEVICE_LOCAL;
            }
        }

        @Override public long handle() { return handle; }
        @Override public String debugName() { return desc.debugName; }
        @Override public long sizeBytes() { return desc.sizeBytes; }
        @Override public int usage() { return desc.usage; }
        @Override public MemoryDomain domain() { return desc.domain; }
        @Override public boolean hostVisible() { return hostVisible; }

        void checkRange(long offset, long length, String what) {
            if (offset < 0 || length < 0 || offset + length > desc.sizeBytes) {
                throw new GfxException("vulkan backend: " + what + " on buffer '" + desc.debugName
                        + "' runs [" + offset + ".." + (offset + length) + ") past its "
                        + desc.sizeBytes + " bytes");
            }
        }

        void requireUsage(int flag, String flagName, String what) {
            if ((desc.usage & flag) == 0) {
                throw new GfxException("vulkan backend: " + what + " needs " + flagName
                        + " on buffer '" + desc.debugName + "' (" + GfxUsage.describe(desc.usage) + ")");
            }
        }

        void writeHost(long offset, ByteBuffer src) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer p = stack.mallocPointer(1);
                check(vkMapMemory(device.device, memory, offset, src.remaining(), 0, p),
                        "vkMapMemory(upload)");
                ByteBuffer mapped = MemoryUtil.memByteBuffer(p.get(0), src.remaining());
                mapped.put(src);
                vkUnmapMemory(device.device, memory);
            }
        }

        void readHost(long offset, ByteBuffer dst) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer p = stack.mallocPointer(1);
                check(vkMapMemory(device.device, memory, offset, dst.remaining(), 0, p),
                        "vkMapMemory(readback)");
                ByteBuffer mapped = MemoryUtil.memByteBuffer(p.get(0), dst.remaining());
                dst.put(mapped);
                vkUnmapMemory(device.device, memory);
            }
        }

        void destroy() {
            vkDestroyBuffer(device.device, handle, null);
            vkFreeMemory(device.device, memory, null);
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            device.liveBuffers.remove(this);
            destroy();
        }
    }

    // ---------------------------------------------------------------- image ---

    static final class VkImage implements GfxImage {
        final VkDevice device;
        final GfxDesc.Image desc;
        final int format;
        final long handle;
        final long memory;
        final long fullView;
        private boolean closed;

        VkImage(VkDevice device, GfxDesc.Image desc, int format) {
            this.device = device;
            this.desc = desc;
            this.format = format;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int flags = desc.type == ImageType.CUBE ? VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT : 0;
                VkImageCreateInfo ci = VkImageCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                        .flags(flags)
                        .imageType(VkEnumMap.imageType(desc.type))
                        .format(format);
                ci.extent().set(desc.width, desc.height,
                        desc.type == ImageType.TEXTURE_3D ? desc.depth : 1);
                ci.mipLevels(desc.format.isCompressed() ? 1 : desc.mipLevels)
                        .arrayLayers(desc.type == ImageType.TEXTURE_3D ? 1 : desc.arrayLayers)
                        .samples(device.sampleBits(desc.samples))
                        .tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(VkEnumMap.imageUsage(desc.usage))
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                LongBuffer p = stack.mallocLong(1);
                check(vkCreateImage(device.device, ci, null, p), "vkCreateImage");
                handle = p.get(0);
                VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
                vkGetImageMemoryRequirements(device.device, handle, req);
                int props = VkEnumMap.memoryProperties(desc.domain);
                memory = device.allocateMemory(req.size(), req.memoryTypeBits(), props,
                        "image '" + desc.debugName + "'");
                check(vkBindImageMemory(device.device, handle, memory, 0), "vkBindImageMemory");
                boolean array = desc.type == ImageType.CUBE ? desc.arrayLayers > 6
                        : desc.arrayLayers > 1;
                VkImageViewCreateInfo vi = VkImageViewCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                        .image(handle)
                        .viewType(VkEnumMap.viewType(desc.type, array))
                        .format(format);
                vi.subresourceRange().aspectMask(aspect(desc.format))
                        .baseMipLevel(0).levelCount(ci.mipLevels())
                        .baseArrayLayer(0).layerCount(ci.arrayLayers());
                LongBuffer pv = stack.mallocLong(1);
                check(vkCreateImageView(device.device, vi, null, pv), "vkCreateImageView");
                fullView = pv.get(0);
            }
            device.setLayout(handle, VK_IMAGE_LAYOUT_UNDEFINED);
        }

        int aspect() { return aspect(desc.format); }

        static int aspect(GfxFormat f) {
            if (f.isDepthStencil()) {
                return f.isStencil() ? VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT
                        : VK_IMAGE_ASPECT_DEPTH_BIT;
            }
            return VK_IMAGE_ASPECT_COLOR_BIT;
        }

        @Override public long handle() { return handle; }
        @Override public String debugName() { return desc.debugName; }
        @Override public long sizeBytes() { return desc.approxBytes(); }
        @Override public GfxDesc.Image desc() { return desc; }
        @Override public GfxFormat format() { return desc.format; }
        @Override public int width() { return desc.width; }
        @Override public int height() { return desc.height; }
        @Override public int depth() { return desc.depth; }
        @Override public int mipLevels() { return desc.mipLevels; }
        @Override public int arrayLayers() { return desc.arrayLayers; }
        @Override public int samples() { return desc.samples; }
        @Override public ImageType type() { return desc.type; }

        void checkLevel(int level) {
            if (level < 0 || level >= desc.mipLevels) {
                throw new GfxException("vulkan backend: mip level " + level + " on image '"
                        + desc.debugName + "', which has " + desc.mipLevels);
            }
        }

        void requireUsage(int flag, String flagName, String what) {
            if ((desc.usage & flag) == 0) {
                throw new GfxException("vulkan backend: " + what + " needs " + flagName
                        + " on image '" + desc.debugName + "' (" + GfxUsage.describe(desc.usage) + ")");
            }
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            device.liveImages.remove(this);
            // Order matters: the cached attachment views and the framebuffers
            // that reference them go first, while this image is still a valid
            // object to talk about, so nothing between these lines can draw
            // through a view of a freed image.
            device.purgeCachesForImage(handle);
            vkDestroyImageView(device.device, fullView, null);
            vkDestroyImage(device.device, handle, null);
            vkFreeMemory(device.device, memory, null);
        }
    }

    // -------------------------------------------------------------- sampler ---

    static final class VkSampler implements GfxSampler {
        final VkDevice device;
        final GfxDesc.Sampler desc;
        final long handle;
        private boolean closed;

        VkSampler(VkDevice device, GfxDesc.Sampler desc) {
            this.device = device;
            this.desc = desc;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo ci = VkSamplerCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                        .magFilter(VkEnumMap.filter(desc.magFilter))
                        .minFilter(VkEnumMap.filter(desc.minFilter))
                        .mipmapMode(VkEnumMap.mipmapMode(desc.mipmapMode))
                        .addressModeU(VkEnumMap.addressMode(desc.addressU))
                        .addressModeV(VkEnumMap.addressMode(desc.addressV))
                        .addressModeW(VkEnumMap.addressMode(desc.addressW))
                        .mipLodBias(0.0f)
                        .anisotropyEnable(desc.maxAnisotropy > 1.0f)
                        .maxAnisotropy(Math.min(desc.maxAnisotropy, device.limits.maxAnisotropy))
                        .compareEnable(desc.compareEnable)
                        .compareOp(VkEnumMap.compareOp(desc.compareOp))
                        .minLod(desc.minLod)
                        .maxLod(desc.maxLod)
                        .borderColor(borderColor(desc.borderColor))
                        .unnormalizedCoordinates(false);
                LongBuffer p = stack.mallocLong(1);
                check(vkCreateSampler(device.device, ci, null, p), "vkCreateSampler");
                handle = p.get(0);
            }
        }

        private static int borderColor(float[] c) {
            // Vulkan offers three float border colours; anything else snaps to the
            // nearest rather than failing a pack over four floats.
            if (c[0] == 0 && c[1] == 0 && c[2] == 0 && c[3] == 0) {
                return VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
            }
            if (c[0] == 0 && c[1] == 0 && c[2] == 0 && c[3] == 1) {
                return VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK;
            }
            return VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE;
        }

        @Override public long handle() { return handle; }
        @Override public String debugName() { return desc.debugName; }
        @Override public long sizeBytes() { return 0; }
        @Override public GfxDesc.Sampler desc() { return desc; }

        @Override public void close() {
            if (closed) return;
            closed = true;
            device.liveSamplers.remove(this);
            vkDestroySampler(device.device, handle, null);
        }
    }

    // ---------------------------------------------------------------- module ---

    static final class VkShaderModule implements GfxShaderModule {
        final VkDevice device;
        final GfxStage stage;
        final String entry;
        final String origin;
        final long hash;
        final long handle;
        private boolean closed;

        VkShaderModule(VkDevice device, ShaderSource source) {
            this.device = device;
            this.stage = source.stage();
            this.entry = source.entryPoint().isEmpty() ? "main" : source.entryPoint();
            this.origin = source.origin();
            this.hash = source.hash();
            byte[] code = source.spirv();
            if (code.length == 0 || code.length % 4 != 0) {
                throw new GfxException("vulkan backend: SPIR-V in '" + origin + "' is "
                        + code.length + " bytes; words only");
            }
            ByteBuffer words = MemoryUtil.memAlloc(code.length);
            try {
                words.put(code).flip();
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    org.lwjgl.vulkan.VkShaderModuleCreateInfo ci =
                            org.lwjgl.vulkan.VkShaderModuleCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                            .pCode(words);
                    LongBuffer p = stack.mallocLong(1);
                    check(vkCreateShaderModule(device.device, ci, null, p),
                            "vkCreateShaderModule(" + origin + ")");
                    handle = p.get(0);
                }
            } finally {
                MemoryUtil.memFree(words);
            }
        }

        @Override public long handle() { return handle; }
        @Override public String debugName() { return origin; }
        @Override public long sizeBytes() { return 0; }
        @Override public GfxStage stage() { return stage; }
        @Override public String entryPoint() { return entry; }
        @Override public boolean isValid() { return true; }
        @Override public String infoLog() { return ""; }
        @Override public long sourceHash() { return hash; }

        @Override public void close() {
            if (closed) return;
            closed = true;
            device.liveModules.remove(this);
            vkDestroyShaderModule(device.device, handle, null);
        }
    }

    // -------------------------------------------------------------- pipeline ---

    static final class VkPipeline implements GfxPipeline {
        final VkDevice device;
        final boolean compute;
        final long handle;
        final long layout;
        final long[] setLayouts;
        final GfxDesc.PipelineLayout descLayout;
        final GfxDesc.VertexLayout vertexLayout;
        final GfxDesc.Raster raster;
        final String name;
        private boolean closed;

        VkPipeline(VkDevice device, GfxDesc.GraphicsPipeline desc) {
            this.device = device;
            this.compute = false;
            this.name = desc.debugName;
            this.descLayout = desc.layout;
            this.vertexLayout = desc.vertexLayout;
            this.raster = desc.raster;
            this.setLayouts = device.setLayoutsFor(desc.layout);
            this.layout = device.pipelineLayoutFor(desc.layout, setLayouts);
            GfxDesc.RenderPass pass = desc.renderPass != null ? desc.renderPass : defaultPass(device);
            long renderPass = device.renderPassFor(pass);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPipelineShaderStageCreateInfo.Buffer stages =
                        VkPipelineShaderStageCreateInfo.calloc(desc.stages.size(), stack);
                for (int i = 0; i < desc.stages.size(); i++) {
                    VkShaderModule m = device.ownModule(desc.stages.get(i));
                    stages.get(i).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                            .stage(VkEnumMap.shaderStage(m.stage))
                            .module(m.handle)
                            .pName(stack.UTF8(m.entry));
                }
                VkVertexInputBindingDescription.Buffer bindings =
                        VkVertexInputBindingDescription.calloc(desc.vertexLayout.bindings.size(), stack);
                for (int i = 0; i < desc.vertexLayout.bindings.size(); i++) {
                    GfxDesc.VertexLayout.Binding b = desc.vertexLayout.bindings.get(i);
                    bindings.get(i).binding(b.binding).stride(b.stride)
                            .inputRate(b.perInstance ? VK_VERTEX_INPUT_RATE_INSTANCE
                                    : VK_VERTEX_INPUT_RATE_VERTEX);
                }
                VkVertexInputAttributeDescription.Buffer attrs =
                        VkVertexInputAttributeDescription.calloc(
                                desc.vertexLayout.attributes().size(), stack);
                for (int i = 0; i < desc.vertexLayout.attributes().size(); i++) {
                    GfxDesc.VertexLayout.Attribute a = desc.vertexLayout.attributes().get(i);
                    attrs.get(i).location(a.location).binding(a.binding)
                            .format(VkFormatTable.of(a.format)).offset(a.offset);
                }
                VkPipelineVertexInputStateCreateInfo vertexInput =
                        VkPipelineVertexInputStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                        .pVertexBindingDescriptions(bindings)
                        .pVertexAttributeDescriptions(attrs);
                VkPipelineInputAssemblyStateCreateInfo inputAssembly =
                        VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                        .topology(VkEnumMap.topology(desc.raster.topology))
                        .primitiveRestartEnable(false);
                VkPipelineTessellationStateCreateInfo tessellation = null;
                if (desc.raster.topology == dev.auralite.render.rhi.GfxEnums.Topology.PATCH_LIST) {
                    tessellation = VkPipelineTessellationStateCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PIPELINE_TESSELLATION_STATE_CREATE_INFO)
                            .patchControlPoints(3);
                }
                VkPipelineViewportStateCreateInfo viewport =
                        VkPipelineViewportStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                        .viewportCount(1)
                        .scissorCount(1);
                if (desc.depthStencil.stencilTest) {
                    throw new GfxException("vulkan backend: pipeline '" + desc.debugName
                            + "' enables stencil, which the RHI cannot configure yet");
                }
                VkPipelineRasterizationStateCreateInfo rasterState =
                        VkPipelineRasterizationStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                        .depthClampEnable(desc.raster.depthClamp)
                        .rasterizerDiscardEnable(false)
                        .polygonMode(VK_POLYGON_MODE_FILL)
                        .cullMode(VkEnumMap.cullMode(desc.raster.cullMode))
                        .frontFace(VkEnumMap.frontFace(desc.raster.frontFace))
                        .depthBiasEnable(desc.raster.polygonOffset)
                        .depthBiasConstantFactor(desc.depthStencil.depthBiasConstant)
                        .depthBiasSlopeFactor(desc.depthStencil.depthBiasSlope)
                        .lineWidth(desc.raster.lineWidth);
                int samples = 1;
                for (GfxDesc.Attachment a : pass.colors) {
                    samples = Math.max(samples, a.image.samples());
                }
                VkPipelineMultisampleStateCreateInfo multisample =
                        VkPipelineMultisampleStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                        .rasterizationSamples(device.sampleBits(samples));
                VkPipelineDepthStencilStateCreateInfo depthStencil =
                        VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                        .depthTestEnable(desc.depthStencil.depthTest)
                        .depthWriteEnable(desc.depthStencil.depthWrite)
                        .depthCompareOp(VkEnumMap.compareOp(desc.depthStencil.depthOp))
                        .depthBoundsTestEnable(false)
                        .stencilTestEnable(false);
                VkPipelineColorBlendAttachmentState.Buffer blendAtts =
                        VkPipelineColorBlendAttachmentState.calloc(Math.max(1, pass.colors.size()), stack);
                for (int i = 0; i < blendAtts.capacity(); i++) {
                    GfxDesc.Blend b = i < pass.colors.size() ? pass.colors.get(i).blend : null;
                    VkPipelineColorBlendAttachmentState dst = blendAtts.get(i)
                            .blendEnable(b != null && b.enabled)
                            .colorWriteMask(b == null ? 0xF : writeMask(b.writeMask));
                    if (b != null && b.enabled) {
                        dst.srcColorBlendFactor(VkEnumMap.blendFactor(b.srcColor))
                           .dstColorBlendFactor(VkEnumMap.blendFactor(b.dstColor))
                           .colorBlendOp(VkEnumMap.blendOp(b.opColor))
                           .srcAlphaBlendFactor(VkEnumMap.blendFactor(b.srcAlpha))
                           .dstAlphaBlendFactor(VkEnumMap.blendFactor(b.dstAlpha))
                           .alphaBlendOp(VkEnumMap.blendOp(b.opAlpha));
                    }
                }
                VkPipelineColorBlendStateCreateInfo blend =
                        VkPipelineColorBlendStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                        .logicOpEnable(false)
                        .pAttachments(blendAtts);
                IntBuffer dynamics = stack.mallocInt(2)
                        .put(0, VK_DYNAMIC_STATE_VIEWPORT).put(1, VK_DYNAMIC_STATE_SCISSOR);
                VkPipelineDynamicStateCreateInfo dynamic =
                        VkPipelineDynamicStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                        .pDynamicStates(dynamics);
                VkGraphicsPipelineCreateInfo.Buffer ci = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                        .pStages(stages)
                        .pVertexInputState(vertexInput)
                        .pInputAssemblyState(inputAssembly)
                        .pViewportState(viewport)
                        .pRasterizationState(rasterState)
                        .pMultisampleState(multisample)
                        .pDepthStencilState(depthStencil)
                        .pColorBlendState(blend)
                        .pDynamicState(dynamic)
                        .layout(layout)
                        .renderPass(renderPass)
                        .subpass(0);
                if (tessellation != null) ci.get(0).pTessellationState(tessellation);
                LongBuffer p = stack.mallocLong(1);
                check(vkCreateGraphicsPipelines(device.device, 0L, ci, null, p),
                        "vkCreateGraphicsPipelines(" + desc.debugName + ")");
                handle = p.get(0);
            }
        }

        VkPipeline(VkDevice device, GfxDesc.ComputePipeline desc) {
            this.device = device;
            this.name = desc.debugName;
            this.compute = true;
            this.descLayout = desc.layout;
            this.vertexLayout = GfxDesc.VertexLayout.empty();
            this.raster = new GfxDesc.Raster();
            this.setLayouts = device.setLayoutsFor(desc.layout);
            this.layout = device.pipelineLayoutFor(desc.layout, setLayouts);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkShaderModule m = device.ownModule(desc.stage);
                VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                        .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                        .module(m.handle)
                        .pName(stack.UTF8(m.entry));
                VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack)
                        .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                        .stage(stage)
                        .layout(layout);
                LongBuffer p = stack.mallocLong(1);
                check(vkCreateComputePipelines(device.device, 0L, ci, null, p),
                        "vkCreateComputePipelines(" + desc.debugName + ")");
                handle = p.get(0);
            }
        }

        private static int writeMask(int rhiMask) {
            int out = 0;
            if ((rhiMask & 1) != 0) out |= VK_COLOR_COMPONENT_R_BIT;
            if ((rhiMask & 2) != 0) out |= VK_COLOR_COMPONENT_G_BIT;
            if ((rhiMask & 4) != 0) out |= VK_COLOR_COMPONENT_B_BIT;
            if ((rhiMask & 8) != 0) out |= VK_COLOR_COMPONENT_A_BIT;
            return out;
        }

        /** A pipeline without a pass assumes one opaque RGBA8 target. Never cached. */
        private static GfxDesc.RenderPass defaultPass(VkDevice device) {
            GfxDesc.RenderPass pass = GfxDesc.RenderPass.of(1, 1).named("<implicit>");
            GfxDesc.Image desc = GfxDesc.Image.tex2d(1, 1, GfxFormat.RGBA8_UNORM,
                    GfxUsage.IMAGE_COLOR_ATTACHMENT).named("<implicit>");
            // The image only lends its format and sample count to the render pass
            // key; it is never allocated, bound, or drawn.
            pass.color(GfxDesc.Attachment.of(new UnallocatedImage(device, desc)));
            return pass;
        }

        @Override public long handle() { return handle; }
        @Override public String debugName() { return name; }
        @Override public long sizeBytes() { return 0; }
        @Override public boolean isCompute() { return compute; }
        @Override public boolean isValid() { return true; }
        @Override public String infoLog() { return ""; }
        @Override public GfxDesc.PipelineLayout layout() { return descLayout; }

        @Override public void close() {
            if (closed) return;
            closed = true;
            device.livePipelines.remove(this);
            vkDestroyPipeline(device.device, handle, null);
        }
    }

    /**
     * A format-and-samples stand-in for render-pass compatibility when a pipeline
     * is created without a pass. Any real use throws: it owns no image.
     */
    static final class UnallocatedImage implements GfxImage {
        final VkDevice device;
        final GfxDesc.Image desc;
        UnallocatedImage(VkDevice device, GfxDesc.Image desc) { this.device = device; this.desc = desc; }
        @Override public long handle() { throw new GfxException("vulkan backend: unallocated image used"); }
        @Override public String debugName() { return desc.debugName; }
        @Override public long sizeBytes() { return 0; }
        @Override public GfxDesc.Image desc() { return desc; }
        @Override public GfxFormat format() { return desc.format; }
        @Override public int width() { return desc.width; }
        @Override public int height() { return desc.height; }
        @Override public int depth() { return desc.depth; }
        @Override public int mipLevels() { return desc.mipLevels; }
        @Override public int arrayLayers() { return desc.arrayLayers; }
        @Override public int samples() { return desc.samples; }
        @Override public ImageType type() { return desc.type; }
        @Override public void close() { }
    }

    long[] setLayoutsFor(GfxDesc.PipelineLayout layout) {
        if (layout == null || layout.sets.isEmpty()) return new long[0];
        long[] out = new long[layout.sets.size()];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int i = 0; i < layout.sets.size(); i++) {
                GfxDesc.PipelineLayout.SetLayout set = layout.sets.get(i);
                VkDescriptorSetLayoutBinding.Buffer bindings =
                        VkDescriptorSetLayoutBinding.calloc(set.bindings.size(), stack);
                for (int j = 0; j < set.bindings.size(); j++) {
                    GfxDesc.PipelineLayout.BindingDesc b = set.bindings.get(j);
                    bindings.get(j).binding(b.binding)
                            .descriptorType(descriptorType(b.kind))
                            .descriptorCount(b.count)
                            .stageFlags(VkEnumMap.shaderStage(b.stages))
                            .pImmutableSamplers(null);
                }
                VkDescriptorSetLayoutCreateInfo ci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                        .pBindings(bindings);
                LongBuffer p = stack.mallocLong(1);
                check(vkCreateDescriptorSetLayout(device, ci, null, p),
                        "vkCreateDescriptorSetLayout(set " + i + ")");
                out[i] = p.get(0);
                setLayouts.add(out[i]);
            }
        }
        return out;
    }

    long pipelineLayoutFor(GfxDesc.PipelineLayout layout, long[] setLayouts) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineLayoutCreateInfo ci = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            if (setLayouts.length > 0) {
                LongBuffer sets = stack.mallocLong(setLayouts.length);
                for (int i = 0; i < setLayouts.length; i++) sets.put(i, setLayouts[i]);
                ci.pSetLayouts(sets);
            }
            if (layout != null && layout.pushConstantBytes > 0) {
                if (layout.pushConstantBytes > limits.maxPushConstantSize) {
                    throw new GfxException("vulkan backend: " + layout.pushConstantBytes
                            + " push-constant bytes exceed this device's "
                            + limits.maxPushConstantSize);
                }
                VkPushConstantRange.Buffer range = VkPushConstantRange.calloc(1, stack)
                        .stageFlags(VkEnumMap.shaderStage(layout.pushConstantStages))
                        .offset(0).size(layout.pushConstantBytes);
                ci.pPushConstantRanges(range);
            }
            LongBuffer p = stack.mallocLong(1);
            check(vkCreatePipelineLayout(device, ci, null, p), "vkCreatePipelineLayout");
            pipelineLayouts.add(p.get(0));
            return p.get(0);
        }
    }

    long totalCreatedHandles() { return totalCreated; }
}
