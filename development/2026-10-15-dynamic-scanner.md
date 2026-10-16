# Dynamic section scanner implementation handoff

`WorldSceneScanner` now extracts section-local immutable geometry under a per-call
block/time budget. Model, tint and world access remains on the calling render
thread. Each completed section increments the revision immediately; no full-scene
completion gate is required for snapshot publication. A nearby dirty section can
preempt unfinished distant work. This removes the previous all-sections CPU gate;
native allocation, publication and GPU completion remain separate responsibilities.

## Integration API

- `retarget(SceneBounds, double cameraX, double cameraY, double cameraZ)` preserves
  overlapping cached sections and updates deterministic distance priority. Bounds
  use the existing 16-block scene grid.
- `step(Minecraft, int blocks, long nanos)` still returns true only when all active
  sections are resolved, preserving the legacy completion meaning. Empty/missing
  sections resolve without visiting 4096 block states. Loaded identity checks and
  one final block/mesh completion may exceed a very small wall budget; this is a
  cooperative budget, not a hard deadline.
- `revision()` and `snapshot()` expose earlier partial progress. Snapshot records
  contain `bounds`, `revision`, `complete`, `pendingSections` and immutable
  `sections`. `Snapshot.assemble()` accesses only those immutable meshes and may
  run on the single preparation worker. It preserves stable section order and
  rebases integer section origins before float addition.
- `boundsReadyPending()` returns unresolved active section count. `progress()`
  counts resolved section cells plus valid partial work. `completed()` explicitly
  assembles only a fully resolved legacy scene without retaining its packed
  buffer in the scanner; do not call it on the async hot
  path. Legacy `begin(int,int,int)` refreshes a repeated identical bound, while a
  changed bound reuses overlap. New frame-driven callers should use `retarget`.
- `invalidateBlock(BlockPos)` marks sections touching the block's one-block
  neighborhood. `invalidateColumn(int,int)` includes 3x3 columns for face/tint
  dependencies. `unloadColumn` immediately removes that column's snapshot meshes.
  `invalidateAll()` invalidates all extraction tickets; `reset()` releases cache
  ownership and rejects every old ticket.

`WorldSceneScanner.Metrics` fields are `rebuiltSections`, `cacheHits`,
`cachedSections`, `pendingSections`, `scannedBlocks`, `scanNanos` and
`pendingAgeNanos`. The last value is age of the continuous pending CPU backlog;
it is not edit-to-GPU latency. `TriangleScene.hash()` allows the parent to retain
origin/count/hash metadata after native preparation releases its packed buffer.

## Ownership and invalidation

The active 96x64x96 extent contains 144 sections. The access-ordered cache retains
at most 216 active/recent section entries. X movement by 16 blocks reuses 120
active meshes; Y movement reuses 108. Retarget changes invalidate old partial
tickets even if the same section later reenters. Block/column invalidation also
invalidates in-progress tickets. Previous complete geometry remains available
while its replacement is extracted; unload immediately removes it from new
snapshots. Already submitted GPU generations require the parent's native lifetime
and publication handling.

Every step rechecks bounded cached columns with `getChunk(FULL,false)` and compares
weak identities. No loaded/null lookup result is used as the next frame's source.
An initial observation of an already loaded newly entered column does not dirty
overlap; subsequent identity changes invalidate neighboring columns. Chunk objects
used for extraction are local to the current step. Empty sections share one empty
mesh and do not allocate a large staging buffer. World changes automatically reset
the scanner. Resource/model reload must call `reset`; tint-only changes may call
`invalidateAll` through the integration hooks.

## Isolated verification

The initial missing-cache test failed before implementation. An additional
retarget/eviction/reentry regression failed because an old ticket could become
valid again; changing the bounds epoch rejects that work. The final isolated Java
25 checks cover incremental near publication, 120/108 overlap reuse, boundary edit
invalidation, dirty priority, stale tickets, unload/reload, neighboring tint
invalidation, bounded retention, immutable worker assembly, deterministic packing,
world-border precision, material byte preservation and triangle budgets.

The scanner also compiles directly against cached Minecraft26.3 classes with
`javac --release 25`. Full Gradle checks and actual loaded-world movement/edit,
packet/unload and GPU latency measurements belong to the coordinated parent run.
No game client, Gradle task or installed jar was changed by this scanner task.
