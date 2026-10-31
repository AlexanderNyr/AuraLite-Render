package dev.auralite.render.backend.nulldev;

import dev.auralite.render.rhi.GfxBuffer;
import dev.auralite.render.rhi.GfxCapability;
import dev.auralite.render.rhi.GfxCommandBuffer;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxDescriptorSet;
import dev.auralite.render.rhi.GfxEnums.Access;
import dev.auralite.render.rhi.GfxEnums.PassType;
import dev.auralite.render.rhi.GfxEnums.QueueType;
import dev.auralite.render.rhi.GfxEnums.ResourceState;
import dev.auralite.render.rhi.GfxImage;
import dev.auralite.render.rhi.GfxPipeline;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Command recording with a contract check on every call.
 *
 * <p>The rules enforced are the ones a real driver enforces too late or not at
 * all: drawing outside a render pass, dispatching inside one, binding a
 * descriptor set the pipeline never declared, pushing more constants than the
 * layout allows, indexing without an index buffer, indirect draws on a device
 * that does not advertise them. A pack that breaks one of these fails
 * {@code make test} instead of producing a black screen on someone's machine.
 */
public final class RecordingCommandBuffer implements GfxCommandBuffer {

    /** Command opcodes, counted for the receipts. */
    public enum Op {
        BEGIN, END, BEGIN_PASS, END_PASS, NEXT_SUBPASS, VIEWPORT, SCISSOR,
        BIND_GFX, BIND_COMPUTE, BIND_VERTEX, BIND_INDEX, BIND_SET, PUSH_CONSTANTS,
        DRAW, DRAW_INDEXED, DRAW_INDIRECT, DRAW_INDEXED_INDIRECT, DRAW_INDEXED_INDIRECT_COUNT,
        DISPATCH, DISPATCH_INDIRECT, COPY_BUFFER, COPY_BUFFER_TO_IMAGE, COPY_IMAGE,
        BLIT, GEN_MIPS, BARRIER_IMAGE, BARRIER_BUFFER, BARRIER_ALL, TIMESTAMP
    }

    private final NullDevice device;
    private final QueueType queue;
    private final List<String> trace = new ArrayList<String>();
    private final int[] histogram = new int[Op.values().length];

    private boolean recording;
    private boolean ended;
    private boolean inPass;
    private int subpass;
    private GfxDesc.RenderPass currentPass;
    private GfxPipeline graphicsPipeline;
    private GfxPipeline computePipeline;
    private GfxBuffer indexBuffer;
    private boolean index32;
    private final boolean[] vertexBound = new boolean[16];
    private final boolean[] setBound = new boolean[8];
    private int commands;
    private int draws;
    private int dispatches;
    private long verticesDrawn;
    private long instancesDrawn;
    private long workGroupsDispatched;

    RecordingCommandBuffer(NullDevice device, QueueType queue) {
        this.device = device;
        this.queue = queue;
    }

    public QueueType queue() { return queue; }
    public List<String> trace() { return Collections.unmodifiableList(trace); }
    public int count(Op op) { return histogram[op.ordinal()]; }
    public int draws() { return draws; }
    public int dispatches() { return dispatches; }
    public long verticesDrawn() { return verticesDrawn; }
    public long instancesDrawn() { return instancesDrawn; }
    public long workGroupsDispatched() { return workGroupsDispatched; }
    public boolean isInPass() { return inPass; }
    public GfxPipeline graphicsPipeline() { return graphicsPipeline; }
    public GfxPipeline computePipeline() { return computePipeline; }

    private void record(Op op, String detail) {
        if (ended && op != Op.BEGIN) {
            device.violate("command " + op + " recorded after end()");
            return;
        }
        if (!recording && op != Op.BEGIN) {
            device.violate("command " + op + " recorded outside begin()/end()");
            return;
        }
        histogram[op.ordinal()]++;
        commands++;
        device.note("  cmd " + op + (detail == null || detail.isEmpty() ? "" : " " + detail));
        if (trace.size() < 20000) trace.add(op + (detail == null ? "" : " " + detail));
    }

