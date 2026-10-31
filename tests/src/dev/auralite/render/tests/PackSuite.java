package dev.auralite.render.tests;

import dev.auralite.render.backend.nulldev.NullDevice;
import dev.auralite.render.backend.nulldev.RecordingCommandBuffer;
import dev.auralite.render.graph.CompiledGraph;
import dev.auralite.render.graph.GraphContext;
import dev.auralite.render.graph.RenderGraph;
import dev.auralite.render.pack.GraphSpec;
import dev.auralite.render.pack.PackFormat;
import dev.auralite.render.pack.PackGraphBuilder;
import dev.auralite.render.pack.PackLoader;
import dev.auralite.render.pack.PackManifest;
import dev.auralite.render.pack.PackOption;
import dev.auralite.render.pack.PackProgram;
import dev.auralite.render.pack.PackSource;
import dev.auralite.render.pack.PipelineProvider;
import dev.auralite.render.rhi.GfxCapability;
import dev.auralite.render.rhi.GfxDesc;
import dev.auralite.render.rhi.GfxEnums.QueueType;
import dev.auralite.render.rhi.GfxFormat;
import dev.auralite.render.rhi.GfxUsage;
import dev.auralite.render.rhi.GfxPipeline;
import dev.auralite.render.rhi.GfxStage;
import dev.auralite.render.scene.FrameContext;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static dev.auralite.render.tests.TestRig.check;
import static dev.auralite.render.tests.TestRig.contains;
import static dev.auralite.render.tests.TestRig.eq;
import static dev.auralite.render.tests.TestRig.receipt;
import static dev.auralite.render.tests.TestRig.section;
import static dev.auralite.render.tests.TestRig.suite;

/**
 * K-lane gate: both pack modes parse, validate and reach an executable graph.
 */
public final class PackSuite {
    private PackSuite() { }

    static final String NATIVE_PACK =
            "# AuraLite-Render native pack, used verbatim by the K-lane gate\n"
          + "[meta]\n"
          + "name = AuraLite Native Test\n"
          + "version = 0.1.0\n"
          + "author = AlexanderNyr\n"
          + "auralite.api = 1\n"
          + "\n"
          + "[capabilities]\n"
          + "require = COMPUTE_SHADERS, TEXTURE_3D, INTEGER_TEXTURES\n"
          + "\n"
          + "[biome]\n"
          + "spatialUniforms = true\n"
          + "sampleEntries = 4\n"
          + "\n"
          + "[resources]\n"
          + "backbuffer   = rgba8_unorm fullscreen imported usage=color|present\n"
          + "scene_color  = rgba16f fullscreen\n"
          + "scene_depth  = d32_sfloat fullscreen usage=depth|sampled\n"
          + "shadow_depth = d32_sfloat shadow usage=depth|sampled\n"
          + "ao           = r16f scaled=0.5 usage=storage|sampled\n"
          + "cloud_shadow = r16f 256x256\n"
          + "biome_volume = rgba8_uint biome-volume usage=sampled\n"
          + "taa          = rgba16f fullscreen history\n"
          + "\n"
          + "[options]\n"
          + "CLOUD_QUALITY = select \"Raymarch steps\" { 14=Cheap, 24=Standard, 36=High, 56=Extreme } default=24 tab=Atmosphere\n"
          + "SSAO = bool \"Screen-space ambient occlusion\" default=true tab=Lighting\n"
          + "BLOOM_STRENGTH = slider 0.0..2.0 step 0.05 \"Bloom\" default=0.35 tab=Colour\n"
          + "\n"
          + "[graph]\n"
          + "pass cloud_shadow raster color=cloud_shadow program=cloud_shadow\n"
          + "pass shadow_map raster depth=shadow_depth program=shadow\n"
          + "pass terrain raster color=scene_color depth=scene_depth read=cloud_shadow,shadow_depth program=terrain\n"
          + "pass ssao compute write=ao read=scene_depth program=ssao dispatch=ceil(width/16),ceil(height/16),1 when=SSAO\n"
          + "pass composite raster color=scene_color read=scene_color,ao program=composite\n"
          + "pass taa_resolve raster color=taa.cur read=scene_color,taa.prev program=taa\n"
          + "pass present raster color=backbuffer read=taa.cur external program=final\n"
          + "present = taa\n"
          + "\n"
          + "[programs]\n"
          + "cloud_shadow = shaders/cloud_shadow.vert, shaders/cloud_shadow.frag\n"
          + "shadow = shaders/shadow.vert\n"
          + "terrain = shaders/terrain.vert, shaders/terrain.frag\n"
          + "ssao = shaders/ssao.comp local_size=16x16x1\n"
          + "composite = shaders/composite.vert, shaders/composite.frag\n"
          + "taa = shaders/taa.vert, shaders/taa.frag\n"
          + "final = shaders/final.vert, shaders/final.frag\n"
          + "\n"
          + "[screen]\n"
          + "Atmosphere = CLOUD_QUALITY\n"
          + "Lighting = SSAO\n"
          + "Colour = BLOOM_STRENGTH\n";

