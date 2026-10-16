# World programs and Seabreeze shaderpack

Owner wants real world geometry/material shaders (water and more), not only
screen effects, and reports white localized Prismarine lighting. Autonomous
implementation remains authorized; no installed jars/user ZIPs/commits/siblings.
Continue0.5, Java25/MC26.3, standalone Loader; Vulkan primary/GL shared fallback.

Confirmed source defect: Prismarine0.4 consumes RayLighting as radiance while0.5
exports irradiance. Its hardware pass adds unmodulated white irradiance only on
matched local RT surfaces; its Lua lights explicitly orbit/follow the camera.
Keep owner archive immutable; ship corrected independent example, no orbiting
lights, correct world-plane/coverage-aware irradiance usage. Preserve0.5 bindings.

Add optional schema1 `programs` declarations and public PackProgram records for
world pipeline families. Preserve ShaderPack's original constructor. Snapshot
vertex/fragment/includes transactionally. Replace pipelines at actual frontend
render-pass use; clone host formats/blend/depth/defines/vertex bindings/push
constants, append surface bindings without moving existing uniform slots. Cache
per original variant and pack; failure restores host pipeline and reports Failed.
Terrain, block, entity, sky, clouds, weather, particles are source families.

MarineFrame remains288 bytes. New128-byte MarineSurfaceFrame carries readiness,
camera-relative phase origin, sun, sky, water atlas rectangles and effective eye.
Ray bindings remain initialized with current-frame readiness. Move ray submission
to the captured projection before terrain, since native queries do not need scene
raster data. Original public render stages keep their ordering after world draw.

OpaqueScene owns color/depth copies with actual host formats/dimensions. Capture
outside render passes after solid drawing/before OIT. For classic transparency,
defer its draw into a separate pass after opaque snapshot; preserve default path
when world programs are absent. No framebuffer feedback or guessed screen water
mask. Fluid material identification uses current atlas UV rectangles.

Seabreeze replaces actual terrain vertex/fragment programs. Water vertices get
world-stable waves; all OIT phases use the same displacement/alpha. Fragment
water gets absorption, depth-aware refraction from opaque copies and Fresnel sky
reflection. Opaque terrain uses biome/AO vertex color, vanilla block light,
world sun/optional current-frame RT irradiance and fog. Sky/cloud optional programs
demonstrate broader source override; no Iris format compatibility claim, no fake
hardware reflections. RT shadows still have finite range; no infinite scene claim.

Acceptance: public/manifest/clone/lifecycle tests, Prismarine arithmetic regression,
controlled water/solid/glass/slab/emitter fixture, bob/move/night/resize/reload,
Vulkan and classic/OpenGL material shaders/OIT, failures and status, CPU/GPU costs.
Final base/Fullpack Java/Python/Rust release checks and one independent review.
