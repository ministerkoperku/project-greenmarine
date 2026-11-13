package com.koper.greenmarine.client.world;

import com.koper.greenmarine.api.pack.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real descriptor routing without allocating GPU objects or touching host ownership. */
class WorldMrtLayoutTest {
    @Test void opaqueAndTranslucentFeedbackBatchesPublishOncePerBoundary() {
        int[] writes={0},publications={0};
        var colors=List.of(view(GpuFormat.RGBA32_FLOAT),view(GpuFormat.R32_UINT));
        var state=new WorldMrt(new WorldMrt.TargetAccess() {
            public List<GpuTextureView> writes(Map<Integer,String> outputs,com.mojang.renderpearl.api.commands.CommandEncoder encoder) {writes[0]++;assertEquals(Map.of(0,"A",1,"B"),outputs);return colors;}
            public void publish(Map<Integer,String> outputs) {publications[0]++;assertEquals(Map.of(0,"A",1,"B"),outputs);}
        });
        var pack=ShaderPack.parse(com.google.gson.JsonParser.parseString("""
            {"schema":1,"id":"test:world","targets":{"A":{"scale":1},"B":{"scale":1,"format":"r32ui"}},
             "passes":[],"programs":[{"target":"terrain","fragment":"test:world","outputs":{"0":"A","1":"B"}}]}
            """).getAsJsonObject());
        var color=view(GpuFormat.RGBA8_UNORM);var depth=view(GpuFormat.D32_FLOAT);
        var host=new com.mojang.blaze3d.pipeline.RenderTarget("test",GpuFormat.RGBA8_UNORM,GpuFormat.D32_FLOAT) {
            {colorTextureView=color;depthTextureView=depth;}
        };
        state.prepare(pack,host,null,3);assertEquals(1,writes[0]);assertEquals(0,publications[0]);
        state.publish();state.publish();assertEquals(1,publications[0]);
        state.afterDeferred(null);state.afterDeferred(null);assertEquals(2,writes[0]);
        state.publish();state.publish();assertEquals(2,publications[0]);
        state.reset();state.publish();assertEquals(2,publications[0]);
        assertThrows(IllegalArgumentException.class,()->state.prepare(pack,host,null,2));
        assertEquals(2,writes[0]);
    }
    @Test void appendedWorldTargetsPreserveEveryBorrowedOitAttachmentAndDepth() throws Exception {
        var color=view(GpuFormat.RGBA8_UNORM);var depth=view(GpuFormat.D32_FLOAT);
        var first=attachment(view(GpuFormat.RGBA16_FLOAT));var second=attachment(view(GpuFormat.R16_FLOAT));
        var descriptor=new RenderPassDescriptor(()->"OIT",List.of(first,second),new RenderPassDescriptor.Attachment<>(depth,OptionalDouble.empty()),new com.mojang.renderpearl.api.commands.RenderPass.RenderArea(0,0,64,32));
        var state=state(color,depth,null,List.of(view(GpuFormat.RGBA32_FLOAT),view(GpuFormat.R32_UINT)));
        var routed=state.descriptor(descriptor,4);
        assertSame(descriptor.depthAttachment(),routed.depthAttachment());
        assertSame(first,routed.colorAttachments().get(0));assertSame(second,routed.colorAttachments().get(1));
        assertEquals(List.of(GpuFormat.RGBA16_FLOAT,GpuFormat.R16_FLOAT,GpuFormat.RGBA32_FLOAT,GpuFormat.R32_UINT),state.formats());
        assertEquals(Map.of(0,2,1,3),state.mapping(program(),2));
        assertEquals(Map.of(0,0,1,1),state.mapping(null,2));
        assertThrows(IllegalArgumentException.class,()->state.descriptor(descriptor,3));
    }
    @Test void ownedMainColorReplacesOnlySingleMainColorPassAndHonorsPhysicalLimit() throws Exception {
        var color=view(GpuFormat.RGBA8_UNORM);var depth=view(GpuFormat.D32_FLOAT);
        var writes=new ArrayList<GpuTextureView>();for(int i=0;i<8;i++)writes.add(view(GpuFormat.RGBA32_FLOAT));
        var state=state(color,depth,"A",writes);
        var descriptor=new RenderPassDescriptor(()->"Main",List.of(attachment(color)),new RenderPassDescriptor.Attachment<>(depth,OptionalDouble.empty()),new com.mojang.renderpearl.api.commands.RenderPass.RenderArea(0,0,64,32));
        var routed=state.descriptor(descriptor,8);
        assertEquals(8,routed.colorAttachments().size());assertSame(writes.getFirst(),routed.colorAttachments().getFirst().textureView());
        assertSame(descriptor.depthAttachment(),routed.depthAttachment());
        assertThrows(IllegalArgumentException.class,()->state.descriptor(descriptor,7));
        var oit=new RenderPassDescriptor(()->"OIT",List.of(attachment(color),attachment(color)),descriptor.depthAttachment(),descriptor.renderArea());
        assertThrows(IllegalArgumentException.class,()->state.descriptor(oit,8));
    }
    @Test void unrelatedDepthAndColorPassesKeepOriginalAttachments() throws Exception {
        var color=view(GpuFormat.RGBA8_UNORM);var depth=view(GpuFormat.D32_FLOAT);
        var state=state(color,depth,null,List.of(view(GpuFormat.RGBA32_FLOAT),view(GpuFormat.R32_UINT)));
        var descriptor=new RenderPassDescriptor(()->"Owned depth",List.of(attachment(color)),new RenderPassDescriptor.Attachment<>(view(GpuFormat.D32_FLOAT),OptionalDouble.empty()),new com.mojang.renderpearl.api.commands.RenderPass.RenderArea(0,0,64,32));
        assertSame(descriptor,state.descriptor(descriptor,8));assertFalse(state.pass());
    }
    private static PackProgram program() {return new PackProgram(PackProgram.Target.TERRAIN,null,"test:world",false,null,false,false,false,Map.of(0,"A",1,"B"),Map.of(),Map.of());}
    private static RenderPassDescriptor.Attachment<Optional<org.joml.Vector4fc>> attachment(GpuTextureView view) {return new RenderPassDescriptor.Attachment<>(view,Optional.empty());}
    private static WorldMrt state(GpuTextureView color,GpuTextureView depth,String owned,List<GpuTextureView> writes) throws Exception {
        var state=new WorldMrt();
        for(var entry:Map.<String,Object>of("outputs",Map.of(0,"A",1,"B"),"writes",writes,"hostColor",color,"hostDepth",depth,"batch",true).entrySet())set(state,entry.getKey(),entry.getValue());
        set(state,"worldColor",owned);return state;
    }
    private static void set(WorldMrt state,String name,Object value) throws Exception {var field=WorldMrt.class.getDeclaredField(name);field.setAccessible(true);field.set(state,value);}
    private static GpuTextureView view(GpuFormat format) {
        GpuTexture texture=new GpuTexture() {
            public int getWidth(int mip){return 64;}public int getHeight(int mip){return 32;}public int getDepthOrLayers(){return 1;}public int getMipLevels(){return 1;}
            public GpuFormat getFormat(){return format;}public int usage(){return GpuTexture.USAGE_RENDER_ATTACHMENT;}public String getLabel(){return "test";}public boolean isClosed(){return false;}public void close(){fail("Borrowed texture closed");}
        };
        return new GpuTextureView() {
            public boolean isClosed(){return false;}public GpuTexture texture(){return texture;}public int baseMipLevel(){return 0;}public int mipLevels(){return 1;}
            public int getWidth(int mip){return texture.getWidth(mip);}public int getHeight(int mip){return texture.getHeight(mip);}public void close(){fail("Borrowed view closed");}
        };
    }
}
