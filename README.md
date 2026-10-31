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
tests/           7 suites, one `RunAll`, no GPU required
tools/           packlint, drift-check
docs/            STATUS, ROADMAP, api/, plans/
third_party/     gitignored LWJGL 3.4.3, fetched by `make deps` (compile-only)
```

## Build

```
make deps    # fetch LWJGL 3.4.3 into third_party/ (~11 MB)
make test    # build everything, run 7 suites
make bench   # run the perf gates 5x
```

Host rule: everything except `fabric/` stays Java 11 source-compatible, so
`make test` is genuinely green on OpenJDK 11. `fabric/` is the documented
exception — it needs a JDK 21 lane this sandbox does not have.

Current state: **967 passed, 0 failed, 2 negative controls, 7/7 suites green**,
both backends compile `rc=0` against real LWJGL sources.

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

## Standing rules

1. Receipts, not adjectives. A claim without a suite line is a rumour.
2. Every refusal names its offender. `GfxException` always says which backend,
   which call, and what was wrong.
3. The NULL backend is the spec. If GL and VK disagree, the recording device
   settles it.
4. No GPU in the sandbox, so backends are compile-verified here and
   behaviour-verified where a GPU exists. Never claim a frame was rendered
   until one has been.
