# AuraLite-Render — status

Date: 2026-10-31. Host: OpenJDK 11, Linux x86_64, 2 CPUs.
Every number below is measured by `make test`, not estimated.

## Host gates: 8/8 green

```
rhi         51 passed   0 failed   0 NC   PASS
graph       81 passed   0 failed   1 NC   PASS
uniform     91 passed   0 failed   0 NC   PASS
biome      142 passed   0 failed   1 NC   PASS
nulldev    115 passed   0 failed   0 NC   PASS
pack       165 passed   0 failed   0 NC   PASS
tables     340 passed   0 failed   0 NC   PASS
fabric      23 passed   0 failed   0 NC   PASS
TOTAL 1008 passed, 0 failed, 2 negative controls, 8/8 suites green
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
  738 B, one vertex-only shadow warning. Native frame: 56 commands, 17 barriers,
  8160 work groups, 0 violations. Legacy pack: 6 programs, 7 options, coverage
  6/40; legacy frame 5 passes, 35 commands, 8 barriers, 0 violations.
  `ceil(1080/16) = 68` — the dispatch covers every edge pixel.
  The reference pack (`packs/AuraLite-Native`) loads 0 errors / 0 warnings
  and its own frame runs on NULL with 44 commands, 13 barriers,
  40560 work groups, 0 violations — its shipped GLSL is what gets validated.
- **fabric**: the Phase 4 sandbox deliverable is gated, not assumed — 7 glue
  sources (swapchain, pacing, hot-reload, labels, GL pipeline provider, loop,
  mod entrypoint), `fabric.mod.json` with client environment + `java >=21` +
  a matching entrypoint source, mixin config `JAVA_21` matching its target,
  gradle toolchain 21 reusing `third_party/` LWJGL, and the visible
  `fabric: SKIP - needs JDK 21` line in `make build`. The windowed-frame
  gate is deliberately NOT in this list: it needs a JDK 21 + GPU lane
  (standing rule 4).
- **tables**: GL maps 39/42 formats (no sized sRGB R/RG storage in core GL),
  VK 41 + `UNDEFINED`→0; every enum literal verified against the spec value
  (`RGBA8=0x8058`, `BC1_SRGB=0x8C4C`, `RGBA8_SRGB=43`, `D32S8=130`,
  `BC7_SRGB=146`); barrier stages derived from access masks and pinned
  (`SHADER_READ → COMPUTE|FRAGMENT`, `MEMORY → ALL_COMMANDS`).
  The tables load with no LWJGL on the classpath — inlined constants only.

## Tool gates

```
packlint    valid native pack: OK, exit 0 (capabilities checked via --null-device)
            broken pack: 4 named errors with file:line, REFUSED, exit 1
            warnings-only pack: exit 0; with --strict: exit 1
            legacy pack: OK, coverage 2/40, exit 0
            reference pack (packs/AuraLite-Native): OK --strict --null-device,
            0 errors, 0 warnings
drift-check 7 suite receipts + TOTAL match docs/STATUS.md and README.md, RESULT: PASS
            negative control: one graph count moved in a copy of STATUS.md ->
            named DRIFT line, exit 1
