package dev.auralite.render.backend.gl;

import dev.auralite.render.backend.gl.GlDevice.GlBuffer;
import dev.auralite.render.backend.gl.GlDevice.GlImage;
import dev.auralite.render.backend.gl.GlDevice.GlPipeline;
import dev.auralite.render.backend.gl.GlDevice.GlSampler;
import dev.auralite.render.rhi.GfxBuffer;
import dev.auralite.render.rhi.GfxCapability;
import dev.auralite.render.rhi.GfxCommandBuffer;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxDescriptorSet;
import dev.auralite.render.rhi.GfxEnums.PassType;
import dev.auralite.render.rhi.GfxEnums.QueueType;
import dev.auralite.render.rhi.GfxException;
import dev.auralite.render.rhi.GfxImage;
import dev.auralite.render.rhi.GfxPipeline;
import dev.auralite.render.rhi.GfxSampler;
import dev.auralite.render.rhi.GfxUsage;
import dev.auralite.render.util.Checks;
import dev.auralite.render.util.MathUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.GL46C.GL_ALL_BARRIER_BITS;
import static org.lwjgl.opengl.GL46C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL46C.GL_DISPATCH_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL46C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL46C.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL46C.GL_LINEAR;
import static org.lwjgl.opengl.GL46C.GL_NEAREST;
import static org.lwjgl.opengl.GL46C.GL_PARAMETER_BUFFER;
import static org.lwjgl.opengl.GL46C.GL_PATCH_VERTICES;
import static org.lwjgl.opengl.GL46C.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL46C.GL_READ_WRITE;
import static org.lwjgl.opengl.GL46C.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL46C.GL_STENCIL_TEST;
import static org.lwjgl.opengl.GL46C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL46C.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL46C.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL46C.GL_UNSIGNED_SHORT;
import static org.lwjgl.opengl.GL46C.glBindBuffer;
import static org.lwjgl.opengl.GL46C.glBindBufferBase;
import static org.lwjgl.opengl.GL46C.glBindBufferRange;
import static org.lwjgl.opengl.GL46C.glBindFramebuffer;
import static org.lwjgl.opengl.GL46C.glBindImageTexture;
import static org.lwjgl.opengl.GL46C.glBindSampler;
import static org.lwjgl.opengl.GL46C.glBindTextureUnit;
import static org.lwjgl.opengl.GL46C.glBindVertexArray;
import static org.lwjgl.opengl.GL46C.glBlendEquationSeparatei;
import static org.lwjgl.opengl.GL46C.glBlendFuncSeparatei;
import static org.lwjgl.opengl.GL46C.glBlitNamedFramebuffer;
import static org.lwjgl.opengl.GL46C.glColorMaski;
import static org.lwjgl.opengl.GL46C.glCopyImageSubData;
import static org.lwjgl.opengl.GL46C.glCopyNamedBufferSubData;
import static org.lwjgl.opengl.GL46C.glCreateVertexArrays;
import static org.lwjgl.opengl.GL46C.glCullFace;
import static org.lwjgl.opengl.GL46C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL46C.glDepthFunc;
import static org.lwjgl.opengl.GL46C.glDepthMask;
import static org.lwjgl.opengl.GL46C.glDepthRange;
import static org.lwjgl.opengl.GL46C.glDisable;
import static org.lwjgl.opengl.GL46C.glDispatchCompute;
import static org.lwjgl.opengl.GL46C.glDispatchComputeIndirect;
import static org.lwjgl.opengl.GL46C.glDrawArraysIndirect;
import static org.lwjgl.opengl.GL46C.glDrawArraysInstancedBaseInstance;
import static org.lwjgl.opengl.GL46C.glDrawElementsIndirect;
import static org.lwjgl.opengl.GL46C.glDrawElementsInstancedBaseVertexBaseInstance;
import static org.lwjgl.opengl.GL46C.glEnable;
import static org.lwjgl.opengl.GL46C.glFrontFace;
import static org.lwjgl.opengl.GL46C.glGenerateTextureMipmap;
import static org.lwjgl.opengl.GL46C.glLineWidth;
import static org.lwjgl.opengl.GL46C.glMemoryBarrier;
import static org.lwjgl.opengl.GL46C.glMultiDrawArraysIndirect;
import static org.lwjgl.opengl.GL46C.glMultiDrawElementsIndirect;
import static org.lwjgl.opengl.GL46C.glMultiDrawElementsIndirectCount;
import static org.lwjgl.opengl.GL46C.glPatchParameteri;
import static org.lwjgl.opengl.GL46C.glQueryCounter;
import static org.lwjgl.opengl.GL46C.glScissor;
import static org.lwjgl.opengl.GL46C.glTextureBarrier;
import static org.lwjgl.opengl.GL46C.glUseProgram;
import static org.lwjgl.opengl.GL46C.glVertexArrayAttribBinding;
import static org.lwjgl.opengl.GL46C.glVertexArrayAttribFormat;
import static org.lwjgl.opengl.GL46C.glVertexArrayAttribIFormat;
import static org.lwjgl.opengl.GL46C.glVertexArrayBindingDivisor;
import static org.lwjgl.opengl.GL46C.glVertexArrayElementBuffer;
import static org.lwjgl.opengl.GL46C.glVertexArrayVertexBuffer;
import static org.lwjgl.opengl.GL46C.glViewport;
import static org.lwjgl.opengl.GL46C.glEnableVertexArrayAttrib;
import static org.lwjgl.opengl.GL46C.glTextureSubImage2D;
import static org.lwjgl.opengl.GL46C.glTextureSubImage3D;
import static org.lwjgl.opengl.GL46C.glBlendFunci;

