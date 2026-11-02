# AuraLite-Render — status

Date: 2026-11-01. Host: OpenJDK 11, Linux x86_64, 2 CPUs.
Every number below is measured by `make test`, not estimated.

## Host gates: 9/9 green

```
rhi         51 passed   0 failed   0 NC   PASS
graph       81 passed   0 failed   1 NC   PASS
uniform     91 passed   0 failed   0 NC   PASS
biome      142 passed   0 failed   1 NC   PASS
nulldev    115 passed   0 failed   0 NC   PASS
pack       165 passed   0 failed   0 NC   PASS
tables     340 passed   0 failed   0 NC   PASS
fabric      30 passed   0 failed   0 NC   PASS
view         69 passed   0 failed   1 NC   PASS
TOTAL 1084 passed, 0 failed, 3 negative controls, 9/9 suites green
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

Standing rule: host-buildable code stays Java 11 source-compatible so `make test`
is genuinely green here. `third_party/` holds gitignored LWJGL 3.4.3 jars fetched
by `make deps` (~11 MB); they are compile-only and never committed.