    private static final String VERT = "#version 460 core\nvoid main() { gl_Position = vec4(0.0); }\n";
    private static final String FRAG = "#version 460 core\nout vec4 c;\nvoid main() { c = vec4(1.0); }\n";
    private static final String COMP = "#version 460 core\nlayout(local_size_x=16, local_size_y=16) in;\n"
            + "void main() { }\n";

    static PackSource nativeSource() {
        PackSource.Memory m = new PackSource.Memory("<native-test-pack>");
        m.put("pack.aurapack", NATIVE_PACK);
        for (String n : new String[] { "cloud_shadow", "terrain", "composite", "taa", "final" }) {
            m.put("shaders/" + n + ".vert", VERT);
            m.put("shaders/" + n + ".frag", FRAG);
        }
        m.put("shaders/shadow.vert", VERT);
        m.put("shaders/ssao.comp", COMP);
        return m;
    }

    static PackSource legacySource() {
        PackSource.Memory m = new PackSource.Memory("<legacy-test-pack>");
        m.put("shaders/shaders.properties",
                  "# A legacy pack, shaped like AuraLite-Shaders\n"
                + "sliders = EXPOSURE CONTRAST COLOR_SATURATION\n"
                + "screen = [Atmosphere] [Lighting] <empty> CLOUD_QUALITY SSAO\n"
                + "screen.Atmosphere = CLOUD_QUALITY CLOUD_COVERAGE\n"
                + "screen.columns = 2\n"
                + "shadowDistance = 128\n"
                + "shadowMapResolution = 4096\n"
                + "colortex0Format = rgba16f\n"
                + "colortex3Format = r16f\n"
                + "customTexture.noise = textures/noise.png\n"
                + "program.gbuffers_weather.enabled = false\n"
                + "program.gbuffers_water.blend = SRC_ALPHA ONE_MINUS_SRC_ALPHA\n"
                + "variable.float.waterColorR = if(in(biome, BIOME_SWAMP), 0.38, 0.247)\n"
                + "uniform.vec3.waterColor = vec3(waterColorR, 0.463, 0.894)\n"
                + "waterColor.transition = 2.5\n"
                + "thisLineMakesNoSense = 42\n");
        m.put("shaders/gbuffers_terrain.vsh", VERT);
        m.put("shaders/gbuffers_terrain.fsh", FRAG);
        m.put("shaders/gbuffers_water.vsh", VERT);
        m.put("shaders/gbuffers_water.fsh", FRAG);
        m.put("shaders/gbuffers_weather.vsh", VERT);
        m.put("shaders/gbuffers_weather.fsh", FRAG);
        m.put("shaders/shadow.vsh", VERT);
        m.put("shaders/composite.vsh", VERT);
        m.put("shaders/composite.fsh", FRAG);
        m.put("shaders/final.vsh", VERT);
        m.put("shaders/final.fsh", FRAG);
        m.put("shaders/world-1/gbuffers_terrain.fsh", FRAG);
        return m;
    }

    /** A provider that answers every program with a valid NULL pipeline. */
    static PipelineProvider stubProvider(final NullDevice d, final PackManifest m) {
        return new PipelineProvider() {
            @Override public GfxPipeline rasterPipeline(String name, GfxDesc.RenderPass pass) {
                return d.createGraphicsPipeline(new GfxDesc.GraphicsPipeline()
                        .renderPass(pass)
                        .stage(d.createShaderModule(dev.auralite.render.rhi.ShaderSource.glsl(
                                GfxStage.VERTEX, VERT, name + ".vert")))
                        .stage(d.createShaderModule(dev.auralite.render.rhi.ShaderSource.glsl(
                                GfxStage.FRAGMENT, FRAG, name + ".frag")))
                        .layout(programLayout(m, name))
                        .named(name));
            }
            @Override public GfxPipeline computePipeline(String name) {
                return d.createComputePipeline(dev.auralite.render.rhi.GfxDesc.ComputePipeline
                        .of(d.createShaderModule(dev.auralite.render.rhi.ShaderSource.glsl(
                                GfxStage.COMPUTE, COMP, name + ".comp")))
                        .localSize(16, 16, 1)
                        .layout(programLayout(m, name))
                        .named(name));
            }
            @Override public boolean has(String name) { return true; }
        };
    }

