package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.PackResources;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PackUniformPipelineTest {
    @Test void importedBlockBindingsAreDistinctFromStorageAndSeparatePipelineVariants() throws Exception {
        var method=assertDoesNotThrow(()->PackRenderer.class.getDeclaredMethod("pipelineFor",String.class,List.class,
            boolean.class,boolean.class,List.class,PackResources.class,List.class));
        method.setAccessible(true);
        var first=(RenderPipeline)method.invoke(null,"test:uniforms",List.of(GpuFormat.RGBA16_FLOAT),false,false,
            List.of(),PackResources.NONE,List.of("IrisUniforms"));
        var second=(RenderPipeline)method.invoke(null,"test:uniforms",List.of(GpuFormat.RGBA16_FLOAT),false,false,
            List.of(),PackResources.NONE,List.of("OtherUniforms"));
        assertNotEquals(first.getLocation(),second.getLocation());
        var uniform=first.getBindGroupLayouts().stream().flatMap(layout->layout.uniforms().stream())
            .filter(value->value.name().equals("IrisUniforms")).findFirst().orElseThrow();
        assertEquals(UniformType.UNIFORM_BUFFER,uniform.type());
        assertNull(RasterStorage.kind(first.getLocation().toString(),"IrisUniforms"));
    }
}
