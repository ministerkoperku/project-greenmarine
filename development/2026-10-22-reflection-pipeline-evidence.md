# GreenMarine 0.9.2 reflection pipeline verification

Completed 2026-10-22 following the owner's continuation request. The owner reports
that RT looks worse than fallback and reflections resemble an overlay near blocks.
They want the player reflected. The earlier scalar footprint correction did not
establish that the gameplay report was solved. No fresh gameplay capture or
running Android world was available for this change.

## Implemented pipeline

- Optional `surfaceBeforeTerrain` replays terrain and dispatches native surface
  rays before the opaque color pass, after host sampler/lighting initialization.
  Early capture clears its owned depth image; it does not inherit entity depth.
  Water-ready capture state is restored before normal world drawing. Existing
  capture timing remains for unrelated programs.
- Native diffuse irradiance and reflection are applied in the terrain material
  shader before entities, fog and display conversion. Native sunlight updates
  diffuse illumination while preserving block light and emission. Each material
  blends its gated environment fallback and native hit once. The scene pass no
  longer applies late native color ratios or another reflection overlay.
- Actual local vanilla player models supply animated wide/slim body cubes and
  enabled outer skin layers. Exact root transform order uses virtual vanilla
  rotation/scale invokers on an owned pose. Geometry comes from actual cube
  polygons, including deformation and mirrored UVs. Host models are not mutated.
- An additive native ABI1 extension accepts initialized 4624-byte posed-box data
  and a borrowed skin view. Per-flight buffers/descriptors retain independent
  snapshots; RayWorld retains the skin through GPU submission completion.
  Nearest opaque skin entry/exit is accepted only before the native terrain hit.
  Missing actor data returns a fresh empty payload and a diagnostic reason.

## Verification

Red then green checks cover the actual host bytecode early-replay hook and external
pack timing. Java model tests use actual vanilla model baking and animation:
wide/slim arms, enabled overlays, deformation, all-face affine/mirrored UVs,
visibility, non-finite/bounded storage, scene-relative world-border precision,
sleeping offsets and initialized unavailable-player behavior.

The actual final-scene GLSL ran in the controlled offscreen GL fixture with 2340
already shaded reflective pixels. The 0.9.1 scene shader modified all 2340;
the current scene shader modified zero when fog/debug were disabled. This proves
removal of that late composition, not final Minecraft appearance. Outputs are
`build/quality/reflection-scene-before/after.{json,png}`.

The production actor GLSL executed in an isolated Vulkan compute fixture on
llvmpipe: non-unit world-distance preservation, parallel miss, inside exit, skin
alpha/UV/linearization, nearest actor, nearer-terrain cutoff and inverse-transpose
normals passed. A normalization mutation failed and the restored shader passed.
The full native ray-query actor/block fixture remains explicitly ignored because
it requires a ray-query device; this machine's portable checks do not replace it.

Verification results:

- `./gradlew compileActorTestShader test build -PandroidNative=true` with the
  documented Bionic clang linker: successful; both target release libraries built.
- Java: 234 tests, zero failures/errors, one optional Sodium fixture skipped.
- Python: all 56 tests passed, including Vulkan/OpenGL GLSL and SPIR-V variants.
- Rust: 12 tests passed, six explicitly gated GPU tests ignored in default suite.
  The portable actor compute test was separately executed and passed (one test).
- `cargo clippy --all-targets -- -D warnings`: passed.
- Actual Bionic Java25: NativeRayTracer smoke passed 29 checks plus library
  lifetime/worker teardown; optional actor detection, legacy symbol fallback,
  invalid payloads and missing-host FFM behavior were exercised.
- Packaged Android library: ABI1 ready, bundled-library FFM/close smoke passed.
- Both packaged native entries exactly match their target release build files.
- Independent review found one renamed-external-pack timing regression; fixed by
  explicit/default-compatible program selection and regression tests. Re-review
  confirmed the Important finding resolved with no remaining blocker.

## Delivery

Guarded exporter checked Android AArch64 ELF/Bionic dependencies, no RPATH/RUNPATH,
metadata and exported bytes. Delivery:

`/storage/emulated/0/Documents/koper/recents/greenmarine-0.9.2-prototype.jar`

JAR SHA256:
`071c2ae06a92e2d42e20b262e281d4895cd47a0aae0d14e9d90ea9a64327551f`

Android native SHA256:
`d9817b8e7bbc5eb7005a7bb00a3e95e0c9c3923abc000036aad7c649d724b55c`

Linux ARM64 native SHA256:
`3152016092afe6bb6c1bcf0e5ed4747e07ef4709533b358951ea80124f778438`

## Runtime limits

Player skin reflections require active hardware surface RT. Raster-only fallback
remains an environment reflection. Armor, capes, held items and arbitrary entities
are not included; local player boxes do not cast native sunlight shadows or enter
diffuse GI. This update preserves existing native output/ray/tap budgets but adds
bounded actor intersection work on reflective pixels. There is no new Android FPS
measurement, visual pose acceptance or proof that every reported near-block
artifact is resolved. In-game mixin application, appearance and performance need
acceptance on the owner's running client.
