# Distant Horizons compatibility fixes — 2026-10-26

Scope: investigate the owner's PC report in
`2026-10-26-owner-pc-rendering-bugs.md`. The owner subsequently authorized renderer
fixes. Existing editor/SDK changes in this linked worktree were preserved.

## Confirmed source defects and changes

- LOD relighting checked whether DH was installed, but never checked its active
  `graphics().renderingEnabled().getValue()`. It could therefore consume a retained
  depth texture after the owner disabled DH. Relighting now stops when DH rendering
  is disabled, and temporary fog/shading overrides are released. Pack reset also
  releases these overrides.
- Depth reconstruction inferred the depth range from the depth direction. DH
  exposes these independently, and other render integrations may change them.
  Reconstruction now reads `getDepthRange()` and `getDepthDirection()` separately,
  supports all four combinations, and rejects invalid clip planes and unknown
  conventions before uploading coefficients.
- Diagnostics included camera-dependent clip coefficients in a state string
  reported whenever it changed. Removed these changing values from that state
  message to avoid per-frame logging during movement.
- Review caught partial configuration writes: a failed shading override could
  leave a successfully written fog override installed. Override state now tracks
  incomplete writes, and cleanup attempts both releases even if the first fails.

DH API references:

- [Graphics configuration](https://distant-horizons-team.gitlab.io/distant-horizons/com/seibel/distanthorizons/api/interfaces/config/client/IDhApiGraphicsConfig.html)
- [Render proxy](https://distant-horizons-team.gitlab.io/distant-horizons/com/seibel/distanthorizons/api/interfaces/render/IDhApiRenderProxy.html)
- [Depth range enum](https://distant-horizons-team.gitlab.io/distant-horizons/com/seibel/distanthorizons/api/enums/config/EDhApiDepthRange.html)

## Verification

- Regression tests failed with the prior depth math and installation-only render
  admission. Partial override cleanup also failed before its repair.
- Final `./gradlew test build -PandroidNative=true
  -PandroidLinker=/data/data/com.termux/files/usr/bin/aarch64-linux-android-clang
  -Pmod_version=0.9.11-prototype-dhfix1 --console=plain` passed with
  `CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS='-C link-arg=-fno-termux-rpath'`.
- Java: 289 tests, zero failures/errors, seven skipped GPU/environment tests.
- `cargo test`: 17 passed, eight explicit GPU/hardware tests ignored.
- `git diff --check` passed. Independent focused review found no remaining issues
  after the partial-write cleanup correction.
- The first Android export was rejected for a Termux RPATH. Rebuilding with the
  documented linker flag removes that nonportable path. Export uses
  `tools/export-android-mod.py`, which validates Bionic dependencies and verifies
  the destination bytes against the built JAR.

## Remaining runtime acceptance

No owner's PC modpack, GPU/driver details, latest.log, exact DH version or confirmed
shaderpack identity were supplied. These source repairs do not establish that
the reported flickering shadows, white block tops, lingering LODs, or low FPS are
fully resolved. No claim is made about unnamed mod incompatibilities. Existing
native platform support is unchanged; this build bundles Android/Bionic ARM64
and host Linux ARM64, not a Windows/x86 native engine.

On the original PC configuration, repeat upward flight, disable DH, reload the
shaderpack, re-enable DH, and switch worlds/packs. Capture latest.log and frame
times with the exact pack/version and renderer stack. DH depth relighting must
stop immediately after disabling DH; no camera-fixed relit image should remain.
Check shadows and white surfaces separately rather than treating the admission
fix as proof of all reported symptoms.
