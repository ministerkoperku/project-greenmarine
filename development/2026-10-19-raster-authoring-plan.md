# Raster authoring implementation plan

Goal: deliver usable raster defaults and independently authored external packs.
Architecture: PackSource delegates immutable per-pack resources to PackCatalog;
ExternalPacks snapshots filesystem sources on reload. Existing constructors stay.
Spec: 2026-10-19-raster-authoring-design.md. Execution: inline, authorized by handoff.

Constraints: Minecraft 26.3, Java 25, Loader only; Renderpearl and Vulkan primary;
MarineFrame288/schema1/native ABI unchanged; no installed jars or owner settings changed.

Review focus: duplicate resource names across packs; failed replacement keeps old
catalog; ZIP path traversal/symlinks; aggregate inflated resources; raster bindings
and optional Sodium shader selection.

## Tasks

- [x] Add tests for per-source catalog resolution, diagnostic names and duplicate-read caching;
  run RED, implement PackSource and transactional `reload(List<PackSource>)`, run GREEN.
- [x] Add directory/ZIP isolation and bounds tests; run RED, implement ExternalPacks,
  integrate ResourcePacks reload with mounted-include fallback, run GREEN.
- [x] Add raster default/export and authoring CLI tests; run RED, implement default
  manifest, conditional shader variant, starter/validate/package tool and docs, run GREEN.
- [x] Run full Java test/build, Rust tests, CLI tests; inspect artifacts/diff and record
  limitations and remaining feature scope. Do not claim runtime graphics acceptance.
