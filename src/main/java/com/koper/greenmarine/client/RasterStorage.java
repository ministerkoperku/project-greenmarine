package com.koper.greenmarine.client;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raster pipelines can't name storage descriptors through BindGroupLayout (UniformType has no
 * such constants), so a storage buffer is declared as UNIFORM_BUFFER and a storage image as
 * COMBINED_IMAGE_SAMPLER, and this registry, keyed by pipeline location, tells the host
 * mixins to make them descriptor types 7 / 3 instead.
 */
public final class RasterStorage {
    public enum Kind { BUFFER, IMAGE }
    private static final Map<String, Map<String, Kind>> PIPELINES = new ConcurrentHashMap<>();
    static final ThreadLocal<String> LAST_NAME = new ThreadLocal<>();
    private RasterStorage() {}

    /** Same location must always declare the same storage names. */
    public static void declare(String location, Map<String, Kind> names) {
        if (names.isEmpty()) return;
        var copy = Map.copyOf(names);
        var previous = PIPELINES.putIfAbsent(location, copy);
        if (previous != null && !previous.equals(copy)) throw new IllegalArgumentException("Pipeline " + location + " already declares different storage bindings");
    }
    public static Kind kind(String location, String name) {
        var names = PIPELINES.get(location);
        return names == null || name == null ? null : names.get(name);
    }
    public static boolean any(String location) { return PIPELINES.containsKey(location); }

    // Mixin plumbing: PipelineBuilder looks a uniform up by name right before asking for its type.
    public static void lookedUp(Object key) { if (key instanceof String name) LAST_NAME.set(name); }
    public static int resourceType(String location, int original) {
        var kind = kind(location, LAST_NAME.get());
        if (kind == Kind.BUFFER && original == 1) return 2;  // spvc STORAGE_BUFFER
        if (kind == Kind.IMAGE && original == 7) return 6;   // spvc STORAGE_IMAGE
        return original;
    }
    /** Per-binding VkDescriptorType overrides (0 = keep host type). */
    public static int[] descriptorTypes(String location, List<String> uniforms) {
        var result = new int[uniforms.size()];
        for (int i = 0; i < result.length; i++) {
            var kind = kind(location, uniforms.get(i));
            result[i] = kind == Kind.BUFFER ? 7 : kind == Kind.IMAGE ? 3 : 0;
        }
        return result;
    }
    public interface Pipeline { int[] greenmarine$storageTypes(); void greenmarine$storageTypes(int[] types); }
}
