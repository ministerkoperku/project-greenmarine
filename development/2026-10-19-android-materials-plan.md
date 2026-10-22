# Android delivery, local shadows and material differentiation

Owner reports: 0.8.3 has no Android native library, synthetic sky reflects in
caves, fallback has no shadows, and materials appear uniformly reflective.
Log: https://mclo.gs/Pswt31v. Both latest Android screenshots show the missing
`native/android-aarch64/libgreenmarine_engine.so` error. Packaging cause and
unconditional environment-reflection code are confirmed; no Adreno gameplay
acceptance is inferred from build/native smoke checks.

Authorized execution continues inline under SESSION-HANDOFF.md. Preserve owner
settings, installed jars and sibling projects; deliver only to Android Documents.

1. [x] Repair Android distribution and add export rejection for host-only JARs.
2. [x] Gate synthetic water sky by local skylight; keep geometry-ray hit colors.
3. [x] Add explicit opt-in voxel-shadow world-program policy, preserving old
   constructors and legacy world-only raster fallback. Solstice opts in. Trace one
   bounded 32-cell-volume sun ray per lit fragment only in voxel mode. Expose finite
   coverage/readiness honestly; this is not distant cascaded raster shadows.
4. [x] Add bounded built-in material profiles for glass, stained glass, ice and
   metals, with a world-only 1040-byte atlas-rectangle/property uniform. Normal
   terrain stays rough/nonmetallic. Use the reserved final triangle float for
   sunlight transmission, retaining 96-byte scene/native ABI layouts. Additive
   triangle overloads preserve old zero-transmission semantics. Glass transmits
   sunlight; tinted glass stays blocking. No colored caustics/refraction promise.
5. [x] Avoid tracing reflection rays for opaque material outputs never consumed.
   Lower default reflection strength while preserving owner overrides.
6. [x] Test old/new policies, material differences, packed transmission and
   actual shader variants, rebuild both native targets, rerun Bionic loading/FFM
   smokes and export only through the Android validator. Record limits.
