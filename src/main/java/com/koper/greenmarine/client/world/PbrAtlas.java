package com.koper.greenmarine.client.world;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.mixin.TextureAtlasAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.joml.Vector4f;

/**
 * LabPBR material maps from resource packs, laid out exactly like the block atlas: for each block
 * sprite, textures/<sprite>_s.png (specular) and _n.png (normal) are scaled to the sprite's
 * rectangle. Texels without a map stay (0,0,0,0) specular and flat normal. Without any map both
 * views are 1x1 placeholders, which shaders detect through textureSize.
 * KNOWN ISSUE (2026-10-23): the CPU images are correct (logged pixels match the pack), but the
 * terrain shader reads zeros from the uploaded atlas, so packs have no visible effect yet.
 */
public final class PbrAtlas implements AutoCloseable {
    private static final int EMPTY_SPECULAR=0,FLAT_NORMAL=0xFF8080FF; // ARGB: a=255, r=128, g=128, b=255
    private GpuTexture specular,normal,emptySpecular,emptyNormal;
    private GpuTextureView specularView,normalView,emptySpecularView,emptyNormalView;
    private GpuTexture builtFor;
    // The encoder uploads from these later; they live until the maps are rebuilt or closed.
    private NativeImage specularImage,normalImage;
    private int specularMaps,normalMaps;

    public GpuTextureView specular(CommandEncoder encoder) {refresh(encoder);return specularView!=null?specularView:emptySpecularView;}
    public GpuTextureView normal(CommandEncoder encoder) {refresh(encoder);return normalView!=null?normalView:emptyNormalView;}

    /** Rebuilds when the block atlas texture is replaced (resource reload or resize). */
    private void refresh(CommandEncoder encoder) {
        if(emptySpecular==null) {
            // Cleared on the GPU, which needs render attachment usage.
            var device=RenderSystem.getDevice();int usage=GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_RENDER_ATTACHMENT;
            emptySpecular=device.createTexture("GreenMarine empty LabPBR specular",usage,GpuFormat.RGBA8_UNORM,1,1,1,1);
            emptyNormal=device.createTexture("GreenMarine empty LabPBR normal",usage,GpuFormat.RGBA8_UNORM,1,1,1,1);
            emptySpecularView=device.createTextureView(emptySpecular);emptyNormalView=device.createTextureView(emptyNormal);
            encoder.clearColorTexture(emptySpecular,new Vector4f());encoder.clearColorTexture(emptyNormal,new Vector4f(.5f,.5f,1,1));
        }
        var client=Minecraft.getInstance();
        TextureAtlas atlas=client.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
        var atlasTexture=atlas.getTexture();
        if(atlasTexture==builtFor)return;
        builtFor=atlasTexture;closeMaps();
        int width=atlasTexture.getWidth(0),height=atlasTexture.getHeight(0);
        var resources=client.getResourceManager();
        specularImage=new NativeImage(width,height,false);normalImage=new NativeImage(width,height,false);
        {
            specularImage.fillRect(0,0,width,height,EMPTY_SPECULAR);normalImage.fillRect(0,0,width,height,FLAT_NORMAL);
            specularMaps=normalMaps=0;
            for(var sprite:((TextureAtlasAccessor)atlas).greenmarine$sprites().values()) {
                var name=sprite.contents().name();
                int x=sprite.getX(),y=sprite.getY(),w=sprite.contents().width(),h=sprite.contents().height();
                if(copy(resources,name,"_s",specularImage,x,y,w,h))specularMaps++;
                if(copy(resources,name,"_n",normalImage,x,y,w,h))normalMaps++;
            }
            if(specularMaps+normalMaps>0) {
                var device=RenderSystem.getDevice();int usage=GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST;
                specular=device.createTexture("GreenMarine LabPBR specular atlas",usage,GpuFormat.RGBA8_UNORM,width,height,1,1);
                normal=device.createTexture("GreenMarine LabPBR normal atlas",usage,GpuFormat.RGBA8_UNORM,width,height,1,1);
                specularView=device.createTextureView(specular);normalView=device.createTextureView(normal);
                encoder.writeToTexture(specular,specularImage);encoder.writeToTexture(normal,normalImage);
            }
            GreenMarine.LOGGER.info("LabPBR material maps: {} specular, {} normal ({}x{} atlas)",specularMaps,normalMaps,width,height);
        }
    }

    /** Copies the first animation frame of a material map, nearest-scaled to the sprite rectangle. */
    private static boolean copy(ResourceManager resources,Identifier sprite,String suffix,NativeImage target,int x,int y,int w,int h) {
        var location=Identifier.fromNamespaceAndPath(sprite.getNamespace(),"textures/"+sprite.getPath()+suffix+".png");
        var resource=resources.getResource(location);
        if(resource.isEmpty())return false;
        try(var stream=resource.get().open();var image=NativeImage.read(stream)) {
            int sourceWidth=image.getWidth(),frameHeight=Math.min(image.getHeight(),Math.max(1,sourceWidth*h/Math.max(w,1)));
            for(int dy=0;dy<h;dy++)for(int dx=0;dx<w;dx++)
                target.setPixel(x+dx,y+dy,image.getPixel(dx*sourceWidth/w,dy*frameHeight/h));
            return true;
        } catch(Exception error) {
            GreenMarine.LOGGER.warn("LabPBR map {} unreadable: {}",location,error.toString());
            return false;
        }
    }

    public int specularMaps() {return specularMaps;}
    public int normalMaps() {return normalMaps;}
    private void closeMaps() {
        if(specularView!=null)specularView.close();if(normalView!=null)normalView.close();
        if(specular!=null)specular.close();if(normal!=null)normal.close();
        specularView=normalView=null;specular=normal=null;
        if(specularImage!=null)specularImage.close();if(normalImage!=null)normalImage.close();
        specularImage=normalImage=null;
    }
    @Override public void close() {
        closeMaps();builtFor=null;
        if(emptySpecularView!=null)emptySpecularView.close();if(emptyNormalView!=null)emptyNormalView.close();
        if(emptySpecular!=null)emptySpecular.close();if(emptyNormal!=null)emptyNormal.close();
        emptySpecularView=emptyNormalView=null;emptySpecular=emptyNormal=null;
    }
}
