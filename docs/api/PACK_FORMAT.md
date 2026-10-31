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
```

`spatialUniforms` opts the pack into the baked 3D biome field (see the biome
notes): legacy `if(in(biome,…))` expressions evaluate per cell at bake time
instead of per fragment. Truthy: `true`, `1`, `yes`, `on`.

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
compute), bare `external` (touches the imported backbuffer). History resources
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
