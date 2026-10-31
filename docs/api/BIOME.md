# The biome field

`dev.auralite.render.biome`. Iris answers "which biome is this?" with one
integer sampled at the camera. AuraLite-Render answers with a **volume**: a
sliding 3D window of biome ids at Minecraft's own granularity of one id per
4x4x4 blocks, so a fragment 120 blocks away in a swamp asks the volume at its
own position and gets the swamp while the beach under the camera gets the
beach — same draw call, same frame. This is the type `BiomeSample` (up to four
ids with normalised weights) that Iris has no equivalent of.

## The field

`BiomeField` is a flat `int[]` window around the camera, not a sparse map,
because the access pattern is a strided 8-neighbourhood read at fragment
rate: flat is cache-friendly, branch-free, and copies to the GPU in one
sub-image upload. `UNKNOWN = -1` marks cells nothing has ingested yet.
The window follows the camera in **whole cells** and carries its contents
across the move, so walking in a straight line costs one new slice of cells
per 4 blocks rather than a full re-ingest.

`BiomeFieldBuilder` keeps the field in step with the chunks the client has
loaded. Ingest is one chunk section = 4x4x4 = 64 cells — exactly Minecraft's
biome container granularity, so nothing is resampled and nothing is lost.
Sections outside the vertical range are rejected and counted; changed cells
become `DirtyRegion` boxes, coalesced deterministically (greedy: merge the
touching pair whose union wastes the least volume) so a chunk border crossing
does not produce dozens of one-cell GPU uploads.

## Sampling — and the one-cell seam

`BiomeSampler` does trilinear interpolation over the eight cells surrounding
the position, cell centres at `(c + 0.5) * cellSize`. Duplicate ids merge,
contributors sort by weight, the top `BiomeSample.MAX_ENTRIES = 4` are kept
and renormalised: weights always sum to 1, the dominant biome is entry 0.
Cells never ingested contribute nothing; when all eight are unknown the
sample is empty and callers fall back to the registry default rather than
inventing a biome.

**The seam is exactly one biome cell (4 blocks), and that is a structural
fact, not a tunable.** Trilinear interpolation consults the two cells either
side of the position on each axis; a position more than one cell from a
border cannot see it. An earlier revision carried a `blendRadius` knob that
appeared to widen the seam; measurement showed it only reweighted the same
eight corners and never reached a further cell, so it was removed rather than
left as a control that lies. Widening a seam is done where it is cheap — a
compute-pass blur over the baked property volume (BIOME_PLAN B5) — not by
widening a per-fragment CPU kernel.

## The registry

`BiomeRegistry` maps id ↔ record **in registration order**: Iris hands a pack
`BIOME_PLAINS` and friends as small integers derived from registry order, so
the built-in vanilla table is seeded in that order (48 entries,
`vanillaDefaults()`) and modded biomes are appended as the game registers
them. Ids are stable for a world load, not across versions — which is why the
native pack format refers to biomes by `registryKey` and resolves at load.
The fallback record for unknown ids is `BIOME_PLAINS`. `bindConstants`
exposes every `BIOME_*`, `CAT_*` and `PPT_*` to the expression environment;
water and fog colours are seeded from AuraLite-Shaders v1.2.0, the reference
pack. The fabric glue overwrites colours from the live registry.

## The LUT

`BiomeLut` is the property table as a 2D texture — one texel column per biome
id, five RGBA32F rows, fetched by id in the shader with zero CPU involvement:

```
row 0  WATER_COLOR      rgb = linear water colour          a = 1.0
row 1  WATER_FOG_COLOR  rgb = linear water fog colour      a = fogDensityScale
row 2  FOLIAGE_COLOR    rgb = linear foliage tint          a = temperature
row 3  GRASS_COLOR      rgb = linear grass tint            a = downfall
row 4  META             r = biome id, g = category, b = precipitation, a = cloudCoverageScale
```

Width is padded to a power of two; `LAYOUT_VERSION` goes up if a row changes
meaning and the native pack format refuses a pack built against a different
one.

## Packing to the GPU

`BiomeFieldPacker` packs the whole field into uploadable texels. Two layouts,
because they trade different things:

- **`ID_R32UI`** — one 32-bit id per cell. Exact, any id a modded registry
  can produce; the shader does its own trilinear blend from eight fetches
  plus eight LUT fetches.
- **`TOPK_RGBA8UI`** — the four most likely ids per cell plus a matching
  RGBA8 weight texture. One fetch instead of eight, a quarter of the
  bandwidth, a hard ceiling of **254** biome ids. The packer refuses higher
  ids with a named reason rather than silently truncating
  (`TOPK_RGBA8UI cannot represent biome id 300; … Use ID_R32UI`).

Unknown cells pack as `0xFFFFFFFF` / `255`. `subImageBoxes` clips dirty
regions to the window, so a chunk load on the far side of the window touches
no GPU memory at all; `bytesForBoxes` prices the upload for the receipts.

## Baking legacy expressions — one pack, many biomes

`BiomeBake` is the compatibility story for "more than one biome", and it needs
no pack edits. A legacy pack writes

```
variable.float.waterColorR = if(in(biome, BIOME_SWAMP), 0.38, in(biome, BIOME_LUKEWARM_OCEAN), 0.271, 0.247)
uniform.vec3.waterColor    = vec3(waterColorR, waterColorG, waterColorB)
```

Iris evaluates that once per frame with `biome` bound to the camera's biome:
one water colour for the whole screen. Here the same expression is evaluated
once per field cell with `biome` bound to that cell's id — plus `temperature`,
`rainfall`, `biome_category`, `biome_precipitation` for expressions that read
those — and uploaded as an RGBA32F volume. Under the spatial opt-in (see
[UNIFORMS.md](UNIFORMS.md)) the pack gets spatially varying water colour, fog
and foliage **without a single line changed**. Unknown cells bake as the
fallback biome; a definition that fails to evaluate bakes zero and is
counted. `smooth()` in a baked definition is evaluated once, frozen, with a
warning.

## Receipts (from `make test`, `biome` suite)

```
field: BiomeField{cell=4, window=8x4x8, origin=(0,0,0), filled=1/256, v2}
water colour across a swamp|plains border in ONE frame:
  x= 6.0 swamp   (0.380, 0.482, 0.392)  contributors=1
  x=15.0 blend   (0.347, 0.477, 0.517)  contributors=2
  x=17.0 blend   (0.280, 0.468, 0.768)  contributors=2
  x=26.0 plains  (0.247, 0.463, 0.894)  contributors=1
seam: pure swamp at x<=14, blended 14<x<18, pure plains at x>=18
      (one 4-block biome cell, which is Minecraft's own granularity)
lut: BiomeLut{64x5 RGBA32F, 5120 B, layout v1}
registry capacity: TOPK tops out at 254 ids; 300 modded biomes require ID_R32UI
coalesce: 64 cells -> [0,0,0 +4x4x4]
bake: one unmodified legacy expression -> 2 spatially distinct water colours
      in a 8x4x8 volume (4096 B). Iris can express 1.
bake: 8x4x8 cells, 256 evaluations, 0 unknown, 4096 B
window for render distance 2: 23x97x23 cells = 205252 B
biome ingest: 1 sections accepted, 2 rejected, 0 cached, 0/51313 cells filled,
              2 window moves
```

Seven sample positions, five distinct colours, three strictly intermediate:
the blend is real, and the seam is structurally one 4-block cell.
