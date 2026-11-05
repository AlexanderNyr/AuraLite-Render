#version 460
// Seam probe: the raster stage writes the whole quad from gl_VertexIndex, the
// convention every reference-pack vertex stage follows, and hands the fragment
// stage the position in cell space.
layout(location = 0) out vec2 vPos;
void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    vPos = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
