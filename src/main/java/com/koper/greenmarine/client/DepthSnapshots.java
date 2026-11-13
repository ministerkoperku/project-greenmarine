package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.PackDepthSource;
import com.koper.greenmarine.api.render.ShaderProgram;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import java.util.*;

/** Owned forward-depth images. Capture only outside the borrowed host depth's render pass. */
final class DepthSnapshots implements AutoCloseable {
    private static final class Owned implements AutoCloseable {
        GpuTexture texture;GpuTextureView view;
        @Override public void close() {if(view!=null)view.close();if(texture!=null)texture.close();}
    }
    private final Map<PackDepthSource,Owned> images=new EnumMap<>(PackDepthSource.class);
    private final Map<Integer,CompiledRenderPipeline> pipelines=new HashMap<>();
    private int width,height;
    private boolean opaque;

    void prepare(Set<PackDepthSource> required,RenderTarget main,CommandEncoder encoder) {
        if(required.isEmpty()) {close();return;}
        if(!images.keySet().equals(required) || width!=main.width || height!=main.height) {
            close();width=main.width;height=main.height;
            try {
                var device=RenderSystem.getDevice();
                int limit=device.getDeviceInfo().limits().maxTextureSizeForFormat(GpuFormat.R32_FLOAT);
                if(width>limit || height>limit)throw new IllegalArgumentException("Depth snapshot exceeds host format limit");
                for(var source:required) {
                    var image=new Owned();images.put(source,image);
                    image.texture=device.createTexture("GreenMarine "+source.source(),GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_COPY_DST,
                        GpuFormat.R32_FLOAT,width,height,1,1);
                    image.view=device.createTextureView(image.texture);
                }
            }catch(RuntimeException|LinkageError failure){close();throw failure;}
        }
        opaque=false;
        // Never expose previous-frame or uninitialized depth to early/world programs.
        for(var image:images.values())encoder.clearColorTexture(image.texture,new Vector4f(1));
    }
    boolean required() {return !images.isEmpty();}
    GpuTextureView read(PackDepthSource source) {var image=images.get(source);return image==null?null:image.view;}
    boolean owns(GpuTextureView view) {return images.values().stream().anyMatch(image->image.view==view);}
    void afterOpaque(RenderTarget main,CommandEncoder encoder) {
        if(opaque || images.isEmpty())return;
        capture(main,encoder,new ArrayList<>(images.values()));opaque=true;
    }
    void afterWorld(RenderTarget main,CommandEncoder encoder) {
        var world=images.get(PackDepthSource.WORLD);
        if(world!=null)capture(main,encoder,List.of(world));
    }
    private void capture(RenderTarget main,CommandEncoder encoder,List<Owned> outputs) {
        var depth=main.getDepthTextureView();
        if(depth==null || depth.getWidth(0)!=width || depth.getHeight(0)!=height)
            throw new IllegalStateException("Host depth dimensions changed during the pack frame");
        var pipeline=pipelines.computeIfAbsent(outputs.size(),this::compile);
        var descriptor=RenderPassDescriptor.builder(()->"GreenMarine forward depth snapshot");
        for(var output:outputs)descriptor.withColorAttachment(output.view);
        try(var pass=encoder.createRenderPass(descriptor.build())) {
            pass.setPipeline(pipeline);
            pass.setUniform("HostDepth",depth,RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.draw(3,1,0,0);
        }
    }
    private CompiledRenderPipeline compile(int count) {
        var vertex=Identifier.parse("greenmarine:depth_snapshot_vertex");
        var fragment=Identifier.parse("greenmarine:depth_snapshot_"+count);
        var builder=RenderPipeline.builder().withLocation(fragment).withVertexShader(vertex).withFragmentShader(fragment)
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("HostDepth",UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false);
        var body=new StringBuilder("#version 450\nuniform sampler2D HostDepth;\n");
        for(int i=0;i<count;i++) {
            builder.withColorTargetState(i,new ColorTargetState(Optional.empty(),GpuFormat.R32_FLOAT,ColorTargetState.WRITE_ALL));
            body.append("layout(location=").append(i).append(") out float D").append(i).append(";\n");
        }
        body.append("void main(){float d=1.0-texelFetch(HostDepth,ivec2(gl_FragCoord.xy),0).r;");
        for(int i=0;i<count;i++)body.append("D").append(i).append("=d;");
        body.append("}\n");
        var pipeline=builder.build();
        var program=new ShaderProgram(pipeline,Map.of(vertex,"""
            #version 450
            void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2.-1.,0.,1.);}
            """,fragment,body.toString()),Map.of(),Map.of());
        try(var sources=new ProgramSources(program,ComputeBackend.vulkan(RenderSystem.getDevice())!=null)) {
            var compiled=RenderSystem.getDevice().compilePipeline(pipeline,sources,Runnable::run).join().finishCompile();
            if(compiled==null)throw new IllegalArgumentException("Unable to compile forward depth snapshot");
            return compiled;
        }
    }
    @Override public void close() {
        images.values().forEach(Owned::close);images.clear();pipelines.values().forEach(CompiledRenderPipeline::close);pipelines.clear();
        width=height=0;opaque=false;
    }
}
