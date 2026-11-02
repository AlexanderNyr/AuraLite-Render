#version 460
// Reference pack: present pass; writes the imported backbuffer.
// Reads the host-captured vanilla frame (imported "scene") and displays it
// through the DEBUG_VIEW const option (#define from the host's option lane):
//   0 = scene as-is, 1 = luma, 2 = rgb split, 3 = 64px grid overlay.
layout(set = 0, binding = 0, rgba8) uniform readonly image2D scene;
layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    ivec2 sz = imageSize(scene);
    ivec2 p = clamp(ivec2(gl_FragCoord.xy), ivec2(0), max(sz, ivec2(1)) - 1);
    vec4 c = imageLoad(scene, p);
#if DEBUG_VIEW == 1
    float l = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
    c = vec4(vec3(l), 1.0);
#elif DEBUG_VIEW == 2
    c = vec4(c.r, c.g * 0.15, c.b * 0.15, 1.0);
#elif DEBUG_VIEW == 3
    vec2 g = fract(gl_FragCoord.xy / 64.0);
    float line = step(g.x, 0.02) + step(g.y, 0.02);
    c = mix(c, vec4(1.0, 0.4, 0.0, 1.0), clamp(line, 0.0, 1.0));
#endif
    outColor = c;
}
