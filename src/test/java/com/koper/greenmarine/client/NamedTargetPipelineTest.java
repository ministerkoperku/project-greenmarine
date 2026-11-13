package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.PackResources;
import com.mojang.renderpearl.api.GpuFormat;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class NamedTargetPipelineTest {
    @Test void mixedAttachmentFormatsAreIndependentPipelineState() throws Exception {
        var method=assertDoesNotThrow(()->PackRenderer.class.getDeclaredMethod("pipelineFor",String.class,List.class,boolean.class,boolean.class,List.class,PackResources.class));
        method.setAccessible(true);
        var pipeline=(com.mojang.renderpearl.api.pipeline.RenderPipeline)method.invoke(null,"test:mrt",List.of(GpuFormat.RGBA16_FLOAT,GpuFormat.R32_UINT),false,false,List.of("Previous"),PackResources.NONE);
        assertEquals(2,pipeline.getColorTargetStates().size());
        assertEquals(GpuFormat.RGBA16_FLOAT,pipeline.getColorTargetStates().get(0).format());
        assertEquals(GpuFormat.R32_UINT,pipeline.getColorTargetStates().get(1).format());
        var single=PackRenderer.pipelineFor("test:mrt",GpuFormat.RGBA16_FLOAT,false,List.of("Previous"));
        assertNotEquals(single.getLocation(),pipeline.getLocation());
    }
}
