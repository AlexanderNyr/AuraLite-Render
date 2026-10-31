# Custom-uniform expressions

`dev.auralite.render.uniform`. The expression language a pack writes in
`shaders.properties` (`uniform.*` / `variable.*`) and the same parser the
native format reuses for extent expressions like
`dispatch=ceil(width/16),ceil(height/16),1`. Behaviour-compatible with what
Iris/OptiFine evaluate on the CPU — the parity gate diffs this evaluator's
output against recorded Iris results — with one documented extension and two
documented semantic decisions.

## Grammar

Recursive descent; precedence lowest → highest:

```
||  &&  == !=  < <= > >=  + -  * / %  unary(-,!)  primary
```

Primary: identifiers, decimal and hex (`0x…`) literals, `pi`, `true`/`false`,
parentheses, function calls. Unknown identifiers and trailing input are
refusals with the offset inside the expression text
(`unknown identifier 'wat' at offset 12 in '…'`), never a bare NPE.

**Integer vs float division is a measured decision.** `+ - * %` on two ints
stay ints, as in GLSL. `/` does **not**: GLSL refuses `int/int` outright and
every pack author who writes `ceil(width/16)` means float division. An earlier
revision floored first; that made `ceil()` a no-op and dropped the last
partial row and column of every compute dispatch — at 1920x1080 with a 16x16
group, the bottom 8 rows of AO were never computed and nothing complained.
`/` therefore yields a float even from two ints.

`&&`/`||` do **not** short-circuit: both sides are evaluated, matching Iris's
CPU evaluator.

## Values

`Value` kinds: `BOOL`, `INT`, `FLOAT`, `VEC2`, `VEC3`, `VEC4`. Component-wise
arithmetic widens to the larger side; comparisons and equality operate on the
first component (bools compare as bools); equality is approximate (1e-9).

## Functions

| Call | Semantics |
|---|---|
| `if(c1, v1, c2, v2, …, else)` | Variadic, not ternary: odd argument count ≥ 3, condition/value pairs, trailing else. Branches evaluate lazily pair-by-pair, so `smooth()` state does not advance on a branch that was not taken. `select` is an alias. |
| `in(v, a, b, …)` | True when `v` equals any candidate. This is what `in(biome, BIOME_SWAMP)` compiles to. |
| `smooth(id, expr, rate[, rateDown])` | Per-call-site temporal filter, state keyed by the `id` expression's source text. Exponential approach scaled by frame time: `k = 1 - exp(-r * dt)`, `value += (target - value) * k`; `rateDown` is used when the target falls. First evaluation snaps to the target. Advances at most once per frame id. |
| `vec2/vec3/4(a, …)` | Vector constructors, component count checked. |
| `blendWeights(w1, w2, …)` | **AuraLite extension.** Clamps weights ≥ 0 and renormalises to sum 1 (uniform when the sum is 0). Exists so a legacy per-biome `variable.float.waterColorR` chain can be re-evaluated per biome cell and blended — see [BIOME.md](BIOME.md). |
| maths | `abs sign floor ceil round fract trunc sin cos tan asin acos atan atan2 exp exp2 log log2 sqrt inversesqrt pow mod min max clamp mix lerp step smoothstep radians degrees length distance dot` — arity-checked, `lerp` aliases `mix`. |

`FunctionTable.setFrame(frameId, deltaSeconds)` drives `smooth()`; the default
delta is 1/60 s.

## The definition table

`CustomUniformTable` holds `uniform.<type>.<name>` and
`variable.<type>.<name>` definitions **in declaration order**, because a
variable may feed a later uniform — that is exactly how AuraLite-Shaders
builds its water colour, so order is part of the semantics and is preserved
verbatim in both pack modes. `<name>.transition` lines set OptiFine's biome
transition time on the following definition. Definitions that fail to parse
are collected as `problems()` and surface as load warnings, not crashes.

An `ExprEnv` carries the bindings for one evaluation and is reused across a
frame (bindings overwritten in place — the legacy path is allocation-free).
Built-in bindings a pack expression may see: frame time and counters, viewport
and `width`/`height`, and — on the spatial path — `biome`, `temperature`,
`rainfall`, `biome_category`, `biome_precipitation`, plus every `BIOME_*`,
`CAT_*` and `PPT_*` constant bound by `BiomeRegistry.bindConstants`.

## The spatial opt-in

Legacy biome-dependent expressions evaluate once per frame with `biome`
bound to the camera's biome — one colour for the whole screen. With the
opt-in (`shaders.properties` host flag `auralite.spatialBiomeUniforms=true`,
native `[biome] spatialUniforms=true`), the same expression is re-evaluated
per biome-field cell at bake time and uploaded as a 3D volume the shader
samples at its own world position. Two semantic differences are **recorded,
not hidden**:

- `smooth()` is evaluated once and frozen (a per-cell temporal filter is not
  what the pack author meant) — a warning says so;
- `.transition` times are ignored, because the spatial blend replaces them.

The opt-in injects an `AURALITE_SPATIAL_BIOME` option (default false) so the
choice is visible in the option table rather than being magic.

## Receipts (from `make test`, `uniform` suite)

```
smooth(t1, 0->1, rate=4): frame1=0.064493 frame2=0.124827
waterColor(swamp)  = (0.380, 0.482, 0.392)
waterColor(plains) = (0.247, 0.463, 0.894)
waterColor(meadow) = (0.055, 0.306, 0.812)
waterColor(warm_ocean) = (0.263, 0.835, 0.933)
if(): variadic chain of 28 comma-separated parts accepted
```

The 28-part chain is AuraLite-Shaders' water-colour `if()` evaluated verbatim
— a first implementation that required exactly three arguments refused the
pack outright.
