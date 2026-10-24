# Material addons verification

API1 provides generic and shaderpack-specific material definitions with palette
PNG masks. Mounted JSON replaces mod JSON by resource ID; private shaderpack
sources remain scoped to their manifest owner. Terrain preserves authored LabPBR
precedence. Kender consumes the CPU API through an optional adapter.

Dual-native Gradle `test build` succeeded: 274 ordinary tests, 7 skipped, no
failures/errors. Six separately enabled GPU upload tests and 59 Python checks
passed. A real Minecraft atlas probe passed addon upload and mapped/removal/restore
reload phases, with authored specular precedence. These checks do not validate the
external robot draw on an Android GPU.

Review regressions cover early decoded-mask budget enforcement and exclusion of
manifest-owned resources from global discovery. Mod-root discovery works without
requiring Fabric ResourceLoader. Invalid sources are isolated.

Export: `greenmarine-0.9.10-prototype.jar`, identical to the dual-native build.
Android native SHA256:
`4fb00f3906a8833b0f70440b96237f00b3ff33219514a28f1d367f5a1150e441`.
See `docs/MATERIAL-ADDONS.md` for the authoring schema and lifecycle contract.
