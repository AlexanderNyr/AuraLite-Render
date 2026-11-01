package dev.auralite.render.backend.vk;

import dev.auralite.render.backend.vk.VkDevice.VkBuffer;
import dev.auralite.render.backend.vk.VkDevice.VkImage;
import dev.auralite.render.backend.vk.VkDevice.VkPipeline;
import dev.auralite.render.backend.vk.VkDevice.VkSampler;
import dev.auralite.render.rhi.GfxBuffer;
import dev.auralite.render.rhi.GfxCommandBuffer;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxDescriptorSet;
import dev.auralite.render.rhi.GfxEnums.PassType;
import dev.auralite.render.rhi.GfxEnums.QueueType;
import dev.auralite.render.rhi.GfxException;
import dev.auralite.render.rhi.GfxImage;
import dev.auralite.render.rhi.GfxPipeline;
import dev.auralite.render.rhi.GfxUsage;
import dev.auralite.render.util.Checks;
import dev.auralite.render.util.MathUtil;

import java.nio.ByteBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkViewport;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Recording command buffer. One native buffer per RHI buffer, one-shot.
 *
 * <p>RHI viewports and scissors use a top-left origin on every backend; Vulkan
 * viewports are flipped with the negative-height trick against the pass size.
 * Descriptor sets are allocated from the device's grow-only pools on every
 * bind and validated against the bound pipeline's layout first, the way the
 * NULL backend validates them. Image barriers trust the device's layout
 * tracker over the graph's from-state whenever they disagree: uploads happen
 * outside the graph, and a barrier that claims UNDEFINED for an image the
 * host just filled would let the driver throw those bytes away.
 */
public final class VkCommandBuffer implements GfxCommandBuffer {

    final VkDevice device;
    private final QueueType queue;
    private final long pool;
    private final org.lwjgl.vulkan.VkCommandBuffer nativeHandle;

    private boolean recording;
    private boolean ended;
    private boolean inPass;
    private int passWidth;
    private int passHeight;
    private VkPipeline graphicsPipeline;
    private VkPipeline computePipeline;
    private int commandCount;