/**
 * Immediate-execution command buffer: GL has no command lists, so each call
 * runs as it arrives and the ordering contract is enforced the same way the
 * NULL backend enforces it.
 *
 * <p>RHI viewports and scissors use a top-left origin on every backend; GL's
 * are bottom-left, so this buffer flips them against the current pass height.
 * Descriptor sets are emulated onto GL binding points as
 * {@code setIndex * 16 + binding}; a set with a binding at 16 or above is
 * refused with its number. Push constants are emulated with a device-owned
 * uniform buffer that shaders read as {@code layout(std140) uniform
 * auralite_push} on the last uniform binding point.
 */
public final class GlCommandBuffer implements GfxCommandBuffer {

    /** Bindings per emulated descriptor set. Layouts using more are refused. */
    static final int SET_STRIDE = 16;

    final GlDevice device;
    private final QueueType queue;

    private boolean recording;
    private boolean ended;
    private boolean inPass;
    private int fbo;
    private int passWidth;
    private int passHeight;
    private GfxDesc.RenderPass currentPass;
    private GlPipeline graphicsPipeline;
    private GlPipeline computePipeline;

    private final Map<Integer, BoundVertex> vertexBuffers = new HashMap<Integer, BoundVertex>();
    private GlBuffer indexBuffer;
    private long indexOffset;
    private boolean index32;
    private int vao;
    private boolean vaoDirty = true;
    private int commandCount;

    private static final class BoundVertex {
        final GlBuffer buffer;
        final long offset;
        BoundVertex(GlBuffer buffer, long offset) { this.buffer = buffer; this.offset = offset; }
    }

    GlCommandBuffer(GlDevice device, QueueType queue) {
        this.device = device;
        this.queue = queue;
    }

    @Override public void begin() {
        if (recording || ended) throw new GfxException("gl backend: begin() on a used command buffer");
        recording = true;
        count();
    }

    @Override public void end() {
        if (!recording) throw new GfxException("gl backend: end() while not recording");
        if (inPass) throw new GfxException("gl backend: end() with a render pass still open");
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
        if (inPass) throw new GfxException("gl backend: beginRenderPass while a pass is open");
        Checks.notNull(pass, "pass");
        if (pass.width <= 0 || pass.height <= 0) {
            throw new GfxException("gl backend: render area " + pass.width + "x" + pass.height
                    + " is not positive");
        }
        if (pass.colors.isEmpty() && pass.depth == null) {
            throw new GfxException("gl backend: pass '" + pass.debugName + "' has no attachments at all");
        }
        checkAreaAgainstAttachments(pass);
        fbo = device.framebufferFor(pass);
        passWidth = pass.width;
        passHeight = pass.height;
        currentPass = pass;
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
        device.clearFramebuffer(fbo, pass);
        graphicsPipeline = null;
        inPass = true;
        count();
    }

    private void checkAreaAgainstAttachments(GfxDesc.RenderPass pass) {
        for (int i = 0; i < pass.colors.size(); i++) {
            GfxDesc.Attachment a = pass.colors.get(i);
            GlImage img = device.ownImage(a.image);
            int w = Math.max(1, img.desc.width >> a.mipLevel);
            int h = Math.max(1, img.desc.height >> a.mipLevel);
            if (pass.width > w || pass.height > h) {
                throw new GfxException("gl backend: pass '" + pass.debugName + "' is " + pass.width
                        + "x" + pass.height + " but colour " + i + " is " + w + "x" + h);
            }
        }
        if (pass.depth != null) {
            GlImage img = device.ownImage(pass.depth.image);
            if (pass.width > img.desc.width || pass.height > img.desc.height) {
                throw new GfxException("gl backend: pass '" + pass.debugName + "' is " + pass.width
                        + "x" + pass.height + " but its depth is " + img.desc.width + "x" + img.desc.height);
            }
        }
    }

