package com.koper.greenmarine.api.render;

import com.koper.greenmarine.api.pack.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldMrtPipelineTest {
    @Test void capturedTerrainDeclaresSidecarsWithoutReplacingHostDrawState() {
        var host=RenderPipelines.SOLID_TERRAIN;
        var program=new PackProgram(PackProgram.Target.TERRAIN,"test:terrain","test:terrain",false);
        var pipeline=WorldPipeline.decorate(host,program,Identifier.parse("test:captured_terrain"),true);
        var names=pipeline.getBindGroupLayouts().stream().flatMap(layout->layout.uniforms().stream()).map(BindGroupLayout.UniformDescription::name).toList();
        assertEquals(1,names.stream().filter("MarineTerrainVertices"::equals).count());
        assertEquals(1,names.stream().filter("MarineTerrainDraws"::equals).count());
        assertTrue(names.contains("ChunkSection"));assertTrue(names.contains("TerrainUniform"));
        assertSame(host.getVertexFormatBinding(0),pipeline.getVertexFormatBinding(0));
        assertEquals(host.getPrimitiveTopology(),pipeline.getPrimitiveTopology());
        assertEquals(host.getDepthStencilState(),pipeline.getDepthStencilState());
        assertEquals(host.getColorTargetStates(),pipeline.getColorTargetStates());
    }
    @Test void unmodifiedHostDrawCannotOverwriteExtraAttachmentsWithUndefinedShaderOutputs() {
        var host=RenderPipelines.OPAQUE_PARTICLE;
        var colors=List.of(GpuFormat.RGBA8_UNORM,GpuFormat.RGBA16_FLOAT,GpuFormat.RGBA32_UINT);
        var pipeline=WorldPipeline.attachments(host,Identifier.parse("test:masked"),colors,Map.of(0,0));
        assertEquals(3,pipeline.getColorTargetStates().size());
        assertEquals(ColorTargetState.WRITE_ALL,pipeline.getColorTargetStates().get(0).writeMask());
        assertEquals(ColorTargetState.WRITE_NONE,pipeline.getColorTargetStates().get(1).writeMask());
        assertEquals(ColorTargetState.WRITE_NONE,pipeline.getColorTargetStates().get(2).writeMask());
        assertEquals(host.getDepthStencilState(),pipeline.getDepthStencilState());
    }
    @Test void mixedFloatIntegerOutputsCanRouteToDifferentGlobalAttachmentSlots() {
        var host=RenderPipelines.OPAQUE_PARTICLE;
        var pipeline=WorldPipeline.attachments(host,Identifier.parse("test:mapped"),
            List.of(GpuFormat.RGBA8_UNORM,GpuFormat.RGBA16_FLOAT,GpuFormat.RGBA32_UINT),Map.of(0,2,1,1));
        assertEquals(ColorTargetState.WRITE_NONE,pipeline.getColorTargetStates().get(0).writeMask());
        assertEquals(ColorTargetState.WRITE_ALL,pipeline.getColorTargetStates().get(1).writeMask());
        assertEquals(ColorTargetState.WRITE_ALL,pipeline.getColorTargetStates().get(2).writeMask());
        assertTrue(pipeline.getColorTargetStates().get(2).blendFunction().isEmpty());
    }
}
