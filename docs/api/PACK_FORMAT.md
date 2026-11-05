# Pack format, API level 1

Authority for both modes. When this doc and a loader disagree, the loader is
a bug — except the loader is also the gate, so check the suite first.

`CURRENT_API_LEVEL = 1`. A pack declaring higher is refused with both numbers
named. A pack declaring lower (or nothing) loads; the loader never guesses.

## Detection

Checked in order, first match wins:

1. `pack.aurapack` exists → NATIVE.
2. `shaders/shaders.properties` exists → LEGACY_IRIS.
3. `shaders.properties` exists at root → LEGACY_IRIS.
4. A non-empty `shaders/` tree exists → LEGACY_IRIS (markerless legacy).
5. Neither → refused: "this does not look like a shader pack".

Both trees present → NATIVE, and the report records that the legacy tree was
ignored. Silence is not a detection strategy.

Legacy dimensions, in scan order: `""`, `world-1/`, `world1/`, `world0/`.
Program names come from filenames against 40 real on-disk names
(`gbuffers_terrain`, `shadow`, `composite`, …); unknown names are warnings,
not errors, and coverage is reported as `k of 40`.

## NATIVE: `pack.aurapack`

INI-shaped, `#` comments, `key = value`. Eight sections; anything before the
first `[section]` is an error, an unknown section is a warning. Values are
trimmed; keys are case-sensitive.

### [meta]

`name`, `version`, `author`, `description` — all plain strings.
`auralite.api` (or bare `api`) — integer, must be ≤ 1. Absent means 1.

### [capabilities]

Two keys, comma-separated `GfxCapability` names:

```
require = COMPUTE_SHADERS, TEXTURE_3D, INTEGER_TEXTURES
optional = RAY_QUERY
```

A missing `require` fails the load against a device with the capability
named. `optional` is advisory: the pack is expected to branch on it.

### [biome]

```
spatialUniforms = true
sampleEntries = 4
seamRadius = 2
```

`spatialUniforms` opts the pack into the baked 3D biome field (see the biome
notes): legacy `if(in(biome,…))` expressions evaluate per cell at bake time
instead of per fragment. Truthy: `true`, `1`, `yes`, `on`.

`seamRadius` is the widened seam's blend radius **in cells** (default 2, i.e.
five cells or 20 blocks of blend; `0` is today's one-cell seam). The ceiling
is the pass's declared maximum, 4 — the kernel reads `(2R+1)^3` cells, so a
larger radius would cost a bake it cannot pay for. A value outside `0..4`, or
one that is not an integer, is **refused at load by name**
(`BIOME_SEAM_R_TOO_WIDE`) and never clamped: a pack that asked for one seam
and silently got another cannot tell the difference from a bug in its own
shader. The reference pack declares `seamRadius = 2`.

The widened volume is produced by a compute pass, not by the host: a pass
writes `biome_volume` (`r32g32_uint`: four ids in `.x`, four weights in `.y`,
low slot in the low byte) from `biome_field_ids` (`r32_uint`, the exact ids,
`0xFFFFFFFF` for a cell nothing ingested).

`r32g32_uint` and not `rgba8_uint` is the whole width of the trap, so it is
stated here rather than left to the shader: a packed cell is four bytes inside
ONE channel, and an 8-bit channel stores one. Narrowing an integer image write
**saturates** instead of truncating — `0xFFFFFF06` and `0x12345678` both land as
`255` — so three of the four slots become the UNKNOWN sentinel in every cell and
a reader skips every contribution. The bake still runs, the frame still renders,
and only the pixels are wrong; a pack author hitting this sees a black screen
with no error anywhere. Two 32-bit channels hold the same 16 bytes per cell the
8-bit form was meant to hold, and `rg32ui`/`rg32_uint` is spelled the same way
in both backends. `packs/AuraLite-Native` ships that
pass as `shaders/biome_field.comp`, which is **generated** — `make shader`
rewrites it from `BiomeSeamShader`, and `make shader-check` fails by name
when the committed file and the generator disagree, so the shader cannot
drift away from the CPU rule it mirrors.

