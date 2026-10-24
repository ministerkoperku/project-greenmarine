package com.koper.greenmarine.api.render;

import com.koper.greenmarine.client.ComputeBackend;
import java.util.*;

/** A compiled compute pipeline owned by one session. */
public final class ComputeHandle extends OwnedResource {
    public enum Access { UNIFORM_BUFFER, STORAGE_BUFFER, STORAGE_IMAGE, SAMPLED_TEXTURE }
    public record Binding(String name, int binding, Access access) {}
    private final ComputeProgram program;
    private final ComputeBackend.Pipeline pipeline;
    private final com.mojang.renderpearl.backend.vulkan.VulkanDevice device;
    private final List<Binding> bindings;
    ComputeHandle(ResourceScope owner, ComputeProgram program, ComputeBackend.Pipeline pipeline, com.mojang.renderpearl.backend.vulkan.VulkanDevice device) {
        super(owner); this.program = program; this.pipeline = pipeline; this.device = device;
        bindings = pipeline.reflection().slots().stream()
            .map(slot -> new Binding(slot.name(), slot.binding(), Access.valueOf(slot.kind().name()))).toList();
    }
    public ComputeProgram program() { check(); return program; }
    /** Reflected resources in binding order. */
    public List<Binding> bindings() { check(); return bindings; }
    /** {@code layout(local_size_x/y/z)} of the module, for sizing dispatches. */
    public int[] localSize() { check(); var r = pipeline.reflection(); return new int[]{r.localX(), r.localY(), r.localZ()}; }
    /** Workgroups needed to cover {@code width x height} invocations with this module's local size. */
    public int groupsX(int width) { check(); return Math.max(1, Math.ceilDiv(width, pipeline.reflection().localX())); }
    public int groupsY(int height) { check(); return Math.max(1, Math.ceilDiv(height, pipeline.reflection().localY())); }
    public ComputeBindings bind() { check(); return new ComputeBindings(this); }
    ComputeBackend.Pipeline pipeline() { check(); return pipeline; }
    @Override protected void destroy() { pipeline.release(device); }
}