    @Override public void endRenderPass() {
        checkRecording("endRenderPass");
        if (!inPass) throw new GfxException("gl backend: endRenderPass with no pass open");
        // StoreOp.RESOLVE has no FBO-level meaning on GL: the attachment keeps its
        // multisampled contents and the pack resolves with an explicit blit pass,
        // exactly like the NULL backend's contract describes.
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glBindVertexArray(0);
        fbo = 0;
        currentPass = null;
        graphicsPipeline = null;
        vaoDirty = true;
        inPass = false;
        count();
    }

    @Override public void nextSubpass() {
        checkRecording("nextSubpass");
        if (!inPass) throw new GfxException("gl backend: nextSubpass outside a render pass");
        // GL has no subpasses; the texture barrier is the equivalent ordering point
        // for framebuffer-fetch feedback loops.
        glTextureBarrier();
        count();
    }

    @Override public void setViewport(float x, float y, float w, float h, float minDepth, float maxDepth) {
        checkRecording("setViewport");
        if (w <= 0 || h <= 0) {
            throw new GfxException("gl backend: viewport extent " + w + "x" + h + " is not positive");
        }
        // RHI origin is top-left, GL is bottom-left: flip against the pass height.
        int iy = inPass ? passHeight - (int) y - (int) h : (int) y;
        glViewport((int) x, iy, (int) w, (int) h);
        glDepthRange(minDepth, maxDepth);
        count();
    }

    @Override public void setScissor(int x, int y, int w, int h) {
        checkRecording("setScissor");
        if (w <= 0 || h <= 0) {
            throw new GfxException("gl backend: scissor extent " + w + "x" + h + " is not positive");
        }
        int iy = inPass ? passHeight - y - h : y;
        glScissor(x, iy, w, h);
        count();
    }

    @Override public void bindGraphicsPipeline(GfxPipeline pipeline) {
        checkRecording("bindGraphicsPipeline");
        if (!inPass) throw new GfxException("gl backend: bindGraphicsPipeline outside a render pass");
        GlPipeline p = ownPipeline(pipeline, false);
        glUseProgram(p.program);
        applyRasterState(p);
        graphicsPipeline = p;
        vaoDirty = true;
        count();
    }

