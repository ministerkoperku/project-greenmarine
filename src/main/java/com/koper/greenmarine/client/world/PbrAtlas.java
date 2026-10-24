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
 * rectangle. A separate R8 mask preserves map presence independently of channel values.
 * Missing specular maps can carry conservative inferred properties (presence bit 2),
 * separately from authored LabPBR data. Missing normals remain flat; shaders supply relief.
 */
public final class PbrAtlas implements AutoCloseable {
    private static final int EMPTY_SPECULAR=0,FLAT_NORMAL=0xFF8080FF; // ARGB: a=255, r=128, g=128, b=255
    private GpuTexture specular,normal,presence,emptySpecular,emptyNormal,emptyPresence;
    private GpuTextureView specularView,normalView,presenceView,emptySpecularView,emptyNormalView,emptyPresenceView;
    private GpuTexture builtFor;
    private int specularMaps,normalMaps;
    private long builtMaterialsGeneration=-1;

    public GpuTextureView specular(CommandEncoder encoder) {refresh(encoder);return specularView!=null?specularView:emptySpecularView;}
    public GpuTextureView normal(CommandEncoder encoder) {refresh(encoder);return normalView!=null?normalView:emptyNormalView;}
    public GpuTextureView presence(CommandEncoder encoder) {refresh(encoder);return presenceView!=null?presenceView:emptyPresenceView;}