```

## Lanes

| Lane | State |
|---|---|
| core: rhi, graph, scene, uniform, biome, pack | done, gated |
| backend-null (recording + fail-fast) | done, gated |
| core: util (`Checks`, `MathUtil`, `Extents`) | done, gated |
| backend-gl (GL 4.6, LWJGL, compile-verified) | done AND RENDERED: gpu-test gl lane green on this host through zink (GL 4.6 over lavapipe; pin `LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=zink` - zink's choose_pdev rejects CPU-type Vulkan devices unless software is forced by the user). The pack's Vulkan GLSL dialect is adapted at compile time: `layout(set=S,binding=B)` -> `layout(binding=S*16+B)` (exactly the bridge's unit packing) and `gl_VertexIndex` -> `gl_VertexID`. Without the pin llvmpipe caps at 4.5 and the lane refuses by name - both states measured |
| backend-vulkan (VK 1.3, LWJGL, compile-verified) | done AND RENDERED: gpu-test vk lane green on lavapipe - 44 commands, 13 barriers, 6 passes, 0 violations raised, exit 0. The descriptor bridge + pass-compatible pipeline creation + `VK_EXT_vertex_attribute_robustness` (when offered; lavapipe doesn't offer it, so its location-0 advisory stays in the receipt) closed both original findings |
| docs (README, ROADMAP, api/, plans/) | done — api/ has RHI, PACK_FORMAT, GRAPH, UNIFORMS, BIOME |
| tools (packlint, drift-check) | done, gated — see Tool gates |
| packs/AuraLite-Native | done, gated — 0 errors, 0 warnings, NULL frame 0 violations; storage-capable usages declared (`biome_volume usage=sampled\|storage`, `ao usage=storage\|sampled`); ssao keeps its pass-level `read=scene_depth` but samples nothing until the sampler bridge (PACK_FORMAT) |
| fabric/ (Java 21 + Gradle/Loom) | sandbox deliverable done AND build-proven: `gradle build` rc=0 on OpenJDK 21.0.13 with loom 1.10.5 (production jar, 211 entries); `make build` prints the JDK-present fabric line; gated by the fabric suite (23); the windowed-frame gate is still NOT claimed |
| gpu-test (`make gpu-test`, Phase 5 lane) | GREEN on this host: vk RENDERED + gl RENDERED, both children answered, `make gpu-test` exit 0 (zink pin above for the GL 4.6 context). Not yet claimed: the plan's full GPU bar - first-frame pixel hashes, validation-clean, monotonic timestamps, a discrete card |
| patches/ (one .patch per phase) | done — `patches/phase-0-core-null.patch` (627/0, 6/6), `patches/phase-1-gl-vulkan-compile-verified.patch` (967/0, 7/7), `patches/phase-2-docs-and-tools.patch` (967/0 + tool gates), `patches/phase-3-native-reference-pack.patch` (985/0 + reference-pack gate), all replay-verified |

## Backends (compile-verified; first behaviour run via `make gpu-test` on a software GPU)

- **GL** (`GlDevice` + `GlCommandBuffer`, ~1450 lines): immediate execution,
  `submit` is a flush; semantic DSA; push constants via a reserved 128 B UBO;
  descriptor unit = `set*16+binding`; RHI viewports flipped against pass height;
  FBO cache with hex completeness codes; handle 0 = the window, never mixed
  with offscreen targets; SPIR-V where `GL_ARB_gl_spirv` exists, GLSL otherwise.
  The pack's Vulkan GLSL dialect is adapted at compile time (unit packing +
  `gl_VertexIndex` -> `gl_VertexID`), see PACK_FORMAT.
- **Vulkan** (`VkDevice` + `VkCommandBuffer`, ~2300 lines): instance → device,
  validation behind `AURALITE_VK_VALIDATION=1`; discrete-GPU pick; per-family
  command pools; per-image layout tracker that wins over the graph's from-state
  (uploads happen outside the graph); render-pass keys include incoming layouts
  so LOAD passes survive frame 2; grow-only descriptor pools; mesh/ray features
  enabled when the driver offers them, capabilities reported accordingly.
- Both refuse foreign resources by name, enforce usage flags exactly like NULL,
  and keep the same failure vocabulary (`GfxException` with backend + offender).
- Device extensions are gated on their dependencies (the swapchain stays off
  until a surface exists, the accel-structure family requires
  `VK_KHR_deferred_host_operations`); the host timestamp reset in `beginFrame`
  runs only with `VK_EXT_host_query_reset` enabled - both fixes came from
  validation output in the first gpu-test runs, together with
  `VK_EXT_vertex_attribute_robustness` for the unbound location-0 read that
  fullscreen-triangle packs ask for (enabled when the driver offers it).
- **The graph's descriptor bridge**: `PackProgram` parses
  `layout(set, binding, format) uniform image/sampler name` declarations from
  the GLSL stages - the uniform's name IS the graph resource name, no other
  mapping (PACK_FORMAT "Descriptors and draws") - `layout()` becomes the
  pipeline layout every provider attaches, and the pass body binds one set
  per declared set index right after the pipeline, then draws `draw(3)`: one
  fullscreen triangle per raster pass. The pipeline's `GfxDesc.RenderPass`
  mirrors `beginRasterPass`'s attachments exactly, so pipeline/render-pass
  compatibility holds on Vulkan.

### gpu-test receipts on this host (software GPU: lavapipe + llvmpipe + zink, Mesa 25)

```
$ LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=zink make gpu-test   # exit 0
GPU vk: device "llvmpipe (LLVM 19.1.7, 256 bits)", validation ON (AURALITE_VK_VALIDATION=1)
[auralite-vk] vkCreateGraphicsPipelines(): pCreateInfos[0].pVertexInputState->pVertexAttributeDescriptions does not have a Location 0 but vertex shader has an input variable at that Location. (This can be valid if vertexAttributeRobustness feature is enabled).
[auralite-vk] vkCreateGraphicsPipelines(): pCreateInfos[0].Inside the fragment shader, it writes to output Location 0 but there is no VkSubpassDescription::pColorAttachments[0] and this write is unused.
[auralite-vk] vkCmdPipelineBarrier(): pImageMemoryBarriers[0].newLayout (VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) is not compatible with VkImage 0xb000000000b usage flags VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT.
[auralite-vk] vkCmdPipelineBarrier(): pImageMemoryBarriers[0].oldLayout (VK_IMAGE_LAYOUT_PRESENT_SRC_KHR) requires the extensions VK_KHR_swapchain.
[auralite-vk] vkQueueSubmit(): pSubmits[0].pCommandBuffers[0] command buffer VkCommandBuffer 0x7f85b9f88db0 expects VkImage 0x50000000005 (subresource: aspectMask = VK_IMAGE_ASPECT_COLOR_BIT, mipLevel = 0, arrayLayer = 0) to be in layout VK_IMAGE_LAYOUT_PRESENT_SRC_KHR--instead, current layout is VK_IMAGE_LAYOUT_UNDEFINED.
GPU vk: RENDERED - 44 commands, 13 barriers, 6 passes, 0 violations (none raised), 1280x720 on "llvmpipe (LLVM 19.1.7, 256 bits)"
GPU SMOKE CHILD vk: answered
GPU gl: EGL context 4.6, "zink Vulkan 1.4(llvmpipe (LLVM 19.1.7, 256 bits) (MESA_LLVMPIPE))", GL "4.6 (Core Profile) Mesa 25.0.7-2+deb13u1", GLSL "4.60", core=true
GPU gl: RENDERED - 44 commands, 13 barriers, 6 passes, 0 violations (none raised), 1280x720 on "zink Vulkan 1.4(llvmpipe (LLVM 19.1.7, 256 bits) (MESA_LLVMPIPE))"
GPU SMOKE CHILD gl: answered
GPU SMOKE: vk answered, gl answered (answered = RENDERED or refused/skipped BY NAME)
```

- Both lanes execute the reference pack's frame: 44 commands / 13 barriers /
  6 passes - the same numbers `make test` counts on NULL (the receipt IS the
  predicate: submit + waitIdle of the real command buffer, child JVM isolated).
- The five `[auralite-vk]` lines are validation-layer advisories printed
  during the run and kept verbatim here; none raised into our violations
  counter, and each has a name and a fix path: (1) location-0 vertex input -
  `VK_EXT_vertex_attribute_robustness` is wired but lavapipe does not offer
  it; (2) the depth-only shadow pass' fragment shader writes a color output
  that pass never attaches; (3) `scene_depth` transitions to
  `SHADER_READ_ONLY_OPTIMAL` while the pack declares `usage=depth` only;
  (4)/(5) the imported backbuffer uses `PRESENT_SRC_KHR` layouts on a device
  that has no swapchain extension yet.
- GL needs the zink pin on this box: without `LIBGL_ALWAYS_SOFTWARE=1
  GALLIUM_DRIVER=zink`, Mesa's default llvmpipe context tops out at GL 4.5
  and `GlDevice` refuses by name (REFUSED-NAMED - measured, both ways).
  With it, zink exposes GL 4.6 over lavapipe (zink_screen.c: CPU-type
  Vulkan devices are rejected unless software rendering is forced).
- Not yet claimed (the plan's full GPU bar): first-frame pixel hashes,
  validation-clean, monotonic timestamps, and a discrete NVIDIA/AMD card.

Standing rule: host-buildable code stays Java 11 source-compatible so `make test`
is genuinely green here. `third_party/` holds gitignored LWJGL 3.4.3 jars fetched
by `make deps` (~11 MB); they are compile-only and never committed.
