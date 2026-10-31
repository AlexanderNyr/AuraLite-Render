#version 460
// Reference pack: depth-only shadow pass, vertex stage.
layout(location = 0) in vec3 inPos;
void main() { gl_Position = vec4(inPos, 1.0); }
