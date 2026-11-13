package com.koper.greenmarine.client;

import com.koper.greenmarine.api.render.*;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.backend.api.*;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import java.nio.*;
import java.util.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

/** One compilation snapshot; mutable SPIR-V descriptors are never shared across pipeline builds. */
public final class GraphicsStageBuild {
    private static final Map<BackendRenderPipeline.CreateInfo,GraphicsStageBuild> PENDING=Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Deque<GraphicsStageBuild>> ACTIVE=ThreadLocal.withInitial(ArrayDeque::new);
    private final ShaderProgram program;
    private final List<GraphicsSpirv> intermediate=new ArrayList<>(),chain=new ArrayList<>();
    private boolean validated;
    private SpvModule.Reflection vertexReflection;
    private GraphicsStageBuild(ShaderProgram program,BinaryShaderSource source) {
        this.program=program;
        for(var stage:GraphicsStage.values()) {
            if(!stage.intermediate())continue;var declaration=program.stages().get(stage);if(declaration==null)continue;
            byte[] bytes=declaration.binary()!=null?declaration.binary().bytes():compile(stage,declaration.source(),program);
            source.validateIntermediateModule(stage,ByteBuffer.wrap(bytes));
            intermediate.add(new GraphicsSpirv(stage,bytes));
        }
    }
    public static GraphicsStageBuild begin(ShaderSource source) {
        var program=source instanceof BinaryShaderSource binary?binary.graphicsProgram():null;
        GraphicsStageBuild build=program!=null&&!program.stages().isEmpty()?new GraphicsStageBuild(program,(BinaryShaderSource)source):null;
        // A sentinel prevents a nested ordinary pipeline from borrowing its parent's stage chain.
        ACTIVE.get().push(build==null?EMPTY:build);return build;
    }
    private static final GraphicsStageBuild EMPTY=new GraphicsStageBuild();
    private GraphicsStageBuild(){program=null;}
    public static void end(){var stack=ACTIVE.get();stack.pop();if(stack.isEmpty())ACTIVE.remove();}
    public static GraphicsStageBuild current(){var stack=ACTIVE.get();return stack.isEmpty()||stack.peek()==EMPTY?null:stack.peek();}
    public static GraphicsStageBuild take(BackendRenderPipeline.CreateInfo info){return PENDING.remove(info);}
    public static void attach(BackendRenderPipeline.CreateInfo info,GraphicsStageBuild build){if(info!=null&&build!=null)PENDING.put(info,build);}
    public int patchVertices(){return program.inputPatchVertices();}
    public List<GraphicsSpirv> intermediate(){return List.copyOf(intermediate);}
    public List<GraphicsSpirv> chain(){if(!validated)throw new IllegalStateException("Graphics chain has not been validated");return List.copyOf(chain);}
    public void validate(ReferenceArrayList<BackendRenderPipeline.CreateInfo.Shader> shaders) {
        if(validated)return;
        for(var shader:shaders) {
            var type=shader.module().type();if(type==ShaderType.VERTEX){
                chain.add(reflect(GraphicsStage.VERTEX,shader.module()));
                try{vertexReflection=shader.module().reflect();}
                catch(com.mojang.renderpearl.util.ShaderCompileException failure){throw new IllegalArgumentException("Cannot reflect graphics vertex endpoint",failure);}
            }
        }
        chain.addAll(intermediate);
        for(var shader:shaders)if(shader.module().type()==ShaderType.FRAGMENT)chain.add(reflect(GraphicsStage.FRAGMENT,shader.module()));
        GraphicsSpirv.validateChain(chain,patchVertices(),program.pipeline().pushConstantSize());
        if(program.stages().containsKey(GraphicsStage.GEOMETRY)&&patchVertices()==0) {
            var geometry=intermediate.stream().filter(m->m.stage()==GraphicsStage.GEOMETRY).findFirst().orElseThrow();
            boolean compatible=switch(program.pipeline().getPrimitiveTopology()) {
                case TRIANGLES,TRIANGLE_STRIP,TRIANGLE_FAN,QUADS->geometry.mode(22);
                case POINTS->geometry.mode(19);
                case LINES,DEBUG_LINES,DEBUG_LINE_STRIP->geometry.mode(20);
            };
            if(!compatible)throw new IllegalArgumentException("Geometry input primitive differs from host input assembly");
        }
        validated=true;
    }
    private static GraphicsSpirv reflect(GraphicsStage stage,SpvModule module){var b=module.spv().duplicate();byte[] bytes=new byte[b.remaining()];b.get(bytes);return new GraphicsSpirv(stage,bytes);}
    public List<SpvModule.Reflection.Descriptor> descriptors(SpvModule.Reflection reflection) {
        var original=reflection.descriptors();if(reflection!=vertexReflection)return original;
        var union=new ArrayList<>(original);for(var module:intermediate)union.addAll(module.descriptors());return union;
    }
    public int[] visibility(List<BindGroupLayout.UniformDescription> uniforms) {
        var masks=new HashMap<String,Integer>();for(var module:chain())for(var descriptor:module.descriptors())masks.merge(descriptor.name(),module.stage().vulkanBit(),(a,b)->a|b);
        int[] out=new int[uniforms.size()];for(int i=0;i<out.length;i++){out[i]=masks.getOrDefault(uniforms.get(i).name(),0);if(out[i]==0)throw new IllegalArgumentException("Missing graphics descriptor visibility: "+uniforms.get(i).name());}return out;
    }
    private static byte[] compile(GraphicsStage stage,String source,ShaderProgram program) {
        String expanded=ComputeBackend.expand(source,id->program.includes().get(net.minecraft.resources.Identifier.parse(id)));
        long compiler=shaderc_compiler_initialize(),options=0,result=0;
        if(compiler==0)throw new IllegalStateException("Cannot initialize graphics shader compiler");
        try {
            options=shaderc_compile_options_initialize();if(options==0)throw new IllegalStateException("Cannot initialize graphics shader options");
            shaderc_compile_options_set_target_env(options,shaderc_target_env_vulkan,shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_target_spirv(options,shaderc_spirv_version_1_5);
            shaderc_compile_options_set_auto_bind_uniforms(options,true);
            shaderc_compile_options_set_generate_debug_info(options);
            var defines=program.pipeline().getShaderDefines();for(var entry:defines.values().entrySet())shaderc_compile_options_add_macro_definition(options,entry.getKey(),entry.getValue());for(String flag:defines.flags())shaderc_compile_options_add_macro_definition(options,flag,"");
            int kind=switch(stage){case GEOMETRY->shaderc_geometry_shader;case TESS_CONTROL->shaderc_tess_control_shader;case TESS_EVALUATION->shaderc_tess_evaluation_shader;default->throw new IllegalArgumentException("Not an intermediate stage");};
            result=shaderc_compile_into_spv(compiler,expanded,kind,program.pipeline().getLocation()+"."+stage,"main",options);
            if(result==0 || shaderc_result_get_compilation_status(result)!=shaderc_compilation_status_success)throw new IllegalArgumentException("Graphics stage "+stage+" failed: "+(result==0?"no shaderc result":shaderc_result_get_error_message(result)));
            var b=shaderc_result_get_bytes(result);if(b==null || b.remaining()>4*1024*1024)throw new IllegalArgumentException("Invalid graphics stage binary size");byte[] bytes=new byte[b.remaining()];b.get(bytes);return bytes;
        }finally{if(result!=0)shaderc_result_release(result);if(options!=0)shaderc_compile_options_release(options);shaderc_compiler_release(compiler);}
    }
}
