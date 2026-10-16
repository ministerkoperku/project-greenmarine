package com.koper.greenmarine.api.render;

import com.mojang.renderpearl.api.buffers.GpuBuffer;

public final class BufferHandle extends OwnedResource {
    private final GpuBuffer buffer;
    BufferHandle(ResourceScope owner, GpuBuffer buffer) { super(owner); this.buffer = buffer; }
    public GpuBuffer buffer() { check(); return buffer; }
    @Override protected void destroy() { buffer.close(); }
}
