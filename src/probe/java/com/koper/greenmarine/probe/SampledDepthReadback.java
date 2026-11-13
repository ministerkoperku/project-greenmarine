package com.koper.greenmarine.probe;

import com.google.gson.JsonObject;
import com.koper.greenmarine.api.render.*;
import com.koper.greenmarine.client.PackRenderer;
import com.koper.greenmarine.client.terrain.TerrainDrawBindings;
import com.koper.greenmarine.client.world.WorldPrograms;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.*;
import java.util.*;
import java.util.function.Consumer;

/** Reads actual GPU snapshots and independent original-source depth witnesses. */
final class SampledDepthReadback {
    static void capture(RenderSession session,FrameContext frame,Consumer<Object> completed) {
        var views=new LinkedHashMap<String,GpuTextureView>();
        for(String name:List.of("world_depth","opaque_depth","opaque_no_hand_depth","colortex0","colortex2","colortex3","colortex4","colortex5","colortex6"))
            views.put(name,PackRenderer.worldInput(name));
        views.put("host",frame.texture("sceneDepth"));
        views.put("compute",com.koper.greenmarine.client.PackCompute.image("sampledDepthProof"));
        int width=frame.width(),pixels=width*frame.height();
        var buffers=new LinkedHashMap<String,GpuBuffer>();
        try {
            for(var entry:views.entrySet()) {
                var format=entry.getValue().texture().getFormat();
                var expected=entry.getKey().equals("host")?GpuFormat.D32_FLOAT:entry.getKey().startsWith("colortex")||entry.getKey().equals("compute")?GpuFormat.RGBA32_FLOAT:GpuFormat.R32_FLOAT;
                if(format!=expected)throw new IllegalStateException("Unexpected depth proof format: "+entry.getKey()+" "+format);
                buffers.put(entry.getKey(),session.device().createBuffer(()->"Depth proof "+entry.getKey(),GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,(long)pixels*(format==GpuFormat.RGBA32_FLOAT?16:4)));
            }
            var encoder=session.device().createCommandEncoder();
            for(var entry:views.entrySet()) {
                boolean last=entry.getKey().equals("compute");
                encoder.copyTextureToBuffer(entry.getValue().texture(),buffers.get(entry.getKey()),0,last?()->{
                    var mappings=new ArrayList<com.mojang.renderpearl.api.buffers.GpuBufferSlice.MappedView>();
                    try {
                        var data=new HashMap<String,ByteBuffer>();
                        for(var buffer:buffers.entrySet()) {
                            var mapping=buffer.getValue().map(true,false);mappings.add(mapping);
                            data.put(buffer.getKey(),mapping.data().order(ByteOrder.nativeOrder()));
                        }
                        var result=verify(data,pixels,width);
                        result.addProperty("device",session.device().getDeviceInfo().name());
                        result.addProperty("boundDraws",TerrainDrawBindings.boundDraws());result.addProperty("worldDraws",WorldPrograms.draws());
                        completed.accept(result);
                    }catch(Throwable error){completed.accept(error);}
                    finally{mappings.forEach(com.mojang.renderpearl.api.buffers.GpuBufferSlice.MappedView::close);buffers.values().forEach(GpuBuffer::close);}
                }:()->{},0);
            }
            encoder.submit();
        }catch(Throwable failure){buffers.values().forEach(GpuBuffer::close);throw failure;}
    }
    private static JsonObject verify(Map<String,ByteBuffer> data,int pixels,int width) {
        int bad=0,worldBad=0,opaqueBad=0,earlyBad=0,deferredBad=0,compositeBad=0,computeBad=0,waterBad=0,water=0,waterDifferences=0,cutoutLeaks=0;
        int[] geometry=new int[3];
        var mismatches=new com.google.gson.JsonArray();
        for(int pixel=0;pixel<pixels;pixel++) {
            float host=data.get("host").getFloat(pixel*4),world=data.get("world_depth").getFloat(pixel*4);
            float opaque=data.get("opaque_depth").getFloat(pixel*4),noHand=data.get("opaque_no_hand_depth").getFloat(pixel*4);
            float expectedOpaque=data.get("colortex2").getFloat(pixel*16+12)==1?data.get("colortex2").getFloat(pixel*16):1;
            for(float depth:new float[]{host,world,opaque,noHand})if(!Float.isFinite(depth)||depth<0||depth>1)bad++;
            if(!near(world,1-host))worldBad++;
            if(!near(opaque,expectedOpaque)||!near(noHand,expectedOpaque)) {
                opaqueBad++;
                if(mismatches.size()<8) {
                    var example=new JsonObject();example.addProperty("x",pixel%width);example.addProperty("y",pixel/width);
                    example.addProperty("opaque",opaque);example.addProperty("noHand",noHand);example.addProperty("reference",expectedOpaque);
                    example.addProperty("world",world);example.addProperty("host",host);mismatches.add(example);
                }
            }
            var early=data.get("colortex5");var deferred=data.get("colortex6");var composite=data.get("colortex4");
            for(int channel=0;channel<4;channel++)if(early.getFloat(pixel*16+channel*4)!=1)earlyBad++;
            for(int channel=0;channel<4;channel++)if(!near(deferred.getFloat(pixel*16+channel*4),channel==3?1:expectedOpaque))deferredBad++;
            float[] expectedComposite={1-host,expectedOpaque,expectedOpaque,1};
            for(int channel=0;channel<4;channel++)if(!near(composite.getFloat(pixel*16+channel*4),expectedComposite[channel]))compositeBad++;
            for(int channel=0;channel<4;channel++)if(!near(data.get("compute").getFloat(pixel*16+channel*4),expectedComposite[channel]))computeBad++;
            var color=data.get("colortex0");float r=color.getFloat(pixel*16),g=color.getFloat(pixel*16+4);
            int variant=Math.round(g*4)-1;
            if(variant>=0 && variant<3 && near(r,new int[]{37,73,111}[variant]/255f))geometry[variant]++;
            if(variant==1 && pixel%width%8<4)cutoutLeaks++;
            var witness=data.get("colortex3");
            if(witness.getFloat(pixel*16+12)==1) {
                water++;
                for(int channel=0;channel<3;channel++)if(!near(witness.getFloat(pixel*16+channel*4),expectedOpaque))waterBad++;
                if(Math.abs(world-opaque)>1e-7f)waterDifferences++;
            }
        }
        var result=new JsonObject();result.addProperty("status","complete");result.addProperty("pixels",pixels);
        result.addProperty("nonfiniteOrInvalidDepth",bad);result.addProperty("worldDepthMismatches",worldBad);result.addProperty("opaqueDepthMismatches",opaqueBad);
        result.addProperty("earlyDepthMismatches",earlyBad);result.addProperty("deferredDepthMismatches",deferredBad);result.addProperty("compositeDepthMismatches",compositeBad);
        result.addProperty("waterDepthMismatches",waterBad);result.addProperty("waterPixels",water);result.addProperty("waterDepthDifferences",waterDifferences);
        result.addProperty("computeDepthMismatches",computeBad);
        result.addProperty("solidPixels",geometry[0]);result.addProperty("cutoutPixels",geometry[1]);result.addProperty("cutoutLeaks",cutoutLeaks);
        result.addProperty("initializedCurrentFrame",true);result.addProperty("originalSourceRegenerated",true);
        result.add("opaqueMismatchExamples",mismatches);
        if(bad+worldBad+opaqueBad+earlyBad+deferredBad+compositeBad+computeBad+waterBad+cutoutLeaks!=0 || water<100 || waterDifferences<100 || geometry[0]<100 || geometry[1]<100)
            throw new IllegalStateException("Sampled depth acceptance failed: "+result);
        return result;
    }
    private static boolean near(float actual,float expected) {return Float.isFinite(actual)&&Float.isFinite(expected)&&Math.abs(actual-expected)<1e-6f;}
}
