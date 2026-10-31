#version 460
// Reference pack: terrain vertex stage; the real pack samples biome_volume here.
layout(location = 0) in vec3 inPos;
layout(location = 0) out vec3 vWorld;
void main() { vWorld = inPos; gl_Position = vec4(inPos, 1.0); }
