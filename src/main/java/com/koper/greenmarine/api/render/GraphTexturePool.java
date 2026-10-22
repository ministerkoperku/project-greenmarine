package com.koper.greenmarine.api.render;

import java.util.*;
import java.util.function.BiFunction;

/** Owns physical slots; logical aliases never take independent ownership. */
final class GraphTexturePool<T extends AutoCloseable> implements AutoCloseable {
    private final GraphAllocationPlan plan;
    private List<T> resources = List.of();
    private int width, height;

    GraphTexturePool(GraphAllocationPlan plan) { this.plan = plan; }
    List<T> resources() { return resources; }
    T resource(String name) {
        Integer slot = plan.assignments().get(name);
        if (slot == null) throw new IllegalArgumentException("No transient allocation for " + name);
        return resources.get(slot);
    }
    void prepare(int frameWidth, int frameHeight, BiFunction<Integer, TextureSpec, T> allocator) {
        if (frameWidth <= 0 || frameHeight <= 0) throw new IllegalArgumentException("Frame dimensions must be positive");
        if (width == frameWidth && height == frameHeight) return;
        var fresh = new ArrayList<T>();
        var allocated = new ArrayList<T>();
        var retired = new ArrayList<T>();
        try {
            for (int slot = 0; slot < plan.physicalTextureCount(); slot++) {
                var spec = plan.physicalSpecs().get(slot);
                T previous = slot < resources.size() ? resources.get(slot) : null;
                if (previous != null && spec.width(width) == spec.width(frameWidth) && spec.height(height) == spec.height(frameHeight))
                    fresh.add(previous);
                else {
                    var created = Objects.requireNonNull(allocator.apply(slot, spec));
                    allocated.add(created); fresh.add(created);
                    if (previous != null) retired.add(previous);
                }
            }
        } catch (RuntimeException | Error failure) {
            closeAll(allocated, failure);
            throw failure;
        }
        resources = List.copyOf(fresh); width = frameWidth; height = frameHeight;
        closeAll(retired, null);
    }
    @Override public void close() {
        var previous = resources;
        resources = List.of(); width = height = 0;
        closeAll(previous, null);
    }
    private static void closeAll(List<? extends AutoCloseable> resources, Throwable original) {
        Throwable failure = original;
        for (var resource : resources) {
            try { resource.close(); }
            catch (Throwable e) {
                if (failure == null) failure = e;
                else if (failure != e) failure.addSuppressed(e);
            }
        }
        if (original == null && failure != null) {
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Could not close graph texture", failure);
        }
    }
}
