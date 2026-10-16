package com.koper.greenmarine.api.render;

import java.util.*;
import java.util.function.Consumer;

/** Immutable single-writer texture graph. Renderpearl supplies backend synchronization. */
public final class RenderGraph {
    public record Pass(String id, Set<String> reads, Set<String> writes, Set<String> after,
                       Consumer<GraphExecution.PassContext> execute) {
        public Pass {
            name(id); reads = Set.copyOf(reads); writes = Set.copyOf(writes); after = Set.copyOf(after);
            Objects.requireNonNull(execute);
            if (!Collections.disjoint(reads, writes)) throw new IllegalArgumentException("Texture feedback in pass " + id);
        }
    }
    private final Map<String, TextureSpec> textures;
    private final Set<String> imports;
    private final List<Pass> passes;
    private RenderGraph(Builder builder) {
        textures = Collections.unmodifiableMap(new LinkedHashMap<>(builder.textures));
        imports = Set.copyOf(builder.imports);
        var byId = new LinkedHashMap<String, Pass>();
        var writers = new HashMap<String, String>();
        for (var p : builder.passes) {
            if (byId.putIfAbsent(p.id(), p) != null) throw new IllegalArgumentException("Duplicate pass: " + p.id());
            for (String r : union(p.reads(), p.writes())) {
                if (!textures.containsKey(r) && !imports.contains(r)) throw new IllegalArgumentException("Unknown texture: " + r);
            }
            for (String r : p.writes()) if (writers.putIfAbsent(r, p.id()) != null)
                throw new IllegalArgumentException("Multiple writers for " + r);
        }
        var edges = new LinkedHashMap<String, Set<String>>();
        byId.keySet().forEach(id -> edges.put(id, new HashSet<>()));
        for (var p : byId.values()) {
            for (String a : p.after()) {
                if (!byId.containsKey(a)) throw new IllegalArgumentException("Unknown dependency: " + a);
                edges.get(p.id()).add(a);
            }
            for (String r : p.reads()) {
                String writer = writers.get(r);
                if (imports.contains(r)) {
                    if (writer != null) edges.get(writer).add(p.id());
                } else {
                    if (writer == null) throw new IllegalArgumentException("Uninitialized texture: " + r);
                    edges.get(p.id()).add(writer);
                }
            }
        }
        var order = new ArrayList<Pass>();
        var remaining = new LinkedHashSet<>(byId.keySet());
        while (!remaining.isEmpty()) {
            String next = remaining.stream().filter(id -> edges.get(id).isEmpty()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Render graph dependency cycle: " + remaining));
            remaining.remove(next); order.add(byId.get(next));
            edges.values().forEach(deps -> deps.remove(next));
        }
        passes = List.copyOf(order);
    }
    private static Set<String> union(Set<String> a, Set<String> b) { var result = new HashSet<>(a); result.addAll(b); return result; }
    static void name(String name) { if (name == null || name.isBlank()) throw new IllegalArgumentException("Empty graph name"); }
    public Map<String, TextureSpec> textures() { return textures; }
    public Set<String> imports() { return imports; }
    public List<Pass> passes() { return passes; }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private final Map<String, TextureSpec> textures = new LinkedHashMap<>();
        private final Set<String> imports = new LinkedHashSet<>();
        private final List<Pass> passes = new ArrayList<>();
        public Builder texture(String name, TextureSpec spec) {
            unique(name); textures.put(name, Objects.requireNonNull(spec)); return this;
        }
        public Builder importTexture(String name) { unique(name); imports.add(name); return this; }
        private void unique(String name) {
            RenderGraph.name(name);
            if (textures.containsKey(name) || imports.contains(name)) throw new IllegalArgumentException("Duplicate texture: " + name);
        }
        public Builder pass(Pass pass) { passes.add(Objects.requireNonNull(pass)); return this; }
        public RenderGraph build() { return new RenderGraph(this); }
    }
}
