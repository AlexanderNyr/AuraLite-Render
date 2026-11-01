#version 460
// Reference pack: terrain vertex stage; the real pack samples biome_volume
// here. The reference frame has no mesh yet: no vertex inputs, the same
// constant origin placeholder as shadow.vert (written literally so the
// frame no longer depends on VK_EXT_vertex_attribute_robustness).
layout(location = 0) out vec3 vWorld;
void main() {
    vWorld = vec3(0.0);
    gl_Position = vec4(0.0, 0.0, 0.0, 1.0);
}
