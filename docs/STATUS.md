# AuraLite-Render — status

Date: 2026-10-31. Host: OpenJDK 11, Linux x86_64, 2 CPUs.
Every number below is measured by `make test`, not estimated.

## Host gates: 7/7 green

```
rhi         51 passed   0 failed   0 NC   PASS
graph       81 passed   0 failed   1 NC   PASS
uniform     91 passed   0 failed   0 NC   PASS
biome      142 passed   0 failed   1 NC   PASS
nulldev    115 passed   0 failed   0 NC   PASS
pack       147 passed   0 failed   0 NC   PASS
tables     340 passed   0 failed   0 NC   PASS
TOTAL 967 passed, 0 failed, 2 negative controls, 7/7 suites green
RESULT: PASS
```

(Timings excluded: the receipt is the counts plus PASS, never microseconds.)

## What the receipts prove

- **rhi**: 42 formats, 13 stages, 27 capabilities behind one backend-agnostic interface.
- **graph**: 8 passes declared, 7 ordered (`cloud_shadow, shadow_map, terrain, ssao,
  lighting, taa_resolve, present`); 14 barriers planned, 0 suppressed; 1 alias group
  reclaiming 2097152 B; 79116288 B owned textures. The disabled pass names its
  option (`unused_debug -- option 'DEBUG_VIEW' is off`).
- **uniform**: variadic OptiFine/Iris `if()` accepted as a 28-part chain; smooth
  interpolation measured frame by frame (`frame1=0.064493 frame2=0.124827`).
- **biome**: water colour across a swamp|plains border in ONE frame — 7 sample
  positions, 5 distinct colours, 3 strictly intermediate; seam structurally one
  4-block cell. Bake: one unmodified legacy expression → 2 spatially distinct
  water colours in an 8×4×8 volume (4096 B). Iris can express 1.
- **nulldev**: 14 commands recorded, 18 violations refused and named; leak gate:
  40 handles created, 0 live after `close()`; idle-device receipts byte-identical.
- **pack**: native pack parses with 0 errors — 7 passes, 7 programs, 12 files /
  738 B, one vertex-only shadow warning. Native frame: 50 commands, 17 barriers,
  8160 work groups, 0 violations. Legacy pack: 6 programs, 7 options, coverage
  6/40; legacy frame 5 passes, 35 commands, 8 barriers, 0 violations.
  `ceil(1080/16) = 68` — the dispatch covers every edge pixel.
- **tables**: GL maps 39/42 formats (no sized sRGB R/RG storage in core GL),
  VK 41 + `UNDEFINED`→0; every enum literal verified against the spec value
  (`RGBA8=0x8058`, `BC1_SRGB=0x8C4C`, `RGBA8_SRGB=43`, `D32S8=130`,
  `BC7_SRGB=146`); barrier stages derived from access masks and pinned
  (`SHADER_READ → COMPUTE|FRAGMENT`, `MEMORY → ALL_COMMANDS`).
  The tables load with no LWJGL on the classpath — inlined constants only.

## Lanes

| Lane | State |
|---|---|
| core: rhi, graph, scene, uniform, biome, pack | done, gated |
| backend-null (recording + fail-fast) | done, gated |
| core: util (`Checks`, `MathUtil`, `Extents`) | done, gated |
| backend-gl (GL 4.6, LWJGL, compile-verified) | done, compiles rc=0 |
| backend-vulkan (VK 1.3, LWJGL, compile-verified) | done, compiles rc=0 |
| docs (README, ROADMAP, api/, plans/) | next |
| tools (packlint, drift-check) | after docs |
| packs/AuraLite-Native | after tools |
| fabric/ (Java 21 + Gradle/Loom) | source + json only; build lane SKIPs (needs JDK 21) |
| patches/ (one .patch per phase) | at phase close |

## Backends (compile-verified, no GPU in this sandbox)

- **GL** (`GlDevice` + `GlCommandBuffer`, ~1450 lines): immediate execution,
  `submit` is a flush; semantic DSA; push constants via a reserved 128 B UBO;
  descriptor unit = `set*16+binding`; RHI viewports flipped against pass height;
  FBO cache with hex completeness codes; handle 0 = the window, never mixed
  with offscreen targets; SPIR-V where `GL_ARB_gl_spirv` exists, GLSL otherwise.
- **Vulkan** (`VkDevice` + `VkCommandBuffer`, ~2300 lines): instance → device,
  validation behind `AURALITE_VK_VALIDATION=1`; discrete-GPU pick; per-family
  command pools; per-image layout tracker that wins over the graph's from-state
  (uploads happen outside the graph); render-pass keys include incoming layouts
  so LOAD passes survive frame 2; grow-only descriptor pools; mesh/ray features
  enabled when the driver offers them, capabilities reported accordingly.
- Both refuse foreign resources by name, enforce usage flags exactly like NULL,
  and keep the same failure vocabulary (`GfxException` with backend + offender).

Standing rule: host-buildable code stays Java 11 source-compatible so `make test`
is genuinely green here. `third_party/` holds gitignored LWJGL 3.4.3 jars fetched
by `make deps` (~11 MB); they are compile-only and never committed.
