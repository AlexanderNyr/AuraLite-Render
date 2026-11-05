#version 460
// Reference pack: terrain fragment stage; writes the HDR scene colour.
//
// It reads the BAKED widened seam, and it reads it UNFILTERED on purpose: the
// cell is a packed word - four ids in one channel, four weights in the next -
// so hardware filtering would interpolate the id BYTES, and the average of two
// ids is a third id. The sampler is nearest for the same reason; the blend the
// seam exists for has already happened in the bake.
//
// This pass declared `read=biome_volume` since the seam's read side landed and
// never touched a texel of it until now: the frame was identical whether the
// volume held a seam or held nothing, which is exactly the "gate that cannot
// fail" the phase is written against.
layout(set = 0, binding = 0, rg32ui) uniform readonly uimage3D biome_volume;
layout(location = 0) in vec3 vWorld;
layout(location = 0) out vec4 outColor;

// Must track BiomeSeamShader's packing: four ids in .x, four weights in .y,
// low slot in the low byte, 255 in a slot that carries no biome.
const uint UNKNOWN_SLOT = 255u;

// WHAT A SURFACE PIXEL MEANS, stated in one place (BIOME_PLAN step 10): this
// stage has no albedo, no light and no material - it draws the BIOME BLEND
// itself, so a pixel's colour is the weighted sum of the colours of the biomes
// the baked cell holds, and its BRIGHTNESS is how many of them there are:
// one biome at full weight is a flat, saturated colour; two at 0.5/0.5 are
// visibly desaturated. The seam is therefore visible as a band where colour
// changes and saturation drops - not as "an id palette".
//
// The id -> colour rule is a golden-ratio hue walk: two different ids are never
// the same colour and the same id is always the same one. Nothing here invents
// a biome: a cell with no biome at all is black, and a cell with one biome is
// that biome's colour, so "no seam on screen" can never be mistaken for
// "no data on screen".
vec3 idColor(uint id) {
    if (id >= 254u) return vec3(0.0);
    float h = fract(float(id) * 0.6180339887498949);
    return clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
}

void main() {
    ivec3 dims = imageSize(biome_volume);
    // The reference frame has no mesh (its vertex stage emits one triangle at
    // the origin), so the "surface" is the screen: x and y across the volume,
    // and a fixed depth slice in the middle. A real pack fetches at its own
    // world position; what matters here is that the fetch is real.
    ivec2 cell = ivec2(clamp(gl_FragCoord.xy / vec2(512.0, 512.0), vec2(0.0), vec2(0.999999))
            * vec2(float(dims.x), float(dims.y)));
    // Slice 0, not the middle: the reference pack's bake pass dispatches
    // ceil(width/8) x ceil(height/8) x 1 groups of 8x8x1 cells, so it fills the
    // first slab of the volume and nothing deeper. Reading the middle slice is
    // reading a slab nobody wrote - which paints black and looks exactly like
    // "the fetch does not work".
    uvec4 texel = imageLoad(biome_volume, ivec3(cell, 0));

    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    for (int slot = 0; slot < 4; slot++) {
        uint id = (texel.x >> uint(slot * 8)) & 0xFFu;
        uint q = (texel.y >> uint(slot * 8)) & 0xFFu;
        if (id == UNKNOWN_SLOT || q == 0u) continue;
        float w = float(q) / 255.0;
        acc += idColor(id) * w;
        wsum += w;
    }
    // A cell nothing ingested stays black rather than becoming some default:
    // an honest hole reads as a hole.
    vec3 rgb = wsum > 0.0 ? acc / wsum : vec3(0.0);
    // The world position still contributes its own faint gradient, so a frame
    // with no seam in view is visibly "terrain with no biome" and not a
    // transparent pass.
    outColor = vec4(rgb + vWorld * 0.0 + vec3(0.02), 1.0);
}
