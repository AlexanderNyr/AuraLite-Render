- **r63: the receipt said a number no buffer held, the plan file had merge
  markers, and the r42 teardown was gone** (2026-11-07). The r62 run is the
  first session that reached the world instead of dying before it: the
  NoSuchMethodError is closed, the pack survived 600/1200/1800-frame
  heartbeats, and the seam upload printed
  `upload=v2 bytes=205252 nanos=600500`. Three things that run exposed:
  (1) `biome/bake` says `rebuilt from 0 of 529 chunk columns ... 0 filled /
  51313 unknown` - the first bake ran at spawn with an empty client chunk
  pool, stamped the window as built anyway, and never retried; fixed by
  making an unanswered pass leave the stamp off and retry on the throttle,
  with `no-manager` / `no-chunk` refusal counts so a dead field names its
  reason. (2) the process died again with `0xC0000409`, and
  `auralite-trace-4.txt` puts the death on the FIRST frame after the fourth
  F9 reload - after three rebuilds that each cost ~3.0 s on their first
  frame; the r42 fix for exactly this (cached attachment views and
  framebuffers surviving `VkImage.close()`) is not in the tree, only in the
  prose. Restored as `ResourceCacheOwnership` in core (host-gated, with a
  negative control) plus a timed `waitIdle` between the new graph's uploads
  and the old graph's release, and the rebuild line asks the Vulkan device
  for `imagesLive= released= viewsTorn= fbosTorn=` again. The purge is keyed
  on identity, not membership: an owned view or framebuffer is destroyed only
  while the cache still holds that same object under that key. The first cut
  swept `framebufferCache.remove(handle)` instead, which evicted a still-live
  framebuffer that merely shared the handle and let the next `close()` destroy
  it twice - `make gpu-test` failed 3/3 runs on that version and answers 3/3
  with 0 validation violations on this one. (3) the bake line
  printed `51313 ids packed (401 764 B)` beside an upload of 205 252 bytes -
  23x97x23 cells is 51 313 cells and the pack's `r32_uint` layout moves one
  int per cell: 401 764 is the `r32g32_uint` figure, i.e. a number no buffer
  held. Bytes now come from the upload state, with the layout named;
  `present/frame: sha256=…` now hashes the whole pack output every 600
  frames, because one centre pixel once per session is not a frame receipt.
  Also closed while measuring: `AURALITE_VK_VALIDATION=1` could not be
  enabled at all (the r62 `pfnUserCallback(null)` spelling NPEs inside
  LWJGL 3.3.3 before any Vulkan call) - the validation layer stays, the
  messenger is gone by decision, and the gate that exercises it
  (`make gpu-test`, which defaults the flag ON) is back in the closing
  sequence. `docs/plans/BIOME_PLAN.md` had unresolved `<<<<<<< ours` /
  `>>>>>>> theirs` markers committed into it; they are resolved and step 11
  is added. Gates: `make test` **1320/0**, 7 NC, 9/9 (rhi 59, biome 301,
  fabric 46), `make gpu-test` vk+gl answered (3/3, 0 violations), `drift-check` PASS,
  `packlint --strict` OK, `gradle compileJava` + `remapJar` green.
  B5 stays OPEN: the world frame pair and a filled-window session log are
  still owed, and no frame is claimed that this run did not measure.


# AuraLite-Render — status

Date: 2026-11-01. Host: OpenJDK 11, Linux x86_64, 2 CPUs.
Every number below is measured by `make test`, not estimated.

## Host gates: 9/9 green

```
rhi             63 passed   0 failed   0 NC PASS
graph           90 passed   0 failed   1 NC PASS
uniform         91 passed   0 failed   0 NC PASS
biome          290 passed   0 failed   5 NC PASS
nulldev        115 passed   0 failed   0 NC PASS
pack           209 passed   0 failed   0 NC PASS
tables         340 passed   0 failed   0 NC PASS
fabric          57 passed   0 failed   0 NC PASS
view            69 passed   0 failed   1 NC PASS
TOTAL 1324 passed, 0 failed, 7 negative controls, 9/9 suites green
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
| backend-gl (GL 4.6, LWJGL, compile-verified) | done AND RENDERED: gpu-test gl lane green on this host through zink (GL 4.6 over lavapipe; pin `LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=zink` - zink's choose_pdev rejects CPU-type Vulkan devices unless software is forced by the user). The pack's Vulkan GLSL dialect is adapted at compile time: `layout(set=S,binding=B)` -> `layout(binding=S*16+B)` (exactly the bridge's unit packing) and `gl_VertexIndex` -> `gl_VertexID`. Without the pin llvmpipe caps at 4.5 and the lane refuses by name - both states measured; frame receipts carry the pixel hash + monotonic timestamps, and readImage refuses the window handle (0) by name |
| backend-vulkan (VK 1.3, LWJGL, compile-verified) | done AND RENDERED: gpu-test vk lane green on lavapipe - 44 commands, 13 barriers, 6 passes, 0 violations raised, exit 0. The descriptor bridge + pass-compatible pipeline creation + `VK_EXT_vertex_attribute_robustness` (wired and offered when the driver supports it; the reference pack no longer needs it - placeholder positions are literals) closed both original findings, and the receipt is validation-clean; readback refuses a missing IMAGE_TRANSFER_SRC by name, and while swapchain stays off the PRESENT state maps to GENERAL |
| docs (README, ROADMAP, api/, plans/) | done — api/ has RHI, PACK_FORMAT, GRAPH, UNIFORMS, BIOME |
| tools (packlint, drift-check) | done, gated — see Tool gates |
| packs/AuraLite-Native | done, gated — 0 errors, 0 warnings, NULL frame 0 violations; storage-capable usages declared (`biome_volume usage=sampled\|storage`, `ao usage=storage\|sampled`); ssao keeps its pass-level `read=scene_depth` but samples nothing until the sampler bridge (PACK_FORMAT) |
| fabric/ (Java 21 + Gradle/Loom) | done AND RUN IN THE REAL GAME: `gradle build` rc=0; **real receipt from a discrete NVIDIA card** (Windows 10, Prism, GT 710, driver 475.14, Minecraft 1.21.1): the game context (GL 3.2) is refused BY NAME, the loop falls back to a hidden shared 4.6 context, the reference pack loads (`pack loaded: ... 10 program files`, `renderer ready: NVIDIA GeForce GT 710 | 4.6.0 NVIDIA 475.14`) and the loop ran **6692 successful frames in one session** (GUI counter). The windowed PRESENT IS CONFIRMED (standing rule 4 closed): the one-shot readback of the DEFAULT framebuffer before the swap reads `(128,128,0,255)` - the pack final's gradient centre - and the user's screenshot shows the pack's gradient filling the Minecraft window on the GT 710. Chain receipted end to end: offscreen RGBA8 -> split READ/DRAW blit -> back buffer -> swap -> screen. Present is OFF by default (the game plays out of the box) and the user's
  own session toggles it with F8 at will - a dozen `present enabled` /
  `present disabled; vanilla frame shows` pairs in one run, each enable
  re-reading `(128,128,0,255)` from the back buffer |
| gpu-test (`make gpu-test`, Phase 5 lane) | GREEN on this host with the frame evidence IN the receipts: vk RENDERED + gl RENDERED, pixel sha256 (readback, non-zero required) + strictly-increasing GPU timestamps, `make gpu-test` exit 0 (zink pin above for the GL 4.6 context). Validation-clean delivered (banner on, zero advisory lines); remaining for the plan's full bar: a discrete card |
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
  `VK_EXT_vertex_attribute_robustness` for unbound vertex inputs
  (enabled when the driver offers it; the reference pack dropped its
  dependency by writing placeholder positions literally).
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
GPU vk: RENDERED - 46 commands, 13 barriers, 6 passes, 0 violations (none raised), 1280x720 on "llvmpipe (LLVM 19.1.7, 256 bits)"; pixels sha256=149d2b372d04e9169073f74ca8902d5324d44ead5c496e18391e8107b955ab59 nonzero=2761360/3686400; timestamps 346488523429 < 346606458058
GPU SMOKE CHILD vk: answered
GPU gl: EGL context 4.6, "zink Vulkan 1.4(llvmpipe (LLVM 19.1.7, 256 bits) (MESA_LLVMPIPE))", GL "4.6 (Core Profile) Mesa 25.0.7-2+deb13u1", GLSL "4.60", core=true
GPU gl: RENDERED - 46 commands, 13 barriers, 6 passes, 0 violations (none raised), 1280x720 on "zink Vulkan 1.4(llvmpipe (LLVM 19.1.7, 256 bits) (MESA_LLVMPIPE))"; pixels sha256=5b4ed44eadb50f93015cdadd80556eaa4f7e98959fc1b3f63b692c8c78e97cc5 nonzero=2761360/3686400; timestamps 347131444457 < 347160880207
GPU SMOKE CHILD gl: answered
GPU SMOKE: vk answered, gl answered (answered = RENDERED or refused/skipped BY NAME)
```

