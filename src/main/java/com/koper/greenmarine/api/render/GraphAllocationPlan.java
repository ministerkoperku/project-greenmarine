package com.koper.greenmarine.api.render;

import java.util.*;

/** Immutable physical texture slots and lifetimes, with opt-in transient reuse. */
public final class GraphAllocationPlan {
    public record Lifetime(int firstUse, int lastUse) {}
    private final Map<String, Lifetime> lifetimes;
    private final Set<String> transientTextures;
    private final Map<String, Integer> assignments;
    private final List<TextureSpec> physicalSpecs;

    GraphAllocationPlan(Map<String, TextureSpec> textures, Set<String> transientTextures, List<RenderGraph.Pass> passes) {
        this.transientTextures = Set.copyOf(transientTextures);
        var intervals = new LinkedHashMap<String, Lifetime>();
        for (String name : textures.keySet()) {
            int first = -1, last = -1;
            for (int i = 0; i < passes.size(); i++) {
                var pass = passes.get(i);
                if (pass.reads().contains(name) || pass.writes().contains(name)) {
                    if (first < 0) first = i;
                    last = i;
                }
            }
            if (first >= 0) intervals.put(name, new Lifetime(first, last));
        }
        var slots = new ArrayList<TextureSpec>();
        var lastUses = new ArrayList<Integer>();
        var assigned = new LinkedHashMap<String, Integer>();
        // Persistent declarations reserve dedicated storage, even when unused.
        for (var entry : textures.entrySet()) {
            if (!transientTextures.contains(entry.getKey())) {
                assigned.put(entry.getKey(), slots.size());
                slots.add(entry.getValue()); lastUses.add(Integer.MAX_VALUE);
            }
        }
        int persistentSlots = slots.size();
        // List.sort is stable: declaration order breaks ties in first use.
        var names = new ArrayList<>(intervals.keySet());
        names.removeIf(name -> !transientTextures.contains(name));
        names.sort(Comparator.comparingInt(name -> intervals.get(name).firstUse()));
        for (String name : names) {
            var lifetime = intervals.get(name);
            var spec = textures.get(name);
            int slot = persistentSlots;
            while (slot < slots.size() && (!slots.get(slot).equals(spec) || lastUses.get(slot) >= lifetime.firstUse())) slot++;
            if (slot == slots.size()) { slots.add(spec); lastUses.add(lifetime.lastUse()); }
            else lastUses.set(slot, lifetime.lastUse());
            assigned.put(name, slot);
        }
        lifetimes = Collections.unmodifiableMap(intervals);
        assignments = Collections.unmodifiableMap(assigned);
        physicalSpecs = List.copyOf(slots);
    }
    public Set<String> transientTextures() { return transientTextures; }
    public Map<String, Lifetime> lifetimes() { return lifetimes; }
    public Map<String, Integer> assignments() { return assignments; }
    public List<TextureSpec> physicalSpecs() { return physicalSpecs; }
    public int physicalTextureCount() { return physicalSpecs.size(); }
}
