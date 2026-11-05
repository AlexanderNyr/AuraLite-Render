#version 460
// Reference pack: terrain vertex stage.
//
// The reference frame still has no mesh, but this stage must COVER something:
// its predecessor emitted one triangle with all three vertices at the origin,
// which is a zero-area triangle and therefore not a single fragment - so the
// terrain pass drew nothing at all, and every downstream pass resolved an empty
// scene. That looked exactly like "the chain is wired and the pixels are a
// gradient". The fullscreen-triangle convention (the same one final.vert and
// taa.vert use) makes the pass real: it writes every pixel of its area, and a
// change in this stage is visible at the end of the chain, which is what the
// step-8 gate needs.
layout(location = 0) out vec3 vWorld;
void main() {
    vec2 uv = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    vWorld = vec3(uv, 0.0);
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