Two refusals belong to this lane and are named before any code:

- `BIOME_VOLUME_IDS_OVER_TOPK` — an id a byte cannot carry (above 254) is
  never truncated into a different biome. The host bake throws by name; the
  shader marks the cell `255` with zero weights, because a compute invocation
  cannot throw and an invented id is worse than an honest hole.
- `BIOME_VOLUME_STALE` — a field region was touched but the pass has not run
  before a frame reads the volume. Reserved here, not yet enforced: a stale
  seam still looks plausible, which is exactly why the name exists in advance.

### [resources]

One line per image: `name = format tokens…`. The format is a `GfxFormat`
name (`rgba8_unorm`, `rgba16f`, `d32_sfloat`, `r16f`, `rgba8_uint`, …);
unknown formats are errors.

Size tokens (one wins; default is fullscreen):

| Token | Meaning |
|---|---|
| `fullscreen` | swapchain-sized, follows the window |
| `shadow` | shadow-map sized, implies depth usage |
| `scaled=f` / `scaled f` | fullscreen × factor (SSAO at `scaled=0.5`) |
| `WxH` / `WxHxD` | fixed pixels, e.g. `256x256` |
| `biome-volume` | the baked biome field; implies 3D + sampled |
| `3d` | a 3D image with the current size kind |
| `mips=N` / `layers=N` | mip count / array layers |

Other tokens: `history` (keeps `.prev` alongside `.cur` for TAA-style
passes), `imported` (the host binds it, e.g. the backbuffer),
`usage=a|b|c` with flags `sampled storage color depth present transient
input transfer-src transfer-dst`. Unknown usage flags warn, unknown tokens
error.

With no `usage=` token, depth formats default to `sampled|depth` and
everything else to `sampled|color`. The default covers the common case;
passes that need more say so.

### [options]

```
CLOUD_QUALITY = select "Raymarch steps" { 14=Cheap, 24=Standard, 36=High } default=24 tab=Atmosphere
SSAO = bool "Screen-space ambient occlusion" default=true tab=Lighting
BLOOM_STRENGTH = slider 0.0..2.0 step 0.05 "Bloom" default=0.35 tab=Colour
```

Grammar: `NAME = kind "label" [{ v=Label, … }] [default=v] [tab=T]`.
Kinds: `bool`, `const`, `slider`, `select`/`selector`, `define` (the default
when no kind is given). `bool` gains `true/false` choices automatically.
Sliders carry their range in the head: `slider min..max step s`.
Unterminated labels and choice blocks are errors; unknown kinds are errors.

Options become `#define` lines (`#define CLOUD_QUALITY 36`, `#define SSAO`,
`#define BLOOM_STRENGTH 0.35`) and gate passes through `when=`.

### [graph]

Passes first, then the present pointer:

```
pass cloud_shadow raster color=cloud_shadow program=cloud_shadow
pass ssao compute write=ao read=scene_depth program=ssao dispatch=ceil(width/16),ceil(height/16),1 when=SSAO
pass present raster color=backbuffer read=taa.cur external program=final
present = taa
```

`pass <name> <raster|compute|copy> [clauses…]`. A pass line carries `=` inside
its own clause list, so it is split on whitespace, not on `=` — that special
case is load-bearing, not incidental.

Clauses: `color`/`colors`, `depth`, `read`/`reads`, `write`/`writes`,
`program`, `when` (option name; a pass whose option is off is culled and
names the option), `dispatch` (three comma-separated extent expressions for
compute), `dispatchBase` (`write` sizes those expressions from the first
`write=` target instead of the framebuffer — required for a 3D bake, where a
framebuffer-sized dispatch writes layer 0 and nothing else), bare `external`
(touches the imported backbuffer).