    /** The image-uniform layout the pack's shaders describe, or null for none. */
    static GfxDesc.PipelineLayout programLayout(PackManifest m, String name) {
        PackProgram p = m.program(name);
        return p == null ? null : p.layout();
    }

    public static void run() {
        suite("pack");

        section("format detection");
        eq(PackLoader.detect(nativeSource()), PackFormat.NATIVE, "pack.aurapack selects NATIVE");
        eq(PackLoader.detect(legacySource()), PackFormat.LEGACY_IRIS, "shaders/ selects LEGACY_IRIS");
        eq(PackLoader.detect(new PackSource.Memory("<empty>")), null, "an empty source detects as nothing");
        PackSource.Memory both = new PackSource.Memory("<both>");
        both.put("pack.aurapack", NATIVE_PACK);
        both.put("shaders/shaders.properties", "sliders = X\n");
        both.put("shaders/final.vsh", VERT);
        both.put("shaders/final.fsh", FRAG);
        eq(PackLoader.detect(both), PackFormat.NATIVE, "a pack with both trees loads as NATIVE");

        section("native: meta and contracts");
        PackLoader nl = new PackLoader(nativeSource());
        check(nl.load(), "the native test pack loads: " + nl.report().errors());
        PackManifest n = nl.manifest();
        eq(n.format, PackFormat.NATIVE, "format is NATIVE");
        eq(n.name, "AuraLite Native Test", "the declared name wins over the directory name");
        eq(n.version, "0.1.0", "version");
        eq(n.author, "AlexanderNyr", "author");
        eq(n.apiLevel, 1, "api level");
        eq(n.requiredCapabilities.size(), 3, "three capabilities are required");
        check(n.requiredCapabilities.contains(GfxCapability.COMPUTE_SHADERS), "COMPUTE_SHADERS is required");
        check(n.spatialBiomeUniforms, "spatial biome uniforms are on");
        eq(n.biomeSampleEntries, 4, "four biome contributors per sample");
        eq(nl.report().programFilesRead(), 12, "12 shader files were read");
        eq(nl.report().shaderBytesRead(), 738, "the shader bytes were counted, once per file");

        section("native: resources");
        eq(n.graph.resources().size(), 8, "8 resources declared");
        GraphSpec.ResourceSpec bb = n.graph.findResource("backbuffer");
        check(bb.imported, "the backbuffer is imported");
        eq(bb.format, GfxFormat.RGBA8_UNORM, "the backbuffer format");
        GraphSpec.ResourceSpec ao = n.graph.findResource("ao");
        eq(ao.sizeKind, GraphSpec.SizeKind.SCALED, "ao is a scaled resource");
        eq(ao.scale, 0.5, "ao is half resolution");
        int[] aoExt = ao.extent(1920, 1080, 2048, null);
        eq(aoExt[0], 960, "half of 1920");
        eq(aoExt[1], 540, "half of 1080");
        GraphSpec.ResourceSpec sd = n.graph.findResource("shadow_depth");
        eq(sd.sizeKind, GraphSpec.SizeKind.SHADOW, "the shadow map is sized from the shadow distance");
        eq(sd.extent(1920, 1080, 4096, null)[0], 4096, "and follows it on resize");
        GraphSpec.ResourceSpec vol = n.graph.findResource("biome_volume");
        check(vol.is3d, "the biome volume is a 3D texture");
        eq(vol.format, GfxFormat.RGBA8_UINT, "the biome volume is an integer format");
        int[] volExt = vol.extent(1920, 1080, 2048, new int[] { 23, 97, 23 });
        eq(volExt[1], 97, "the biome volume follows the field window height");
        GraphSpec.ResourceSpec taa = n.graph.findResource("taa");
        check(taa.history, "taa is declared as a history pair");
        eq(n.graph.presentResource, "taa", "the present resource is declared");
        check(n.graph.danglingReferences().isEmpty(),
                "no pass references an undeclared resource: " + n.graph.danglingReferences());

        section("native: options");
        eq(n.optionCount(), 3, "three options declared");
        PackOption cq = n.option("CLOUD_QUALITY");
        eq(cq.kind, PackOption.Kind.SELECTOR, "CLOUD_QUALITY is a selector");
        eq(cq.label, "Raymarch steps", "the label was parsed out of quotes");
        eq(cq.choices().size(), 4, "four choices");
        eq(cq.choices().get(2).value, "36", "the third choice value");
        eq(cq.choices().get(2).label, "High", "and its label");
        eq(cq.defaultValue, "24", "the declared default");
        eq(cq.screenTab, "Atmosphere", "the tab");
        check(cq.accepts("56"), "an enumerated value is accepted");
        check(!cq.accepts("99"), "a value outside the enumeration is refused");
        eq(cq.defineFor("36"), "#define CLOUD_QUALITY 36", "a selector becomes a #define");
        PackOption ssao = n.option("SSAO");
        eq(ssao.kind, PackOption.Kind.BOOL, "SSAO is a bool");
        check(n.optionEnabled("SSAO"), "SSAO is on by default");
        eq(ssao.defineFor("false"), "", "a bool that is off emits no #define");
        PackOption bloom = n.option("BLOOM_STRENGTH");
        eq(bloom.kind, PackOption.Kind.SLIDER, "BLOOM_STRENGTH is a slider");
        eq(bloom.sliderMin, 0.0, "slider minimum");
        eq(bloom.sliderMax, 2.0, "slider maximum");
        eq(bloom.sliderStep, 0.05, "slider step");
        check(bloom.accepts("1.25"), "a value inside the range is accepted");
        check(!bloom.accepts("3.0"), "a value outside the range is refused");
        eq(n.screen().get("Atmosphere").get(0), "CLOUD_QUALITY", "the [screen] tab lists its option");
        n.setOption("CLOUD_QUALITY", "36");
        List<String> defines = n.activeDefines();
        check(defines.contains("#define CLOUD_QUALITY 36"), "changing an option changes the emitted defines");
        check(defines.contains("#define SSAO"), "an enabled bool emits a bare define");
        receipt("options: " + n.optionCount() + " declared, defines = " + defines);
        TestRig.throwsNamed(new Runnable() { @Override public void run() { n.setOption("CLOUD_QUALITY", "99"); } },
                "does not accept", "setting an option to an undeclared value is refused");

        section("native: graph");
        eq(n.graph.passes().size(), 7, "7 passes declared");
        GraphSpec.PassSpec ssaoPass = n.graph.findPass("ssao");
        check(ssaoPass.isCompute(), "ssao is a compute pass");
        eq(ssaoPass.when, "SSAO", "ssao is conditional on an option");
        eq(ssaoPass.dispatchExpr, "ceil(width/16),ceil(height/16),1", "the dispatch expression was kept verbatim");
        eq(n.program("ssao").localSizeX, 16, "local_size was parsed");
        eq(n.programCount(), 7, "7 programs declared");
        eq(n.program("terrain").sourcePaths.size(), 2, "terrain declares two stages");
        check(n.program("terrain").hasStage(GfxStage.VERTEX), "and the vertex stage was bound");
        check(n.program("ssao").isCompute(), "ssao is recognised as a compute program");
        contains(nl.report().warnings().toString(), "shadow",
                "the vertex-only shadow program is reported as incomplete rather than silently accepted");

        section("dispatch expressions");
        dev.auralite.render.uniform.ExprEnv env =
                new dev.auralite.render.uniform.ExprEnv(new dev.auralite.render.uniform.FunctionTable());
        int[] d1 = PackGraphBuilder.resolveDispatch(ssaoPass, env, 1920, 1080);
        eq(d1[0], 120, "ceil(1920/16) = 120");
        eq(d1[1], 68, "ceil(1080/16) = 68, rounded up so no edge pixel is missed");
        eq(d1[2], 1, "the z extent is literal");
        int[] d2 = PackGraphBuilder.resolveDispatch(ssaoPass, env, 2560, 1440);
        eq(d2[0], 160, "the same expression re-resolves on resize");
        eq(d2[1], 90, "and again for height");
        GraphSpec.PassSpec fixed = new GraphSpec.PassSpec("fixed");
        fixed.dispatchX = 4; fixed.dispatchY = 2; fixed.dispatchZ = 1;
        eq(PackGraphBuilder.resolveDispatch(fixed, env, 1920, 1080)[0], 4, "a literal dispatch is used as given");
        List<String> nested = PackGraphBuilder.splitTopLevel("min(width,1920),ceil(height/8),1", ',');
        eq(nested.size(), 3, "a comma inside a call does not split the clause");
        eq(nested.get(0), "min(width,1920)", "the first clause keeps its inner comma");

        section("native: builds a real graph and executes it");
        NullDevice ndev = new NullDevice();
        PackGraphBuilder builder = new PackGraphBuilder(n, stubProvider(ndev, n));
        RenderGraph g = builder.build(1920, 1080, n.shadowMapResolution(), new int[] { 23, 97, 23 });
        CompiledGraph c = g.compile(1920, 1080, ndev.limits());
        List<String> order = c.passOrder();
        receipt("native order: " + order);
        eq(order.get(0), "cloud_shadow", "the declared dependencies put cloud_shadow first");
        eq(order.get(order.size() - 1), "present", "and present last");
        check(order.indexOf("shadow_map") < order.indexOf("terrain"), "shadow_map precedes terrain");
        check(order.indexOf("terrain") < order.indexOf("ssao"), "terrain precedes the ssao compute pass");
        check(order.indexOf("ssao") < order.indexOf("composite"), "ssao precedes composite");
        check(order.indexOf("composite") < order.indexOf("taa_resolve"), "composite precedes taa_resolve");
        eq(c.effectiveDesc("ao").width, 960, "the half-resolution target got its real extent");
        eq(c.effectiveDesc("biome_volume").depth, 23, "the biome volume got its real depth");
        eq(c.graph().require("taa.0").imageDesc().format, GfxFormat.RGBA16F, "the history pair took the format");

        c.allocate(ndev);
        // The backbuffer belongs to the window system, not to the pack, so the
        // host binds it. Refusing to execute until it is bound is the point:
        // a pack that names a resource the host never supplied must not render.
        c.bindImported("backbuffer", ndev.createImage(GfxDesc.Image.tex2d(1920, 1080,
                GfxFormat.RGBA8_UNORM, GfxUsage.IMAGE_COLOR_ATTACHMENT
                        | GfxUsage.IMAGE_PRESENT).named("window")));
        ndev.beginFrame();
        RecordingCommandBuffer cb = (RecordingCommandBuffer) ndev.createCommandBuffer(QueueType.GRAPHICS);
        cb.begin();
        c.execute(new GraphContext(ndev, cb, c, new FrameContext()));
        cb.end();
        ndev.submit(QueueType.GRAPHICS, cb);
        ndev.endFrame();
        eq(ndev.violations().size(), 0, "the whole native frame ran with no contract violations: "
                + ndev.violations());
        eq(cb.count(RecordingCommandBuffer.Op.DISPATCH), 1, "the compute pass dispatched once");
        eq(cb.count(RecordingCommandBuffer.Op.BEGIN_PASS), 6, "6 raster passes ran");
        eq(cb.count(RecordingCommandBuffer.Op.BIND_COMPUTE), 1, "the compute pipeline was bound once");
        check(cb.workGroupsDispatched() == 120L * 68, "the dispatch used the resolved work-group counts");
        receipt("native frame: " + cb.commandCount() + " commands, " + c.totalBarriers() + " barriers, "
                + cb.workGroupsDispatched() + " work groups, 0 violations");
        c.release();

        section("an option turning a pass off is honoured");
        n.setOption("SSAO", "false");
        RenderGraph g2 = new PackGraphBuilder(n, stubProvider(ndev, n)).build(1920, 1080, 4096, new int[] { 23, 97, 23 });
        CompiledGraph c2 = g2.compile(1920, 1080, ndev.limits());
        check(!c2.passOrder().contains("ssao"), "turning SSAO off removes the pass from the frame");
        eq(c2.stats().disabled.size(), 1, "and the receipt says why");
        contains(c2.stats().disabled.get(0), "SSAO", "the receipt names the option");
        n.setOption("SSAO", "true");

        section("legacy: shaders.properties");
        PackLoader ll = new PackLoader(legacySource());
        check(ll.load(), "the legacy test pack loads: " + ll.report().errors());
        PackManifest lm = ll.manifest();
        eq(lm.format, PackFormat.LEGACY_IRIS, "format is LEGACY_IRIS");
        eq(lm.name, "<legacy-test-pack>", "a legacy pack has no name field, so the source name is used");
        eq(lm.shadowDistance(), 128, "shadowDistance was parsed");
        eq(lm.shadowMapResolution(), 4096, "shadowMapResolution was parsed");
        eq(lm.legacyFormats().get("colortex0Format"), "rgba16f", "colortex formats were parsed");
        eq(lm.customTextures().get("noise"), "textures/noise.png", "custom textures were parsed");
        eq(lm.sliderOrder().size(), 3, "three sliders were declared");
        eq(lm.sliderOrder().get(0), "EXPOSURE", "in declaration order");
        eq(lm.screen().get("Atmosphere").size(), 2, "the Atmosphere tab has two options");
        check(lm.hasOption("CLOUD_QUALITY"), "a screen entry created an option");
        eq(lm.customUniforms.size(), 2, "one variable and one uniform were parsed");
        eq(lm.customUniforms.find("waterColor").transitionSeconds, 2.5, "the transition was attached");
        check(!lm.program("gbuffers_weather").enabled, "program.gbuffers_weather.enabled=false was honoured");
        eq(lm.program("gbuffers_water").blendSrc, "SRC_ALPHA", "a legacy blend pair was parsed");
        eq(lm.program("gbuffers_water").blendDst, "ONE_MINUS_SRC_ALPHA", "both halves of it");
        eq(lm.settings().get("screen.columns"), "2", "screen.columns landed in settings");
        eq(ll.report().unparsed().size(), 1, "exactly one line was not understood");
        contains(ll.report().unparsed().get(0), "thisLineMakesNoSense", "and it is reported, not dropped silently");
        contains(ll.report().unparsed().get(0), "shaders.properties:16", "with its line number");
        eq(ll.legacyCoverage().size(), 6, "6 of the 40 core Iris programs are covered by this pack");
        contains(ll.legacyCoverage().toString(), "gbuffers_terrain", "including gbuffers_terrain");
        receipt("legacy: " + lm.programCount() + " programs discovered, "
                + lm.optionCount() + " options, coverage " + ll.legacyCoverage());

        section("legacy: dimension overrides and defaults");
        check(lm.program("gbuffers_terrain").stage(GfxStage.FRAGMENT).origin().contains("world-1"),
                "the last dimension override wins, matching Iris");
        eq(lm.settings().containsKey("shadowDistance"), true, "shadowDistance is present");
        PackLoader noProps = new PackLoader(new PackSource.Memory("<bare>")
                .put("shaders/final.vsh", VERT).put("shaders/final.fsh", FRAG));
        check(noProps.load(), "a pack with no shaders.properties still loads");
        eq(noProps.manifest().shadowDistance(), 128, "and gets the Iris default shadow distance");
        check(noProps.manifest().hasOption("AURALITE_SPATIAL_BIOME"),
                "every legacy pack gets the spatial-biome option injected");
        eq(noProps.manifest().option("AURALITE_SPATIAL_BIOME").defaultValue, "false",
                "off by default, because it changes what an unmodified pack renders");
        contains(noProps.report().warnings().toString(), "shaders.properties",
                "the missing properties file is reported as a warning");

        section("legacy: synthesises a graph too");
        NullDevice ldev = new NullDevice();
        RenderGraph lg = new PackGraphBuilder(lm, stubProvider(ldev, lm)).build(1920, 1080, 4096, null);
        CompiledGraph lc = lg.compile(1920, 1080, ldev.limits());
        List<String> lorder = lc.passOrder();
        receipt("legacy order: " + lorder);
        eq(lorder.get(0), "shadow_map", "the legacy frame starts with the shadow map");
        eq(lorder.get(lorder.size() - 1), "present", "and ends at present");
        check(lorder.contains("gbuffers_terrain"), "the terrain program became a pass");
        check(!lorder.contains("gbuffers_weather"), "a disabled program did not become a pass");
        check(lorder.indexOf("gbuffers_terrain") < lorder.indexOf("composite"),
                "terrain precedes composite, as Iris does");
        lc.allocate(ldev);
        RecordingCommandBuffer lcb = (RecordingCommandBuffer) ldev.createCommandBuffer(QueueType.GRAPHICS);
        lcb.begin();
        lc.execute(new GraphContext(ldev, lcb, lc, new FrameContext()));
        lcb.end();
        eq(ldev.violations().size(), 0, "the legacy frame ran clean on the new pipeline: " + ldev.violations());
        receipt("legacy frame: " + lc.passCount() + " passes, " + lcb.commandCount() + " commands, "
                + lc.totalBarriers() + " barriers, 0 violations");
        lc.release();

        section("refusals name their reason");
        PackSource.Memory noApi = new PackSource.Memory("<api>");
        noApi.put("pack.aurapack", NATIVE_PACK.replace("auralite.api = 1", "auralite.api = 99"));
        noApi.put("shaders/shadow.vert", VERT);
        PackLoader apiLoader = new PackLoader(noApi);
        check(!apiLoader.load(), "a pack above the implemented API level is refused");
        contains(apiLoader.report().errors().get(0), "requires API level 99",
                "the refusal states the level it needs");
        contains(apiLoader.report().errors().get(0), "implements 1", "and the level this loader has");

        Set<GfxCapability> weak = EnumSet.of(GfxCapability.TEXTURE_3D);
        NullDevice weakDev = new NullDevice(NullDevice.conservativeLimits(), weak, false);
        PackLoader capLoader = new PackLoader(nativeSource());
        check(!capLoader.load(weakDev), "a pack whose capabilities the device lacks is refused");
        contains(capLoader.report().errors().get(0), "lacks", "the refusal says the device lacks them");
        contains(capLoader.report().errors().get(0), "COMPUTE_SHADERS", "and names the first missing one");
        weakDev.close();

        PackLoader okLoader = new PackLoader(nativeSource());
        check(okLoader.load(new NullDevice()), "the same pack loads on a device that has them");
        contains(okLoader.report().notes().toString(), "required capabilities present",
                "and the report says the contract was met");

        PackSource.Memory dangling = new PackSource.Memory("<dangling>");
        dangling.put("pack.aurapack",
                  "[meta]\nname = Dangling\n[resources]\nscene = rgba16f fullscreen\n"
                + "[graph]\npresent = scene\npass main raster color=scene read=not_declared external\n"
                + "[programs]\n");
        PackLoader dangLoader = new PackLoader(dangling);
        check(!dangLoader.load(), "a pass reading an undeclared resource is refused");
        contains(dangLoader.report().errors().toString(), "undeclared resource: main -> not_declared",
                "the refusal names the pass and the resource");

        PackSource.Memory dupPass = new PackSource.Memory("<dup>");
        dupPass.put("pack.aurapack",
                  "[meta]\nname = Dup\n[resources]\nscene = rgba16f fullscreen\n[graph]\n"
                + "pass a raster color=scene external\npass a raster color=scene external\n");
        PackLoader dupLoader = new PackLoader(dupPass);
        check(!dupLoader.load(), "a duplicated pass name is refused");
        contains(dupLoader.report().errors().toString(), "declared twice", "and says it is a duplicate");

        PackSource.Memory badFormat = new PackSource.Memory("<badfmt>");
        badFormat.put("pack.aurapack",
                  "[meta]\nname = Bad\n[resources]\nscene = rgb9e5 fullscreen\n[graph]\n"
                + "pass a raster color=scene external\n");
        PackLoader bfLoader = new PackLoader(badFormat);
        check(!bfLoader.load(), "an unknown resource format is refused");
        contains(bfLoader.report().errors().toString(), "unknown format 'rgb9e5'", "and names the format");

        PackSource.Memory noPasses = new PackSource.Memory("<nopass>");
        noPasses.put("pack.aurapack", "[meta]\nname = Empty\n[resources]\n[graph]\n");
        PackLoader npLoader = new PackLoader(noPasses);
        check(!npLoader.load(), "a native pack with no passes is refused");
        contains(npLoader.report().errors().toString(), "at least one pass", "and says what is missing");

        PackSource.Memory badProgram = new PackSource.Memory("<badprog>");
        badProgram.put("pack.aurapack",
                  "[meta]\nname = BadProg\n[resources]\nscene = rgba16f fullscreen\n"
                + "[graph]\npass a raster color=scene program=ghost external\n"
                + "[programs]\nghost = shaders/ghost.frag\n");
        badProgram.put("shaders/ghost.frag", FRAG);
        PackLoader bpLoader = new PackLoader(badProgram);
        check(!bpLoader.load(), "a pass bound to a program with no vertex stage is refused");
        contains(bpLoader.report().errors().toString(), "missing", "and reports what the program lacks");

        PackSource.Memory notAPack = new PackSource.Memory("<junk>");
        notAPack.put("readme.txt", "hello");
        PackLoader junk = new PackLoader(notAPack);
        check(!junk.load(), "a directory that is not a pack is refused");
        contains(junk.report().errors().get(0), "does not look like a shader pack", "and says so plainly");

        section("both trees: the decision is recorded");
        PackLoader bothLoader = new PackLoader(both);
        bothLoader.load();
        contains(bothLoader.report().notes().toString(), "loaded as NATIVE",
                "loading both trees records which one won");
        contains(bothLoader.report().notes().toString(), "legacy tree was ignored",
                "and that the other was ignored");

        section("reference pack: packs/AuraLite-Native is the executable spec");
        java.nio.file.Path refDir = java.nio.file.Paths.get("packs/AuraLite-Native");
        check(java.nio.file.Files.isRegularFile(refDir.resolve("pack.aurapack")),
                "packs/AuraLite-Native/pack.aurapack exists (run make test from the repository root)");
        PackLoader rl = new PackLoader(new PackSource.Directory(refDir));
        check(rl.load(null), "the reference pack loads with 0 errors: " + rl.report().errors());
        PackManifest rm = rl.manifest();
        eq(rl.report().errorCount(), 0, "0 errors");
        eq(rl.report().warningCount(), 0, "0 warnings - it lints clean: " + rl.report().warnings());
        eq(rm.format, PackFormat.NATIVE, "detected NATIVE");
        eq(rm.apiLevel, 1, "api level 1");
        check(rm.requiredCapabilities.contains(GfxCapability.COMPUTE_SHADERS),
                "the compute requirement survived validation");
        check(rm.spatialBiomeUniforms, "spatial biome uniforms are on");
        eq(rm.graph.passes().size(), 6, "6 passes cover raster, compute, history and external");
        eq(rm.optionCount(), 4, "4 options cover bool, select, slider and const");
        eq(rm.programCount(), 6, "6 programs, every stage listed explicitly");
        eq(rm.graph.presentResource, "taa", "the present resource is declared");

        // The provider answers only with the pack's own declared programs and
        // sources, so the NULL device structurally validates the shipped GLSL
        // (entry point per stage) as part of this gate.
        NullDevice rdev = new NullDevice();
        PackGraphBuilder rb = new PackGraphBuilder(rm, packBackedProvider(rdev, rm));
        RenderGraph rg = rb.build(1920, 1080, rm.shadowMapResolution(), new int[] { 23, 97, 23 });
        CompiledGraph rc = rg.compile(1920, 1080, rdev.limits());
        List<String> rorder = rc.passOrder();
        eq(rorder.get(rorder.size() - 1), "present", "present is last");
        check(rorder.contains("biome_field"), "the compute pass survives culling (terrain reads its output)");
        check(rorder.contains("ssao"), "the option-gated compute pass runs with SSAO on by default");
        rc.allocate(rdev);
        rc.bindImported("backbuffer", rdev.createImage(GfxDesc.Image.tex2d(1920, 1080,
                GfxFormat.RGBA8_UNORM, GfxUsage.IMAGE_COLOR_ATTACHMENT | GfxUsage.IMAGE_PRESENT)
                .named("window")));
        rdev.beginFrame();
        RecordingCommandBuffer rcb = (RecordingCommandBuffer) rdev.createCommandBuffer(QueueType.GRAPHICS);
        rcb.begin();
        rc.execute(new GraphContext(rdev, rcb, rc, new FrameContext()));
        rcb.end();
        rdev.submit(QueueType.GRAPHICS, rcb);
        rdev.endFrame();
        eq(rdev.violations().size(), 0,
                "the reference frame ran with 0 contract violations: " + rdev.violations());
        eq(rcb.count(RecordingCommandBuffer.Op.BIND_COMPUTE), 2, "both compute pipelines were bound");
        check(rcb.workGroupsDispatched() > 0, "the compute dispatches used the resolved extents");
        receipt("reference pack: loads 0 errors 0 warnings, frame " + rcb.commandCount() + " commands, "
                + rc.totalBarriers() + " barriers, " + rcb.workGroupsDispatched() + " work groups, 0 violations");
        rc.release();
        rdev.close();

        ndev.close();
        ldev.close();
    }

