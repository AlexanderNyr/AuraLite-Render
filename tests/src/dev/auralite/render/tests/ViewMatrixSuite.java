package dev.auralite.render.tests;

import dev.auralite.render.scene.Camera;
import dev.auralite.render.scene.ViewMatrix;

import static dev.auralite.render.tests.TestRig.check;
import static dev.auralite.render.tests.TestRig.eq;
import static dev.auralite.render.tests.TestRig.near;
import static dev.auralite.render.tests.TestRig.negativeControl;
import static dev.auralite.render.tests.TestRig.section;
import static dev.auralite.render.tests.TestRig.suite;

/**
 * The frame-context view gate: the glue rebuilds Minecraft's model-view from
 * eye/yaw/pitch (the game's own matrices are GUI-owned by hook time), so the
 * rebuild must agree with Minecraft's look convention or every pack stage
 * that transforms a world point would be silently mirrored.
 */
public final class ViewMatrixSuite {

    public static void run() {
        suite("view");

        section("look agreement: view * (look * d) == (0, 0, -d)");
        float[] yaws = { 0f, 45f, 90f, 180f, 270f, -30f, 359f };
        float[] pitches = { -60f, -45f, -1f, 0f, 1f, 30f, 45f, 60f };
        int checked = 0;
        for (float yaw : yaws) {
            for (float pitch : pitches) {
                float[] view = new float[16];
                ViewMatrix.compute(view, 0, 0, 0, yaw, pitch);
                float[] look = ViewMatrix.lookDirection(yaw, pitch);
                float d = 10f;
                float[] p = Camera.transformPoint(view, look[0] * d, look[1] * d, look[2] * d);
                boolean ok = Math.abs(p[0]) < 1e-3f && Math.abs(p[1]) < 1e-3f
                        && Math.abs(p[2] + d) < 1e-3f;
                check(ok, "yaw=" + yaw + " pitch=" + pitch
                        + " look lands at (0,0,-" + d + "), got (" + p[0] + "," + p[1] + "," + p[2] + ")");
                checked++;
            }
        }
        eq(checked, yaws.length * pitches.length, "every angle pair was checked");

        section("polar pitch: straight up and down still face -Z");
        for (float pitch : new float[] { -90f, 90f }) {
            float[] view = new float[16];
            ViewMatrix.compute(view, 0, 0, 0, 123f, pitch);
            float[] look = ViewMatrix.lookDirection(123f, pitch);
            float[] p = Camera.transformPoint(view, look[0] * 10f, look[1] * 10f, look[2] * 10f);
            check(Math.abs(p[0]) < 1e-3f && Math.abs(p[1]) < 1e-3f && Math.abs(p[2] + 10f) < 1e-3f,
                    "pitch=" + pitch + " look maps to -Z, got (" + p[0] + "," + p[1] + "," + p[2] + ")");
        }

        section("eye translation: the eye lands on the camera origin");
        double[][] eyes = { { 0, 0, 0 }, { 3, -2, 7 }, { -128.5, 64.25, 384.125 } };
        for (double[] eye : eyes) {
            float[] view = new float[16];
            ViewMatrix.compute(view, eye[0], eye[1], eye[2], 40f, -20f);
            float[] p = Camera.transformPoint(view, (float) eye[0], (float) eye[1], (float) eye[2]);
            check(Math.abs(p[0]) < 1e-3f && Math.abs(p[1]) < 1e-3f && Math.abs(p[2]) < 1e-3f,
                    "eye (" + eye[0] + "," + eye[1] + "," + eye[2] + ") maps to origin, got ("
                            + p[0] + "," + p[1] + "," + p[2] + ")");
        }

        section("rotation part is orthonormal with det +1 (no mirror, no scale)");
        float[] view = new float[16];
        ViewMatrix.compute(view, 5, 6, 7, 210f, -35f);
        float[] c0 = { view[0], view[1], view[2] };
        float[] c1 = { view[4], view[5], view[6] };
        float[] c2 = { view[8], view[9], view[10] };
        near(Math.sqrt(dot(c0, c0)), 1.0, 1e-5, "column 0 is unit length");
        near(Math.sqrt(dot(c1, c1)), 1.0, 1e-5, "column 1 is unit length");
        near(Math.sqrt(dot(c2, c2)), 1.0, 1e-5, "column 2 is unit length");
        near(dot(c0, c1), 0.0, 1e-5, "columns 0 and 1 are orthogonal");
        near(dot(c0, c2), 0.0, 1e-5, "columns 0 and 2 are orthogonal");
        near(dot(c1, c2), 0.0, 1e-5, "columns 1 and 2 are orthogonal");
        float det3 = c0[0] * (c1[1] * c2[2] - c1[2] * c2[1])
                - c0[1] * (c1[0] * c2[2] - c1[2] * c2[0])
                + c0[2] * (c1[0] * c2[1] - c1[1] * c2[0]);
        near(det3, 1.0, 1e-5, "rotation determinant is +1 (handedness preserved)");

        negativeControl("a pitch-sign flip fails the look gate", new Runnable() {
            @Override public void run() {
                // Same math as ViewMatrix.compute but with the pitch sign flipped:
                // the agreement check above must reject it - that is the gate.
                float yaw = 40f, pitch = 30f;
                float[] wrong = new float[16];
                ViewMatrix.compute(wrong, 0, 0, 0, yaw, -pitch); // flipped on purpose
                float[] look = ViewMatrix.lookDirection(yaw, pitch);
                float[] p = Camera.transformPoint(wrong, look[0] * 10f, look[1] * 10f, look[2] * 10f);
                boolean agrees = Math.abs(p[0]) < 1e-3f && Math.abs(p[1]) < 1e-3f
                        && Math.abs(p[2] + 10f) < 1e-3f;
                check(agrees, "flipped view must NOT agree with the look direction");
            }
        });
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private ViewMatrixSuite() { }
}
