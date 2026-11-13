package com.koper.greenmarine.probe;

import com.google.gson.*;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.api.render.*;
import com.koper.greenmarine.client.*;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real manifest scheduler/renderer: mixed MRT, integer clear, feedback and retained snapshots. */
final class MrtProbe {
    private static final AtomicReference<Object> RESULT=new AtomicReference<>();
    private static MarineRenderer.Registration registration;
    private static AutoCloseable packRegistration;
    private static ShaderSettings saved;
    private static boolean selected,done;private static int ticks;
    private static Boolean savedTransparency;
    static boolean expectsShaderFailure() {
        return Set.of("raster","compute").contains(System.getProperty("greenmarine.probe.uniform_abi_mismatch",""))
            && ShaderRuntime.status().detail().contains("Uniform ABI:");
    }
    static void tick(Minecraft client,Path output) {
        if(done || client.level==null || client.player==null)return;
        try {
            if(!selected) {
                selected=true;
                if(phases()) {savedTransparency=client.options.improvedTransparency().get();client.options.improvedTransparency().set(Boolean.getBoolean("greenmarine.probe.oit"));}
                saved=ResourcePacks.CATALOG.get(ShaderRuntime.settings().pack()).isPresent()?ShaderRuntime.settings():ShaderSettings.defaults();
                var manifest=resource("/assets/probe/greenmarine/packs/mrt_probe.json");
                var json=JsonParser.parseString(manifest).getAsJsonObject();
                String mismatch=System.getProperty("greenmarine.probe.uniform_abi_mismatch","");
                if(!mismatch.isEmpty()) {
                    if(!Set.of("raster","compute").contains(mismatch))throw new IllegalArgumentException("Unknown ABI rejection stage");
                    var blocks=json.getAsJsonObject("resources").getAsJsonObject("uniforms");
                    var broken=blocks.getAsJsonArray("SceneInputs").deepCopy();
                    broken.get(0).getAsJsonObject().addProperty("name","renamedWidth");
                    blocks.add("BrokenInputs",broken);
                    json.getAsJsonArray("passes").get(mismatch.equals("compute")?0:1).getAsJsonObject()
                        .getAsJsonObject("uniforms").addProperty("IrisUniforms","BrokenInputs");
                }
                if(phases())configurePhases(json);
                var definition=ShaderPack.parse(json);
                var shaders=new HashMap<String,String>(Map.of(
                    "probe:core/mrt_accumulate",resource("/assets/probe/shaders/core/mrt_accumulate.fsh"),
                    "compute|probe:core/mrt_uniform_check",resource("/assets/probe/shaders/core/mrt_uniform_check.csh"),
                    "probe:core/mrt_present",resource("/assets/probe/shaders/core/mrt_present.fsh")));
                if(phases())for(int phase=0;phase<PackPass.Phase.values().length;phase++)
                    shaders.put("compute|probe:core/phase_trace_"+phase,resource("/assets/probe/shaders/core/phase_trace.csh").replace("#define PHASE 0","#define PHASE "+phase));
                packRegistration=GreenMarine.PACKS.register(new PackCatalog.LoadedPack(definition,"function update(frame) end",shaders));
                ShaderRuntime.apply(new ShaderSettings("probe:mrt",Quality.HIGH,RayMode.OFF,false,Map.of()));
                if(!GreenMarineClient.enabled())GreenMarineClient.toggleEffect();
                registration=MarineRenderer.register("probe:mrt-check",MarineRenderer.Stage.AFTER_PACKS,MrtProbe::session);
                return;
            }
            if(++ticks>800)throw new IllegalStateException("MRT acceptance timed out");
            if(PackRenderer.failed()) {
                String mismatch=System.getProperty("greenmarine.probe.uniform_abi_mismatch","");
                if(!mismatch.isEmpty() && ShaderRuntime.status().detail().contains("Uniform ABI:")) {
                    var result=new JsonObject();result.addProperty("status","complete");result.addProperty("rejectedIn",mismatch);
                    result.addProperty("detail",ShaderRuntime.status().detail());finish(client,output,result);return;
                }
                throw new IllegalStateException("PackRenderer failed; inspect runtime log");
            }
            if(ShaderRuntime.status().state()==PackStatus.State.FAILED)throw new IllegalStateException("Pack selection failed: "+ShaderRuntime.status());
            if(RESULT.get() instanceof Throwable error)throw new IllegalStateException("MRT acceptance failed",error);
            if(RESULT.get() instanceof JsonObject result) {
                if(!System.getProperty("greenmarine.probe.uniform_abi_mismatch","").isEmpty())throw new IllegalStateException("Mismatched uniform layout was accepted");
                finish(client,output,result);
            }
        } catch(Throwable error) {
            GreenMarine.LOGGER.error("MRT probe failed",error);var result=new JsonObject();result.addProperty("status","failed");result.addProperty("error",error.toString());finish(client,output,result);
        }
    }
    private static boolean phases() {return Boolean.getBoolean("greenmarine.probe.phases");}
    private static void configurePhases(JsonObject json) {
        var passes=json.getAsJsonArray("passes");
        String[] phases={"begin","prepare","deferred","composite","final"};
        for(int i=0;i<phases.length;i++)passes.get(i).getAsJsonObject().addProperty("phase",phases[i]);
        var buffer=new JsonObject();buffer.addProperty("size",64);
        json.getAsJsonObject("resources").getAsJsonObject("buffers").add("PhaseTrace",buffer);
        for(var phase:PackPass.Phase.values()) {
            var pass=new JsonObject();pass.addProperty("name","trace_"+phase.name().toLowerCase(Locale.ROOT));
            pass.addProperty("stage","compute");pass.addProperty("compute","probe:core/phase_trace_"+phase.ordinal());
            pass.addProperty("requires","compute");pass.addProperty("groups","1,1,1");pass.addProperty("phase",phase.name().toLowerCase(Locale.ROOT));
            var uniforms=new JsonObject();uniforms.addProperty("IrisUniforms","SceneInputs");pass.add("uniforms",uniforms);passes.add(pass);
        }
    }
    private static String resource(String name) throws java.io.IOException {
        try(var input=MrtProbe.class.getResourceAsStream(name)) {
            if(input==null)throw new java.io.IOException("Probe resource missing: "+name);
            return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private static java.util.function.Consumer<FrameContext> session(RenderSession session) {
        var buffers=new BufferHandle[]{session.buffer("MRT float readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,ByteBuffer.allocateDirect(8*8*16)),
            session.buffer("MRT uint readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,ByteBuffer.allocateDirect(8*8*4)),
            session.buffer("MRT clear readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,ByteBuffer.allocateDirect(8*8*4)),
            session.buffer("MRT presentation readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,ByteBuffer.allocateDirect(4*16)),
            session.buffer("MRT host uniform readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,ByteBuffer.allocateDirect(16)),
            session.buffer("Phase order readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,ByteBuffer.allocateDirect(64))};
        var presentation=session.texture("MRT presentation sample",TextureSpec.fixed(GpuFormat.RGBA32_FLOAT,4,1),4,1);
        var vertex=Identifier.parse("probe:mrt_check_quad");var fragment=Identifier.parse("probe:mrt_check_present");
        var pipeline=RenderPipeline.builder().withLocation(fragment).withVertexShader(vertex).withFragmentShader(fragment)
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("SceneColor",UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
        var shader=session.compile(new ShaderProgram(pipeline,Map.of(
            vertex,"#version 450\nvoid main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2.-1.,0,1);}",
            fragment,"#version 450\nuniform sampler2D SceneColor;layout(location=0)out vec4 color;void main(){color=texture(SceneColor,vec2(.5));}"),Map.of(),Map.of()));
        var graph=session.graph(RenderGraph.builder().importTexture("sceneColor").importTexture("out")
            .pass(new RenderGraph.Pass("mrt_presentation",Set.of("sceneColor"),Set.of("out"),Set.of(),ctx->ctx.draw(
                ctx.material(shader).texture("SceneColor","sceneColor",FilterMode.NEAREST),List.of("out"),null,null,true))).build());
        var submitted=new boolean[1];
        return frame->{
            if(submitted[0] || PackRenderer.sceneFrames()<4)return;
            submitted[0]=true;
            try {
                long frames=PackRenderer.sceneFrames();var encoder=session.device().createCommandEncoder();
                var field=PackRenderer.class.getDeclaredField("NAMED");field.setAccessible(true);var targets=field.get(null);
                var read=targets.getClass().getDeclaredMethod("read",String.class);read.setAccessible(true);
                var names=List.of("Radiance","Material","FrameClear");
                for(int i=0;i<3;i++) {
                    var view=(GpuTextureView)read.invoke(targets,names.get(i));
                    encoder.copyTextureToBuffer(view.texture(),buffers[i].buffer(),0,()->{},0);
                }
                encoder.copyToBuffer(PackCompute.buffer("UniformCheck").slice(),buffers[4].buffer().slice());
                if(phases())encoder.copyToBuffer(PackCompute.buffer("PhaseTrace").slice(),buffers[5].buffer().slice());
                graph.execute(frame,Map.of("out",presentation));
                int width=frame.width(),height=frame.height();
                encoder.copyTextureToBuffer(presentation.texture(),buffers[3].buffer(),0,()->verify(buffers,frames,width,height),0);
                encoder.submit();
            } catch(Throwable error) {RESULT.set(error);}
        };
    }
    private static void verify(BufferHandle[] buffers,long frames,int width,int height) {
        try(var a=buffers[0].buffer().map(true,false);var b=buffers[1].buffer().map(true,false);var c=buffers[2].buffer().map(true,false);var d=buffers[3].buffer().map(true,false);var e=buffers[4].buffer().map(true,false)) {
            var ad=a.data().order(ByteOrder.LITTLE_ENDIAN);var bd=b.data().order(ByteOrder.LITTLE_ENDIAN);var cd=c.data().order(ByteOrder.LITTLE_ENDIAN);
            int bad=0;
            for(int y=0;y<8;y++)for(int x=0;x<8;x++) {
                int pixel=y*8+x;
                if(ad.getFloat(pixel*16)!=(x==0?.25f:.25f+frames*3))bad++;
                if(bd.getInt(pixel*4)!=(int)(4294967293L+(x==0?0:frames*3)))bad++;
                if(cd.getFloat(pixel*4)!=(x==0?7:10))bad++;
            }
            var presented=d.data().order(ByteOrder.LITTLE_ENDIAN);
            for(int pixel=0;pixel<4;pixel++)for(int component=0;component<4;component++)
                if(Math.abs(presented.getFloat((pixel*4+component)*4)-(component==1 || component==3?1:0))>.001)bad++;
            var result=new JsonObject();result.addProperty("status","complete");result.addProperty("frames",frames);
            var host=e.data().order(ByteOrder.LITTLE_ENDIAN);
            if(host.getFloat(0)!=width || host.getFloat(4)!=height || host.getFloat(8)<0 || host.getFloat(12)!=1)bad++;
            result.addProperty("hostUniformWidth",host.getFloat(0));result.addProperty("hostUniformHeight",host.getFloat(4));
            result.addProperty("hostFrameCounter",host.getFloat(8));result.addProperty("hostUniformsValid",host.getFloat(12)==1);
            var inputConfig=ShaderRuntime.active().definition().uniformInputs();
            result.addProperty("customExpressionDeclarations",inputConfig.expressions().size());
            result.addProperty("sunPathRotation",inputConfig.sunPathRotation());
            if(inputConfig.expressions().size()!=7 || inputConfig.sunPathRotation()!=-35)bad++;
            if(phases())try(var trace=buffers[5].buffer().map(true,false)) {
                var data=trace.data().order(ByteOrder.LITTLE_ENDIAN);
                if(data.getInt(0)!=1 || data.getInt(4)!=frames || data.getInt(8)!=6 || data.getInt(12)!=0 || data.getInt(32)!=1)bad++;
                for(int phase=1;phase<7;phase++)if(data.getInt((8+phase)*4)!=frames)bad++;
                result.addProperty("improvedTransparency",Boolean.getBoolean("greenmarine.probe.oit"));
                result.addProperty("setupExecutions",data.getInt(0));result.addProperty("phaseFrames",data.getInt(4));
                result.addProperty("lastPhase",data.getInt(8));result.addProperty("phaseErrors",data.getInt(12));
                result.addProperty("sharedUniformSnapshot",data.getInt(16)==(int)host.getFloat(8));
                if(data.getInt(16)!=(int)host.getFloat(8))bad++;
            }
            result.addProperty("attachments",3);result.addProperty("pixelsPerAttachment",64);result.addProperty("mismatches",bad);
            result.addProperty("presentationPixels",4);
            if(bad!=0)throw new IllegalStateException(result.toString());RESULT.set(result);
        }catch(Throwable error){RESULT.set(error);}
    }
    private static void finish(Minecraft client,Path output,JsonObject result) {
        done=true;
        try{Files.writeString(output.resolve(phases()?"phase-result.json":"mrt-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));}
        catch(Exception error){GreenMarine.LOGGER.error("Cannot save MRT evidence",error);}
        if(savedTransparency!=null)client.options.improvedTransparency().set(savedTransparency);
        if(registration!=null)registration.close();if(saved!=null)ShaderRuntime.apply(saved);client.stop();
        try{if(packRegistration!=null)packRegistration.close();}catch(Exception ignored){}
    }
}
