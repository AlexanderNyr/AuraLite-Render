# BIOME_PLAN — B5: widening the seam

Date: 2026-11-04. Status: **plan first, code second.** This document is the
prerequisite the roadmap's Phase 6 asks for (BIG_PLAN: "each gets its own plan
doc before any code"), and it deliberately contains no code.

## The problem, stated exactly

`BiomeSampler.sample()` does trilinear interpolation over the eight cells
surrounding a position (`docs/api/BIOME.md`, "Sampling — and the one-cell
seam"). Cell centres sit at `(c + 0.5) * cellSize` with `cellSize = 4` blocks,
so the blend between two neighbouring ids happens across **one cell — 4
blocks**. Past that the sample is a single id: hard edge, visible as a straight
line on a hillside when a swamp meets a beach.

That width is structural, not a knob. Trilinear interpolation consults the two
cells either side of the position on each axis; a position further than one
cell from a border cannot see that border, whatever weights you assign. An
earlier `blendRadius` control only reweighted the same eight corners and was
removed rather than left lying (`BiomeSampler`'s class comment records this).

So B5 is not "make the radius bigger". It is: **decide where the blend is
computed**, because a per-fragment CPU kernel cannot reach past its own
neighbourhood and buying width by sampling more corners costs fragment-rate
money for a seam that is a *low-frequency* signal.

## What already exists (this is why B5 is cheap)

Measured against the tree at r43, not assumed:

- `BiomeField` — a flat `int[]` sliding window at 4³ blocks per cell,
  carried across camera moves, `UNKNOWN = -1` for cells nothing ingested.
- `BiomeFieldBuilder` — ingest at chunk-section granularity (4×4×4 = 64
  cells), `DirtyRegion` boxes coalesced greedily so a border crossing does not
  become dozens of one-cell uploads.
- `BiomeFieldPacker` — **two GPU layouts already implemented**:
  `ID_R32UI` (exact ids, shader does 8 field + 8 LUT fetches) and
  `TOPK_RGBA8UI` (four ids + weights pre-resolved on the CPU, one fetch,
  ceiling 254 ids, refused by name past that).
- `BiomeLut` — property LUT as a 2D texture, one texel column per biome id,
  five RGBA32F rows, versioned by `LAYOUT_VERSION`.
- The reference pack declares a **compute pass** (`biome_field.comp`,
  `local_size 8×8`) that fills a `biome_volume` storage image — currently a
  constant, i.e. a placeholder waiting for a real producer.

Two facts follow. First, the data is already on the GPU as a volume, at the
same granularity the CPU samples. Second, the compute lane and its plumbing
(graph pass, storage image, barriers) are already exercised end to end. B5 is
therefore a **shader-side widening over data that is already resident**, not a
new subsystem.

## The design (choose blur, not radius)

Widen in two stages, both cheap because both work on the *baked volume* rather
than per fragment:

1. **Blur the volume.** `biome_field.comp` becomes a real producer: read the
   `ID_R32UI` field texture, and for each cell write a `TOPK_RGBA8UI` texel —
   the up-to-four dominant ids in a neighbourhood of `+/- R` cells with their
   weights, using `BiomeSampler`'s merge-and-renormalise rule so the GPU and
   CPU agree on ties. `R` is a pack option (default 2 cells = 8 blocks of
   blend on either side), clamped to a hard maximum the pass declares.
2. **Sample the top-K volume.** The fragment path fetches one texel from the
   blurred volume instead of eight from the field — *fewer* fetches than today
   for a *wider* seam, which is the whole reason to do it in compute.

`ID_R32UI` stays as the source of truth for the frame and for exact-id
consumers (the pack decides per pass which layout it reads). The blurred
volume is derived state: drop it, rebuild it, nothing is lost.

## Refusals, by name

The plan is only worth having if the failure modes are named in advance:

- **`BIOME_SEAM_R_TOO_WIDE`** — `R` past the pass's declared maximum: refused
  by `packlint` and at load, never silently clamped.
- **`BIOME_VOLUME_IDS_OVER_TOPK`** — more than `MAX_TOPK_IDS = 254` distinct
  ids inside one neighbourhood: that cell is written `UNKNOWN_TOPK` and the
  sample falls back to the dominant single id, with a counted warning. No
  truncation of the id set, ever (the packer already refuses rather than
  truncates in the CPU lane; the GPU lane must match).
- **`BIOME_VOLUME_STALE`** — a `DirtyRegion` touched but the pass not run
  before a frame reads the volume. This is the dangerous one: a stale seam
  looks *plausible*, so it must be a loud receipt, not a silent accept. The
  graph already has the dependency edges to express it; the gate proves the
  edge exists.
- **Unknown cells keep their current meaning**: contribute nothing, never
  invented. A blurred cell whose entire neighbourhood is unknown stays
  `UNKNOWN_TOPK`.

## The gate (a phase closes on a receipt, not on code)

B5 is done when these exist as test output, on the NULL backend, no GPU:

1. **Equivalence at `R = 0`**: a compute-blurred cell at `R = 0` reproduces
   `BiomeSampler`'s eight-corner result **exactly** (same ids, weights within
   the renormalisation epsilon). This is the honesty gate: if the shader rule
   and the CPU rule ever diverge, this fails by name.
2. **Width**: the band spans `(2R + 1)` cells. At `R = 0` that is one cell
   (4 blocks, today's seam); at `R = 2` it is five cells (20 blocks). The probe
   walks a straight line across a two-biome border and counts blended
   positions; the widened count must exceed the sampler's, and the band must
   be monotonic — weights descending, summing to 1 everywhere.
3. **Determinism**: two runs of the same field produce byte-identical
   `TOPK_RGBA8UI` output. The coalescing is already deterministic by
   construction; the blur must be too, which means a fixed traversal order and
   no float accumulation across threads.
4. **Staleness is loud**: mark a `DirtyRegion`, read without running the pass,
   and the receipt must name `BIOME_VOLUME_STALE`. A negative control keeps
   this honest — remove the check and the suite must fail.
5. **Cost receipt**: fragment-path fetch count drops from 8 field + 8 LUT to
   1 (+ LUT rows only for the dominant entries), stated as a number in the
   receipt. This is the "why compute" justification, measured rather than
   asserted.

On a GPU the same probes run in `make gpu-test`, with the blurred volume's
pixel hash in the receipt like every other frame gate.

## What this plan refuses

- **A wider CPU kernel.** Sampling 4×4×4 corners per fragment to reach 3 cells
  is 64 fetches for a low-frequency signal; it also breaks the allocation-free
  scratch arrays that make sampling cheap today. Refused.
- **Anisotropic seam widths** (wide horizontally, narrow vertically). Minecraft's
  biome containers are 4×4×4; an anisotropic blur would have to invent a rule
  for which axis is "up" and would double the option surface. Refused until
  someone has a receipt that it matters.
- **Baking the blur into the pack on disk.** The seam must follow the live
  field (chunks stream, modded biomes appear); a baked artefact would be stale
  by definition, which is exactly the failure mode this plan gives a name to.
- **Touching the legacy lane.** Legacy packs have no `biome_volume` and keep
  today's one-cell seam. B5 is a native-pack capability, gated by the pack
  declaring the resource — the same rule every other capability follows.

## Order of work

1. **DONE (r46) — the rule, in core, before any shader.** `BiomeSeam` states
   the widened blend once: a `(2R+1)^3` box of cells, per-axis tent weights
   `max(0, R + 1 - |d|)`, duplicate ids merged, sorted by weight descending
   with ties broken by ascending id (deterministic, so a shader can match it),
   top four kept and renormalised, all-unknown stays empty. `MAX_RADIUS = 4`
   is refused by name past the limit (`BIOME_SEAM_R_TOO_WIDE`), never clamped.
   Gate 1 is green on the NULL backend: at `R = 0` the rule reproduces
   `BiomeSampler` at **every cell centre of a border field** (ids and weights,
   0 mismatches). Measured width: along a 25-position line across a border the
   sampler blends at 4 positions, the widened rule at 16; `seamWidthBlocks`
   reports 4 blocks at `R = 0` and 20 at `R = 2`. Weight invariants (sum to 1,
   descending) are checked at every radius 0..4. `make test` 1100/0, 9/9.
2. **DONE (r47) — the producer, and gates 2–4 green.** `biome_field.comp` is
   no longer a placeholder: it reads `biome_field_ids` (`r32_uint`, binding 1)
   and writes `biome_volume` (`rgba8ui`, binding 0) with the rule above, one
   texel hold four packed ids in `.x` and four packed weights in `.y`.
   **The shader is generated, not hand-written**: `BiomeSeamShader.generate()`
   emits it, `make shader` rewrites it, and `make shader-check` fails by name
   when the committed file and the generator disagree — the same defence as
   gate 1, extended to the artefact. `seamRadius` is now a `[biome]` key
   (default 2, ceiling 4) refused at load as `BIOME_SEAM_R_TOO_WIDE` and never
   clamped. Gates, as receipts:
   **gate 2 (equivalence and width)** — at `R = 0` the emitted volume is
   byte-identical to `BiomeFieldPacker`'s `TOPK_RGBA8UI` output over a border
   field (0 id bytes and 0 weight bytes differ across 256 cells); at `R = 2`
   it blends at 16 positions along the border line where `R = 0` blends at
   none, and the band is 20 blocks. **gate 3 (determinism)** — two bakes are
   byte-identical, and reversing the row order of the input moves no texel;
   the reason is stated and checked rather than trusted (`sumsAreExact`: the
   largest possible total, 15625 at `R = 4`, is below 2^24, so float32 holds
   every partial sum exactly). **gate 4a (`BIOME_VOLUME_IDS_OVER_TOPK`)** —
   the host bake throws by name for a cell merging an id above 254, the
   shader marks that cell as a hole with zero weights instead of truncating a
   biome name, and a negative control proves the guard is load-bearing.
   **gate 4b (`BIOME_VOLUME_STALE`)** — `BiomeSeamFreshness` compares the
   field's version against the version last baked and refuses a read by name,
   counting the refusal; re-baking clears it; a negative control stands in
   for the plan's "remove the check and the suite must go red".
   **gate 5 (cost)** — the fragment path goes from 28 fetches per sample (8
   field + 20 LUT) to 21 (1 volume + 20 LUT) while the seam gets wider, and
   the bake's private table is 125 int/float pairs at `R = 2`, 729 at the
   ceiling. `make test` 1178/0, 5 negative controls, 9/9; `make shader-check`
   PASS (4,376 chars); `glslangValidator -V` compiles the emitted shader to
   SPIR-V (8,720 B) with no diagnostics; `packlint --strict --null-device`
   OK; `gpu-test LANE=vk` renders with the same hash as before
   (`90f1a0c4…`), because nothing samples the volume yet — which is the
   honest state of step 3.
3. **DONE (r48) — the read side, and the gate wired into the graph.**
   `BiomeSeamSampler` is the read side: bilinear between the eight baked cell
   centres, entries merged by id, renormalised over the top four, holes
   contributing nothing and positions outside the volume absent rather than
   clamped. It reads the volume as an **unfiltered** image on purpose — a
   linear sampler would interpolate the packed id *bytes*, and the average of
   ids 12 and 40 is neither, so slot order would be destroyed and ids
   invented. `BiomeSeamVolume` ties the arrays to the field they came from:
   the extent is the field's by construction, the bake records the version it
   saw, and a read after any field write refuses as `BIOME_VOLUME_STALE`.
   The refusal is now wired where it belongs: a graph pass that **reads** a
   `biome-volume` resource calls the gate before recording a command, so a
   stale seam cannot reach the screen. Receipts: at every cell centre the
   read returns that cell's single id at weight 1 (all 23x97x23 of them); the
   read blends between centres, and the wider bake widens that band again on
   the same quarter-block line; every non-empty read sums to 1 with entries
   in descending order; a position outside the volume is empty, not clamped;
   and running the reference pack's own graph on a volume whose field moved
   since the last bake throws `BIOME_VOLUME_STALE` by name.
   **One thing r47 got wrong, corrected here**: r47's STATUS called the
   volume's height "still the framebuffer's". That was false — `[resources]`
   declares `biome-volume`, whose extent is the field's cell extent (23x97x23
   in the probe), and it always was. The real gap was the missing read side,
   and a re-probe is why the doc now says so.
   **And one thing deliberately not done**: the graph does *not* mark the
   volume current when the producer dispatches. A dispatch proves a pass ran,
   not that the GPU's copy matches the field the CPU holds — that depends on
   the host's upload, which happens outside the graph. Marking on dispatch
   would let the gate certify itself, which is exactly the failure this
   refusal exists to prevent. The record belongs to the bake.
4. **Partially done (r49) — the probe pack, and three defects it found.** The
   gap was that no pass sampled the widened volume, so no pixel could move
   when the seam widened. `packs/AuraLite-SeamProbe` closes it in principle: it
   uploads the exact-id field, bakes it in compute, and paints the result
   unfiltered out of `biome_volume`. Two sibling packs are generated from it
   (`-r0`, `-r2`) differing only in `seamRadius`, which is what makes a pair of
   runs a gate rather than an anecdote.
   Building it found three real defects, none of them in the seam:
   - **Pack options were collected and never compiled with.** `activeDefines()`
     had been tested since the options lane landed and no compiler ever
     consumed it, so every `[options]` value was inert inside every shader, and
     `seamRadius` could not reach the bake at all. That is why a probe run at
     `R = 0` and one at `R = 2` produced byte-identical frames. Fixed with
     `PackDefines` (inserts the manifest's defines after `#version`, refuses a
     shader with no version line by name) and the generated shader now takes
     `BIOME_SEAM_R` as a define with the plan's 2 as its fallback.
   - **`dispatch=` had no way to mean "the image I am filling".** A bake
     declared over a 23x97x23 volume was dispatched from the 1920x1080
     framebuffer as 240x135x1 groups, so every invocation past the image did
     nothing and layer 0 was the only one written. `dispatchBase=write` plus a
     `depth` term sizes a compute pass from its target; without it the term
     silently evaluated to 1.
   - **The volume was declared `rgba8_uint` while the bake stored a 32-bit
     packed word in one of its channels.** First reported as a barrier bug
     ("the upload leaves the image read-only, so the stores are discarded"),
     and that report was wrong: a layout trace shows the legal sequence three
     times a frame. Narrowing an integer image write SATURATES, so
     `0xFFFFFF06` and `0x12345678` both landed as `255`; slots 1..3 read as
     `UNKNOWN_SLOT` in every cell, the fragment skipped every contribution,
     and a page of barrier, descriptor and ordering measurements all came
     back clean while the screen stayed black. Fixed in step 5 below, which is
     where the corrected diagnosis and the receipts live.
   **This gate was NOT green when step 4 closed, and it was not claimed.**
   On the probe pack the frame rendered (`2` passes, `0` violations,
   non-black pixels, the volume's size read back correctly through
   `imageSize`), but the volume content was wrong for the reason above.
   Also worth recording: the first version of the probe pack's present pass
   omitted `external`, and the graph culled the entire chain (a pass that
   touches the imported backbuffer must say so). `pass order []` was the
   receipt, which is the graph doing its job.
5. **Step 5 — the volume's format was 8 bits wide and the bake is 32.**
   Planned here first, because the plan owns the rule that code follows the
   document. Found on 2026-11-04 while measuring the pixel gate, and measured
   rather than reasoned: the bake computes `packedIds` (four ids in one 32-bit
   word) and stores it in `.x` of an `rgba8ui` image. Channels that narrow
   **saturate**, they do not truncate — a probe that stored `0xFFFFFF06` read
   back `ff`, and one that stored `0x12345678` also read back `ff`. So slot 0
   survived only while the word happened to be a single byte, and slots 1..3
   were always `255`, which the fragment reads as `UNKNOWN_SLOT` and skips.
   The screen then showed a volume that *was* baked and *was* uploaded and
   still painted nothing: exactly the "probe that renders black" failure the
   last three revisions chased in the wrong place (barriers, descriptors,
   ordering), all of which measured clean.

   The contract the code states was already the right one; only the format
   declaration lagged. `BiomeSeamShader`, `BiomeSeamSampler` and the fragment
   all describe "four ids in `.x`, four weights in `.y`" — a 16-byte cell is
   what that sentence means as integers, and `R32G32_UINT` is that format in
   both backends (`VkFormatTable`, `GlFormatTable` already map it).

   The change, in order:
   - `packs/AuraLite-SeamProbe{,-r0,-r2}`: `biome_volume` becomes
     `r32g32_uint`, and both shaders declare `rg32ui` for it. Nothing else in
     the pack moves — the pair still differs in `seamRadius` and nothing else.
   - `BiomeSeamShader`: emit `rg32ui`, and state in the class comment why an
     8-bit volume cannot carry a packed top-k cell (a byte per *cell* is not a
     byte per *slot*; the saturation is silent, so the refusal has to be in the
     type).
   - receipts: the readback digest of the volume, and the pixel gate itself.

   Acceptance for this step: `packs/AuraLite-SeamProbe-r0` and `-r2` produce
   **different** backbuffer hashes on a real device, twice in a row, and the
   host's own readback of the volume agrees with what the fragment fetches.

5. **DONE (2026-11-04) — the pixel gate is green on the Vulkan lane.** The
   differing hashes between the two radius variants, on an unmodified
   checkout, twice in a row: `-r0` `051df21086e91b1d…` (2 780 640 nonzero
   pixels) against `-r2` `0cc14ba8f791f11e…` (2 842 560), i.e. widening the
   seam moves 61 920 pixels. The host's readback of the volume and the
   fragment's own fetch were measured against each other and agree: the
   centre cell carries swamp id 6 at 85/255 and plains id 1 at 170/255
   (`0xFFFF0601`) — a blend, which is the whole point, and not a flat cell.
6. **DONE (r57) — the same gate on the GL lane, and two defects it exposed.**
   Both lanes now produce the same pair of hashes: `-r0`
   `051df21086e91b1d…` and `-r2` `0cc14ba8f791f11e…`, identical on Vulkan and
   on GL, while the reference pack keeps its own hashes on both
   (`149d2b37…`, `5b4ed44e…`) — so the gate moved and nothing else did.
   - **GL bound storage images with `format = 0`.** Legal to submit, and then
     rejected at draw time for a 32-bit integer image declared `rg32ui`: the
     shader read a binding that was never valid, `imageSize()` returned 0, and
     the frame painted nothing. The backend now names the texture's own
     internal format, which keeps texture and shader declaration in agreement
     by construction instead of by luck.
   - **Pack option defines reached Vulkan and not GL.** `BIOME_SEAM_R` stayed
     at the shader's fallback of 2 on the GL lane, so both probe packs rendered
     the same picture — a widened seam cannot do that, which is how it was
     caught. The application now lives in `PackManifest.withOptionDefines`, one
     place both providers and the harness call.
7. **DONE (r58) — the bake runs on the world the player is standing in.**
   The host frame path had never filled a biome field at all: the graph baked
   an empty volume every frame while the seam was being measured on a fixture.
   Now `WorldBiomeSource` (fabric) reads one biome per cell from the loaded
   chunks around the player, `BiomeWindowSource` (core) owns the window
   arithmetic, and `RendererLoop` bakes the widened seam from that field and
   binds it to the frame so the freshness gate has a record it did not invent.
   Three things this step measured rather than assumed:
   - **A 23&times;97&times;23 window is 51 313 biome lookups and one bake of
     51 ms on this host**, so a per-frame rebuild is not an option: the window
     follows the player on a CELL crossing and no more often than 20 frames.
     Both rules are pinned by the suite (a crossing that changes nothing, and
     a crossing inside the throttle, are separate assertions).
   - **The window starts at NEGATIVE cells** when the player is near the
     origin (origin cell (-11,-32,-11) for a player at (0,64,0)), and
     `BiomeSeamSampler` did not know that: it read every world position as if
     the volume began at cell 0, which shifted the answer by half a window.
     Symptom: a border at z = 0 read as a flat swamp and the inside of the
     swamp read as nothing at all. The sampler now takes the window's origin
     and `BiomeSeamVolume` passes the field's; with both, the border returns
     two biomes at 0.5/0.5 and the interior returns exactly one - the receipt
     the phase wanted from the beginning.
   - **Removing the throttle guard is easy to get wrong**: `frameIndex -
     lastBuildFrame` overflows at `Long.MIN_VALUE`, so a first-ever update
     looks throttled forever. The suite caught it with a fresh counter.
8. **DONE (r59) — the reference pack's terrain samples the volume, and the
   frame proves it.** `pass terrain ... read=biome_volume program=terrain` was
   declared since step 2 while `terrain.frag` never touched a texel, and every
   receipt stayed green: passes in order, 0 violations, a plausible hash. What
   it took, and what each piece measured:
   - **The descriptor bridge threw on sampled reads.** `bindImageSets` wired
     storage images only, so `sampler*D` could not bind at all. It now binds a
     combined sampler with an explicit `nearestClamp` sampler created once per
     device. Nearest is not an optimisation: hardware filtering interpolates
     the packed id BYTES, and the average of two ids is a third id.
   - **A history resource has two spellings and one phase.** The graph keeps
     `taa.0`/`taa.1` and swaps the images per frame; a shader cannot put a dot
     in a uniform name, so it writes `taa_cur`. The resolver knew only the
     dotted form and only the CURRENT phase, and answering with the current
     phase read the frame's other side - the present pass sampled an empty
     buffer while the drawn one sat next to it. Resolution now happens once, at
     build time, for both spellings, and the frame uses that answer.
   - **A pass that draws nothing looks exactly like a pass that draws.** Three
     stages of the reference pack were stubs: `terrain.vert` emitted a
     triangle with all three vertices at the origin (zero area, not one
     fragment), `taa.frag` and `final.frag` wrote a gradient of
     `gl_FragCoord` and ignored their inputs. Injecting a red terrain stage
     changed no pixel, which is what a chain that carries nothing looks like.
     All four stages now read what the manifest says they read.
   - **The GL lane had two real defects behind the same symptom.** Sampler
     uniforms were never given a texture unit (the backend binds sampled
     images at `set * SET_STRIDE + binding`, and a sampler uniform no
     `glUniform1i` touches keeps unit 0 - the present pass only worked by
     coincidence of that arithmetic), and a `DONT_CARE` depth attachment kept
     whatever the previous pass left, so the fullscreen triangle's
     `gl_Position.z = 0` lost the depth test and wrote nothing. Both fixed:
     samplers are pointed at their unit after link, and a depth attachment the
     graph did not ask to preserve is cleared - the same semantics Vulkan
     already had.
   Acceptance, met on both lanes: **the same frame draws differently for two
   different volumes and identically for the same one.** `c635d4906ec10a94`
   with a swamp/plains split against `4a75f3b149347026` with one biome
   everywhere, byte-identical on Vulkan and GL, and the smoke refuses to report
   RENDERED if the two hashes agree.
   Two things this step measured that the plan had wrong: the bake fills slice
   0 only (`ceil(width/8) x ceil(height/8) x 1` groups), so reading the
   volume's middle slab reads a slab nobody wrote - it paints black and reads
   as "the fetch is broken"; and `scene_color` is a render target, so a stage
   that wants it must SAMPLE it (that is `usage=color|sampled`), not declare it
   a storage image.
9. Still owed. A receipt from a real Minecraft session (the fabric lane's
   windowed gate needs a JDK 21, a GPU and a world; standing rule 4 is that no
   frame is claimed until one has been), and the backend's upload policy (an
   upload deciding a storage image's final layout is defensible, but it should
   be a written decision rather than a side effect).