    /**
     * A provider that answers only with the pack's own declared programs and
     * shader sources - the reference-pack gate uses it so the shipped GLSL is
     * what the NULL device validates, not a stub.
     */
    static PipelineProvider packBackedProvider(final NullDevice d, final PackManifest m) {
        return new PipelineProvider() {
            @Override public GfxPipeline rasterPipeline(String name, GfxDesc.RenderPass pass) {
                PackProgram p = m.program(name);
                GfxDesc.GraphicsPipeline gp = new GfxDesc.GraphicsPipeline()
                        .renderPass(pass)
                        .layout(programLayout(m, name)).named(name);
                if (p.stage(GfxStage.VERTEX) != null) gp.stage(d.createShaderModule(p.stage(GfxStage.VERTEX)));
                if (p.stage(GfxStage.FRAGMENT) != null) gp.stage(d.createShaderModule(p.stage(GfxStage.FRAGMENT)));
                return d.createGraphicsPipeline(gp);
            }
            @Override public GfxPipeline computePipeline(String name) {
                PackProgram p = m.program(name);
                if (p == null || p.stage(GfxStage.COMPUTE) == null) return null;
                return d.createComputePipeline(GfxDesc.ComputePipeline
                        .of(d.createShaderModule(p.stage(GfxStage.COMPUTE)))
                        .localSize(p.localSizeX, p.localSizeY, p.localSizeZ)
                        .layout(programLayout(m, name)).named(name));
            }
            @Override public boolean has(String name) {
                PackProgram p = m.program(name);
                return p != null && p.stageCount() > 0;
            }
        };
    }
}
