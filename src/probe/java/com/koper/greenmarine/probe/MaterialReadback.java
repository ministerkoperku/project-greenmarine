package com.koper.greenmarine.probe;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.client.world.WorldPrograms;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Diagnostic readback of the actual host Vulkan surface material capture. */
final class MaterialReadback {
    static void capture(Consumer<Object> done) {
        GpuBuffer allocated=null;
        var completed=new AtomicBoolean();
        try {
            var view=WorldPrograms.surfaceAlbedo();
            if(view==null)throw new IllegalStateException("Current surface material capture is unavailable");
            // Snapshot all borrowed resource metadata before asynchronous work. A pack
            // change may close the view before the completion callback runs.
            var texture=view.texture();
            int width=texture.getWidth(0),height=texture.getHeight(0);
            var format=texture.getFormat();
            if(format!=GpuFormat.RGBA16_FLOAT)throw new IllegalStateException("Unexpected surface material format: "+format);
            int pixels=Math.multiplyExact(width,height);
            int bytes=Math.multiplyExact(pixels,8);
            var device=RenderSystem.getDevice();
            var buffer=device.createBuffer(()->"GreenMarine material diagnostic readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,bytes);
            allocated=buffer;
            var encoder=device.createCommandEncoder();
            encoder.copyTextureToBuffer(texture,buffer,0,()->{
                if(!completed.compareAndSet(false,true))return;
                try(var mapped=buffer.map(true,false)) {
                    var result=summarize(mapped.data().order(ByteOrder.LITTLE_ENDIAN),width,height);
                    result.addProperty("format",format.toString());
                    deliver(done,result);
                }catch(Throwable failure){deliver(done,failure);}
                finally {close(buffer);}
            },0);
            encoder.submit();
        }catch(Throwable failure) {
            if(completed.compareAndSet(false,true)) {
                try {deliver(done,failure);}
                finally {if(allocated!=null)close(allocated);}
            }
        }
    }

    private static JsonObject summarize(ByteBuffer data,int width,int height) {
        int pixels=Math.multiplyExact(width,height);
        long captured=0,reflective=0,invalidAlpha=0,porous=0;
        float porousMin=Float.POSITIVE_INFINITY,porousMax=Float.NEGATIVE_INFINITY;
        var counts=new TreeMap<Integer,Long>();
        for(int pixel=0;pixel<pixels;pixel++) {
            float alpha=Float.float16ToFloat(data.getShort(pixel*8+6));
            if(!Float.isFinite(alpha)) {invalidAlpha++;continue;}
            int surfaceClass=(int)Math.floor(alpha+.0001f);
            counts.merge(surfaceClass,1L,Long::sum);
            if(surfaceClass>=1 && surfaceClass<=9)captured++;
            if((surfaceClass>=2 && surfaceClass<=6) || surfaceClass==8 || surfaceClass==9)reflective++;
            if(surfaceClass==7) {
                float roughness=(alpha-(float)Math.floor(alpha))*2;
                porous++;porousMin=Math.min(porousMin,roughness);porousMax=Math.max(porousMax,roughness);
            }
        }
        var histogram=new JsonObject();
        counts.forEach((surfaceClass,count)->histogram.addProperty(Integer.toString(surfaceClass),count));
        int centerX=width/2,centerY=height/2,centerOffset=(centerY*width+centerX)*8;
        var center=new JsonObject();center.addProperty("x",centerX);center.addProperty("y",centerY);
        String[] channels={"r","g","b","a"};
        for(int channel=0;channel<4;channel++)addFinite(center,channels[channel],Float.float16ToFloat(data.getShort(centerOffset+channel*2)));
        var roughness=new JsonObject();roughness.addProperty("pixels",porous);
        addFinite(roughness,"min",porousMin);addFinite(roughness,"max",porousMax);
        var result=new JsonObject();result.addProperty("status","complete");
        result.addProperty("width",width);result.addProperty("height",height);result.addProperty("pixelCount",pixels);
        result.addProperty("capturedPixelCount",captured);result.addProperty("reflectiveClassCount",reflective);
        result.addProperty("invalidAlphaPixelCount",invalidAlpha);
        result.add("center",center);result.add("classHistogram",histogram);result.add("class7Roughness",roughness);
        return result;
    }
    private static void addFinite(JsonObject object,String name,float value) {
        if(Float.isFinite(value))object.addProperty(name,value);else object.add(name,JsonNull.INSTANCE);
    }
    private static void close(GpuBuffer buffer) {
        try {buffer.close();}
        catch(Throwable failure) {GreenMarine.LOGGER.error("Material diagnostic buffer cleanup failed",failure);}
    }
    private static void deliver(Consumer<Object> done,Object result) {
        try {done.accept(result);}
        catch(Throwable failure) {GreenMarine.LOGGER.error("Material diagnostic consumer failed",failure);}
    }
}
