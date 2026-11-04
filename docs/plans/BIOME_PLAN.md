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
2. **Width**: with `R = 2`, a probe that walks a straight line across a
   two-biome border reports the blended band spanning **>= 8 blocks** (today
   it spans exactly 4), and the band is monotonic — no oscillation, weights
   sum to 1 everywhere.
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

## Order of work (when the operator starts it)

1. `biome_field.comp` producer + `PACK_FORMAT` lines for `BIOME_SEAM_R`
   (option) — no behaviour change yet, gate 1 only.
2. Blur radius plumbing + gate 2, refusals for gates 3-4.
3. Fetch-count receipt (gate 5) and the `gpu-test` pixel hash on the GT 710.

Nothing here starts before this document exists. That is the rule the roadmap
set, and it is the reason the last four revisions closed actual defects instead
of adding a second feature.