    // ------------------------------------------------------------- lifecycle
    @Override public void begin() {
        if (recording) { device.violate("begin() while already recording"); return; }
        if (ended) { device.violate("begin() after end(); command buffers are one-shot"); return; }
        recording = true;
        record(Op.BEGIN, queue.name());
    }

    @Override public void end() {
        if (!recording) { device.violate("end() while not recording"); return; }
        // A refusal must leave the buffer usable: the caller closes the pass and
        // ends cleanly. Recording END here would kill the buffer while a pass is
        // still open, and the recovery path would silently record nothing.
        if (inPass) { device.violate("end() with a render pass still open"); return; }
        // Record before flipping the flags: END is itself a command, and record()
        // refuses anything after end().
        record(Op.END, "");
        recording = false;
        ended = true;
    }

    @Override public boolean isRecording() { return recording; }

    @Override public PassType passType() {
        if (inPass) return PassType.RASTER;
        if (computePipeline != null) return PassType.COMPUTE;
        return PassType.EXTERNAL;
    }

    @Override public int commandCount() { return commands; }

    // ---------------------------------------------------------------- raster
    @Override public void beginRenderPass(GfxDesc.RenderPass pass) {
        if (pass == null) { device.violate("beginRenderPass(null)"); return; }
        if (inPass) { device.violate("beginRenderPass while '" + currentPass.debugName + "' is open"); return; }
        if (queue == QueueType.COMPUTE) {
            device.violate("render pass on a COMPUTE queue");
            return;
        }
        int colors = pass.colorCount();
        if (colors > device.limits().maxColorAttachments) {
            device.violate("render pass '" + pass.debugName + "' has " + colors
                    + " colour attachments, limit " + device.limits().maxColorAttachments);
            return;
        }
        for (int i = 0; i < colors; i++) {
            GfxDesc.Attachment a = pass.colors.get(i);
            if (a.image == null) { device.violate("render pass '" + pass.debugName + "' colour " + i + " has no image"); return; }
            if (!a.image.format().isColorAttachment()) {
                device.violate("render pass '" + pass.debugName + "' colour " + i + " has format "
                        + a.image.format() + ", which is not a colour format");
                return;
            }
            if (a.image.width() < pass.width || a.image.height() < pass.height) {
                device.violate("render pass '" + pass.debugName + "' is " + pass.width + "x" + pass.height
                        + " but colour " + i + " is " + a.image.width() + "x" + a.image.height());
                return;
            }
        }
        if (pass.depth != null) {
            if (pass.depth.image == null) { device.violate("depth attachment has no image"); return; }
            if (!pass.depth.image.format().isDepthStencil()) {
                device.violate("depth attachment has format " + pass.depth.image.format());
                return;
            }
        }
        if (colors == 0 && pass.depth == null) {
            device.violate("render pass '" + pass.debugName + "' has no attachments at all");
            return;
        }
        inPass = true;
        subpass = 0;
        currentPass = pass;
        graphicsPipeline = null;
        for (int i = 0; i < setBound.length; i++) setBound[i] = false;
        record(Op.BEGIN_PASS, pass.debugName + " " + pass.width + "x" + pass.height
                + " colors=" + colors + (pass.depth != null ? " depth=yes" : ""));
    }

    @Override public void endRenderPass() {
        if (!inPass) { device.violate("endRenderPass with no pass open"); return; }
        record(Op.END_PASS, currentPass.debugName);
        inPass = false;
        currentPass = null;
        graphicsPipeline = null;
    }

    @Override public void nextSubpass() {
        if (!inPass) { device.violate("nextSubpass outside a render pass"); return; }
        subpass++;
        graphicsPipeline = null;
        record(Op.NEXT_SUBPASS, "subpass=" + subpass);
    }

    @Override public void setViewport(float x, float y, float w, float h, float minDepth, float maxDepth) {
        if (!inPass) { device.violate("setViewport outside a render pass"); return; }
        if (w <= 0 || h <= 0) { device.violate("viewport extent " + w + "x" + h + " is not positive"); return; }
        if (w > device.limits().maxViewportWidth) {
            device.violate("viewport width " + w + " exceeds " + device.limits().maxViewportWidth);
            return;
        }
        if (minDepth > maxDepth) { device.violate("viewport depth range " + minDepth + ".." + maxDepth + " is inverted"); return; }
        record(Op.VIEWPORT, x + "," + y + " " + w + "x" + h);
    }

