# Solstice RT design

Continue 0.6 in place. The owner authorizes autonomous implementation and testing,
including extending the public API while preserving existing packs. This is an
architectural change. The owner's autonomous instruction takes precedence over
routine skill approval, worktree and commit gates; no installed jars or commits.

## Intent and reference

Create an independently authored comprehensive GreenMarine pack inspired by the
Eclipse Unstable look: warm sunlight, cool shadows, readable terrain, deep water,
layered clouds, weather, night and restrained bloom. Reference is the owner's
Prism koperpack Eclipse archive, SHA256
d464dea02ed91ba95ddab2c8670fbe25f38c0718df8b3534838d7d62e526313a.
Reference code/assets are not copied. Similarity requires paired visual evidence;
feature presence alone is not proof of an equivalent image.

## Rendering architecture

Add optional terrain `surface` fragment programs. Replay opaque and transparent
terrain into two owned attachments after the opaque scene copy: world normal and
device depth RGBA32F; linear tinted albedo and material class/roughness RGBA16F.
Copy opaque depth to a private depth target, so entities still occlude terrain.
Replay uses the same host vertices, projection, alpha and wave displacement.
Never append attachments to arbitrary host pipelines or sample active outputs.
OpenGL and Vulkan share Renderpearl passes. Other targets remain unchanged.

Add separately versioned native surface RT entrypoints using the same model
BLAS/TLAS as existing rays. Raster surfaces supply ray origins/normals including
actual displaced water. Trace sunlight, diffuse illumination and offscreen
reflections. Preserve core ABI1, RT ABI1, MarineFrame288 and legacy constructors.
Hardware readiness requires enabled host features, successful pipeline creation
and actual output. Missing hardware uses a clearly reported raster fallback.
Finite loaded scene bounds remain explicit; no infinite tracing claim.

Add environmental uniforms (weather, wetness, camera altitude, celestial and
dimension state) and opt-in pass history with explicit invalidation. Temporal
resolve is not advertised as jittered TAA. Solstice supplies terrain/water,
atmosphere/clouds, night/weather, bloom and configurable tone mapping. No FSR4.

## Verification

Failing compatibility/API tests before implementation; fresh Java/Python/Rust
checks, base and Fullpack compilation, release native and SPIR-V validation.
Sequential actual Vulkan and OpenGL probes: surface capture, offscreen rays,
water motion, bob/hurt, resize/reload, hardware Off and failure recovery.
Measure CPU and GPU separately. Do not build profile changes while a client runs.