10. **The last step before the live receipt: the world window must survive a
    real world, and the seam must be visible where the player stands.** Step 7
    made the host fill the field and step 8 made the frame read it, but both
    were measured on fixtures. Four things stand between that and a receipt from
    a real session, and each one is a claim the current code cannot yet make:
    - **The seam's RADIUS has never run on live terrain.** `seamVolume.radius()`
      passes the manifest's `seamRadius` (2), but every measured receipt so far
      is either the probe's synthetic split or R = 0 arithmetic. On a real world
      a radius of 2 blends two biome borders into one another; the live gate has
      to show that the blend count it reports matches what `BiomeSeam` says the
      band should hold, or the live number is decoration.
    - **`LiveSampler.idAt` calls `world.getChunk` from the render thread.** That
      is the same class of bug as a texture read from the wrong queue: usually
      fine, occasionally a stall or a chunk generated underneath the frame. The
      window is 51 313 lookups; doing them through the chunk cache on the render
      thread is what makes the 51 ms bake a 51 ms freeze. The sampler must read
      from a snapshot taken where the world is safe to touch, and the field's
      UNKNOWN must cover a chunk that is not loaded in that snapshot.
    - **No receipt says the seam is in the FRAME.** The window receipt says what
      was baked and the volume receipt says what was packed; neither says a
      pixel of the picture came from the seam. The live gate needs the same
      shape as the GPU lane's: a frame with the seam bound and a frame with the
      volume unbound (or bound empty) must not hash the same, and the count of
      pixels that moved must be reported.
    - **The seam's colour is a debug colour today.** `terrain.frag` colours by
      biome id from a golden-ratio walk - fine for a gate, wrong for a world.
      Before a live frame is claimed, the reference pack needs to say in one
      line what a surface pixel's colour is derived from, so that "the seam is
      visible" means "the biome blend is visible", not "an id palette is
      visible".
    Acceptance, none of it claimed yet: a session log with the window receipt,
    a bake time under the frame budget, the blend-count comparison, and two
    frame hashes that differ only in whether the seam was bound. Standing rule 4
    applies in full - until that log exists, B5 stays open and this document
    says so, because a fixture that proves the mechanics is not a world.

    **Landed so far (r60), all of it measured on fixtures - none of it a
    session.** The column reader is the first piece and it found the thing the
    plan predicted plus one it did not:
    - `BlockSampler` grew `hasColumns()`/`column(chunkX, chunkZ, cellSize,
      cellY)` and `WorldBiomeSource` answers one chunk column per call, read
      from the chunk the client ALREADY has (`ClientChunkManager.getWorldChunk(x,
      z, false)`, called reflectively so a Minecraft update that removes it
      degrades to "no column" instead of taking the frame down). For the
      reference window that is **529 column calls instead of 51 313 block
      lookups**, field byte-identical cell for cell.
    - **A 97-cell window of 4-block cells is 388 blocks tall and the world is
      384.** Centred on a player at y = 64 the window reaches below y = -64, so
      128 of its cells address rows that do not exist. They are counted holes
      (`UNLOADED`), not repeated edge values - `BiomeField.MIN_BLOCK_Y` names
      the bottom so the arithmetic and the world agree on one frame. The first
      version of the test asserted "no holes", which is what a fixture that
      never looked at the world would say.
    - **A half-unloaded world must stay half-unloaded.** With the column path
      first written as "use a column when there is one, otherwise fall back per
      block", a world answering half its columns produced a volume with ZERO
      holes: every missing chunk was quietly refilled cell by cell. Splitting
      "this sampler has columns" from "this column is not loaded" fixed it, and
      the suite pins both halves - equal to the per-block path where the world
      has data, and counted holes where it does not.
    - **"The seam is in the frame" is now countable.** `BiomeSeamVolume`
      reports blended cells and the distinct ids it holds, and its receipt
      quotes them: one biome over the window bakes ZERO blended cells at R = 2
      (the picture is a flat colour and looks exactly like a working seam), a
      border bakes 256 of 512. The reference pack's `terrain.frag` states what a
      surface pixel means - the weighted sum of the biomes the cell holds, so a
      blended cell is visibly desaturated - and the pack suite pins that
      sentence, so "the seam is visible" cannot quietly come to mean "an id
      palette is visible".
    **r61: the half nobody had written.** `CompiledGraph.allocate()` creates the
    images a pack declares and writes none of them; the host bound the baked
    volume to the frame and never uploaded it, so a world would have run the
    `biome_field` pass over uninitialised device memory while the freshness gate
    - two CPU integers - said `fresh=true`. `BiomeVolumeUpload` is the missing
    half: one push per bake, the whole window, the pack's own layout, an extent
    that is the bake's extent and not a second literal, and two named refusals
    (`BIOME_VOLUME_EXTENT`, `BIOME_VOLUME_NOT_UPLOADED`). Two things this step
    already measured: the fabric lane had NEVER been compiled (twelve errors
    across four revisions, `make test` does not build it), and the fix for that
    is a gate, not a habit - `gradle compileJava` in `fabric/` is now part of
    closing a step. Still owed before B5 closes: the session log itself, a bake
    measured under the frame budget on real terrain, and the bound-vs-unbound
    frame pair.
