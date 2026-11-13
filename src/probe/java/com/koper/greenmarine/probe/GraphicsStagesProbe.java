package com.koper.greenmarine.probe;

import com.google.gson.*;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.render.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.*;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real host-device draws exercise both pipeline variants and intermediate-only descriptor discovery. */
final class GraphicsStagesProbe {
    private static final int SIZE=8,BYTES=SIZE*SIZE*16;
    private static final AtomicReference<Object> RESULT=new AtomicReference<>();
    private static MarineRenderer.Registration registration;
    private static int ticks;
    private static boolean done;
    private static final String VERTEX="""
        #version 450
        layout(std140,set=0,binding=7) uniform Shared {vec4 base;};
        layout(location=0) out DATA {vec4 value;} d;
        void main(){vec2 p=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(p*2.-1.,.5,1);d.value=base;}
        """;
    private static final String CONTROL="""
        #version 450
        layout(vertices=3) out;
        layout(std140,set=0,binding=9) uniform ControlOnly {vec4 tint;};
        layout(location=0) in DATA {vec4 value;} d[];
        layout(location=0) out DATA {vec4 value;} e[];
        void main(){e[gl_InvocationID].value=d[gl_InvocationID].value+tint;
            gl_out[gl_InvocationID].gl_Position=gl_in[gl_InvocationID].gl_Position;
            if(gl_InvocationID==0){gl_TessLevelOuter[0]=1;gl_TessLevelOuter[1]=1;gl_TessLevelOuter[2]=1;gl_TessLevelInner[0]=1;}}
        """;
    private static final String EVALUATION="""
        #version 450
        layout(triangles,fractional_odd_spacing,ccw) in;
        layout(std140,set=0,binding=9) uniform EvaluationOnly {vec4 tint;};
        layout(location=0) in DATA {vec4 value;} d[];
        layout(location=0) out DATA {vec4 value;} e;
        void main(){e.value=d[0].value+tint;gl_Position=gl_TessCoord.x*gl_in[0].gl_Position+gl_TessCoord.y*gl_in[1].gl_Position+gl_TessCoord.z*gl_in[2].gl_Position;}
        """;
    private static final String GEOMETRY="""
        #version 450
        layout(triangles) in;layout(triangle_strip,max_vertices=3) out;
        layout(std140,set=0,binding=9) uniform GeometryOnly {vec4 tint;};
        layout(location=0) in DATA {vec4 value;} d[];
        layout(location=0) out DATA {vec4 value;} e;
        void main(){for(int i=0;i<3;i++){e.value=d[i].value+tint;gl_Position=gl_in[i].gl_Position;EmitVertex();}EndPrimitive();}
        """;
    private static final String FRAGMENT="""
        #version 450
        layout(std140,set=0,binding=7) uniform Shared {vec4 base;};
        layout(location=0) in DATA {vec4 value;} d;
        layout(location=0) out vec4 color;
        void main(){color=vec4(d.value.rgb,base.w);}
        """;
    static void tick(Minecraft client,Path output) {
        if(done)return;
        try {
            if(registration==null){registration=MarineRenderer.register("probe:graphics-stages",MarineRenderer.Stage.AFTER_PACKS,GraphicsStagesProbe::session);return;}
            if(++ticks>800)throw new IllegalStateException("Graphics stages probe timed out");
            if(RESULT.get() instanceof Throwable failure)throw new IllegalStateException("Stage GPU acceptance failed",failure);
            if(MarineRenderer.status().stream().anyMatch(s->s.id().equals("probe:graphics-stages")&&s.failed()))throw new IllegalStateException("Stage contribution failed; inspect log");
            if(RESULT.get() instanceof JsonObject result)finish(client,output,result);
        }catch(Throwable failure){GreenMarine.LOGGER.error("Graphics stages probe failed",failure);var result=new JsonObject();result.addProperty("status","failed");result.addProperty("error",failure.toString());finish(client,output,result);}
    }
    private static ShaderProgram definition(String name,boolean tessellation) {
        var id=Identifier.parse("probe:stages_"+name);
        var bindings=BindGroupLayout.builder().withUniform("Shared",UniformType.UNIFORM_BUFFER).withUniform("GeometryOnly",UniformType.UNIFORM_BUFFER);
        if(tessellation)bindings.withUniform("ControlOnly",UniformType.UNIFORM_BUFFER).withUniform("EvaluationOnly",UniformType.UNIFORM_BUFFER);
        var pipeline=RenderPipeline.builder().withLocation(id).withVertexShader(Identifier.parse("probe:stage_vertex"))
            .withFragmentShader(Identifier.parse("probe:stage_fragment")).withBindGroupLayout(bindings.build())
            .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
        var stages=new EnumMap<GraphicsStage,ShaderStageSource>(GraphicsStage.class);
        stages.put(GraphicsStage.GEOMETRY,new ShaderStageSource(GEOMETRY));
        if(tessellation){stages.put(GraphicsStage.TESS_CONTROL,new ShaderStageSource(CONTROL));stages.put(GraphicsStage.TESS_EVALUATION,new ShaderStageSource(EVALUATION));}
        return new ShaderProgram(pipeline,Map.of(Identifier.parse("probe:stage_vertex"),VERTEX,Identifier.parse("probe:stage_fragment"),FRAGMENT),Map.of(),Map.of()).withStages(stages,tessellation?3:0);
    }
    private static java.util.function.Consumer<FrameContext> session(RenderSession session) {
        boolean rejected=false;
        try {session.compile(definition("invalid",false).withStages(Map.of(GraphicsStage.GEOMETRY,new ShaderStageSource(GEOMETRY.replace("out DATA {vec4 value;}","out DATA {vec3 value;}").replace("e.value=d[i].value+tint","e.value=(d[i].value+tint).xyz"))),0));}
        catch(RuntimeException expected){rejected=true;}
        if(!rejected)throw new IllegalStateException("Mismatched chain was accepted");
        var programs=List.of(session.compile(definition("geometry",false)),session.compile(definition("tess_geometry",true)));
        var colors=new ArrayList<TextureHandle>();var buffers=new ArrayList<BufferHandle>();
        for(int i=0;i<4;i++) {colors.add(session.texture("stage output "+i,TextureSpec.fixed(GpuFormat.RGBA32_FLOAT,SIZE,SIZE),SIZE,SIZE));buffers.add(session.buffer("stage readback "+i,GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,ByteBuffer.allocateDirect(BYTES)));}
        var depth=session.texture("stage depth",TextureSpec.fixed(GpuFormat.D32_FLOAT,SIZE,SIZE),SIZE,SIZE);
        var submitted=new boolean[1];
        return frame->{
            if(submitted[0])return;submitted[0]=true;
            try {
                var encoder=session.device().createCommandEncoder();
                for(int i=0;i<4;i++) {
                    var program=programs.get(i/2);boolean withDepth=i%2==1;
                    var descriptor=RenderPassDescriptor.builder(()->"GreenMarine stages acceptance").withColorAttachment(colors.get(i).view(),Optional.of(new Vector4f()));
                    if(withDepth)descriptor.withDepthAttachment(depth.view(),OptionalDouble.of(1));
                    try(var pass=encoder.createRenderPass(descriptor.build())) {
                        pass.setPipeline(program.pipeline());
                        pass.setUniform("Shared",upload(encoder,.125f,.125f,.125f,1));
                        pass.setUniform("GeometryOnly",upload(encoder,.125f,.25f,.375f,0));
                        if(i>=2){pass.setUniform("ControlOnly",upload(encoder,.125f,.125f,.125f,0));pass.setUniform("EvaluationOnly",upload(encoder,.125f,.125f,.125f,0));}
                        pass.draw(3,1,0,0);
                    }
                    final int index=i;
                    encoder.copyTextureToBuffer(colors.get(i).texture(),buffers.get(i).buffer(),0,()->{if(index==3)verify(buffers,session.device().getDeviceInfo().toString());},0);
                }
                encoder.submit();
            }catch(Throwable failure){RESULT.set(failure);}
        };
    }
    private static com.mojang.renderpearl.api.buffers.GpuBufferSlice upload(com.mojang.renderpearl.api.commands.CommandEncoder encoder,float x,float y,float z,float w) {
        var bytes=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());bytes.putFloat(x).putFloat(y).putFloat(z).putFloat(w).flip();
        return encoder.transientMemory().uploadGpu(bytes,256,GpuBuffer.USAGE_UNIFORM);
    }
    private static void verify(List<BufferHandle> buffers,String device) {
        try {
            int mismatches=0;
            for(int i=0;i<4;i++)try(var mapping=buffers.get(i).buffer().map(true,false)) {
                var bytes=mapping.data().order(ByteOrder.nativeOrder());float extra=i>=2?.25f:0;float[] expected={.25f+extra,.375f+extra,.5f+extra,1};
                for(int pixel=0;pixel<SIZE*SIZE;pixel++)for(int component=0;component<4;component++)if(Math.abs(bytes.getFloat((pixel*4+component)*4)-expected[component])>.00001f)mismatches++;
            }
            var result=new JsonObject();result.addProperty("status","complete");result.addProperty("device",device);result.addProperty("draws",4);result.addProperty("pixels",SIZE*SIZE*4);result.addProperty("mismatches",mismatches);result.addProperty("geometry",true);result.addProperty("tessellationGeometry",true);result.addProperty("depthAndNoDepth",true);result.addProperty("intermediateOnlyUniforms",3);result.addProperty("failureRecovery",true);
            if(mismatches!=0)throw new IllegalStateException(result.toString());RESULT.set(result);
        }catch(Throwable failure){RESULT.set(failure);}
    }
    private static void finish(Minecraft client,Path output,JsonObject result) {
        done=true;
        try{Files.writeString(output.resolve("graphics-stages-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));}catch(Exception failure){GreenMarine.LOGGER.error("Cannot save stages evidence",failure);}
        if(registration!=null)registration.close();client.stop();
    }
}