    /** Rebuilds when the block atlas texture is replaced (resource reload or resize). */
    private void refresh(CommandEncoder encoder) {
        if(emptySpecular==null) {
            // Cleared on the GPU, which needs render attachment usage.
            var device=RenderSystem.getDevice();int usage=GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_RENDER_ATTACHMENT;
            emptySpecular=device.createTexture("GreenMarine empty LabPBR specular",usage,GpuFormat.RGBA8_UNORM,1,1,1,1);
            emptyNormal=device.createTexture("GreenMarine empty LabPBR normal",usage,GpuFormat.RGBA8_UNORM,1,1,1,1);
            emptyPresence=device.createTexture("GreenMarine empty LabPBR presence",usage,GpuFormat.R8_UNORM,1,1,1,1);
            emptyPresenceView=device.createTextureView(emptyPresence);encoder.clearColorTexture(emptyPresence,new Vector4f());
            emptySpecularView=device.createTextureView(emptySpecular);emptyNormalView=device.createTextureView(emptyNormal);
            encoder.clearColorTexture(emptySpecular,new Vector4f());encoder.clearColorTexture(emptyNormal,new Vector4f(.5f,.5f,1,1));
        }
        var client=Minecraft.getInstance();
        TextureAtlas atlas=client.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
        var atlasTexture=atlas.getTexture();
        long materialGeneration=com.koper.greenmarine.api.material.MaterialAddons.generation();
        if(atlasTexture==builtFor && materialGeneration==builtMaterialsGeneration)return;
        closeMaps();
        int width=atlasTexture.getWidth(0),height=atlasTexture.getHeight(0);
        var resources=client.getResourceManager();
        try(var specularImage=new NativeImage(width,height,false);var normalImage=new NativeImage(width,height,false)) {
            specularImage.fillRect(0,0,width,height,EMPTY_SPECULAR);normalImage.fillRect(0,0,width,height,FLAT_NORMAL);
            specularMaps=normalMaps=0;int inferredMaps=0,addonMaps=0;
            var mask=java.nio.ByteBuffer.allocateDirect(Math.multiplyExact(width,height));
            for(var sprite:((TextureAtlasAccessor)atlas).greenmarine$sprites().values()) {
                var name=sprite.contents().name();
                // Sprite x/y include the atlas gutter; UV bounds point at the actual image.
                int x=Math.round(sprite.getU0()*width),y=Math.round(sprite.getV0()*height);
                int w=sprite.contents().width(),h=sprite.contents().height();
                boolean hasSpecular=copy(resources,name,"_s",specularImage,x,y,w,h);
                boolean hasNormal=copy(resources,name,"_n",normalImage,x,y,w,h);
                if(hasSpecular)specularMaps++;if(hasNormal)normalMaps++;
                boolean builtin=com.koper.greenmarine.api.render.BlockMaterials.atlasProfiles().containsKey(name.toString())
                    || com.koper.greenmarine.api.render.BlockMaterials.additionalAtlasProfiles().containsKey(name.toString());
                var inferred=hasSpecular || builtin?null:com.koper.greenmarine.api.render.InferredMaterials.forTexture(name.toString());
                if(inferred!=null) {
                    // Inferred texel: raw roughness/reflectance/transmission, integer surface class.
                    int encoded=(inferred.surfaceClass()<<24)|(Math.round(inferred.roughness()*255)<<16)
                        |(Math.round(inferred.reflectance()*255)<<8)|Math.round(inferred.sunlightTransmission()*255);
                    specularImage.fillRect(x,y,w,h,encoded);inferredMaps++;
                }
                byte flags=(byte)((hasSpecular?1:0)|(hasNormal?2:0)|(inferred!=null?4:0));
                if(flags!=0)for(int dy=0;dy<h;dy++)for(int dx=0;dx<w;dx++)mask.put((y+dy)*width+x+dx,flags);
                if(!hasSpecular && w<=2048 && h<=2048) {
                    var compiled=com.koper.greenmarine.api.material.MaterialAddons.resolve(name.toString(),
                        com.koper.greenmarine.api.material.MaterialAddons.activePackId(),w,h);
                    if(compiled.isPresent()) {
                        var pixels=compiled.get().pixels();boolean any=false;
                        for(int dy=0;dy<h;dy++)for(int dx=0;dx<w;dx++) {
                            int offset=(dy*w+dx)*4,kind=Byte.toUnsignedInt(pixels.get(offset+3));
                            if(kind==0)continue;
                            int encoded=(kind<<24)|(Byte.toUnsignedInt(pixels.get(offset))<<16)
                                |(Byte.toUnsignedInt(pixels.get(offset+1))<<8)|Byte.toUnsignedInt(pixels.get(offset+2));
                            specularImage.setPixel(x+dx,y+dy,encoded);
                            mask.put((y+dy)*width+x+dx,(byte)((flags&~4)|8));any=true;
                        }
                        if(any)addonMaps++;
                    }
                }
            }
            if(specularMaps+normalMaps+inferredMaps+addonMaps>0) {
                var device=RenderSystem.getDevice();int usage=GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST;
                specular=device.createTexture("GreenMarine LabPBR specular atlas",usage,GpuFormat.RGBA8_UNORM,width,height,1,1);
                normal=device.createTexture("GreenMarine LabPBR normal atlas",usage,GpuFormat.RGBA8_UNORM,width,height,1,1);
                presence=device.createTexture("GreenMarine LabPBR presence",usage,GpuFormat.R8_UNORM,width,height,1,1);
                specularView=device.createTextureView(specular);normalView=device.createTextureView(normal);presenceView=device.createTextureView(presence);
                encoder.writeToTexture(specular,specularImage);encoder.writeToTexture(normal,normalImage);
                encoder.writeToTexture(presence,mask,0,0,0,0,width,height);
            }
            builtFor=atlasTexture;builtMaterialsGeneration=materialGeneration;
            // Renderpearl copies uploads into staging immediately; CPU images can close here.
            GreenMarine.LOGGER.info("LabPBR material maps: {} specular, {} normal, {} inferred, {} addon ({}x{} atlas)",specularMaps,normalMaps,inferredMaps,addonMaps,width,height);
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
        if(specularView!=null)specularView.close();if(normalView!=null)normalView.close();if(presenceView!=null)presenceView.close();
        if(specular!=null)specular.close();if(normal!=null)normal.close();if(presence!=null)presence.close();
        specularView=normalView=presenceView=null;specular=normal=presence=null;specularMaps=normalMaps=0;
    }
    @Override public void close() {
        closeMaps();builtFor=null;
        if(emptySpecularView!=null)emptySpecularView.close();if(emptyNormalView!=null)emptyNormalView.close();if(emptyPresenceView!=null)emptyPresenceView.close();
        if(emptySpecular!=null)emptySpecular.close();if(emptyNormal!=null)emptyNormal.close();if(emptyPresence!=null)emptyPresence.close();
        emptySpecularView=emptyNormalView=emptyPresenceView=null;emptySpecular=emptyNormal=emptyPresence=null;
    }
}
