# RHI: the device contract

`dev.auralite.render.rhi`. One interface, three implementations (NULL, GL,
Vulkan). If two backends disagree, NULL — the recording reference — settles it.

## Resources

Created from descriptors, destroyed explicitly, never garbage-collected
quietly: the leak gate counts 40 handles created, 0 live after `close()`.

- **Buffers**: size, usage flags (`VERTEX INDEX UNIFORM STORAGE INDIRECT
  TRANSFER_SRC/DST`), memory domain. Host-visible buffers map; device-local
  buffers upload through staging.
- **Images**: format (42 in `GfxFormat`), size kind, mips, layers, samples,
  usage. Compressed images are single-level by construction: GL clamps the
  level count to 1 and uploads them with the compressed entry points, Vulkan
  refuses `mips>1`/multisampled at creation and staged uploads entirely
  (block math is a named v2 gap, not a silent clamp).
- **Samplers, shader modules, pipelines**: descriptors in, validated handles
  out. SPIR-V is the interchange; GLSL source is accepted on GL and refused
  on Vulkan with the offline-compile message.
- **Descriptors**: sets are written against a pipeline layout and validated
  at bind — kind, resource type, usage flag, array bounds. A write that does
  not match the layout names the set, the binding, and both sides.

Foreign resources are refused by name at every entry point: a GL buffer
handed to the Vulkan device fails with the call and the offender, never
with a native crash.

## Commands

Command buffers are one-shot: `begin()`, record, `end()`, submit once.
Recording outside `begin()/end()` is refused; `end()` with a pass open is
refused without killing the buffer.

- **Raster**: `beginRenderPass` (area checked against every attachment at
  its mip), viewport/scissor (RHI origin is top-left on all backends),
  pipeline + vertex/index bindings, direct/indirect/indexed draws.
- **Compute**: pipeline, `dispatch` ( extents checked against device limits),
  `dispatchIndirect`. Dispatch inside a render pass is refused.
- **Transfers**: buffer↔buffer, buffer→image, image→image, blits (linear on
  depth or integer images is refused), mipmap generation.
- **Barriers**: image (state + access pair), buffer (access pair),
  `memoryBarrierAll`. Push constants and timestamps round out the set.
- **Frame evidence**: `readImage` returns mip 0 as row-major RGBA8 bytes
  (the GPU gate hashes them; the RGBA8 family only - Vulkan also requires
  `IMAGE_TRANSFER_SRC` on the image by name, GL has no usage flags and
  refuses the window handle 0 by name instead), and `frameTimestamps()`
  returns the frame's GPU timestamps in write order for a strictly-
  increasing assertion after `waitIdle`.

Every command validates usage flags exactly like NULL does. Indirect
strides default honestly: 16 bytes for arrays, 20 for indexed.

## Backends

| | GL 4.6 | Vulkan 1.3 |
|---|---|---|
| Execution | immediate; `submit` is a flush | recorded, one-shot submit |
| Descriptors | unit = `set*16+binding` | grow-only pools, allocated per bind |
| Push constants | reserved 128 B UBO | one range, stages from the layout |
| Layouts | none (API has none) | tracked per image; the tracker wins over stale graph state |
| Render passes | FBO cache, hex completeness | cached, keyed with incoming layouts so LOAD survives frame 2 |
| Timestamps | 64-query ring, CPU-readable after finish | 256-query pool, reset per frame, read back with WAIT |
| Shaders | SPIR-V via `GL_ARB_gl_spirv`, else GLSL | SPIR-V only |

Neither backend owns a swapchain: presentation is fabric glue. Handle 0 on
GL is the window's default framebuffer and is never mixed with offscreen
targets. Validation layers (Vulkan) and the debug callback (GL) are wired
but quiet unless the host asks.

## Capabilities and limits

`GfxCapability` (27) is the pack-facing vocabulary: what the pack may
require. `GfxLimits` is the numeric side: work-group counts, viewport and
texture bounds, descriptor and push-constant ceilings. Backends report only
capabilities the RHI can actually exercise — a flag the API cannot back is
a lie, and lies fail the gate.
