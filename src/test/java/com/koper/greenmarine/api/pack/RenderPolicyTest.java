package com.koper.greenmarine.api.pack;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderPolicyTest {
    private final RenderCapabilities portable = new RenderCapabilities("OpenGL", false, false, true, false);

    @Test void selectsRayFallbackWithoutPretendingHardwareIsEnabled() {
        assertEquals(RayMode.OFF, RenderPolicy.rays(RayMode.OFF, Quality.ULTRA, portable).effective());
        assertEquals(RayMode.OFF, RenderPolicy.rays(RayMode.AUTO, Quality.LOW, portable).effective());
        assertEquals(RayMode.VOXEL, RenderPolicy.rays(RayMode.AUTO, Quality.HIGH, portable).effective());
        var result = RenderPolicy.rays(RayMode.HARDWARE, Quality.HIGH, portable);
        assertEquals(RayMode.VOXEL, result.effective());
        assertFalse(result.reason().isBlank());
        var capable = new RenderCapabilities("Vulkan", true, true, true, true);
        assertEquals(RayMode.HARDWARE, RenderPolicy.rays(RayMode.AUTO, Quality.HIGH, capable).effective());
    }

    @Test void resolvesPassQualityAndCapabilityFallbacks() {
        var pass = new PackPass("advanced", PackPass.Stage.SCENE, "demo:core/rt", "demo:core/flat",
            PackPass.Requirement.HARDWARE_RT, Quality.HIGH, 0.5f, null);
        assertTrue(RenderPolicy.pass(pass, Quality.LOW, portable, RayMode.OFF).isEmpty());
        var fallback = RenderPolicy.pass(pass, Quality.HIGH, portable, RayMode.VOXEL).orElseThrow();
        assertEquals("demo:core/flat", fallback.fragment());
        assertTrue(fallback.fallback());
        var unsupported = new PackPass("rt", PackPass.Stage.SCENE, "demo:core/rt", null,
            PackPass.Requirement.HARDWARE_RT, Quality.LOW, 1, null);
        assertTrue(RenderPolicy.pass(unsupported, Quality.ULTRA, portable, RayMode.OFF).isEmpty());
    }
}
