package com.koper.greenmarine.client.world;

import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.client.PackRenderer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.util.*;

/** Borrows host depth/OIT attachments and owns no GPU resources; SceneTargets owns all named colours. */
final class WorldMrt {
    interface TargetAccess {
        List<GpuTextureView> writes(Map<Integer,String> outputs,CommandEncoder encoder);
        void publish(Map<Integer,String> outputs);
    }
    private final TargetAccess targets;
    WorldMrt() {this(new TargetAccess() {
        public List<GpuTextureView> writes(Map<Integer,String> outputs,CommandEncoder encoder) {return PackRenderer.worldTargetWrites(outputs,encoder);}
        public void publish(Map<Integer,String> outputs) {PackRenderer.worldTargetPublish(outputs);}
    });}
    WorldMrt(TargetAccess targets) {this.targets=Objects.requireNonNull(targets);}
    private Map<Integer,String> outputs=Map.of();
    private List<GpuTextureView> writes=List.of();
    private GpuTextureView hostColor,hostDepth;
    private String worldColor;
    private boolean batch,pass;
    private int hostSlots;
    private List<GpuFormat> formats=List.of();

    void prepare(ShaderPack pack,RenderTarget host,CommandEncoder encoder,int maxAttachments) {
        reset();var names=new LinkedHashSet<String>();worldColor=pack.worldColor();
        if(worldColor!=null)names.add(worldColor);
        for(var program:pack.programs())names.addAll(program.outputs().values());
        if(names.isEmpty())return;
        int required=names.size()+(worldColor==null?1:0);
        if(required>maxAttachments)throw new IllegalArgumentException("World MRT requires "+required+" colour attachments; host limit is "+maxAttachments);
        var all=new LinkedHashMap<Integer,String>();int slot=0;for(String name:names)all.put(slot++,name);
        outputs=Collections.unmodifiableMap(all);hostColor=host.getColorTextureView();hostDepth=host.getDepthTextureView();
        begin(encoder);
    }
    private void begin(CommandEncoder encoder) {
        if(outputs.isEmpty() || batch)return;
        writes=targets.writes(outputs,encoder);batch=true;
    }
    void publish() {
        pass=false;
        if(batch) {targets.publish(outputs);batch=false;writes=List.of();}
    }
    void afterDeferred(CommandEncoder encoder) {begin(encoder);}
    boolean active() {return !outputs.isEmpty();}
    boolean pass() {return pass;}
    List<GpuFormat> formats() {return formats;}
    String signature() {return hostSlots+":"+formats;}
    Map<Integer,Integer> mapping(PackProgram program,int ordinaryOutputs) {
        var result=new LinkedHashMap<Integer,Integer>();
        if(program==null || program.outputs().isEmpty()) {
            for(int i=0;i<ordinaryOutputs;i++)result.put(i,i);
        } else for(var output:program.outputs().entrySet()) {
            int index=-1;
            for(var named:outputs.entrySet())if(named.getValue().equals(output.getValue())) {index=named.getKey();break;}
            if(index<0)throw new IllegalStateException("World target was not allocated: "+output.getValue());
            result.put(output.getKey(),hostSlots+index);
        }
        return Map.copyOf(result);
    }
    RenderPassDescriptor descriptor(RenderPassDescriptor original,int maxAttachments) {
        pass=false;
        if(!batch || original.depthAttachment()==null || original.depthAttachment().textureView()!=hostDepth || original.colorAttachments().isEmpty())return original;
        boolean main=original.colorAttachments().size()==1 && original.colorAttachments().getFirst()!=null
            && original.colorAttachments().getFirst().textureView()==hostColor;
        // Owned main colour is explicit. OIT keeps every host accumulation attachment and appends.
        hostSlots=main && worldColor!=null?0:original.colorAttachments().size();
        if(hostSlots+writes.size()>maxAttachments)throw new IllegalArgumentException("World MRT plus borrowed host/OIT attachments exceed host colour attachment limit");
        var colors=new ArrayList<RenderPassDescriptor.Attachment<Optional<org.joml.Vector4fc>>>();
        if(hostSlots>0)colors.addAll(original.colorAttachments());
        for(var view:writes)colors.add(new RenderPassDescriptor.Attachment<>(view,Optional.empty()));
        var types=new ArrayList<GpuFormat>();
        for(var attachment:colors) {
            if(attachment==null)throw new IllegalArgumentException("Sparse borrowed world attachments are not supported");
            types.add(attachment.textureView().texture().getFormat());
        }
        formats=List.copyOf(types);pass=true;
        return new RenderPassDescriptor(original.label(),List.copyOf(colors),original.depthAttachment(),original.renderArea());
    }
    void reset() {outputs=Map.of();writes=List.of();hostColor=hostDepth=null;worldColor=null;batch=pass=false;hostSlots=0;formats=List.of();}
}