11. **r63 — the three defects the r62 run showed**, the first session that
    reached the world rather than dying before it.
    - **An empty window stamped itself as an answer.** r62's log says
      `rebuilt from 0 of 529 chunk columns` and `0 filled / 51313 unknown`:
      the first bake ran at spawn, before the client's chunk pool held a
      column at the new position, so every column was refused — and because
      the pass still stamped the window's cell, `needsRebuild` stayed false
      and the field was never tried again for the rest of the session. An
      unanswered pass now leaves the stamp off, retries on the throttle, and
      keeps whatever the field already had; the reader counts its two refusal
      reasons (`no-manager` — the accessor moved — and `no-chunk` — the world
      simply has not streamed in yet) so a dead field names itself in the log.
      Measured on the fixture: 529 refusals, then the same unmoved player
      position fills 42 849 cells once the world answers.
    - **`0xC0000409` came back**, and `auralite-trace-4.txt` says exactly
      when: death on the first traced frame after the fourth reload, after
      three reloads that each cost ~3.0 s on their first frame. The r42 fix
      (cached attachment views and framebuffers surviving `VkImage.close()`)
      was missing from the tree - the prose kept it, the code did not. The
      rule is restored as `ResourceCacheOwnership` in core, where the host
      suite can gate it (a negative control reddens the moment the stamp comes
      back), and the receipt is on the rebuild line again: `imagesLive=…
      released=… viewsTorn=… fbosTorn=…`, asked of the Vulkan device instead
      of dropped because the GL backend has nothing to answer. A second,
      timed `waitIdle` now sits between the new graph's uploads and the old
      graph's release, because the drain at the top of `rebuild()` runs before
      the pack load submits its own one-shots.
    - **A receipt that invented a number.** The host printed
      `ids().length * 4` as "ids packed (401 764 B)" beside an upload of
      205 252 bytes: 23x97x23 cells is 51 313 cells, and the `r32_uint`
      layout moves one int per cell - 401 764 is the `r32g32_uint` figure.
      Bytes now come from the upload's own `UploadState`, with the layout
      named next to them, and `packedBytes()` states the size for whichever
      layout the image actually has.
    - **The validation path existed only to crash.** The r62 messenger
      passed a null callback through LWJGL's generated setter, which
      dereferences it: `AURALITE_VK_VALIDATION=1` died inside
      `createMessenger` before any Vulkan call, and `make gpu-test` (which
      defaults the flag ON) had been skipped for two revisions. The layer
      stays - it prints to stderr and turns violations into failing result
      codes - and the messenger is gone by decision, recorded at the code.
    - Added so the next run can pay the acceptance in full: `present/frame:
      sha256=… bytes=WxH center=…` every 600 frames while present is on, a
      hash of the whole pack output rather than one centre pixel once per
      session. Acceptance for this step, none of it claimed: a session log
      where the window receipt is filled (not `0 filled`), the retry count is
      visible, every F9 rebuild line shows `viewsTorn=` > 0 with a bounded
      `drained before release (…)`, at least two `present/frame` hashes exist
      with the first taken before the window fills, and the run ends with exit
      0 instead of a driver fault.

Nothing here starts before this document exists. That is the rule the roadmap
set, and it is the reason the last four revisions closed actual defects instead
of adding a second feature.
