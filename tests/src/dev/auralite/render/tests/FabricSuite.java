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
            contains(read(root.resolve("build.gradle")), "zipTree(\"../third_party/",
                    "the gradle lane reads compile-only LWJGL from the host's ../third_party jars");
            contains(read(root.resolve("build.gradle")), "strippedLwjgl",
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

        receipt("fabric: sources + json gated (7 sources, entrypoint, mixins, java 21); "
                + "windowed-frame gate NOT claimed here - needs JDK 21 + GPU (Phase 4)");
    }
}
