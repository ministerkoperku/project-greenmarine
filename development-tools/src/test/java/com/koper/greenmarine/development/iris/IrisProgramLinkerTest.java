package com.koper.greenmarine.development.iris;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class IrisProgramLinkerTest {
    @Test void oneProgramHasOneUniformAndDescriptorAbiRegardlessOfStageDeclarationOrder() throws Exception {
        var input = Map.of(
            IrisStage.VERTEX, "#version 330\nuniform float vertexOnly; uniform mat4 sharedMatrix; uniform sampler2D zebra; uniform sampler2D sharedTexture;\nout vec2 uv; void main(){ uv=vec2(vertexOnly); gl_Position=sharedMatrix*vec4(texture(sharedTexture,uv).x); }",
            IrisStage.FRAGMENT, "#version 330\nuniform float fragmentOnly; uniform mat4 sharedMatrix; uniform sampler2D sharedTexture; uniform sampler2D alpha;\nin vec2 uv; out vec4 color; void main(){ color=texture(sharedTexture,uv)+vec4(fragmentOnly)+sharedMatrix[0]; }");
        var program = IrisProgramLinker.link(input);
        var stages = program.stages();
        var vs = stages.get(IrisStage.VERTEX); var fs = stages.get(IrisStage.FRAGMENT);
        assertEquals(vs.uniforms(), fs.uniforms());
        assertEquals(List.of("fragmentOnly", "sharedMatrix", "vertexOnly"), vs.uniforms().stream().map(IrisTranslator.Uniform::name).toList());
        assertEquals(binding(vs, "sharedTexture"), binding(fs, "sharedTexture"));
        assertTrue(vs.text().contains("layout(location = 0) out vec2 uv;"), vs.text());
        assertTrue(fs.text().contains("layout(location = 0) in vec2 uv;"), fs.text());
        var reflected = new ArrayList<Map<String, Integer>>();
        for (var stage : stages.entrySet()) {
            var compiled = IrisCompiler.compileExplicit(stage.getValue().text(), stage.getKey(), "linked." + stage.getKey().extension);
            assertTrue(compiled.success(), compiled.diagnostics() + "\n" + stage.getValue().text());
            reflected.add(decorations(compiled.spirv()));
        }
        for (var layout : reflected) {
            assertEquals(0, layout.get("fragmentOnly.offset"));
            assertEquals(16, layout.get("sharedMatrix.offset"));
            assertEquals(80, layout.get("vertexOnly.offset"));
            assertEquals(2, layout.get("sharedTexture.binding"));
            assertEquals(0, layout.get("uv.location"));
        }
    }
    @Test void matricesAndArraysConsumeCompleteLocationRangesAndExplicitDeclarationsSurvive() {
        var program = IrisProgramLinker.link(Map.of(
            IrisStage.VERTEX, "#version 450\nlayout(location=5) flat out mat3 basis;\nout vec4 samples[2]; out vec2 uv; void main(){gl_Position=vec4(0); basis=mat3(1); samples[0]=vec4(1); samples[1]=vec4(2); uv=vec2(0);}",
            IrisStage.FRAGMENT, "#version 450\nin vec2 uv; in vec4 samples[2]; layout(location=5) flat in mat3 basis; out vec4 color; void main(){color=samples[0]+vec4(basis[0],uv.x);}"));
        var ranges = program.varyings().stream().collect(java.util.stream.Collectors.toMap(IrisProgramLinker.Varying::name, v -> List.of(v.location(),v.locations())));
        assertEquals(List.of(5,3), ranges.get("basis"));
        assertEquals(List.of(0,2), ranges.get("samples"));
        assertEquals(List.of(2,1), ranges.get("uv"));
        assertTrue(program.stages().get(IrisStage.VERTEX).text().contains("layout(location=5) flat out mat3 basis;"));
        assertCompiled(program);
    }
    @Test void geometryStripsOnlyItsImplicitVertexArrayDimension() {
        var program = IrisProgramLinker.link(Map.of(
            IrisStage.VERTEX, "#version 450\nout vec3 values[2]; void main(){values[0]=vec3(1); values[1]=vec3(2); gl_Position=vec4(0);}",
            IrisStage.GEOMETRY, "#version 450\nlayout(points) in; layout(points,max_vertices=1) out; in vec3 values[][2]; flat out vec3 result; void main(){result=values[0][1]; gl_Position=gl_in[0].gl_Position; EmitVertex(); EndPrimitive();}",
            IrisStage.FRAGMENT, "#version 450\nflat in vec3 result; out vec4 color; void main(){color=vec4(result,1);}"));
        assertEquals(2, program.varyings().getFirst().locations());
        assertEquals("[2]", program.varyings().getFirst().array());
        assertCompiled(program);
    }
    @Test void tessellationArrayAndPatchInterfacesCompileWithMatchedLocations() {
        var program = IrisProgramLinker.link(Map.of(
            IrisStage.VERTEX, "#version 450\nout vec3 value; void main(){value=vec3(1);gl_Position=vec4(0);}",
            IrisStage.TESS_CONTROL, "#version 450\nlayout(vertices=3) out; in vec3 value[]; out vec3 result[]; patch out float factor; void main(){result[gl_InvocationID]=value[gl_InvocationID];gl_out[gl_InvocationID].gl_Position=gl_in[gl_InvocationID].gl_Position; if(gl_InvocationID==0){factor=1; gl_TessLevelOuter[0]=1;gl_TessLevelOuter[1]=1;gl_TessLevelOuter[2]=1;gl_TessLevelInner[0]=1;}}",
            IrisStage.TESS_EVALUATION, "#version 450\nlayout(triangles,equal_spacing,cw) in; in vec3 result[]; patch in float factor; out vec3 finalValue; void main(){finalValue=result[0]*factor;gl_Position=gl_in[0].gl_Position;}",
            IrisStage.FRAGMENT, "#version 450\nin vec3 finalValue; out vec4 color;void main(){color=vec4(finalValue,1);}"));
        assertEquals(4, program.varyings().size());
        assertCompiled(program);
    }
    @Test void rejectsInterfaceMismatchesInsteadOfSilentlyAssigningStageLocalLocations() {
        String vertex = "#version 450\nflat out vec3 value;void main(){gl_Position=vec4(0);value=vec3(0);}";
        for (String input : List.of("in vec3 value;", "flat in vec2 value;", "flat in vec3 missing;", "layout(location=1) flat in vec3 value[2];")) {
            assertThrows(IllegalArgumentException.class, () -> IrisProgramLinker.link(Map.of(IrisStage.VERTEX, vertex,
                IrisStage.FRAGMENT, "#version 450\n"+input+"out vec4 color;void main(){color=vec4(1);}")), input);
        }
        assertThrows(IllegalArgumentException.class, () -> IrisProgramLinker.link(Map.of(IrisStage.VERTEX,
            "#version 450\nout Data {layout(location=2) vec3 value;} data;void main(){gl_Position=vec4(0);}")));
    }
    @Test void explicitLocationsAndBindingsCannotCollideOrDisagree() {
        String vertex = "#version 450\nlayout(location=2) out mat3 basis; void main(){gl_Position=vec4(0);basis=mat3(1);}";
        assertThrows(IllegalArgumentException.class, () -> IrisProgramLinker.link(Map.of(IrisStage.VERTEX, vertex,
            IrisStage.FRAGMENT,"#version 450\nlayout(location=3) in mat3 basis; out vec4 color;void main(){color=vec4(1);}")));
        assertThrows(IllegalArgumentException.class, () -> IrisProgramLinker.link(Map.of(IrisStage.VERTEX,
            vertex.replace("void main", "layout(location=4) out float overlaps; void main"))));
        assertThrows(IllegalArgumentException.class, () -> IrisProgramLinker.link(Map.of(IrisStage.COMPUTE,
            "#version 450\nlayout(local_size_x=1) in;layout(binding=1) uniform sampler2D a;layout(binding=1) uniform sampler2D b;void main(){}")));
    }
    @Test void explicitCompilerNeverInventsMissingStageInterfaceLocations() throws Exception {
        var result = IrisCompiler.compileExplicit("#version 450\nout vec2 uv;void main(){uv=vec2(0);gl_Position=vec4(0);}",IrisStage.VERTEX,"missing.vsh");
        assertFalse(result.success(), "Strict program compilation must reject undeclared locations");
    }
    @Test void namedInterfaceBlocksMatchByBlockNameAndKeepInstanceNamesAndMemberInterpolation() {
        var program = IrisProgramLinker.link(Map.of(
            IrisStage.VERTEX, "#version 450\nout DATA {vec2 uv; flat int material; mat3 basis;} vertexData; void main(){gl_Position=vec4(0);vertexData.uv=vec2(0);vertexData.material=1;vertexData.basis=mat3(1);}",
            IrisStage.FRAGMENT, "#version 450\nin DATA {vec2 uv; flat int material; mat3 basis;} fragmentData; out vec4 color;void main(){color=vec4(fragmentData.uv,float(fragmentData.material),fragmentData.basis[0].x);}"));
        assertEquals(5, program.varyings().getFirst().locations());
        assertEquals("DATA", program.varyings().getFirst().name());
        assertTrue(program.stages().get(IrisStage.VERTEX).text().contains("vertexData;"));
        assertTrue(program.stages().get(IrisStage.FRAGMENT).text().contains("fragmentData;"));
        assertCompiled(program);
    }
    @Test void rejectsDifferentImageFormatsAcrossStagesBeforeCreatingAnInvalidDescriptorAbi() {
        assertThrows(IllegalArgumentException.class, () -> IrisProgramLinker.link(Map.of(
            IrisStage.VERTEX, "#version 450\nlayout(rgba8) uniform readonly image2D imageData;void main(){gl_Position=vec4(0);}",
            IrisStage.FRAGMENT, "#version 450\nlayout(r32f) uniform readonly image2D imageData;out vec4 color;void main(){color=vec4(0);}")));
    }
    @Test void linkedRealPackProgramsWhenConfigured() throws Exception {
        String pack = System.getProperty("greenmarine.iris.pack");
        org.junit.jupiter.api.Assumptions.assumeTrue(pack != null && !pack.isBlank());
        var plan = IrisPipelinePlan.parse(IrisSources.load(Path.of(pack)));
        var blockers = new ArrayList<String>(); var integrationBlockers = new ArrayList<String>();
        int programs = 0, stages = 0, integrations = 0;
        for (var world : plan.worlds()) for (var program : world.programs()) {
            if (!program.enabled()) continue;
            var groups = new ArrayList<Map<IrisStage, String>>();
            var graphics = new EnumMap<IrisStage,String>(IrisStage.class);
            program.stages().forEach((kind, stage) -> graphics.put(kind, stage.preprocessedText()));
            if (!graphics.isEmpty()) groups.add(graphics);
            for (var compute : program.computes()) groups.add(Map.of(IrisStage.COMPUTE, compute.preprocessedText()));
            for (var group : groups) {
                programs++; stages += group.size(); if (program.optionalIntegration()) integrations++;
                try {
                    var linked = IrisProgramLinker.link(group);
                    for (var stage : linked.stages().entrySet()) {
                        var compiled = IrisCompiler.compileExplicit(stage.getValue().text(),stage.getKey(),world.folder()+"/"+program.name()+"."+stage.getKey().extension);
                        if (!compiled.success()) (program.optionalIntegration() ? integrationBlockers : blockers).add(world.folder()+"/"+program.name()+" "+stage.getKey()+": "+compiled.diagnostics());
                    }
                }
                catch (RuntimeException error) {
                    (program.optionalIntegration() ? integrationBlockers : blockers).add(world.folder()+"/"+program.name()+": "+error.getMessage());
                }
            }
        }
        System.out.println("IR-2 linked fixture programs="+programs+", stages="+stages+", integrationPrograms="+integrations+", blockers="+blockers.size()+", integrationBlockers="+integrationBlockers.size());
        if (!integrationBlockers.isEmpty()) System.out.println("Optional host integration blockers:\n"+String.join("\n",integrationBlockers));
        assertTrue(blockers.isEmpty(), String.join("\n",blockers));
    }
    /** Minimal SPIR-V reflection: test the actual compiled ABI, including std140 member offsets. */
    private static Map<String,Integer> decorations(byte[] spirv) {
        var words = ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        var names = new HashMap<Integer,String>(); var members = new HashMap<String,String>();
        var pointerTargets = new HashMap<Integer,Integer>(); var variableTypes = new HashMap<Integer,Integer>();
        var decorated = new ArrayList<int[]>(); var result = new HashMap<String,Integer>();
        for (int at=5;at<words.limit();) {
            int header=words.get(at), size=header>>>16, op=header&0xffff;
            if (op==5) names.put(words.get(at+1), string(words,at+2,at+size)); // OpName
            if (op==6) members.put(words.get(at+1)+":"+words.get(at+2),string(words,at+3,at+size)); // OpMemberName
            if (op==32) pointerTargets.put(words.get(at+1),words.get(at+3)); // OpTypePointer
            if (op==59) variableTypes.put(words.get(at+2),words.get(at+1)); // OpVariable
            if (op==71 && size>=4) decorated.add(new int[]{words.get(at+1),words.get(at+2),words.get(at+3)});
            if (op==72 && size>=5 && words.get(at+3)==35) result.put(members.get(words.get(at+1)+":"+words.get(at+2))+".offset",words.get(at+4));
            at+=size;
        }
        for (var entry : decorated) {
            String name=names.get(entry[0]);
            if (name!=null && entry[1]==30) result.put(name+".location",entry[2]);
            if (name!=null && entry[1]==33) result.put(name+".binding",entry[2]);
            Integer pointer=variableTypes.get(entry[0]); Integer target=pointerTargets.get(pointer);
            String blockName=names.get(target);
            if(blockName!=null && entry[1]==33) result.put(blockName+".binding",entry[2]);
        }
        return result;
    }
    private static String string(IntBuffer words,int start,int end) {
        var bytes=ByteBuffer.allocate((end-start)*4).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=start;i<end;i++) bytes.putInt(words.get(i));
        byte[] value=bytes.array(); int length=0; while(length<value.length&&value[length]!=0)length++;
        return new String(value,0,length,StandardCharsets.UTF_8);
    }
    @Test void namedUniformAndStorageBlocksReceiveSharedDescriptorBindings() {
        var program = IrisProgramLinker.link(Map.of(
            IrisStage.VERTEX, "#version 450\nlayout(std140) uniform WorldData {mat4 transform;};layout(std430) readonly buffer Points {vec4 vertices[];};out vec2 uv;void main(){uv=vec2(0);gl_Position=transform*vertices[0];}",
            IrisStage.FRAGMENT, "#version 450\nlayout(std430) readonly buffer Points {vec4 vertices[];};layout(std140) uniform WorldData {mat4 transform;};in vec2 uv;out vec4 color;void main(){color=vertices[0]+transform[0]+vec4(uv,0,0);}"));
        assertCompiled(program);
        var vs = IrisCompiler.compileExplicit(program.stages().get(IrisStage.VERTEX).text(),IrisStage.VERTEX,"blocks.vsh");
        var fs = IrisCompiler.compileExplicit(program.stages().get(IrisStage.FRAGMENT).text(),IrisStage.FRAGMENT,"blocks.fsh");
        for (String block : List.of("Points", "WorldData")) {
            Integer vertexBinding=decorations(vs.spirv()).get(block+".binding");
            assertNotNull(vertexBinding, "Compiled descriptor missing for "+block);
            assertEquals(vertexBinding,decorations(fs.spirv()).get(block+".binding"));
            assertEquals(vertexBinding,program.blocks().stream().filter(b -> b.name().equals(block)).findFirst().orElseThrow().binding());
        }
    }
    @Test void rejectsIncompleteGraphicsProgramsInsteadOfInventingUnmatchedFragmentInputs() {
        assertThrows(IllegalArgumentException.class, () -> IrisProgramLinker.link(Map.of(IrisStage.FRAGMENT,
            "#version 450\nin vec2 uv;out vec4 color;void main(){color=vec4(uv,0,1);}")));
        assertThrows(IllegalArgumentException.class, () -> IrisProgramLinker.link(Map.of(
            IrisStage.VERTEX,"#version 450\nvoid main(){gl_Position=vec4(0);}",
            IrisStage.TESS_CONTROL,"#version 450\nlayout(vertices=3) out;void main(){}")));
    }
    @Test void rejectsDifferentSameNamedStructLayoutsInSharedLooseUniforms() {
        assertThrows(IllegalArgumentException.class, () -> IrisProgramLinker.link(Map.of(
            IrisStage.VERTEX, "#version 450\nstruct Item {vec4 value;};uniform Item item;void main(){gl_Position=item.value;}",
            IrisStage.FRAGMENT, "#version 450\nstruct Item {mat4 value;};uniform Item item;out vec4 color;void main(){color=item.value[0];}")));
    }
    @Test void recursivelyRejectsDifferentNestedStructLayoutsInSharedResourceBlocks() {
        assertThrows(IllegalArgumentException.class, () -> IrisProgramLinker.link(Map.of(
            IrisStage.VERTEX, "#version 450\nstruct Inner {vec4 value;};struct Outer {Inner payload;float weight;};layout(std430) readonly buffer Items {Outer items[];};void main(){gl_Position=items[0].payload.value;}",
            IrisStage.FRAGMENT, "#version 450\nstruct Inner {vec2 value;};struct Outer {Inner payload;float weight;};layout(std430) readonly buffer Items {Outer items[];};out vec4 color;void main(){color=vec4(items[0].payload.value,0,1);}")));
    }
    @Test void matchingReachableStructsCompileWithSharedUniformAndStorageLayouts() {
        var program = IrisProgramLinker.link(Map.of(
            IrisStage.VERTEX, "#version 450\nstruct Inner {vec4 value; mat3 basis;};struct Outer {Inner payload[2];float weight;};uniform Outer item;layout(std430) readonly buffer Items {Outer items[];};void main(){gl_Position=item.payload[0].value+items[0].payload[1].value;}",
            IrisStage.FRAGMENT, "#version 450\nstruct Inner { vec4 value;mat3 basis; };struct Outer { Inner payload[2]; float weight; };uniform Outer item;layout(std430) readonly buffer Items {Outer items[];};out vec4 color;void main(){color=item.payload[0].value+items[0].payload[1].value;}"));
        assertCompiled(program);
    }
    @Test void rejectsUnverifiableStructCyclesUnknownTypesAndSymbolicExtents() {
        for (String definitions : List.of(
            "struct First {Second next;};struct Second {First next;};",
            "struct First {Missing value;};",
            "const int COUNT=2;struct First {vec4 value[COUNT];};",
            "struct First {vec4 value;float value;};")) {
            var error=assertThrows(IllegalArgumentException.class, () -> IrisProgramLinker.link(Map.of(IrisStage.COMPUTE,
                "#version 450\n"+definitions+"uniform First value;layout(local_size_x=1) in;void main(){}")),definitions);
            assertTrue(error.getMessage().contains("struct"),error.getMessage());
        }
    }
    @Test void sharedCustomUniformTypesMustBeDefinedInEveryStageReceivingTheirBlock() {
        assertThrows(IllegalArgumentException.class, () -> IrisProgramLinker.link(Map.of(
            IrisStage.VERTEX, "#version 450\nstruct Item {vec4 value;};uniform Item item;void main(){gl_Position=item.value;}",
            IrisStage.FRAGMENT, "#version 450\nout vec4 color;void main(){color=vec4(1);}")));
    }
    private static void assertCompiled(IrisProgramLinker.Program program) {
        for (var entry : program.stages().entrySet()) {
            var result = IrisCompiler.compileExplicit(entry.getValue().text(), entry.getKey(), "linked."+entry.getKey().extension);
            assertTrue(result.success(), result.diagnostics()+"\n"+entry.getValue().text());
        }
    }
    private static int binding(IrisTranslator.Translation translation, String name) {
        return translation.opaques().stream().filter(o -> o.name().equals(name)).findFirst().orElseThrow().binding();
    }
}
