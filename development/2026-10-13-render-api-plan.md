# Public rendering API implementation plan

> Execution: superpowers:executing-plans, inline under owner authorization.

**Goal:** Deliver reusable rendering contracts and an actual geometry/material consumer.
**Architecture:** Client-only sessions own host-backed GPU objects. A compiled
texture graph schedules contribution passes around the existing renderer.
**Tech Stack:** Java 25, Minecraft 26.3 Renderpearl, Fabric Loader, GLSL/SPIR-V.
**Spec:** `development/2026-10-13-render-api-design.md`.

## Global constraints

Standalone base; Fullpack optional. Vulkan primary, shared OpenGL commands.
Preserve existing packs, constructors, MarineFrame 288-byte ABI and Lua.
No FSR4, RT claims, commits, branches, installed jars or sibling edits.
Public docs in docs; plans/evidence in development; English new text.

## Review focus

- Borrowed host textures must survive graph/session closure.
- Resize allocation failures must close new partial allocations.
- Undeclared access and feedback must fail before recording a GPU draw.
- World/reload reset must preserve registrations and clear failed sessions.
- A rejected shader contribution must leave existing pack rendering working.

### Task 1: GPU-free graph and shader contracts

Files: `api/render/RenderGraph.java`, `TextureSpec.java`, `ShaderProgram.java`,
`FrameContext.java`; tests under `src/test/java/.../api/render/`.
Produces: immutable graph resources/passes/compiled order; immutable program
source snapshot; frame-relative matrices. Runtime executor consumes them.

- [x] Write tests for reversed producer order, imported read-before-write,
cycles, missing producers, duplicate writes, feedback, invalid scales,
shader snapshots and camera-relative precision; run test and observe RED.
- [x] Implement validation, stable topological compilation and snapshots.
- [x] Run Java tests; expected all passing.

### Task 2: Owned sessions, materials and graph execution

Files: `api/render/RenderSession.java`, typed handles, `Material.java`,
`Mesh.java`, `GraphExecution.java`, `client/ProgramSources.java`.
Consumes Task 1 contracts; produces reusable host-backed execution.

- [x] Write lifecycle, cross-owner, binding/mesh and attachment regression
cases; runtime probe supplies GPU assertions. Observe RED before implementation.
- [x] Implement allocation cleanup, idempotent closure, typed bindings and
frame-sized target reuse, checked graph access and host MRT draw calls.
- [x] Run Java tests and build; expected all passing.

### Task 3: Stage registry and real geometry consumer

Files: `api/render/MarineRenderer.java`, `client/GeometryExample.java`,
existing lifecycle mixins and test-only render probe.
Consumes sessions/graph; produces opt-in mesh G-buffer and composite.

- [x] Add failure-isolation and registration/reset tests and runtime probe.
- [x] Implement stages, lazy sessions and optional example; verify Vulkan
then OpenGL, resize/reload, depth occlusion, lease cleanup and shader rejection.
- [x] Record CPU/GPU timing, inspect captures, run base and Fullpack test/build,
Rust/Python regressions and inspect jars; expected success with probe excluded.
- [x] Update public guide, progress and session handoff with implemented
limits and exact evidence. Review once and close concrete findings.

## Execution ledger and rulings

Owner authorization selects inline work and removes routine design/plan approval
pauses. This untracked project cannot provide task commit ranges; no commits or
workspace/index changes are authorized. The evidence directory is this plan's
ledger instead of a parent-repository `.superpowers` workspace.

Pre-flight: Task 1 graph/program/frame contracts feed Task 2 sessions/execution;
Task 3 uses those same signatures and host stage hooks. No interface conflict.
Task 1: complete; contracts-red.txt -> contracts-green.txt, later final 83 tests.
Task 2: complete; resources-red.txt, resize-red.txt -> review-green.txt / final tests.
Task 3: complete; registry-red.txt -> registry-green.txt; final Vulkan/OpenGL probes,
base/fullpack builds, Rust/Python tests and artifact checks pass.

Rulings: graph resource history and a vanilla G-buffer stay future work; the
implemented consumer demonstrates contributed geometry. Keep namespaced Java
contributions separate from existing schema-v1 manifests. Use explicit formats,
never silently alter a program's target contract. The portable shader bridge
imports snapshots and does not implicitly supply pack bindings.

One fresh review requested by requesting-code-review reports three Important
findings; all fixed with regressions. Shader/geometry runtime validation followed
the fixes. Example format-change pipeline retention was also fixed. No finding
is deferred; broader device/coexistence coverage remains an explicit limit.
