# Camera-motion lighting and complete accessory texture plan

Owner reports the same RT movement artifact after0.9.3, now describing lighting
glitches on reflective blocks when rotating the camera. They also request missing
KAvatar accessories. Prior filter/material fixes did not establish this bug fixed.

## Investigation and selected correction

Actual Minecraft26.3 bytecode and native source show capture→native queue order,
fence ownership, uniform staging snapshots, world projection/view matrices and
replay placement are coherent. No synchronization/matrix patch is justified.
Slot exhaustion could cause raster fallback, but no runtime counter evidence
establishes that as the owner's cause.

Both native GI paths rotate their entire four-ray hemisphere by a hash of floored
quarter-block world cells. An infinitesimal producer shift across a cell changes
all secondary directions. Actual source executed as C++ produced direction delta
0.624856 and a constant angular-field energy step0.0610969; actual GLSL GL probes
produced blue27..39 where the field should be spatially constant. Select a fixed,
normal-oriented four-direction cosine quadrature without positional hash. Keep
four rays, reach and GI strength; do not disable GI or add temporal smearing.

Accessory omission is a separate16-box/single-texture constraint. Add optional
extended64-box actor ABI while preserving all old16-box calls/payloads. Internal
storage initializes/pads the extra slots for legacy calls. Java supports a second
snapshot overload; old entrypoints remain source/binary compatible.

Pack custom body/accessory textures into an owned2D atlas and remap affine UV rows.
Borrowed DynamicTexture/skin images lack COPY_SRC, so use shared Renderpearl sampled
blits with2px gutters and no blending, not invalid image copies. Prepare before
leasing native flight resources; retire pending references before atlas resize
or close. Keep bounded64boxes/2048atlas limits with explicit diagnostics.

## Work and verification

- [x] Audit host/native boundaries without speculative production edits.
- [x] Observe CPU and actualGLSL GI regressions red; implement fixed quadrature,
  verify green and compile/validate both production SPIR-V shaders.
- [x] Add extended native64 ABI and validate legacy zero padding, last-slot hit,
  count65/malformed rejection and optional Java API compatibility.
- [x] Add complete KAvatar parts/texture atlas, bounded packing and UV tests,
  sampled-blit shaders, integration ordering and resource lifecycle checks.
  RayWorld selects the extended call when the native module exports it, pads
  legacy/vanilla payloads to 64 boxes, and closes the atlas on reset after drain.
- [x] Java248/Python59/Rust14 checks, Android dual-native build (Termux clang with
  `-fno-termux-rpath`), guarded export; JAR SHA256 9b4e729b...e868ad matches.
- [ ] Independent review and owner gameplay acceptance on device.

Controlled constant-field tests establish the artificial sampling step, not final
Android gameplay appearance. Do not announce the entire movement bug solved until
the owner's running client supplies acceptance.