- Both lanes execute the reference pack's frame: 46 commands (44 graph
  commands + 2 frame timestamps) / 13 barriers / 6 passes, and the receipts
  carry the evidence: `pixels sha256=` (mip-0 readback of the bound
  backbuffer through `GfxDevice.readImage`, non-zero bytes required -
  blank is refused, never claimed - and byte-stable across reruns on this
  host) and `timestamps t0 < t1` (strictly increasing GPU nanoseconds via
  `frameTimestamps()`, asserted). The GL lane used to hand the window
  (handle 0, no storage to hash) to the graph; it now renders offscreen
  where the pixels exist.
- The run is validation-clean with the banner on: zero `[auralite-vk]`
  lines. All five earlier advisories are gone, each receipted by its
  absence: shadow.frag writes no color output (the pass is depth-only),
  `scene_depth` declares `usage=depth|sampled` (the SHADER_READ_ONLY
  barrier is legal), the PRESENT state translates to GENERAL with a
  canonical UNDEFINED first-present transition while swapchain stays off
  (`VkDevice.swapchainEnabled()`), and - the last one - the two raster
  stages that read an unbound location-0 attribute now write their
  placeholder positions literally, so nothing asks
  `VK_EXT_vertex_attribute_robustness` to define anything. The ext stays
  wired and offered for future real vertex data; the reference pack no
  longer depends on it, and the pixel hashes did not move when the
  dependency went away (byte-identical frame).
- GL needs the zink pin on this box: without `LIBGL_ALWAYS_SOFTWARE=1
  GALLIUM_DRIVER=zink`, Mesa's default llvmpipe context tops out at GL 4.5
  and `GlDevice` refuses by name (REFUSED-NAMED - measured, both ways).
  With it, zink exposes GL 4.6 over lavapipe (zink_screen.c: CPU-type
  Vulkan devices are rejected unless software rendering is forced).
- Delivered: pixel hashes, monotonic timestamps, and validation-clean -
  both lanes, receipts above (banner on, zero advisory lines). The
  discrete card bar is met: NVIDIA GT 710 (driver 475.14) ran the fabric
  loop with the reference pack, 6692 frames, receipted in-game (fabric
  lane row). The windowed Phase 4 PRESENT is CLAIMED and closed: the
  back-buffer readback (128,128,0,255) before swap plus the on-screen
  gradient screenshot on the GT 710 - rule 4 satisfied. Remaining for a
  playable picture: scene/camera bridging (the reference pack's final
  stage is a test gradient by design) and the Present toggle now in the
  GUI for play vs demonstration.
- r17 (2026-11-02): the vanilla frame reaches the pack. `SceneCapture`
  blits the game's default framebuffer - the world Minecraft just drew,
  between `Framebuffer.draw` and the swap - into an RGBA8 image the graph
  binds as the imported `scene` resource (usage=storage; the descriptor
  bridge wires storage images, samplers are still the "(yet)" case). The
  reference pack's final stage now reads `scene` instead of drawing a
  test gradient: F9 cycles the const `DEBUG_VIEW` (scene/luma/rgb/grid),
  each value reaching the shader as a `#define` through the option lane
  (`PackOption.defineFor` grew a CONST arm; `GlPipelineProvider` now
  prepends `PackManifest.activeDefines()`, which was dead code until
  this). Session-forced options survive hot-reload (hostOptions are
  re-applied after every load). Gates here: `make test` 1008/0, 8/8
  suites, reference pack loads 0 errors 0 warnings and runs its frame
  with 0 contract violations including the new present-reads-scene
  access; fabric jar rebuilt 442,655 B. On-screen proof stays rule 4's
  business: the GT 710 run must show the world through F8 and the four
  F9 views before any of this is claimed presented.
- r18 run receipt (2026-11-02, GT 710): the pack dir on the player's
  machine still held the pre-scene `pack.aurapack`, so the first frame
  died in `bindImported("scene")` - GraphException: unknown resource
  'scene', frames 0, F9 refused with "no live device" (screenshot +
  full log). The host must not depend on the pack being current:
  `reloadPack` now binds `scene` only when `manifest.graph.findResource`
  finds it and logs how to update `config/auralite-render/pack` from the
  kit otherwise - an old pack renders exactly as before. Gates after the
  fix: `make test` 1008/0, 8/8; jar 442,839 B. The on-screen world
  through F8 (and the four F9 views) is still the unclaimed part -
  rule 4.
- r19 run receipt #2 (2026-11-02, GT 710): with the pack updated the
  loop got further and died compiling `shadow.vert` - C0105 "Syntax
  error in #define", four times. Cause: `activeDefines()` emits whole
  `#define NAME value` lines while `ShaderSource.preprocessed()`
  prepends `#define ` itself, so every option line reached GLSL as
  `#define #define ...`. The NULL device never sees it (structure
  checks only), which is exactly why the gates here are gpu-test's
  real compiler too. Three fixes rode out together: (1) the option
  lane strips the prefix before `withDefines`; (2) GpuSmoke binds and
  fills the imported `scene` (a pack that reads the capture needs a
  capture); (3) two GL-backend spec bugs the new final stage exposed -
  `uploadImage` now closes with `glMemoryBarrier`, and
  `glBindImageTexture` passes the texture's sized internal format
  instead of 0 (format-0 binds leave imageLoad reading zeros on Mesa
  GL - measured with a 40-line standalone probe: same texture, same
  upload, GL_RGBA8 reads fine, 0 reads nothing; the Vulkan lane never
  makes that call). Gates after the trio: `make test` 1008/0;
  `gpu-test LANE=gl` RENDERED nonzero=3686400/3686400 and
  `gpu-test LANE=vk` RENDERED with the byte-identical sha256
  90f1a0c4... - both lanes, same pixels, timestamps increasing.
  On-screen world through F8/F9 remains rule 4's to collect.
- r20 run receipt (2026-11-02, GT 710, driver 475.14): the scene phase
  closes under rule 4. The player's session shows the world through the
  pack on screen in all four DEBUG_VIEW modes - scene as-is, luma
  (grayscale landscape), rgb (red channel), grid (64px orange overlay
  over the colour world, also over the main menu) - five screenshots,
  plus the in-game receipt `present: blit 1280x720 -> default fb ...;
  backbuffer center rgba=(48,48,48,255)` (the luma frame read back
  before swap), `debug view -> N (scene/luma/rgb/grid)` on every F9,
  `pack loaded` on each cycle, GUI RUNNING with frames=11086, clean
  shutdown (exit 0). Both artefacts of rule 4 - the screenshot and the
  readback - exist and agree. Known cosmetic note for the record: the
  vanilla F2 screenshot reads Minecraft's own mainTarget and therefore
  does not carry the pack's present (the window does); a session leak
  counter also reports 240 live shader modules after ~30 F9 rebuilds
  (cached modules are not dropped on reload - cosmetic, not a gate).
- r21 fixes (2026-11-02, both cosmetic notes from the r20 run now
  closed at the source): (1) F2 screenshots - `RendererLoop.present()`
  now lands the pack frame a SECOND time, into Minecraft's own
  `getFramebuffer().fbo`, after the screen blit; MC's F2 reads that
  mainTarget in `handleInputEvents` (render offset 363) and it never
  carried the pack, so screenshots came out vanilla while the window
  showed the pack. The screen path (default fb, rule 4) is untouched,
  F8-off skips both landings so the screenshot matches the vanilla
  frame the button promises. Receipt to collect: the player's next F2
  file showing the pack (rule 4 for the F2 path). (2) Shader module
  leak - `GlPipelineProvider.invalidate()` now closes the stages it
  created along with the pipelines (~8 modules leaked per F9 rebuild,
  240 live after ~30 reloads); `close()` is idempotent so device
  shutdown may close them again. Receipt to collect: a session's
  shutdown `live modules` staying flat across F9 rebuilds. Gates after
  both fixes: `make test` 1008/0; `gpu-test LANE=gl` and `LANE=vk`
  (validation ON) RENDERED nonzero=3686400/3686400, byte-identical
  sha256 90f1a0c4... on both lanes; fabric jar rebuilt 443490 bytes.
