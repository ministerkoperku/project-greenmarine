package com.koper.greenmarine.api.render;

import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.*;
import java.nio.ByteBuffer;
import java.util.*;

/** Per-pass bindings to a reusable program. A material expires with its pass callback. */
public final class Material {
    private record Texture(GpuTextureView view, GpuSampler sampler) {}
    final GraphExecution.PassContext context;
    final ProgramHandle program;
    private final Map<String, Object> bindings = new LinkedHashMap<>();
    Material(GraphExecution.PassContext context, ProgramHandle program) {
        this.context = context; this.program = program; program.requireOwner(context.session().scope);
    }
    public Material uniform(String name, ByteBuffer data) {
        context.check(); program.definition().checkBinding(name, UniformType.UNIFORM_BUFFER);
        if (!data.hasRemaining() || data.remaining() % 16 != 0) throw new IllegalArgumentException("Uniform data must contain whole std140 slots");
        var session = context.session();
        var slice = context.encoder().transientMemory().uploadGpu(data.duplicate(),
            session.device().getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
        bindings.put(name, slice); return this;
    }
    public Material uniform(String name, BufferHandle buffer) {
        context.check(); program.definition().checkBinding(name, UniformType.UNIFORM_BUFFER);
        buffer.requireOwner(context.session().scope);
        if ((buffer.buffer().usage() & GpuBuffer.USAGE_UNIFORM) == 0) throw new IllegalArgumentException("Buffer lacks uniform usage");
        bindings.put(name, buffer); return this;
    }
    public Material texture(String name, String resource, FilterMode filter) {
        context.check(); program.definition().checkBinding(name, UniformType.COMBINED_IMAGE_SAMPLER);
        var view = context.read(resource);
        var sampler = com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getClampToEdge(Objects.requireNonNull(filter));
        bindings.put(name, new Texture(view, sampler)); return this;
    }
    void check() {
        context.check(); program.requireOwner(context.session().scope); program.definition().checkComplete(bindings.keySet());
        for (var value : bindings.values()) if (value instanceof BufferHandle handle) handle.requireOwner(context.session().scope);
    }
    void apply(RenderPass pass) {
        check(); pass.setPipeline(program.pipeline());
        for (var binding : bindings.entrySet()) {
            var value = binding.getValue();
            if (value instanceof GpuBufferSlice slice) pass.setUniform(binding.getKey(), slice);
            else if (value instanceof BufferHandle buffer) pass.setUniform(binding.getKey(), buffer.buffer());
            else if (value instanceof Texture texture) pass.setUniform(binding.getKey(), texture.view(), texture.sampler());
        }
    }
}
