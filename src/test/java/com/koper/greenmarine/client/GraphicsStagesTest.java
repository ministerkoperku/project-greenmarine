package com.koper.greenmarine.client;

import com.koper.greenmarine.api.render.*;
import com.koper.greenmarine.api.pack.ShaderBinary;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

class GraphicsStagesTest {
    static byte[] compile(GraphicsStage stage,String source) {
        long c=shaderc_compiler_initialize(),o=shaderc_compile_options_initialize(),r=0;
        try {
            shaderc_compile_options_set_target_env(o,shaderc_target_env_vulkan,shaderc_env_version_vulkan_1_2);
            int kind=switch(stage) {case VERTEX->shaderc_vertex_shader;case FRAGMENT->shaderc_fragment_shader;case GEOMETRY->shaderc_geometry_shader;case TESS_CONTROL->shaderc_tess_control_shader;case TESS_EVALUATION->shaderc_tess_evaluation_shader;};
            r=shaderc_compile_into_spv(c,"#version 450\n"+source,kind,"stages.glsl","main",o);
            assertEquals(shaderc_compilation_status_success,shaderc_result_get_compilation_status(r),shaderc_result_get_error_message(r));
            var data=shaderc_result_get_bytes(r);byte[] bytes=new byte[data.remaining()];data.get(bytes);return bytes;
        }finally{if(r!=0)shaderc_result_release(r);shaderc_compile_options_release(o);shaderc_compiler_release(c);}
    }
    static final String VERTEX="layout(location=0) out DATA {mat2 basis;vec3 values[2];flat int tag;} d;void main(){gl_Position=vec4(0);d.basis=mat2(1);d.values[0]=vec3(1);d.values[1]=vec3(2);d.tag=3;}";
    static final String GEOMETRY="layout(triangles) in;layout(triangle_strip,max_vertices=3) out;layout(location=0) in DATA {mat2 basis;vec3 values[2];flat int tag;} d[];layout(location=0) out DATA {mat2 basis;vec3 values[2];flat int tag;} e;layout(set=0,binding=9,std140) uniform GeometryOnly {vec4 tint;};void main(){for(int i=0;i<3;i++){e.basis=d[i].basis;e.values=d[i].values;e.tag=d[i].tag;gl_Position=gl_in[i].gl_Position+tint;EmitVertex();}EndPrimitive();}";
    static final String FRAGMENT="layout(location=0) in DATA {mat2 basis;vec3 values[2];flat int tag;} d;layout(location=0) out vec4 color;void main(){color=vec4(d.values[0]+d.values[1]+vec3(d.basis[0],float(d.tag)),1);}";
    @Test void stageBinaryChecksActualEntryModelAndPreservesFragmentOnlyContract() {
        byte[] g=compile(GraphicsStage.GEOMETRY,GEOMETRY);
        assertDoesNotThrow(()->new StageBinary(GraphicsStage.GEOMETRY,g));
        assertThrows(IllegalArgumentException.class,()->new StageBinary(GraphicsStage.TESS_CONTROL,g));
        assertThrows(IllegalArgumentException.class,()->new ShaderBinary(g));
        byte[] copy=new StageBinary(GraphicsStage.GEOMETRY,g).bytes();copy[0]=0;
        assertNotEquals(0,new StageBinary(GraphicsStage.GEOMETRY,g).bytes()[0]);
    }
    @Test void validatesStructMatrixArrayChainAndRemapsIntermediateOnlyDescriptors() {
        var v=new GraphicsSpirv(GraphicsStage.VERTEX,compile(GraphicsStage.VERTEX,VERTEX));
        var g=new GraphicsSpirv(GraphicsStage.GEOMETRY,compile(GraphicsStage.GEOMETRY,GEOMETRY));
        var f=new GraphicsSpirv(GraphicsStage.FRAGMENT,compile(GraphicsStage.FRAGMENT,FRAGMENT));
        assertDoesNotThrow(()->GraphicsSpirv.validateChain(List.of(v,g,f),0,0));
        assertEquals(5,g.outputs().size());
        var descriptor=g.descriptors().getFirst();assertEquals("GeometryOnly",descriptor.name());assertEquals(9,descriptor.binding());
        descriptor.binding(2);descriptor.descriptorSetIndex(0);
        assertEquals(2,new GraphicsSpirv(GraphicsStage.GEOMETRY,g.bytes()).descriptors().getFirst().binding());
        var wrong=new GraphicsSpirv(GraphicsStage.FRAGMENT,compile(GraphicsStage.FRAGMENT,FRAGMENT.replace("vec3 values[2]","vec2 values[2]").replace("d.values[0]+d.values[1]","vec3(d.values[0]+d.values[1],0)")));
        assertThrows(IllegalArgumentException.class,()->GraphicsSpirv.validateChain(List.of(v,g,wrong),0,0));
    }
    @Test void tessellationStripsOnlyImplicitVertexArraysAndRequiresBothStages() {
        String block="{vec3 value;}",v="layout(location=0) out D "+block+" d;void main(){d.value=vec3(1);gl_Position=vec4(0);}";
        String c="layout(vertices=3) out;layout(location=0) in D "+block+" d[];layout(location=0) out D "+block+" e[];void main(){e[gl_InvocationID].value=d[gl_InvocationID].value;gl_out[gl_InvocationID].gl_Position=gl_in[gl_InvocationID].gl_Position;gl_TessLevelOuter[0]=2;gl_TessLevelOuter[1]=2;gl_TessLevelOuter[2]=2;gl_TessLevelInner[0]=2;}";
        String e="layout(triangles,equal_spacing,ccw) in;layout(location=0) in D "+block+" d[];layout(location=0) out D "+block+" e;void main(){e.value=d[0].value;gl_Position=gl_in[0].gl_Position;}";
        String f="layout(location=0) in D "+block+" d;layout(location=0) out vec4 color;void main(){color=vec4(d.value,1);}";
        var vs=new GraphicsSpirv(GraphicsStage.VERTEX,compile(GraphicsStage.VERTEX,v));
        var tc=new GraphicsSpirv(GraphicsStage.TESS_CONTROL,compile(GraphicsStage.TESS_CONTROL,c));
        var te=new GraphicsSpirv(GraphicsStage.TESS_EVALUATION,compile(GraphicsStage.TESS_EVALUATION,e));
        var fs=new GraphicsSpirv(GraphicsStage.FRAGMENT,compile(GraphicsStage.FRAGMENT,f));
        assertDoesNotThrow(()->GraphicsSpirv.validateChain(List.of(vs,tc,te,fs),3,0));
        assertThrows(IllegalArgumentException.class,()->GraphicsSpirv.validateChain(List.of(vs,te,fs),3,0));
        assertThrows(IllegalArgumentException.class,()->GraphicsSpirv.validateChain(List.of(vs,tc,te,fs),0,0));
    }
    @Test void rejectsMissingProducersAndIntermediatePushConstantsBeyondHostRange() {
        var v=new GraphicsSpirv(GraphicsStage.VERTEX,compile(GraphicsStage.VERTEX,"void main(){gl_Position=vec4(0);}"));
        var g=new GraphicsSpirv(GraphicsStage.GEOMETRY,compile(GraphicsStage.GEOMETRY,GEOMETRY));
        var f=new GraphicsSpirv(GraphicsStage.FRAGMENT,compile(GraphicsStage.FRAGMENT,FRAGMENT));
        assertThrows(IllegalArgumentException.class,()->GraphicsSpirv.validateChain(List.of(v,g,f),0,0));
        var output=new GraphicsSpirv(GraphicsStage.VERTEX,compile(GraphicsStage.VERTEX,VERTEX));
        var push=new GraphicsSpirv(GraphicsStage.GEOMETRY,compile(GraphicsStage.GEOMETRY,GEOMETRY.replace("layout(set=0,binding=9,std140) uniform GeometryOnly", "layout(push_constant) uniform GeometryOnly")));
        assertEquals(16,push.pushConstantSize());
        assertThrows(IllegalArgumentException.class,()->GraphicsSpirv.validateChain(List.of(output,push,f),0,0));
        assertDoesNotThrow(()->GraphicsSpirv.validateChain(List.of(output,push,f),0,16));
    }
    @Test void declarationsRetainLegacySourcesAndRejectMismatchedBinaryStages() {
        var vertex=net.minecraft.resources.Identifier.parse("probe:stages_vertex");
        var fragment=net.minecraft.resources.Identifier.parse("probe:stages_fragment");
        var pipeline=com.mojang.renderpearl.api.pipeline.RenderPipeline.builder().withLocation(fragment)
            .withVertexShader(vertex).withFragmentShader(fragment).withPrimitiveTopology(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES).build();
        var original=new ShaderProgram(pipeline,Map.of(vertex,"vertex fallback",fragment,"fragment fallback"),Map.of(),Map.of());
        var extended=original.withStages(Map.of(GraphicsStage.GEOMETRY,new ShaderStageSource(GEOMETRY)),0);
        assertTrue(original.stages().isEmpty());assertEquals(1,extended.stages().size());
        assertEquals("fragment fallback",extended.source(fragment,com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT));
        assertThrows(UnsupportedOperationException.class,()->extended.stages().clear());
        var binary=new StageBinary(GraphicsStage.GEOMETRY,compile(GraphicsStage.GEOMETRY,GEOMETRY));
        assertThrows(IllegalArgumentException.class,()->original.withStages(Map.of(GraphicsStage.TESS_CONTROL,new ShaderStageSource(GEOMETRY,binary)),3));
        assertThrows(IllegalArgumentException.class,()->original.withStages(Map.of(GraphicsStage.TESS_CONTROL,new ShaderStageSource(GEOMETRY)),3));
    }