- r22 run receipt (2026-11-02, GT 710, driver 475.14, Prism/Win10):
  both r21 fixes close under rule 4. F2 screenshots now carry the
  pack: the player's four files from `screenshots/` show every
  DEBUG_VIEW mode through Minecraft's own screenshot path - luma
  (15.49.31), rgb (.32), grid (.33 over the colour world), scene
  (.34) - next to the window receipt `present: blit 1280x720 ->
  default fb 27 (FBO 11); ctx game=yes`, so the F2 path joins rule 4
  as closed. The module leak is closed too: shutdown after ~7 F9
  rebuilds reports `live ... modules=0` (240 after ~30 reloads in
  r20). Session: pack loaded on every cycle, `debug view -> N` on
  each F9, GUI RUNNING frames=75378, clean exit 0.
- frame-context bridge (2026-11-02): the glue fills FrameContext every
  frame before the graph runs (`FrameBridge.fill`, called after
  `beginFrame` so the previous-frame snapshot stays honest): camera
  eye/yaw/pitch, projection from Minecraft's own
  `getBasicProjectionMatrix` (1.21.1 bytecode-verified: perspective(rad
  fov, fbAspect, 0.05, far)), view rebuilt by core `ViewMatrix` (the
  game's own GL matrices are GUI-owned by hook time - render(Z)V calls
  setProjectionMatrix twice, the second with the GUI ortho), plus
  time/day/sunAngle/isNight/moonPhase, weather + wetness, dimension,
  render distance, eye altitude, hideGui; then `finishCameraUpdate()`
  derives inverses, view-projection and both frusta. NOT bridged yet,
  stale defaults on purpose: hasSkylight, bedrock/height limits,
  ambient, eye-in-water, held items - a wrong receipt is worse than a
  stale one. New host gate: the `view` suite (69 checks - look
  agreement over 56 angle pairs, polar pitch, eye translation,
  orthonormality + det +1, 1 negative control) and 7 source pins in
  `fabric` (fill-after-beginFrame order among them): `make test`
  1084/0, 3 NC, 9/9 green; `drift-check` PASS with the doc lines moved
  to match. Also fixed `TestRig.negativeControl`: it re-tested the
  failure counter after rolling it back, so only a throwing control
  could ever be credited - check()-based controls count now. GPU lanes
  not re-run for this phase (data-only fill, no GL/VK path touched);
  the in-game receipt to collect is the one-shot `framectx: ...` log
  line.
