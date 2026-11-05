# AuraLite-Render

A renderer-agnostic core, two real backends, and a shader-pack format with two
modes — legacy-compatible and new native. Built for the AuraLite stack
(AuraLite-OS, Emulator), usable without it.

Every number in this repo is measured by `make test`, not estimated.
See [docs/STATUS.md](docs/STATUS.md) for the receipts.

## Layout

```
core/            RHI, render graph, scene, uniforms, biomes, pack loader (Java 11)
backend-null/    recording + fail-fast reference device (Java 11)
backend-gl/      OpenGL 4.6 device (Java 11, LWJGL, compile-verified)
backend-vulkan/  Vulkan 1.3 device (Java 11, LWJGL, compile-verified)
fabric/          mod glue: Java 21 + Gradle/Loom (sources + json; build SKIPs here)
packs/           AuraLite-Native reference pack
tests/           8 suites, one `RunAll`, no GPU required
tools/           packlint, drift-check
docs/            STATUS, ROADMAP, api/, plans/
third_party/     gitignored LWJGL 3.4.3, fetched by `make deps` (compile-only)
```

## Build

```
make deps    # fetch LWJGL 3.4.3 into third_party/ (~11 MB)
make test    # build everything, run all 8 suites
make bench   # run the perf gates 5x
make packlint PACK=path/to/pack [STRICT=1] [NULL_DEVICE=1]
make gpu-test   # run OUR GL/Vulkan backends on this host's GPU (software ok);
                # one named receipt per lane: RENDERED / REFUSED-NAMED / SKIP-NAMED
             # validate a pack: exit 1 on error, warnings on stderr,
             # --strict promotes warnings, --null-device checks capabilities
make drift-check
             # replay the suite receipts; numbers that moved without their
             # doc lines are reported by name and fail the gate
```

Host rule: everything except `fabric/` stays Java 11 source-compatible, so
`make test` is genuinely green on OpenJDK 11. `fabric/` is the documented
exception — it needs a JDK 21 lane this sandbox does not have.

Current state: **1272 passed, 0 failed, 6 negative controls, 9/9 suites green**,
both backends compile `rc=0` against real LWJGL sources, and the reference
pack `packs/AuraLite-Native` lints clean and runs a NULL frame with 0
violations.

## The two modes

- **LEGACY_IRIS** reads an OptiFine/Iris pack exactly as shipped:
  `shaders.properties`, filename-derived program names, scalar custom uniforms.
  AuraLite-Shaders, Complementary and BSL keep working on day one.
- **NATIVE** reads `pack.aurapack`: an explicit resource table, a declared
  render-graph DAG, compute passes, a required-capability list, a versioned
  API level. Nothing is inferred from filenames.

A directory containing both loads as NATIVE; the decision goes into the load
report instead of happening silently. Format authority:
[docs/api/PACK_FORMAT.md](docs/api/PACK_FORMAT.md).

## Docs

- [docs/STATUS.md](docs/STATUS.md) — what is done, with receipts
- [docs/ROADMAP.md](docs/ROADMAP.md) — phases and lanes
- [docs/plans/BIG_PLAN.md](docs/plans/BIG_PLAN.md) — the whole endeavour,
  and where it plugs into AuraLite-OS and the Emulator
- [docs/api/RHI.md](docs/api/RHI.md) — the device contract both backends keep
- [docs/api/PACK_FORMAT.md](docs/api/PACK_FORMAT.md) — the pack grammar
- [docs/api/GRAPH.md](docs/api/GRAPH.md) — the render graph: order, barriers,
  lifetimes, aliasing
- [docs/api/UNIFORMS.md](docs/api/UNIFORMS.md) — the pack expression language
  and the spatial opt-in
- [docs/api/BIOME.md](docs/api/BIOME.md) — the biome field, the sampler, the
  LUT and the legacy bake

## Standing rules

1. Receipts, not adjectives. A claim without a suite line is a rumour.
2. Every refusal names its offender. `GfxException` always says which backend,
   which call, and what was wrong.
3. The NULL backend is the spec. If GL and VK disagree, the recording device
   settles it.
4. The sandbox's software GPUs (lavapipe, llvmpipe, zink) do render - and a
   frame is claimed only by the harness's own `RENDERED` receipt: submit +
   waitIdle of the real command buffer, then a non-zero mip-0 pixel sha256
   and strictly-increasing GPU timestamps in the same line. Blank bytes are
   refused; validation-clean stays its own gate in docs/STATUS.md.
