package com.koper.greenmarine.probe;

import com.google.gson.*;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.api.render.*;
import com.koper.greenmarine.client.PackUniforms;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Two submissions in one encoder must retain independent bytes after CPU scratch reuse/free. */
final class UniformCopyProbe {
    private static final AtomicReference<Object> RESULT=new AtomicReference<>();
    private static MarineRenderer.Registration registration;
    private record Pending(BufferHandle buffer,com.mojang.renderpearl.api.commands.GpuFence fence) {}
    private static Pending pending;
    private static int ticks;
    private static boolean done;
    static void tick(Minecraft client,Path output) {
        if(done)return;
        try {
            if(registration==null) {registration=MarineRenderer.register("probe:uniform-copy",MarineRenderer.Stage.AFTER_PACKS,UniformCopyProbe::session);return;}
            if(++ticks>800)throw new IllegalStateException("Uniform copy probe timed out");
            if(pending!=null && pending.fence().awaitCompletion(0)) {
                verify(pending.buffer());pending.fence().close();pending=null;
            }
            if(RESULT.get() instanceof Throwable failure)throw new IllegalStateException("Uniform copy acceptance failed",failure);
            if(MarineRenderer.status().stream().anyMatch(s->s.id().equals("probe:uniform-copy")&&s.failed()))throw new IllegalStateException("Uniform copy contribution failed");
            if(RESULT.get() instanceof JsonObject result)finish(client,output,result);
        } catch(Throwable failure) {
            GreenMarine.LOGGER.error("Uniform copy probe failed",failure);var result=new JsonObject();result.addProperty("status","failed");result.addProperty("error",failure.toString());finish(client,output,result);
        }
    }
    private static java.util.function.Consumer<FrameContext> session(RenderSession session) {
        var block=new PackUniformBlock(List.of(new PackUniformBlock.Member("color","vec3","color"),
            new PackUniformBlock.Member("gain","float","gain"),new PackUniformBlock.Member("basis","mat3","basis"),
            new PackUniformBlock.Member("weights","float",2,"weights"),new PackUniformBlock.Member("enabled","bool","enabled"),
            new PackUniformBlock.Member("index","int","index")));
        var resources=new PackResources(Map.of(),Map.of(),Map.of("Payload",block));
        var compute=session.compute(new ComputeProgram(Identifier.parse("probe:uniform_copy"),"""
            #version 450
            layout(local_size_x=1) in;
            layout(std140) uniform Imported {
                vec3 color;float gain;mat3 basis;float weights[2];bool enabled;int index;
            };
            layout(std430) buffer Results {vec4 values[];};
            void main(){values[index]=vec4(color.x+gain,basis[2][1]+weights[1],enabled?1.0:0.0,weights[0]);}
            """,Map.of()));
        var data=session.storageBuffer("uniform copy output",32,0);
        var readback=session.buffer("uniform copy readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,ByteBuffer.allocateDirect(32));
        var submitted=new boolean[1];
        return frame->{
            if(submitted[0])return;submitted[0]=true;
            try {
                var encoder=session.device().createCommandEncoder();
                try(var uniforms=new PackUniforms()) {
                    for(int index=0;index<2;index++) {
                        boolean first=index==0;
                        var values=Map.<String,Object>of("color",first?new float[]{1,2,3}:new float[]{10,20,30},"gain",first?4f:40f,
                            "basis",first?new float[]{1,2,3,4,5,6,7,8,9}:new float[]{-1,-2,-3,-4,-5,-6,-7,-8,-9},
                            "weights",first?new float[]{.25f,.75f}:new float[]{.5f,1.5f},"enabled",first,"index",index);
                        uniforms.prepare(resources,Set.of("Payload"),encoder,values);
                        var slice=uniforms.slice("Payload");
                        session.dispatch(encoder,compute.bind().buffer("Imported",slice.buffer(),slice.offset(),slice.length()).buffer("Results",data),1,1,1);
                    }
                } // Intentionally free CPU scratch before either dispatch is submitted.
                encoder.copyToBuffer(data.buffer().slice(),readback.buffer().slice());
                pending=new Pending(readback,encoder.createFence());
                encoder.submit();
            }catch(Throwable failure){RESULT.set(failure);}
        };
    }
    private static void verify(BufferHandle readback) {
        try(var mapped=readback.buffer().map(true,false)) {
            var values=mapped.data().order(ByteOrder.nativeOrder());float[] expected={5,8.75f,1,.25f,50,-6.5f,0,.5f};int bad=0;
            for(int i=0;i<expected.length;i++)if(values.getFloat(i*4)!=expected[i])bad++;
            var result=new JsonObject();result.addProperty("status","complete");result.addProperty("copies",2);
            result.addProperty("scratchReleasedBeforeSubmit",true);result.addProperty("mismatches",bad);
            if(bad!=0)throw new IllegalStateException(result.toString());RESULT.set(result);
        }catch(Throwable failure){RESULT.set(failure);}
    }
    private static void finish(Minecraft client,Path output,JsonObject result) {
        done=true;
        if(pending!=null) {pending.fence().close();pending=null;}
        try{Files.writeString(output.resolve("uniform-copy-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));}
        catch(Exception failure){GreenMarine.LOGGER.error("Cannot save uniform copy evidence",failure);}
        if(registration!=null)registration.close();client.stop();
    }
}
