# Solstice quality/performance investigation (0.8.9)

Owner reports Medium FPS below High and requests optimization and edge cases.
Scope confirmed by owner: Solstice only (called Soltrace). Runtime FPS/ray-mode
numbers were requested; no fresh local gameplay capture/profile is available.
The inversion itself is not reproduced and no FPS gain should be claimed.

Confirmed costs: Medium and High used the same half-resolution native surface
ray dispatch and rough reconstruction footprint. Ordinary nonreflective land
also fetched reflection output. Dry/dark/vertical surfaces evaluated puddle
noise even though a later factor was zero. Fully filtered cloud octaves still
evaluated noise even though their contribution was replaced with a constant.

Bounded implementation: Solstice Low/Medium native output uses quarter dimensions
(1080p: 480x270, 129600 pixels), High/Ultra retain half (960x540, 518400).
Low/Medium reconstruction keeps the four-tap footprint; High/Ultra retain up to
16 taps for rough reflectors. External packs keep the previous dispatch size.
Skip reflection output reads for classes 1/7, skip zero puddle/cloud work.
Preserve full material replay because temporal history consumes it even without
hardware rays. Do not disable clouds, shadows, transparency or history to imply
an optimization. Preserve producing-capture/material/normal/plane checks.

Failing tests observed before implementation: missing SurfaceRayResolution,
missing quality/reflection policies, counted production GLSL cloud/puddle
functions performed noise work when their contribution was zero.

These counts establish a reduced work budget; CPU/GPU timing and game FPS must
be measured separately and are not inferred from pixel counts.

## Controlled sky comparison

Actual production GLSL was rendered at 960x540 through Mesa llvmpipe before/after
using the 0.8.8 common source from its JAR as the baseline. Medium output had
zero changed channels/pixels. High changed one red channel by 1/255 out of
518400 pixels (RGBA MAE 0.000001929/0/0/0), consistent with float rounding.

Three alternating before/after runs per quality, five warmup draws, 200 timed
draws per run. Raw results: build/quality/sky-089-timings.json. GPU timer results
are on a software renderer; calling-thread CPU is measured separately and does
not include llvmpipe worker CPU. Other validation ran concurrently, so these
are noisy observations and must not become a game FPS or Android GPU claim.

| Quality/version | GPU timer median, range ms/draw | Calling-thread CPU median ms/draw |
|---|---|---|
| Medium 0.8.8 | 1.1015 (1.0994..1.1188) | 0.0080 |
| Medium 0.8.9 | 1.0401 (0.6641..1.3446) | 0.0078 |
| High 0.8.8 | 1.1257 (0.8550..1.4643) | 0.0078 |
| High 0.8.9 | 0.5134 (0.4624..0.5400) | 0.0082 |

The initial full GLSL validation caught the reserved variable name `filter` in
the new branch. Renamed to `detailWeight` before the final build/validation.
No failed build was exported. Full final results follow after validation ends.

## Final validation and Android delivery

- Final Android dual-native Gradle test/build passed: 195 Java tests, 0
  failures/errors, 1 optional Sodium resource skip.
- Final Python suite: 56 tests passed, including all actual Vulkan/OpenGL shader
  variants and SPIR-V validation; counted-work and quarter/odd mapping regressions.
- Rust: 10 passed, 4 explicitly GPU-only tests ignored. Native code unchanged.
- Actual Android/Bionic Java 25 packaged-native FFM/close smoke passed, without
  an attached host GPU. Packaged Android and Linux bytes match release artifacts.
- Independent read-only review found no Important correctness/API issue.
- git diff --check passed. Android export uses the ELF/Bionic guarded exporter
  and byte comparison against build/libs/greenmarine-0.8.9-prototype.jar.

JAR SHA256: 1258fcc574fab4538d8f7b420432b7a8b7d06d885bc617efa77119d9f2376c46
Android native SHA256: 498919581aad70796961b929ffe19be4f3df0bdc6b18e20ac405aae084b2d238

No game FPS increase, whole-game Medium/High ordering, or native quarter-rate
visual acceptance is claimed. Quarter-rate reflections can lose fine detail and
need Android gameplay comparison. No installed game artifact was replaced.
