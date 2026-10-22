# Renderer platform and settings design (0.9.0)

Owner intent: improve GreenMarine itself, rather than only its builtin Solstice
shader, close useful API gaps, reduce renderer overhead and replace the cramped
settings UI. Vulkan does not by itself establish a performance advantage.
Existing autonomous implementation/testing authorization applies.

This delivery adds indexed/instanced contribution drawing on actual host command
APIs, explicit opt-in graph texture reuse for disjoint lifetimes, cached redundant
world binding updates, and a touch-friendly settings interface with CPU/GPU
measurements. Keep public old constructors/methods, schema1/MarineFrame288/native
ABI1, borrowed-host ownership and optional integrations. Preserve shader quality.

General compute/storage images, full world shadow maps, complete scene G-buffers,
PBR texture loading and volumetric clouds remain explicit backlog; host's public
Renderpearl API lacks a compute dispatcher. Do not advertise unsupported paths.

Design acceptance: graph aliases only equal specs with strict nonoverlapping
lifetimes, never imports or simultaneous read/write; draw APIs validate index
format/ranges/instance counts and session ownership; binding cache invalidates
across pipeline switches and binding state changes. UI preserves per-pack values,
shows friendly names, quality/ray selection, real effective status and timing;
small screens have bounded layouts and large touch targets. No measured game
FPS promise without actual comparable CPU/GPU/gameplay samples.

Compatibility refinement: `.texture` retains dedicated persistent storage because
opaque pass callbacks can use attachment loads/blending across executions. Only
new `.transientTexture` declarations opt into aliasing/discardable contents.
Profiling changes no longer rebuild pipelines/native scenes or reset scripts;
PackRenderer disables its timestamp pool dynamically when measurements stop.
