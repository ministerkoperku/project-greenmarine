# GreenMarine 0.8.8 prototype — Android connector depth correction

Enabled effects now request Minecraft's consistent world depth before the frame
graph is built. Always-on-top gizmos use the host's separate gizmo depth target
and integration instead of clearing the world depth read by GreenMarine.
Existing host requirements and disabled behavior are preserved. No changes or
dependencies were added to Mechanics Dream/KoperLib; native/shader code unchanged.

Root-cause evidence: connector source submits always-on-top gizmos; Minecraft
26.3 executeAlwaysOnTop clears the selected depth to zero and uses world depth
when consistentDepthRequired is false. GameRenderer only derives that flag from
vanilla post effects. GreenMarine's render3dHud HEAD effect consumes it later.
This establishes a source-level depth-input defect, not live visual acceptance
of the entire reported after-removal/camera-motion sequence.

Validation:
- Android dual-native Gradle test/build passed: 192 Java tests, 0 failures/errors,
  1 skipped optional Sodium resource fixture.
- Regression checks enabled/disabled/re-enabled behavior and preserves another
  effect's request; ASM verifies the exact target call and argument in 26.3.
- Regression first failed with NoSuchMethodException before implementation.
- Rust: 10 passed, 4 explicit GPU-only tests ignored; no native changes.
- Actual Android/Bionic Java 25 loaded the packaged native and passed FFM/close
  smoke checks. No attached host GPU was used.
- Packaged Android and Linux native bytes match their explicit-target release
  artifacts. Independent review found no Important correctness issues.
- git diff --check passed.

Minecraft/Android gameplay reproduction and the full disappearance sequence
remain unverified. Shared-storage logs available locally predate this report.

JAR SHA256: 6119bf7a1d4f429afc05c6df6eefef3a1d6a35cd62aede387c2b5ca95a3f1d96
Android native SHA256: 498919581aad70796961b929ffe19be4f3df0bdc6b18e20ac405aae084b2d238
