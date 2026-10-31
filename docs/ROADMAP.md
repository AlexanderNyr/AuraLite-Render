# AuraLite-Render — roadmap

Date: 2026-10-31. Phases close with a patch in `patches/` and a STATUS update.
A phase is done when its gate is green, not when its code is written.

## Phase 0 — core + NULL (closed)

RHI, render graph, scene, uniforms, biomes, pack loader, and the recording
reference device. Gate: 6 suites green on OpenJDK 11, no GPU.

## Phase 1 — backends compile-verified (closed)

GL 4.6 and Vulkan 1.3 devices against LWJGL 3.4.3, same failure vocabulary
as NULL, mapping tables pinned by the `tables` suite. Gate: both modules
`javac rc=0` on real sources, `make test` 967/0, 7/7 green.

## Phase 2 — docs + tools (current)

- `docs/`: README, ROADMAP, STATUS, `api/` (RHI, PACK_FORMAT, GRAPH, UNIFORMS,
  BIOME), `plans/BIG_PLAN.md` with AuraLite-OS/Emulator links.
- `tools/packlint`: the loader's refusals as a CLI — exit non-zero on error,
  warnings to stderr, `--strict` promoting warnings.
- `tools/drift-check`: replays the suite receipts and fails when a number
  moves without its doc line moving too.
- Gate: a stranger can validate a pack and explain every refusal from the docs.

## Phase 3 — AuraLite-Native pack

The reference native pack under `packs/AuraLite-Native/`: resources, graph,
shaders, options, screen tabs. Small on purpose — it is the format's
executable spec, not a showcase. Gate: loads with 0 errors, executes 0
violations on NULL, lints clean.

## Phase 4 — fabric glue

Java 21 + Gradle/Loom sources under `fabric/`: window swapchain, frame
pacing, pack hot-reload, RenderDoc-friendly labelling. This sandbox has no
JDK 21, so the lane builds where 21 exists and SKIPs here — by design,
not by accident. Gate: a windowed frame on each backend, presented.

## Phase 5 — GPU behaviour gates

Where a GPU exists: triangle receipts (hash of first presented frame per
backend), barrier validation clean under Vulkan validation layers and the
GL debug callback, timestamp sanity (monotonic, non-zero deltas).
Gate: `make gpu-test` green on one discrete NVIDIA/AMD card.

## Phase 6 — widening (explicitly not started)

BIOME_PLAN B5 (blend radius beyond one 4-block cell), task/mesh lanes in GL
via NV extensions, ray-query in shaders, bindless descriptor indexing.
Each gets its own plan doc before any code. The seam stays one cell until B5.

## Non-goals

- Replacing Iris/OptiFine. Legacy mode is compatibility, not conquest.
- A scene editor, a material system, PBR. The pack format describes passes
  and resources; authoring tools are someone else's repo.
- Supporting GL < 4.6 or Vulkan < 1.3. The RHI assumes DSA-era GL and
  core-1.3 Vulkan; backport shims are refused, not deferred.
