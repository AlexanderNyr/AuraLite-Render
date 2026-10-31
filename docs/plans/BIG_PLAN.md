# The big plan

Why AuraLite-Render exists, what it is, and where it plugs into the stack.
Companion docs: AuraLite-OS `PLAN.md` (the OS-side renderer surface),
Emulator `ROADMAP.md` + `CONTRIBUTING.md` (house style and gates).

## Why

Minecraft shader packs are stuck between two facts: twenty years of
OptiFine/Iris packs that must keep working, and hardware that can do far
more than the legacy format can express. Every existing loader picks one
fact and apologises for the other. AuraLite-Render refuses the trade:

- legacy packs load **as shipped** and look right on day one;
- native packs get compute, an explicit DAG, and required capabilities;
- both run on one RHI with two backends, so GL and Vulkan stay honest
  with each other instead of drifting into dialects.

The NULL backend is the mechanism of that honesty: a recording reference
device that settles every cross-backend disagreement without a GPU.

## What it is

Three layers, bottom up:

1. **Ядро (the core)** — renderer-agnostic: RHI interfaces, the render
   graph (ordering, barriers, aliasing, culling), scene feeds, the uniform
   expression evaluator, multi-biome fields, the pack loader. Gated by six
   suites, 627 assertions, no GPU.
2. **Two backends, one RHI** — GL 4.6 (immediate) and Vulkan 1.3
   (recorded), pinned by the `tables` suite's 340 literal checks.
   Same failure vocabulary, same usage enforcement, same top-left origin.
3. **The extended pack API** — compute passes, DAG-declared graphs,
   capability-gated features, spatial biome uniforms, hot reload, motion
   vectors and HDR staging, RenderDoc-friendly labelling. The format doc
   is the authority; the reference pack is its executable spec.

## Where it plugs in

- **AuraLite-OS** consumes the RHI surface: window swapchain behind fabric
  glue, frame pacing, pack hot-reload. The OS plan's renderer milestones
  are this repo's Phase 4 and 5 gates.
- **Emulator** consumes determinism: NULL-backend receipts are byte-identical
  run to run, so emulated frames diff cleanly. The `tables` suite runs there
  unmodified — mapping tables load with no LWJGL on the classpath by design.
- **AuraLite-Shaders** is the day-one legacy proof: it loads unmodified,
  and the parity gate measures pixels, not intentions.

## The gates, in one place

| Gate | Command | Bar |
|---|---|---|
| Host | `make test` | 1008/0, 8/8 green, OpenJDK 11 and 21 both measured, no GPU |
| Perf | `make bench` | graph + biome + uniform gates, 5×, no timing in receipts |
| Lint | `packlint` | exit non-zero on error, `--strict` promotes warnings |
| Drift | `drift-check` | suite numbers may not move without their doc lines |
| GPU | `make gpu-test` | first-frame hashes, validation-clean, monotonic timestamps — software host now GREEN: vk + gl RENDERED (44 commands / 13 barriers / 6 passes each, exit 0; GL through zink with `LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=zink`); hashes, validation-clean, timestamps and a discrete card still owed |
| Parity | legacy suite | AuraLite-Shaders pixels match the reference loader |

## What is deliberately not in the plan

Widening the biome seam past one cell (BIOME_PLAN B5), mesh shaders on GL,
bindless indexing, a material system, an editor, GL < 4.6, Vulkan < 1.3.
Each has its paragraph in the roadmap's Phase 6 or non-goals; none has code,
and none gets code before it gets a plan doc. The fastest way to kill this
project is a second feature before the first gate.
