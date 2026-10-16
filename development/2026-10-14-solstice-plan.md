# Solstice RT implementation plan

1. Preserve baseline and reference hashes; record design and compatibility rules.
2. Add opt-in surface declaration/snapshots, phase-safe terrain replay and GPU
   resources. Test old constructors, host state and invalid declarations first.
3. Add shared-scene surface ray pipeline and separately versioned optional FFM
   functions; retain legacy ABI behavior. Validate SPIR-V and layout tests.
4. Add environment/history contracts and independently authored Solstice pack,
   preserving existing resources and namespace identifiers.
5. Integrate readiness, examples, diagnostics and meaningful runtime probes.
6. Run base/Fullpack, native release and GPU acceptance sequentially; review and
   fix findings, publish local artifacts and update handoff with measured limits.

Independent native and pack/history domains may be delegated under the
dispatching-parallel-agents skill. Parent owns world capture integration.
All builders and live clients must be coordinated by the parent.

## Completion ledger, 2026-10-15

All six steps are implemented. Original Solstice, additive surface/environment/
history contracts and preserved old APIs/archives are packaged in 0.7. Final
Vulkan/OIT and OpenGL/classic probes verify water geometry, material capture,
weather/night/altitude, bob/movement, resize and failure/fallback recovery; legacy
Aurora passes separately. Native GPU readbacks establish real offscreen model
reflections, sun occlusion and emitter GI. Dynamic scene integration addresses
the owner's delayed moving shadows and is measured in its own fixture.

141 Java, 36 Python, 10 portable Rust plus two actual GPU tests and 22 FFM checks
pass; base/Fullpack, release native, clippy/fmt and SPIR-V pass. Independent review
findings were addressed before final runtime acceptance. See
`evidence/solstice/README.md` and its artifact/source hash manifest. Eclipse image
equivalence, full Iris/Veil compatibility, infinite scene coverage, entity/fluid
secondary geometry and FSR4 are not claimed.
