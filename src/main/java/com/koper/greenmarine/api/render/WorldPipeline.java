package com.koper.greenmarine.api.render;

import com.koper.greenmarine.api.pack.PackProgram;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.resources.Identifier;
import java.util.*;

/** Host-state-preserving world shader replacement, shared by both backends. */
public final class WorldPipeline extends RenderPipeline {
    private WorldPipeline(RenderPipeline host, Map<ShaderType,Identifier> shaders, List<BindGroupLayout> layouts, Identifier location,ColorTargetState[] colors,DepthStencilState depth) {
        super(location,shaders,host.getShaderDefines(),layouts,colors,
            depth,host.getPolygonMode(),host.isCull(),
            host.getVertexFormatBindings().toArray(com.mojang.renderpearl.api.vertex.VertexFormat[]::new),
            host.getPrimitiveTopology(),host.pushConstantSize(),host.getSortKey());
    }
    public static RenderPipeline decorate(RenderPipeline original,PackProgram program,Identifier location) {
        var shaders=new EnumMap<ShaderType,Identifier>(ShaderType.class);shaders.putAll(original.getShaders());
        shaders.put(ShaderType.FRAGMENT,Identifier.parse(program.fragment()));
        if(program.vertex()!=null) shaders.put(ShaderType.VERTEX,Identifier.parse(program.vertex()));
        var layouts=new ArrayList<>(original.getBindGroupLayouts());
        for(var layout:layouts) for(var uniform:layout.uniforms())
            if(List.of("MarineFrame","MarineSurfaceFrame","MarineEnvironment","MarineMaterialFrame","MarineMaterialExtension","RaySurfaceFrame","RayFrame","VoxelFrame","MarineOpaqueColor","MarineOpaqueDepth","MarineSurfaceNormalDepth","MarineSurfaceAlbedo","RaySurfaceLighting","RayReflection","RaySurfaceExtra","RayLighting","RayNormalDepth","RayAlbedo","VoxelMaterials","MarineHeightField","MarinePbrSpecular","MarinePbrNormal").contains(uniform.name()))
                throw new IllegalArgumentException("Host uniform conflicts with world binding: "+uniform.name());
        var uniforms=new ArrayList<BindGroupLayout.UniformDescription>(layouts.isEmpty()?List.of():layouts.getLast().uniforms());
        for(String name:List.of("MarineFrame","MarineSurfaceFrame","MarineEnvironment","RaySurfaceFrame","RayFrame","VoxelFrame","MarineMaterialFrame")) add(uniforms,name,UniformType.UNIFORM_BUFFER);
        for(String name:List.of("MarineOpaqueColor","MarineOpaqueDepth","MarineSurfaceNormalDepth","MarineSurfaceAlbedo","RaySurfaceLighting","RayReflection","RaySurfaceExtra","RayLighting","RayNormalDepth","RayAlbedo","VoxelMaterials","MarineHeightField","MarinePbrSpecular","MarinePbrNormal")) add(uniforms,name,UniformType.COMBINED_IMAGE_SAMPLER);
        add(uniforms,"MarineMaterialExtension",UniformType.UNIFORM_BUFFER);
        if(layouts.isEmpty()) layouts.add(new BindGroupLayout(uniforms));else layouts.set(layouts.size()-1,new BindGroupLayout(uniforms));
        return new WorldPipeline(original,shaders,layouts,location,original.getColorTargetStates().toArray(ColorTargetState[]::new),original.getDepthStencilState());
    }
    public static RenderPipeline capture(RenderPipeline original,PackProgram program,Identifier location) {
        if(program.surface()==null || program.target()!=PackProgram.Target.TERRAIN) throw new IllegalArgumentException("Missing terrain surface program");
        var decorated=decorate(original,program,location);
        var shaders=new EnumMap<ShaderType,Identifier>(ShaderType.class);shaders.putAll(decorated.getShaders());
        shaders.put(ShaderType.FRAGMENT,Identifier.parse(program.surface()));
        var colors=new ColorTargetState[]{new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL),
            new ColorTargetState(Optional.empty(),GpuFormat.RGBA16_FLOAT,ColorTargetState.WRITE_ALL)};
        return new WorldPipeline(original,shaders,decorated.getBindGroupLayouts(),location,colors,new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,true));
    }
    private static void add(List<BindGroupLayout.UniformDescription> uniforms,String name,UniformType type) {
        if(uniforms.stream().anyMatch(u->u.name().equals(name))) throw new IllegalArgumentException("Host uniform conflicts with world binding: "+name);
        uniforms.add(new BindGroupLayout.UniformDescription(name,type));
    }
}