    @Test void geometryLimitsIncludePositionAndOtherPerVertexBuiltins() {
        var geometry=new GraphicsSpirv(GraphicsStage.GEOMETRY,compile(GraphicsStage.GEOMETRY,GEOMETRY));
        int declared=geometry.outputs().values().stream().mapToInt(GraphicsSpirv.Value::vector).sum();
        var storage=org.lwjgl.system.MemoryUtil.memCalloc(org.lwjgl.vulkan.VkPhysicalDeviceLimits.SIZEOF);
        try {
            var limits=org.lwjgl.vulkan.VkPhysicalDeviceLimits.create(org.lwjgl.system.MemoryUtil.memAddress(storage));
            org.lwjgl.system.MemoryUtil.memPutInt(limits.address()+org.lwjgl.vulkan.VkPhysicalDeviceLimits.MAXGEOMETRYOUTPUTVERTICES,3);
            org.lwjgl.system.MemoryUtil.memPutInt(limits.address()+org.lwjgl.vulkan.VkPhysicalDeviceLimits.MAXGEOMETRYSHADERINVOCATIONS,1);
            org.lwjgl.system.MemoryUtil.memPutInt(limits.address()+org.lwjgl.vulkan.VkPhysicalDeviceLimits.MAXGEOMETRYINPUTCOMPONENTS,128);
            org.lwjgl.system.MemoryUtil.memPutInt(limits.address()+org.lwjgl.vulkan.VkPhysicalDeviceLimits.MAXGEOMETRYOUTPUTCOMPONENTS,128);
            org.lwjgl.system.MemoryUtil.memPutInt(limits.address()+org.lwjgl.vulkan.VkPhysicalDeviceLimits.MAXGEOMETRYTOTALOUTPUTCOMPONENTS,declared*3);
            assertThrows(IllegalArgumentException.class,()->GraphicsStageFeatures.validateLimits(geometry,limits));
        }finally{org.lwjgl.system.MemoryUtil.memFree(storage);}
    }

}
