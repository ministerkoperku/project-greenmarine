package com.koper.greenmarine.api.render;

/** Explicitly owned GPU object. Raw host views inherit this object's lifetime. */
public abstract class OwnedResource implements AutoCloseable {
    private final ResourceScope owner;
    private boolean closed;
    OwnedResource(ResourceScope owner) { this.owner = owner; owner.add(this); }
    protected final void check() { owner.check(); if (closed) throw new IllegalStateException("GPU resource is closed"); }
    final void requireOwner(ResourceScope expected) {
        check(); if (owner != expected) throw new IllegalArgumentException("GPU resource belongs to a different session");
    }
    public final boolean isClosed() { return closed; }
    @Override public final void close() {
        owner.checkThread(); if (closed) return;
        closed = true; owner.remove(this); destroy();
    }
    protected abstract void destroy();
}
