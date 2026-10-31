# AuraLite-Render — roadmap

Date: 2026-10-31. Phases close with a patch in `patches/` and a STATUS update.
A phase is done when its gate is green, not when its code is written.

## Phase 0 — core + NULL (closed)

RHI, render graph, scene, uniforms, biomes, pack loader, and the recording
reference device. Gate: 6 suites green on OpenJDK 11, no GPU.
Closing patch: `patches/phase-0-core-null.patch` — replayed gate 627/0, 6/6.

## Phase 1 — backends compile-verified (closed)

GL 4.6 and Vulkan 1.3 devices against LWJGL 3.4.3, same failure vocabulary
as NULL, mapping tables pinned by the `tables` suite. Gate: both modules
`javac rc=0` on real sources, `make test` 967/0, 7/7 green.
Closing patch: `patches/phase-1-gl-vulkan-compile-verified.patch` —
replayed gate 967/0, 7/7 (applying it after Phase 0 rebuilds this tree
byte-identically).

## Phase 2 — docs + tools (closed)

- `docs/`: README, ROADMAP, STATUS, `api/` (RHI, PACK_FORMAT, GRAPH, UNIFORMS,
  BIOME), `plans/BIG_PLAN.md` with AuraLite-OS/Emulator links.
- `tools/packlint`: the loader's refusals as a CLI — exit non-zero on error,
  warnings to stderr, `--strict` promoting warnings, `--null-device`
  capability checking.
- `tools/drift-check`: replays the suite receipts and fails when a number
  moves without its doc line moving too.
- Gate: a stranger can validate a pack and explain every refusal from the
  docs. Receipt: a 4-error probe pack is refused with file-and-line reasons
  and exit 1; a warnings-only probe exits 0 and fails `--strict`; a legacy
  probe reports coverage; drift-check passes clean and names a deliberately
  moved count in its negative control.
Closing patch: `patches/phase-2-docs-and-tools.patch` — replayed gate
967/0, 7/7 plus `drift-check` PASS and the `packlint` probe receipts.

## Phase 3 — AuraLite-Native pack (closed)

The reference native pack under `packs/AuraLite-Native/`: resources, graph,
shaders, options, screen tabs. Small on purpose — it is the format's
executable spec, not a showcase. Gate: loads with 0 errors, executes 0
violations on NULL, lints clean. Receipt: packlint reports 0 errors and
0 warnings (also under `--strict --null-device`); the pack suite loads it
from disk and executes its own frame on the recording device — 38 commands,
13 barriers, 40560 work groups, 0 contract violations, with the pack's
shipped GLSL as the validated sources. Host gate moves to 985/0.
Closing patch: `patches/phase-3-native-reference-pack.patch` — replayed gate
985/0, 7/7; a from-nothing replay of all four patches rebuilds this tree
byte-identically (verified 2026-10-31).

## Phase 4 — fabric glue (sandbox deliverable landed; windowed-frame gate PENDING)

Java 21 + Gradle/Loom sources under `fabric/`: window swapchain, frame
pacing, pack hot-reload, RenderDoc-friendly labelling. The sandbox now HAS
a JDK 21 (OpenJDK 21.0.13) and `gradle build` with loom 1.10.5 is rc=0
here — compileJava against real yarn/fabric-api plus a remapped production
jar (211 entries). `make build` prints the JDK-present fabric line when 21
exists and `fabric: SKIP - needs JDK 21` when it does not. Gate: a windowed
frame on each backend, presented — still PENDING.

- **Landed and host-gated**: `fabric/` sources + `fabric.mod.json` +
  mixins + gradle files; `make build` prints the fabric line visibly
  (SKIP on pre-21 hosts, JDK-present here); the `fabric` suite
  (23 assertions) pins the module shape — entrypoint matches its source,
  mixins config matches its target, java 21 declared, toolchain pinned,
  LWJGL reuse from `third_party/` (multi-release-stripped copies: loom's
  tiny-remapper rejects the jar's class major 71 entries). Receipt in STATUS.
- **Build-proven this session**: `gradle build` rc=0 on OpenJDK 21.0.13
  (loom 1.8.13 had produced a manifest-only remapJar under Gradle 8.12 —
  fixed by loom 1.10.5 + pinning remapJar's input to the jar task archive).
- **NOT landed, NOT claimed**: a presented windowed frame. The JDK 21 lane
  exists now; what remains is the windowed presentation itself on a GPU.
  Per standing rule 4 this phase stays open and gets no closing patch until
  that frame exists.

## Phase 5 — GPU behaviour gates

Where a GPU exists: triangle receipts (hash of first presented frame per
backend), barrier validation clean under Vulkan validation layers and the
GL debug callback, timestamp sanity (monotonic, non-zero deltas).
Gate: `make gpu-test` green on one discrete NVIDIA/AMD card.

- **Landed**: the `make gpu-test` harness (GpuSmoke) — one child JVM per
  lane so a driver abort cannot swallow the other lane's receipt, named
  outcomes only (RENDERED / REFUSED-NAMED / SKIP-NAMED / CRASH-UNNAMED),
  EGL surfaceless display, Khronos validation on by default.
- **First software-GPU run (this host, measured)**: GL answered BY NAME
  (REFUSED-NAMED: llvmpipe caps at 4.5, the backend requires 4.6); VK on
  lavapipe reached pipeline creation with 2 named findings (vertex
  Location 0 not wired into pVertexInputState; storage-image descriptors
  missing from the pipeline layout) and the driver aborted on the invalid
  layout — `make gpu-test` exits 1, honestly RED. Receipts in STATUS.
- **Open**: fix both named VK findings, get a real RENDERED frame (hash),
  then close on one discrete NVIDIA/AMD card.

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
