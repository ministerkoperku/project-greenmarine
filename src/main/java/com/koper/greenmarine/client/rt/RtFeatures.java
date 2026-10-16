package com.koper.greenmarine.client.rt;

import com.mojang.renderpearl.backend.vulkan.init.*;
import java.util.*;
import org.lwjgl.vulkan.*;

/** Optional device contract; physical support is deliberately distinct from enabled support. */
public final class RtFeatures {
    public static final Set<String> EXTENSIONS = Set.of("VK_KHR_acceleration_structure", "VK_KHR_ray_query",
        "VK_KHR_deferred_host_operations", "VK_KHR_buffer_device_address", "VK_KHR_spirv_1_4", "VK_KHR_shader_float_controls");
    public static final FeatureSet OPTIONAL = new FeatureSet("GreenMarine hardware ray queries", EXTENSIONS,
        Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceVulkan12Features.class), "bufferDeviceAddress"),
            new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceAccelerationStructureFeaturesKHR.class), "accelerationStructure"),
            new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceRayQueryFeaturesKHR.class), "rayQuery")));
    private RtFeatures() {}
    public static boolean enabled(Set<String> extensions, Set<String> features) {
        return extensions.containsAll(EXTENSIONS) && features.containsAll(Set.of("bufferDeviceAddress", "accelerationStructure", "rayQuery"));
    }
    public static boolean enabled(FeatureSet set) {
        return enabled(set.extensions(), set.features().stream().map(VulkanFeature::name).collect(java.util.stream.Collectors.toSet()));
    }
}
