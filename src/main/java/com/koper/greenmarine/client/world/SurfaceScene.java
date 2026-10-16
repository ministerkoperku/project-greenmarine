package com.koper.greenmarine.client.world;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.textures.*;
import org.joml.Vector4f;
import java.util.*;

/** Owned nearest visible terrain material buffers; host depth is never modified. */
final class SurfaceScene implements AutoCloseable {
    private GpuTexture normal,albedo,depth;
    private GpuTextureView normalView,albedoView,depthView;
    void prepare(RenderTarget main,CommandEncoder encoder) {
        if(normal!=null && normal.getWidth(0)==main.width && normal.getHeight(0)==main.height
            && depth.getFormat()==main.getDepthTexture().getFormat()) return;
        close();var device=RenderSystem.getDevice();
        int usage=GpuTexture.USAGE_RENDER_ATTACHMENT|GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_COPY_DST;
        try {
            normal=device.createTexture("GreenMarine surface normal/depth",usage,GpuFormat.RGBA32_FLOAT,main.width,main.height,1,1);
            albedo=device.createTexture("GreenMarine surface albedo/material",usage,GpuFormat.RGBA16_FLOAT,main.width,main.height,1,1);
            depth=device.createTexture("GreenMarine surface private depth",usage,main.getDepthTexture().getFormat(),main.width,main.height,1,1);
            normalView=device.createTextureView(normal);albedoView=device.createTextureView(albedo);depthView=device.createTextureView(depth);
            encoder.clearColorTexture(normal,new Vector4f());encoder.clearColorTexture(albedo,new Vector4f());encoder.clearDepthTexture(depth,0);
        } catch(RuntimeException|LinkageError error) {close();throw error;}
    }
    RenderPass begin(RenderTarget main,CommandEncoder encoder) {
        encoder.copyTextureToTexture(main.getDepthTexture(),depth,0,0,0,0,0,main.width,main.height);
        return encoder.createRenderPass(RenderPassDescriptor.builder(()->"GreenMarine terrain material replay")
            .withColorAttachment(normalView,Optional.of(new Vector4f()))
            .withColorAttachment(albedoView,Optional.of(new Vector4f()))
            .withDepthAttachment(depthView,OptionalDouble.empty()).build());
    }
    GpuTextureView normal() {return normalView;}
    GpuTextureView albedo() {return albedoView;}
    @Override public void close() {
        if(normal!=null)com.koper.greenmarine.client.rt.RayWorld.awaitSurfaceResources();
        if(normalView!=null)normalView.close();if(albedoView!=null)albedoView.close();if(depthView!=null)depthView.close();
        if(normal!=null)normal.close();if(albedo!=null)albedo.close();if(depth!=null)depth.close();
        normalView=albedoView=depthView=null;normal=albedo=depth=null;
    }
}
