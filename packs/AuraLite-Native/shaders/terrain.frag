#version 460
// Reference pack: terrain fragment stage; writes the HDR scene colour.
layout(location = 0) in vec3 vWorld;
layout(location = 0) out vec4 outColor;
void main() { outColor = vec4(vWorld, 1.0); }
