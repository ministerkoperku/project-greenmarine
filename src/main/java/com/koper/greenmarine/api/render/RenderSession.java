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
    private long textures, buffers, programs;
    public record Stats(long textureAllocations, long bufferAllocations, long programCompilations) {}
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
