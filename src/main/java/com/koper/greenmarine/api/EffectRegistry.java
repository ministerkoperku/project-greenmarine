package com.koper.greenmarine.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class EffectRegistry {
    private Map<String, EffectDefinition> active = Map.of();
    private Map<String, EffectDefinition> pending;
    private boolean healthy;

    public synchronized void beginReload() {
        pending = new LinkedHashMap<>();
        healthy = true;
    }

    public synchronized void stage(EffectDefinition effect) {
        if (pending == null) throw new IllegalStateException("No effect reload in progress");
        if (pending.putIfAbsent(effect.id(), effect) != null) {
            healthy = false;
            throw new IllegalArgumentException("Duplicate GreenMarine effect: " + effect.id());
        }
    }

    public synchronized void finishReload(boolean success) {
        if (pending != null && success && healthy) active = Map.copyOf(pending);
        pending = null;
        healthy = false;
    }

    public synchronized Optional<EffectDefinition> get(String id) { return Optional.ofNullable(active.get(id)); }
    public synchronized int size() { return active.size(); }
}
