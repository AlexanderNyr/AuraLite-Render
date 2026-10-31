package dev.auralite.render.scene;

/**
 * One camera's transform for one frame.
 *
 * <p>Two matrices are carried, current and previous, because reprojection is
 * not optional in this renderer: TAA, motion-blur-free temporal accumulation
 * and the biome field's temporal smoothing all need last frame's transform.
 * Iris supplies {@code gbufferPreviousModelView} but nothing guarantees it is
 * the transform that actually produced the history buffer; here both come from
 * the same {@link FrameContext} and are asserted consistent by a test gate.
 */
public final class Camera {
    /** Column-major 4x4, the layout both GLSL and SPIR-V expect. */
    public final float[] modelView = identity();
    public final float[] projection = identity();
    public final float[] previousModelView = identity();
    public final float[] previousProjection = identity();
    public final float[] modelViewInverse = identity();
    public final float[] projectionInverse = identity();

    public double eyeX, eyeY, eyeZ;
    public double previousEyeX, previousEyeY, previousEyeZ;
    public float fovDegrees = 70.0f;
    public float nearPlane = 0.05f;
    public float farPlane = 1024.0f;
    public float aspect = 16.0f / 9.0f;
    /** Sub-pixel jitter applied for TAA, zero when jitter is disabled. */
    public float jitterX, jitterY;
    public int viewWidth = 1920, viewHeight = 1080;

    public static float[] identity() {
        return new float[] { 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1 };
    }

    public void setModelView(float[] m) { System.arraycopy(m, 0, modelView, 0, 16); }
    public void setProjection(float[] m) { System.arraycopy(m, 0, projection, 0, 16); }
    public void setPreviousModelView(float[] m) { System.arraycopy(m, 0, previousModelView, 0, 16); }
    public void setPreviousProjection(float[] m) { System.arraycopy(m, 0, previousProjection, 0, 16); }

    /** Fills {@code modelViewInverse} and {@code projectionInverse} in place. */
    public void updateInverses() {
        invert(modelView, modelViewInverse);
        invert(projection, projectionInverse);
    }

    /** 4x4 column-major inverse; returns false when singular and leaves dst untouched. */
    public static boolean invert(float[] m, float[] dst) {
        float a00 = m[0], a01 = m[1], a02 = m[2], a03 = m[3];
        float a10 = m[4], a11 = m[5], a12 = m[6], a13 = m[7];
        float a20 = m[8], a21 = m[9], a22 = m[10], a23 = m[11];
        float a30 = m[12], a31 = m[13], a32 = m[14], a33 = m[15];

        float b00 = a00 * a11 - a01 * a10;
        float b01 = a00 * a12 - a02 * a10;
        float b02 = a00 * a13 - a03 * a10;
        float b03 = a01 * a12 - a02 * a11;
        float b04 = a01 * a13 - a03 * a11;
        float b05 = a02 * a13 - a03 * a12;
        float b06 = a20 * a31 - a21 * a30;
        float b07 = a20 * a32 - a22 * a30;
        float b08 = a20 * a33 - a23 * a30;
        float b09 = a21 * a32 - a22 * a31;
        float b10 = a21 * a33 - a23 * a31;
        float b11 = a22 * a33 - a23 * a32;

        float det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06;
        if (det == 0.0f) return false;
        float inv = 1.0f / det;

        dst[0] = (a11 * b11 - a12 * b10 + a13 * b09) * inv;
        dst[1] = (a02 * b10 - a01 * b11 - a03 * b09) * inv;
        dst[2] = (a31 * b05 - a32 * b04 + a33 * b03) * inv;
        dst[3] = (a22 * b04 - a21 * b05 - a23 * b03) * inv;
        dst[4] = (a12 * b08 - a10 * b11 - a13 * b07) * inv;
        dst[5] = (a00 * b11 - a02 * b08 + a03 * b07) * inv;
        dst[6] = (a32 * b02 - a30 * b05 - a33 * b01) * inv;
        dst[7] = (a20 * b05 - a22 * b02 + a23 * b01) * inv;
        dst[8] = (a10 * b10 - a11 * b08 + a13 * b06) * inv;
        dst[9] = (a01 * b08 - a00 * b10 - a03 * b06) * inv;
        dst[10] = (a30 * b04 - a31 * b02 + a33 * b00) * inv;
        dst[11] = (a21 * b02 - a20 * b04 - a23 * b00) * inv;
        dst[12] = (a11 * b07 - a10 * b09 - a12 * b06) * inv;
        dst[13] = (a00 * b09 - a01 * b07 + a02 * b06) * inv;
        dst[14] = (a31 * b01 - a30 * b03 - a32 * b00) * inv;
        dst[15] = (a20 * b03 - a21 * b01 + a22 * b00) * inv;
        return true;
    }

    /** Transforms a point by a column-major matrix, applying the perspective divide. */
    public static float[] transformPoint(float[] m, float x, float y, float z) {
        float ox = m[0] * x + m[4] * y + m[8] * z + m[12];
        float oy = m[1] * x + m[5] * y + m[9] * z + m[13];
        float oz = m[2] * x + m[6] * y + m[10] * z + m[14];
        float ow = m[3] * x + m[7] * y + m[11] * z + m[15];
        if (ow != 0.0f) { ox /= ow; oy /= ow; oz /= ow; }
        return new float[] { ox, oy, oz, ow };
    }
}
