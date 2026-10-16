package com.koper.greenmarine.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public final class LightRegistry {
    private final LinkedHashMap<Long, Light> lights = new LinkedHashMap<>();
    private long nextId = 1;
    private List<Light> snapshot = List.of();

    public synchronized long create(Light light) {
        Objects.requireNonNull(light);
        if (nextId == Long.MAX_VALUE) throw new IllegalStateException("Light handle space exhausted");
        long id = nextId++;
        lights.put(id, light);
        snapshot = null;
        return id;
    }

    public synchronized boolean update(long id, Light light) {
        Objects.requireNonNull(light);
        var previous = lights.get(id);
        if (previous == null) return false;
        if (!previous.equals(light)) { lights.put(id, light); snapshot = null; }
        return true;
    }

    public synchronized boolean remove(long id) {
        if (lights.remove(id) == null) return false;
        snapshot = null; return true;
    }
    public synchronized void clear() { lights.clear(); snapshot = List.of(); }
    public synchronized List<Light> snapshot() {
        if (snapshot == null) snapshot = List.copyOf(lights.values());
        return snapshot;
    }
}
