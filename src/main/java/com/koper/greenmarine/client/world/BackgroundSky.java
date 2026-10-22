package com.koper.greenmarine.client.world;

import com.koper.greenmarine.api.pack.*;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.resources.Identifier;
import java.util.Optional;

/** One owned fullscreen pipeline; borrowed host attachments are preserved. */
final class BackgroundSky implements AutoCloseable {
    private CompiledRenderPipeline compiled;
    private PackCatalog.LoadedPack owner;
    private PackProgram definition;
    private GpuFormat format;

    static RenderPipeline pipeline(PackProgram program,GpuFormat format) {
        if(program.target()!=PackProgram.Target.BACKGROUND)throw new IllegalArgumentException("Expected background program");
        return RenderPipeline.builder().withLocation(Identifier.parse("greenmarine:world_background"))
            .withVertexShader(Identifier.parse(program.vertex())).withFragmentShader(Identifier.parse(program.fragment()))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("MarineFrame",UniformType.UNIFORM_BUFFER)
                .withUniform("MarineSurfaceFrame",UniformType.UNIFORM_BUFFER)
                .withUniform("MarineEnvironment",UniformType.UNIFORM_BUFFER).build())
            .withColorTargetState(new ColorTargetState(Optional.empty(),format,ColorTargetState.WRITE_ALL))
            .withDepthStencilState(new DepthStencilState(CompareOp.EQUAL,false))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    }

    void render(RenderTarget target,CommandEncoder encoder,PackCatalog.LoadedPack pack,PackProgram program,
                GpuBuffer frame,GpuBuffer surface,GpuBuffer environment) {
        var targetFormat=target.getColorTexture().getFormat();
        if(compiled==null || owner!=pack || !program.equals(definition) || targetFormat!=format) {
            close();
            try(var sources=new WorldShaders(pack)) {
                compiled=RenderSystem.getDevice().compilePipeline(pipeline(program,targetFormat),sources,Runnable::run).join().finishCompile();
                if(compiled==null)throw new IllegalArgumentException("Unable to compile background program: "+program.fragment());
                owner=pack;definition=program;format=targetFormat;
            }
        }
        try(var pass=encoder.createRenderPass(()->"GreenMarine sky background",target.getColorTextureView(),Optional.empty(),
                target.getDepthTextureView(),java.util.OptionalDouble.empty())) {
            pass.setPipeline(compiled);
            pass.setUniform("MarineFrame",frame);pass.setUniform("MarineSurfaceFrame",surface);
            pass.setUniform("MarineEnvironment",environment);
            pass.draw(3,1,0,0);
        }
    }
    @Override public void close() {
        if(compiled!=null)compiled.close();compiled=null;owner=null;definition=null;format=null;
    }
}
