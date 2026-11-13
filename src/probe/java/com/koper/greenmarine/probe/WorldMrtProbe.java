package com.koper.greenmarine.probe;

import com.google.gson.*;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.api.render.*;
import com.koper.greenmarine.client.*;
import com.koper.greenmarine.client.world.WorldPrograms;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.minecraft.client.Minecraft;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Actual terrain draws write owned named attachments beside the borrowed host depth attachment. */
final class WorldMrtProbe {
    private static final AtomicReference<Object> RESULT=new AtomicReference<>();
    private static AutoCloseable packRegistration;
    private static MarineRenderer.Registration registration;
    private static ShaderSettings saved;
    private static boolean selected,submitted,done,transparency;
    private static int ticks;
    private static boolean owned() {return Boolean.getBoolean("greenmarine.probe.world_mrt_owned");}
    static void tick(Minecraft client,Path output) {
        if(done || client.level==null || client.player==null)return;
        try {
            if(!selected) {
                selected=true;saved=ShaderRuntime.settings();transparency=client.options.improvedTransparency().get();
                client.options.improvedTransparency().set(false);
                client.player.setPos(-55.5,93,76.5);client.player.setYRot(180);client.player.setXRot(50);
                var json=manifest();var shaders=new HashMap<String,String>();
                shaders.put("probe:core/world_mrt",worldShader());
                shaders.put("probe:core/world_mrt_deferred",DEFERRED);
                shaders.put("probe:core/world_mrt_present",PRESENT);
                var definition=ShaderPack.parse(json);
                packRegistration=GreenMarine.PACKS.register(new PackCatalog.LoadedPack(definition,"function update(frame) end",shaders));
                ShaderRuntime.apply(new ShaderSettings(definition.id(),Quality.HIGH,RayMode.OFF,false));
                if(!GreenMarineClient.enabled())GreenMarineClient.toggleEffect();
                registration=MarineRenderer.register("probe:world_mrt",MarineRenderer.Stage.AFTER_PACKS,session->frame->capture(session,frame));
                return;
            }
            if(++ticks>800)throw new IllegalStateException("World MRT probe timed out");
            if(ShaderRuntime.status().state()==PackStatus.State.FAILED)throw new IllegalStateException(ShaderRuntime.status().detail());
            if(RESULT.get() instanceof Throwable error)throw new IllegalStateException("World MRT verification failed",error);
            if(RESULT.get() instanceof JsonObject result)finish(client,output,result);
        }catch(Throwable error) {
            GreenMarine.LOGGER.error("World MRT probe failed",error);var result=new JsonObject();result.addProperty("status","failed");result.addProperty("error",error.toString());finish(client,output,result);
        }
    }
    private static JsonObject manifest() {
        var json=JsonParser.parseString("""
            {"schema":1,"id":"probe:world_mrt","targets":{
                "WorldFloat":{"format":"rgba32f","scale":1,"clear":[1,0,0,1],"clearEachFrame":true},
                "WorldUint":{"format":"r32ui","scale":1,"clear":[0,0,0,0],"clearEachFrame":true},
                "OpaqueProof":{"format":"rgba32f","scale":1}},
             "resources":{"uniforms":{"WorldInputs":[{"name":"width","type":"float","source":"viewWidth"}]}},
             "programs":[{"target":"terrain","fragment":"probe:core/world_mrt",
                "inputs":{"Previous":"WorldFloat"},"uniforms":{"WorldFrame":"WorldInputs"},
                "outputs":{"0":"WorldFloat","1":"WorldUint"}}],
             "passes":[
               {"name":"world_deferred","fragment":"probe:core/world_mrt_deferred","phase":"deferred",
                "inputs":{"FloatInput":"WorldFloat","UintInput":"WorldUint"},"outputs":{"0":"OpaqueProof"}},
               {"name":"world_present","fragment":"probe:core/world_mrt_present","phase":"final",
                "inputs":{"FloatInput":"WorldFloat","UintInput":"WorldUint","OpaqueInput":"OpaqueProof"}}]}
            """).getAsJsonObject();
        if(owned()) {
            json.addProperty("worldColor","WorldFloat");
            for(int i=2;i<8;i++) {
                var target=new JsonObject();target.addProperty("format","rgba32f");target.addProperty("scale",1);
                json.getAsJsonObject("targets").add("WorldExtra"+i,target);
                json.getAsJsonArray("programs").get(0).getAsJsonObject().getAsJsonObject("outputs").addProperty(""+i,"WorldExtra"+i);
            }
        }
        return json;
    }
    private static String worldShader() {
        var source=new StringBuilder("""
            #version 450
            uniform sampler2D Previous;
            layout(std140) uniform WorldFrame {float width;};
            layout(location=0) out vec4 floatOutput;
            layout(location=1) out uint uintOutput;
            """);
        if(owned())for(int i=2;i<8;i++)source.append("layout(location=").append(i).append(") out vec4 extra").append(i).append(";\n");
        source.append("void main(){ivec2 p=ivec2(gl_FragCoord.xy);floatOutput=vec4(texelFetch(Previous,p,0).r+1.,width,3.,1.);uintOutput=73u;");
        if(owned())for(int i=2;i<8;i++)source.append("extra").append(i).append("=vec4(").append(i).append(".,2.,3.,1.);");
        return source.append("}\n").toString();
    }
    private static final String DEFERRED="""
        #version 450
        uniform sampler2D FloatInput;uniform usampler2D UintInput;uniform sampler2D DepthSampler;uniform sampler2D SceneSampler;
        layout(location=0)out vec4 proof;
        void main(){ivec2 p=ivec2(gl_FragCoord.xy);vec4 a=texelFetch(FloatInput,p,0);uint b=texelFetch(UintInput,p,0).r;
            proof=vec4(a.r,float(b),texelFetch(DepthSampler,p,0).r,a.g);}
        """;
    private static final String PRESENT="""
        #version 450
        uniform sampler2D FloatInput;uniform usampler2D UintInput;uniform sampler2D OpaqueInput;
        layout(location=0)out vec4 color;
        void main(){ivec2 p=ivec2(gl_FragCoord.xy);vec4 a=texelFetch(FloatInput,p,0);uint b=texelFetch(UintInput,p,0).r;
            color=b==73u?vec4(0,1,0,1):vec4(0,0,0,1);}
        """;
    private static void capture(RenderSession session,FrameContext frame) {
        if(submitted || ticks<80 || WorldPrograms.draws()<10)return;
        submitted=true;
        try {
            int width=frame.width(),height=frame.height(),pixels=width*height;
            var buffers=new ArrayList<GpuBuffer>();var names=new ArrayList<>(List.of("WorldFloat","WorldUint","OpaqueProof"));
            if(owned())for(int i=2;i<8;i++)names.add("WorldExtra"+i);
            var encoder=session.device().createCommandEncoder();
            for(int i=0;i<names.size();i++) {
                var view=PackRenderer.worldInput(names.get(i));
                var buffer=session.device().createBuffer(()->"World MRT readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,(long)pixels*(i==1?4:16));
                buffers.add(buffer);
                boolean last=i==names.size()-1;
                encoder.copyTextureToBuffer(view.texture(),buffer,0,last?()->verify(buffers,names,width,height):()->{},0);
            }
            encoder.submit();
        }catch(Throwable failure){RESULT.set(failure);}
    }
    private static void verify(List<GpuBuffer> buffers,List<String> names,int width,int height) {
        var mappings=new ArrayList<com.mojang.renderpearl.api.buffers.GpuBufferSlice.MappedView>();
        try {
            var bytes=new ArrayList<ByteBuffer>();for(var buffer:buffers) {var mapped=buffer.map(true,false);mappings.add(mapped);bytes.add(mapped.data().order(ByteOrder.LITTLE_ENDIAN));}
            int opaque=0,latest=0,bad=0;var a=bytes.get(0);var b=bytes.get(1);var proof=bytes.get(2);
            for(int i=0;i<width*height;i++) {
                if(proof.getFloat(i*16+4)==73) {
                    opaque++;
                    if(proof.getFloat(i*16)!=2 || proof.getFloat(i*16+8)<=0 || proof.getFloat(i*16+12)!=width)bad++;
                }
                if(b.getInt(i*4)==73) {
                    latest++;
                    float value=a.getFloat(i*16);
                    if((value!=2 && value!=3) || a.getFloat(i*16+4)!=width || a.getFloat(i*16+8)!=3)bad++;
                    if(owned())for(int target=3;target<bytes.size();target++)if(bytes.get(target).getFloat(i*16)!=(target-1))bad++;
                }
            }
            var result=new JsonObject();result.addProperty("status","complete");result.addProperty("ownedMainColor",owned());
            result.addProperty("worldAttachments",owned()?8:2);result.addProperty("opaqueTerrainPixels",opaque);result.addProperty("latestTerrainPixels",latest);
            result.addProperty("width",width);result.addProperty("height",height);result.addProperty("worldDraws",WorldPrograms.draws());result.addProperty("mismatches",bad);
            int first=0;while(first<width*height-1 && b.getInt(first*4)!=73)first++;
            var sample=new JsonObject();
            sample.addProperty("floatR",a.getFloat(first*16));sample.addProperty("floatG",a.getFloat(first*16+4));sample.addProperty("floatB",a.getFloat(first*16+8));sample.addProperty("uint",b.getInt(first*4));
            sample.addProperty("opaqueR",proof.getFloat(first*16));sample.addProperty("opaqueDepth",proof.getFloat(first*16+8));sample.addProperty("opaqueWidth",proof.getFloat(first*16+12));
            result.add("sample",sample);
            if(opaque<100 || latest<100 || bad!=0)throw new IllegalStateException(result.toString());RESULT.set(result);
        }catch(Throwable failure){RESULT.set(failure);}
        finally {for(var mapping:mappings)mapping.close();for(var buffer:buffers)buffer.close();}
    }
    private static void finish(Minecraft client,Path output,JsonObject result) {
        done=true;
        try {Files.writeString(output.resolve(owned()?"world-mrt-owned-result.json":"world-mrt-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));}
        catch(Exception error){GreenMarine.LOGGER.error("Cannot save world MRT evidence",error);}
        if(registration!=null)registration.close();client.options.improvedTransparency().set(transparency);
        if(saved!=null)ShaderRuntime.apply(saved);
        try {if(packRegistration!=null)packRegistration.close();}catch(Exception ignored){}
        client.stop();
    }
}
