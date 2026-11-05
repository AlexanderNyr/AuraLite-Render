#version 460
// Reference pack: TAA resolve. Reads the scene it is asked to resolve and the
// ambient occlusion the previous pass wrote.
//
// This stage used to write `vUv` and ignore both inputs, which made the frame
// a gradient no matter what any earlier pass did - the reason a red terrain
// pass could not move the output pixel. A resolve stage that does not read its
// input is not a resolve, it is a screensaver with a plausible name.
layout(set = 0, binding = 0) uniform sampler2D scene_color;
layout(set = 0, binding = 1, r16f) uniform readonly image2D ao;
layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 scene = texelFetch(scene_color, p, 0).rgb;
    float occlusion = imageLoad(ao, ivec2(p / 2)).r;   // ao is half resolution
    outColor = vec4(scene * clamp(occlusion, 0.35, 1.0), 1.0);
}
