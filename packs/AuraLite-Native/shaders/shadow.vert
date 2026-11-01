#version 460
// Reference pack: depth-only shadow pass, vertex stage. The reference frame
// has no mesh yet; the pass draws the pack's placeholder position, written
// as the constant origin point so the frame no longer depends on
// VK_EXT_vertex_attribute_robustness (lavapipe cannot offer it).
void main() { gl_Position = vec4(0.0, 0.0, 0.0, 1.0); }
