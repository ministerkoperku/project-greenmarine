package com.koper.greenmarine.development.iris;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IrisTerrainAdapterTest {
    @Test void multipliedAoAndRouteSpecificAlphaAreExplicitAdaptationInputs() {
        var linked=IrisProgramLinker.link(Map.of(IrisStage.VERTEX,"#version 120\nvarying vec4 tint;void main(){tint=gl_Color;gl_Position=ftransform();}",
            IrisStage.FRAGMENT,"#version 120\nvarying vec4 tint;void main(){gl_FragColor=tint;}"));
        var adapted=IrisTerrainAdapter.adapt(linked,IrisTerrainAdapter.ColorProfile.IRIS_MULTIPLIED_AO,IrisTerrainAdapter.ClipDepth.ZERO_TO_ONE_REVERSED,true).program();
        compile(adapted);
        assertTrue(adapted.stages().get(IrisStage.VERTEX).text().contains("gm_RawColor.rgb * gm_RawColor.a"));
        assertTrue(adapted.stages().get(IrisStage.FRAGMENT).text().contains("discard"));
    }
    @Test void actualHostAtlasAndLightmapSamplersRetainBuiltinTextureCalls() {
        for(String sampler:List.of("gtexture","tex","texture")) {
            var linked=IrisProgramLinker.link(Map.of(IrisStage.VERTEX,"""
                #version 120
                varying vec2 uv;
                void main(){gl_Position=ftransform();uv=(gl_TextureMatrix[0]*gl_MultiTexCoord0).xy;}
                """,IrisStage.FRAGMENT,"#version 120\nuniform sampler2D "+sampler+";uniform sampler2D lightmap;varying vec2 uv;void main(){gl_FragColor=texture2D("+sampler+",uv)*texture2D(lightmap,uv);}"));
            var adapted=IrisTerrainAdapter.adapt(linked).program();
            assertTrue(adapted.uniforms().isEmpty());
            var result=IrisCompiler.compileExplicit(adapted.stages().get(IrisStage.FRAGMENT).text(),IrisStage.FRAGMENT,"host-samplers");
            assertTrue(result.success(),result.diagnostics());
            // Reflection is the runtime binding contract; source spelling alone is insufficient.
            var descriptors=new com.koper.greenmarine.client.GraphicsSpirv(com.koper.greenmarine.api.render.GraphicsStage.FRAGMENT,result.spirv()).descriptors();
            assertTrue(descriptors.stream().anyMatch(d->d.name().equals("Sampler0")));
            assertTrue(descriptors.stream().anyMatch(d->d.name().equals("Sampler2")));
            compile(adapted);
        }
    }
    @Test void semanticInputsIgnoreSourceLocationsAndRetainSignedSidecarValues() {
        var linked = IrisProgramLinker.link(Map.of(IrisStage.VERTEX, """
            #version 450
            layout(location=12) in vec4 mc_Entity;
            layout(location=11) in vec3 at_midBlock;
            layout(location=10) in vec4 at_tangent;
            layout(location=9) in vec4 mc_midTexCoord;
            out vec4 result;
            void main(){result=gl_Color+mc_Entity+at_tangent+mc_midTexCoord+vec4(at_midBlock,0)+vec4(gl_Normal,0);gl_Position=ftransform();}
            """, IrisStage.FRAGMENT, "#version 450\nin vec4 result;out vec4 color;void main(){color=result;}"));
        var adapted = IrisTerrainAdapter.adapt(linked);
        String vertex = adapted.program().stages().get(IrisStage.VERTEX).text();
        assertTrue(vertex.contains("layout(location = 3) in ivec2 UV2;"), vertex);
        assertTrue(vertex.contains("layout(location = 0) in vec3 Position;"),vertex);
        assertTrue(vertex.contains("layout(location = 1) in vec4 Color;"),vertex);
        assertTrue(vertex.contains("layout(location = 2) in vec2 UV0;"),vertex);
        assertFalse(vertex.contains("in vec4 mc_Entity;"), vertex);
        assertTrue(vertex.contains("mc_Entity = vec4(float(int(gm_Record0.z)), float(gm_Mid.w), 0.0, 1.0);"),vertex);
        assertTrue(vertex.contains("at_midBlock = vec3(gm_Mid.xyz);"), vertex);
        assertTrue(vertex.contains("mc_midTexCoord = vec4(uintBitsToFloat(gm_Record1.xy), 0.0, 1.0);"),vertex);
        assertTrue(vertex.contains("gm_Draw.y + gl_VertexIndex - gm_Draw.x"),vertex);
        assertTrue(vertex.contains("Position + vec3(gm_ChunkPosition - gm_CameraBlockPos) + gm_CameraOffset"),vertex);
        assertTrue(vertex.contains("gm_Record1.z"),vertex);
        assertFalse(adapted.program().uniforms().stream().anyMatch(u -> u.name().equals("iris_ModelViewMat") || u.name().equals("iris_ProjMat")));
        assertEquals(linked.varyings(), adapted.program().varyings());
        compile(adapted.program());
    }

    @Test void modernInputsAndTwoComponentMidpointUseTheSameHostAbi() {
        var linked = IrisProgramLinker.link(Map.of(IrisStage.VERTEX,"""
            #version 450
            in vec2 mc_midTexCoord; in vec3 vaPosition; in vec3 vaNormal;
            in ivec2 vaUV2; in vec2 vaUV0; in vec4 vaColor;
            void main(){gl_Position=vec4(vaPosition+vaNormal+vec3(mc_midTexCoord+vaUV0,0)+vec3(vaUV2,0),1)+vaColor;}
            """));
        var adapted = IrisTerrainAdapter.adapt(linked);
        String vertex = adapted.program().stages().get(IrisStage.VERTEX).text();
        assertTrue(vertex.contains("mc_midTexCoord = uintBitsToFloat(gm_Record1.xy);"),vertex);
        assertTrue(vertex.contains("vaUV2 = UV2;"),vertex);
        assertTrue(vertex.contains("vaColor = unpackUnorm4x8(gm_Record1.z).bgra;"),vertex);
        compile(adapted.program());
    }

    @Test void compatibilityAttributesCanShareLinesAndDeclaratorLists() {
        var linked=IrisProgramLinker.link(Map.of(IrisStage.VERTEX,"#version 120\nattribute vec4 at_tangent, mc_Entity;attribute vec2 mc_midTexCoord;void main(){gl_Position=gl_Vertex+at_tangent+mc_Entity+vec4(mc_midTexCoord,0,0);}"));
        compile(IrisTerrainAdapter.adapt(linked).program());
    }

    @Test void intermediateStagesKeepIrisClipUntilGeometryEmitsEachVertex() {
        var linked=IrisProgramLinker.link(Map.of(
            IrisStage.VERTEX,"#version 450\nvoid main(){gl_Position=ftransform();}",
            IrisStage.TESS_CONTROL,"#version 450\nlayout(vertices=3) out;void main(){gl_out[gl_InvocationID].gl_Position=gl_in[gl_InvocationID].gl_Position;if(gl_InvocationID==0){gl_TessLevelOuter[0]=1;gl_TessLevelOuter[1]=1;gl_TessLevelOuter[2]=1;gl_TessLevelInner[0]=1;}}",
            IrisStage.TESS_EVALUATION,"#version 450\nlayout(triangles,equal_spacing,cw) in;void main(){gl_Position=gl_in[0].gl_Position;}",
            IrisStage.GEOMETRY,"#version 450\nlayout(triangles) in;layout(triangle_strip,max_vertices=3) out;void main(){for(int i=0;i<3;i++){gl_Position=gl_in[i].gl_Position;EmitVertex();}EndPrimitive();}",
            IrisStage.FRAGMENT,"#version 450\nout vec4 color;void main(){color=vec4(1);}"));
        var adapted=IrisTerrainAdapter.adapt(linked).program();
        assertFalse(adapted.stages().get(IrisStage.VERTEX).text().contains("gl_Position.z ="));
        assertFalse(adapted.stages().get(IrisStage.TESS_EVALUATION).text().contains("gl_Position.z ="));
        String geometry=adapted.stages().get(IrisStage.GEOMETRY).text();
        assertTrue(geometry.contains("gm_TerrainEmitVertex();"),geometry);
        assertTrue(geometry.contains("gl_Position = gm_SavedPosition;"),geometry);
        compile(adapted);
    }

    @Test void unsupportedAttributesAndTypesFailExplicitly() {
        var unknown=IrisProgramLinker.link(Map.of(IrisStage.VERTEX,"#version 450\nin vec4 customAttribute;void main(){gl_Position=customAttribute;}"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->IrisTerrainAdapter.adapt(unknown)).getMessage().contains("customAttribute"));
        var wrongType=IrisProgramLinker.link(Map.of(IrisStage.VERTEX,"#version 450\nin mat3 mc_Entity;void main(){gl_Position=vec4(mc_Entity[0],1);}"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->IrisTerrainAdapter.adapt(wrongType)).getMessage().contains("mc_Entity"));
        var emission=IrisProgramLinker.link(Map.of(IrisStage.VERTEX,"#version 450\nin vec4 at_midBlock;void main(){gl_Position=at_midBlock;}"));
        var emitted=IrisTerrainAdapter.adapt(emission).program();
        assertTrue(emitted.stages().get(IrisStage.VERTEX).text().contains("float(gm_Record1.w & 15u)"));
        compile(emitted);
    }

    @Test void integerAndScalarMaterialDeclarationsFollowIrisConversions() {
        for(String type:List.of("float","int","vec2","vec3","vec4","ivec2","ivec3","ivec4")) {
            String expression=type.equals("float")||type.equals("int")?"float(mc_Entity)":"float(mc_Entity.x)";
            var linked=IrisProgramLinker.link(Map.of(IrisStage.VERTEX,"#version 450\nin "+type+" mc_Entity;void main(){gl_Position=vec4("+expression+");}"));
            compile(IrisTerrainAdapter.adapt(linked).program());
        }
    }

    @Test void compatibilityIndexBuiltinsTranslateWithoutChangingCommentTokens() {
        String comment="// gl_VertexID gl_InstanceID\n/* gl_VertexID gl_InstanceID */";
        var translated=IrisTranslator.translate("#version 450\n"+comment+"\nvoid main(){gl_Position=vec4(gl_VertexID+gl_InstanceID);}",IrisStage.VERTEX);
        assertTrue(translated.text().contains(comment),translated.text());
        assertTrue(translated.text().contains("vec4(gl_VertexIndex+gl_InstanceIndex)"),translated.text());
        var compiled=IrisCompiler.compileExplicit(translated.text(),IrisStage.VERTEX,"indices.vsh");
        assertTrue(compiled.success(),compiled.diagnostics());
    }

    @Test void tessellationOutputAndAllProjectionConventionsCompileWithActualHostMatrices() {
        var linked=IrisProgramLinker.link(Map.of(
            IrisStage.VERTEX,"#version 450\nvoid main(){gl_Position=ftransform();}",
            IrisStage.TESS_CONTROL,"#version 450\nlayout(vertices=3) out;void main(){gl_out[gl_InvocationID].gl_Position=gl_in[gl_InvocationID].gl_Position;}",
            IrisStage.TESS_EVALUATION,"#version 450\nlayout(triangles,equal_spacing,cw) in;void main(){gl_Position=gl_in[0].gl_Position;}",
            IrisStage.FRAGMENT,"#version 450\nout vec4 color;void main(){color=gl_ProjectionMatrixInverse*gl_ModelViewMatrixInverse*vec4(gl_NormalMatrix[0],1);}"));
        for(var depth:IrisTerrainAdapter.ClipDepth.values()) {
            var adapted=IrisTerrainAdapter.adapt(linked,IrisTerrainAdapter.ColorProfile.HOST_LIT,depth).program();
            assertTrue(adapted.uniforms().isEmpty());
            String fragment=adapted.stages().get(IrisStage.FRAGMENT).text();
            assertTrue(fragment.contains("inverse(gm_IrisProjection())"),fragment);
            assertTrue(fragment.contains("inverse(gm_TerrainModelView)"),fragment);
            String tessellation=adapted.stages().get(IrisStage.TESS_EVALUATION).text();
            assertEquals(depth!=IrisTerrainAdapter.ClipDepth.OPENGL,tessellation.contains("gl_Position.z ="));
            compile(adapted);
        }
    }

    /** Native adaptation coverage is separate from original-source compilation and runtime parity. */
    @Test void nativeTerrainProgramsCompileWhenPackConfigured() throws Exception {
        String pack=System.getProperty("greenmarine.iris.pack");
        org.junit.jupiter.api.Assumptions.assumeTrue(pack!=null&&!pack.isBlank());
        var plan=IrisPipelinePlan.parse(IrisSources.load(Path.of(pack)));int programs=0;
        for(var world:plan.worlds())for(String name:List.of("gbuffers_terrain","gbuffers_water")) {
            var selected=world.resolve(name);if(selected.isEmpty())continue;
            var sources=new EnumMap<IrisStage,String>(IrisStage.class);
            selected.get().stages().forEach((stage,source)->sources.put(stage,source.preprocessedText()));
            compile(IrisTerrainAdapter.adapt(IrisProgramLinker.link(sources)).program());programs++;
        }
        assertTrue(programs>0,"Configured pack has no native terrain or water programs");
    }

    @Test void descriptorAllocationPreservesExistingResourcesAndOriginalProgram() {
        var linked=IrisProgramLinker.link(Map.of(IrisStage.VERTEX,"#version 450\nlayout(std430,binding=0) buffer Existing {float value[];};void main(){gl_Position=gl_ModelViewProjectionMatrix*gl_Vertex+vec4(value[0]);}"));
        String original=linked.stages().get(IrisStage.VERTEX).text();
        var adapted=IrisTerrainAdapter.adapt(linked);
        var occupied=new HashSet<Integer>();occupied.add(0);
        for(var block:adapted.program().blocks())assertTrue(occupied.add(block.binding()),block.name());
        assertEquals(original,linked.stages().get(IrisStage.VERTEX).text());
        assertTrue(adapted.program().blocks().stream().anyMatch(b->b.name().equals("MarineTerrainVertices")));
        assertTrue(adapted.program().blocks().stream().anyMatch(b->b.name().equals("MarineTerrainDraws")));
        compile(adapted.program());
    }

    private static void compile(IrisProgramLinker.Program program) {
        for(var stage:program.stages().entrySet()) {
            var result=IrisCompiler.compileExplicit(stage.getValue().text(),stage.getKey(),"terrain."+stage.getKey().extension);
            assertTrue(result.success(),result.diagnostics()+"\n"+stage.getValue().text());
        }
    }
}