- r24 receipts + world-line fix (2026-11-02): the framectx chain is
  live on the GT 710 - the session's one-shot `framectx: eye=(0,0,0)
  ... proj[0]=0.8033332 view[0]=-1.0 viewProjection[0]=-0.8033332`
  printed on the first frame (main menu, no world: tod=-1 in that
  line is the designed stale marker), and the numbers check out:
  cot(35 deg) / (1280/720) = 0.8033 is exactly
  perspective(fov 70, aspect), and vp = proj * view follows from it.
  A menu line cannot prove the world fields, so the bridge now prints
  a second one-shot `framectx/world:` at the first frame with a live
  world - that receipt is owed next run. Host gate re-run on a second
  machine (WSL2 Ubuntu 26.04, OpenJDK 21.0.12.1): `make test` 1084/0,
  3 NC, 9/9 PASS - the doc numbers hold on hardware this repo does
  not own. `make gpu-test` there: vk RENDERED byte-identical sha
  90f1a0c4... but on llvmpipe (LLVM 21.1.8), gl REFUSED-NAMED
  (llvmpipe caps at 4.5 where the backend needs 4.6; /dev/dri denied
  under WSL) - the GT 710 is not visible inside WSL, so Phase 5's
  discrete-card gate stays owed (native Windows, or a GPU-visible
  WSL/Docker once the NVIDIA ICD is present). Gates for this fix:
  `make test` 1084/0, 9/9; fabric jar rebuilt 448047 B.
- r25 run receipt (2026-11-02, GT 710, Prism/Win10): the world line
  lands and checks out arithmetically - `framectx/world:
  eye=(-23.592139869347488,136.85393295419917,73.35093515001122)
  yaw=6.4507165 pitch=10.050014 fov=70.0 | time=67458 tod=4457
  day=0 sun=0.18570833 night=false moon=0 weather=CLEAR wet=0.0
  dim=OVERWORLD rd=12 | proj[0]=0.8033332 view[0]=-0.99366885
  vp[0]=-0.7982472`: the eye is exactly the login position from the
  same log, sun = 4457/24000 to the last digit, view[0] = cos(186.45
  deg) = cos(yaw + 180), vp = proj * view follows. Session: menu line
  first (tod=-1 stale marker by design), F9 through all four modes,
  present receipt (214,214,214,255 luma readback), F2, shutdown
  modules=0, exit 0. The frame-context phase closes under rule 4.
  WSL re-run (fresh clone 453 objects): `make test` 1084/0 9/9 PASS
  again; `make gpu-test` unchanged - vk RENDERED on llvmpipe with the
  byte-identical sha 90f1a0c4..., gl REFUSED-NAMED (llvmpipe 4.5);
  the discrete GT 710 still never appears inside WSL, Phase 5 stays
  owed and is being moved to a native Windows runner (see the kit's
  gpu-test-win lane).

- Native Windows gate (2026-11-02, kit r28, gpu-test-win\run-gpu-test.bat,
  LANE=vk, Adoptium 17): `GPU vk: RENDERED - 47 commands, 13 barriers,
  6 passes, 0 violations (none raised), 1280x720 on "NVIDIA GeForce GT
  710"; pixels sha256=90f1a0c49a1c4d5be3fb6f43bc60696b8d53c443b9f3e68cf24c8a63d3c6a3dd
  nonzero=3686400/3686400; timestamps 1793647842679092256 <
  1793647842681704480`, exit 0. The discrete GT 710 renders the smoke
  frame in Vulkan and is validation-clean (0 violations; Khronos layer
  absent on this host, VkDevice skipped it by name). The pixel sha is
  byte-identical to the llvmpipe receipts from WSL and the sandbox
  (90f1a0c4...) - cross-driver determinism of the smoke frame. Phase
  5's discrete-geometry gate closes here. Kit tooling: packs/
  AuraLite-Native shipped in the kit (repo-root requirement),
  glslangValidator 16.6 in bin\ (SPIR-V), default lane vk - Windows
  has no NVIDIA libEGL.dll, so the gl lane stays covered by WSL
  (llvmpipe) and the game's WGL path.

- Phase 4 VK-windowed, code landed (2026-11-03): `-Dauralite.backend=vk`
  (or `AURALITE_BACKEND=vulkan`) runs the fabric loop on `VkDevice`
  offscreen; GL stays the default lane. `VulkanPipelineProvider` compiles
  the pack through glslangValidator (`-Dauralite.glslang`, then
  `config/auralite-render/bin/`, then PATH) with the option defines in the
  text hash; `SceneCapture` uploads the vanilla frame GL -> VK with no row
  flip (both samplers read row 0 at v=0); present reads the VK backbuffer
  back (`readImage`, device side) and uploads it into a preview GL texture
  with a row flip (GL row 0 = screen bottom, VK row 0 = screen top - both
  backends keep RHI top-left through their viewport flips), then the closed
  GL blit and default-fb receipt run unchanged (rule 4 preserved). Host
  gate `make test` 1084/0, 9/9; fabric lane `gradle remapJar` BUILD
  SUCCESSFUL, jar 456,983 B. **NOT CLAIMED PRESENTED**: the windowed VK
  frame still owes its GPU receipt - run the kit's jar with
  `-Dauralite.backend=vk`, press F8, and file the present line plus the
  orientation check (F9's scene view must read upright, same as the GL
  lane); glslangValidator goes in `config/auralite-render/bin/` (copy
  from the kit's `gpu-test-win/bin/`). Phase 4 stays open until that
  receipt exists.

- Phase 4 VK-windowed gate CLOSED (2026-11-03, kit r34, Prism/Win10,
  GT 710, `-Dauralite.backend=vk`): the windowed VK frame landed with
  its receipts. Startup chain: `device = Vulkan (NVIDIA GeForce GT 710)`
  -> `pack loaded: AuraLite Native Reference ... 94245 us, 10 program
  files` (glslangValidator lane) -> `renderer ready: NVIDIA GeForce
  GT 710 | 475.56.0 | backend AuraLite-Render VK13`. F8: `present: blit
  1280x720 -> default fb 61 (FBO 6); ctx game=yes current=...; backbuffer
  center rgba=(140,174,199,0)` - sky at the operator's sight line, read
  from the default framebuffer after the blit (rule 4). Orientation: the
  r33 run is the first frame ever seen on this lane and it came out
  upside down - the present upload's row flip was compensating a row
  order the readback does not have (the "VK row 0 is the top" note in
  the code-landed entry above was a pre-first-frame guess); the flip was
  removed in kit r34, and the operator confirms the world reads upright
  with present on, F9's luma/rgb/grid views upright, F2 screenshots
  matching. Known defect filed as the next item: the process dies with
  `0xC0000409` ~15 s after an F9 debug-view rebuild, with no `Stopping!`
  line and no `hs_err_pid*.log` - the native fastfail lands outside the
  JVM before CLIENT_STOPPING can run, so the r34 shutdown step receipts
  (`begin`/`waitIdle ok`/`device closed`) never print. Host gates
  unchanged: `make test` 1084/0, 9/9; `make gpu-test LANE=vk` RENDERED
  sha 90f1a0c4... byte-stable; patch applies clean on a fresh clone of
  26c7098 (APPLY-OK + 1084/0/9/9). Phase 4 closes here under rule 4.

- VK-lane stability receipts (2026-11-03, kits r35-r37, GT 710): the r35
  isolation run excluded F9 (never pressed) and pinned the crash to an AV
  inside nvoglv64.dll (hs_err_pid9360.log, random timing); r36 replaced
  the synchronous glGetTexImage with a two-PBO glReadPixels ring and
  deferred the present readImage one frame - the crash did not come back
  and the shutdown chain ran clean for the first time (`Stopping!` ->
  `shutdown: begin` -> `waitIdle ok` -> receipt -> `device closed`,
  exit 0). Two costs surfaced in that run: present FBO incomplete 0x8cd6
  on the first F8 frame (the deferred readback means no pixels means no
  texture storage; the loop died and the operator saw 100 fps of vanilla
  with the module stopped), and F8-off stayed at 25 fps - the scene
  upload's synchronous one-shot waited for the previous frame's pack
  every frame. r37 fixes both: VkDevice one-shots are fence-ringed (an
  upload submits and never waits; the oldest fence is retired before the
  next submit and sits ahead of the pack, so that wait is instant;
  staging dies with its fence; waitIdle/close drain the ring), and the
  preview texture allocates storage up front while present blits only
  after everFilled - the vanilla frame shows until real pixels arrive.
  Host gates: `make test` 1084/0, 9/9; `make gpu-test LANE=vk` RENDERED
  sha 90f1a0c4... byte-stable through the async change. The r37 Windows
  run then landed the F8-off baseline at 45 fps (the upload wait is
  gone) and, with F8 on, reproduced the nvoglv64 AV after ~35 s of
  gameplay - hs_err_pid9540.log pinned it precisely: the Render thread
  died inside `glTexImage2D` called from `WindowSwapchain.colorTexture()
  +308` (pc nvoglv64.dll+0xea6ff0, the same PC as r35, code
  0xC0000409), i.e. the per-frame preview upload, not the blit and not
  the PBO ring. r38 changes that call: storage for the extent already
  exists, so pixels now land via glTexSubImage2D (no per-frame texture
  reallocation inside driver 475.56.0), from one reused scratch buffer
  (no fresh 3.6 MB memAlloc per frame), with GL_UNPACK_ROW_LENGTH /
  SKIP_* pinned to 0 in case vanilla left them set - any of which could
  otherwise make the driver read past the buffer. F8-off 45 fps and the
  r37 gpu-test receipts stand. The r38 Windows run: four minutes of F8
  gameplay with no crash and a clean exit 0 (shutdown chain intact),
  fewer freezes at 15-20 fps with F8 on - then a second run hit the
  same 0xC0000409 AV after F9 cycling, and the operator could not
  locate hs_err_pid12056.log (Prism writes it to the instance
  directory, one level above minecraft\); the crash stays open pending
  that file. The same run exposed a separate defect: F9 logged
  `debug view -> N` but the screen never changed, on-screen and in the
  F2 screenshots alike. Root cause (r39): the Vulkan lane compiled
  SpirvCompiler's raw `src.text()` while only the GL lane went through
  `ShaderSource.preprocessed()` - the pack's option defines physically
  live there - so `#if DEBUG_VIEW == 1` saw an undefined identifier
  (GLSL evaluates that to 0) and always took the scene branch; the
  compile cache key was `text().hashCode()`, which ignores defines.
  r39 compiles preprocessed(), keys the cache on the full source hash
  (defines included), and logs `pack defines: ...` on every reload so
  the option lane's receipt is in the log itself.

  r39 was verified on the GT 710: the log shows
  `pack defines: ... #define DEBUG_VIEW 1` after F9 and screenshot
  2026-11-04_10.01.56.png is the grey luma world - the debug view
  reaches pixels, F9 is closed. The same run crashed again
  (0xC0000409, ~40 s after present enabled with two debug-view
  reloads), and, like the r38 run 2, left no hs_err anywhere on the
  machine - the only hs_err files present are the older r35-r37 ones.
  A crash that leaves no dump cannot be diagnosed from the outside, so
  r40 makes the run describe its own death: every reload logs its
  stage (source, parsed, pipelines, compiled, allocated, releasing the
  old graph, done), the loop records the stage and the frame count per
  frame and logs a heartbeat every 600 frames with the GL state a
  faulting driver trips over (first error, PIXEL_PACK/UNPACK buffer
  bindings, draw/read FBO, bound texture), and a shutdown hook - the
  one thing that runs whenever the process dies, hs_err or not -
  writes `auralite-crash.txt` next to the game log with the last
  stage, the device counters and every thread's stack. Nothing in r40
  changes rendering: the gates are unchanged (gpu-test vk pixels sha
  90f1a0c4 byte-identical), it only makes the next crash report
  The r40 run (10:54-10:58) delivered the receipts and moved the
  diagnosis: F9 is confirmed on hardware for all four views (luma grey,
  rgb red channel, grid lines, scene), and the crash is NOT tied to a
  debug view - it is tied to the NUMBER of rebuilds. Runs: no rebuild,
  four minutes, clean exit; two rebuilds, crash; four rebuilds, crash.
  The r40 run's last line is `debug view -> 0 (scene)` at 10:57:17 and
  then sixty seconds of complete silence - no heartbeat (which fires
  every 600 frames at ~16-20 fps), no shutdown log, no crash hook, no
  hs_err. Silence in the log as well as on disk means the process was
  killed from outside the JVM, which is exactly what 0xC0000409 from a
  display-driver fault looks like, and it explains both missing files:
  the hook never ran and log4j's tail died with the process. So r41
  writes an unbuffered trace straight to `auralite-trace.txt` (open,
  write, close - nothing to lose on a hard kill): every rebuild stage,
  the first three frames after each rebuild, and the frame counter
  every 20 frames. It also logs the device's live object counts after
  every rebuild, which is the leak receipt for the real backend, and
  GraphSuite gained a rebuild-cycle gate: four allocate/execute/release
  rounds on the pack's own graph shape must return the device to the
  same live image and buffer counts, so an internal leak is now a test
  failure rather than a driver fault. Rendering is unchanged
  (gpu-test vk sha 90f1a0c4).

  r42 is the fix that trace pointed at. The r41 run's auralite-trace.txt
  ends mid-run - `frame after rebuild 2`, the last line, three
  milliseconds after `frame after rebuild 1` - so the process did not
  die inside a rebuild and not while idle: it died on the third frame
  after a rebuild, exactly like the r37-r41 runs. That is the signature
  of a use-after-free, not of a bad call: the frames after a rebuild
  are the first ones to draw through the new graph's framebuffers while
  the previous graph's attachments were just freed. The Vulkan backend
  was doing precisely that: releasing a graph closed its images, but
  the framebuffers and image views cached in VkDevice outlived them -
  nothing in the backend ever destroyed a framebuffer (the lists exist
  and are only cleared in close()). A framebuffer pins the attachment
  views, and a view pins its image, so every rebuild left framebuffers
  referencing freed images, and the first draw through a stale one is
  the driver fault the GT 710 reports as 0xC0000409 - two or three
  frames later, with no Vulkan error and no Java exception. The count
  is stable because the caches are keyed by hashes that include the
  image handle: a stale entry is never hit, it just keeps a dangling
  view alive. r42 makes image teardown drop the caches first, scoped to
  images that actually have attachment views (staging images for
  readback keep the per-frame path untouched), and never destroys a
  view another live image still uses. Two receipts were added: the
  rebuild log now carries `fbos= viewOwners= invalidations=`, and
  GpuSmoke replays the host's rebuild sequence (compile, frame,
  release the old graph, frame) on the real backend with validation on.
  Honest limit, measured: the gate does NOT fail without the fix on
  lavapipe - that driver refcounts image views itself and survives the
  same sequence, so the gate proves the path works and would catch a
  gpu-side validation error, but the fault itself is Windows-driver
  specific and only the GPU run can close it.

  r42 run, GT 710, Windows 10, driver 475.56.0, VK13 - THE CRASH IS
  CLOSED. Ten minutes with present on, eighty rebuilds, and a clean
  exit 0 (shutdown: waitIdle ok -> labels receipt -> device closed).
  The run covers every shape that used to kill the process: single F9
  steps with minutes in between (15:57:36 luma, 15:58:52 rgb, 16:00:52
  grid, 16:03:10 scene - each survived, where the pre-r42 run died on
  the second), the all-four-views burst at 16:04:00 (four rebuilds in
  under a second, repeated four times), and present toggled off and on
  mid-run (16:03:36 / 16:03:46). The receipts carry the fix itself:
  every teardown that had attachment views reports
  `invalidations=1` and the framebuffer count stays 0, so the stale
  framebuffers that used to pin freed images no longer exist at all.
  The first rebuild after a pack whose attachments were never torn
  down shows `viewOwners=0 invalidations=0`, and every later one shows
  `viewOwners=1 invalidations=1` - exactly the before/after of the
  fix. Note for the record: the 16:04:00 burst produced
  `root.updateDisplay`-era frame-time warnings (100-550 ms) because
  four whole graphs were compiled inside one second - that is the cost
  of a dev keystroke, not a steady-state number. All four debug views
  are on screen in that run's screenshots (luma grey, rgb red, grid
  overlay, scene), and `make drift-check` now passes with the suite
  numbers moved to 1093/graph 90.

  One wrinkle worth recording: `drift-check` compares the suite receipts
  against `docs/STATUS.md` AND `README.md`, and the update patch
  deliberately does not carry `README.md` (the standing rule: the patch
  is fabric/core/packs/tests/docs only, README stays out). So a fresh
  clone with the patch applied passes `make test` 1093/0 but still
  fails `drift-check` on the README lines - by construction, not by
  drift. Clone verification therefore stays `make test` (the protocol
  every earlier revision used), while `drift-check` is a repository
  gate: it is green here, where README.md lives. Moving README's three
  number lines in a patch would fix the clone case and break the rule;
  that call belongs to the operator, not to this revision.

  r44 closes the paperwork the crash had been blocking, and starts Phase 6
  the only way the roadmap allows - with a document, not code.
  THREE things moved:
  (1) ROADMAP: Phase 4 and Phase 5 are marked closed with their receipts
  instead of the stale "PENDING" / "Open" paragraphs. Phase 4's closing
  receipt is the windowed frame on the GT 710 (the pack's own pixels read
  back from the default framebuffer before the swap, `rgba=(128,128,0,255)`
  then the world's `(161,202,223,0)`, present toggled by F8, shutdown exit
  0); Phase 5's is the vk lane rendering with a byte-stable hash plus the
  three later defects it took to make that lane survive a real session.
  (2) BIG_PLAN's gate table no longer claims "a discrete NVIDIA/AMD card
  remains owed": the same pack runs inside Minecraft on a GT 710 over VK13
  through 80 rebuilds with exit 0, which is that bar, and the row now says
  so with the receipt attached.
  (3) NEW `docs/plans/BIOME_PLAN.md` - the plan doc Phase 6 requires before
  any B5 code exists. It states the seam problem exactly (one cell = 4
  blocks is structural, not a knob, and the removed `blendRadius` control is
  cited rather than re-litigated), records what already exists so the work
  is scoped honestly (the field, the builder, both packer layouts, the LUT,
  and a compute pass in the reference pack that already has the plumbing),
  chooses the design that makes the seam cheap (blur the *baked volume* in
  compute, sample one texel instead of eight - wider seam for fewer
  fetches), names four refusals before any code (`BIOME_SEAM_R_TOO_WIDE`,
  `BIOME_VOLUME_IDS_OVER_TOPK`, `BIOME_VOLUME_STALE` with the note that a
  stale seam looks plausible and must therefore be loud, and "unknown cells
  stay unknown"), and lists five NULL-backend gates including the one that
  keeps the widening honest: at R = 0 the GPU rule must reproduce the CPU
  sampler exactly. It also refuses four things outright (a wider CPU kernel,
  anisotropic widths, baking the blur to disk, touching the legacy lane).
  Phase 6 stays "planned, not started" and B5 stays uncoded until gate 1 is
  green - the roadmap's rule, kept.
  Gates after the doc work: `make test` 1093/0 9/9, `make drift-check`
  PASS.

  r46 takes Phase 6's first code step - and only the first one. B5's plan
  said gate 1 comes before any shader, so the widened seam exists first as
  a CPU reference (`core/.../biome/BiomeSeam.java`) that states the rule in
  one place: a `(2R+1)^3` box of cells, a per-axis tent weight
  `max(0, R + 1 - |d|)`, duplicate ids merged, sorted by weight descending
  with ties broken by ascending id - the tie-break is not cosmetic, it is
  what lets a compute shader reproduce the same order - the top four kept and
  renormalised, all-unknown cells yielding an empty set, and a radius past
  four refused by name (`BIOME_SEAM_R_TOO_WIDE`) rather than silently
  clamped. The load-bearing decision is what `R = 0` means: not "sample the
  field at an arbitrary point" but "one cell, one id", which is exactly what
  `BiomeSampler` already does at a cell centre - so the widening is a
  strict extension of today's lookup, not a second opinion about it.
  Gate 1 is measured, not argued. Walking every cell of a border field,
  `BiomeSeam` at radius 0 reproduces `BiomeSampler.sampleCellCentre` at
  **all of them**: 0 mismatches in count, id or weight (1e-6). The width
  itself is receipted two ways: `seamWidthBlocks` reports 4 blocks at R = 0
  and 20 at R = 2, and a probe along 25 positions across a border counts
  blended positions - 4 for the sampler, 16 for the widened rule. Weight
  invariants (sum to 1, descending) hold at every radius 0..4, the blow-up is
  bounded and reported (`729` cells folded per output cell at the maximum
  radius, which is the budget the compute pass has to respect), and the
  refusal is checked by name.
  Gates after step 1: `make test` 1100/0 9/9 (+7 checks, all in the biome
  suite), `make gpu-test LANE=vk` unchanged (`sha 90f1a0c4...` - no render
  path touched, which is the point). Still owed, and deliberately not
  started here: the compute producer, `BIOME_SEAM_R` in `PACK_FORMAT`,
  `BIOME_VOLUME_IDS_OVER_TOPK`, `BIOME_VOLUME_STALE`, the fetch-count
  receipt and the GT 710 pixel hash.

  r47 is B5's second step: the producer, and the plan's remaining gates.
  `biome_field.comp` stops being a constant. It reads `biome_field_ids`
  (`r32_uint`, the exact ids) and writes `biome_volume` (`rgba8ui`, four
  packed ids in .x and four packed weights in .y) with `BiomeSeam`'s rule,
  once per baked cell instead of once per fragment.
  The decision worth recording is that the shader is GENERATED. A GLSL copy
  of the rule is a second implementation in a second language, and gate 1
  exists precisely because such copies drift while still compiling and still
  rendering - so `BiomeSeamShader` emits the text, `make shader` writes it,
  and `make shader-check` fails by name with the first differing line when
  the committed file and the generator disagree. The shader is still checked
  in: the pack must load with no toolchain and no network, which is the rule
  every other pack asset follows. `seamRadius` becomes a `[biome]` key
  (default 2, ceiling 4) and a pack past the ceiling is refused at load as
  `BIOME_SEAM_R_TOO_WIDE` with the words "nothing is clamped" - the plan's
  refusal, spelled the same way in the parser and in core.
  The gates are receipts, not claims. At R = 0 the emitted volume is
  BYTE-IDENTICAL to the TOPK packer's output over a border field: 0 id bytes
  and 0 weight bytes differ across 256 cells, which is a much stronger
  statement than the 1e-6 weight comparison gate 1 settled for, and it is
  available because both layouts quantise with the same remainder rule. Width
  at R = 2: 16 blended positions along the border line against 0 at R = 0, in
  a 20-block band. Determinism is proven the rude way as well as explained:
  two bakes agree byte for byte, and REVERSING the row order of the input
  moves no texel - the sums are integers (largest possible total 15,625, far
  below 2^24) so float32 holds them exactly, and the comparator fixes the
  only thing order could have changed.
  Both volume refusals are now code. `BIOME_VOLUME_IDS_OVER_TOPK`: an id a
  byte cannot carry is never truncated into a different biome - the host bake
  throws by name, and the shader marks that cell as a hole (255, zero
  weights) because a compute invocation cannot throw and an invented id is
  worse than an honest one. The refusal is per cell, not per volume: a wide
  id taints exactly the cells whose box reaches it, which the suite pins down
  as 2 of 4 in its fixture. `BIOME_VOLUME_STALE`: `BiomeSeamFreshness`
  compares the field's version with the version last baked and refuses a read
  by name, counting the refusal; a re-bake clears it. This is the failure
  mode the plan called the dangerous one - a stale seam looks plausible - so
  it is loud and it has a negative control standing in for "remove the check
  and the suite must go red". There are now three such controls on this lane,
  and five host-wide.
  Cost, stated as the plan demanded a number: the fragment path goes from 28
  fetches per sample (8 field + 20 LUT rows) to 21 (1 volume + 20 LUT) while
  the seam gets WIDER, and the bake's private table is 125 int/float pairs at
  R = 2 and 729 at the ceiling.
  Gates: `make test` 1178/0, 5 negative controls, 9/9 (biome 212, pack 180);
  `make shader-check` PASS on a 4,376-char shader; `glslangValidator -V`
  compiles it to SPIR-V (8,720 B) with no diagnostics; `make packlint
  PACK=packs/AuraLite-Native STRICT=1 NULL_DEVICE=1` OK, and it noticed on
  its own that the pack now requires INTEGER_TEXTURES; `make drift-check`
  PASS; `gpu-test LANE=vk` RENDERED with the SAME hash as r46
  (`90f1a0c4...`), which is the correct result and not a shortfall - nothing
  samples `biome_volume` yet, so a changed hash would have meant the bake
  leaked into the frame.
  What r47 does not claim: the staleness gate is not yet called from
  `PackGraphBuilder`, the volume's height is still the framebuffer's rather
  than the field's (the graph is built before any field is bound, so it has
  only the cell extent), and no pass reads the widened volume. Those are the
  first three items of B5's step 3, named rather than implied.

  r48 is B5's step 3, and it opens with a correction. r47's STATUS claimed the
  volume's height was "still the framebuffer's rather than the field's". That
  was wrong, and a re-probe says so plainly: `[resources]` declares
  `biome-volume`, whose extent is the field's cell extent, so the volume was
  23x97x23 in the probe and always had been. The gap was never the extent - it
  was that nothing read the volume. Correcting this in the same breath as
  closing it is the point of keeping receipts: a wrong "owed" line is a defect
  in the record, even when the code is fine.
  The read side is now `BiomeSeamSampler`: bilinear between the eight baked cell
  centres, entries merged by id, renormalised over the top four, holes
  contributing nothing, positions outside the volume absent rather than clamped.
  It reads the volume UNFILTERED on purpose, and this is the design decision
  worth stating: a linear sampler would interpolate the packed id BYTES, and
  the average of ids 12 and 40 is neither - slot order would be destroyed and
  ids invented. So the contract is "fetch a texel, interpolate the unpacked
  entries", which is also why the executor's storage-image-only bridge is
  sufficient for the read side and no sampler unit is needed.
  `BiomeSeamVolume` ties the arrays to the field they came from - extent from
  the field by construction, bake recording the version it saw, a read after any
  field write refusing as `BIOME_VOLUME_STALE` - and the refusal is now wired
  where the plan wanted it: a graph pass that READS a `biome-volume` resource
  calls the gate before recording a single command. That is the difference
  between a tested tracker and a gate.
  Writing that gate is also where this revision caught a real design flaw in
  its own first attempt: the producer pass marked the volume current when it
  dispatched. The suite failed immediately, and rightly - a dispatch proves a
  pass ran, not that the GPU's copy matches the field the CPU holds, and that
  depends on an upload the graph never sees. A gate that certifies itself is the
  one failure mode worth designing against, so the mark now belongs to whoever
  actually baked and the graph only refuses to read past it. The failing test is
  why the code is correct; it is recorded rather than quietly fixed.
  Gates: at EVERY cell centre of the 23x97x23 window the read returns that
  cell's single id at weight 1 (0 misses); the read blends between centres at
  R = 0 and blends at strictly more positions at R = 2 on the same
  quarter-block line; every non-empty read sums to 1 with entries in descending
  weight order; a position outside the volume is empty, not clamped; and
  running the reference pack's own graph over a volume whose field moved since
  the bake throws `BIOME_VOLUME_STALE` by name, through the pass body.
  Gates after step 3: `make test` 1199/0, 5 negative controls, 9/9 (biome 229,
  pack 184); `make shader-check` PASS (the emitted shader did not move, so the
  generated artefact is stable across a core change); `packlint --strict
  --null-device` OK.
  Still owed, named with reasons: no host bake runs in the live frame path; no
  pack pass samples `biome_volume`, so the 28 -> 21 fetch receipt remains a
  statement about the design; and the descriptor bridge still binds storage
  images only, which is enough for the unfiltered read this lane wants and
  insufficient for a pack that asks for a combined sampler.

  r49 builds the thing that was missing and does NOT get to close B5: the
  seam needed a frame where it is actually sampled, so there is now a probe
  pack (`packs/AuraLite-SeamProbe`) that uploads the exact-id field, bakes it
  in compute and paints the result unfiltered, plus two generated siblings
  (-r0, -r2) that differ in the radius and nothing else.
  Building it found three real defects, and none of them was in the seam.
  First: PACK OPTIONS WERE NEVER COMPILED WITH. `activeDefines()` has been
  tested since the options lane landed and no compiler ever consumed its
  output, so every `[options]` value in every pack was inert inside every
  shader - and `seamRadius` could not reach the bake at all. The receipt was a
  probe run at R=0 and a run at R=2 producing byte-identical pixels, with the
  shader's own default of 2 in force both times. Fixed in core (`PackDefines`
  inserts the manifest's defines after `#version` and refuses a shader with no
  version line by name) and in the generated shader, which now reads
  `BIOME_SEAM_R` as a define with the plan's 2 as its fallback.
  Second: `dispatch=` had no way to say "the image I am filling". A bake over
  a 23x97x23 volume was dispatched from the 1920x1080 framebuffer as
  240x135x1 groups - every invocation past the image did nothing, and the
  depth term silently evaluated to 1, so only layer 0 was ever written. Now
  `dispatchBase=write` plus a `depth` binding sizes a compute pass from its
  target (3x13x23 for this volume), and the pack suite pins all three numbers.
  Third: the volume's format was 8 bits wide and the bake is 32. Written up
  in r55 as a layout bug ("the upload leaves the image in SHADER_READ_ONLY,
  so every `imageStore` is discarded"), and THAT DIAGNOSIS WAS WRONG. It was
  measured and refuted on 2026-11-04 with a layout trace: the volume goes
  `UNDEFINED -> STORAGE_WRITE -> GENERAL` before the bake and
  `STORAGE_WRITE -> SHADER_READ -> SHADER_READ_ONLY` before the draw, three
  times a frame, exactly as designed. The real cause is narrower and had been
  in front of every one of those runs: the bake computes `packedIds` (four
  ids in one 32-bit word) and stores it in `.x` of an `rgba8ui` image, and an
  integer narrowing write SATURATES rather than truncates - a probe that
  stored `0xFFFFFF06` read back `ff`, and one that stored `0x12345678` also
  read back `ff`. Slot 0 survived only while the word happened to be one
  byte; slots 1..3 were always 255, which the fragment reads as
  `UNKNOWN_SLOT` and skips, so a correctly baked and correctly uploaded
  volume painted nothing. The format is now `r32g32_uint` (the contract the
  code already stated: four ids in `.x`, four weights in `.y`), in both the
  pack declarations and the generated shader.
  WHAT IS && IS NOT CLAIMED. With the format fixed the B5 pixel gate CLOSES
  on the Vulkan lane, and it is a gate and not an anecdote because the same
  two commands give two hashes, twice in a row, on an unmodified checkout:
  `packs/AuraLite-SeamProbe-r0` -> `sha256=051df21086e91b1d...` (2780640
  nonzero pixels) and `-r2` -> `sha256=0cc14ba8f791f11e...` (2842560) - the
  seam widening moves 61 920 pixels. The host's own readback of the volume
  agrees with what the fragment fetches: the centre cell is `0xFFFF0601` ids
  and `0x000055AA` weights, i.e. swamp id 6 at 85/255 and plains id 1 at
  170/255, which is the widened seam itself and not a flat cell. Still NOT
  claimed: the same gate on the GL lane (it renders, but its volume readback
  refuses outside the RGBA8 family and both radii hash the same, so that
  lane's pixel evidence is unproven) and the live host frame path (no
  Fabric session has been run with the bake in the frame loop).
  One more receipt worth keeping: the probe's first present pass omitted
  `external`, and the graph culled the whole chain (`pass order []`). A pass
  that touches the imported backbuffer has to say so; the compiler caught it.
  THE GL LANE, r57. Closing the same gate on GL found two more defects, and
  neither was in the seam:
  First: the GL backend bound a storage image with `format = 0` ("whatever the
  texture already is"). GL accepts that, and then refuses the image at draw
  time for a 32-bit integer image declared as `rg32ui` - so the shader read a
  binding that was never valid, `imageSize()` returned 0, and the fragment
  painted nothing. Diagnosed by dumping the draw-time binding state (`fmt` was
  0x8D7C = RGBA8UI on an RG32UI image) and then by asking GL to name its own
  error per argument: the same call with the texture's real internal format is
  clean. The backend now passes `GlFormatTable.of(format).internalFormat`.
  Second, and this one was core's: the pack's option defines reached the Vulkan
  provider and NOT the GL one, so `BIOME_SEAM_R` stayed at the shader's
  fallback of 2 on GL and both probe packs rendered the same picture. Measured
  by running the pair on the GL lane: two packs, one hash. The application now
  lives in one place (`PackManifest.withOptionDefines`) that both providers and
  the GPU harness call, and the pack suite pins it (`pack` 196 -> 200).
  With both fixed the two lanes agree, hash for hash:
  `-r0` `051df21086e91b1d...` (2780640 nonzero) in BOTH, `-r2`
  `0cc14ba8f791f11e...` (2842560) in BOTH - and the reference pack's own hashes
  are unchanged (`149d2b37...` vk, `5b4ed44e...` gl), so the fixes moved nothing
  that was already correct.
  THE LIVE WORLD, r58. The host frame path never filled a biome field, so
  every fabric frame baked an empty volume and the seam could not appear
  however correct the bake, the format and the barriers were. `WorldBiomeSource`
  now reads one biome per cell from the loaded chunks around the player,
  `BiomeWindowSource` (core) owns the window arithmetic - origin, cell
  crossings, the 20-frame throttle, and UNKNOWN for unloaded chunks, with
  "unloaded" and "unmapped biome" counted apart - and `RendererLoop` bakes the
  seam from that field and binds it to the frame, so the freshness gate gets a
  record the host produced rather than one the graph assumed.
  Two defects fell out of wiring it, both measured:
  first, `frameIndex - lastBuildFrame` overflows at `Long.MIN_VALUE`, so a
  first-ever update looked throttled forever (the suite caught it with a fresh
  counter); second and bigger, the window starts at NEGATIVE cells for a player
  near the origin - origin cell (-11,-32,-11) at (0,64,0) - and
  `BiomeSeamSampler` read every world position as if the volume began at cell
  0. A border at z = 0 therefore read as a flat swamp and the inside of that
  swamp as nothing at all. The sampler takes the window's origin now and the
  volume passes the field's: the border returns two biomes at 0.5/0.5 and the
  interior exactly one.
  Cost, because it decides the throttle: 51 313 lookups and a 51 ms bake on
  this host, so the window rebuilds on a cell crossing and at most every 20
  frames. NOT claimed: a receipt from a real Minecraft session - the windowed
  gate needs a JDK 21, a GPU and a world, and standing rule 4 says no frame is
  claimed until one has been.
  Gates: `make test` 1290/0, 6 negative controls, 9/9 (biome 292, pack 209);
  `make shader-check` PASS on three packs; `packlint --strict --null-device`
  OK on all three (the probe and its siblings; the reference pack also now
  receives a host-uploaded 23x97x23 field, and its hash is unchanged because
  no pass samples that volume - correctly inert); `gpu-test LANE=vk` RENDERED
  with the same hash as before.

  THE FRAME READS THE SEAM, r59. That "correctly inert" line above was the last
  honest sentence of a pack that did not work: `terrain` declared
  `read=biome_volume`, no shader touched a texel, and every gate stayed green
  because none of them could see the difference. What had to be true first:
  - the descriptor bridge bound STORAGE images only and threw on a combined
    sampler, so a sampled read could not even bind. It binds now, with an
    explicit nearest-clamp sampler - nearest because filtering interpolates the
    packed id bytes, and the average of two ids is a third id;
  - a history resource has two spellings (`taa.cur` in the manifest, `taa_cur`
    in GLSL - a uniform name cannot hold a dot) and one phase, and resolving it
    at BIND time answered with the frame's other side. Resolution happens once,
    at build time, for both spellings;
  - three of the four reference stages were stubs: `terrain.vert` emitted all
    three vertices at the origin (zero area, zero fragments), `taa.frag` and
    `final.frag` drew a gradient of `gl_FragCoord` and ignored their inputs. A
    red terrain stage changed no pixel, which is exactly what a chain that
    carries nothing looks like.
  The gate is the one the plan asked for and it can fail: the same frame,
  twice, differing only in what the host put in the source field, must draw
  differently. Split field `c635d4906ec10a94`, uniform field `4a75f3b149347026`,
  byte-identical on Vulkan and GL. The smoke refuses to say RENDERED if the two
  hashes agree.
  Two more defects surfaced under the same symptom, both on the GL lane and both
  real: sampler uniforms were never assigned a texture unit, so any sampled
  binding other than 0 read whatever sat on unit 0 (the present pass worked by
  coincidence of `set * SET_STRIDE + binding`); and a `DONT_CARE` depth
  attachment kept the previous pass's contents, so a fullscreen triangle at
  `gl_Position.z = 0` lost the depth test and wrote nothing. Samplers are
  pointed at their unit after link, and a depth attachment the graph did not ask
  to preserve is cleared - the semantics Vulkan already had.
  Two corrections to the plan itself: the bake fills slice 0 only
  (`ceil(width/8) x ceil(height/8) x 1` groups), so reading the volume's middle
  slab reads a slab nobody wrote; and `scene_color` is a render target, so a
  stage that wants it declares `sampler2D` against `usage=color|sampled`.
  Gates: `make test` 1290/0, 6 negative controls, 9/9 (biome 292, pack 209);
  `make shader-check` PASS; `make drift-check` PASS; `gpu-test LANE=vk` and
  `LANE=gl` both RENDERED with the same hash, and the volume-driven receipt
  printed on both.

  THE WINDOW MEETS A REAL SHAPE, r60. Step 7 read the live world one BLOCK at a
  time - 51 313 lookups through the chunk cache, each of which is allowed to
  generate terrain on the render thread. The reader is column-shaped now: 529
  calls, one chunk each, from the chunk the client already has, and the field is
  identical cell for cell. Three things fell out:
  - a 97-cell window of 4-block cells is 388 blocks tall and the world is 384,
    so 128 cells of the reference window address rows below y = -64. They are
    counted holes, not repeated edge values (`BiomeField.MIN_BLOCK_Y` names the
    bottom); the first test asserted "no holes", which is what a fixture that
    never looked at the world says;
  - a half-unloaded world stayed half-unloaded only after the contract was
    split: "this sampler has columns" and "this column is not loaded" are two
    different answers. Written as one, every missing chunk was quietly refilled
    per block and the volume had zero holes - the opposite of an honest field;
  - the seam is countable now. `BiomeSeamVolume` reports blended cells and
    distinct ids and quotes them: one biome over a window bakes ZERO blended
    cells at R = 2, a border bakes 256 of 512. A frame drawn from the first one
    looks exactly like a working seam, which is why the number had to exist.
  Also stated, and pinned by the pack suite: what a surface pixel means in the
  reference pack - the weighted sum of the biomes the cell holds, so a blend is
  visibly desaturated. NOT claimed: a session log. B5 stays open until one
  exists (standing rule 4).
  Gates: `make test` 1290/0, 6 negative controls, 9/9 (biome 292, pack 209);
  `gpu-test LANE=vk` and `LANE=gl` RENDERED with the same hash and the
  volume-driven receipt on both.

  THE SEAM REACHES THE DEVICE, r61 - and the first thing that had to be
  admitted is that the mod had never been compiled. `make test` does not touch
  `fabric/` (the Makefile prints `fabric: JDK present ... not part of make`), so
  twelve compile errors had accumulated over four revisions of a lane that no
  gate covers: a receipt naming a variable that did not exist, three `VkDevice`
  counters that belong to the upstream backend, and two Minecraft API calls that
  1.21.1 does not have (`Chunk.getBiome(BlockPos)` is `WorldView`'s;
  `ClientChunkManager.getWorldChunk` does not exist either, `getChunk(x, z,
  BIOMES, false)` is the do-not-load call). All twelve are fixed against the
  yarn 1.21.1 jar rather than against memory, and `gradle compileJava` in
  `fabric/` is now run explicitly - a green `make test` says nothing about
  whether the mod builds.
  What the compile then exposed is the hole standing rule 4 exists for:
  `CompiledGraph.allocate()` creates every owned image and writes none of them,
  and the fabric host bound the volume to the frame without ever uploading it.
  In a world the `biome_field` pass would have read uninitialised device
  memory - random ids, a different picture per run - while the freshness gate
  reported `fresh=true`, because it only ever compared two CPU integers. The
  fixture lane looked fine because the GPU smoke uploads its own field by hand.
  `BiomeVolumeUpload` closes it: one push per bake (paced with the bake, skips
  counted), the whole window in one transfer, `r32_uint` or the packed
  `r32g32_uint` the pack declares, an extent mismatch refused by name
  (`BIOME_VOLUME_EXTENT`) instead of half-filled, and a `NOT_UPLOADED` refusal a
  consumer can raise. The extent itself stopped being two numbers that agreed
  by hand: the graph is built from `WorldBiomeSource.cellExtent()`, the same
  constants the bake uses.
  Evidence, and its exact limits: `make test` 1287/0, 6 negative controls, 9/9
  (biome 292, pack 209) with the new section proving a push reaches a real
  `NullDevice` (1 upload, 401 764 B for 23x97x23, the field version quoted
  back), a skip for an unchanged bake, a second push after a field write, and
  the two refusals by name; `make drift-check` PASS; `packlint --strict` OK on
  four packs; `gradle compileJava` BUILD SUCCESSFUL in 121 s on JDK 21 with
  loom 1.10.5. NOT claimed, and unchanged by any of this: a session log. The
  upload is proven to reach a device, not to reach a window, and nothing here
  says the seam is visible in a world - B5 stays open.

## r64 - the five things the r63 live log proved, and what the rollback cost

The operator's run (`uploads/auralite-trace-5.txt`, 2140 lines, PrismLauncher,
GT 710, `-Dauralite.backend=vk`, 1280x720, world at 73.085/150.859/393.937,
badlands + wooded_badlands) closed nothing. It exposed five defects, and this
round fixes all five in code, with a gate for each.

1. **`viewsTorn=0` on all five rebuilds while `cachedViews` climbed
   5 -> 9 -> 13 -> 17 -> 21.** The teardown removed VIEW HANDLES from a cache
   keyed by view KEY, so `remove` returned null, the identity guard `continue`d,
   and no view was destroyed: a growing set of views onto freed images, which is
   the state `nvoglv64.dll` dies in. `purgeCachesForImage` now walks the cache by
   entry and matches it against the values the ownership purge returns, so each
   view is destroyed exactly once and its key goes with it. A third refusal
   reason - `viewsUnattributed` - counts an image whose ownership named nothing
   while the cache is non-empty, so a broken ATTACH side cannot hide behind a
   zero that reads like "nothing to do". `owns(Object key)` stays on the books in
   `ResourceCacheOwnership` (gated in `RhiSuite`) as the keyed question the next
   sweep will ask.
2. **`0 empty-window retry(ies)` printed next to `window NOT stamped, retry
   due`.** r63's guard on the retry path required prior data
   (`builds > 0 || field.filledCells() > 0`), which is FALSE at spawn - the one
   moment the retry exists for. The first unanswered pass fell through to the
   answered path, counted itself a build, and armed a throttle for a retry nobody
   had asked for. The guard is gone: an unanswered pass completes nothing, and
   `BiomeSuite` pins `builds == 0` with `retriesAfterEmptyWindow >= 1` on exactly
   that first pass.
3. **One bake receipt per session, read out of the FIRST bake** - the bake where
   an empty world is normal. It re-arms whenever the answer changes, so the line
   in the log can only come from a pass that says something.
4. **`529 column(s) refused` against `refused: 0 no-manager, 0 no-chunk`.** A
   refusal that nobody attributed gets its own bucket (`unattributed`) and is
   printed, so the two numbers can no longer disagree by construction.
5. **The old graph was released inside the rebuild call**, frames before the
   frames that had queued work against it. It is parked in `rebuild` and freed
   after `RELEASE_GRACE_FRAMES = 2` frames, BEFORE that frame's `beginFrame`,
   and the deferral is receipted:
   `release timing: N deferral(s), longest M frame(s) (grace 2)`.

Gates, on THIS tree: `make test` 1324/0, 7 negative controls, 9/9 suites green
(`rhi 63 | graph 90 | uniform 91 | biome 290 | nulldev 115 | pack 209 | tables
340 | fabric 57 | view 69`); `make gpu-test` with the VK lane RENDERING (biome
field uploaded, 50 commands, 14 barriers, 6 passes, **0 violations**, pixels
sha256 `c635d4906ec10a94`, and main's fuller probe also confirms the terrain pass
really samples the volume - `volume-driven: c635d4906ec10a94 !=
4a75f3b149347026`, which is the direction that check demands) and the GL lane
refusing 4.6 by name; `make packlint` OK; `make drift-check` PASS. `GpuSmoke` now honours
`-Dauralite.glslang` the way `SpirvCompiler` does, so the VK lane can be pointed
at a compiler that is not on `PATH` - without it this host answers
REFUSED-NAMED and the purge this round changed is never exercised.

**Why these totals are larger than the sandbox's r64 entry.** The entry above was
written in a workspace whose snapshot had just rolled `tests/` back to r20, so it
measured 1142/4 with `biome 155` - a smaller GATE SET, not a smaller revision.
This tree is the same code replayed onto the `tests/` that GitHub's `main`
carries: the r63 and r64 gates were re-applied through the revision scripts
(`r63-tests.py`, `r64-tests.py`) rather than by hand, one duplicated declaration
was dropped (`colSeaLevel / lowBiome / highBiome` already exist in `main`'s
`BiomeSuite` world-window section, so the retry gate no longer re-declares them),
and `main`'s fuller `GpuSmoke`/`PackSuite`/`BiomeSuite` sections stayed. 1324/7
is what r63's 1320/7 grows to once the empty-window retry gate, the ownership
gate, the receipts gate and the r64 assertions sit on top of it - which is the
comparison worth keeping, not the sandbox number.

**Not claimed.** That any of this fixes the crash: the run still ended at
`-1073740791` right after `debug view -> 1 (luma)`, and plan step 11 stays open
until a log shows a filled window and a reload-free teardown. Also open, and now
the louder problem: at `DEBUG_VIEW=0` the pack's own output was BLACK while views
1 and 3 showed content - that is the `taa_cur` resolve chain inside the pack, not
the capture, and step 10's frame-pair comparison cannot be run until it is fixed.

**Found by the jar build, not by any gate.** `remapJar`'s `:compileJava` stopped
on `method refusalReasons() is already defined in class WorldBiomeSource`: r64's
third-reason edit inserted the three-counter method *beside* the two-element one
it superseded, because its anchor ended above the old method instead of covering
it. No host gate can catch that - `make build` compiles `core`/`null`/`gl`/`vk`
and reports `fabric: SKIP - needs JDK 21`, and the suites compile against `core`,
not the mod - so between that edit and this line the r64 fabric module had never
been compiled at all. Removed as `reader: one refusalReasons, three counters` in
`r64-apply.py`. The lesson is the boring one: the gradle lane is a gate like any
other, and `make build` + `make test` being green does not mean the jar builds.

One tool-level change came out of the same episode: the r63 script's
`reader: refusalReasons` insertion now skips on `long[] refusalReasons()` rather
than on its own exact text. An insertion whose anchor survives the insertion is
not idempotent unless it declares a marker, and re-running it on a tree that
already had r64's method re-created the duplicate. The rule is recorded in
`r63-apply.py`'s `sub()` comment: every inserting edit names what its own result
looks like.
Standing rule: host-buildable code stays Java 11 source-compatible so `make test`
is genuinely green here. `third_party/` holds gitignored LWJGL 3.4.3 jars fetched
by `make deps` (~11 MB); they are compile-only and never committed.
