package com.koper.greenmarine.client.world;

import com.koper.greenmarine.api.pack.PackProgram;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Optional adapter for Sodium 0.9.3-alpha.1's 20-byte terrain ABI. No Sodium classes are linked. */
public final class SodiumCompatibility {
    private static final Identifier SHADER = Identifier.parse("sodium:blocks/block_layer_opaque");
    private static final Map<String, RenderPipeline> HOSTS = new ConcurrentHashMap<>();
    private static final VertexFormat PACKED = VertexFormat.builder(0)
        .addAttribute("a_Position", GpuFormat.RG32_UINT).addAttribute("a_Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("a_TexCoord", GpuFormat.RG16_UINT).addAttribute("a_LightAndData", GpuFormat.RGBA8_UINT).build();
    private SodiumCompatibility() {}

    public static void observe(RenderPipeline pipeline) {
        String name = pipeline.getLocation().toString();
        // OitPipelineSet assigns minecraft: locations even for Sodium's own shaders.
        if (!SHADER.equals(pipeline.getShaders().get(ShaderType.VERTEX))
            || !SHADER.equals(pipeline.getShaders().get(ShaderType.FRAGMENT))
            || pipeline.pushConstantSize() != 20 || !PACKED.equals(pipeline.getVertexFormatBinding(0))
            || pipeline.getVertexFormatBinding(0).getStepRate() != 0
            || pipeline.getVertexFormatBindings().stream().skip(1).anyMatch(Objects::nonNull)
            || !pipeline.getShaderDefines().flags().contains("USE_VERTEX_COMPRESSION")) {
            HOSTS.remove(name); return;
        }
        var uniforms = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
        if (!uniforms.stream().anyMatch(u -> u.name().equals("u_Globals") && u.type() == UniformType.UNIFORM_BUFFER)
            || !uniforms.stream().anyMatch(u -> u.name().equals("u_BlockTex") && u.type() == UniformType.COMBINED_IMAGE_SAMPLER)
            || !uniforms.stream().anyMatch(u -> u.name().equals("u_SectionTimeInfo") && u.type() == UniformType.TEXEL_BUFFER && u.gpuFormat() == GpuFormat.R32_SINT)) {
            HOSTS.remove(name); return;
        }
        HOSTS.put(name, pipeline);
    }

    static RenderPipeline find(String name) { return HOSTS.get(name); }
    static boolean supports(PackProgram program) {
        return program.target() == PackProgram.Target.TERRAIN && (
            "greenmarine:core/solstice_terrain".equals(program.vertex())
                && "greenmarine:core/solstice_terrain".equals(program.fragment())
                && (program.surface() == null || "greenmarine:core/solstice_surface".equals(program.surface()))
            || "greenmarine:core/seabreeze_terrain".equals(program.vertex())
                && "greenmarine:core/seabreeze_terrain".equals(program.fragment()) && program.surface() == null);
    }

    static RenderPipeline prepare(RenderPipeline original) {
        return new HostWithFog(original);
    }

    private static final class HostWithFog extends RenderPipeline {
        HostWithFog(RenderPipeline host) {
            super(host.getLocation(), host.getShaders(), host.getShaderDefines(), layouts(host),
                host.getColorTargetStates().toArray(ColorTargetState[]::new), host.getDepthStencilState(),
                host.getPolygonMode(), host.isCull(), host.getVertexFormatBindings().toArray(VertexFormat[]::new),
                host.getPrimitiveTopology(), host.pushConstantSize(), host.getSortKey());
        }
        private static List<BindGroupLayout> layouts(RenderPipeline host) {
            var result = new ArrayList<>(host.getBindGroupLayouts());
            if (BindGroupLayout.flattenUniforms(result).stream().noneMatch(u -> u.name().equals("Fog")))
                result.add(BindGroupLayout.builder().withUniform("Fog", UniformType.UNIFORM_BUFFER).build());
            return result;
        }
    }
}
