package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.PackTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.*;
import java.util.*;

/** Owns initialized pairs of colour attachments. Storage images and borrowed host images never enter this set. */
final class SceneTargets implements AutoCloseable {
    private static final class Owned implements AutoCloseable {
        final GpuTexture[] textures=new GpuTexture[2];final GpuTextureView[] views=new GpuTextureView[2];
        @Override public void close() {for(var view:views)if(view!=null)view.close();for(var texture:textures)if(texture!=null)texture.close();}
    }
    private final Map<String,Owned> owned=new LinkedHashMap<>();
    private final FeedbackTargets<GpuTextureView> versions=new FeedbackTargets<>();
    private Map<String,PackTarget> declared=Map.of();private Set<String> required=Set.of();
    private int width,height;private Object world;
    private long frameEpoch;private boolean frameBegun;

    void ensure(Map<String,PackTarget> declarations,Set<String> names,int width,int height,Object world,CommandEncoder encoder) {
        if(!declared.equals(declarations) || !required.equals(names) || this.width!=width || this.height!=height || this.world!=world) {
            close();declared=declarations;required=Set.copyOf(names);this.width=width;this.height=height;this.world=world;
            try {
                var device=RenderSystem.getDevice();
                for(var name:names) {
                    var spec=declarations.get(name);if(spec==null)throw new IllegalArgumentException("Undeclared colour target: "+name);
                    var format=PackCompute.format(spec.format());int w=spec.width(width),h=spec.height(height);
                    int limit=device.getDeviceInfo().limits().maxTextureSizeForFormat(format);
                    if(w>limit || h>limit)throw new IllegalArgumentException("Colour target exceeds host texture limit: "+name);
                    var target=new Owned();owned.put(name,target);
                    for(int i=0;i<2;i++) {
                        target.textures[i]=device.createTexture("GreenMarine target "+name+" "+i,
                            GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_SRC|GpuTexture.USAGE_COPY_DST|GpuTexture.USAGE_RENDER_ATTACHMENT,format,w,h,1,1);
                        target.views[i]=device.createTextureView(target.textures[i]);TargetClears.clear(encoder,target.textures[i],spec);
                    }
                    versions.define(name,target.views[0],target.views[1]);
                }
            } catch(RuntimeException|LinkageError failure) {close();throw failure;}
        }
    }
    /** Clear once before the first phase, never again at the post-world boundary. */
    void beginFrame(long epoch,CommandEncoder encoder) {
        if(frameBegun && frameEpoch==epoch)return;
        // Allocation initialized both versions already. Later frames apply their declared frame clears.
        if(frameBegun)for(var name:required)if(declared.get(name).clearEachFrame())
            for(var texture:owned.get(name).textures)TargetClears.clear(encoder,texture,declared.get(name));
        frameEpoch=epoch;frameBegun=true;
    }
    GpuTextureView read(String name) {return owned.containsKey(name)?versions.read(name):null;}
    List<GpuTextureView> writes(Map<Integer,String> outputs,CommandEncoder encoder) {
        var result=new ArrayList<GpuTextureView>();
        for(var name:outputs.values()) {
            var previous=versions.read(name);var next=versions.write(name);
            if(HostImages.dimensions(previous.texture())!=2 || HostImages.dimensions(next.texture())!=2)throw new IllegalArgumentException("Volume cannot be a colour attachment: "+name);
            if(!RenderSchedule.canCopy(previous.getWidth(0),previous.getHeight(0),previous.texture().getFormat(),next.getWidth(0),next.getHeight(0),next.texture().getFormat()))
                throw new IllegalStateException("Mismatched feedback pair: "+name);
            encoder.copyTextureToTexture(previous.texture(),next.texture(),0,0,0,0,0,next.getWidth(0),next.getHeight(0));
            result.add(next);
        }
        return List.copyOf(result);
    }
    void publish(Map<Integer,String> outputs) {versions.commit(outputs.values());}
    static boolean integer(GpuFormat format) {
        return switch(format.componentType()) {case UINT_8,SINT_8,UINT_16,SINT_16,UINT_32,SINT_32->true;default->false;};
    }
    @Override public void close() {owned.values().forEach(Owned::close);owned.clear();versions.clear();declared=Map.of();required=Set.of();world=null;width=height=0;frameBegun=false;}
}