    VkCommandBuffer(VkDevice device, QueueType queue) {
        this.device = device;
        this.queue = queue;
        this.pool = device.poolForFamily(device.familyFor(queue));
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferAllocateInfo ai = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                    .commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(1);
            PointerBuffer p = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device.nativeDevice(), ai, p), "vkAllocateCommandBuffers");
            nativeHandle = new org.lwjgl.vulkan.VkCommandBuffer(p.get(0), device.nativeDevice());
        }
    }

    private static void check(int rc, String what) {
        if (rc != VK_SUCCESS) throw new GfxException("vulkan backend: " + what + " failed: " + rc);
    }

    org.lwjgl.vulkan.VkCommandBuffer nativeHandle() { return nativeHandle; }
    boolean ended() { return ended; }

    @Override public void begin() {
        if (recording || ended) throw new GfxException("vulkan backend: begin() on a used command buffer");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo bi = VkCommandBufferBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(nativeHandle, bi), "vkBeginCommandBuffer");
        }
        recording = true;
        count();
    }

    @Override public void end() {
        if (!recording) throw new GfxException("vulkan backend: end() while not recording");
        if (inPass) throw new GfxException("vulkan backend: end() with a render pass still open");
        check(vkEndCommandBuffer(nativeHandle), "vkEndCommandBuffer");
        count();
        recording = false;
        ended = true;
    }

    @Override public boolean isRecording() { return recording; }

    @Override public PassType passType() {
        if (inPass) return PassType.RASTER;
        if (computePipeline != null) return PassType.COMPUTE;
        return PassType.EXTERNAL;
    }

    // ------------------------------------------------------------------ raster

    @Override public void beginRenderPass(GfxDesc.RenderPass pass) {
        checkRecording("beginRenderPass");
        if (inPass) throw new GfxException("vulkan backend: beginRenderPass while a pass is open");
        Checks.notNull(pass, "pass");
        if (pass.width <= 0 || pass.height <= 0) {
            throw new GfxException("vulkan backend: render area " + pass.width + "x" + pass.height
                    + " is not positive");
        }
        if (pass.colors.isEmpty() && pass.depth == null) {
            throw new GfxException("vulkan backend: pass '" + pass.debugName + "' has no attachments at all");
        }
        checkAreaAgainstAttachments(pass);
        long renderPass = device.renderPassFor(pass);
        long framebuffer = device.framebufferFor(pass, renderPass);
        device.notePassExecuted(pass);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int colors = pass.colors.size();
            int hasDepth = pass.depth == null ? 0 : 1;
            VkClearValue.Buffer clears = VkClearValue.calloc(colors + hasDepth, stack);
            for (int i = 0; i < colors; i++) {
                GfxDesc.Attachment a = pass.colors.get(i);
                if (a.loadOp != dev.auralite.render.rhi.GfxEnums.LoadOp.CLEAR) continue;
                if (a.image.format().isInteger()) {
                    if (a.image.format().flags()
                            == dev.auralite.render.rhi.GfxFormat.FLAG_INT) {
                        // Signed vs unsigned is indistinguishable from the clear
                        // floats, so signed formats take the int path: the bit
                        // pattern is what matters and ints preserve it.
                    }
                    boolean signed = a.image.format().name().contains("SINT");
                    for (int c = 0; c < 4; c++) {
                        int v = (int) a.clearColor[c];
                        if (signed) clears.get(i).color().int32(c, v);
                        else clears.get(i).color().uint32(c, v);
                    }
                } else {
                    for (int c = 0; c < 4; c++) clears.get(i).color().float32(c, a.clearColor[c]);
                }
            }
            if (pass.depth != null
                    && pass.depth.loadOp == dev.auralite.render.rhi.GfxEnums.LoadOp.CLEAR) {
                clears.get(colors).depthStencil().depth(pass.depth.clearDepth);
                clears.get(colors).depthStencil().stencil(pass.depth.clearStencil);
            }
            VkRenderPassBeginInfo bi = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(renderPass)
                    .framebuffer(framebuffer)
                    .pClearValues(clears);
            bi.renderArea().offset().x(0).y(0);
            bi.renderArea().extent().width(pass.width).height(pass.height);
            vkCmdBeginRenderPass(nativeHandle, bi, VK_SUBPASS_CONTENTS_INLINE);
        }
        passWidth = pass.width;
        passHeight = pass.height;
        graphicsPipeline = null;
        inPass = true;
        count();
    }

    private void checkAreaAgainstAttachments(GfxDesc.RenderPass pass) {
        for (int i = 0; i < pass.colors.size(); i++) {
            GfxDesc.Attachment a = pass.colors.get(i);
            VkImage img = device.ownImage(a.image);
            int w = Math.max(1, img.desc.width >> a.mipLevel);
            int h = Math.max(1, img.desc.height >> a.mipLevel);
            if (pass.width > w || pass.height > h) {
                throw new GfxException("vulkan backend: pass '" + pass.debugName + "' is " + pass.width
                        + "x" + pass.height + " but colour " + i + " is " + w + "x" + h);
            }
        }
        if (pass.depth != null) {
            VkImage img = device.ownImage(pass.depth.image);
            if (pass.width > img.desc.width || pass.height > img.desc.height) {
                throw new GfxException("vulkan backend: pass '" + pass.debugName + "' is " + pass.width
                        + "x" + pass.height + " but its depth is "
                        + img.desc.width + "x" + img.desc.height);
            }
        }
    }

    @Override public void endRenderPass() {
        checkRecording("endRenderPass");
        if (!inPass) throw new GfxException("vulkan backend: endRenderPass with no pass open");
        vkCmdEndRenderPass(nativeHandle);
        graphicsPipeline = null;
        inPass = false;
        count();
    }

    @Override public void nextSubpass() {
        checkRecording("nextSubpass");
        if (!inPass) throw new GfxException("vulkan backend: nextSubpass outside a render pass");
        // Every AuraLite render pass holds a single subpass, so this is a
        // validation error in the making rather than a feature: refuse it.
        throw new GfxException("vulkan backend: nextSubpass needs subpass structure the graph"
                + " does not emit yet; every pass holds one subpass");
    }

    @Override public void setViewport(float x, float y, float w, float h, float minDepth, float maxDepth) {
        checkRecording("setViewport");
        if (w <= 0 || h <= 0) {
            throw new GfxException("vulkan backend: viewport extent " + w + "x" + h + " is not positive");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkViewport.Buffer viewports = VkViewport.calloc(1, stack);
            // Negative height flips the RHI's top-left origin into Vulkan's.
            viewports.get(0).x(x).y(y + h).width(w).height(-h).minDepth(minDepth).maxDepth(maxDepth);
            vkCmdSetViewport(nativeHandle, 0, viewports);
        }
        count();
    }

    @Override public void setScissor(int x, int y, int w, int h) {
        checkRecording("setScissor");
        if (w <= 0 || h <= 0) {
            throw new GfxException("vulkan backend: scissor extent " + w + "x" + h + " is not positive");
        }
        int iy = inPass ? passHeight - y - h : y;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkRect2D.Buffer scissors = VkRect2D.calloc(1, stack);
            scissors.get(0).offset().x(x).y(iy);
            scissors.get(0).extent().width(w).height(h);
            vkCmdSetScissor(nativeHandle, 0, scissors);
        }
        count();
    }

    @Override public void bindGraphicsPipeline(GfxPipeline pipeline) {
        checkRecording("bindGraphicsPipeline");
        if (!inPass) throw new GfxException("vulkan backend: bindGraphicsPipeline outside a render pass");
        VkPipeline p = ownPipeline(pipeline, false);
        vkCmdBindPipeline(nativeHandle, VK_PIPELINE_BIND_POINT_GRAPHICS, p.handle);
        graphicsPipeline = p;
        count();
    }

    @Override public void bindVertexBuffer(int binding, GfxBuffer buffer, long offset) {
        checkRecording("bindVertexBuffer");
        VkBuffer b = device.ownBuffer(buffer);
        b.requireUsage(GfxUsage.BUFFER_VERTEX, "BUFFER_VERTEX", "a vertex binding");
        if (offset < 0 || offset >= b.desc.sizeBytes) {
            throw new GfxException("vulkan backend: vertex offset " + offset + " on buffer '"
                    + b.desc.debugName + "' of " + b.desc.sizeBytes + " bytes");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindVertexBuffers(nativeHandle, binding,
                    stack.mallocLong(1).put(0, b.handle),
                    stack.mallocLong(1).put(0, offset));
        }
        count();
    }

    @Override public void bindIndexBuffer(GfxBuffer buffer, long offset, boolean index32) {
        checkRecording("bindIndexBuffer");
        VkBuffer b = device.ownBuffer(buffer);
        b.requireUsage(GfxUsage.BUFFER_INDEX, "BUFFER_INDEX", "an index binding");
        if (offset < 0 || offset >= b.desc.sizeBytes) {
            throw new GfxException("vulkan backend: index offset " + offset + " on buffer '"
                    + b.desc.debugName + "' of " + b.desc.sizeBytes + " bytes");
        }
        vkCmdBindIndexBuffer(nativeHandle, b.handle, offset,
                index32 ? VK_INDEX_TYPE_UINT32 : VK_INDEX_TYPE_UINT16);
        count();
    }

    @Override public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        checkRecording("draw");
        checkInPass("draw");
        if (graphicsPipeline == null) {
            throw new GfxException("vulkan backend: draw with no graphics pipeline bound");
        }
        if (vertexCount <= 0) throw new GfxException("vulkan backend: draw with vertexCount " + vertexCount);
        if (instanceCount <= 0) {
            throw new GfxException("vulkan backend: draw with instanceCount " + instanceCount);
        }
        vkCmdDraw(nativeHandle, vertexCount, instanceCount, firstVertex, firstInstance);
        count();
    }

    @Override public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset,
                                      int firstInstance) {
        checkRecording("drawIndexed");
        checkInPass("drawIndexed");
        if (graphicsPipeline == null) {
            throw new GfxException("vulkan backend: drawIndexed with no graphics pipeline bound");
        }
        if (indexCount <= 0) {
            throw new GfxException("vulkan backend: drawIndexed with indexCount " + indexCount);
        }
        vkCmdDrawIndexed(nativeHandle, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
        count();
    }

    @Override public void drawIndirect(GfxBuffer args, long offset, int drawCount, int stride) {
        checkRecording("drawIndirect");
        checkInPass("drawIndirect");
        VkBuffer a = indirectArgs(args, offset, "drawIndirect");
        if (graphicsPipeline == null) {
            throw new GfxException("vulkan backend: drawIndirect with no graphics pipeline bound");
        }
        vkCmdDrawIndirect(nativeHandle, a.handle, offset, drawCount, stride == 0 ? 16 : stride);
        count();
    }

    @Override public void drawIndexedIndirect(GfxBuffer args, long offset, int drawCount, int stride) {
        checkRecording("drawIndexedIndirect");
        checkInPass("drawIndexedIndirect");
        VkBuffer a = indirectArgs(args, offset, "drawIndexedIndirect");
        if (graphicsPipeline == null) {
            throw new GfxException("vulkan backend: drawIndexedIndirect with no graphics pipeline bound");
        }
        vkCmdDrawIndexedIndirect(nativeHandle, a.handle, offset, drawCount, stride == 0 ? 20 : stride);
        count();
    }

    @Override public void drawIndexedIndirectCount(GfxBuffer args, long argsOffset, GfxBuffer count,
                                                   long countOffset, int maxDrawCount, int stride) {
        checkRecording("drawIndexedIndirectCount");
        checkInPass("drawIndexedIndirectCount");
        VkBuffer a = indirectArgs(args, argsOffset, "drawIndexedIndirectCount");
        VkBuffer c = device.ownBuffer(count);
        c.requireUsage(GfxUsage.BUFFER_INDIRECT, "BUFFER_INDIRECT", "a draw-count buffer");
        if (graphicsPipeline == null) {
            throw new GfxException("vulkan backend: drawIndexedIndirectCount with no graphics pipeline bound");
        }
        vkCmdDrawIndexedIndirectCount(nativeHandle, a.handle, argsOffset, c.handle, countOffset,
                maxDrawCount, stride == 0 ? 20 : stride);
        count();
    }

    private VkBuffer indirectArgs(GfxBuffer args, long offset, String what) {
        VkBuffer a = device.ownBuffer(args);
        a.requireUsage(GfxUsage.BUFFER_INDIRECT, "BUFFER_INDIRECT", "an indirect " + what);
        if (offset < 0) throw new GfxException("vulkan backend: " + what + " with negative offset");
        return a;
    }

    // ----------------------------------------------------------------- compute

    @Override public void bindComputePipeline(GfxPipeline pipeline) {
        checkRecording("bindComputePipeline");
        if (inPass) throw new GfxException("vulkan backend: bindComputePipeline inside a render pass");
        VkPipeline p = ownPipeline(pipeline, true);
        vkCmdBindPipeline(nativeHandle, VK_PIPELINE_BIND_POINT_COMPUTE, p.handle);
        computePipeline = p;
        count();
    }

    @Override public void dispatch(int groupsX, int groupsY, int groupsZ) {
        checkRecording("dispatch");
        if (inPass) throw new GfxException("vulkan backend: dispatch inside a render pass");
        if (computePipeline == null) {
            throw new GfxException("vulkan backend: dispatch with no compute pipeline bound");
        }
        if (groupsX <= 0 || groupsY <= 0 || groupsZ <= 0) {
            throw new GfxException("vulkan backend: dispatch extent " + groupsX + "x" + groupsY + "x" + groupsZ
                    + " is not positive");
        }
        if (groupsX > device.limits().maxComputeWorkGroupCountX
                || groupsY > device.limits().maxComputeWorkGroupCountY
                || groupsZ > device.limits().maxComputeWorkGroupCountZ) {
            throw new GfxException("vulkan backend: dispatch " + groupsX + "x" + groupsY + "x" + groupsZ
                    + " exceeds this device's work-group counts");
        }
        vkCmdDispatch(nativeHandle, groupsX, groupsY, groupsZ);
        count();
    }

    @Override public void dispatchIndirect(GfxBuffer args, long offset) {
        checkRecording("dispatchIndirect");
        if (inPass) throw new GfxException("vulkan backend: dispatchIndirect inside a render pass");
        if (computePipeline == null) {
            throw new GfxException("vulkan backend: dispatchIndirect with no compute pipeline bound");
        }
        VkBuffer a = device.ownBuffer(args);
        a.requireUsage(GfxUsage.BUFFER_INDIRECT, "BUFFER_INDIRECT", "an indirect dispatch");
        vkCmdDispatchIndirect(nativeHandle, a.handle, offset);
        count();
    }

    // --------------------------------------------------------------- resources

    @Override public void bindDescriptorSet(int setIndex, GfxDescriptorSet set) {
        checkRecording("bindDescriptorSet");
        Checks.notNull(set, "set");
        VkPipeline p = inPass ? graphicsPipeline : computePipeline;
        if (p == null) {
            throw new GfxException("vulkan backend: bindDescriptorSet with no pipeline bound;"
                    + " the set needs a layout to be written against");
        }
        if (p.descLayout == null || setIndex < 0 || setIndex >= p.setLayouts.length) {
            throw new GfxException("vulkan backend: descriptor set " + setIndex
                    + " does not exist on pipeline '" + p.debugName() + "'");
        }
        checkWritesAgainstLayout(p, setIndex, set);
        long allocated = device.allocateSet(p.setLayouts[setIndex]);
        device.writeSet(allocated, set);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindDescriptorSets(nativeHandle,
                    p.isCompute() ? VK_PIPELINE_BIND_POINT_COMPUTE : VK_PIPELINE_BIND_POINT_GRAPHICS,
                    p.layout, setIndex,
                    stack.mallocLong(1).put(0, allocated), null);
        }
        count();
    }

    /** The NULL backend's validateAgainst, restated for real sets: kind, type and usage. */
    private void checkWritesAgainstLayout(VkPipeline p, int setIndex, GfxDescriptorSet set) {
        GfxDesc.PipelineLayout.SetLayout layout = p.descLayout.sets.get(setIndex);
        for (GfxDescriptorSet.Write w : set.writes()) {
            GfxDesc.PipelineLayout.BindingDesc found = null;
            if (layout != null) {
                for (GfxDesc.PipelineLayout.BindingDesc b : layout.bindings) {
                    if (b.binding == w.binding) found = b;
                }
            }
            if (found == null) {
                throw new GfxException("vulkan backend: set " + setIndex + " binding " + w.binding
                        + " does not exist on pipeline '" + p.debugName() + "'");
            }
            if (found.kind != w.kind) {
                throw new GfxException("vulkan backend: set " + setIndex + " binding " + w.binding
                        + " is " + found.kind + " in the layout but " + w.kind + " in the write");
            }
            if (w.arrayIndex < 0 || w.arrayIndex >= found.count) {
                throw new GfxException("vulkan backend: set " + setIndex + " binding " + w.binding
                        + " writes array element " + w.arrayIndex + " of " + found.count);
            }
            switch (w.kind) {
                case UNIFORM_BUFFER:
                    requireBuffer(w, GfxUsage.BUFFER_UNIFORM, "BUFFER_UNIFORM");
                    break;
                case STORAGE_BUFFER:
                    requireBuffer(w, GfxUsage.BUFFER_STORAGE, "BUFFER_STORAGE");
                    break;
                case COMBINED_IMAGE_SAMPLER:
                    requireImage(w, GfxUsage.IMAGE_SAMPLED, "IMAGE_SAMPLED");
                    if (!(w.combinedSampler instanceof VkSampler)) {
                        throw new GfxException("vulkan backend: set " + setIndex + " binding "
                                + w.binding + " is a combined sampler with no Vulkan sampler");
                    }
                    break;
                case SAMPLED_IMAGE:
                case INPUT_ATTACHMENT:
                    requireImage(w, GfxUsage.IMAGE_SAMPLED, "IMAGE_SAMPLED");
                    break;
                case SAMPLER:
                    if (!(w.resource instanceof VkSampler)) {
                        throw new GfxException("vulkan backend: set " + setIndex + " binding "
                                + w.binding + " holds a foreign sampler");
                    }
                    break;
                case STORAGE_IMAGE:
                    requireImage(w, GfxUsage.IMAGE_STORAGE, "IMAGE_STORAGE");
                    break;
                default:
                    throw new GfxException("vulkan backend: set " + setIndex + " binding "
                            + w.binding + " of kind " + w.kind + " cannot be written here");
            }
        }
    }

    private void requireBuffer(GfxDescriptorSet.Write w, int flag, String flagName) {
        if (!(w.resource instanceof VkBuffer)) {
            throw new GfxException("vulkan backend: binding " + w.binding
                    + " needs a Vulkan buffer, got a foreign resource");
        }
        ((VkBuffer) w.resource).requireUsage(flag, flagName, "descriptor binding " + w.binding);
    }

    private void requireImage(GfxDescriptorSet.Write w, int flag, String flagName) {
        if (!(w.resource instanceof VkImage)) {
            throw new GfxException("vulkan backend: binding " + w.binding
                    + " needs a Vulkan image, got a foreign resource");
        }
        ((VkImage) w.resource).requireUsage(flag, flagName, "descriptor binding " + w.binding);
    }

    @Override public void pushConstants(int offset, ByteBuffer data) {
        checkRecording("pushConstants");
        Checks.notNull(data, "data");
        VkPipeline p = inPass ? graphicsPipeline : computePipeline;
        if (p == null) {
            throw new GfxException("vulkan backend: pushConstants with no pipeline bound");
        }
        if (p.descLayout == null || p.descLayout.pushConstantBytes <= 0) {
            throw new GfxException("vulkan backend: pipeline '" + p.debugName()
                    + "' has no push-constant range");
        }
        if (offset < 0 || offset + data.remaining() > p.descLayout.pushConstantBytes) {
            throw new GfxException("vulkan backend: push range [" + offset + ".."
                    + (offset + data.remaining()) + ") exceeds the pipeline's "
                    + p.descLayout.pushConstantBytes + " bytes");
        }
        vkCmdPushConstants(nativeHandle, p.layout,
                VkEnumMap.shaderStage(p.descLayout.pushConstantStages), offset, data);
        count();
    }

    @Override public void pushConstants(int offset, float[] values) {
        Checks.notNull(values, "values");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer bb = stack.malloc(values.length * 4);
            bb.asFloatBuffer().put(values);
            pushConstants(offset, bb);
        }
    }

    @Override public void copyBuffer(GfxBuffer src, long srcOffset, GfxBuffer dst, long dstOffset, long sizeBytes) {
        checkRecording("copyBuffer");
        VkBuffer s = device.ownBuffer(src);
        VkBuffer d = device.ownBuffer(dst);
        s.requireUsage(GfxUsage.BUFFER_TRANSFER_SRC, "BUFFER_TRANSFER_SRC", "a copy");
        d.requireUsage(GfxUsage.BUFFER_TRANSFER_DST, "BUFFER_TRANSFER_DST", "a copy");
        s.checkRange(srcOffset, sizeBytes, "copy source");
        d.checkRange(dstOffset, sizeBytes, "copy destination");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack)
                    .srcOffset(srcOffset).dstOffset(dstOffset).size(sizeBytes);
            vkCmdCopyBuffer(nativeHandle, s.handle, d.handle, region);
        }
        count();
    }

    @Override public void copyBufferToImage(GfxBuffer src, GfxImage dst, int mipLevel) {
        checkRecording("copyBufferToImage");
        VkBuffer s = device.ownBuffer(src);
        VkImage d = device.ownImage(dst);
        s.requireUsage(GfxUsage.BUFFER_TRANSFER_SRC, "BUFFER_TRANSFER_SRC", "an image upload");
        d.requireUsage(GfxUsage.IMAGE_TRANSFER_DST, "IMAGE_TRANSFER_DST", "an image upload");
        d.checkLevel(mipLevel);
        if (d.desc.format.isCompressed()) {
            throw new GfxException("vulkan backend: staged upload into compressed image '"
                    + d.desc.debugName + "' needs block math this version does not have yet");
        }
        int w = MathUtil.mipExtent(d.desc.width, mipLevel);
        int h = MathUtil.mipExtent(d.desc.height, mipLevel);
        int dep = d.desc.type == dev.auralite.render.rhi.GfxEnums.ImageType.TEXTURE_3D
                ? MathUtil.mipExtent(d.desc.depth, mipLevel) : 1;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(mipLevel).baseArrayLayer(0).layerCount(d.desc.arrayLayers);
            region.get(0).imageOffset().x(0).y(0).z(0);
            region.get(0).imageExtent().width(w).height(h).depth(dep);
            vkCmdCopyBufferToImage(nativeHandle, s.handle, d.handle,
                    device.currentLayout(d.handle), region);
        }
        count();
    }

    @Override public void copyImage(GfxImage src, int srcMip, GfxImage dst, int dstMip,
                                    int width, int height, int depth) {
        checkRecording("copyImage");
        VkImage s = device.ownImage(src);
        VkImage d = device.ownImage(dst);
        s.requireUsage(GfxUsage.IMAGE_TRANSFER_SRC, "IMAGE_TRANSFER_SRC", "an image copy");
        d.requireUsage(GfxUsage.IMAGE_TRANSFER_DST, "IMAGE_TRANSFER_DST", "an image copy");
        s.checkLevel(srcMip);
        d.checkLevel(dstMip);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCopy.Buffer regions = VkImageCopy.calloc(1, stack);
            int aspect = s.desc.format.isDepth() ? VK_IMAGE_ASPECT_DEPTH_BIT
                    : VK_IMAGE_ASPECT_COLOR_BIT;
            regions.get(0).srcSubresource().aspectMask(aspect)
                    .mipLevel(srcMip).baseArrayLayer(0).layerCount(1);
            regions.get(0).dstSubresource().aspectMask(aspect)
                    .mipLevel(dstMip).baseArrayLayer(0).layerCount(1);
            regions.get(0).srcOffset().x(0).y(0).z(0);
            regions.get(0).dstOffset().x(0).y(0).z(0);
            regions.get(0).extent().width(width).height(height).depth(depth);
            vkCmdCopyImage(nativeHandle, s.handle, device.currentLayout(s.handle),
                    d.handle, device.currentLayout(d.handle), regions);
        }
        count();
    }

    @Override public void blitImage(GfxImage src, GfxImage dst, boolean linearFilter) {
        checkRecording("blitImage");
        VkImage s = device.ownImage(src);
        VkImage d = device.ownImage(dst);
        s.requireUsage(GfxUsage.IMAGE_TRANSFER_SRC, "IMAGE_TRANSFER_SRC", "a blit");
        d.requireUsage(GfxUsage.IMAGE_TRANSFER_DST, "IMAGE_TRANSFER_DST", "a blit");
        if (linearFilter && (s.desc.format.isDepth() || d.desc.format.isDepth())) {
            throw new GfxException("vulkan backend: linear blit on a depth image is illegal on every API");
        }
        if (s.desc.format.isInteger() && linearFilter) {
            throw new GfxException("vulkan backend: linear blit on integer image '"
                    + s.desc.debugName + "' is illegal; integers filter nearest");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageBlit.Buffer blits = VkImageBlit.calloc(1, stack);
            VkImageBlit blit = blits.get(0);
            int aspect = s.desc.format.isDepth() ? VK_IMAGE_ASPECT_DEPTH_BIT
                    : VK_IMAGE_ASPECT_COLOR_BIT;
            blit.srcSubresource().aspectMask(aspect)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            blit.srcOffsets(0).x(0).y(0).z(0);
            blit.srcOffsets(1).x(s.desc.width).y(s.desc.height).z(1);
            blit.dstSubresource().aspectMask(aspect)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            blit.dstOffsets(0).x(0).y(0).z(0);
            blit.dstOffsets(1).x(d.desc.width).y(d.desc.height).z(1);
            vkCmdBlitImage(nativeHandle, s.handle, device.currentLayout(s.handle),
                    d.handle, device.currentLayout(d.handle), blits,
                    linearFilter ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
        }
        count();
    }

    @Override public void generateMipmaps(GfxImage image) {
        checkRecording("generateMipmaps");
        VkImage img = device.ownImage(image);
        if (img.desc.mipLevels <= 1) {
            throw new GfxException("vulkan backend: generateMipmaps on single-level image '"
                    + img.desc.debugName + "'");
        }
        if (img.desc.format.isCompressed()) {
            throw new GfxException("vulkan backend: generateMipmaps on compressed image '"
                    + img.desc.debugName + "'");
        }
        img.requireUsage(GfxUsage.IMAGE_TRANSFER_SRC, "IMAGE_TRANSFER_SRC", "mipmap generation");
        img.requireUsage(GfxUsage.IMAGE_TRANSFER_DST, "IMAGE_TRANSFER_DST", "mipmap generation");
        int entry = device.currentLayout(img.handle);
        if (entry != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                && entry != VK_IMAGE_LAYOUT_GENERAL) {
            throw new GfxException("vulkan backend: generateMipmaps on image '" + img.desc.debugName
                    + "' starts from layout " + entry + "; stage it to SHADER_READ or GENERAL first");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int level = 1; level < img.desc.mipLevels; level++) {
                int srcW = MathUtil.mipExtent(img.desc.width, level - 1);
                int srcH = MathUtil.mipExtent(img.desc.height, level - 1);
                int dstW = MathUtil.mipExtent(img.desc.width, level);
                int dstH = MathUtil.mipExtent(img.desc.height, level);
                barrierOneLevel(img, level - 1, level == 1 ? entry : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        level == 1 ? VK_ACCESS_SHADER_READ_BIT : VK_ACCESS_TRANSFER_READ_BIT,
                        VK_ACCESS_TRANSFER_READ_BIT,
                        level == 1 ? VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                                : VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT);
                barrierOneLevel(img, level, entry, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        0, VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
                VkImageBlit.Buffer blits = VkImageBlit.calloc(1, stack);
                VkImageBlit blit = blits.get(0);
                blit.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(level - 1).baseArrayLayer(0).layerCount(1);
                blit.srcOffsets(0).x(0).y(0).z(0);
                blit.srcOffsets(1).x(srcW).y(srcH).z(1);
                blit.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(level).baseArrayLayer(0).layerCount(1);
                blit.dstOffsets(0).x(0).y(0).z(0);
                blit.dstOffsets(1).x(dstW).y(dstH).z(1);
                vkCmdBlitImage(nativeHandle, img.handle, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        img.handle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blits, VK_FILTER_LINEAR);
                barrierOneLevel(img, level - 1, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, entry,
                        VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
            }
            barrierOneLevel(img, img.desc.mipLevels - 1, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, entry,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
        }
        device.setLayout(img.handle, entry);
        count();
    }

    private void barrierOneLevel(VkImage img, int level, int oldLayout, int newLayout,
                                 int srcAccess, int dstAccess, int srcStage, int dstStage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                    .oldLayout(oldLayout).newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(img.handle);
            barrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(level).levelCount(1)
                    .baseArrayLayer(0).layerCount(img.desc.arrayLayers);
            vkCmdPipelineBarrier(nativeHandle, srcStage, dstStage, 0, null, null, barrier);
        }
    }

    @Override public void barrier(GfxImage image, dev.auralite.render.rhi.GfxEnums.ResourceState from,
                                  dev.auralite.render.rhi.GfxEnums.ResourceState to,
                                  int srcAccess, int dstAccess) {
        checkRecording("barrier");
        VkImage img = device.ownImage(image);
        int mappedFrom = layoutForDevice(from);
        int mappedTo = layoutForDevice(to);
        int tracked = device.currentLayout(img.handle);
        // The tracker's layout wins over the graph's from-state: uploads happen
        // outside the graph, and oldLayout=UNDEFINED would let the driver
        // discard bytes the host just wrote. A healed barrier pays for its
        // caution with an ALL_COMMANDS source stage. One named exception: a
        // PRESENT from-state on an image the tracker has never seen is the
        // first present of a fresh image - there is nothing to preserve, the
        // presenting side rewrites the whole image, so the canonical
        // UNDEFINED discard transition is the honest old layout.
        int oldLayout;
        int srcStage;
        int actualSrcAccess = srcAccess;
        if (tracked != VK_IMAGE_LAYOUT_UNDEFINED) {
            oldLayout = tracked;
            srcStage = (oldLayout == mappedFrom) ? VkEnumMap.stageForState(from)
                    : VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        } else if (from == dev.auralite.render.rhi.GfxEnums.ResourceState.PRESENT) {
            oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            actualSrcAccess = 0;
        } else {
            oldLayout = mappedFrom;
            srcStage = (oldLayout == mappedFrom) ? VkEnumMap.stageForState(from)
                    : VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .srcAccessMask(VkEnumMap.accessFlags(actualSrcAccess))
                    .dstAccessMask(VkEnumMap.accessFlags(dstAccess))
                    .oldLayout(oldLayout).newLayout(mappedTo)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(img.handle);
            barrier.get(0).subresourceRange().aspectMask(img.aspect())
                    .baseMipLevel(0).levelCount(img.desc.mipLevels)
                    .baseArrayLayer(0).layerCount(img.desc.arrayLayers);
            vkCmdPipelineBarrier(nativeHandle, srcStage, VkEnumMap.stageForState(to), 0,
                    null, null, barrier);
        }
        device.setLayout(img.handle, mappedTo);
        count();
    }

    /**
     * {@code VK_IMAGE_LAYOUT_PRESENT_SRC_KHR} needs VK_KHR_swapchain, which
     * this device never enables - the PRESENT state translates to GENERAL,
     * the legal stand-in for "the presenting side owns this image".
     */
    private int layoutForDevice(dev.auralite.render.rhi.GfxEnums.ResourceState state) {
        int layout = VkEnumMap.imageLayout(state);
        if (layout == VK_IMAGE_LAYOUT_PRESENT_SRC_KHR && !device.swapchainEnabled()) {
            return VK_IMAGE_LAYOUT_GENERAL;
        }
        return layout;
    }

    @Override public void barrier(GfxBuffer buffer, int srcAccess, int dstAccess) {
        checkRecording("barrier");
        VkBuffer buf = device.ownBuffer(buffer);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferMemoryBarrier.Buffer barrier =
                    VkBufferMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                    .srcAccessMask(VkEnumMap.accessFlags(srcAccess))
                    .dstAccessMask(VkEnumMap.accessFlags(dstAccess))
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .buffer(buf.handle)
                    .offset(0).size(VK_WHOLE_SIZE);
            vkCmdPipelineBarrier(nativeHandle, VkEnumMap.stageForAccess(srcAccess),
                    VkEnumMap.stageForAccess(dstAccess), 0, null, barrier, null);
        }
        count();
    }

    @Override public void memoryBarrierAll() {
        checkRecording("memoryBarrierAll");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            vkCmdPipelineBarrier(nativeHandle, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, barrier, null, null);
        }
        count();
    }

    @Override public void writeTimestamp(int queryIndex) {
        checkRecording("writeTimestamp");
        if (queryIndex < 0) throw new GfxException("vulkan backend: negative timestamp index");
        // Capture-oriented like the GL lane: the pool resets every frame and
        // nothing reads the values back on the CPU.
        vkCmdWriteTimestamp(nativeHandle, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                device.timestampPool(), device.nextTimestampIndex());
        count();
    }

    @Override public int commandCount() { return commandCount; }

    // ------------------------------------------------------------------ misc

    private void checkRecording(String what) {
        if (!recording) {
            throw new GfxException("vulkan backend: " + what + " recorded outside begin()/end()");
        }
    }

    private void checkInPass(String what) {
        if (!inPass) throw new GfxException("vulkan backend: " + what + " outside a render pass");
    }

    private VkPipeline ownPipeline(GfxPipeline pipeline, boolean compute) {
        if (!(pipeline instanceof VkPipeline) || ((VkPipeline) pipeline).device != device) {
            throw new GfxException("vulkan backend: a foreign pipeline was bound here");
        }
        VkPipeline p = (VkPipeline) pipeline;
        if (p.isCompute() != compute) {
            throw new GfxException("vulkan backend: pipeline '" + p.debugName() + "' is "
                    + (p.isCompute() ? "compute" : "graphics") + ", bound as "
                    + (compute ? "compute" : "graphics"));
        }
        return p;
    }

    private void count() { commandCount++; }

    void dispose() {
        vkFreeCommandBuffers(device.nativeDevice(), pool, nativeHandle);
    }
}
