# Inferred Solstice materials implementation plan

Goal: useful conservative material response when a resource pack supplies only albedo.
Architecture: infer texture families while building the existing PBR atlas. Store inferred properties with mask bit 2, independent of explicit specular/normal presence. Keep every builtin profile and explicit map authoritative. Apply bounded albedo roughness variation to matte surfaces only. No new shader bindings, frame layout, dependencies, native ABI or terrain paths.
Tech stack: Java 25, Renderpearl textures, shared GLSL Vulkan/OpenGL.

The owner explicitly requested implementation without further design questions. Chosen approach: category defaults plus existing albedo relief; expanding uniform profiles would exhaust fixed slots, while arbitrary luminance-to-metal inference would misclassify bright stone. This is heuristic material inference, not recovery of authored normals or POM.

- [x] Add behavior tests for category distinctions, ores/unknowns, explicit profiles, shader precedence and bounded matte roughness; observe failures.
- [x] Implement pure texture classifier, atlas population and shared shader application. Preserve independent normal maps, water and transparent profiles.
- [x] Run complete Java/Python suites and opt-in Vulkan material fixtures; build dual native Android 0.9.9, verify guarded Android export and ZIP bytes.

Review focus: explicit black maps; specular-only/normal-only packs; transparent/water class preservation; copper oxidation and ores; unknown mod textures remain rough; capture/shading roughness agreement. No commits, branch changes, installed jar replacements or sibling edits.