    @Override public void setScissor(int x, int y, int w, int h) {
        if (!inPass) { device.violate("setScissor outside a render pass"); return; }
        if (w < 0 || h < 0) { device.violate("scissor extent " + w + "x" + h + " is negative"); return; }
        record(Op.SCISSOR, x + "," + y + " " + w + "x" + h);
    }

    @Override public void bindGraphicsPipeline(GfxPipeline pipeline) {
        if (pipeline == null) { device.violate("bindGraphicsPipeline(null)"); return; }
        if (pipeline.isCompute()) { device.violate("compute pipeline bound on the graphics path"); return; }
        if (!pipeline.isValid()) { device.violate("binding invalid pipeline: " + pipeline.infoLog()); return; }
        if (!inPass) { device.violate("bindGraphicsPipeline outside a render pass"); return; }
        graphicsPipeline = pipeline;
        record(Op.BIND_GFX, pipeline.debugName());
    }

    @Override public void bindVertexBuffer(int binding, GfxBuffer buffer, long offset) {
        if (binding < 0 || binding >= vertexBound.length) {
            device.violate("vertex binding " + binding + " out of range");
            return;
        }
        if (buffer == null) { device.violate("bindVertexBuffer(null) at binding " + binding); return; }
        if ((buffer.usage() & dev.auralite.render.rhi.GfxUsage.BUFFER_VERTEX) == 0) {
            device.violate("buffer '" + buffer.debugName() + "' was not created with VERTEX usage");
            return;
        }
        if (offset + 1 > buffer.sizeBytes()) {
            device.violate("vertex offset " + offset + " overruns buffer of " + buffer.sizeBytes() + "B");
            return;
        }
        vertexBound[binding] = true;
        record(Op.BIND_VERTEX, binding + " <- " + buffer.debugName() + " +" + offset);
    }

    @Override public void bindIndexBuffer(GfxBuffer buffer, long offset, boolean is32) {
        if (buffer == null) { device.violate("bindIndexBuffer(null)"); return; }
        if ((buffer.usage() & dev.auralite.render.rhi.GfxUsage.BUFFER_INDEX) == 0) {
            device.violate("buffer '" + buffer.debugName() + "' was not created with INDEX usage");
            return;
        }
        indexBuffer = buffer;
        index32 = is32;
        record(Op.BIND_INDEX, buffer.debugName() + " +" + offset + (is32 ? " u32" : " u16"));
    }

    private boolean checkDraw(String what) {
        // The outermost contract first: a command outside begin()/end() is more
        // fundamental than one outside a render pass, and naming the wrong one
        // sends a pack author looking in the wrong place.
        if (!recording) { device.violate(what + " recorded outside begin()/end()"); return false; }
        if (!inPass) { device.violate(what + " outside a render pass"); return false; }
        if (graphicsPipeline == null) { device.violate(what + " with no graphics pipeline bound"); return false; }
        return true;
    }

