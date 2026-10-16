package com.koper.greenmarine.api.render;

import java.util.*;

final class ResourceScope implements AutoCloseable {
    private final Thread thread = Thread.currentThread();
    private final Set<OwnedResource> resources = new LinkedHashSet<>();
    private boolean closed;
    void checkThread() { if (Thread.currentThread() != thread) throw new IllegalStateException("GPU access must stay on its render thread"); }
    void check() { checkThread(); if (closed) throw new IllegalStateException("Render session is closed"); }
    void add(OwnedResource resource) { check(); resources.add(resource); }
    void remove(OwnedResource resource) { resources.remove(resource); }
    @Override public void close() {
        checkThread(); if (closed) return;
        RuntimeException failure = null;
        var pending = new ArrayList<>(resources); Collections.reverse(pending);
        for (var r : pending) try { r.close(); } catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        resources.clear(); closed = true;
        if (failure != null) throw failure;
    }
}
