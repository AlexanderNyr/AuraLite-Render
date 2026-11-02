package dev.auralite.render.fabric;

import dev.auralite.render.scene.Camera;
import dev.auralite.render.scene.FrameContext;
import dev.auralite.render.scene.ViewMatrix;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.joml.Matrix4f;

/**
 * Minecraft &rarr; {@link FrameContext}: everything one frame knows about the
 * world, collected once on the render thread before the graph executes.
 *
 * <p>Two sources, both deliberate. The projection is Minecraft's own
 * {@link GameRenderer#getBasicProjectionMatrix(double)} (bytecode-verified:
 * {@code perspective(rad(fov), fbAspect, 0.05, far)}), so the pack sees the
 * frustum the game actually rendered with. The view matrix cannot be sampled
 * from {@code RenderSystem} at hook time - by then GameRenderer has replaced
 * it with the GUI's ortho pair - so it is rebuilt from the same Camera by
 * {@link ViewMatrix}, whose host suite pins it to Minecraft's look convention.
 * View bob and hurt tilt are intentionally absent (see ViewMatrix javadoc).
 *
 * <p>World fields (time, weather, dimension, ...) are read straight from
 * {@code client.world} and skipped entirely on the main menu, where there is
 * no world and the previous frame's values stay honest rather than being
 * zeroed. {@code hasSkylight} and the height-limit fields are NOT bridged yet
 * - a wrong value is worse than a stale default until a receipt says otherwise.
 */
public final class FrameBridge {

    private static boolean receiptLogged;

    private FrameBridge() { }

    /** Fills {@code f} for this frame; call after {@code f.beginFrame(...)}. */
    static void fill(FrameContext f, MinecraftClient client, int fbWidth, int fbHeight) {
        if (client == null) return;
        GameRenderer renderer = client.gameRenderer;
        net.minecraft.client.render.Camera mcCamera =
                renderer != null ? renderer.getCamera() : null;

        if (mcCamera != null) {
            Vec3d eye = mcCamera.getPos();
            f.camera.eyeX = eye.x;
            f.camera.eyeY = eye.y;
            f.camera.eyeZ = eye.z;

            int fovDeg = client.options != null && client.options.getFov() != null
                    ? client.options.getFov().getValue() : 70;
            f.camera.fovDegrees = fovDeg;
            Matrix4f proj = renderer.getBasicProjectionMatrix(fovDeg);
            // JOML has no get(int); column-major index i is (col = i/4, row = i%4).
            for (int i = 0; i < 16; i++) f.camera.projection[i] = proj.get(i / 4, i % 4);

            ViewMatrix.compute(f.camera.modelView, eye.x, eye.y, eye.z,
                    mcCamera.getYaw(), mcCamera.getPitch());

            f.camera.aspect = fbHeight > 0 ? (float) fbWidth / (float) fbHeight : (16f / 9f);
            f.camera.viewWidth = fbWidth;
            f.camera.viewHeight = fbHeight;
            f.camera.nearPlane = 0.05f;   // matches getBasicProjectionMatrix
            f.eyeAltitude = (float) eye.y;
        }

        World world = client.world;
        if (world != null) {
            f.worldTime = (int) world.getTime();
            long timeOfDay = world.getTimeOfDay();
            f.worldDay = (int) (timeOfDay / 24000L);
            int dayFraction = (int) Math.floorMod(timeOfDay, 24000L);
            // 0 = sunrise (MC time 0), 0.5 = midnight's opposite... 0..1 over
            // the full day; the day/night flag uses MC's own thresholds.
            f.sunAngle = dayFraction / 24000f;
            f.isNight = dayFraction >= 13000 && dayFraction < 23000;
            // MC has no moon-phase getter in 1.21.1 (bytecode-checked); the
            // game derives the phase from the day index the same way.
            f.moonPhase = (int) Math.floorMod(timeOfDay / 24000L, 8L);

            f.rainStrength = world.getRainGradient(1.0f);
            f.thunderStrength = world.getThunderGradient(1.0f);
            f.wetness = f.rainStrength;
            f.weather = world.isThundering() ? FrameContext.Weather.THUNDER
                    : world.isRaining() ? FrameContext.Weather.RAIN
                    : FrameContext.Weather.CLEAR;

            if (World.OVERWORLD.equals(world.getRegistryKey())) {
                f.dimension = FrameContext.DimensionKind.OVERWORLD;
            } else if (World.NETHER.equals(world.getRegistryKey())) {
                f.dimension = FrameContext.DimensionKind.NETHER;
            } else if (World.END.equals(world.getRegistryKey())) {
                f.dimension = FrameContext.DimensionKind.END;
            } else {
                f.dimension = FrameContext.DimensionKind.CUSTOM;
            }

            if (client.options != null && client.options.getViewDistance() != null) {
                f.renderDistanceChunks = client.options.getViewDistance().getValue();
            }
        }

        if (client.options != null) {
            f.hideGui = client.options.hudHidden;
        }

        f.finishCameraUpdate();

        if (!receiptLogged && mcCamera != null) {
            receiptLogged = true;
            AuraLiteRenderMod.LOGGER.info(
                    "framectx: eye=({},{},{}) yaw={} pitch={} fov={} | time={} tod={} day={} "
                            + "sun={} night={} moon={} weather={} dim={} rd={} hideGui={} | "
                            + "proj[0]={} view[0]={} viewProjection[0]={}",
                    f.camera.eyeX, f.camera.eyeY, f.camera.eyeZ,
                    mcCamera.getYaw(), mcCamera.getPitch(), f.camera.fovDegrees,
                    f.worldTime, world != null ? world.getTimeOfDay() : -1, f.worldDay,
                    f.sunAngle, f.isNight, f.moonPhase, f.weather, f.dimension,
                    f.renderDistanceChunks, f.hideGui,
                    f.camera.projection[0], f.camera.modelView[0], f.viewProjection[0]);
        }
    }
}
