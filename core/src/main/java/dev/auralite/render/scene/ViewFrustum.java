package dev.auralite.render.scene;

/**
 * Six-plane frustum in world space, extracted from the view-projection matrix.
 *
 * <p>Used for two things the scalar Iris uniforms cannot do: culling the biome
 * field down to what is actually on screen, and giving pack code a
 * {@code auralite_frustonPlane(i)} that is correct for the shadow and cloud
 * passes rather than only for the terrain pass.
 */
public final class ViewFrustum {
    /** Plane storage: 6 planes x (a,b,c,d) with ax+by+cz+d = 0, normal pointing in. */
    public final float[] planes = new float[24];

    public static final int LEFT = 0, RIGHT = 1, BOTTOM = 2, TOP = 3, NEAR = 4, FAR = 5;

    /** Gribb-Hartmann extraction from a column-major view-projection matrix. */
    public void fromViewProjection(float[] m) {
        setPlane(LEFT,   m[3] + m[0], m[7] + m[4], m[11] + m[8],  m[15] + m[12]);
        setPlane(RIGHT,  m[3] - m[0], m[7] - m[4], m[11] - m[8],  m[15] - m[12]);
        setPlane(BOTTOM, m[3] + m[1], m[7] + m[5], m[11] + m[9],  m[15] + m[13]);
        setPlane(TOP,    m[3] - m[1], m[7] - m[5], m[11] - m[9],  m[15] - m[13]);
        setPlane(NEAR,   m[3] + m[2], m[7] + m[6], m[11] + m[10], m[15] + m[14]);
        setPlane(FAR,    m[3] - m[2], m[7] - m[6], m[11] - m[10], m[15] - m[14]);
        for (int i = 0; i < 6; i++) normalize(i);
    }

    private void setPlane(int i, float a, float b, float c, float d) {
        planes[i * 4] = a; planes[i * 4 + 1] = b; planes[i * 4 + 2] = c; planes[i * 4 + 3] = d;
    }

    private void normalize(int i) {
        float a = planes[i * 4], b = planes[i * 4 + 1], c = planes[i * 4 + 2];
        float len = (float) Math.sqrt(a * a + b * b + c * c);
        if (len == 0f) return;
        planes[i * 4] /= len; planes[i * 4 + 1] /= len;
        planes[i * 4 + 2] /= len; planes[i * 4 + 3] /= len;
    }

    /** Signed distance from a point to plane {@code i}; positive is inside. */
    public float distanceToPlane(int i, float x, float y, float z) {
        return planes[i * 4] * x + planes[i * 4 + 1] * y + planes[i * 4 + 2] * z + planes[i * 4 + 3];
    }

    public boolean containsPoint(float x, float y, float z) {
        for (int i = 0; i < 6; i++) if (distanceToPlane(i, x, y, z) < 0) return false;
        return true;
    }

    /** Conservative sphere test; used for chunk-section culling of the biome field. */
    public boolean intersectsSphere(float cx, float cy, float cz, float r) {
        for (int i = 0; i < 6; i++) if (distanceToPlane(i, cx, cy, cz) < -r) return false;
        return true;
    }

    /** Conservative AABB test using the p/n vertex trick. */
    public boolean intersectsBox(float minX, float minY, float minZ,
                                 float maxX, float maxY, float maxZ) {
        for (int i = 0; i < 6; i++) {
            float a = planes[i * 4], b = planes[i * 4 + 1], c = planes[i * 4 + 2], d = planes[i * 4 + 3];
            float px = a >= 0 ? maxX : minX;
            float py = b >= 0 ? maxY : minY;
            float pz = c >= 0 ? maxZ : minZ;
            if (a * px + b * py + c * pz + d < 0) return false;
        }
        return true;
    }

    public float[] plane(int i) {
        return new float[] { planes[i * 4], planes[i * 4 + 1], planes[i * 4 + 2], planes[i * 4 + 3] };
    }
}