    private void applyRasterState(GlPipeline p) {
        // Depth.
        if (p.depthStencil.depthTest) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
        glDepthMask(p.depthStencil.depthWrite);
        glDepthFunc(GlEnumMap.compareOp(p.depthStencil.depthOp));
        // The RHI carries a stencil flag but no stencil ops yet, so enabling the
        // test would apply whatever state the last pass left behind. Refuse the
        // combination instead of inheriting garbage.
        if (p.depthStencil.stencilTest) {
            throw new GfxException("gl backend: pipeline '" + p.debugName()
                    + "' enables stencil, which the RHI cannot configure yet");
        }
        glDisable(GL_STENCIL_TEST);
        // Raster.
        if (GlEnumMap.cullEnabled(p.raster.cullMode)) {
            glEnable(org.lwjgl.opengl.GL46C.GL_CULL_FACE);
            glCullFace(GlEnumMap.cullFace(p.raster.cullMode));
        } else {
            glDisable(org.lwjgl.opengl.GL46C.GL_CULL_FACE);
        }
        glFrontFace(GlEnumMap.frontFace(p.raster.frontFace));
        if (p.raster.topology == dev.auralite.render.rhi.GfxEnums.Topology.PATCH_LIST) {
            // The RHI has no patch-vertex count field; tessellation packs use
            // triangles until it grows one.
            glPatchParameteri(GL_PATCH_VERTICES, 3);
        }
        glLineWidth(p.raster.lineWidth);
        if (p.raster.polygonOffset) {
            glEnable(org.lwjgl.opengl.GL46C.GL_POLYGON_OFFSET_FILL);
        } else {
            glDisable(org.lwjgl.opengl.GL46C.GL_POLYGON_OFFSET_FILL);
        }
        // Blend lives on the pass attachments, per-attachment like GL 4.6 itself.
        // The enable is global on GL, so disabled attachments get ONE/ZERO, which
        // is the same pixel the RHI's disabled blend describes.
        boolean anyBlend = false;
        int targets = currentPass == null ? 0 : currentPass.colors.size();
        for (int i = 0; i < targets; i++) {
            GfxDesc.Blend b = currentPass.colors.get(i).blend;
            if (b != null && b.enabled) {
                anyBlend = true;
                glBlendFuncSeparatei(i,
                        GlEnumMap.blendFactor(b.srcColor), GlEnumMap.blendFactor(b.dstColor),
                        GlEnumMap.blendFactor(b.srcAlpha), GlEnumMap.blendFactor(b.dstAlpha));
                glBlendEquationSeparatei(i,
                        GlEnumMap.blendOp(b.opColor), GlEnumMap.blendOp(b.opAlpha));
            } else {
                glBlendFunci(i, org.lwjgl.opengl.GL46C.GL_ONE, org.lwjgl.opengl.GL46C.GL_ZERO);
            }
            int mask = b == null ? 0xF : b.writeMask;
            glColorMaski(i, (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0);
        }
        if (anyBlend) glEnable(org.lwjgl.opengl.GL46C.GL_BLEND);
        else glDisable(org.lwjgl.opengl.GL46C.GL_BLEND);
    }

    @Override public void bindVertexBuffer(int binding, GfxBuffer buffer, long offset) {
        checkRecording("bindVertexBuffer");
        GlBuffer b = device.ownBuffer(buffer);
        b.requireUsage(GfxUsage.BUFFER_VERTEX, "BUFFER_VERTEX", "a vertex binding");
        if (offset < 0 || offset >= b.desc.sizeBytes) {
            throw new GfxException("gl backend: vertex offset " + offset + " on buffer '"
                    + b.desc.debugName + "' of " + b.desc.sizeBytes + " bytes");
        }
        vertexBuffers.put(binding, new BoundVertex(b, offset));
        vaoDirty = true;
        count();
    }

    @Override public void bindIndexBuffer(GfxBuffer buffer, long offset, boolean index32) {
        checkRecording("bindIndexBuffer");
        GlBuffer b = device.ownBuffer(buffer);
        b.requireUsage(GfxUsage.BUFFER_INDEX, "BUFFER_INDEX", "an index binding");
        if (offset < 0 || offset >= b.desc.sizeBytes) {
            throw new GfxException("gl backend: index offset " + offset + " on buffer '"
                    + b.desc.debugName + "' of " + b.desc.sizeBytes + " bytes");
        }
        indexBuffer = b;
        indexOffset = offset;
        this.index32 = index32;
        vaoDirty = true;
        count();
    }

    private void ensureVertexArray() {
        if (!vaoDirty) {
            glBindVertexArray(vao);
            return;
        }
        if (graphicsPipeline == null) {
            throw new GfxException("gl backend: draw with no graphics pipeline bound");
        }
        if (vao == 0) vao = glCreateVertexArrays();
        for (GfxDesc.VertexLayout.Attribute a : graphicsPipeline.vertexLayout.attributes()) {
            GlEnumMap.AttributeInfo info = GlEnumMap.attribute(a.format);
            if (info == null) {
                throw new GfxException("gl backend: vertex attribute " + a.location + " uses "
                        + a.format + ", which cannot be a vertex attribute");
            }
            if (info.integer) {
                glVertexArrayAttribIFormat(vao, a.location, info.size, info.type, a.offset);
            } else {
                glVertexArrayAttribFormat(vao, a.location, info.size, info.type, info.normalized, a.offset);
            }
            glVertexArrayAttribBinding(vao, a.location, a.binding);
            glEnableVertexArrayAttrib(vao, a.location);
        }
        for (GfxDesc.VertexLayout.Binding b : graphicsPipeline.vertexLayout.bindings) {
            BoundVertex v = vertexBuffers.get(b.binding);
            if (v == null) {
                throw new GfxException("gl backend: vertex binding " + b.binding
                        + " was never bound for pipeline '" + graphicsPipeline.debugName() + "'");
            }
            glVertexArrayVertexBuffer(vao, b.binding, (int) v.buffer.handle(), v.offset, b.stride);
            glVertexArrayBindingDivisor(vao, b.binding, b.perInstance ? 1 : 0);
        }
        if (indexBuffer != null) {
            glVertexArrayElementBuffer(vao, (int) indexBuffer.handle());
        }
        glBindVertexArray(vao);
        vaoDirty = false;
    }

    private int drawMode() {
        return graphicsPipeline == null ? GL_TRIANGLES
                : GlEnumMap.topology(graphicsPipeline.raster.topology);
    }

    @Override public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        checkRecording("draw");
        checkInPass("draw");
        if (graphicsPipeline == null) {
            throw new GfxException("gl backend: draw with no graphics pipeline bound");
        }
        if (vertexCount <= 0) throw new GfxException("gl backend: draw with vertexCount " + vertexCount);
        if (instanceCount <= 0) {
            throw new GfxException("gl backend: draw with instanceCount " + instanceCount);
        }
        ensureVertexArray();
        glDrawArraysInstancedBaseInstance(drawMode(), firstVertex, vertexCount, instanceCount, firstInstance);
        count();
    }