Compute dispatch expressions may use `width`, `height` and `depth`: the
framebuffer's extents by default, or the target image's with
`dispatchBase=write`. A pass that dispatches over the wrong extents is not an
error the backend can report — the extra invocations simply do nothing — which
is why the clause exists rather than a convention. History resources
address sides explicitly: `taa.cur`, `taa.prev`.

The graph builder orders passes by semantic RAW/WAW edges plus a declaration
tie-break — never by declaration order alone — plans barriers, and culls
passes no kept pass reads. Load-bearing passes survive via `keep(why)`.

`present = <resource>` names what reaches the screen.

### [programs]

```
ssao = shaders/ssao.comp local_size=16x16x1
terrain = shaders/terrain.vert, shaders/terrain.frag
shadow = shaders/shadow.vert pass=shadow_map
```

`name = path[, path…] [attr=…]…`. Attributes: `local_size` (also
`localSize`, `WxHxD`), `pass` (binds the program to a pass by name).
Stages come from extensions (`.vert .frag .comp`, …).

NATIVE never infers filenames: every stage is listed. A raster program
without a vertex stage is a load error; a missing fragment stage is a
warning, because depth-only shadow passes are legal and common.

### Descriptors and draws

An image uniform written as
`layout(set = N, binding = B[, format]) uniform [readonly|writeonly] <image…|sampler…> <name>;`
binds to the graph resource whose name equals `<name>` **exactly** — that
equality is the whole mapping table. The pass must read or write that
resource, `PackProgram.layout()` turns the declarations into the pipeline
layout, and the pass body binds one descriptor set per set index right after
the pipeline. Block uniforms (`uniform Block { … }`) are not parsed yet, and
combined samplers are parsed but not yet wired in the graph — both refuse by
name if a pack leans on them.

A resource a compute pass writes (or reads) through a storage image must
declare `usage=storage` in `[resources]` — the backend refuses the bind by
name otherwise, and depth textures are never storage images: they are read
as samples, and the sampler side of this contract lands with the graph's
sampler bridge (until then a pack may keep the pass-level `read=` ordering
edge and have its shader sample nothing).

The pack dialect is Vulkan GLSL and the backends translate at compile time:
the GL backend packs `set = S, binding = B` into GL's flat
`binding = S*16 + binding` (the unit map in api/RHI.md) and renames
`gl_VertexIndex` to `gl_VertexID`; SPIR-V is compiled from the dialect as
written.

Every raster body draws exactly one fullscreen triangle (`draw(3)`): the
shaders carry the `gl_VertexIndex` convention (the reference pack's
`final.vert` builds the entire quad from it). Until real mesh data lands,
vertex stages declare no inputs and write their placeholder positions
literally — the frame must not depend on `VK_EXT_vertex_attribute_robustness`
(lavapipe cannot offer it, and a spec-defined constant is portable).

### [screen]

Tabs, one per line: `Atmosphere = CLOUD_QUALITY`. The left side is the tab
name, the right lists that tab's options; matching names are tabbed onto
their options, the rest are kept verbatim for the UI lane to resolve.
A tab pointing nowhere is a pack bug, but the loader reports it — it does
not refuse the pack over a menu.

## LEGACY_IRIS: `shaders.properties`

Read as shipped: block/item/entity IDs, custom uniforms with OptiFine/Iris
expression syntax (`if`, `in`, `smooth`, `mix`, variadic chains), filename
→ program mapping per dimension. The expression evaluator is shared with the
uniform lane and gated there (28-part `if` chains, float `/`, frame-by-frame
`smooth`). Spatial biome uniforms stay off unless the host opts in with
`auralite.spatialBiomeUniforms=true`, which injects `AURALITE_SPATIAL_BIOME`
(default false) and freezes `smooth()` state at bake time.

## The load report

Every load returns errors, warnings, and notes together. Errors refuse the
pack; warnings load it loudly; notes record decisions (NATIVE-over-both,
inferred filenames, culled passes). `packlint` is this report as a CLI.
