#version 460
// Reference pack: TAA resolve reads scene_color, ao and the previous history side.
layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;
void main() { outColor = vec4(vUv, 0.0, 1.0); }
