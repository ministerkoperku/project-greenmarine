package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.PackUniformBlock;
import com.koper.greenmarine.api.render.UniformLayout;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.util.shaderc.Shaderc.*;

class UniformAbiTest {
    private static PackUniformBlock block(String declarations) {
        return new PackUniformBlock(UniformLayout.parse(declarations).slots().stream()
            .map(slot -> new PackUniformBlock.Member(slot.member().name(),slot.member().type(),slot.member().arrayLength(),slot.member().name())).toList());
    }
    private static ByteBuffer compile(String declarations,int stage,String instance,String access) {
        return compile(declarations,stage,instance,access,false);
    }
    private static ByteBuffer compile(String declarations,int stage,String instance,String access,boolean optimized) {
        String main=switch(stage) {
            case shaderc_vertex_shader -> "void main(){gl_Position=vec4("+access+");}";
            case shaderc_fragment_shader -> "layout(location=0) out vec4 color;void main(){color=vec4("+access+");}";
            default -> "layout(local_size_x=1) in;layout(std430,set=0,binding=1) buffer Output {vec4 color;};void main(){color=vec4("+access+");}";
        };
        String source="#version 460\nstruct Value {float x;};\nlayout(std140,set=0,binding=0) uniform IrisUniforms {"+declarations+"}"+instance+";\n"+main;
        long compiler=shaderc_compiler_initialize(),options=shaderc_compile_options_initialize(),result=0;
        try {
            shaderc_compile_options_set_target_env(options,shaderc_target_env_vulkan,shaderc_env_version_vulkan_1_2);
            if(optimized)shaderc_compile_options_set_optimization_level(options,shaderc_optimization_level_performance);
            result=shaderc_compile_into_spv(compiler,source,stage,"abi.glsl","main",options);
            assertEquals(shaderc_compilation_status_success,shaderc_result_get_compilation_status(result),shaderc_result_get_error_message(result));
            var data=shaderc_result_get_bytes(result);var copy=ByteBuffer.allocate(data.remaining()).order(ByteOrder.LITTLE_ENDIAN);copy.put(data).flip();return copy;
        } finally {if(result!=0)shaderc_result_release(result);shaderc_compile_options_release(options);shaderc_compiler_release(compiler);}
    }

    @Test void reflectsNamedInstancesByBlockTypeForEveryShaderStageWithoutChangingTheInput() {
        for(int stage:new int[]{shaderc_vertex_shader,shaderc_fragment_shader,shaderc_compute_shader}) {
            var original=compile("float gain;vec3 camera;mat2x3 basis;float weights[2];",stage," frame","frame.gain+frame.camera.x+frame.basis[0].x+frame.weights[1]");
            var padded=ByteBuffer.allocate(original.remaining()+8);padded.position(4);padded.put(original);padded.flip().position(4);
            int position=padded.position(),limit=padded.limit();var order=padded.order();
            var reflected=UniformAbi.reflect(padded);
            assertEquals(position,padded.position());assertEquals(limit,padded.limit());assertEquals(order,padded.order());
            assertEquals(Set.of("IrisUniforms"),reflected.keySet());
            var block=reflected.get("IrisUniforms");assertEquals(96,block.byteSize());
            assertEquals(List.of("gain","camera","basis","weights"),block.members().stream().map(UniformAbi.Member::name).toList());
            assertEquals(List.of(0,16,32,64),block.members().stream().map(UniformAbi.Member::offset).toList());
            assertEquals(16,block.members().get(2).matrixStride());assertEquals(16,block.members().get(3).arrayStride());
            UniformAbi.validate(reflected,Map.of("IrisUniforms",block("float gain;vec3 camera;mat2x3 basis;float weights[2];")));
        }
    }

    @Test void acceptsBooleanPhysicalLoweringUnsignedVectorsAndColumnMajorMatrixArrays() {
        String members="bool enabled;bvec2 flags;uint mask;uvec2 indices;mat3x2 transforms[2];";
        var spirv=compile(members,shaderc_compute_shader,"","float(enabled)+float(flags.x)+float(mask+indices.x)+transforms[1][2].x");
        assertDoesNotThrow(() -> UniformAbi.validate(spirv,Map.of("IrisUniforms",block(members))));
        var reflected=UniformAbi.reflect(spirv).get("IrisUniforms");
        assertEquals(48,reflected.members().get(4).arrayStride());assertFalse(reflected.members().get(4).rowMajor());
    }

    @Test void rejectsWrongTypeOrderMissingMembersAndExtraMembersEvenWhenByteSizesMatch() {
        var spirv=compile("float first;int second;vec2 third;",shaderc_fragment_shader,"","first+float(second)+third.x");
        for(String wrong:new String[]{"uint first;int second;vec2 third;","int second;float first;vec2 third;",
            "float first;int second;","float first;int second;vec2 third;float extra;"}) {
            var error=assertThrows(IllegalArgumentException.class,() -> UniformAbi.validate(spirv,Map.of("IrisUniforms",block(wrong))),wrong);
            assertTrue(error.getMessage().contains("IrisUniforms"),error.getMessage());
        }
    }

