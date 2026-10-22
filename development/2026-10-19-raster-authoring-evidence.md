# Raster authoring verification, 0.8.3-prototype

Android delivery correction: this milestone's host-only JAR was later exported
to Android without its Bionic native library. The owner reproduced unavailable
RT. See `2026-10-19-android-materials-evidence.md` for the 0.8.4 packaging fix,
actual Android JVM checks and guarded export procedure.

Scope: native GreenMarine pack authoring, external sources and raster defaults.
The original broader rendering roadmap remains incomplete; no general pack-format
compatibility or feature-parity claim is made.

Tests were introduced before implementation. PackSource/ExternalPacks first failed
because their APIs did not exist. Default-setting/adapter tests failed assertions
before the raster changes. Authoring tests failed before the CLI and shader existed.
The review regression first demonstrated a missing `passes` field was accepted,
then the CLI required the runtime manifest field and rejected wrong JSON shapes.

`./gradlew test build --console=plain` succeeds for 0.8.3: 181 Java tests,
zero failures/errors, one skipped optional Sodium-resource compiler test. The real
CLI directory/ZIP consumer test ran with cached Minecraft includes and passed.
The existing Sodium ABI routing test includes the new builtin raster program;
this is not rendered Sodium device acceptance.

`cargo test --locked` passes 10 tests, with four explicit desktop GPU acceptance
tests ignored. Rust sources and native ABI are unchanged. Full Gradle build reuses
the native release task where inputs are unchanged.

`python3 -m unittest discover -s tools -p 'test_*.py'` passes all 49 tests in
94.222 seconds. This includes starter/validator/reproducible-ZIP tests and actual
GLSL/SPIR-V compilation of the raster fragment's Vulkan variants. `git diff --check`
passes. No Minecraft gameplay session or whole-frame performance benchmark was run.

Artifact inspection verifies the JAR version, PackSource/ExternalPacks classes,
absence of probe classes and Loader/Minecraft/Java-only base requirements.
The raster ZIP contains 14 entries and the distinct `greenmarine:raster_example`
manifest ID, so it can coexist with `greenmarine:raster`. This build uses the host
native target; no new Android or live gameplay acceptance was performed.

Optimization evidence is a deterministic resource-read test: two passes sharing
one fragment resolve that source once rather than twice per pack reload. The
include matcher is compiled once. No CPU/GPU frame-time or FPS gain is claimed.
The raster fragment has no ray bindings or ray-output sampling, and the pack
requests no ray scene. New settings select Rays Off; existing saved settings stay.

Independent read-only review found no Critical/Important issue, and two Minor
findings. Missing manifest-field validation was fixed with a RED/GREEN regression.
Ruling on archive symlinks: filesystem links are rejected; ZIP entries are read as
bounded bytes and are never extracted or followed, including entries carrying Unix
link metadata. The design now states the filesystem-link guarantee precisely.

Owner's Connector held-item report is preserved separately in
`2026-10-19-connector-bugreport.md`; it has not been reproduced or attributed.
No installed jars, owner settings, sibling sources, branches or commits changed.
