package com.koper.greenmarine.client.rt;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class RtFeaturesTest {
    @Test void requiresAllEnabledHostFeaturesRatherThanGpuMarketing() {
        assertFalse(RtFeatures.enabled(Set.of("VK_KHR_ray_query"), Set.of("rayQuery")));
        assertTrue(RtFeatures.enabled(RtFeatures.EXTENSIONS, Set.of("accelerationStructure", "rayQuery", "bufferDeviceAddress")));
        assertFalse(RtFeatures.enabled(RtFeatures.EXTENSIONS, Set.of("accelerationStructure", "rayQuery")));
    }
}
