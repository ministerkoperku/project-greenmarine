# Renderer platform implementation plan

Goal: implement the approved renderer-platform direction as working, reviewable
APIs, resource optimization and settings controls. No branches/commits/installed
artifact replacement. Shared checkout contains prior authorized releases.

- [x] Graph task: read RenderGraph/GraphExecution contracts; failing tests for
  equal-spec disjoint lifetime reuse, overlapping reads, imports, unused textures,
  deterministic scheduling and reset/resize cleanup. Implement allocation plan
  and reuse owned attachments. Document logical resources vs physical lifetime.
- [x] UI task: responsive settings/packs/quality/performance controls, helpful
  effective RT/fallback status; preserve old entrypoints and parameters. Avoid
  polling/sorting timing snapshots every render call. Test layout/pagination
  policies on small/mobile sizes and many packs; document validation limits.
- [x] Root task: actual host indexed/instanced command contract inspection,
  failing range/layout/ownership tests, compatible mesh overloads and commands.
  Cache redundant world bindings per pass with explicit binding epoch/flags;
  invalidate on pipeline/frame/capture mode changes. Verify counted submissions.
- [x] Integrate independent changes; review owner/resource boundaries and
  APIs; full Gradle test/build with Android native, Python shader/contract suite,
  Rust tests, packaged Android FFM smoke, checksums and Android export.
- [x] Record measurable saved allocations/binding work separately from game
  timing. Report remaining compute/scene/cloud limitations honestly.
