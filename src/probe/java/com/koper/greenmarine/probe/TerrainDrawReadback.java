package com.koper.greenmarine.probe;

import com.koper.greenmarine.api.render.*;
import com.koper.greenmarine.client.terrain.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.*;
import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.*;
import net.minecraft.resources.Identifier;
import java.nio.*;
import java.util.*;

/** Executes the same decorated Draw callback as host terrain, using actual borrowed uber-buffer vertices. */
final class TerrainDrawReadback {
    private static final String VERTEX = """
        #version 450
        layout(location=0) in vec3 Position;
        layout(std140) uniform ProbeFrame {int probeBase;int count;};
        layout(std430) readonly buffer MarineTerrainVertices {uvec4 records[];};
        layout(std430) readonly buffer MarineTerrainDraws {ivec4 draws[];};
        layout(location=0) flat out vec4 proof;
        void main(){
            int local=gl_VertexIndex-probeBase;
            ivec4 draw=draws[gl_InstanceIndex];
            int vertex=draw.y+gl_VertexIndex-draw.x;
            proof=vec4(-999,-999,-999,Position.x);
            if(draw.w!=0 && vertex>=draw.y && vertex<draw.y+draw.z){
                uvec4 a=records[vertex*2],b=records[vertex*2+1];
                proof=vec4(float(int(a.z)),uintBitsToFloat(b.x),uintBitsToFloat(b.y),Position.x);
            }
            gl_Position=vec4((float(local)+.5)/float(count)*2.-1.,0.,0.,1.);
            gl_PointSize=1.;
        }
        """;
    private static final String FRAGMENT = """
        #version 450
        layout(location=0) flat in vec4 proof;
        layout(location=0) out vec4 color;
        void main(){color=proof;}
        """;
    private static ProgramHandle program;
    private static final List<ProgramHandle> adapterPrograms=new ArrayList<>();
    private static final String ADAPTER_DIR=System.getProperty("greenmarine.probe.terrain_adapter_dir");
    static int vertices, callbacks, unavailableChecks, adapterVertices, nonzeroBases;
    static void verify(RenderSession session, Minecraft client, Object mesh, ChunkSectionLayer layer) throws Exception {
        verify(session,client,mesh,layer,true,-1);
        if(ADAPTER_DIR!=null)for(int variant=0;variant<4;variant++)verify(session,client,mesh,layer,true,variant);
    }
    static void verifyUnavailable(RenderSession session, Minecraft client, Object mesh, ChunkSectionLayer layer) throws Exception { verify(session,client,mesh,layer,false,-1); }
    private static void verify(RenderSession session, Minecraft client, Object mesh, ChunkSectionLayer layer, boolean ready, int variant) throws Exception {
        var field=LevelRenderer.class.getDeclaredField("sectionRenderDispatcher");field.setAccessible(true);
        var dispatcher=(SectionRenderDispatcher)field.get(client.levelRenderer);
        var slice=dispatcher.getRenderSectionSlice((SectionMesh)mesh,layer);
        if(slice==null)throw new IllegalStateException("Captured mesh has no actual host allocation");
        int base=Math.toIntExact(slice.vertexBufferOffset()/28);
        if(ready && variant<0 && base!=0)nonzeroBases++;
        var captured=TerrainSidecars.data(mesh).layers().get(layer);
        int count=Math.min(256,captured.vertexCount());
        if(program==null) {
            var vertex=Identifier.parse("probe:terrain_draw_vertex");var fragment=Identifier.parse("probe:terrain_draw_fragment");
            var bindings=BindGroupLayout.builder().withUniform("ProbeFrame",UniformType.UNIFORM_BUFFER)
                .withUniform(TerrainDrawBindings.VERTICES,UniformType.UNIFORM_BUFFER).withUniform(TerrainDrawBindings.DRAWS,UniformType.UNIFORM_BUFFER).build();
            var pipeline=RenderPipeline.builder().withLocation(fragment).withVertexShader(vertex).withFragmentShader(fragment)
                .withVertexBinding(0,layer.pipeline(false).getVertexFormatBinding(0)).withBindGroupLayout(bindings)
                .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.POINTS).withCull(false).build();
            program=session.compile(new ShaderProgram(pipeline,Map.of(vertex,VERTEX,fragment,FRAGMENT),Map.of(),Map.of())
                .withStorage(Map.of(TerrainDrawBindings.VERTICES,ShaderProgram.Storage.BUFFER,TerrainDrawBindings.DRAWS,ShaderProgram.Storage.BUFFER)));
        }
        if(variant>=0 && adapterPrograms.isEmpty())for(String name:List.of("attributes","tintao","signed","tintao_default")) {
            var vertex=Identifier.parse("probe:terrain_adapter_"+name+"_vertex");var fragment=Identifier.parse("probe:terrain_adapter_"+name+"_fragment");
            var bindings=BindGroupLayout.builder();
            for(String uniform:List.of("ProbeFrame","TerrainUniform","Projection","Globals","ChunkSection",TerrainDrawBindings.VERTICES,TerrainDrawBindings.DRAWS))bindings.withUniform(uniform,UniformType.UNIFORM_BUFFER);
            var pipeline=RenderPipeline.builder().withLocation(fragment).withVertexShader(vertex).withFragmentShader(fragment)
                .withVertexBinding(0,layer.pipeline(false).getVertexFormatBinding(0)).withBindGroupLayout(bindings.build())
                .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(PrimitiveTopology.POINTS).withCull(false).build();
            var path=java.nio.file.Path.of(ADAPTER_DIR);
            adapterPrograms.add(session.compile(new ShaderProgram(pipeline,Map.of(vertex,java.nio.file.Files.readString(path.resolve(name+".vsh")),fragment,java.nio.file.Files.readString(path.resolve(name+".fsh"))),Map.of(),Map.of())
                .withStorage(Map.of(TerrainDrawBindings.VERTICES,ShaderProgram.Storage.BUFFER,TerrainDrawBindings.DRAWS,ShaderProgram.Storage.BUFFER))));
        }
        var selected=variant<0?program:adapterPrograms.get(variant);
        var target=session.texture("Terrain draw proof",TextureSpec.fixed(GpuFormat.RGBA32_FLOAT,count,1),count,1);
        var indices=ByteBuffer.allocateDirect((count+5)*4).order(ByteOrder.nativeOrder());
        for(int i=0;i<5;i++)indices.putInt(0);for(int i=count-1;i>=0;i--)indices.putInt(i);indices.flip();
        try(var index=session.device().createBuffer(()->"Terrain draw reverse index fixture",GpuBuffer.USAGE_INDEX,indices);
            var readback=session.device().createBuffer(()->"Terrain vertex-stage readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,count*16)) {
            var encoder=session.device().createCommandEncoder();
            var frame=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).putInt(base).putInt(count).putLong(0).flip();
            var uniform=encoder.transientMemory().uploadGpu(frame,256,GpuBuffer.USAGE_UNIFORM);
            var prepared=client.levelRenderer.prepareChunkRenders(new org.joml.Matrix4f(),true);
            var capture=((TerrainDrawBindings.Attachment)prepared).greenmarine$terrainDraws();
            if(capture==null || capture.mesh(layer.ordinal(),slice.vertexBuffer(),base)!=mesh)throw new IllegalStateException("Real host draw capture did not associate the actual mesh allocation");
            var draw=new RenderPass.Draw<Object>(0,slice.vertexBuffer(),index,IndexType.INT,5,count,base,(data,uploader)->callbacks++);
            try(var pass=encoder.createRenderPass(RenderPassDescriptor.builder(()->"Actual terrain vertex-stage indexing").withColorAttachment(target.view(),Optional.of(new org.joml.Vector4f())).build())) {
                pass.setPipeline(selected.pipeline());pass.setUniform("ProbeFrame",uniform);
                if(variant>=0){
                    var terrain=ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());new org.joml.Matrix4f().get(0,terrain);
                    var projection=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());new org.joml.Matrix4f().get(0,projection);
                    var globals=ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder()).putInt(4).putInt(-8).putInt(12).putFloat(0).putFloat(.5f).putFloat(-.25f).putFloat(.125f).putFloat(0).putFloat(count).putFloat(1).putInt(0).putInt(0).flip();
                    var chunk=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).putInt(32).putInt(64).putInt(-16).putFloat(1).flip();
                    pass.setUniform("TerrainUniform",encoder.transientMemory().uploadGpu(terrain,256,GpuBuffer.USAGE_UNIFORM));
                    pass.setUniform("Projection",encoder.transientMemory().uploadGpu(projection,256,GpuBuffer.USAGE_UNIFORM));
                    pass.setUniform("Globals",encoder.transientMemory().uploadGpu(globals,256,GpuBuffer.USAGE_UNIFORM));
                    pass.setUniform("ChunkSection",encoder.transientMemory().uploadGpu(chunk,256,GpuBuffer.USAGE_UNIFORM));
                }
                pass.drawMultipleIndexed(TerrainDrawBindings.decorate(capture,layer,List.of(draw)),index,IndexType.INT,TerrainDrawBindings.uniforms(List.of()),null);
            }
            encoder.copyTextureToBuffer(target.texture(),readback,0,()->{},0);
            try(var fence=encoder.createFence()){encoder.submit();if(!fence.awaitCompletion(10_000_000_000L))throw new IllegalStateException("Terrain vertex-stage timeout");}
            var source=captured.records();var host=captured.hostVertices();
            try(var mapped=readback.map(true,false)) {
                var output=mapped.data().order(ByteOrder.nativeOrder());
                for(int i=0;i<count;i++) {
                    float[] expected;
                    if(variant==1 || variant==3){int color=source.getInt(i*32+24);float ao=(color>>>24)/255f,factor=variant==3?ao:1;expected=new float[]{((color>>>16)&255)/255f*factor,((color>>>8)&255)/255f*factor,(color&255)/255f*factor,variant==3?1:ao};}
                    else if(variant==2){int mid=source.getInt(i*32+12);expected=new float[]{(byte)mid,(byte)(mid>>>8),(byte)(mid>>>16),(byte)(mid>>>24)};}
                    else expected=new float[]{ready?source.getInt(i*32+8):-999,ready?source.getFloat(i*32+16):-999,ready?source.getFloat(i*32+20):-999,host.getFloat(i*28)+(variant==0?28.5f:0)};
                    for(int component=0;component<4;component++)if(!Float.isFinite(output.getFloat(i*16+component*4)) || Math.abs(output.getFloat(i*16+component*4)-expected[component])>1e-6f)
                        throw new IllegalStateException("Actual terrain vertex lookup differs at "+i+", base="+base+", layer="+layer+", variant="+variant+", channel="+component+", output="+output.getFloat(i*16+component*4)+", expected="+expected[component]);
                    if(variant>=0)adapterVertices++;else if(ready)vertices++;else unavailableChecks++;
                }
            }
        } finally { target.close(); }
    }
}
