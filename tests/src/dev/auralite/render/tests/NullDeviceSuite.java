package dev.auralite.render.tests;

import dev.auralite.render.backend.nulldev.NullDevice;
import dev.auralite.render.backend.nulldev.RecordingCommandBuffer;
import dev.auralite.render.rhi.DeviceLimits;
import dev.auralite.render.rhi.GfxBuffer;
import dev.auralite.render.rhi.GfxCapability;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxDescriptorSet;
import dev.auralite.render.rhi.GfxEnums.BackendType;
import dev.auralite.render.rhi.GfxEnums.MemoryDomain;
import dev.auralite.render.rhi.GfxEnums.QueueType;
import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxImage;
import dev.auralite.render.rhi.GfxPipeline;
import dev.auralite.render.rhi.GfxSampler;
import dev.auralite.render.rhi.GfxShaderModule;
import dev.auralite.render.rhi.GfxStage;
import dev.auralite.render.rhi.GfxUsage;
import dev.auralite.render.rhi.ShaderSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumSet;
import java.util.Set;

import static dev.auralite.render.tests.TestRig.check;
import static dev.auralite.render.tests.TestRig.contains;
import static dev.auralite.render.tests.TestRig.eq;
import static dev.auralite.render.tests.TestRig.receipt;
import static dev.auralite.render.tests.TestRig.section;
import static dev.auralite.render.tests.TestRig.suite;

/**
 * R-lane gate: the recording backend's contract.
 *
 * <p>Every violation asserted below is something a real driver reports too late
 * (a black screen, a device loss) or not at all (silently wrong pixels). The
 * NULL device turns each one into a named string at the moment it happens,
 * which is what lets the rest of this suite assert on pipeline behaviour with
 * no GPU anywhere in the build.
 */
public final class NullDeviceSuite {
    private NullDeviceSuite() { }

    /** A device that collects violations instead of raising, for negative cases. */
    private static NullDevice lenient() {
        return new NullDevice(NullDevice.conservativeLimits(), NullDevice.fullCapabilities(), false);
    }

    private static GfxShaderModule vert(NullDevice d) {
        return d.createShaderModule(ShaderSource.glsl(GfxStage.VERTEX,
                "#version 460 core\nvoid main() { gl_Position = vec4(0.0); }\n", "test.vert"));
    }

    private static GfxShaderModule frag(NullDevice d) {
        return d.createShaderModule(ShaderSource.glsl(GfxStage.FRAGMENT,
                "#version 460 core\nout vec4 c;\nvoid main() { c = vec4(1.0); }\n", "test.frag"));
    }

    private static GfxShaderModule comp(NullDevice d, int x, int y) {
        return d.createShaderModule(ShaderSource.glsl(GfxStage.COMPUTE,
                "#version 460 core\nlayout(local_size_x=" + x + ", local_size_y=" + y + ") in;\n"
              + "void main() { }\n", "test.comp"));
    }

    private static GfxImage target(NullDevice d, int w, int h) {
        return d.createImage(GfxDesc.Image.tex2d(w, h, GfxFormat.RGBA16F,
                GfxUsage.IMAGE_COLOR_ATTACHMENT | GfxUsage.IMAGE_SAMPLED).named("t"));
    }

    private static GfxPipeline fullScreenPipe(NullDevice d, GfxImage img, String name) {
        return d.createGraphicsPipeline(new GfxDesc.GraphicsPipeline()
                .stage(vert(d)).stage(frag(d))
                .renderPass(GfxDesc.RenderPass.of(img.width(), img.height())
                        .color(GfxDesc.Attachment.of(img)))
                .named(name));
    }

    /** The receipt of a device that ran {@code frames} empty frames. */
    private static String idleDeviceReceipt(int frames) {
        NullDevice d = new NullDevice();
        for (int i = 0; i < frames; i++) { d.beginFrame(); d.endFrame(); }
        String r = d.receipt();
        d.close();
        return r;
    }

    private interface DeviceBody {
        void run(NullDevice d);
    }

    /** The single violation a body produces, or "" if it produced none. */
    private static String oneViolation(DeviceBody body) {
        NullDevice d = lenient();
        body.run(d);
        String v = d.violations().isEmpty() ? "" : d.violations().get(d.violations().size() - 1);
        d.close();
        return v;
    }

