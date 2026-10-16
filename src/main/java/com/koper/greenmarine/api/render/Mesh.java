package com.koper.greenmarine.api.render;

import com.mojang.renderpearl.api.vertex.VertexFormat;

/** Reusable single vertex binding, non-indexed triangle mesh. */
public final class Mesh implements AutoCloseable {
    final RenderSession session;
    final VertexFormat format;
    final int vertices;
    final BufferHandle buffer;
    Mesh(RenderSession session, VertexFormat format, int vertices, BufferHandle buffer) {
        this.session = session; this.format = format; this.vertices = vertices; this.buffer = buffer;
    }
    static void validate(int stride, int vertices, long bytes) {
        if (stride <= 0 || vertices <= 0 || vertices % 3 != 0 || bytes != (long)stride * vertices)
            throw new IllegalArgumentException("Mesh must contain exactly the declared triangle vertices");
    }
    void check(RenderSession expected, ShaderProgram program) {
        buffer.requireOwner(expected.scope);
        checkLayout(format, program);
    }
    static void checkLayout(VertexFormat format, ShaderProgram program) {
        var bindings = program.pipeline().getVertexFormatBindings();
        if (bindings.stream().filter(java.util.Objects::nonNull).count() != 1 || !format.equals(bindings.getFirst())) throw new IllegalArgumentException("Mesh layout differs from program");
    }
    @Override public void close() { buffer.close(); }
}
