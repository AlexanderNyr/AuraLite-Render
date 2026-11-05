#version 460
// Reference pack: present pass; writes the imported backbuffer.
//
// It reads what the graph says it reads - BOTH of them. Upstream this stage
// drew a gradient of the screen position, which is how a three-pass chain
// produces a fixed output no matter what the terrain pass drew: the receipts
// were green, the passes ran in order, and the pixels were a function of
// nothing. It now samples the resolved frame (taa_cur) and the host's captured
// vanilla scene, and the DEBUG_VIEW const option (a #define from the host's
// option lane) chooses which of the two is shown and how:
//   0 = the unresolved capture as-is, 1 = luma, 2 = rgb split, 3 = 64px grid.
// With no debug view selected the frame is the pack's own resolve, because
// that is what "the pack draws the world" means.
layout(set = 0, binding = 0) uniform sampler2D taa_cur;
layout(set = 0, binding = 1) uniform sampler2D scene;
layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 resolved = texelFetch(taa_cur, p, 0);
#if DEBUG_VIEW >= 1 && DEBUG_VIEW <= 3
    // The capture is an imported resource: its extent is the frame's, and a
    // fetch outside it is undefined, so clamp to what the image actually has.
    ivec2 sz = max(textureSize(scene, 0), ivec2(1));
    vec4 c = texelFetch(scene, clamp(p, ivec2(0), sz - 1), 0);
#endif
#if DEBUG_VIEW == 1
    float l = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
    outColor = vec4(vec3(l), 1.0);
#elif DEBUG_VIEW == 2
    outColor = vec4(c.r, c.g * 0.15, c.b * 0.15, 1.0);
#elif DEBUG_VIEW == 3
    vec2 g = fract(gl_FragCoord.xy / 64.0);
    float line = step(g.x, 0.02) + step(g.y, 0.02);
    outColor = mix(c, vec4(1.0, 0.4, 0.0, 1.0), clamp(line, 0.0, 1.0));
#else
    outColor = vec4(resolved.rgb, 1.0);
#endif
}
