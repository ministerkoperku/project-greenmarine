package com.koper.greenmarine.client.rt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.*;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Owned sampled atlas: imported/player textures lack COPY_SRC and are never changed or closed. */
final class ActorAtlas implements AutoCloseable {
    record Capture(ByteBuffer data,GpuTextureView skin) {}
    private record Input(Identifier id,GpuTextureView view) {}
    private GpuTexture texture;
    private GpuTextureView view;
    private CompiledRenderPipeline compiled;
    private List<ActorAtlasPlan.Source> sources=List.of();
    private ActorAtlasPlan.Plan plan;
    private List<ByteBuffer> frames=List.of();

    Capture prepare(Minecraft client,PlayerReflectionKAvatar.Result actor) {
        var inputs=new LinkedHashMap<Identifier,Input>();
        for(var span:actor.textures()) {
            if(inputs.containsKey(span.texture()))continue;
            var borrowed=client.getTextureManager().getTexture(span.texture());
            var skin=borrowed==null?null:borrowed.getTextureView();
            if(skin==null || skin.isClosed() || skin.texture().isClosed())throw new IllegalArgumentException("KAvatar texture is not ready: "+span.texture());
            if(skin.texture().getFormat()!=GpuFormat.RGBA8_UNORM || (skin.texture().usage()&GpuTexture.USAGE_TEXTURE_BINDING)==0
                || skin.texture().getDepthOrLayers()!=1)throw new IllegalArgumentException("Unsupported KAvatar atlas texture format: "+span.texture());
            inputs.put(span.texture(),new Input(span.texture(),skin));
        }
        if(inputs.isEmpty())throw new IllegalArgumentException("KAvatar has no reflection textures");
        if(inputs.size()==1)return new Capture(actor.data(),inputs.values().iterator().next().view());
        var dimensions=inputs.values().stream().map(input->new ActorAtlasPlan.Source(input.id().toString(),input.view().getWidth(0),input.view().getHeight(0))).toList();
        if(!sources.equals(dimensions)) {
            plan=ActorAtlasPlan.pack(dimensions);sources=List.copyOf(dimensions);
            var next=new ArrayList<ByteBuffer>();
            for(var source:sources)next.add(blitFrame(plan,plan.rectangles().get(source.id())));
            frames=List.copyOf(next);
        }
        var device=RenderSystem.getDevice();
        if(texture==null || texture.getWidth(0)!=plan.width() || texture.getHeight(0)!=plan.height()) {
            if(texture!=null)RayWorld.awaitSurfaceResources();
            closeTexture();
            try {
                texture=device.createTexture("GreenMarine actor atlas",GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_SRC,
                    GpuFormat.RGBA8_UNORM,plan.width(),plan.height(),1,1);
                view=device.createTextureView(texture);
            }catch(RuntimeException|LinkageError failure) {closeTexture();throw failure;}
        }
        if(compiled==null) {
            try(var source=new Sources()) {
                compiled=device.compilePipeline(pipeline(),source,Runnable::run).join().finishCompile();
                if(compiled==null)throw new IllegalStateException("Unable to compile actor atlas blit");
            }
        }
        var encoder=device.createCommandEncoder();
        var uniforms=encoder.transientMemory().multiUploadGpu(frames.stream().map(ByteBuffer::duplicate).toList(),device.getDeviceInfo().limits().minUniformOffsetAlignment(),GpuBuffer.USAGE_UNIFORM);
        var sampler=RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try(var pass=encoder.createRenderPass(()->"GreenMarine actor atlas",view,Optional.of(new Vector4f()))) {
            pass.setPipeline(compiled);int index=0;
            for(var input:inputs.values()) {
                pass.setUniform("ActorAtlasBlit",uniforms.get(index++));pass.setUniform("ActorSource",input.view(),sampler);pass.draw(6,1,0,0);
            }
        }
        // Renderpearl and the native sampler both use GENERAL; closing this pass publishes its writes.
        var data=ByteBuffer.allocateDirect(actor.data().remaining()).order(ByteOrder.nativeOrder());data.put(actor.data().duplicate()).clear();
        for(var span:actor.textures())ActorAtlasPlan.remap(data,span.first(),span.boxes(),plan.rectangles().get(span.texture().toString()),plan.width(),plan.height());
        return new Capture(data.asReadOnlyBuffer().order(ByteOrder.nativeOrder()),view);
    }
    static ByteBuffer blitFrame(ActorAtlasPlan.Plan plan,ActorAtlasPlan.Rect rectangle) {
        var frame=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());int pad=ActorAtlasPlan.GUTTER;
        frame.putFloat(0,rectangle.x()-pad).putFloat(4,rectangle.y()-pad).putFloat(8,rectangle.width()+2*pad).putFloat(12,rectangle.height()+2*pad);
        frame.putFloat(16,plan.width()).putFloat(20,plan.height()).putFloat(24,rectangle.width()).putFloat(28,rectangle.height());return frame;
    }
    static RenderPipeline pipeline() {
        return RenderPipeline.builder().withLocation(Identifier.parse("greenmarine:actor_atlas"))
            .withVertexShader(Identifier.parse("greenmarine:core/actor_atlas")).withFragmentShader(Identifier.parse("greenmarine:core/actor_atlas"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("ActorAtlasBlit",UniformType.UNIFORM_BUFFER).withUniform("ActorSource",UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA8_UNORM,ColorTargetState.WRITE_ALL))
            .withDepthStencilState(Optional.empty()).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
    }
    private static final class Sources implements ShaderSource {
        private final String vertex=resource("vsh"),fragment=resource("fsh");
        @Override public String getShader(Identifier id,ShaderType type) {return type==ShaderType.VERTEX?vertex:fragment;}
        @Override public CachedIncludeSource getInclude(Identifier id) {return CachedIncludeSource.createError("Actor atlas has no shader includes");}
        @Override public void close() {}
        private static String resource(String suffix) {
            String path="/assets/greenmarine/shaders/core/actor_atlas."+suffix;
            try(var input=ActorAtlas.class.getResourceAsStream(path)) {
                if(input==null)throw new IllegalArgumentException("Missing actor atlas shader: "+path);
                return new String(input.readAllBytes(),StandardCharsets.UTF_8);
            }catch(IOException error) {throw new IllegalArgumentException("Unable to load actor atlas shader",error);}
        }
    }
    private void closeTexture() {
        if(view!=null)view.close();if(texture!=null)texture.close();view=null;texture=null;
    }
    /** Caller drains native/host submissions before reset; growth drains before replacing a view. */
    @Override public void close() {
        closeTexture();if(compiled!=null)compiled.close();compiled=null;sources=List.of();frames=List.of();plan=null;
    }
}
