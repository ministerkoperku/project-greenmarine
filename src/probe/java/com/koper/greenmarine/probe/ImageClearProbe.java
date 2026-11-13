package com.koper.greenmarine.probe;

import com.google.gson.*;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.api.render.*;
import com.koper.greenmarine.client.*;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.minecraft.client.Minecraft;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real manifest execution over multiple frames and phases, including every volume slice. */
final class ImageClearProbe {
    private static final AtomicReference<Object> RESULT=new AtomicReference<>();
    private static MarineRenderer.Registration renderer;
    private static AutoCloseable pack;
    private static ShaderSettings saved;
    private static int ticks;
    private static boolean done;
    static void tick(Minecraft client,Path output) {
        if(done || client.player==null)return;
        try {
            client.gui.setScreen(null);
            if(renderer==null) {
                saved=ShaderRuntime.settings();
                var definition=ShaderPack.parse(JsonParser.parseString("""
                    {"schema":1,"id":"probe:image_clear","resources":{"images":{
                      "Frame2D":{"format":"r32ui","size":[5,3],"clearEachFrame":true},
                      "Keep":{"format":"r32ui","size":[5,3]},
                      "Frame3D":{"format":"r32ui","size":[5,3,2],"clearEachFrame":true},
                      "Frame1D":{"format":"rg32ui","size":[17],"clearEachFrame":true}},
                      "buffers":{"Report":{"size":512}}},"passes":[
                      {"name":"early","phase":"begin","stage":"compute","compute":"probe:clear_early","groups":[1,1,1]},
                      {"name":"late","phase":"composite","stage":"compute","compute":"probe:clear_late","groups":[1,1,1]}]}
                    """).getAsJsonObject());
                String shader="""
                    #version 450
                    layout(local_size_x=1) in;
                    layout(r32ui) uniform uimage2D Frame2D;
                    layout(r32ui) uniform uimage2D Keep;
                    layout(r32ui) uniform uimage3D Frame3D;
                    layout(rg32ui) uniform uimage1D Frame1D;
                    layout(std430) buffer Report {uint values[];};
                    void main(){int i=0;
                      for(int y=0;y<3;y++)for(int x=0;x<5;x++) {
                        ivec2 p=ivec2(x,y);uvec4 a=imageLoad(Frame2D,p)+uvec4(1);imageStore(Frame2D,p,a);values[i]=a.r;
                        uvec4 b=imageLoad(Keep,p)+uvec4(1);imageStore(Keep,p,b);values[15+i++]=b.r;}
                      i=30;for(int z=0;z<2;z++)for(int y=0;y<3;y++)for(int x=0;x<5;x++) {
                        ivec3 p=ivec3(x,y,z);uvec4 a=imageLoad(Frame3D,p)+uvec4(1);imageStore(Frame3D,p,a);values[i++]=a.r;}
                      for(int p=0;p<17;p++){uvec4 a=imageLoad(Frame1D,p)+uvec4(1);imageStore(Frame1D,p,a);values[i++]=a.r;values[i++]=a.g;}}
                    """;
                pack=ResourcePacks.CATALOG.register(new PackCatalog.LoadedPack(definition,"function update(frame) end",
                    Map.of("compute|probe:clear_early",shader,"compute|probe:clear_late",shader)));
                ShaderRuntime.apply(new ShaderSettings(definition.id(),Quality.HIGH,RayMode.OFF,false));
                if(!GreenMarineClient.enabled())GreenMarineClient.toggleEffect();
                renderer=MarineRenderer.register("probe:image_clear_readback",MarineRenderer.Stage.AFTER_PACKS,session->{
                    var report=session.buffer("Image clear report",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,ByteBuffer.allocateDirect(512));
                    var direct=session.buffer("Image clear direct",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,ByteBuffer.allocateDirect(60));
                    var submitted=new boolean[1];
                    return frame->{
                        if(submitted[0] || PackRenderer.sceneFrames()<4)return;submitted[0]=true;
                        long frames=PackRenderer.sceneFrames();var encoder=session.device().createCommandEncoder();
                        reenterFrame(encoder);
                        encoder.copyToBuffer(PackCompute.buffer("Report").slice(),report.buffer().slice());
                        encoder.copyTextureToBuffer(PackCompute.image("Frame2D").texture(),direct.buffer(),0,()->{
                            try(var mapped=report.buffer().map(true,false);var actual=direct.buffer().map(true,false)) {
                                var data=mapped.data().order(ByteOrder.nativeOrder());var texture=actual.data().order(ByteOrder.nativeOrder());int bad=0;
                                for(int i=0;i<94;i++)if(Integer.toUnsignedLong(data.getInt(i*4))!=(i>=15&&i<30?frames*2:2))bad++;
                                for(int i=0;i<15;i++)if(texture.getInt(i*4)!=2)bad++;
                                if(bad!=0)throw new IllegalStateException("Image clear mismatches="+bad+", frames="+frames);
                                var result=new JsonObject();result.addProperty("status","complete");result.addProperty("frames",frames);
                                result.addProperty("checkedValues",109);result.addProperty("mismatches",bad);result.addProperty("device",session.device().getDeviceInfo().name());RESULT.set(result);
                            }catch(Throwable failure){RESULT.set(failure);}
                        },0);encoder.submit();
                    };
                });return;
            }
            if(++ticks>200)throw new IllegalStateException("Image clear probe timed out");
            if(RESULT.get() instanceof Throwable failure)throw new IllegalStateException("Image clear acceptance failed",failure);
            if(RESULT.get() instanceof JsonObject result)finish(client,output,result);
        }catch(Throwable failure) {
            GreenMarine.LOGGER.error("Image clear probe failed",failure);var result=new JsonObject();result.addProperty("status","failed");result.addProperty("error",failure.toString());
            if(failure.getCause()!=null)result.addProperty("cause",failure.getCause().toString());finish(client,output,result);
        }
    }
    private static void reenterFrame(com.mojang.renderpearl.api.commands.CommandEncoder encoder) {
        try {
            var epoch=PackRenderer.class.getDeclaredField("frameEpoch");epoch.setAccessible(true);
            var begin=PackCompute.class.getDeclaredMethod("beginFrame",com.mojang.renderpearl.api.commands.CommandEncoder.class,long.class);begin.setAccessible(true);
            begin.invoke(null,encoder,epoch.getLong(null));
        }catch(ReflectiveOperationException failure){throw new IllegalStateException("Cannot reenter the current image frame",failure);}
    }
    private static void finish(Minecraft client,Path output,JsonObject result) {
        done=true;if(renderer!=null)renderer.close();if(saved!=null)ShaderRuntime.apply(saved);
        try{if(pack!=null)pack.close();Files.writeString(output.resolve("image-clear-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));}
        catch(Exception failure){GreenMarine.LOGGER.error("Cannot finish image clear probe",failure);}
        client.stop();
    }
}