    @Override public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        if (!checkDraw("draw")) return;
        if (vertexCount <= 0) { device.violate("draw with vertexCount " + vertexCount); return; }
        if (instanceCount <= 0) { device.violate("draw with instanceCount " + instanceCount); return; }
        draws++;
        verticesDrawn += vertexCount;
        instancesDrawn += instanceCount;
        record(Op.DRAW, "v=" + vertexCount + " i=" + instanceCount + " first=" + firstVertex);
    }

    @Override public void drawIndexed(int indexCount, int instanceCount, int firstIndex,
                                      int vertexOffset, int firstInstance) {
        if (!checkDraw("drawIndexed")) return;
        if (indexBuffer == null) { device.violate("drawIndexed with no index buffer bound"); return; }
        if (indexCount <= 0) { device.violate("drawIndexed with indexCount " + indexCount); return; }
        long bytesPerIndex = index32 ? 4 : 2;
        if ((firstIndex + indexCount) * bytesPerIndex > indexBuffer.sizeBytes()) {
            device.violate("drawIndexed range " + firstIndex + "+" + indexCount
                    + " overruns index buffer of " + indexBuffer.sizeBytes() + "B");
            return;
        }
        draws++;
        verticesDrawn += indexCount;
        instancesDrawn += instanceCount;
        record(Op.DRAW_INDEXED, "idx=" + indexCount + " i=" + instanceCount);
    }

    private boolean checkIndirect(String what, boolean countVariant) {
        if (!checkDraw(what)) return false;
        // The more specific capability first: a counted multi-draw needs both,
        // and naming only INDIRECT_DRAW would hide the one actually missing.
        if (countVariant && !device.hasCapability(GfxCapability.MULTI_DRAW_INDIRECT)) {
            device.violate(what + " but the device lacks MULTI_DRAW_INDIRECT");
            return false;
        }
        if (!device.hasCapability(GfxCapability.INDIRECT_DRAW)) {
            device.violate(what + " but the device lacks INDIRECT_DRAW");
            return false;
        }
        return true;
    }

    @Override public void drawIndirect(GfxBuffer args, long offset, int drawCount, int stride) {
        if (!checkIndirect("drawIndirect", drawCount > 1)) return;
        if (args == null) { device.violate("drawIndirect(null)"); return; }
        if ((args.usage() & dev.auralite.render.rhi.GfxUsage.BUFFER_INDIRECT) == 0) {
            device.violate("indirect args buffer '" + args.debugName() + "' lacks INDIRECT usage");
            return;
        }
        draws += drawCount;
        instancesDrawn += drawCount;
        record(Op.DRAW_INDIRECT, "count=" + drawCount + " stride=" + stride);
    }

    @Override public void drawIndexedIndirect(GfxBuffer args, long offset, int drawCount, int stride) {
        if (!checkIndirect("drawIndexedIndirect", drawCount > 1)) return;
        if (indexBuffer == null) { device.violate("drawIndexedIndirect with no index buffer bound"); return; }
        draws += drawCount;
        record(Op.DRAW_INDEXED_INDIRECT, "count=" + drawCount);
    }

    @Override public void drawIndexedIndirectCount(GfxBuffer args, long argsOffset, GfxBuffer count,
                                                   long countOffset, int maxDrawCount, int stride) {
        if (!checkIndirect("drawIndexedIndirectCount", true)) return;
        if (count == null) { device.violate("drawIndexedIndirectCount(null count buffer)"); return; }
        if (countOffset + 4 > count.sizeBytes()) {
            device.violate("count buffer offset " + countOffset + " overruns " + count.sizeBytes() + "B");
            return;
        }
        draws += maxDrawCount;
        record(Op.DRAW_INDEXED_INDIRECT_COUNT, "max=" + maxDrawCount);
    }

    // --------------------------------------------------------------- compute
    @Override public void bindComputePipeline(GfxPipeline pipeline) {
        if (pipeline == null) { device.violate("bindComputePipeline(null)"); return; }
        if (!pipeline.isCompute()) { device.violate("graphics pipeline bound on the compute path"); return; }
        if (!pipeline.isValid()) { device.violate("binding invalid compute pipeline: " + pipeline.infoLog()); return; }
        if (inPass) { device.violate("bindComputePipeline inside a render pass"); return; }
        computePipeline = pipeline;
        record(Op.BIND_COMPUTE, pipeline.debugName());
    }

    @Override public void dispatch(int groupsX, int groupsY, int groupsZ) {
        if (inPass) { device.violate("dispatch inside a render pass"); return; }
        if (computePipeline == null) { device.violate("dispatch with no compute pipeline bound"); return; }
        if (groupsX <= 0 || groupsY <= 0 || groupsZ <= 0) {
            device.violate("dispatch extent " + groupsX + "x" + groupsY + "x" + groupsZ + " is not positive");
            return;
        }
        DeviceLimitsRef.checkDispatch(device, groupsX, groupsY, groupsZ);
        dispatches++;
        workGroupsDispatched += (long) groupsX * groupsY * groupsZ;
        record(Op.DISPATCH, groupsX + "x" + groupsY + "x" + groupsZ);
    }

    @Override public void dispatchIndirect(GfxBuffer args, long offset) {
        if (inPass) { device.violate("dispatchIndirect inside a render pass"); return; }
        if (computePipeline == null) { device.violate("dispatchIndirect with no compute pipeline bound"); return; }
        if (!device.hasCapability(GfxCapability.INDIRECT_DISPATCH)) {
            device.violate("dispatchIndirect but the device lacks INDIRECT_DISPATCH");
            return;
        }
        if (args == null) { device.violate("dispatchIndirect(null)"); return; }
        if ((args.usage() & dev.auralite.render.rhi.GfxUsage.BUFFER_INDIRECT) == 0) {
            device.violate("indirect args buffer '" + args.debugName() + "' lacks INDIRECT usage");
            return;
        }
        if (offset + 12 > args.sizeBytes()) {
            device.violate("indirect args at offset " + offset + " overrun " + args.sizeBytes() + "B");
            return;
        }
        dispatches++;
        record(Op.DISPATCH_INDIRECT, "+" + offset);
    }

    // ------------------------------------------------------------- resources
    @Override public void bindDescriptorSet(int setIndex, GfxDescriptorSet set) {
        if (set == null) { device.violate("bindDescriptorSet(null)"); return; }
        GfxPipeline active = inPass ? graphicsPipeline : computePipeline;
        if (active == null) {
            device.violate("bindDescriptorSet with no pipeline bound");
            return;
        }
        String problem = set.validateAgainst(active.layout());
        if (problem != null) {
            device.violate("descriptor set " + setIndex + " for pipeline '" + active.debugName()
                    + "': " + problem);
            return;
        }
        if (setIndex < setBound.length) setBound[setIndex] = true;
        record(Op.BIND_SET, "set=" + setIndex + " writes=" + set.size());
    }

    @Override public void pushConstants(int offset, ByteBuffer data) {
        GfxPipeline active = inPass ? graphicsPipeline : computePipeline;
        if (active == null) { device.violate("pushConstants with no pipeline bound"); return; }
        GfxDesc.PipelineLayout layout = active.layout();
        int allowed = layout == null ? 0 : layout.pushConstantBytes;
        if (allowed == 0) { device.violate("pushConstants but the pipeline declares no push constant range"); return; }
        if (offset + data.remaining() > allowed) {
            device.violate("pushConstants " + offset + "+" + data.remaining()
                    + " exceeds the declared range of " + allowed + "B");
            return;
        }
        record(Op.PUSH_CONSTANTS, "+" + offset + " " + data.remaining() + "B");
    }

    @Override public void pushConstants(int offset, float[] values) {
        ByteBuffer bb = ByteBuffer.allocateDirect(values.length * 4)
                .order(java.nio.ByteOrder.nativeOrder());
        for (float f : values) bb.putFloat(f);
        bb.flip();
        pushConstants(offset, bb);
    }

    @Override public void copyBuffer(GfxBuffer src, long srcOffset, GfxBuffer dst, long dstOffset, long sizeBytes) {
        if (src == null || dst == null) { device.violate("copyBuffer with a null endpoint"); return; }
        if (sizeBytes <= 0) { device.violate("copyBuffer of " + sizeBytes + "B"); return; }
        if (srcOffset + sizeBytes > src.sizeBytes()) {
            device.violate("copyBuffer source range overruns '" + src.debugName() + "'");
            return;
        }
        if (dstOffset + sizeBytes > dst.sizeBytes()) {
            device.violate("copyBuffer destination range overruns '" + dst.debugName() + "'");
            return;
        }
        if (inPass) { device.violate("copyBuffer inside a render pass"); return; }
        record(Op.COPY_BUFFER, src.debugName() + " -> " + dst.debugName() + " " + sizeBytes + "B");
    }

    @Override public void copyBufferToImage(GfxBuffer src, GfxImage dst, int mipLevel) {
        if (src == null || dst == null) { device.violate("copyBufferToImage with a null endpoint"); return; }
        if (inPass) { device.violate("copyBufferToImage inside a render pass"); return; }
        if (mipLevel >= dst.mipLevels()) {
            device.violate("copyBufferToImage mip " + mipLevel + " of " + dst.mipLevels());
            return;
        }
        long need = (long) dst.width() * dst.height() * dst.depth() * dst.format().bytesPerTexel();
        if (need > 0 && src.sizeBytes() < need) {
            device.violate("copyBufferToImage needs " + need + "B but the source holds " + src.sizeBytes() + "B");
            return;
        }
        record(Op.COPY_BUFFER_TO_IMAGE, src.debugName() + " -> " + dst.debugName() + " mip" + mipLevel);
    }

    @Override public void copyImage(GfxImage src, int srcMip, GfxImage dst, int dstMip, int w, int h, int d) {
        if (src == null || dst == null) { device.violate("copyImage with a null endpoint"); return; }
        if (inPass) { device.violate("copyImage inside a render pass"); return; }
        if (srcMip >= src.mipLevels() || dstMip >= dst.mipLevels()) {
            device.violate("copyImage mip out of range");
            return;
        }
        if (src.format() != dst.format()) {
            device.violate("copyImage between " + src.format() + " and " + dst.format());
            return;
        }
        record(Op.COPY_IMAGE, src.debugName() + " -> " + dst.debugName() + " " + w + "x" + h + "x" + d);
    }

    @Override public void blitImage(GfxImage src, GfxImage dst, boolean linearFilter) {
        if (src == null || dst == null) { device.violate("blitImage with a null endpoint"); return; }
        if (inPass) { device.violate("blitImage inside a render pass"); return; }
        if (linearFilter && (src.format().isInteger() || dst.format().isInteger())) {
            device.violate("blitImage with linear filtering on an integer format");
            return;
        }
        record(Op.BLIT, src.debugName() + " -> " + dst.debugName() + (linearFilter ? " linear" : " nearest"));
    }

    @Override public void generateMipmaps(GfxImage image) {
        if (image == null) { device.violate("generateMipmaps(null)"); return; }
        if (inPass) { device.violate("generateMipmaps inside a render pass"); return; }
        if (image.mipLevels() < 2) {
            device.violate("generateMipmaps on '" + image.debugName() + "' with " + image.mipLevels() + " level");
            return;
        }
        record(Op.GEN_MIPS, image.debugName() + " levels=" + image.mipLevels());
    }

    @Override public void barrier(GfxImage image, ResourceState from, ResourceState to, int srcAccess, int dstAccess) {
        if (image == null) { device.violate("barrier(null image)"); return; }
        if (from == to && srcAccess == dstAccess) {
            device.note("  cmd barrier suppressed as a no-op on " + image.debugName());
            return;
        }
        if ((dstAccess & Access.COLOR_ATTACHMENT_WRITE) != 0 && !inPass) {
            device.note("  note: colour-attachment access outside a pass on " + image.debugName());
        }
        record(Op.BARRIER_IMAGE, image.debugName() + " " + from + "->" + to);
    }

    @Override public void barrier(GfxBuffer buffer, int srcAccess, int dstAccess) {
        if (buffer == null) { device.violate("barrier(null buffer)"); return; }
        if (srcAccess == dstAccess) {
            device.note("  cmd buffer barrier suppressed as a no-op on " + buffer.debugName());
            return;
        }
        record(Op.BARRIER_BUFFER, buffer.debugName());
    }

    @Override public void memoryBarrierAll() { record(Op.BARRIER_ALL, ""); }

    @Override public void writeTimestamp(int queryIndex) {
        if (!device.hasCapability(GfxCapability.TIMESTAMPS)) {
            device.violate("writeTimestamp but the device lacks TIMESTAMPS");
            return;
        }
        if (queryIndex < 0) { device.violate("writeTimestamp with index " + queryIndex); return; }
        record(Op.TIMESTAMP, "q=" + queryIndex);
    }

    /** Small indirection so the limits check reads cleanly and stays in one place. */
    private static final class DeviceLimitsRef {
        static void checkDispatch(NullDevice device, int gx, int gy, int gz) {
            dev.auralite.render.rhi.DeviceLimits l = device.limits();
            if (gx > l.maxComputeWorkGroupCountX || gy > l.maxComputeWorkGroupCountY
                    || gz > l.maxComputeWorkGroupCountZ) {
                device.violate("dispatch " + gx + "x" + gy + "x" + gz + " exceeds the work-group count limit");
            }
        }
    }

    @Override public String toString() {
        return "RecordingCommandBuffer{" + queue + ", " + commands + " cmds, " + draws + " draws, "
                + dispatches + " dispatches}";
    }
}