    @Test void rejectsExplicitOffsetArrayExtentAndRowMajorDifferences() {
        var offset=compile("layout(offset=16) float value;",shaderc_fragment_shader,"","value");
        assertThrows(IllegalArgumentException.class,() -> UniformAbi.validate(offset,Map.of("IrisUniforms",block("float value;"))));
        var array=compile("float values[2];",shaderc_fragment_shader,"","values[1]");
        assertThrows(IllegalArgumentException.class,() -> UniformAbi.validate(array,Map.of("IrisUniforms",block("float values[3];"))));
        var row=compile("layout(row_major) mat2x3 basis;",shaderc_fragment_shader,"","basis[0].x");
        var error=assertThrows(IllegalArgumentException.class,() -> UniformAbi.validate(row,Map.of("IrisUniforms",block("mat2x3 basis;"))));
        assertTrue(error.getMessage().contains("row-major"),error.getMessage());
    }

    @Test void rejectsUnsupportedMappedTypesButIgnoresUnmappedEngineBlocksAndAbsentMappings() {
        var doubles=compile("double value;",shaderc_compute_shader,"","float(value)");
        var error=assertThrows(IllegalArgumentException.class,() -> UniformAbi.validate(doubles,Map.of("IrisUniforms",block("float value;"))));
        assertTrue(error.getMessage().contains("unsupported"),error.getMessage());
        assertDoesNotThrow(() -> UniformAbi.validate(doubles,Map.of()));
        assertDoesNotThrow(() -> UniformAbi.validate(doubles,Map.of("OptimizedAway",block("float value;"))));
        var nested=compile("Value value;",shaderc_compute_shader,"","value.x");
        assertThrows(IllegalArgumentException.class,() -> UniformAbi.validate(nested,Map.of("IrisUniforms",block("float value;"))));
        var multidimensional=compile("float values[2][2];",shaderc_compute_shader,"","values[0][0]");
        assertThrows(IllegalArgumentException.class,() -> UniformAbi.validate(multidimensional,Map.of("IrisUniforms",block("float values[4];"))));
    }

    @Test void alteredSpirvArrayAndMatrixStridesFailAgainstTheActualModule() {
        var array=compile("float values[2];",shaderc_compute_shader,"","values[1]");
        changeDecoration(array,71,6,32); // OpDecorate, DecorationArrayStride
        var arrayError=assertThrows(IllegalArgumentException.class,() -> UniformAbi.validate(array,Map.of("IrisUniforms",block("float values[2];"))));
        assertTrue(arrayError.getMessage().contains("array stride"),arrayError.getMessage());
        var matrix=compile("mat2x3 basis;",shaderc_compute_shader,"","basis[0].x");
        changeDecoration(matrix,72,7,32); // OpMemberDecorate, DecorationMatrixStride
        var matrixError=assertThrows(IllegalArgumentException.class,() -> UniformAbi.validate(matrix,Map.of("IrisUniforms",block("mat2x3 basis;"))));
        assertTrue(matrixError.getMessage().contains("matrix stride"),matrixError.getMessage());
    }

    @Test void optimizedModulesKeepUsedBlockLayoutsAndMayRemoveUnusedBlocks() {
        String declarations="float first;float unused;float last;";
        var used=compile(declarations,shaderc_compute_shader," frame","frame.last",true);
        assertDoesNotThrow(() -> UniformAbi.validate(used,Map.of("IrisUniforms",block(declarations))));
        var unused=compile(declarations,shaderc_compute_shader," frame","1.0",true);
        assertTrue(UniformAbi.reflect(unused).isEmpty());
        assertDoesNotThrow(() -> UniformAbi.validate(unused,Map.of("IrisUniforms",block(declarations))));
    }

    @Test void malformedSpirvFailsBeforeAnyNativeReflection() {
        for(var bad:List.of(ByteBuffer.allocate(0),ByteBuffer.allocate(19),ByteBuffer.allocate(24)))
            assertThrows(IllegalArgumentException.class,() -> UniformAbi.reflect(bad));
    }

    private static void changeDecoration(ByteBuffer spirv,int opcode,int decoration,int replacement) {
        var words=spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        for(int at=5;at<words.limit();) {
            int instruction=words.get(at),count=instruction>>>16;
            int kind=opcode==71?2:3,value=kind+1;
            if((instruction&0xffff)==opcode && count>value && words.get(at+kind)==decoration) {words.put(at+value,replacement);return;}
            at+=count;
        }
        fail("Missing decoration "+decoration);
    }
}
