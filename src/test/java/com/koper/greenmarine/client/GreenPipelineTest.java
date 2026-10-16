package com.koper.greenmarine.client;

import com.mojang.renderpearl.api.GpuFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GreenPipelineTest {
    @Test void namespacedShadersCanBuildAPipelineBeforeGpuSubmission() {
        assertDoesNotThrow(() -> GreenPostEffect.pipelineFor(GpuFormat.RGBA8_UNORM));
    }
}
