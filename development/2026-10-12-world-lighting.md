# World lighting correction

Owner feedback: the first glow is a screen tint, not a spatial effect.
Vulkan remains the primary development and runtime acceptance backend;
Renderpearl portability to OpenGL is allowed, without an OpenGL-only dependency.
Existing broad execution approval remains in force.

Bounded implementation: replace the fixed screen halo with point lighting
reconstructed from scene depth and the camera matrices. Light positions stay
fixed in world coordinates; radius/intensity/color come from the existing API
and selected resource/Fullpack definition. F7 places a lamp near a targeted
surface; F8 toggles effects. The existing bloom is an independent optional
image effect. This prototype does not provide shadows or a material G-buffer.

Java submits the same pass through Minecraft's command encoder on either
backend, while borrowing the Vulkan context for the native path. Rust continues
evaluating animation through the existing ABI. No separate GPU queue is created.

Verification: test projection reconstruction for Vulkan and OpenGL depth
conventions, camera-relative light serialization, radius boundaries and stale
light handles; then exercise a fixed light, camera movement, sky exclusion,
toggle, resize and reload in the Vulkan demo world. Verify submitted source
positions and compare paired images. Compile the optional Fullpack variant.
Keep shader/shadow/material limits explicit and preserve existing user files.

Acceptance completed: 21 Java tests, base and Fullpack builds, three pixel
verifier tests; live standalone Vulkan and OpenGL runs on Wayland/RX 9070 XT.
Both runs toggle, resize, reload and turn the camera. Camera-turn on/off pairs
were inspected for localized illumination remaining on terrain. Source
positions remain fixed. Evidence and limitations are in `PROGRESS.md` and
`evidence/world-lighting/`. Vulkan is the default development launch backend.
