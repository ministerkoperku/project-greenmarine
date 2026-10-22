package com.koper.greenmarine.api.render;

import com.koper.greenmarine.api.pack.PackProgram;
import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.RenderPipelines;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldPipelineTest {
    @Test void materialExtensionIsBoundAndRejectsHostNameConflicts() {
        var program=new PackProgram(PackProgram.Target.TERRAIN,"test:world","test:world",true);
        var source=RenderPipelines.OPAQUE_PARTICLE;
        var decorated=WorldPipeline.decorate(source,program,Identifier.parse("test:extension"));
        assertTrue(BindGroupLayout.flattenUniforms(decorated.getBindGroupLayouts()).stream()
            .anyMatch(u->u.name().equals("MarineMaterialExtension") && u.type()==UniformType.UNIFORM_BUFFER));
        var conflicting=RenderPipeline.builder().withLocation(Identifier.parse("test:conflict"))
            .withVertexShader(Identifier.parse("test:world")).withFragmentShader(Identifier.parse("test:world"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("MarineMaterialExtension",UniformType.UNIFORM_BUFFER).build()).build();
        assertThrows(IllegalArgumentException.class,()->WorldPipeline.decorate(conflicting,program,Identifier.parse("test:bad")));
    }

    @Test void surfaceCaptureUsesNearestDepthAndIndependentUnblendedAttachments() {
        var program=new PackProgram(PackProgram.Target.TERRAIN,"test:world","test:world",true,"test:surface");
        for(var source:RenderPipelines.requiredPipelines()) {
            if(com.koper.greenmarine.client.world.WorldPrograms.target(source)!=PackProgram.Target.TERRAIN)continue;
            var capture=WorldPipeline.capture(source,program,Identifier.parse("test:capture"));
            assertEquals(Identifier.parse("test:surface"),capture.getShaders().get(ShaderType.FRAGMENT));
            assertEquals(source.getVertexFormatBindings(),capture.getVertexFormatBindings());
            assertEquals(2,capture.getColorTargetStates().size());
            assertEquals(com.mojang.renderpearl.api.GpuFormat.RGBA32_FLOAT,capture.getColorTargetStates().getFirst().format());
            for(var color:capture.getColorTargetStates())assertTrue(color.blendFunction().isEmpty());
            assertEquals(CompareOp.GREATER_THAN_OR_EQUAL,capture.getDepthStencilState().depthTest());
            assertTrue(capture.getDepthStencilState().writeDepth());
        }
    }
    @Test void weatherAndParticlesWithSharedShaderKeepSeparateTargets() {
        for(var source:RenderPipelines.requiredPipelines()) {
            if(source.getLocation().getPath().contains("weather"))
                assertEquals(PackProgram.Target.WEATHER,com.koper.greenmarine.client.world.WorldPrograms.target(source));
        }
        assertEquals(PackProgram.Target.PARTICLES,com.koper.greenmarine.client.world.WorldPrograms.target(RenderPipelines.OPAQUE_PARTICLE));
    }
    @Test void keepsHostStateAndUniformSlotsAcrossTerrainOitAndSparseVertexLayouts() {
        var program=new PackProgram(PackProgram.Target.TERRAIN,"test:world","test:world",true);
        for(var source:RenderPipelines.requiredPipelines()) {
            if(!source.getShaders().get(ShaderType.FRAGMENT).toString().contains("terrain")) continue;
            var changed=WorldPipeline.decorate(source,program,Identifier.parse("test:replacement"));
            assertEquals(Identifier.parse("test:world"),changed.getShaders().get(ShaderType.VERTEX));
            assertEquals(source.getShaderDefines(),changed.getShaderDefines());
            assertEquals(source.getColorTargetStates(),changed.getColorTargetStates());
            assertEquals(source.getDepthStencilState(),changed.getDepthStencilState());
            assertEquals(source.getPolygonMode(),changed.getPolygonMode());
            assertEquals(source.getVertexFormatBindings(),changed.getVertexFormatBindings());
            assertEquals(source.pushConstantSize(),changed.pushConstantSize());
            assertEquals(source.isCull(),changed.isCull());
            var old=source.getBindGroupLayouts();var newer=changed.getBindGroupLayouts();
            assertEquals(old.size(),newer.size());
            var originalUniforms=BindGroupLayout.flattenUniforms(old);
            assertEquals(originalUniforms,BindGroupLayout.flattenUniforms(newer).subList(0,originalUniforms.size()));
            assertTrue(newer.getLast().uniforms().stream().anyMatch(u->u.name().equals("MarineSurfaceFrame")));
        }
    }
}