    @Override public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset,
                                      int firstInstance) {
        checkRecording("drawIndexed");
        checkInPass("drawIndexed");
        if (graphicsPipeline == null) {
            throw new GfxException("gl backend: drawIndexed with no graphics pipeline bound");
        }
        if (indexBuffer == null) throw new GfxException("gl backend: drawIndexed with no index buffer bound");
        if (indexCount <= 0) {
            throw new GfxException("gl backend: drawIndexed with indexCount " + indexCount);
        }
        ensureVertexArray();
        int indexSize = index32 ? 4 : 2;
        long offset = indexOffset + (long) firstIndex * indexSize;
        glDrawElementsInstancedBaseVertexBaseInstance(drawMode(), indexCount,
                index32 ? GL_UNSIGNED_INT : GL_UNSIGNED_SHORT, offset,
                instanceCount, vertexOffset, firstInstance);
        count();
    }

    @Override public void drawIndirect(GfxBuffer args, long offset, int drawCount, int stride) {
        checkRecording("drawIndirect");
        checkInPass("drawIndirect");
        GlBuffer a = indirectArgs(args, offset, "drawIndirect");
        if (graphicsPipeline == null) {
            throw new GfxException("gl backend: drawIndirect with no graphics pipeline bound");
        }
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, (int) a.handle());
        if (drawCount <= 1) {
            glDrawArraysIndirect(drawMode(), offset);
        } else {
            glMultiDrawArraysIndirect(drawMode(), offset, drawCount, stride == 0 ? 16 : stride);
        }
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        count();
    }

    @Override public void drawIndexedIndirect(GfxBuffer args, long offset, int drawCount, int stride) {
        checkRecording("drawIndexedIndirect");
        checkInPass("drawIndexedIndirect");
        GlBuffer a = indirectArgs(args, offset, "drawIndexedIndirect");
        if (graphicsPipeline == null) {
            throw new GfxException("gl backend: drawIndexedIndirect with no graphics pipeline bound");
        }
        if (indexBuffer == null) {
            throw new GfxException("gl backend: drawIndexedIndirect with no index buffer bound");
        }
        ensureVertexArray();
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, (int) a.handle());
        if (drawCount <= 1) {
            glDrawElementsIndirect(drawMode(), index32 ? GL_UNSIGNED_INT : GL_UNSIGNED_SHORT, offset);
        } else {
            glMultiDrawElementsIndirect(drawMode(), index32 ? GL_UNSIGNED_INT : GL_UNSIGNED_SHORT,
                    offset, drawCount, stride == 0 ? 20 : stride);
        }
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        count();
    }

    @Override public void drawIndexedIndirectCount(GfxBuffer args, long argsOffset, GfxBuffer count,
                                                   long countOffset, int maxDrawCount, int stride) {
        checkRecording("drawIndexedIndirectCount");
        checkInPass("drawIndexedIndirectCount");
        GlBuffer a = indirectArgs(args, argsOffset, "drawIndexedIndirectCount");
        GlBuffer c = device.ownBuffer(count);
        c.requireUsage(GfxUsage.BUFFER_INDIRECT, "BUFFER_INDIRECT", "a draw-count buffer");
        if (graphicsPipeline == null) {
            throw new GfxException("gl backend: drawIndexedIndirectCount with no graphics pipeline bound");
        }
        if (indexBuffer == null) {
            throw new GfxException("gl backend: drawIndexedIndirectCount with no index buffer bound");
        }
        ensureVertexArray();
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, (int) a.handle());
        glBindBuffer(GL_PARAMETER_BUFFER, (int) c.handle());
        glMultiDrawElementsIndirectCount(drawMode(), index32 ? GL_UNSIGNED_INT : GL_UNSIGNED_SHORT,
                argsOffset, countOffset, maxDrawCount, stride == 0 ? 20 : stride);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        glBindBuffer(GL_PARAMETER_BUFFER, 0);
        count();
    }

    private GlBuffer indirectArgs(GfxBuffer args, long offset, String what) {
        GlBuffer a = device.ownBuffer(args);
        a.requireUsage(GfxUsage.BUFFER_INDIRECT, "BUFFER_INDIRECT", "an indirect " + what);
        if (!device.hasCapability(GfxCapability.INDIRECT_DRAW)) {
            throw new GfxException("gl backend: " + what + " needs INDIRECT_DRAW");
        }
        if (offset < 0) throw new GfxException("gl backend: " + what + " with negative offset");
        return a;
    }

    // ----------------------------------------------------------------- compute

    @Override public void bindComputePipeline(GfxPipeline pipeline) {
        checkRecording("bindComputePipeline");
        if (inPass) throw new GfxException("gl backend: bindComputePipeline inside a render pass");
        GlPipeline p = ownPipeline(pipeline, true);
        glUseProgram(p.program);
        computePipeline = p;
        count();
    }

    @Override public void dispatch(int groupsX, int groupsY, int groupsZ) {
        checkRecording("dispatch");
        if (inPass) throw new GfxException("gl backend: dispatch inside a render pass");
        if (computePipeline == null) {
            throw new GfxException("gl backend: dispatch with no compute pipeline bound");
        }
        if (groupsX <= 0 || groupsY <= 0 || groupsZ <= 0) {
            throw new GfxException("gl backend: dispatch extent " + groupsX + "x" + groupsY + "x" + groupsZ
                    + " is not positive");
        }
        if (groupsX > device.limits().maxComputeWorkGroupCountX
                || groupsY > device.limits().maxComputeWorkGroupCountY
                || groupsZ > device.limits().maxComputeWorkGroupCountZ) {
            throw new GfxException("gl backend: dispatch " + groupsX + "x" + groupsY + "x" + groupsZ
                    + " exceeds this device's work-group counts");
        }
        glDispatchCompute(groupsX, groupsY, groupsZ);
        count();
    }

    @Override public void dispatchIndirect(GfxBuffer args, long offset) {
        checkRecording("dispatchIndirect");
        if (inPass) throw new GfxException("gl backend: dispatchIndirect inside a render pass");
        if (computePipeline == null) {
            throw new GfxException("gl backend: dispatchIndirect with no compute pipeline bound");
        }
        GlBuffer a = device.ownBuffer(args);
        a.requireUsage(GfxUsage.BUFFER_INDIRECT, "BUFFER_INDIRECT", "an indirect dispatch");
        glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, (int) a.handle());
        glDispatchComputeIndirect(offset);
        glBindBuffer(GL_DISPATCH_INDIRECT_BUFFER, 0);
        count();
    }

    // --------------------------------------------------------------- resources

    @Override public void bindDescriptorSet(int setIndex, GfxDescriptorSet set) {
        checkRecording("bindDescriptorSet");
        Checks.notNull(set, "set");
        if (setIndex < 0 || setIndex >= device.limits().maxBoundDescriptorSets) {
            throw new GfxException("gl backend: descriptor set " + setIndex
                    + " is outside [0.." + device.limits().maxBoundDescriptorSets + ")");
        }
        for (GfxDescriptorSet.Write w : set.writes()) {
            if (w.binding < 0 || w.binding >= SET_STRIDE) {
                throw new GfxException("gl backend: set " + setIndex + " binding " + w.binding
                        + " does not fit the GL packing (stride " + SET_STRIDE + ")");
            }
            int unit = setIndex * SET_STRIDE + w.binding;
            applyWrite(unit, w);
        }
        count();
    }

    private void applyWrite(int unit, GfxDescriptorSet.Write w) {
        switch (w.kind) {
            case UNIFORM_BUFFER: {
                GlBuffer b = device.ownBuffer((GfxBuffer) w.resource);
                b.requireUsage(GfxUsage.BUFFER_UNIFORM, "BUFFER_UNIFORM",
                        "descriptor binding " + w.binding);
                long range = w.range <= 0 ? b.desc.sizeBytes - w.offset : w.range;
                glBindBufferRange(GL_UNIFORM_BUFFER, unit, (int) b.handle(), w.offset, range);
                break;
            }
            case STORAGE_BUFFER: {
                GlBuffer b = device.ownBuffer((GfxBuffer) w.resource);
                b.requireUsage(GfxUsage.BUFFER_STORAGE, "BUFFER_STORAGE",
                        "descriptor binding " + w.binding);
                long range = w.range <= 0 ? b.desc.sizeBytes - w.offset : w.range;
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, unit, (int) b.handle(), w.offset, range);
                break;
            }
            case COMBINED_IMAGE_SAMPLER: {
                GlImage img = device.ownImage((GfxImage) w.resource);
                img.requireUsage(GfxUsage.IMAGE_SAMPLED, "IMAGE_SAMPLED",
                        "descriptor binding " + w.binding);
                if (!(w.combinedSampler instanceof GlSampler)) {
                    throw new GfxException("gl backend: descriptor binding " + w.binding
                            + " is a combined sampler with no GL sampler");
                }
                GlSampler smp = (GlSampler) w.combinedSampler;
                glBindTextureUnit(unit, (int) img.handle());
                glBindSampler(unit, (int) smp.handle());
                break;
            }
            case SAMPLED_IMAGE:
            case INPUT_ATTACHMENT: {
                // An input attachment outside a subpass model is just a texture read.
                GlImage img = device.ownImage((GfxImage) w.resource);
                img.requireUsage(GfxUsage.IMAGE_SAMPLED, "IMAGE_SAMPLED",
                        "descriptor binding " + w.binding);
                glBindTextureUnit(unit, (int) img.handle());
                break;
            }
            case SAMPLER: {
                if (!(w.resource instanceof GlSampler)) {
                    throw new GfxException("gl backend: descriptor binding " + w.binding
                            + " holds a foreign sampler");
                }
                GfxSampler smp = (GfxSampler) w.resource;
                glBindSampler(unit, (int) smp.handle());
                break;
            }
            case STORAGE_IMAGE: {
                GlImage img = device.ownImage((GfxImage) w.resource);
                img.requireUsage(GfxUsage.IMAGE_STORAGE, "IMAGE_STORAGE",
                        "descriptor binding " + w.binding);
                boolean layered = img.desc.arrayLayers > 1
                        || img.desc.type == dev.auralite.render.rhi.GfxEnums.ImageType.TEXTURE_3D
                        || img.desc.type == dev.auralite.render.rhi.GfxEnums.ImageType.CUBE;
                glBindImageTexture(unit, (int) img.handle(), 0, layered, 0, GL_READ_WRITE, 0);
                break;
            }
            default:
                throw new GfxException("gl backend: descriptor binding " + w.binding + " of kind "
                        + w.kind + " cannot be expressed on GL");
        }
    }

    @Override public void pushConstants(int offset, ByteBuffer data) {
        checkRecording("pushConstants");
        Checks.notNull(data, "data");
        if (offset < 0 || offset + data.remaining() > device.limits().maxPushConstantSize) {
            throw new GfxException("gl backend: push range [" + offset + ".."
                    + (offset + data.remaining()) + ") exceeds the 128 emulated bytes");
        }
        org.lwjgl.opengl.GL46C.glNamedBufferSubData(device.pushUbo(), offset, data);
        glBindBufferBase(GL_UNIFORM_BUFFER, device.pushBinding(), device.pushUbo());
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
        GlBuffer s = device.ownBuffer(src);
        GlBuffer d = device.ownBuffer(dst);
        s.requireUsage(GfxUsage.BUFFER_TRANSFER_SRC, "BUFFER_TRANSFER_SRC", "a copy");
        d.requireUsage(GfxUsage.BUFFER_TRANSFER_DST, "BUFFER_TRANSFER_DST", "a copy");
        s.checkRange(srcOffset, sizeBytes, "copy source");
        d.checkRange(dstOffset, sizeBytes, "copy destination");
        glCopyNamedBufferSubData((int) s.handle(), (int) d.handle(), srcOffset, dstOffset, sizeBytes);
        count();
    }

    @Override public void copyBufferToImage(GfxBuffer src, GfxImage dst, int mipLevel) {
        checkRecording("copyBufferToImage");
        GlBuffer s = device.ownBuffer(src);
        GlImage d = device.ownImage(dst);
        s.requireUsage(GfxUsage.BUFFER_TRANSFER_SRC, "BUFFER_TRANSFER_SRC", "an image upload");
        d.requireUsage(GfxUsage.IMAGE_TRANSFER_DST, "IMAGE_TRANSFER_DST", "an image upload");
        d.checkLevel(mipLevel);
        int w = MathUtil.mipExtent(d.desc.width, mipLevel);
        int h = MathUtil.mipExtent(d.desc.height, mipLevel);
        glBindBuffer(GL_PIXEL_UNPACK_BUFFER, (int) s.handle());
        try {
            switch (d.desc.type) {
                case TEXTURE_2D:
                case CUBE:
                    glTextureSubImage2D((int) d.handle(), mipLevel, 0, 0, w, h,
                            d.mapping.format, d.mapping.type, 0L);
                    break;
                case TEXTURE_3D: {
                    int dep = MathUtil.mipExtent(d.desc.depth, mipLevel);
                    glTextureSubImage3D((int) d.handle(), mipLevel, 0, 0, 0, w, h, dep,
                            d.mapping.format, d.mapping.type, 0L);
                    break;
                }
                default:
                    throw new GfxException("gl backend: cannot stage into image type " + d.desc.type);
            }
        } finally {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        }
        count();
    }

    @Override public void copyImage(GfxImage src, int srcMip, GfxImage dst, int dstMip,
                                    int width, int height, int depth) {
        checkRecording("copyImage");
        GlImage s = device.ownImage(src);
        GlImage d = device.ownImage(dst);
        s.requireUsage(GfxUsage.IMAGE_TRANSFER_SRC, "IMAGE_TRANSFER_SRC", "an image copy");
        d.requireUsage(GfxUsage.IMAGE_TRANSFER_DST, "IMAGE_TRANSFER_DST", "an image copy");
        s.checkLevel(srcMip);
        d.checkLevel(dstMip);
        glCopyImageSubData((int) s.handle(), s.target, srcMip, 0, 0, 0,
                (int) d.handle(), d.target, dstMip, 0, 0, 0, width, height, depth);
        count();
    }

    @Override public void blitImage(GfxImage src, GfxImage dst, boolean linearFilter) {
        checkRecording("blitImage");
        GlImage s = device.ownImage(src);
        GlImage d = device.ownImage(dst);
        s.requireUsage(GfxUsage.IMAGE_TRANSFER_SRC, "IMAGE_TRANSFER_SRC", "a blit");
        d.requireUsage(GfxUsage.IMAGE_TRANSFER_DST, "IMAGE_TRANSFER_DST", "a blit");
        if (linearFilter && (s.desc.format.isDepth() || d.desc.format.isDepth())) {
            throw new GfxException("gl backend: linear blit on a depth image is illegal on every API");
        }
        // Scratch FBOs, because a blit needs both ends attached at once.
        boolean depth = s.desc.format.isDepth() || d.desc.format.isDepth();
        int point = depth ? org.lwjgl.opengl.GL46C.GL_DEPTH_ATTACHMENT
                : org.lwjgl.opengl.GL46C.GL_COLOR_ATTACHMENT0;
        int mask = depth ? org.lwjgl.opengl.GL46C.GL_DEPTH_BUFFER_BIT
                : org.lwjgl.opengl.GL46C.GL_COLOR_BUFFER_BIT;
        int readFbo = org.lwjgl.opengl.GL46C.glCreateFramebuffers();
        int drawFbo = org.lwjgl.opengl.GL46C.glCreateFramebuffers();
        try {
            org.lwjgl.opengl.GL46C.glNamedFramebufferTexture(readFbo, point, (int) s.handle(), 0);
            org.lwjgl.opengl.GL46C.glNamedFramebufferTexture(drawFbo, point, (int) d.handle(), 0);
            glBlitNamedFramebuffer(readFbo, drawFbo, 0, 0, s.desc.width, s.desc.height,
                    0, 0, d.desc.width, d.desc.height, mask,
                    linearFilter ? GL_LINEAR : GL_NEAREST);
        } finally {
            org.lwjgl.opengl.GL46C.glDeleteFramebuffers(readFbo);
            org.lwjgl.opengl.GL46C.glDeleteFramebuffers(drawFbo);
        }
        count();
    }

    @Override public void generateMipmaps(GfxImage image) {
        checkRecording("generateMipmaps");
        GlImage img = device.ownImage(image);
        if (img.desc.mipLevels <= 1) {
            throw new GfxException("gl backend: generateMipmaps on single-level image '"
                    + img.desc.debugName + "'");
        }
        glGenerateTextureMipmap((int) img.handle());
        count();
    }

    @Override public void barrier(GfxImage image, dev.auralite.render.rhi.GfxEnums.ResourceState from,
                                  dev.auralite.render.rhi.GfxEnums.ResourceState to,
                                  int srcAccess, int dstAccess) {
        checkRecording("barrier");
        device.ownImage(image);
        // GL tracks image layouts in the driver; the barrier that matters is the
        // memory one, and the coarse one is always correct.
        if (inPass) glTextureBarrier(); else glMemoryBarrier(GL_ALL_BARRIER_BITS);
        count();
    }

    @Override public void barrier(GfxBuffer buffer, int srcAccess, int dstAccess) {
        checkRecording("barrier");
        device.ownBuffer(buffer);
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        count();
    }

    @Override public void memoryBarrierAll() {
        checkRecording("memoryBarrierAll");
        glMemoryBarrier(GL_ALL_BARRIER_BITS);
        count();
    }

    @Override public void writeTimestamp(int queryIndex) {
        checkRecording("writeTimestamp");
        if (queryIndex < 0) throw new GfxException("gl backend: negative timestamp index");
        // The RHI's timestamps are capture-oriented: RenderDoc reads them, the CPU
        // never does, so a 64-deep ring is all the storage this needs.
        glQueryCounter(device.nextTimestampQuery(), org.lwjgl.opengl.GL46C.GL_TIMESTAMP);
        count();
    }

    @Override public int commandCount() { return commandCount; }

    // ------------------------------------------------------------------ misc

    private void checkRecording(String what) {
        if (!recording) throw new GfxException("gl backend: " + what + " recorded outside begin()/end()");
    }

    private void checkInPass(String what) {
        if (!inPass) throw new GfxException("gl backend: " + what + " outside a render pass");
    }

    private GlPipeline ownPipeline(GfxPipeline pipeline, boolean compute) {
        if (!(pipeline instanceof GlPipeline) || ((GlPipeline) pipeline).device != device) {
            throw new GfxException("gl backend: a foreign pipeline was bound here");
        }
        GlPipeline p = (GlPipeline) pipeline;
        if (p.isCompute() != compute) {
            throw new GfxException("gl backend: pipeline '" + p.debugName() + "' is "
                    + (p.isCompute() ? "compute" : "graphics") + ", bound as "
                    + (compute ? "compute" : "graphics"));
        }
        return p;
    }

    private void count() { commandCount++; }

    void dispose() {
        if (vao != 0) {
            glDeleteVertexArrays(vao);
            vao = 0;
        }
    }
}
