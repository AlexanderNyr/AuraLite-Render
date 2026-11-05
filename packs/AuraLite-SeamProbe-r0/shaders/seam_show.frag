#version 460
// Seam probe: paint the baked widened seam.
//
// This is the pixel gate for BIOME_PLAN B5. It reads the volume as an
// UNFILTERED storage image and interpolates the unpacked entries itself - the
// same contract BiomeSeamSampler states in core. Hardware filtering would
// interpolate the packed id bytes, and the average of two ids is a third id.
//
// Colours are derived from the id directly, so a blended cell shows a blend of
// two colours and an unblended one shows a flat colour: widening the seam moves
// pixels, and that is all this pack exists to demonstrate.
layout(set = 0, binding = 0, rg32ui) uniform readonly uimage3D biome_volume;
layout(location = 0) in vec2 vPos;
layout(location = 0) out vec4 outColor;

// Must track BiomeSeamShader's packing: four ids in .x, four weights in .y,
// low slot in the low byte, 255 in an unused slot.
const uint UNKNOWN_SLOT = 255u;

vec3 idColor(uint id) {
    if (id >= 254u) return vec3(0.0);           // no biome: black, never invented
    // Deterministic, well-spread palette: hue from a golden-ratio walk.
    float h = fract(float(id) * 0.6180339887498949);
    return clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
}

void main() {
    ivec3 dims = imageSize(biome_volume);
    // RHI scissors and framebuffers have a top-left origin on every backend
    // (the Vulkan viewport is flipped to compensate), so gl_FragCoord.y grows
    // DOWNWARD. Cell space grows upward, so the mapping has to invert it - a
    // probe that forgot this painted the bottom of the volume on the top of the
    // screen, which reads as "the seam is where it should not be".
    vec2 screen = vec2(gl_FragCoord.x, float(512.0) - gl_FragCoord.y);
    vec2 uv = clamp(screen / vec2(512.0, 512.0), vec2(0.0), vec2(0.999999));

    // Cell space: cell c covers [c, c+1), centre c+0.5 -- so minus 0.5 to
    // interpolate between centres, exactly as BiomeSeamSampler does.
    vec3 f = vec3(uv * vec2(float(dims.x), float(dims.y)), float(dims.z) * 0.5) - vec3(0.5);
    ivec3 c0 = ivec3(floor(f));
    vec3 t = f - floor(f);

    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    for (int k = 0; k < 8; k++) {
        ivec3 o = ivec3(k & 1, (k >> 1) & 1, (k >> 2) & 1);
        vec3 cw = mix(vec3(1.0) - t, t, vec3(o));
        float corner = cw.x * cw.y * cw.z;
        if (corner <= 0.0) continue;
        ivec3 c = c0 + o;
        if (any(lessThan(c, ivec3(0))) || any(greaterThanEqual(c, dims))) continue;
        uvec2 texel = imageLoad(biome_volume, c).xy;
        for (int slot = 0; slot < 4; slot++) {
            uint id = (texel.x >> uint(slot * 8)) & 0xFFu;
            uint q = (texel.y >> uint(slot * 8)) & 0xFFu;
            if (id == UNKNOWN_SLOT || q == 0u) continue;
            float w = (float(q) / 255.0) * corner;
            acc += idColor(id) * w;
            wsum += w;
        }
    }
    vec3 rgb = wsum > 0.0 ? acc / wsum : vec3(0.0);
    outColor = vec4(rgb, 1.0);
}
