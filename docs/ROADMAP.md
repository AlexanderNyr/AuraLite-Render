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

## Phase 4 — fabric glue (closed)

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
- **Closed on hardware (rule 4 satisfied)**: the windowed frame is presented
  on a discrete card - NVIDIA GT 710, driver 475.14, Windows 10, Prism,
  Minecraft 1.21.1. The present receipt reads the pack's own pixels back from
  the DEFAULT framebuffer before the swap (`rgba=(128,128,0,255)` - the
  reference final's gradient centre), the operator's screenshot shows that
  gradient filling the window, and later revisions receipt the world instead
  of a gradient (`rgba=(161,202,223,0)` on a bright sky pixel, with the pack's
  terrain under it). Present is OFF by default and toggled with F8; the
  shutdown chain closes with `waitIdle ok -> device closed -> exit 0`.
  Closing patch: `patches/phase-4-fabric-glue.patch` (the 26c7098 export).

## Phase 5 — GPU behaviour gates (closed)

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
- **Closed on one discrete card**: both findings were fixed (descriptor
  bridge, pass-compatible pipeline creation), the vk lane renders with a
  byte-stable pixel hash and monotonic timestamps, and the whole thing runs
  on a real GT 710 inside Minecraft. Later revisions added: the option
  defines reaching the VK compile lane (F9 was silently ignored), a
  use-after-free on graph rebuilds (framebuffers outliving their images),
  and the receipts that make both visible in the log.
  Closing patch: `patches/phase-5-gpu-behaviour-gates.patch`.

## Phase 6 — widening (planned, not started)

Each item gets its own plan doc **before** any code; none has code today, and
none gets code before its doc exists.

| Item | Plan doc | State |
|---|---|---|
| B5 — biome seam wider than one 4-block cell | `docs/plans/BIOME_PLAN.md` | written 2026-11-04: blur the baked volume in the compute lane, keep `ID_R32UI` exact, name the four refusals (`R_TOO_WIDE`, `IDS_OVER_TOPK`, `VOLUME_STALE`, unknown stays unknown), five NULL-backend gates |
| ray-query in shaders | owed | the backend already enables the feature when the driver offers it and reports it as an optional capability (`RAY_QUERY` in every pack-load receipt); no shader uses it yet |
| task/mesh lanes in GL via NV extensions | owed | backend-vulkan enables mesh features when offered; GL has no lane |
| bindless descriptor indexing | owed | not started; the bridge currently packs `set*16+binding` into GL binding units |

The seam stays one cell until BIOME_PLAN's gate 1 (equivalence at R = 0) is
green — that gate exists precisely so the widening cannot drift from the CPU
rule it replaces.

## Non-goals

- Replacing Iris/OptiFine. Legacy mode is compatibility, not conquest.
- A scene editor, a material system, PBR. The pack format describes passes
  and resources; authoring tools are someone else's repo.
- Supporting GL < 4.6 or Vulkan < 1.3. The RHI assumes DSA-era GL and
  core-1.3 Vulkan; backport shims are refused, not deferred.
