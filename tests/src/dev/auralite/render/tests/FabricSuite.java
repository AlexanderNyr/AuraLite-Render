package dev.auralite.render.tests;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static dev.auralite.render.tests.TestRig.check;
import static dev.auralite.render.tests.TestRig.contains;
import static dev.auralite.render.tests.TestRig.eq;
import static dev.auralite.render.tests.TestRig.receipt;
import static dev.auralite.render.tests.TestRig.section;
import static dev.auralite.render.tests.TestRig.suite;

/**
 * F-lane gate: the fabric module's sandbox deliverable.
 *
 * <p>Phase 4's windowed-frame gate needs a JDK 21 and a GPU and is NOT
 * claimed here (standing rule 4). What CAN be pinned host-side is the lane's
 * documented shape: sources + json exist, the mod metadata declares the
 * entrypoint and java 21 the mixin target matches its file, and the host
 * build SKIPs the module visibly instead of silently not knowing about it.
 * If one of those facts moves without the docs moving, this suite says which.
 */
public final class FabricSuite {

    private FabricSuite() { }

    private static String read(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    public static void run() {
        suite("fabric");

        section("module shape: sources + json (the documented sandbox state)");
        Path root = Paths.get("fabric");
        check(Files.isRegularFile(root.resolve("build.gradle")), "fabric/build.gradle exists");
        check(Files.isRegularFile(root.resolve("settings.gradle")), "fabric/settings.gradle exists");
        check(Files.isRegularFile(root.resolve("gradle.properties")), "fabric/gradle.properties exists");

        section("fabric.mod.json: entrypoint, environment, java 21");
        Path modJson = root.resolve("src/main/resources/fabric.mod.json");
        check(Files.isRegularFile(modJson), "fabric.mod.json exists");
        try {
            String json = read(modJson);
            contains(json, "\"environment\": \"client\"", "the mod is client-environment only");
            contains(json, "\"java\": \">=21\"", "the mod requires java 21");
            contains(json, "dev.auralite.render.fabric.AuraLiteRenderMod",
                    "the client entrypoint is declared");
            contains(json, "auralite-render.mixins.json", "the mixin config is referenced");
            contains(json, "Apache-2.0", "the license matches the repository");

            Path entry = root.resolve(
                    "src/main/java/dev/auralite/render/fabric/AuraLiteRenderMod.java");
            check(Files.isRegularFile(entry),
                    "the declared entrypoint class has a source file");
            contains(read(entry), "package dev.auralite.render.fabric;",
                    "and sits in the package the entrypoint names");
            contains(read(entry), "implements ClientModInitializer",
                    "and implements the fabric client entrypoint interface");
        } catch (Exception e) {
            check(false, "fabric.mod.json could not be read: " + e);
        }

        section("mixin config matches its target");
        Path mixinsJson = root.resolve("src/main/resources/auralite-render.mixins.json");
        check(Files.isRegularFile(mixinsJson), "auralite-render.mixins.json exists");
        try {
            String mj = read(mixinsJson);
            contains(mj, "\"JAVA_21\"", "the mixin compatibility level is java 21");
            // The hook moved from the client ticker to the game renderer in the
            // upstream "Bugfix" commit, and back again later; the contract, not
            // the class name, is what this gate is about: one mixin, listed, and
            // it calls the loop. Whichever name the sources currently use, the
            // listed file must be the one that exists.
            String mixinName = "MinecraftClientMixin";
            Path mixinSrc = root.resolve(
                    "src/main/java/dev/auralite/render/fabric/mixin/" + mixinName + ".java");
            if (!Files.isRegularFile(mixinSrc)) {
                mixinName = "GameRendererMixin";
                mixinSrc = root.resolve(
                        "src/main/java/dev/auralite/render/fabric/mixin/" + mixinName + ".java");
            }
            contains(mj, mixinName, "the frame hook mixin is listed");
            check(Files.isRegularFile(mixinSrc), mixinName + ".java exists (" + mixinName + ")");
            contains(read(mixinSrc), "RendererLoop.frame",
                    "the mixin drives the renderer loop");
        } catch (Exception e) {
            check(false, "the mixin config could not be read: " + e);
        }

        section("the glue sources the ROADMAP names");
        String[] sources = {
                "AuraLiteRenderMod.java", "RendererLoop.java", "WindowSwapchain.java",
                "FramePacer.java", "PackWatcher.java", "RenderDocLabels.java",
                "GlPipelineProvider.java"
        };
        int present = 0;
        for (String name : sources) {
            if (Files.isRegularFile(root.resolve("src/main/java/dev/auralite/render/fabric/" + name))) {
                present++;
            }
        }
        eq(present, sources.length,
                "all glue sources present: swapchain, pacing, hot-reload, labels, provider, loop, mod");
        try {
            contains(read(root.resolve("build.gradle")), "JavaLanguageVersion.of(21)",
                    "the gradle lane pins a java 21 toolchain");
            contains(read(root.resolve("build.gradle")), "zipTree('../third_party/",
                    "the gradle lane reads compile-only LWJGL from the host's ../third_party jars");
            contains(read(root.resolve("build.gradle")), "lwjglJars",
                    "and wires compileOnly to multi-release-stripped copies "
                            + "(tiny-remapper rejects the jar's class major 71 entries)");
        } catch (Exception e) {
            check(false, "build.gradle could not be read: " + e);
        }

        section("host build SKIPs visibly");
        try {
            String makefile = read(Paths.get("Makefile"));
            contains(makefile, "fabric: SKIP",
                    "make build prints the fabric SKIP line on a pre-21 JDK");
            contains(makefile, "needs JDK 21",
                    "and names what the lane needs");
        } catch (Exception e) {
            check(false, "the Makefile could not be read: " + e);
        }

        section("frame context bridge: world state reaches the graph");
        try {
            Path bridge = root.resolve(
                    "src/main/java/dev/auralite/render/fabric/FrameBridge.java");
            check(Files.isRegularFile(bridge), "FrameBridge.java exists");
            String b = read(bridge);
            contains(b, "ViewMatrix.compute",
                    "the view matrix is rebuilt from the camera (RenderSystem is GUI-owned by hook time)");
            contains(b, "getBasicProjectionMatrix",
                    "the projection comes from Minecraft's own function");
            contains(b, "finishCameraUpdate",
                    "derived matrices and the frustum are recomputed after the fill");
            contains(b, "client.world",
                    "world fields are read from the live world (menu leaves them stale, not zeroed)");

            Path loop = root.resolve(
                    "src/main/java/dev/auralite/render/fabric/RendererLoop.java");
            String l = read(loop);
            int begin = l.indexOf("frame.beginFrame");
            int fill = l.indexOf("FrameBridge.fill");
            check(begin >= 0 && fill > begin,
                    "RendererLoop fills the frame context AFTER beginFrame "
                            + "(previous-frame snapshot first, then this frame's values)");

            check(Files.isRegularFile(Paths.get(
                            "core/src/main/java/dev/auralite/render/scene/ViewMatrix.java")),
                    "ViewMatrix lives in core where the host suite can gate it");
        } catch (Exception e) {
            check(false, "the frame bridge could not be read: " + e);
        }

        section("rebuild receipts: the r42 teardown is in the shipped lane");
        try {
            Path vk = Paths.get(
                    "backend-vulkan/src/main/java/dev/auralite/render/backend/vk/VkDevice.java");
            check(Files.isRegularFile(vk), "the Vulkan backend source is readable for the gate");
            String v = read(vk);
            contains(v, "ResourceCacheOwnership",
                    "the backend tracks which cached entries a resource owns");
            contains(v, "purgeCachesForImage",
                    "and an image teardown exists - the r62 tree had the docs and not the code");
            int close = v.indexOf("device.liveImages.remove(this);");
            int purge = v.indexOf("device.purgeCachesForImage(handle);");
            int destroy = v.indexOf("vkDestroyImage(device.device, handle, null);");
            check(close >= 0 && purge > close && destroy > purge,
                    "the purge runs inside VkImage.close(), before the image is destroyed");
            contains(v, "public String cacheReceipt()",
                    "the teardown reports itself (imagesLive/released/viewsTorn/fbosTorn)");
            check(!v.contains("createMessenger(MemoryStack"),
                    "and there is no debug-messenger path that only exists to crash on enable");

            Path loop = root.resolve(
                    "src/main/java/dev/auralite/render/fabric/RendererLoop.java");
            String l = read(loop);
            contains(l, "cacheReceipt()",
                    "the host rebuild line asks the Vulkan device for that receipt");
            contains(l, "present/frame:",
                    "and present hashes the whole pack output, not one centre pixel once");
            contains(l, "packedBytes(",
                    "the bake receipt quotes the packer's byte count instead of cells x 4");

            // The teardown ORDER, from the r62 trace: the process died on the
            // frame after a reload, which is what freeing images in front of
            // an in-flight submit looks like. Pinned as text because the host
            // has no driver to fault, and a reordering that drops the drain is
            // invisible to every other gate.
            int invalidate = l.indexOf("pipelines.invalidate()");
            int release = l.indexOf("graph.release()");
            int drainStart = l.indexOf("drainT0 = System.nanoTime");
            check(invalidate >= 0 && release > invalidate,
                    "rebuild order is drain -> pipelines die -> pack load -> old graph released");
            check(drainStart > 0 && drainStart < release,
                    "and a second waitIdle sits between the new graph's uploads and the release");
            contains(l, "drained before release",
                    "the drain is timed into the trace, so its cost is a receipt, not a hope");

            // r64: the things the live log proved r63 had NOT done.
            contains(l, "pendingRelease",
                    "the old graph is parked, not freed, inside the rebuild call");
            contains(l, "RELEASE_GRACE_FRAMES",
                    "and it survives a named number of frames before release()");
            int defer = l.indexOf("pendingRelease = graph;");
            int grace = l.indexOf("frames - pendingReleaseAtFrame");
            int burst = l.indexOf("releasing the graph deferred by");
            check(defer > 0 && grace > 0 && burst > 0,
                    "park happens in rebuild, the free on a later frame's path and in a burst "
                            + "(saw park " + defer + ", grace " + grace + ", burst " + burst + ")");
            int begin = l.indexOf("device.beginFrame()");
            check(grace > 0 && grace < begin,
                    "the free is BEFORE this frame's beginFrame, so no new work is queued "
                            + "against a graph that is being freed");
            contains(l, "release timing:",
                    "and the deferral is receipted (count + longest hold), not just done");
            int drain = l.indexOf("drained before release");
            check(drain > 0 && drain < defer,
                    "the r63 drain stays - it retires the load's own submits - but is no "
                            + "longer the only wait in front of a release");
            contains(l, "biomeReceiptLogged = false;",
                    "the bake receipt re-arms when the answer changes instead of once per session");
            contains(l, "unattributed",
                    "a refusal nobody counted gets its own number instead of vanishing");
            contains(v, "dying.contains(e.getValue())",
                    "the view sweep matches the purge answer by VALUE while removing by KEY: "
                            + "purge hands back handles and the cache is keyed by view key, and "
                            + "treating one as the other is the r63 bug that left viewsTorn=0");
            contains(v, "viewsUnattributed++",
                    "and an image whose ownership named nothing is COUNTED, not silently skipped");
            contains(read(Paths.get(
                            "core/src/main/java/dev/auralite/render/util/ResourceCacheOwnership.java")),
                    "public boolean owns(Object key)",
                    "the keyed question the utility answers stays on the books for the next sweep");
            int frees = 0, at = 0;
            while ((at = l.indexOf("pendingRelease.release()", at + 1)) > 0) frees++;
            check(frees == 2,
                    "released from exactly two places - the grace path and a rebuild burst - "
                            + "each behind its own null test (a third is a double free), saw " + frees);
            check(l.indexOf("pendingRelease.release()", l.indexOf("if (pendingRelease != null &&")) > 0,
                    "the grace release sits behind its null guard");
            check(l.indexOf("pendingRelease.release()", l.indexOf("if (pendingRelease != null) {")) > 0,
                    "and so does the burst one");

            Path plan = Paths.get("docs/plans/BIOME_PLAN.md");
            String planText = read(plan);
            check(!planText.contains("<<<<<<<") && !planText.contains(">>>>>>>"),
                    "BIOME_PLAN carries no unresolved merge markers - the r62 tree did");
        } catch (Exception e) {
            check(false, "the rebuild receipts could not be read: " + e);
        }

        receipt("fabric: sources + json gated (7 sources, entrypoint, mixins, java 21); "
                + "rebuild receipts pinned (VkDevice purge, drain before release, present hash); "
                + "windowed-frame gate NOT claimed here - needs JDK 21 + GPU (Phase 4)");
    }
}
