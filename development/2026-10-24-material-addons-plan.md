# Material addon API implementation plan

Goal: mods/resource packs and private shaderpacks can provide generic or pack-specific painted per-texture materials, including matte emissive screens alongside metal/glass.
Architecture: CPU-only atomic catalog resolves bounded PNG+JSON sources into immutable RGBA8 roughness/reflection/emission/class maps. Existing renderer owners upload them. Terrain reuses specular atlas storage with distinct addon bit 3; explicit LabPBR wins, addon pixels precede inference and builtin defaults, mask holes retain earlier fallback. No frame/native ABI change.
User explicitly authorized implementation; editable PNG+JSON proceeds independently of any future game editor decision.

- [x] Confirm API contract with the optional Kender bridge implementer; write classifier/catalog mask/priority/isolation tests, observe RED, implement CPU API.
- [x] Add optional manifest materialAddons with backward-compatible ShaderPack/LoadedPack constructors (delegated to scoped manifest/catalog worker).
- [ ] Add resource-manager and standalone mod-root discovery, external pack scoping, atomic reload/active pack generation.
- [ ] Observe GPU addon decoding RED; wire terrain properties and external pre-pass material preparation, test explicit map precedence/holes/screens.
- [ ] Run Java, Python shader, Rust and actual Minecraft reload checks; build dual-native Android 0.9.10 and guarded export verified artifacts.

Review focus: per-pixel class zero; palette alpha/default behavior; source and binary bounds; resource priority; private external PNG snapshots; GPU uploads before render passes; active-pack cache invalidation; no GPU ownership in MaterialAddons.
