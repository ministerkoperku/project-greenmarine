# Render graph texture allocation

`RenderGraph.Builder.texture(name, spec)` preserves dedicated, persistent storage
for existing callers, including unused declarations. Its contents remain available
across executions until overwritten or resized. To allow storage reuse, explicitly
opt in with `builder.transientTexture(name, spec)`.

`RenderGraph` compiles pass dependencies before planning transient textures.
`graph.allocationPlan()` exposes immutable logical lifetimes, logical-to-physical
slot assignments, physical texture specifications and the physical texture count.
Indices in a lifetime refer to `graph.passes()`, including the first and last
pass that reads or writes the texture.

Only explicitly transient textures with exactly equal `TextureSpec` values can
share a physical texture. Persistent declarations reserve dedicated slots and
never share with transient declarations. The previous logical texture's last use must be strictly before the next one's
first use. A pass reading one texture while writing another therefore receives
distinct storage. Differences in format, fixed size or scale prevent reuse,
even if the resolved dimensions happen to match. Imports always remain external
and never participate in transient allocation. Unused transient declarations have
no lifetime or assignment and allocate no storage. `transientTextures()` on the
graph and allocation plan exposes the immutable set of opted-in names.

Scheduling is stable in declaration order where dependencies allow it. Allocation
reserves persistent slots in declaration order, then visits transient textures in
first-use order, breaking ties by texture declaration order. It selects the lowest
compatible free transient slot. Rebuilding the same
graph produces the same plan. For example, write A, consume A, write B, consume B
needs one physical texture when A and B are transient and have equal specs. If the B-writing pass
also consumes A, it needs two. This saves actual GPU textures without changing
resolution, format, samples or shader work.

Textures declared with `transientTexture` are transient. `PassContext.read` and
`write` return borrowed raw views for that callback's declared accesses. Do not
retain views or materials, access them after the callback, or depend on transient
contents across executions. A later logical resource can overwrite the same
physical texture. Use a persistent `texture` declaration for retained graph-local
attachment contents or an imported texture owned by the caller when output must
survive graph execution. Advanced encoder commands must complete access within the
declared pass sequence; do not submit the encoder independently. Renderpearl
supplies backend transitions and ordered command execution; GreenMarine does not
alias separate Vulkan memory objects or introduce backend-specific barriers.

A `GraphExecution` owns one texture per physical slot, through its session.
Unchanged dimensions preserve attachments; fixed-size slots survive frame resize.
Resize first allocates all required replacements. An allocation failure closes
only the newly allocated resources and preserves the previous pool and dimensions
for retry. Successful resize retires replaced physical slots once, regardless of
how many logical names share them. Closing execution releases physical slots once;
session handles also have idempotent closure and host imports are never destroyed.

The allocation and ownership tests use a real planner and transactional pool with
small close-counted resources at the GPU allocation boundary. They cover disjoint
and overlapping lifetimes, equal-size unequal specs, scheduling, deterministic
selection, unused declarations, failed resize, fixed-size retention and repeated
cleanup. They establish logical allocation behavior, not runtime backend rendering
acceptance or a whole-game FPS improvement.
