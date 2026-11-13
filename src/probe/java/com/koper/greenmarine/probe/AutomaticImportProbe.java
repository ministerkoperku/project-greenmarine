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
import java.util.concurrent.atomic.AtomicReference;

/** Loads only converter-generated external assets; no handwritten replacement shader is registered. */
final class AutomaticImportProbe {
    private static final AtomicReference<Object> RESULT=new AtomicReference<>();
    private static MarineRenderer.Registration registration;
    private static ShaderSettings saved;
    private static int ticks;
    private static boolean done,submitted;
    static void tick(Minecraft client,Path output) {
        if(done)return;
        try {
            if(registration==null) {
                saved=ShaderRuntime.settings();
                var pack=ResourcePacks.CATALOG.packs().stream().filter(p->p.definition().id().startsWith("iris_imported:native_")).findFirst().orElseThrow(()->new IllegalStateException("Generated native external fixture is missing"));
                ShaderRuntime.apply(new ShaderSettings(pack.definition().id(),Quality.HIGH,RayMode.OFF,false));
                if(!GreenMarineClient.enabled())GreenMarineClient.toggleEffect();
                registration=MarineRenderer.register("probe:automatic_import",MarineRenderer.Stage.AFTER_PACKS,session->frame->capture(session,frame));
                return;
            }
            if(++ticks>400)throw new IllegalStateException("Automatic import probe timed out");
            if(ShaderRuntime.status().state()==PackStatus.State.FAILED)throw new IllegalStateException(ShaderRuntime.status().detail());
            if(RESULT.get() instanceof Throwable failure)throw new IllegalStateException("Generated pipeline readback failed",failure);
            if(RESULT.get() instanceof JsonObject result)finish(client,output,result);
        }catch(Throwable failure){GreenMarine.LOGGER.error("Automatic import probe failed",failure);var result=new JsonObject();result.addProperty("status","failed");result.addProperty("error",failure.toString());finish(client,output,result);}
    }
    private static void capture(RenderSession session,FrameContext frame) {
        if(submitted || ticks<60)return;
        submitted=true;
        try {
            var target=PackRenderer.worldInput("colortex0");int pixels=frame.width()*frame.height();
            if(target==null)throw new IllegalStateException("Generated named target is unavailable");
            boolean bytes=target.texture().getFormat()==com.mojang.renderpearl.api.GpuFormat.RGBA8_UNORM;
            if(!bytes && target.texture().getFormat()!=com.mojang.renderpearl.api.GpuFormat.RGBA32_FLOAT)throw new IllegalStateException("Unexpected generated fixture format: "+target.texture().getFormat());
            var readback=session.device().createBuffer(()->"Automatic converter result",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,(long)pixels*(bytes?4:16));
            var encoder=session.device().createCommandEncoder();
            encoder.copyTextureToBuffer(target.texture(),readback,0,()->{
                try(var mapped=readback.map(true,false)) {
                    var data=mapped.data().order(ByteOrder.nativeOrder());float[] expected={.5f,.125f,.75f,1};int bad=0;
                    for(int pixel=0;pixel<pixels;pixel++)for(int component=0;component<4;component++) {
                        float actual=bytes?Byte.toUnsignedInt(data.get(pixel*4+component))/255f:data.getFloat(pixel*16+component*4);
                        if(!Float.isFinite(actual) || Math.abs(actual-expected[component])>(bytes?1f/255:1e-6f))bad++;
                    }
                    var result=new JsonObject();result.addProperty("status","complete");result.addProperty("pixels",pixels);result.addProperty("mismatches",bad);
                    result.addProperty("passes",ShaderRuntime.active().definition().passes().size());result.addProperty("customSourceVertex",true);result.addProperty("externalConverterAssets",true);
                    result.addProperty("format",target.texture().getFormat().name());
                    if(bad!=0)throw new IllegalStateException(result.toString());RESULT.set(result);
                }catch(Throwable failure){RESULT.set(failure);}finally{readback.close();}
            },0);encoder.submit();
        }catch(Throwable failure){RESULT.set(failure);}
    }
    private static void finish(Minecraft client,Path output,JsonObject result) {
        done=true;
        try{Files.writeString(output.resolve("automatic-import-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));}
        catch(Exception failure){GreenMarine.LOGGER.error("Cannot save automatic import result",failure);}
        if(registration!=null)registration.close();if(saved!=null)ShaderRuntime.apply(saved);client.stop();
    }
}
