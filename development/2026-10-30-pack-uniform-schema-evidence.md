# Pack uniform schema and CPU preparation

Added `resources.uniforms` as a map from resource names to ordered member arrays,
and pass-level `uniforms` mappings from GLSL block names to those resource names.
This allows programs sharing the GLSL name `IrisUniforms` to select different
declaration layouts. The public `PackUniformBlock` owns immutable member/source
descriptions and a validated `UniformLayout`; CPU preparation requires explicit
host values for every source and rejects missing, null, malformed or unrepresentable
values without changing the destination. No host values are fabricated.

Limits are 64 uniform resources per pack, 64 bindings per pass and 65,536 packed
bytes per resource. Existing resource/pass namespaces and reserved engine names
remain protected. Lightmaps reject custom world uniform mappings. Old resource and
pass constructors default to empty uniform declarations; MarineFrame remains 288
bytes. Public usage and integration boundaries are in `docs/PACK-UNIFORMS.md`.

The initial root Gradle RED attempt encountered the concurrently authored
`IrisFrameInputsTest` before its production class existed. An isolated `javac` and
JUnit launcher then compiled only the schema tests against the existing production
classes: four acceptance tests failed for `Unknown resource kind: uniforms`, as
expected. After implementing the model and parser, isolated tests passed. Once
the host-provider implementation was available, the normal Gradle pack suite ran:

```text
./gradlew test --tests 'com.koper.greenmarine.api.pack.*'
BUILD SUCCESSFUL
18 test classes, 76 tests, zero failures, zero errors
```

The two new test classes contribute 12 tests covering parsed member order and
per-pass resource selection, compute support, exact block/binding/count boundaries,
missing resources, lightmaps, namespace collisions, malformed declarations,
source-to-member packing, immutable snapshots and destination preservation on
failed writes. Existing pack tests cover constructor compatibility and other
schema behavior.

This task adds schema and CPU preparation only. GPU allocation/binding, host-value
coverage, converter emission and runtime rendering evidence are separate integration
work. The parent integration run owns final full-suite/build verification.
