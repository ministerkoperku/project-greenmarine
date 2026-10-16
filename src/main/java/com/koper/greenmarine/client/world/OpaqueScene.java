package com.koper.greenmarine.client.world;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.*;
import org.joml.Vector4f;

/** Independent images avoid sampling from the active world framebuffer. */
final class OpaqueScene implements AutoCloseable {
    private GpuTexture color,depth;
    private GpuTextureView colorView,depthView;
    void prepare(RenderTarget main,CommandEncoder encoder) {
        if(color!=null && color.getWidth(0)==main.width && color.getHeight(0)==main.height
            && color.getFormat()==main.getColorTexture().getFormat() && depth.getFormat()==main.getDepthTexture().getFormat()) return;
        close();var device=RenderSystem.getDevice();
        int usage=GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_RENDER_ATTACHMENT;
        try {
            color=device.createTexture("GreenMarine opaque scene",usage,main.getColorTexture().getFormat(),main.width,main.height,1,1);
            depth=device.createTexture("GreenMarine opaque depth",usage,main.getDepthTexture().getFormat(),main.width,main.height,1,1);
            colorView=device.createTextureView(color);depthView=device.createTextureView(depth);
            encoder.clearColorAndDepthTextures(color,new Vector4f(),depth,0);
        } catch(RuntimeException|LinkageError error) {close();throw error;}
    }
    void capture(RenderTarget main,CommandEncoder encoder) {
        encoder.copyTextureToTexture(main.getColorTexture(),color,0,0,0,0,0,main.width,main.height);
        encoder.copyTextureToTexture(main.getDepthTexture(),depth,0,0,0,0,0,main.width,main.height);
    }
    GpuTextureView color() {return colorView;}
    GpuTextureView depth() {return depthView;}
    @Override public void close() {
        if(colorView!=null)colorView.close();if(depthView!=null)depthView.close();
        if(color!=null)color.close();if(depth!=null)depth.close();
        colorView=depthView=null;color=depth=null;
    }
}
