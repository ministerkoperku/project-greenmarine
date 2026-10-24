package com.koper.greenmarine.client;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.*;
import java.util.*;

/**
 * Optional core features for storage in raster stages. Reads need nothing; writes/atomics from
 * fragment or vertex shaders and format-less storage images need these to be enabled, which is
 * checked against what the host actually enabled, never just physical support.
 */
public final class StorageFeatures {
    public static final FeatureSet FRAGMENT = set("GreenMarine fragment storage writes", "fragmentStoresAndAtomics");
    public static final FeatureSet VERTEX = set("GreenMarine vertex storage writes", "vertexPipelineStoresAndAtomics");
    public static final FeatureSet FORMATLESS = new FeatureSet("GreenMarine format-less storage images", Set.of(),
        Set.of(new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderStorageImageReadWithoutFormat"),
            new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, "shaderStorageImageWriteWithoutFormat")));
    public static final List<FeatureSet> OPTIONAL = List.of(FRAGMENT, VERTEX, FORMATLESS);
    private StorageFeatures() {}
    private static FeatureSet set(String name, String feature) {
        return new FeatureSet(name, Set.of(), Set.of(new VulkanFeature(VulkanFeatureSets.VK10_FEATURES_STRUCT, feature)));
    }
    public static boolean enabled(FeatureSet enabled, FeatureSet wanted) {
        var names = new HashSet<String>(); enabled.features().forEach(f -> names.add(f.name()));
        return wanted.features().stream().allMatch(f -> names.contains(f.name()));
    }
}
