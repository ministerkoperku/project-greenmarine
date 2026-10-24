package com.koper.greenmarine.api.render;

import com.koper.greenmarine.client.ProgramSources;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.util.Objects;

/** A render-thread owner for one mod contribution; factories normally receive this from MarineRenderer. */
public final class RenderSession implements AutoCloseable {
    final ResourceScope scope = new ResourceScope();
    private final GpuDevice device;
    private long textures, buffers, programs, computePrograms, dispatches;
    public record Stats(long textureAllocations, long bufferAllocations, long programCompilations) {}
    public record ComputeStats(long programs, long dispatches) {}
    public RenderSession(GpuDevice device) { com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread(); this.device = Objects.requireNonNull(device); }
    public GpuDevice device() { scope.check(); return device; }
    public Stats stats() { scope.check(); return new Stats(textures, buffers, programs); }
    public TextureHandle texture(String label, TextureSpec spec, int frameWidth, int frameHeight) {
        scope.check();
        int width = spec.width(frameWidth), height = spec.height(frameHeight);
        var texture = device.createTexture(label, GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
            | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST, spec.format(), width, height, 1, 1);
        try { var view = device.createTextureView(texture); textures++; return new TextureHandle(scope, texture, view); }
        catch (RuntimeException | Error e) { texture.close(); throw e; }
    }
    /** Compute is a Vulkan-only path; on other backends the storage/compute factories throw. */
    public boolean computeSupported() { scope.check(); return com.koper.greenmarine.client.ComputeBackend.vulkan(device) != null; }
    public ComputeStats computeStats() { scope.check(); return new ComputeStats(computePrograms, dispatches); }
    private com.mojang.renderpearl.backend.vulkan.VulkanDevice vulkan() {
        var vk = com.koper.greenmarine.client.ComputeBackend.vulkan(device);
        if (vk == null) throw new UnsupportedOperationException("Compute and storage resources require the Vulkan backend");
        return vk;
    }
    /** Sampleable, attachable and writable from compute ({@code image2D} in GENERAL layout). */
    public TextureHandle storageTexture(String label, TextureSpec spec, int frameWidth, int frameHeight) {
        scope.check(); var vk = vulkan();
        if (!com.koper.greenmarine.client.ComputeBackend.storageFormat(vk, spec.format()))
            throw new IllegalArgumentException("Format cannot be a storage image on this device: " + spec.format());
        int width = spec.width(frameWidth), height = spec.height(frameHeight);
        var texture = com.koper.greenmarine.client.HostStorage.create(() -> device.createTexture(label, GpuTexture.USAGE_RENDER_ATTACHMENT
            | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST, spec.format(), width, height, 1, 1));
        try { var view = device.createTextureView(texture); textures++; return new TextureHandle(scope, com.koper.greenmarine.client.ComputeBackend.storage(texture), view); }
        catch (RuntimeException | Error e) { texture.close(); throw e; }
    }
    /** Zero-filled, copyable GPU buffer usable as a {@code buffer} block; extra host usage (vertex, index, uniform) may be added. */
    public BufferHandle storageBuffer(String label, long size, int extraUsage) {
        scope.check(); vulkan();
        if (size <= 0 || size > 1L << 30 || size % 4 != 0) throw new IllegalArgumentException("Storage buffer size must be a positive multiple of 4 up to 1 GiB");
        var buffer = com.koper.greenmarine.client.HostStorage.create(() -> device.createBuffer(() -> label, extraUsage | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC, size));
        buffers++;
        try {
            var encoder = device.createCommandEncoder(); var zero = org.lwjgl.system.MemoryUtil.memCalloc((int) Math.min(size, 1 << 20));
            try { for (long at = 0; at < size; at += zero.capacity()) { int n = (int) Math.min(zero.capacity(), size - at); encoder.writeToBuffer(buffer.slice(at, n), zero.duplicate().limit(n)); } }
            finally { org.lwjgl.system.MemoryUtil.memFree(zero); }
        } catch (RuntimeException | Error e) { buffer.close(); throw e; }
        return new BufferHandle(scope, com.koper.greenmarine.client.ComputeBackend.storage(buffer));
    }
    public ComputeHandle compute(ComputeProgram program) {
        scope.check(); var vk = vulkan();
        byte[] spirv = program.rawSpirv();
        if (spirv == null) spirv = com.koper.greenmarine.client.ComputeBackend.compile(program.id().toString(), program.expandedSource());
        var reflection = com.koper.greenmarine.client.ComputeBackend.reflect(spirv);
        var limits = com.koper.greenmarine.client.ComputeBackend.limits(vk);
        if ((long) reflection.localX() * reflection.localY() * reflection.localZ() > limits.maxInvocations()
            || reflection.localX() > limits.maxGroupSize()[0] || reflection.localY() > limits.maxGroupSize()[1] || reflection.localZ() > limits.maxGroupSize()[2])
            throw new IllegalArgumentException("Compute local size exceeds device limits: " + program.id());
        var pipeline = com.koper.greenmarine.client.ComputeBackend.create(vk, spirv, reflection);
        try { var handle = new ComputeHandle(scope, program, pipeline, vk); computePrograms++; return handle; }
        catch (RuntimeException | Error e) { pipeline.release(vk); throw e; }
    }
    /**
     * Records one dispatch outside render passes, between full memory barriers, so earlier draws
     * and copies are visible to the shader and its writes are visible to everything after it.
     */
    public void dispatch(CommandEncoder encoder, ComputeBindings bindings, int groupsX, int groupsY, int groupsZ) {
        scope.check(); var vk = vulkan(); bindings.program.check();
        var limits = com.koper.greenmarine.client.ComputeBackend.limits(vk);
        if (groupsX < 1 || groupsY < 1 || groupsZ < 1 || groupsX > limits.maxGroupCount()[0] || groupsY > limits.maxGroupCount()[1] || groupsZ > limits.maxGroupCount()[2])
            throw new IllegalArgumentException("Workgroup count outside device limits");
        com.koper.greenmarine.client.ComputeBackend.dispatch(encoder, bindings.program.pipeline(), bindings.resolve(), groupsX, groupsY, groupsZ);
        dispatches++;
    }
    public BufferHandle buffer(String label, int usage, ByteBuffer data) {
        scope.check(); if (!data.hasRemaining()) throw new IllegalArgumentException("Empty GPU buffer");
        var buffer = device.createBuffer(() -> label, usage, data.duplicate()); buffers++;
        return new BufferHandle(scope, buffer);
    }
    public void write(CommandEncoder encoder, BufferHandle buffer, long offset, ByteBuffer data) {
        scope.check(); buffer.requireOwner(scope);
        if ((buffer.buffer().usage() & GpuBuffer.USAGE_COPY_DST) == 0) throw new IllegalArgumentException("Buffer is not writable");
        if (offset < 0 || offset > buffer.buffer().size() - data.remaining()) throw new IllegalArgumentException("Buffer write exceeds allocation");
        encoder.writeToBuffer(buffer.buffer().slice(offset, data.remaining()), data.duplicate());
    }
    public ProgramHandle compile(ShaderProgram definition) {
        scope.check();
        boolean vulkan = device.getDeviceInfo().backendName().equalsIgnoreCase("Vulkan");
        try (var sources = new ProgramSources(definition, vulkan)) {
            var pending = device.compilePipeline(definition.pipeline(), sources, Runnable::run).join();
            var compiled = pending.finishCompile();
            if (compiled == null) throw new IllegalArgumentException("Unable to compile program: " + definition.pipeline().getLocation());
            programs++; return new ProgramHandle(scope, definition, compiled);
        }
    }
    public Mesh mesh(String label, com.mojang.renderpearl.api.vertex.VertexFormat format, int vertices, ByteBuffer data) {
        scope.check();
        if(format.getStepRate()!=0)throw new IllegalArgumentException("Mesh requires a per-vertex binding");
        Mesh.validate(format.getVertexSize(), vertices, data.remaining());
        return new Mesh(this, format, vertices, buffer(label, GpuBuffer.USAGE_VERTEX, data));
    }
    /** Indices are unsigned SHORT/INT in native byte order; positions and limits are preserved. */
    public Mesh indexedMesh(String label,com.mojang.renderpearl.api.vertex.VertexFormat format,int vertices,ByteBuffer vertexData,
                            com.mojang.renderpearl.api.pipeline.IndexType type,ByteBuffer indexData) {
        scope.check();
        if(format.getStepRate()!=0)throw new IllegalArgumentException("Mesh requires a per-vertex binding");
        Mesh.validateVertices(format.getVertexSize(),vertices,vertexData.remaining());
        int count=Mesh.validateIndices(vertices,type,indexData);
        var vertexBuffer=buffer(label+" vertices",GpuBuffer.USAGE_VERTEX,vertexData);
        try {
            var indexBuffer=buffer(label+" indices",GpuBuffer.USAGE_INDEX,indexData);
            return new Mesh(this,format,vertices,vertexBuffer,indexBuffer,type,count);
        } catch(RuntimeException|Error error) {vertexBuffer.close();throw error;}
    }
    public GraphExecution graph(RenderGraph graph) { scope.check(); return new GraphExecution(this, graph); }
    @Override public void close() { scope.close(); }
}
