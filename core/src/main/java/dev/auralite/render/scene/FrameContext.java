package dev.auralite.render.scene;

import java.util.EnumSet;
import java.util.Set;

/**
 * Everything one frame knows about the world, collected once and shared by
 * every pass.
 *
 * <p>This is the structure that replaces Iris's flat list of ~90 individual
 * uniforms. Packs see it as a small set of uniform blocks instead of a wall of
 * scalar locations, which is what lets the same pack run unchanged on a
 * descriptor-set backend (Vulkan) and a plain GL uniform backend.
 */
public final class FrameContext {
    public enum Weather { CLEAR, RAIN, THUNDER }
    public enum DimensionKind { OVERWORLD, NETHER, END, CUSTOM }

    private long frameId;
    private float frameTimeSeconds;
    private float frameTimeDeltaSeconds;
    private long frameTimeNanos;

    public final Camera camera = new Camera();
    public final ViewFrustum frustum = new ViewFrustum();
    public final ViewFrustum previousFrustum = new ViewFrustum();
    public final float[] viewProjection = new float[16];
    public final float[] previousViewProjection = new float[16];

    public Weather weather = Weather.CLEAR;
    public DimensionKind dimension = DimensionKind.OVERWORLD;
    public float wetness;
    public float rainStrength;
    public float thunderStrength;
    public int worldTime;
    public int worldDay;
    public float sunAngle;
    public int moonPhase;
    public boolean isNight;
    public float eyeAltitude;
    public float cloudHeight = 192.0f;
    public int renderDistanceChunks = 12;
    public float shadowDistanceBlocks = 128.0f;
    public boolean hasSkylight = true;
    public boolean hasCeiling;
    public float ambientLight;
    public int bedrockLevel = -64;
    public int heightLimit = 384;
    public int logicalHeightLimit = 384;
    public boolean hideGui;
    public boolean isSpectator;
    public float playerMood;
    public float screenBrightness = -1.0f;
    public int eyeInWater;
    public boolean eyeInCave;
    public int heldItemId;
    public int heldItemId2;
    public int heldBlockLightValue;
    public int heldBlockLightValue2;
    public boolean isSprinting;
    public boolean isOnGround;
    /** Enabled pack options, resolved once per frame from the option table. */
    public final Set<String> enabledOptions = new java.util.LinkedHashSet<String>();
    /** Capabilities the running device actually reported. */
    public final Set<String> deviceCapabilities = EnumSet.noneOf(Placeholder.class).isEmpty()
            ? new java.util.LinkedHashSet<String>() : new java.util.LinkedHashSet<String>();

    /** Exists only so the capability set has an honest, non-magic declaration. */
    private enum Placeholder { UNUSED }

    public long frameId() { return frameId; }
    public float frameTimeSeconds() { return frameTimeSeconds; }
    public float frameDeltaSeconds() { return frameTimeDeltaSeconds; }
    public long frameTimeNanos() { return frameTimeNanos; }

    public void beginFrame(long id, float totalSeconds, float deltaSeconds, long nanos) {
        this.frameId = id;
        this.frameTimeSeconds = totalSeconds;
        this.frameTimeDeltaSeconds = deltaSeconds;
        this.frameTimeNanos = nanos;
        camera.previousEyeX = camera.eyeX;
        camera.previousEyeY = camera.eyeY;
        camera.previousEyeZ = camera.eyeZ;
        System.arraycopy(camera.modelView, 0, camera.previousModelView, 0, 16);
        System.arraycopy(camera.projection, 0, camera.previousProjection, 0, 16);
        System.arraycopy(viewProjection, 0, previousViewProjection, 0, 16);
        System.arraycopy(frustum.planes, 0, previousFrustum.planes, 0, 24);
    }

    /** Recomputes the derived matrices. Called once per frame after the glue fills them in. */
    public void finishCameraUpdate() {
        camera.updateInverses();
        mul(camera.projection, camera.modelView, viewProjection);
        frustum.fromViewProjection(viewProjection);
        mul(camera.previousProjection, camera.previousModelView, previousViewProjection);
        previousFrustum.fromViewProjection(previousViewProjection);
    }

    /** Column-major 4x4 multiply, {@code out = a * b}. */
    public static void mul(float[] a, float[] b, float[] out) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                float s = 0f;
                for (int k = 0; k < 4; k++) s += a[k * 4 + r] * b[c * 4 + k];
                out[c * 4 + r] = s;
            }
        }
    }

    /** True when the two view-projection matrices agree, which the reprojection gate checks. */
    public boolean previousFrameIsUsable() {
        for (int i = 0; i < 16; i++) {
            if (Float.isNaN(previousViewProjection[i])) return false;
        }
        return frameId > 0;
    }
}
