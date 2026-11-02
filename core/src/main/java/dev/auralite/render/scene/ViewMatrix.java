package dev.auralite.render.scene;

/**
 * The camera's model-view matrix, derived from eye + yaw/pitch.
 *
 * <p>The glue cannot sample Minecraft's own GL matrices: by the time the frame
 * hook runs, GameRenderer has already replaced them with the GUI's ortho pair
 * (verified against the 1.21.1 bytecode - setProjectionMatrix is called twice
 * per frame, second time with setOrtho). So the view is rebuilt here from the
 * same camera the game renders with, using Minecraft's convention:
 * {@code look = (-sin(yaw)cos(pitch), sin(pitch), cos(yaw)cos(pitch))} - yaw 0
 * faces +Z, pitch + is up. {@link #compute} builds
 * {@code Rx(-pitch) * Ry(yaw + 180) * T(-eye)}, which maps that look direction
 * onto the GL camera axis (0,0,-1); {@code ViewMatrixSuite} asserts exactly
 * that, so a sign slip fails the host gate instead of shipping a mirrored
 * world. View bob and hurt tilt are deliberately not modelled here - they are
 * ephemeral screen effects, and a pack that needs the raw camera transform
 * should get it, not the wobble.
 */
public final class ViewMatrix {

    private ViewMatrix() { }

    /**
     * Writes a column-major 4x4 view matrix into {@code dst} (16 floats).
     * {@code yawDeg}/{@code pitchDeg} are Minecraft camera degrees.
     */
    public static void compute(float[] dst, double eyeX, double eyeY, double eyeZ,
                               float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg + 180.0);
        double pitch = Math.toRadians(-pitchDeg);
        float cy = (float) Math.cos(yaw), sy = (float) Math.sin(yaw);
        float cp = (float) Math.cos(pitch), sp = (float) Math.sin(pitch);

        // Ry(yaw + 180): column-major
        float[] ry = { cy, 0, -sy, 0,
                       0, 1, 0, 0,
                       sy, 0, cy, 0,
                       0, 0, 0, 1 };
        // Rx(-pitch): column-major
        float[] rx = { 1, 0, 0, 0,
                       0, cp, sp, 0,
                       0, -sp, cp, 0,
                       0, 0, 0, 1 };
        // T(-eye)
        float[] tr = { 1, 0, 0, 0,
                       0, 1, 0, 0,
                       0, 0, 1, 0,
                       (float) -eyeX, (float) -eyeY, (float) -eyeZ, 1 };

        float[] tmp = new float[16];
        FrameContext.mul(rx, ry, tmp);   // Rx * Ry
        FrameContext.mul(tmp, tr, dst);  // (Rx * Ry) * T
    }

    /** Minecraft's look direction for the given camera angles, unit length. */
    public static float[] lookDirection(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        float cy = (float) Math.cos(pitch);
        return new float[] {
                (float) (-Math.sin(yaw) * cy),
                (float) Math.sin(pitch),
                (float) (Math.cos(yaw) * cy),
                0f
        };
    }
}