    /** The violation a lenient device recorded most recently. */
    private static String last(NullDevice d) {
        return d.violations().isEmpty() ? "" : d.violations().get(d.violations().size() - 1);
    }

    public static void run() {
        suite("nulldev");

        section("device identity");
        NullDevice d = new NullDevice();
        eq(d.backendType(), BackendType.NULL, "backend type is NULL");
        eq(d.capabilities().size(), GfxCapability.values().length, "the full profile has every capability");
        check(d.hasCapability(GfxCapability.COMPUTE_SHADERS), "COMPUTE_SHADERS is present");
        check(d.hasCapability(GfxCapability.MESH_SHADERS), "MESH_SHADERS is present");
        eq(d.limits().maxColorAttachments, 8, "the conservative profile allows 8 colour attachments");
        receipt("device: " + d.backendName() + " | " + d.capabilities().size()
                + " capabilities | " + d.limits());

        section("image creation contract");
        contains(oneViolation(new DeviceBody() { @Override public void run(NullDevice dv) {
            dv.createImage(GfxDesc.Image.tex2d(0, 64, GfxFormat.RGBA8_UNORM, 0).named("zero"));
        }}), "extent 0x64", "a zero-width image is refused and the extent is named");
        contains(oneViolation(new DeviceBody() { @Override public void run(NullDevice dv) {
            dv.createImage(GfxDesc.Image.tex2d(64, 64, GfxFormat.RGBA8_UNORM, 0)
                    .samples(4).mips(3).named("msaa-mips"));
        }}), "both multisampled and mipmapped", "MSAA with mipmaps is refused");
        DeviceLimits small = NullDevice.conservativeLimits();
        small.maxTexture3DSize = 128;
        NullDevice tiny = new NullDevice(small, NullDevice.fullCapabilities(), false);
        GfxImage vol = tiny.createImage(GfxDesc.Image.tex3d(256, 64, 64, GfxFormat.R32_UINT, 0).named("vol"));
        contains(last(tiny), "exceeds maxTexture3DSize 128", "a 3D image over the limit names the limit");
        eq(tiny.liveImageCount(), 0,
                "a refused image is dead on arrival and does not occupy a live slot");
        check(vol != null, "the handle is still returned, so the caller gets the named violation here");
        eq(tiny.totalCreatedHandles(), 1, "and the handle was really handed out, not silently swallowed");
        tiny.close();

        GfxImage big = d.createImage(GfxDesc.Image.tex2d(4096, 4096, GfxFormat.D32_SFLOAT,
                GfxUsage.IMAGE_DEPTH_STENCIL_ATTACHMENT).named("shadow"));
        eq(big.width(), 4096, "a 4096x4096 shadow map is within the conservative limits");
        eq(big.height(), 4096, "and both extents come back off the handle");
        eq(GfxFormat.D32_SFLOAT.bytesPerTexel() * 4096L * 4096L, 67108864L,
                "a 4096^2 D32 shadow map costs 64 MiB, which is why it is one image and not four");

        section("shader module validation");
        GfxShaderModule noMain = d.createShaderModule(ShaderSource.glsl(GfxStage.FRAGMENT,
                "#version 460 core\nout vec4 c;\n", "broken.frag"));
        check(!noMain.isValid(), "GLSL with no entry point does not become a valid module");
        contains(noMain.infoLog(), "no 'void main(' entry point", "the info log names what is missing");
        GfxShaderModule ok = frag(d);
        check(ok.isValid(), "well-formed GLSL compiles");
        eq(ok.infoLog(), "", "a successful compile leaves an empty info log");
        eq(ok.stage(), GfxStage.FRAGMENT, "the module remembers its stage");

        byte[] badSpirv = new byte[64];
        ByteBuffer.wrap(badSpirv).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0xDEADBEEF);
        GfxShaderModule badMod = d.createShaderModule(
                ShaderSource.spirv(GfxStage.COMPUTE, badSpirv, "main", "bad.comp.spv"));
        check(!badMod.isValid(), "SPIR-V with the wrong magic is refused");
        contains(badMod.infoLog(), "0xdeadbeef", "the info log shows the magic it found");
        contains(badMod.infoLog(), "0x07230203", "and the magic it expected");
        byte[] goodSpirv = new byte[64];
        ByteBuffer.wrap(goodSpirv).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 0x07230203);
        check(d.createShaderModule(ShaderSource.spirv(GfxStage.COMPUTE, goodSpirv, "main", "ok.spv"))
                .isValid(), "SPIR-V with the right magic passes structural validation");
        check(!d.createShaderModule(ShaderSource.spirv(GfxStage.COMPUTE, new byte[8], "main", "tiny.spv"))
                .isValid(), "a SPIR-V blob too short to hold a header is refused");

        section("pipeline validation");
        GfxPipeline noFrag = d.createGraphicsPipeline(new GfxDesc.GraphicsPipeline()
                .stage(vert(d)).named("no-fragment"));
        check(!noFrag.isValid(), "a graphics pipeline without a fragment stage is invalid");
        contains(noFrag.infoLog(), "no fragment stage", "and says so");
        GfxShaderModule comp8 = comp(d, 8, 8);
        GfxPipeline compInGfx = d.createGraphicsPipeline(new GfxDesc.GraphicsPipeline()
                .stage(vert(d)).stage(frag(d)).stage(comp8).named("mixed"));
        check(!compInGfx.isValid(), "a compute stage inside a graphics pipeline is refused");
        GfxPipeline badComp = d.createComputePipeline(GfxDesc.ComputePipeline.of(frag(d)).named("wrong-stage"));
        check(!badComp.isValid(), "a fragment stage cannot make a compute pipeline");
        contains(badComp.infoLog(), "not COMPUTE", "and names the stage it actually got");
        GfxPipeline hugeLocal = d.createComputePipeline(GfxDesc.ComputePipeline.of(comp8)
                .localSize(64, 64, 1).named("huge-local"));
        check(!hugeLocal.isValid(), "a local size over the invocation limit is refused");
        contains(hugeLocal.infoLog(), "4096 invocations", "the refusal states the invocation count");
        contains(hugeLocal.infoLog(), "maxComputeWorkGroupInvocations", "and the limit it broke");
        GfxPipeline goodComp = d.createComputePipeline(GfxDesc.ComputePipeline.of(comp8)
                .localSize(8, 8, 1).named("good-comp"));
        check(goodComp.isValid(), "an 8x8x1 compute pipeline is accepted");

        section("capability gating is honest");
        Set<GfxCapability> noMesh = EnumSet.allOf(GfxCapability.class);
        noMesh.remove(GfxCapability.MESH_SHADERS);
        NullDevice capped = new NullDevice(NullDevice.conservativeLimits(), noMesh, false);
        GfxShaderModule mesh = capped.createShaderModule(ShaderSource.glsl(GfxStage.MESH,
                "#version 460 core\nvoid main() { }\n", "m.mesh"));
        GfxPipeline meshPipe = capped.createGraphicsPipeline(new GfxDesc.GraphicsPipeline()
                .stage(mesh).stage(frag(capped)).named("mesh-pipe"));
        check(!meshPipe.isValid(), "a mesh pipeline on a device without MESH_SHADERS is refused");
        contains(meshPipe.infoLog(), "MESH_SHADERS", "the refusal names the missing capability");
        eq(meshPipe.infoLog().contains("no fragment stage"), false,
                "the capability is reported, not a spurious missing-stage error");
        capped.close();

        section("push constant range");
        GfxDesc.PipelineLayout bigPush = new GfxDesc.PipelineLayout().pushConstants(512, GfxStage.FRAGMENT);
        GfxPipeline overPush = d.createGraphicsPipeline(new GfxDesc.GraphicsPipeline()
                .stage(vert(d)).stage(frag(d)).layout(bigPush).named("over-push"));
        check(!overPush.isValid(), "push constants over the device limit are refused at creation time");
        contains(overPush.infoLog(), "limit is 128", "the refusal states the limit");

        section("command stream contract");
        NullDevice len = lenient();
        GfxImage img = target(len, 64, 64);
        GfxPipeline pipe = fullScreenPipe(len, img, "pipe");
        RecordingCommandBuffer cb = (RecordingCommandBuffer) len.createCommandBuffer(QueueType.GRAPHICS);

        cb.draw(3, 1, 0, 0);
        contains(last(len), "outside begin()/end()", "a command before begin() is refused first");
        cb.begin();
        cb.draw(3, 1, 0, 0);
        contains(last(len), "outside a render pass", "a draw outside a render pass is refused");
        cb.bindGraphicsPipeline(pipe);
        contains(last(len), "outside a render pass",
                "binding a graphics pipeline outside a render pass is refused");
        cb.endRenderPass();
        contains(last(len), "no pass open", "ending a pass that was never begun is refused");
        cb.dispatch(1, 1, 1);
        contains(last(len), "no compute pipeline bound",
                "a dispatch with nothing bound is refused rather than silently doing nothing");
        cb.bindComputePipeline(goodComp);
        cb.dispatch(2, 2, 1);
        eq(cb.dispatches(), 1, "binding a compute pipeline outside a render pass is the legal compute path");
        eq(cb.workGroupsDispatched(), 4L, "and its 2x2x1 groups are counted");

        cb.beginRenderPass(GfxDesc.RenderPass.of(128, 128).color(GfxDesc.Attachment.of(img)).named("too-big"));
        contains(last(len), "is 128x128 but colour 0 is 64x64",
                "a render area larger than its attachment is refused and both sizes are named");
        GfxImage depthImg = len.createImage(GfxDesc.Image.tex2d(64, 64, GfxFormat.D32_SFLOAT,
                GfxUsage.IMAGE_DEPTH_STENCIL_ATTACHMENT).named("d"));
        cb.beginRenderPass(GfxDesc.RenderPass.of(64, 64)
                .color(GfxDesc.Attachment.of(depthImg)).named("depth-as-color"));
        contains(last(len), "not a colour format", "a depth format as a colour attachment is refused");
        cb.beginRenderPass(GfxDesc.RenderPass.of(64, 64).named("no-attachments"));
        contains(last(len), "no attachments at all", "an empty render pass is refused");

        cb.beginRenderPass(GfxDesc.RenderPass.of(64, 64).color(GfxDesc.Attachment.of(img)).named("ok"));
        cb.beginRenderPass(GfxDesc.RenderPass.of(64, 64).color(GfxDesc.Attachment.of(img)).named("nested"));
        contains(last(len), "while 'ok' is open", "nesting render passes is refused and names the open pass");
        cb.bindComputePipeline(goodComp);
        contains(last(len), "inside a render pass",
                "a compute pipeline cannot be bound while a raster pass is open");
        cb.setViewport(0, 0, -1, 64, 0, 1);
        contains(last(len), "not positive", "a negative viewport extent is refused");
        cb.draw(3, 1, 0, 0);
        contains(last(len), "no graphics pipeline bound",
                "a draw with no pipeline is refused before its arguments are even looked at");
        eq(cb.draws(), 0, "and nothing was recorded");
        cb.bindGraphicsPipeline(pipe);
        cb.draw(0, 1, 0, 0);
        contains(last(len), "vertexCount 0", "a zero-vertex draw is refused");
        cb.drawIndexed(6, 1, 0, 0, 0);
        contains(last(len), "no index buffer bound", "an indexed draw without an index buffer is refused");
        cb.draw(3, 1, 0, 0);
        eq(cb.draws(), 1, "one legal draw was recorded out of all those attempts");
        cb.endRenderPass();

        cb.bindComputePipeline(goodComp);
        cb.dispatch(0, 1, 1);
        contains(last(len), "not positive", "a zero-extent dispatch is refused");
        cb.dispatch(8, 8, 1);
        eq(cb.dispatches(), 2, "both legal dispatches were recorded");
        eq(cb.workGroupsDispatched(), 68L, "4 + 64 work groups in total");
        cb.beginRenderPass(GfxDesc.RenderPass.of(64, 64).color(GfxDesc.Attachment.of(img)).named("p2"));
        cb.dispatch(1, 1, 1);
        contains(last(len), "dispatch inside a render pass", "dispatching inside a render pass is refused");
        cb.endRenderPass();

        cb.beginRenderPass(GfxDesc.RenderPass.of(64, 64).color(GfxDesc.Attachment.of(img)).named("p3"));
        cb.end();
        contains(last(len), "render pass still open", "ending the buffer with a pass open is refused");
        cb.endRenderPass();
        cb.end();
        cb.begin();
        contains(last(len), "one-shot", "re-beginning a finished command buffer is refused");
        len.submit(QueueType.GRAPHICS, cb);
        eq(len.submissionCount(), 1, "the finished command buffer submits");
        // The op histogram IS the evidence a recording backend exists to
        // produce, so pin it exactly rather than asserting a vague lower bound.
        // 14 legal commands survived ~20 refused ones, including the
        // close-the-pass-and-end-cleanly recovery after the refused end().
        eq(cb.commandCount(), 14, "14 legal commands were recorded");
        eq(cb.count(RecordingCommandBuffer.Op.BEGIN), 1, "one begin()");
        eq(cb.count(RecordingCommandBuffer.Op.END), 1, "one end()");
        eq(cb.count(RecordingCommandBuffer.Op.BEGIN_PASS), 3, "three render passes were opened");
        eq(cb.count(RecordingCommandBuffer.Op.END_PASS), 3, "and all three were closed");
        eq(cb.count(RecordingCommandBuffer.Op.BIND_GFX), 1, "one graphics pipeline bind");
        eq(cb.count(RecordingCommandBuffer.Op.BIND_COMPUTE), 2, "two compute pipeline binds");
        eq(cb.count(RecordingCommandBuffer.Op.DRAW), 1, "one draw survived six refused ones");
        eq(cb.count(RecordingCommandBuffer.Op.DISPATCH), 2, "two dispatches survived two refused ones");
        eq(cb.count(RecordingCommandBuffer.Op.DRAW_INDEXED), 0, "no indexed draw was legal");
        receipt("command stream: " + cb.commandCount() + " commands recorded, "
                + cb.draws() + " draws, " + cb.dispatches() + " dispatches, "
                + len.violations().size() + " violations refused");

        section("descriptor sets are validated against the layout");
        GfxDesc.PipelineLayout layout = new GfxDesc.PipelineLayout()
                .set(new GfxDesc.PipelineLayout.SetLayout()
                        .uniformBuffer(0, GfxStage.FRAGMENT)
                        .sampler(1, GfxStage.FRAGMENT))
                .pushConstants(32, GfxStage.FRAGMENT);
        GfxPipeline laid = len.createGraphicsPipeline(new GfxDesc.GraphicsPipeline()
                .stage(vert(len)).stage(frag(len)).layout(layout).named("laid"));
        check(laid.isValid(), "the laid-out pipeline is valid");
        GfxBuffer ubo = len.createBuffer(GfxDesc.Buffer.of(256, GfxUsage.BUFFER_UNIFORM).named("ubo"));
        GfxSampler smp = len.createSampler(GfxDesc.Sampler.linearClamp().named("smp"));
        RecordingCommandBuffer cb2 = (RecordingCommandBuffer) len.createCommandBuffer(QueueType.GRAPHICS);
        cb2.begin();
        cb2.beginRenderPass(GfxDesc.RenderPass.of(64, 64).color(GfxDesc.Attachment.of(img)).named("p4"));
        cb2.bindGraphicsPipeline(laid);

        int before = len.violations().size();
        cb2.bindDescriptorSet(0, new GfxDescriptorSet(0).add(GfxDescriptorSet.Write.uniform(7, ubo)));
        contains(last(len), "binding 7 not declared", "an undeclared binding is refused");
        cb2.bindDescriptorSet(0, new GfxDescriptorSet(0).add(GfxDescriptorSet.Write.storage(0, ubo)));
        contains(last(len), "declared as UNIFORM_BUFFER", "a kind mismatch is refused and names both kinds");
        cb2.bindDescriptorSet(3, new GfxDescriptorSet(3));
        contains(last(len), "set 3 not declared", "an undeclared set index is refused");
        cb2.bindDescriptorSet(0, new GfxDescriptorSet(0).add(GfxDescriptorSet.Write.sampler(1, img, null)));
        contains(last(len), "no sampler was supplied", "a combined image sampler without a sampler is refused");
        eq(len.violations().size() - before, 4, "exactly the four malformed sets were refused");

        cb2.bindDescriptorSet(0, new GfxDescriptorSet(0)
                .add(GfxDescriptorSet.Write.uniform(0, ubo))
                .add(GfxDescriptorSet.Write.sampler(1, img, smp)));
        eq(len.violations().size() - before, 4, "a correctly declared set adds no violation");
        eq(cb2.count(RecordingCommandBuffer.Op.BIND_SET), 1, "and is recorded once");

        cb2.pushConstants(0, new float[] { 1, 2, 3, 4, 5, 6, 7, 8, 9 });
        contains(last(len), "exceeds the declared range of 32B",
                "pushing past the declared range is refused and states the range");
        cb2.pushConstants(0, new float[] { 1, 2, 3, 4 });
        eq(cb2.count(RecordingCommandBuffer.Op.PUSH_CONSTANTS), 1, "a legal push is recorded");
        cb2.endRenderPass();
        cb2.end();

        section("indirect draws need the capability");
        Set<GfxCapability> noIndirect = EnumSet.allOf(GfxCapability.class);
        noIndirect.remove(GfxCapability.INDIRECT_DRAW);
        noIndirect.remove(GfxCapability.MULTI_DRAW_INDIRECT);
        NullDevice ni = new NullDevice(NullDevice.conservativeLimits(), noIndirect, false);
        GfxImage niImg = target(ni, 64, 64);
        GfxPipeline niPipe = fullScreenPipe(ni, niImg, "p");
        GfxBuffer args = ni.createBuffer(GfxDesc.Buffer.of(64, GfxUsage.BUFFER_INDIRECT).named("args"));
        RecordingCommandBuffer cb3 = (RecordingCommandBuffer) ni.createCommandBuffer(QueueType.GRAPHICS);
        cb3.begin();
        cb3.beginRenderPass(GfxDesc.RenderPass.of(64, 64).color(GfxDesc.Attachment.of(niImg)).named("p5"));
        cb3.bindGraphicsPipeline(niPipe);
        cb3.drawIndirect(args, 0, 1, 16);
        contains(last(ni), "lacks INDIRECT_DRAW", "an indirect draw without the capability names it");
        cb3.drawIndexedIndirectCount(args, 0, args, 0, 4, 16);
        contains(last(ni), "lacks MULTI_DRAW_INDIRECT",
                "a counted indirect draw names the more specific capability it needs");
        eq(cb3.draws(), 0, "neither indirect draw was recorded");
        cb3.endRenderPass();
        cb3.end();
        ni.close();

        section("usage flags are enforced");
        NullDevice u = lenient();
        GfxImage uImg = target(u, 64, 64);
        GfxPipeline uPipe = fullScreenPipe(u, uImg, "upipe");
        GfxBuffer notVertex = u.createBuffer(GfxDesc.Buffer.of(64, GfxUsage.BUFFER_UNIFORM).named("ubo-only"));
        RecordingCommandBuffer cb4 = (RecordingCommandBuffer) u.createCommandBuffer(QueueType.GRAPHICS);
        cb4.begin();
        cb4.bindVertexBuffer(0, notVertex, 0);
        contains(last(u), "not created with VERTEX usage",
                "binding a non-vertex buffer as vertices is refused");
        GfxBuffer notIndex = u.createBuffer(GfxDesc.Buffer.of(64, GfxUsage.BUFFER_VERTEX).named("vbo-only"));
        cb4.bindIndexBuffer(notIndex, 0, false);
        contains(last(u), "not created with INDEX usage",
                "binding a non-index buffer as indices is refused");
        GfxBuffer notIndirect = u.createBuffer(GfxDesc.Buffer.of(64, GfxUsage.BUFFER_STORAGE).named("ssbo"));
        cb4.beginRenderPass(GfxDesc.RenderPass.of(64, 64).color(GfxDesc.Attachment.of(uImg)).named("upass"));
        cb4.bindGraphicsPipeline(uPipe);
        cb4.drawIndirect(notIndirect, 0, 1, 16);
        contains(last(u), "lacks INDIRECT usage", "indirect args from a non-indirect buffer are refused");
        eq(cb4.draws(), 0, "no draw was recorded on this device");
        cb4.endRenderPass();
        cb4.end();
        u.close();

        section("upload bounds and the shadow copy");
        NullDevice up = lenient();
        GfxBuffer buf = up.createBuffer(GfxDesc.Buffer.of(64, GfxUsage.BUFFER_UNIFORM)
                .domain(MemoryDomain.HOST_VISIBLE).named("host-ubo"));
        ByteBuffer data = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 8; i++) data.putFloat(i);
        data.flip();
        up.uploadBuffer(buf, data, 0);
        eq(up.uploadCount(), 1, "an in-bounds upload is recorded");
        eq(up.uploadedBytes(), 32L, "and its byte count is recorded");
        ByteBuffer rb = ByteBuffer.wrap(up.readBack(buf)).order(ByteOrder.nativeOrder());
        eq((long) rb.getFloat(4 * 3), 3L, "a host-visible buffer keeps a readable shadow copy");
        up.uploadBuffer(buf, data, 48);
        contains(last(up), "overruns buffer 'host-ubo'",
                "an upload past the end of the buffer is refused and names it");
        eq(up.uploadCount(), 1, "the refused upload was not counted");

        GfxImage tex = up.createImage(GfxDesc.Image.tex2d(16, 16, GfxFormat.RGBA8_UNORM,
                GfxUsage.IMAGE_SAMPLED | GfxUsage.IMAGE_TRANSFER_DST).named("tex"));
        ByteBuffer pixels = ByteBuffer.allocateDirect(16 * 16 * 4);
        up.uploadImage(tex, 0, pixels.duplicate());
        eq(up.uploadCount(), 2, "a full-image upload is recorded");
        up.uploadImage(tex, 3, pixels.duplicate());
        contains(last(up), "mip 3 of 1", "an upload to a mip level that does not exist is refused");
        up.uploadImageRegion(tex, 0, 8, 8, 0, 16, 16, 1, pixels.duplicate());
        contains(last(up), "exceeds 'tex' extent", "a region upload past the image extent is refused");
        ByteBuffer tooSmall = ByteBuffer.allocateDirect(16);
        up.uploadImageRegion(tex, 0, 0, 0, 0, 16, 16, 1, tooSmall);
        contains(last(up), "needs 1024B but the source holds 16B",
                "a short source buffer is refused with both sizes stated");
        up.close();

        section("resource leak gate");
        NullDevice leak = new NullDevice();
        for (int i = 0; i < 10; i++) {
            leak.createBuffer(GfxDesc.Buffer.of(64, GfxUsage.BUFFER_UNIFORM).named("b" + i));
            leak.createImage(GfxDesc.Image.tex2d(64, 64, GfxFormat.RGBA8_UNORM, GfxUsage.IMAGE_SAMPLED)
                    .named("i" + i));
            leak.createSampler(GfxDesc.Sampler.linearClamp().named("s" + i));
            leak.createShaderModule(ShaderSource.glsl(GfxStage.FRAGMENT,
                    "#version 460 core\nvoid main() { }\n", "s.frag"));
        }
        eq(leak.liveBufferCount(), 10, "10 buffers live before teardown");
        eq(leak.liveImageCount(), 10, "10 images live before teardown");
        eq(leak.liveSamplerCount(), 10, "10 samplers live before teardown");
        eq(leak.liveShaderModuleCount(), 10, "10 shader modules live before teardown");
        eq(leak.totalCreatedHandles(), 40, "40 handles were handed out");
        leak.close();
        eq(leak.liveBufferCount(), 0, "close() released every buffer");
        eq(leak.liveImageCount(), 0, "close() released every image");
        eq(leak.liveSamplerCount(), 0, "close() released every sampler");
        eq(leak.liveShaderModuleCount(), 0, "close() released every shader module");
        eq(leak.violations().size(), 0, "the leak gate ran with no contract violations");
        receipt("leak gate: 40 handles created, 0 live after close()");

        section("fail-fast mode raises");
        TestRig.throwsNamed(new Runnable() { @Override public void run() {
            new NullDevice().createImage(GfxDesc.Image.tex2d(0, 0, GfxFormat.RGBA8_UNORM, 0).named("bad"));
        }}, "contract violation", "the default fail-fast device raises instead of collecting");

        section("receipts are deterministic, not merely repeatable");
        // The receipt deliberately carries the frame counter, so "identical
        // across frames" is the wrong property. The right one is that two
        // devices doing the same work report the same bytes: no timestamps, no
        // identity hashes, no iteration-order luck. That is what makes a
        // receipt usable as a recorded expectation in CI.
        String r1 = idleDeviceReceipt(3);
        String r2 = idleDeviceReceipt(3);
        eq(r1, r2, "two devices running 3 idle frames produce byte-identical receipts");
        contains(r1, "frames=3", "the frame counter is part of the receipt");
        contains(r1, "violations=0", "and so is the violation count");
        check(!idleDeviceReceipt(4).equals(r1), "a different amount of work gives a different receipt");
        check(!r1.matches("(?s).*[0-9]{4,}\\.[0-9]+.*"), "no elapsed-time figure leaked into the receipt");
        receipt("idle device: " + r1.trim().replace('\n', ' '));

        d.close();
    }
}
