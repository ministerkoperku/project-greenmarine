package com.koper.greenmarine.api.render;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.commands.RenderPass;
import java.nio.*;
import java.util.Objects;

/** Reusable triangle mesh with optional indices and one per-vertex buffer. */
public final class Mesh implements AutoCloseable {
    final RenderSession session;
    final VertexFormat format;
    final int vertices;
    final BufferHandle buffer;
    private final BufferHandle indices;
    private final IndexType indexType;
    private final int indexCount;
    Mesh(RenderSession session,VertexFormat format,int vertices,BufferHandle buffer) {
        this(session,format,vertices,buffer,null,null,0);
    }
    Mesh(RenderSession session,VertexFormat format,int vertices,BufferHandle buffer,BufferHandle indices,IndexType indexType,int indexCount) {
        this.session=session;this.format=format;this.vertices=vertices;this.buffer=buffer;
        this.indices=indices;this.indexType=indexType;this.indexCount=indexCount;
    }
    public int vertexCount() {return vertices;}
    public int indexCount() {return indexCount;}
    public boolean indexed() {return indices!=null;}
    static void validate(int stride,int vertices,long bytes) {
        validateVertices(stride,vertices,bytes);
        if(vertices%3!=0)throw new IllegalArgumentException("Mesh must contain triangle vertices");
    }
    static void validateVertices(int stride,int vertices,long bytes) {
        if(stride<=0 || vertices<=0 || bytes!=(long)stride*vertices)
            throw new IllegalArgumentException("Vertex data must match declared vertex count and stride");
    }
    static int validateIndices(int vertices,IndexType type,ByteBuffer data) {
        Objects.requireNonNull(type);Objects.requireNonNull(data);
        if(data.remaining()==0 || data.remaining()%type.bytes!=0)throw new IllegalArgumentException("Misaligned or empty index data");
        int count=data.remaining()/type.bytes;
        if(count%3!=0)throw new IllegalArgumentException("Indices must describe whole triangles");
        var values=data.duplicate().order(ByteOrder.nativeOrder());
        while(values.hasRemaining()) {
            long index=type==IndexType.SHORT?Short.toUnsignedInt(values.getShort()):Integer.toUnsignedLong(values.getInt());
            if(index>=vertices)throw new IllegalArgumentException("Index exceeds declared vertex count");
        }
        return count;
    }
    static void validateInstances(int instances,int firstInstance) {
        if(instances<=0 || firstInstance<0 || (long)firstInstance+instances>Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid instance range");
    }
    void check(RenderSession expected,ShaderProgram program) {
        buffer.requireOwner(expected.scope);if(indices!=null)indices.requireOwner(expected.scope);
        checkLayout(format,program);
    }
    static void checkLayout(VertexFormat format,ShaderProgram program) {
        var bindings=program.pipeline().getVertexFormatBindings();
        if(bindings.stream().filter(Objects::nonNull).count()!=1 || !format.equals(bindings.getFirst()) || format.getStepRate()!=0)
            throw new IllegalArgumentException("Mesh layout differs from per-vertex program binding");
        if(program.pipeline().getPrimitiveTopology()!=PrimitiveTopology.TRIANGLES)
            throw new IllegalArgumentException("Mesh requires triangle topology");
    }
    void draw(RenderPass pass,int instances,int firstInstance) {
        validateInstances(instances,firstInstance);
        buffer.requireOwner(session.scope);if(indices!=null)indices.requireOwner(session.scope);
        pass.setVertexBuffer(0,buffer.buffer().slice());
        if(indices!=null) {
            pass.setIndexBuffer(indices.buffer(),indexType);
            pass.drawIndexed(indexCount,instances,0,0,firstInstance);
        } else pass.draw(vertices,instances,0,firstInstance);
    }
    @Override public void close() {
        try {if(indices!=null)indices.close();} finally {buffer.close();}
    }
}
