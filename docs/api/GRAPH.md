# The render graph

`dev.auralite.render.graph`. A pack declares what each pass reads and writes;
the compiler derives order, barriers, lifetimes and memory from those
declarations. Compilation is pure CPU work with no device calls, which is what
makes the whole pipeline testable on a machine with no GPU. Authority for the
pack-side spelling: [PACK_FORMAT.md](PACK_FORMAT.md); this doc is the
semantics those spellings compile to.

## The pipeline

```
RenderGraph (builder, rebuilt on pack/resize/option change)
    -- GraphCompiler.compile() -->
CompiledGraph (immutable: ordered passes, barriersByPass, lifetimes, stats)
    -- allocate(device), execute per frame -->
GraphContext (per-pass body: device, cmd, frame, resources)
```

One compiled graph is reused for every frame at a given size. A resize or an
option change recompiles; nothing else invalidates it.

## Declaring

- **Resources** are textures or buffers the graph owns, or imported ones (the
  backbuffer, a host texture). Imported resources are never reallocated and
  never aliased — that is how the Minecraft glue hands the renderer a
  framebuffer it does not own. A zero width/height means "framebuffer-sized"
  and is resolved against the compile-time extents.
- **Passes** are `RASTER`, `COMPUTE` or `COPY`. A raster pass declares colour
  attachments (slots in declaration order) and/or a depth attachment; a
  compute pass declares a dispatch extent; every pass declares reads and
  writes with an access mask from `GfxEnums.Access`.
- **History pairs** (`GraphHistory`) are honest ping-pong: a pass reads
  `previous()` and writes `current()`, and the frame swaps the two bindings
  after execution. Declaring "pass X reads what pass X writes" would be a real
  cycle; the pair keeps the graph acyclic while carrying TAA-style feedback.
  Two frames later the bindings are back where they started — the B-lane gate
  asserts exactly that.
- **Conditions and keeps**: a pass disabled by an option records why
  (`unused_debug -- option 'DEBUG_VIEW' is off`); a pass that must survive
  culling despite having no kept consumer marks `keep(why)`.

## Compilation — six derivations, in order

1. **Validation.** A broken pack is refused here, not at first draw, with a
   named reason: no accesses, no body, raster pass with neither colour nor
   depth, colour slot duplicates, non-colour colour attachment, non-depth
   depth attachment, dispatch beyond the device work-group limits, resources
   beyond texture limits. Reads no enabled pass produces are never silent —
   they are warnings; history sides are exempt by construction.
2. **Edges.** RAW (writer → reader) and WAW (earlier writer → later writer)
   from the declarations, plus writer-of-one-history-side → reader-of-the-other
   so a temporal producer is not culled. **WAR is deliberately not an edge**:
   a read that must precede a later write of the same resource would
   contradict the RAW edge of the same pair and manufacture a cycle out of an
   ordinary "read the imported framebuffer, then overwrite it" pass. That was
   measured, not assumed. Such pairs fall back to the declaration-index
   tie-break.
3. **Cycle detection.** DFS reports the offending path by name
   (`a -> b -> c -> a`).
4. **Culling.** Kept = external or explicitly kept, closed over predecessors.
   Everything else is culled with its reason
   (`producer_dead -- no external output and no kept consumer`).
5. **Order.** Kahn's algorithm over the kept subgraph; ties break by the lower
   declaration index, so two compiles of the same pack are byte-identical.
   A pack may declare `present` first and it still runs last.
6. **Lifetimes, aliasing, barriers.** First/last ordered position per
   resource; alias groups pair non-overlapping lifetimes with compatible
   descs (type, format, samples, mips) — longest-lived anchor the group; the
   barrier list is derived by walking the ordered passes with a state machine.

## Barriers

Each resource carries a `ResourceState` and an access mask. When a pass's
access implies a different state, a `Barrier` is planned before that pass;
a transition whose state and access pair are unchanged is a no-op and is
suppressed (counted, never silent). Imported present-usage resources start in
`PRESENT`/`MEMORY_READ`; anything whose usage includes `IMAGE_PRESENT` gets a
tail transition back to `PRESENT`. The GL backend turns a barrier into at
most a matching `glMemoryBarrier` and often nothing; the Vulkan backend turns
it into a `VkImageMemoryBarrier2`/buffer barrier. Same list either way, which
is what lets the parity gate diff the two lanes.

## Execution

`CompiledGraph.allocate(device)` creates the owned resources;
`execute(cmd, ...)` walks the ordered passes, emits each pass's barrier list,
and runs the pass body with a `GraphContext`. The context provides:

- `beginRasterPass()` — builds the render pass from the current pass's
  declared attachments. The render **area** is the smallest attached image
  (mip level accounted): a 256x256 cloud-shadow pass and a 2048x2048 shadow
  pass live in the same graph as the 1920x1080 lighting pass, and each gets
  the area its attachments imply. The viewport and scissor follow the area —
  a 256x256 pass with a 1920x1080 viewport draws nothing.
- `image(name)` / `buffer(name)` — the allocated handle, or a `GraphException`
  naming the resource and the pass.
- `frame()` — the `FrameContext` (camera, frustum, weather, …) every pass
  shares; `put/get` — a scratch map for per-frame values that are not graph
  resources.

After the last pass, history pairs swap bindings. `release()` clears the
allocations for a recompile.

## The error vocabulary

`GraphException` always names the pass (or the resource) and what is wrong.
Nothing in this package throws a bare `IllegalStateException`.

## Receipts (from `make test`, `graph` suite)

```
order: [cloud_shadow, shadow_map, terrain, ssao, lighting, taa_resolve, present]
culling: producer_dead -- no external output and no kept consumer
barriers: 14 planned, 0 suppressed
lifetime shadow_depth: shadow_depth [1..2] g-1
aliasing: 1 groups, 2097152 B reclaimable
passes: 8 declared, 7 enabled, 7 ordered, 0 culled
  kinds: 6 raster, 1 compute, 0 copy
resources: 8 declared, 1 imported, 0 transient
memory: 79116288 B owned textures, 8294400 B imported, 0 B buffers
barriers: 14 emitted, 0 suppressed as no-ops
edges: 8
  disabled: unused_debug -- option 'DEBUG_VIEW' is off
  warning: resource 'taa.1' is a history side: written last frame, read this frame
null device: images=8 commands=39 barriers=13
```

Aliasing is computed and reported today; allocations are not yet shared
between group members (that is PIPELINE_PLAN P4, and the compiler says so in
its warnings rather than pretending the memory is saved).
