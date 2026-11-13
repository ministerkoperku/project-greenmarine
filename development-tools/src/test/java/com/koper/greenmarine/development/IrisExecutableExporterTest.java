package com.koper.greenmarine.development;

import com.google.gson.*;
import com.koper.greenmarine.api.pack.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class IrisExecutableExporterTest {
    @TempDir Path temp;
    @Test void depthRuntimeFixtureDeclaresFloatingPointProofTargets() throws Exception {
        Path source=temp.resolve("depth-source"),destination=temp.resolve("depth-native");
        com.koper.greenmarine.development.iris.AutomaticTerrainFixture.main(new String[]{source.toString(),destination.toString(),"depth"});
        var manifest=JsonParser.parseString(Files.readString(destination.resolve("assets/iris_imported/greenmarine/packs/native.json"))).getAsJsonObject();
        assertEquals("RGBA32F",manifest.getAsJsonObject("targets").getAsJsonObject("colortex3").get("format").getAsString());
    }
    @Test void forwardFragmentDepthIsAutomaticallyAdaptedForTerrainAndFullscreen() throws Exception {
        var source=pack("composite","");
        Files.writeString(source.resolve("shaders/block.properties"),"");
        Files.writeString(source.resolve("shaders/gbuffers_terrain_solid.vsh"),"#version 120\nvoid main(){gl_Position=ftransform();}");
        Files.writeString(source.resolve("shaders/gbuffers_terrain_solid.fsh"),"#version 120\nvoid depth(out float d){d=.25;}void main(){depth(gl_FragDepth);gl_FragColor=vec4(gl_FragCoord.zzz,1.);return;}");
        Files.writeString(source.resolve("shaders/composite.fsh"),"#version 120\nconst vec4 colortex0ClearColor=vec4(0.);void main(){gl_FragColor=vec4(gl_FragCoord[2]);}");
        var report=IrisImporter.convert(source,temp.resolve("forward-depth"));
        assertTrue(report.runnable(),String.join("\n",report.diagnostics()));
        try(var paths=Files.walk(report.destination().resolve("assets/iris_imported/shaders/native"))) {
            var fragments=paths.filter(p->p.toString().endsWith(".fsh")).map(p->{try{return Files.readString(p);}catch(Exception e){throw new RuntimeException(e);}}).toList();
            assertTrue(fragments.stream().anyMatch(s->s.contains("gl_FragDepth = 1.0 - gm_IrisDepthValue")));
            assertTrue(fragments.stream().anyMatch(s->s.contains("gm_IrisDepthCoord()[2]")));
        }
    }
    @Test void automaticallyEmitsCompleteNativeTerrainGeometryAndTessellationChains() throws Exception {
        for(String mode:new String[]{"geometry","tessellation","tessellation4"}) {
            Path source=temp.resolve(mode+"-source"),destination=temp.resolve(mode+"-native");
            com.koper.greenmarine.development.iris.AutomaticTerrainFixture.main(new String[]{source.toString(),destination.toString(),mode});
            String manifest=Files.readString(destination.resolve("assets/iris_imported/greenmarine/packs/native.json"));
            var json=JsonParser.parseString(manifest).getAsJsonObject();
            for(var entry:json.getAsJsonArray("programs")) {
                var program=entry.getAsJsonObject();assertTrue(program.has("geometry"));
                assertEquals(mode.startsWith("tessellation"),program.has("tessControl"));
                if(mode.startsWith("tessellation"))assertEquals(3,program.get("patchVertices").getAsInt());
            }
            var catalog=new PackCatalog();
            assertTrue(catalog.reload(java.util.Map.of("native",manifest),id->{
                int colon=id.indexOf(':');
                try{return Files.readString(destination.resolve("assets/"+id.substring(0,colon)+"/"+id.substring(colon+1)));}
                catch(java.io.IOException missing){return null;}
            }),catalog.error());
        }
    }
    @Test void automaticallyInstallsMultipleSourceProgramsWithTheirVertexLogic() throws Exception {
        for(String name:new String[]{"composite","composite4"}) {
            Path source=pack(name,""),destination=temp.resolve(name+"-native");
            var report=IrisImporter.convert(source,destination);
            assertTrue(report.runnable(),String.join("\n",report.diagnostics()));
            var manifests=Files.list(destination.resolve("assets/iris_imported/greenmarine/packs")).toList();
            assertEquals(1,manifests.size());
            var json=JsonParser.parseString(Files.readString(manifests.getFirst())).getAsJsonObject();
            var definition=ShaderPack.parse(json);
            assertEquals(3,definition.passes().size()); // Actual host color seed, original composite, original final.
            var composite=definition.passes().get(1);
            assertNotNull(composite.vertex());assertEquals(6,composite.vertices());
            String id=composite.vertex(),path="assets/"+id.substring(0,id.indexOf(':'))+"/shaders/"+id.substring(id.indexOf(':')+1)+".vsh";
            var compiled=com.koper.greenmarine.development.iris.IrisCompiler.compileExplicit(Files.readString(destination.resolve(path)),com.koper.greenmarine.development.iris.IrisStage.VERTEX,"native-quad");
            assertTrue(compiled.success(),compiled.diagnostics());
        }
    }
    @Test void unknownHostInputKeepsAllProgramsAsExplicitCandidates() throws Exception {
        var report=IrisImporter.convert(pack("composite2","uniform mat4 shadowModelView;"),temp.resolve("blocked"));
        assertFalse(report.runnable());
        assertTrue(String.join("\n",report.diagnostics()).contains("shadowModelView"));
        assertTrue(Files.isRegularFile(report.destination().resolve("conversion/native/root.json")));
        assertFalse(Files.exists(report.destination().resolve("assets/iris_imported/greenmarine/packs")));
    }
    @Test void unsupportedRenderStateCannotBeSilentlyDroppedByAutomaticPublication() throws Exception {
        var source=pack("composite3","");Files.writeString(source.resolve("shaders/shaders.properties"),"blend.composite3=SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ZERO\n");
        var report=IrisImporter.convert(source,temp.resolve("unsupported-blend"));
        assertFalse(report.runnable());
        assertTrue(String.join("\n",report.diagnostics()).contains("blend.composite3"));
    }
    @Test void storageImagesWithoutSamplerAliasesCannotCrashUnresolvedSamplerDiagnostics() throws Exception {
        var source=pack("composite","uniform sampler3D absent;");
        Files.writeString(source.resolve("shaders/shaders.properties"),"image.volume=none RED_INTEGER R32UI UNSIGNED_INT false false 4 4 4\n");
        var report=IrisImporter.convert(source,temp.resolve("storage-without-sampler"));
        assertFalse(report.runnable());assertTrue(String.join("\n",report.diagnostics()).contains("HOST_SAMPLER: composite: absent"));
    }
    @Test void automaticallyRoutesTerrainVariantsAndPreservesNativeMaterialCapture() throws Exception {
        var source=pack("composite","");
        Files.writeString(source.resolve("shaders/block.properties"),"""
            #if MC_VERSION >= 12100
            block.37=oak_log:axis=x
            #endif
            block.42=minecraft:oak_log stone
            block.-7=water:level=0
            """);
        String vertex="""
            #version 120
            attribute vec4 mc_Entity;varying vec4 proof;
            void main(){gl_Position=ftransform();proof=vec4(mc_Entity.x,gl_Color.a,gl_MultiTexCoord0.x,1.);}
            """;
        for(String name:new String[]{"gbuffers_terrain_solid","gbuffers_terrain_cutout","gbuffers_water"}) {
            Files.writeString(source.resolve("shaders/"+name+".vsh"),vertex);
            Files.writeString(source.resolve("shaders/"+name+".fsh"),"#version 120\n/* RENDERTARGETS: 0 */\nvarying vec4 proof;void main(){gl_FragData[0]=proof;}");
        }
        var report=IrisImporter.convert(source,temp.resolve("native-world"));
        assertTrue(report.runnable(),String.join("\n",report.diagnostics()));
        var definition=ShaderPack.parse(JsonParser.parseString(Files.readString(report.destination().resolve("assets/iris_imported/greenmarine/packs/native.json"))).getAsJsonObject());
        assertTrue(definition.terrain().enabled());assertEquals("colortex0",definition.worldColor());
        assertEquals(37,definition.terrain().materialId("minecraft:oak_log",java.util.Map.of("axis","x")));
        assertEquals(42,definition.terrain().materialId("minecraft:oak_log",java.util.Map.of("axis","z")));
        assertEquals(-7,definition.terrain().materialId("minecraft:water",java.util.Map.of("level","0")));
        assertEquals(-1,definition.terrain().materialId("minecraft:water",java.util.Map.of("level","3")));
        assertEquals(3,definition.programs().size());
        var solid=definition.worldProgram(PackProgram.Target.TERRAIN,net.minecraft.client.renderer.RenderPipelines.SOLID_TERRAIN.getLocation().toString());
        var cutout=definition.worldProgram(PackProgram.Target.TERRAIN,net.minecraft.client.renderer.RenderPipelines.CUTOUT_TERRAIN.getLocation().toString());
        var water=definition.worldProgram(PackProgram.Target.TERRAIN,net.minecraft.client.renderer.RenderPipelines.TRANSLUCENT_TERRAIN.getLocation().toString());
        assertNotNull(solid);assertNotNull(cutout);assertNotNull(water);
        assertNotEquals(solid.fragment(),cutout.fragment());assertNotEquals(solid.fragment(),water.fragment());
        assertNull(definition.worldProgram(PackProgram.Target.TERRAIN,"sodium:terrain_solid"));
        assertTrue(definition.passes().stream().noneMatch(p->p.name().equals("host_color")),"Native world output must not be overwritten by a host-color seed");
        var catalog=new PackCatalog();
        String manifest=Files.readString(report.destination().resolve("assets/iris_imported/greenmarine/packs/native.json"));
        assertTrue(catalog.reload(java.util.Map.of("native",manifest),id->{
            int colon=id.indexOf(':');
            try{return Files.readString(report.destination().resolve("assets/"+id.substring(0,colon)+"/"+id.substring(colon+1)));}
            catch(java.io.IOException missing){return null;}
        }),catalog.error());
    }

    @Test void implicitNonterrainFallbacksCannotBePublishedAsTerrainOnlyConversion() throws Exception {
        for(String name:new String[]{"gbuffers_terrain","gbuffers_textured_lit","gbuffers_basic"}) {
            var source=pack("composite","");
            Files.writeString(source.resolve("shaders/block.properties"),"");
            Files.writeString(source.resolve("shaders/"+name+".vsh"),"#version 120\nvoid main(){gl_Position=ftransform();}");
            Files.writeString(source.resolve("shaders/"+name+".fsh"),"#version 120\nvoid main(){gl_FragColor=vec4(1.);}");
            var report=IrisImporter.convert(source,temp.resolve("unsupported-fallback-"+name));
            assertFalse(report.runnable());
            assertTrue(String.join("\n",report.diagnostics()).contains("HOST_GEOMETRY_REQUEST"),String.join("\n",report.diagnostics()));
            Files.delete(source.resolve("shaders/"+name+".vsh"));Files.delete(source.resolve("shaders/"+name+".fsh"));
        }
    }

    @Test void sampledForwardDepthAutomaticallyBindsWorldRasterAndComputePrograms() throws Exception {
        for(String sampler:new String[]{"depthtex0","depthtex1","depthtex2","gdepthtex"}) {
            var source=pack("composite","");Files.writeString(source.resolve("shaders/block.properties"),"");
            Files.writeString(source.resolve("shaders/gbuffers_terrain_solid.vsh"),"#version 120\nvoid main(){gl_Position=ftransform();}");
            Files.writeString(source.resolve("shaders/gbuffers_terrain_solid.fsh"),"#version 120\nuniform sampler2D "+sampler+";void main(){gl_FragColor=texture2D("+sampler+",vec2(.5));}");
            Files.writeString(source.resolve("shaders/composite.fsh"),"#version 450\nconst vec4 colortex0ClearColor=vec4(0.);uniform sampler2D "+sampler+";void main(){gl_FragColor=texelFetch("+sampler+",ivec2(gl_FragCoord.xy),0);}");
            Files.writeString(source.resolve("shaders/composite.csh"),"#version 450\nlayout(local_size_x=1)in;uniform sampler2D "+sampler+";void main(){vec4 d=texelFetch("+sampler+",ivec2(0),0);}");
            var report=IrisImporter.convert(source,temp.resolve("sampled-depth-"+sampler));
            assertTrue(report.runnable(),String.join("\n",report.diagnostics()));
            var manifest=JsonParser.parseString(Files.readString(report.destination().resolve("assets/iris_imported/greenmarine/packs/native.json"))).getAsJsonObject();
            var definition=ShaderPack.parse(manifest);
            String expected=switch(sampler){case "depthtex1"->"opaque_depth";case "depthtex2"->"opaque_no_hand_depth";default->"world_depth";};
            assertEquals(expected,definition.programs().getFirst().inputs().get(sampler));
            assertTrue(definition.passes().stream().filter(p->p.inputs().containsKey(sampler)).count()>=2);
            for(var pass:definition.passes())if(pass.inputs().containsKey(sampler))assertEquals(expected,pass.inputs().get(sampler));
        }
    }
    @Test void missingLegacyMaterialDefaultsBlockPublication() throws Exception {
        var source=pack("composite","");Files.deleteIfExists(source.resolve("shaders/block.properties"));
        Files.writeString(source.resolve("shaders/gbuffers_terrain_solid.vsh"),"#version 120\nattribute vec4 mc_Entity;void main(){gl_Position=ftransform()+mc_Entity;}");
        Files.writeString(source.resolve("shaders/gbuffers_terrain_solid.fsh"),"#version 120\nvoid main(){gl_FragColor=vec4(1.);}");
        var report=IrisImporter.convert(source,temp.resolve("missing-legacy-materials"));
        assertFalse(report.runnable());assertTrue(String.join("\n",report.diagnostics()).contains("LEGACY_MATERIAL_DEFAULTS"));
    }

    @Test void unsupportedGeometryAndMaterialDirectivesRemainBlocking() throws Exception {
        var source=pack("composite","");
        Files.writeString(source.resolve("shaders/block.properties"),"layer.translucent=stone\n");
        Files.writeString(source.resolve("shaders/gbuffers_terrain.vsh"),"#version 120\nvoid main(){gl_Position=ftransform();}");
        Files.writeString(source.resolve("shaders/gbuffers_terrain.fsh"),"#version 120\nvoid main(){gl_FragColor=vec4(1.);}");
        var report=IrisImporter.convert(source,temp.resolve("unsupported-material-layer"));
        assertFalse(report.runnable());assertTrue(String.join("\n",report.diagnostics()).contains("layer.translucent"));
    }
    @Test void incompatibleWorldInputPrimitivesCannotPublishATriangularHostProgram() throws Exception {
        for(String primitive:new String[]{"points","lines","triangles_adjacency"}) {
            var source=pack("composite","");
            Files.writeString(source.resolve("shaders/block.properties"),"");
            Files.writeString(source.resolve("shaders/gbuffers_terrain_solid.vsh"),"#version 450\nvoid main(){gl_Position=ftransform();}");
            Files.writeString(source.resolve("shaders/gbuffers_terrain_solid.fsh"),"#version 450\nvoid main(){gl_FragColor=vec4(1.);}");
            Files.writeString(source.resolve("shaders/gbuffers_terrain_solid.gsh"),"#version 450\nlayout("+primitive+")in;layout(triangle_strip,max_vertices=3)out;void main(){gl_Position=gl_in[0].gl_Position;EmitVertex();EndPrimitive();}");
            var report=IrisImporter.convert(source,temp.resolve("unsupported-primitive-"+primitive));
            assertFalse(report.runnable());
            assertTrue(String.join("\n",report.diagnostics()).contains("WORLD_INPUT_PRIMITIVE"),String.join("\n",report.diagnostics()));
        }
    }
    private Path pack(String program,String extra) throws Exception {
        Path source=temp.resolve(program+"-source");Files.createDirectories(source.resolve("shaders"));
        String vertex="#version 120\nvarying float weight;void main(){gl_Position=ftransform();weight=2.;}";
        Files.writeString(source.resolve("shaders/"+program+".vsh"),vertex);
        Files.writeString(source.resolve("shaders/"+program+".fsh"),"#version 120\n/* RENDERTARGETS: 0 */\nconst vec4 colortex0ClearColor=vec4(0.);"+extra+"\nvarying float weight;void main(){gl_FragData[0]=vec4(weight*.25,.125,.75,1.);}");
        Files.writeString(source.resolve("shaders/final.vsh"),"#version 120\nvarying vec2 uv;void main(){gl_Position=ftransform();uv=gl_MultiTexCoord0.xy;}");
        Files.writeString(source.resolve("shaders/final.fsh"),"#version 120\nuniform sampler2D colortex0;varying vec2 uv;void main(){gl_FragColor=texture2D(colortex0,uv);}");
        return source;
    }
}
