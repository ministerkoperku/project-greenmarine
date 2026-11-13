package com.koper.greenmarine.probe;

import com.google.gson.*;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.render.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Host-device 3D image acceptance: imageStore -> sampler3D compute -> sampler3D raster -> CPU. */
final class VolumeProbe {
    private static final int W=8,H=4,D=3,BYTES=W*H*D*16,LINE=17;
    private static final AtomicReference<Object> RESULT=new AtomicReference<>();
    private static MarineRenderer.Registration registration;
    private static int ticks;
    private static boolean done;
    static void tick(Minecraft client,Path output) {
        if(done)return;
        try {
            if(registration==null) {registration=MarineRenderer.register("probe:volume",MarineRenderer.Stage.AFTER_PACKS,VolumeProbe::session);return;}
            if(++ticks>800)throw new IllegalStateException("Volume probe timed out");
            if(RESULT.get() instanceof Throwable error)throw new IllegalStateException("Volume GPU acceptance failed",error);
            if(MarineRenderer.status().stream().anyMatch(s->s.id().equals("probe:volume")&&s.failed()))throw new IllegalStateException("Volume contribution failed; inspect log");
            if(RESULT.get() instanceof JsonObject result)finish(client,output,result);
        } catch(Throwable error) {
            GreenMarine.LOGGER.error("Volume probe failed",error);
            var result=new JsonObject();result.addProperty("status","failed");result.addProperty("error",error.toString());finish(client,output,result);
        }
    }
    private static java.util.function.Consumer<FrameContext> session(RenderSession session) {
        var volume=session.storageVolume("probe LPV",new VolumeSpec(GpuFormat.RGBA32_FLOAT,W,H,D));
        if(!volume.volume() || volume.depth()!=D)throw new IllegalStateException("Volume metadata lost");
        var fill=session.compute(new ComputeProgram(Identifier.parse("probe:volume_fill"),"""
            #version 450
            layout(local_size_x=4,local_size_y=2,local_size_z=2) in;
            layout(rgba32f) uniform writeonly image3D Lpv;
            void main(){ivec3 p=ivec3(gl_GlobalInvocationID);if(any(greaterThanEqual(p,imageSize(Lpv))))return;
                imageStore(Lpv,p,vec4(vec3(p)+1.,1.));}
            """,Map.of()));
        var read=session.compute(new ComputeProgram(Identifier.parse("probe:volume_read"),"""
            #version 450
            layout(local_size_x=4,local_size_y=2,local_size_z=2) in;
            uniform sampler3D Lpv;
            layout(std430) buffer Cells {vec4 values[];};
            void main(){ivec3 p=ivec3(gl_GlobalInvocationID);ivec3 size=textureSize(Lpv,0);
                if(any(greaterThanEqual(p,size)))return;
                values[(p.z*size.y+p.y)*size.x+p.x]=texelFetch(Lpv,p,0);
                if(all(equal(p,ivec3(0)))) {
                    int end=size.x*size.y*size.z;
                    values[end]=textureLod(Lpv,vec3(.0625,.125,-.25),0.);
                    values[end+1]=textureLod(Lpv,vec3(.0625,.125,1.25),0.);
                }}
            """,Map.of()));
        var cells=session.storageBuffer("volume cells",BYTES+32,0);
        var computed=session.buffer("volume compute readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,ByteBuffer.allocateDirect(BYTES+32));
        var cleared=session.buffer("volume clear readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,ByteBuffer.allocateDirect(BYTES+32));
        var rasterRead=session.buffer("volume raster readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,ByteBuffer.allocateDirect(BYTES));
        var out=session.texture("volume raster slices",TextureSpec.fixed(GpuFormat.RGBA32_FLOAT,W,H*D),W,H*D);
        var vertex=Identifier.parse("probe:volume_quad");var fragment=Identifier.parse("probe:volume_slices");
        var pipeline=RenderPipeline.builder().withLocation(fragment).withVertexShader(vertex).withFragmentShader(fragment)
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("Lpv",UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
        var shader=session.compile(new ShaderProgram(pipeline,Map.of(
            vertex,"#version 450\nvoid main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2.-1.,0,1);}",
            fragment,"#version 450\nuniform sampler3D Lpv;layout(location=0)out vec4 color;void main(){ivec2 p=ivec2(gl_FragCoord.xy);color=texelFetch(Lpv,ivec3(p.x,p.y%4,p.y/4),0);}"),Map.of(),Map.of()));
        var graph=session.graph(RenderGraph.builder().importTexture("lpv").importTexture("out")
            .pass(new RenderGraph.Pass("volume_slices",Set.of("lpv"),Set.of("out"),Set.of(),ctx->ctx.draw(
                ctx.material(shader).texture("Lpv","lpv",FilterMode.NEAREST),List.of("out"),null,null,true))).build());
        var line=session.storageLine("probe block data",GpuFormat.RG32_UINT,LINE);
        var lineFill=session.compute(new ComputeProgram(Identifier.parse("probe:line_fill"),"""
            #version 450
            layout(local_size_x=8) in;
            layout(rg32ui) uniform writeonly uimage1D Data;
            void main(){int p=int(gl_GlobalInvocationID.x);if(p>=imageSize(Data))return;
                imageStore(Data,p,uvec4(uint(p)*13u+7u,0xabcdef00u^uint(p),0u,1u));}
            """,Map.of()));
        var lineRead=session.compute(new ComputeProgram(Identifier.parse("probe:line_read"),"""
            #version 450
            layout(local_size_x=8) in;
            uniform usampler1D Data;
            layout(std430) buffer Cells {uvec2 values[];};
            void main(){int p=int(gl_GlobalInvocationID.x);if(p>=textureSize(Data,0))return;values[p]=texelFetch(Data,p,0).xy;}
            """,Map.of()));
        var lineCells=session.storageBuffer("line cells",LINE*8,0);
        var lineCpu=session.buffer("line compute readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,ByteBuffer.allocateDirect(LINE*8));
        var submitted=new boolean[1];
        return frame->{
            if(submitted[0])return;submitted[0]=true;
            var encoder=session.device().createCommandEncoder();
            session.clearVolume(encoder,volume);
            session.dispatch(encoder,read.bind().texture("Lpv",volume.view(),FilterMode.NEAREST).buffer("Cells",cells),2,2,2);
            encoder.copyToBuffer(cells.buffer().slice(),cleared.buffer().slice());
            session.dispatch(encoder,fill.bind().image("Lpv",volume),fill.groupsX(W),fill.groupsY(H),fill.groupsZ(D));
            session.dispatch(encoder,read.bind().texture("Lpv",volume.view(),FilterMode.NEAREST).buffer("Cells",cells),2,2,2);
            encoder.copyToBuffer(cells.buffer().slice(),computed.buffer().slice());
            session.clearStorageImage(encoder,line);
            session.dispatch(encoder,lineFill.bind().image("Data",line),3,1,1);
            session.dispatch(encoder,lineRead.bind().texture("Data",line.view(),FilterMode.NEAREST).buffer("Cells",lineCells),3,1,1);
            encoder.copyToBuffer(lineCells.buffer().slice(),lineCpu.buffer().slice());
            graph.execute(frame,Map.of("lpv",volume,"out",out));
            encoder.copyTextureToBuffer(out.texture(),rasterRead.buffer(),0,()->verify(computed,cleared,rasterRead,lineCpu),0);
            encoder.submit();
        };
    }
    private static void verify(BufferHandle compute,BufferHandle clear,BufferHandle raster,BufferHandle line) {
        try(var c=compute.buffer().map(true,false);var z=clear.buffer().map(true,false);var r=raster.buffer().map(true,false);var l=line.buffer().map(true,false)) {
            var cd=c.data().order(ByteOrder.LITTLE_ENDIAN);var zd=z.data().order(ByteOrder.LITTLE_ENDIAN);var rd=r.data().order(ByteOrder.LITTLE_ENDIAN);
            int badCompute=0,badClear=0,badRaster=0;
            for(int depth=0;depth<D;depth++)for(int y=0;y<H;y++)for(int x=0;x<W;x++)for(int k=0;k<4;k++) {
                int at=(((depth*H+y)*W+x)*4+k)*4;float expected=switch(k){case 0->x+1;case 1->y+1;case 2->depth+1;default->1;};
                if(cd.getFloat(at)!=expected)badCompute++;
                if(zd.getFloat(at)!=0)badClear++;
                if(rd.getFloat(at)!=expected)badRaster++;
            }
            var result=new JsonObject();result.addProperty("status","complete");result.addProperty("voxels",W*H*D);
            result.addProperty("computeMismatches",badCompute);result.addProperty("clearMismatches",badClear);result.addProperty("rasterMismatches",badRaster);
            var ld=l.data().order(ByteOrder.LITTLE_ENDIAN);int badLine=0;
            for(int p=0;p<LINE;p++)if(ld.getInt(p*8)!=p*13+7 || ld.getInt(p*8+4)!=(0xabcdef00^p))badLine++;
            result.addProperty("lineMismatches",badLine);result.addProperty("lineTexels",LINE);
            float lower=cd.getFloat(BYTES+8),upper=cd.getFloat(BYTES+24);
            result.addProperty("clampedLowerZ",lower);result.addProperty("clampedUpperZ",upper);
            if(badCompute+badClear+badRaster+badLine!=0 || lower!=1 || upper!=D)throw new IllegalStateException(result.toString());
            RESULT.set(result);
        }catch(Throwable error){RESULT.set(error);}
    }
    private static void finish(Minecraft client,Path output,JsonObject result) {
        done=true;
        try{Files.writeString(output.resolve("volume-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));}
        catch(Exception error){GreenMarine.LOGGER.error("Cannot save volume evidence",error);}
        if(registration!=null)registration.close();client.stop();
    }
}
