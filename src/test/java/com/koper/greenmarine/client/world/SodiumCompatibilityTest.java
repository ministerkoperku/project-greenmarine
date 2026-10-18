package com.koper.greenmarine.client.world;

import com.koper.greenmarine.api.pack.PackProgram;
import com.koper.greenmarine.api.render.WorldPipeline;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.oit.OitStage;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SodiumCompatibilityTest {
    static RenderPipeline host() {
        return hostBuilder(true).build();
    }
    static RenderPipeline.Builder hostBuilder(boolean lightmap) {
        var builder = RenderPipeline.builder().withLocation(Identifier.parse("sodium:pipeline/solid_terrain"))
            .withVertexShader(Identifier.parse("sodium:blocks/block_layer_opaque"))
            .withFragmentShader(Identifier.parse("sodium:blocks/block_layer_opaque"))
            .withShaderDefine("USE_VERTEX_COMPRESSION").withShaderDefine("USE_FOG")
            .withBindGroupLayout(BindGroupLayout.builder()
                .withUniform("u_BlockTex", UniformType.COMBINED_IMAGE_SAMPLER)
                .withUniform("u_Globals", UniformType.UNIFORM_BUFFER)
                .withUniform("u_SectionTimeInfo", UniformType.TEXEL_BUFFER, GpuFormat.R32_SINT).build())
            .withPushConstantSize(20).withVertexBinding(0, VertexFormat.builder(0)
                .addAttribute("a_Position", GpuFormat.RG32_UINT).addAttribute("a_Color", GpuFormat.RGBA8_UNORM)
                .addAttribute("a_TexCoord", GpuFormat.RG16_UINT).addAttribute("a_LightAndData", GpuFormat.RGBA8_UINT).build())
            .withPrimitiveTopology(PrimitiveTopology.QUADS).withColorTargetState(ColorTargetState.DEFAULT)
            .withDepthStencilState(DepthStencilState.DEFAULT);
        if (lightmap) builder.withBindGroupLayout(BindGroupLayout.builder().withUniform("u_LightTex", UniformType.COMBINED_IMAGE_SAMPLER).build());
        return builder;
    }

    @Test void observesOnlyKnownSodiumTerrainAndPreservesHostSlotsForCapture() {
        var host = host();
        SodiumCompatibility.observe(host);
        assertSame(host, SodiumCompatibility.find("sodium:pipeline/solid_terrain"));
        assertNull(SodiumCompatibility.find("other:unknown"));
        var prepared = SodiumCompatibility.prepare(host);
        var original = BindGroupLayout.flattenUniforms(host.getBindGroupLayouts());
        var preparedSlots = BindGroupLayout.flattenUniforms(prepared.getBindGroupLayouts());
        assertEquals(original, preparedSlots.subList(0, original.size()));
        assertEquals("Fog", preparedSlots.getLast().name());
        var program = new PackProgram(PackProgram.Target.TERRAIN, "greenmarine:core/solstice_terrain", "greenmarine:core/solstice_terrain", true, "greenmarine:core/solstice_surface");
        assertTrue(SodiumCompatibility.supports(program));
        var capture = WorldPipeline.capture(prepared, program, Identifier.parse("test:sodium_capture"));
        assertEquals(List.of(GpuFormat.RGBA32_FLOAT, GpuFormat.RGBA16_FLOAT), capture.getColorTargetStates().stream().map(ColorTargetState::format).toList());
        assertEquals(host.getVertexFormatBindings(), capture.getVertexFormatBindings());
        assertEquals(host.getShaderDefines(), capture.getShaderDefines());
        assertEquals(host.getPrimitiveTopology(), capture.getPrimitiveTopology());
        assertEquals(20, capture.pushConstantSize());
        var raster = WorldPipeline.decorate(prepared, program, Identifier.parse("test:sodium_raster"));
        assertEquals(host.getColorTargetStates(), raster.getColorTargetStates());
        assertEquals(host.getDepthStencilState(), raster.getDepthStencilState());
    }

    @Test void doesNotClaimArbitraryCustomPacks() {
        assertFalse(SodiumCompatibility.supports(new PackProgram(PackProgram.Target.TERRAIN,"other:custom","other:custom",false)));
        assertFalse(SodiumCompatibility.supports(new PackProgram(PackProgram.Target.SKY,null,"greenmarine:core/solstice_sky",false)));
    }

    @Test void recognizesActualOitPipelinesDespiteTheirMinecraftNamespace() {
        var oit = OitPipelineSet.builder("sodium_terrain", hostBuilder(false))
            .withAccumulateModifier(builder -> builder.withBindGroupLayout(BindGroupLayout.builder()
                .withUniform("u_LightTex", UniformType.COMBINED_IMAGE_SAMPLER).build())).build();
        for (var stage : OitStage.values()) {
            var original = oit.getPipeline(stage);
            SodiumCompatibility.observe(original);
            assertSame(original, SodiumCompatibility.find(original.getLocation().toString()));
            var compatible = SodiumCompatibility.prepare(original);
            assertEquals(original.getColorTargetStates(), compatible.getColorTargetStates());
            assertEquals(original.getShaderDefines(), compatible.getShaderDefines());
            var uniforms = BindGroupLayout.flattenUniforms(original.getBindGroupLayouts());
            assertEquals(uniforms, BindGroupLayout.flattenUniforms(compatible.getBindGroupLayouts()).subList(0,uniforms.size()));
        }
    }

    @Test void rejectsChangedAbiAndDoesNotRetainThePreviousDefinition() {
        var original = host();
        SodiumCompatibility.observe(original);
        assertSame(original, SodiumCompatibility.find(original.getLocation().toString()));
        SodiumCompatibility.observe(hostBuilder(true).withPushConstantSize(24).build());
        assertNull(SodiumCompatibility.find(original.getLocation().toString()));
    }
}
