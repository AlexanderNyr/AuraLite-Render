#version 460
// Reference pack: shadow pass fragment stage (present so the program lints clean).
layout(location = 0) out float shadowDepth;
void main() { shadowDepth = gl_FragCoord.z; }
