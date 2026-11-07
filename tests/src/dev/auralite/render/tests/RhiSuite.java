package dev.auralite.render.tests;

import dev.auralite.render.rhi.DeviceLimits;
import dev.auralite.render.rhi.GfxCapability;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxStage;
import dev.auralite.render.rhi.GfxUsage;
import dev.auralite.render.rhi.ShaderSource;

import java.util.Arrays;

import static dev.auralite.render.tests.TestRig.check;
import static dev.auralite.render.tests.TestRig.eq;
import static dev.auralite.render.tests.TestRig.near;
import static dev.auralite.render.tests.TestRig.receipt;
import static dev.auralite.render.tests.TestRig.section;
import static dev.auralite.render.tests.TestRig.suite;
import static dev.auralite.render.tests.TestRig.throwsNamed;

/** R0/R1 gate: the format table, the descriptors and the shader-source rules. */
public final class RhiSuite {
    private RhiSuite() { }

    public static void run() {
        suite("rhi");

        section("formats");
        eq(GfxFormat.RGBA16F.bytesPerTexel(), 8, "RGBA16F is 8 bytes per texel");
        eq(GfxFormat.RGBA32F.bytesPerTexel(), 16, "RGBA32F is 16 bytes per texel");
        eq(GfxFormat.R32_UINT.bytesPerTexel(), 4, "R32_UINT is 4 bytes per texel");
        check(GfxFormat.RGBA8_SRGB.isSrgb(), "RGBA8_SRGB reports srgb");
        check(!GfxFormat.RGBA8_UNORM.isSrgb(), "RGBA8_UNORM does not report srgb");
        check(GfxFormat.R32_UINT.isInteger(), "R32_UINT is an integer format");
        check(GfxFormat.R32_UINT.requiresIntegerSampler(), "integer formats need an integer sampler");
        check(GfxFormat.D32_SFLOAT.isDepth(), "D32_SFLOAT is a depth format");
        check(!GfxFormat.D32_SFLOAT.isColorAttachment(), "a depth format is not a colour attachment");
        check(GfxFormat.RGBA16F.isColorAttachment(), "RGBA16F is a colour attachment");
        check(!GfxFormat.BC7_SRGB.isStorableImage(), "a compressed format is not a storage image");

        section("format lookup");
        eq(GfxFormat.fromName("rgba16f"), GfxFormat.RGBA16F, "exact name lookup");
        eq(GfxFormat.fromName("RGBA16F"), GfxFormat.RGBA16F, "lookup is case-insensitive");
        eq(GfxFormat.fromName("rgba16"), GfxFormat.RGBA16F, "legacy spelling 'rgba16'");
        eq(GfxFormat.fromName("rg11b10"), GfxFormat.RG11B10F, "legacy spelling 'rg11b10'");
        eq(GfxFormat.fromName("r8"), GfxFormat.R8_UNORM, "legacy spelling 'r8'");
        eq(GfxFormat.fromName("depth"), GfxFormat.D32_SFLOAT, "legacy spelling 'depth'");
        eq(GfxFormat.fromName("not-a-format"), GfxFormat.UNDEFINED, "unknown name yields UNDEFINED");
        eq(GfxFormat.fromName(null), GfxFormat.UNDEFINED, "null name yields UNDEFINED");

        section("image descriptors");
        GfxDesc.Image full = GfxDesc.Image.tex2d(1920, 1080, GfxFormat.RGBA16F, GfxUsage.IMAGE_COLOR_ATTACHMENT);
        eq(full.levelBytes(), 1920L * 1080 * 8, "level 0 bytes for 1920x1080 RGBA16F");
        GfxDesc.Image mipped = GfxDesc.Image.tex2d(1024, 1024, GfxFormat.RGBA16F, 0).fullMipChain();
        eq(mipped.mipLevels, 11, "a 1024x1024 image has 11 mip levels");
        // 1024^2 * 8 * (1 + 1/4 + 1/16 + ...) converges to 4/3 of level 0.
        near(mipped.approxBytes() / (double) (1024L * 1024 * 8), 4.0 / 3.0, 0.01,
                "full mip chain is ~4/3 of level 0");
        GfxDesc.Image vol = GfxDesc.Image.tex3d(64, 96, 64, GfxFormat.RGBA8_UINT, GfxUsage.IMAGE_SAMPLED);
        eq(vol.texelCount(), 64L * 96 * 64, "3D texel count");
        eq(vol.levelBytes(), 64L * 96 * 64 * 4, "3D level bytes for RGBA8UI");
        eq(vol.approxBytes(), vol.levelBytes(), "a single-mip 3D image has no chain overhead");

        section("descriptor hashing");
        GfxDesc.Image a = GfxDesc.Image.tex2d(1920, 1080, GfxFormat.RGBA16F, 0);
        GfxDesc.Image b = GfxDesc.Image.tex2d(1920, 1080, GfxFormat.RGBA16F, 0);
        GfxDesc.Image c = GfxDesc.Image.tex2d(1920, 1080, GfxFormat.RGBA8_UNORM, 0);
        eq(a.hash(), b.hash(), "identical descriptors hash identically");
        check(a.hash() != c.hash(), "a format change changes the hash");
        GfxDesc.Sampler s1 = GfxDesc.Sampler.linearClamp();
        GfxDesc.Sampler s2 = GfxDesc.Sampler.linearClamp().aniso(16);
        check(s1.hash() != s2.hash(), "anisotropy is part of the sampler hash");

        section("limits");
        eq(DeviceLimits.align(100, 256), 256L, "align 100 to 256");
        eq(DeviceLimits.align(512, 256), 512L, "512 is already aligned to 256");
        eq(DeviceLimits.align(0, 256), 0L, "zero stays zero");
        DeviceLimits l = new DeviceLimits();
        check(l.supportsDispatch(240, 135, 1, 8, 8, 1), "a 1920x1080 / 8x8 dispatch fits");
        check(!l.supportsDispatch(1, 1, 1, 2048, 1, 1), "a 2048-wide local size does not fit");
        check(!l.supportsDispatch(99999, 1, 1, 8, 8, 1), "a work-group count over the limit does not fit");

        section("stages");
        eq(GfxStage.fromExtension("vsh"), GfxStage.VERTEX, "legacy .vsh maps to VERTEX");
        eq(GfxStage.fromExtension("fsh"), GfxStage.FRAGMENT, "legacy .fsh maps to FRAGMENT");
        eq(GfxStage.fromExtension("comp"), GfxStage.COMPUTE, ".comp maps to COMPUTE");
        eq(GfxStage.fromExtension("glsl.frag"), GfxStage.FRAGMENT, "glsl.frag maps to FRAGMENT");
        eq(GfxStage.fromExtension("txt"), null, "an unknown extension maps to nothing");
        check(GfxStage.COMPUTE.isGraphics() == false, "COMPUTE is not a graphics stage");
        check(GfxStage.CLOSEST_HIT.isRayTracing(), "CLOSEST_HIT is a ray-tracing stage");
        check(GfxStage.MESH.optional(), "MESH is optional in a graphics pipeline");
        check(!GfxStage.FRAGMENT.optional(), "FRAGMENT is not optional");

        section("shader sources");
        ShaderSource glsl = ShaderSource.glsl(GfxStage.FRAGMENT,
                "#version 460 core\nvoid main() { }\n", "test.frag");
        check(glsl.preprocessed().contains("void main("), "preprocessed GLSL keeps the entry point");
        eq(glsl.preprocessed().split("#version", -1).length - 1, 1,
                "preprocessing yields exactly one #version line");
        ShaderSource withDefines = glsl.withDefines(Arrays.asList("CLOUD_QUALITY 36"));
        check(withDefines.preprocessed().contains("#define CLOUD_QUALITY 36"), "defines are prepended");
        check(withDefines.hash() != glsl.hash(), "defines change the source hash");
        eq(ShaderSource.stripVersion("#version 450\nint x;"), "int x;", "#version is stripped");
        ShaderSource spirv = ShaderSource.spirv(GfxStage.COMPUTE, new byte[64], "main", "test.comp.spv");
        throwsNamed(new Runnable() {
            @Override public void run() { spirv.withDefines(Arrays.asList("X 1")); }
        }, "SPIR-V cannot take preprocessor defines", "defines on SPIR-V are refused with a reason");

        // The rule behind the r62 crash, gated where a GPU cannot be needed:
        // a cached object that names a dead resource must go with it. This is
        // the ownership bookkeeping the Vulkan backend uses for attachment
        // views and framebuffers, so the driver fault it prevents (a
        // use-after-free the GT 710 answers with 0xC0000409 shortly after a
        // rebuild) has a host-side guard instead of only a screenshot.
        section("cache ownership: a dead resource drops its entries");
        dev.auralite.render.util.ResourceCacheOwnership<String, String> owned =
                new dev.auralite.render.util.ResourceCacheOwnership<String, String>();
        owned.attach("view:9:0", "V9a", java.util.Collections.singletonList("img9"));
        eq(owned.entryCount(), 1, "an attached entry is counted");
        eq(owned.purge("img9").toString(), "[V9a]",
                "releasing the image returns exactly the view it owned, for destruction");
        eq(owned.entryCount(), 0, "and the entry is gone from the bookkeeping");
        eq(owned.purge("img9").size(), 0, "purging a dead resource twice is empty, not fatal");
        // The sweep the Vulkan backend actually runs walks its cache BY KEY and
        // asks this question, because `purge` answers with VALUES (the handles to
        // destroy). r63 removed a handle from a map keyed by key: the removal
        // returned null, the guard skipped the destroy, and `viewsTorn=0` sat
        // next to a cache that grew every rebuild - so `owns` is gated here,
        // including for a key that was never attached.
        owned.attach("view:7", "V7", java.util.Collections.singletonList("img7"));
        check(owned.owns("view:7"), "an attached key answers as owned");
        check(!owned.owns("V7"), "the VALUE it holds is not a key: asking with a handle says no");
        owned.purge("img7");
        check(!owned.owns("view:7"), "and after the purge the key is nobody's");
        check(!owned.owns(null), "a null key is refused rather than thrown at");

        owned.attach("fb:1", "F1", java.util.Arrays.asList("imgA", "imgB"));
        owned.attach("fb:2", "F2", java.util.Collections.singletonList("imgB"));
        java.util.List<String> dropped = owned.purge("imgB");
        eq(dropped.size(), 2,
                "one freed attachment invalidates EVERY framebuffer built from it (shared-key rule)");
        eq(owned.entriesFor("imgA"), 0,
                "and the co-owner's entry goes too: a framebuffer with a freed attachment is not valid");

        try {
            owned.attach("fb:x", "V", java.util.Collections.<String>emptyList());
            check(false, "an entry with no owning resource must be refused");
        } catch (IllegalArgumentException expected) {
            check(true, "an entry that can never be invalidated is refused by constructor");
        }
        try {
            owned.attach(null, "V", java.util.Collections.singletonList("imgZ"));
            check(false, "an entry with no key must be refused");
        } catch (IllegalArgumentException expected) {
            check(true, "and so is one that cannot be found again to be dropped");
        }
        receipt("cache ownership: " + owned.entryCount()
                + " entries left after two purges; a shared key drops for every owner");

        section("capabilities");
        eq(GfxCapability.values().length, 27, "the capability enum has 27 members");
        check(GfxCapability.COMPUTE_SHADERS.ordinal() == 0, "COMPUTE_SHADERS is first");

        receipt("rhi: " + GfxFormat.values().length + " formats, "
                + GfxStage.values().length + " stages, "
                + GfxCapability.values().length + " capabilities");
    }
}
