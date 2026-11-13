package com.koper.greenmarine.development;

import com.google.gson.*;
import com.koper.greenmarine.development.iris.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IrisProjectExporterTest {
    @TempDir Path temp;
    private static final String VERTEX = "#version 430\nuniform float vertexScale; out vec2 uv; void main(){uv=vec2(0);gl_Position=vec4(vertexScale);}";
    private static final String FRAGMENT = "#version 430\n/* RENDERTARGETS: 3,7 */\nuniform float exposure; uniform sampler2D colortex0; in vec2 uv; void main(){gl_FragData[0]=texture(colortex0,uv)*exposure;gl_FragData[1]=vec4(1);}";
    private static final String COMPUTE = "#version 430\nlayout(local_size_x=4) in; layout(r32ui) uniform uimage3D voxels; void main(){imageStore(voxels,ivec3(gl_GlobalInvocationID),uvec4(7));}";

    @Test void complexPackExportsLinkedEditableStagesSpirvAndResourceMetadataWithoutAdvertisingRuntime() throws Exception {
        Path source = pack(Map.of("world0/gbuffers_basic.vsh", VERTEX, "world0/gbuffers_basic.fsh", FRAGMENT,
            "world0/composite2.vsh", VERTEX, "world0/composite2.fsh", FRAGMENT,
            "world0/composite2.csh", COMPUTE, "world0/composite2_a.csh", COMPUTE,
            "world0/final.vsh", VERTEX, "world0/final.fsh", FRAGMENT,
            "shaders.properties", "image.voxels = voxelSampler RED_INTEGER R32UI UNSIGNED_INT true false 8 4 2\nbufferObject.4=1024\n"));
        var result = IrisImporter.convert(source, temp.resolve("converted"));
        Path metadata = result.destination().resolve("conversion/pipeline.json");
        assertTrue(Files.isRegularFile(metadata), "Whole-program metadata must be emitted for complex packs");
        var document = JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();
        assertEquals("conversion/inputs.json",document.get("inputMetadata").getAsString());
        var inputMetadata=JsonParser.parseString(Files.readString(result.destination().resolve(document.get("inputMetadata").getAsString()))).getAsJsonObject();
        assertFalse(inputMetadata.get("runtimeReady").getAsBoolean());
        assertTrue(inputMetadata.getAsJsonArray("worlds").asList().stream().map(JsonElement::getAsJsonObject)
            .allMatch(world->world.get("configurationValid").getAsBoolean()));
        assertFalse(result.runnable());
        assertFalse(document.getAsJsonObject("coverage").get("runtimeReady").getAsBoolean());
        assertEquals(8, document.getAsJsonObject("coverage").get("compiledStages").getAsInt());
        assertEquals("owner license", Files.readString(result.destination().resolve("iris-source/LICENSE.txt")));
        assertFalse(Files.exists(result.destination().resolve("assets/iris_imported/greenmarine/packs/imported.json")));
        assertEquals(3, document.getAsJsonArray("images").get(0).getAsJsonObject().getAsJsonArray("dimensions").size());
        var graphics = document.getAsJsonArray("groups").asList().stream().map(JsonElement::getAsJsonObject)
            .filter(g -> g.get("program").getAsString().equals("composite2") && g.get("kind").getAsString().equals("graphics")).findFirst().orElseThrow();
        assertEquals(2, graphics.getAsJsonArray("uniforms").size());
        assertEquals(1, graphics.getAsJsonArray("opaques").size());
        assertEquals(1, graphics.getAsJsonArray("varyings").size());
        for (var group : document.getAsJsonArray("groups")) for (var stage : group.getAsJsonObject().getAsJsonArray("stages")) {
            var output = stage.getAsJsonObject();
            String glsl = Files.readString(result.destination().resolve(output.get("glsl").getAsString()));
            byte[] spirv = Files.readAllBytes(result.destination().resolve(output.get("spirv").getAsString()));
            assertEquals(0x07230203, ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt());
            IrisStage kind = IrisStage.valueOf(output.get("kind").getAsString());
            assertTrue(IrisCompiler.compileExplicit(glsl, kind, "exported."+kind.extension).success());
            if (kind == IrisStage.FRAGMENT) assertEquals(List.of(3,7), output.getAsJsonArray("targets").asList().stream().map(JsonElement::getAsInt).toList());
        }
        assertTrue(document.getAsJsonArray("runtimeBlockers").size() > 0);
    }

    @Test void disabledAndFailedProgramsRemainExplicitAndDoNotEraseSuccessfulStageArtifacts() throws Exception {
        Path source = pack(Map.of("composite.vsh", VERTEX, "composite.fsh", FRAGMENT,
            "composite1.vsh", VERTEX, "composite1.fsh", "#version 430\nvoid main(){ invalid syntax; }",
            "composite2.csh", "invalid disabled shader", "shaders.properties", "program.composite2.enabled=false\n"));
        var result = IrisImporter.convert(source, temp.resolve("partial"));
        Path metadata = result.destination().resolve("conversion/pipeline.json");
        assertTrue(Files.isRegularFile(metadata));
        var document = JsonParser.parseString(Files.readString(metadata)).getAsJsonObject();
        assertEquals(1, document.getAsJsonObject("coverage").get("compileFailures").getAsInt());
        assertEquals(1, document.getAsJsonObject("coverage").get("disabledPrograms").getAsInt());
        assertFalse(result.runnable());
        assertEquals("invalid disabled shader", Files.readString(result.destination().resolve("iris-source/shaders/composite2.csh")));
        assertTrue(document.getAsJsonArray("groups").asList().stream().map(JsonElement::getAsJsonObject)
            .flatMap(g -> g.getAsJsonArray("stages").asList().stream()).map(JsonElement::getAsJsonObject)
            .anyMatch(s -> !s.get("compiled").getAsBoolean() && !s.get("diagnostics").getAsString().isBlank()));
    }

    private Path pack(Map<String,String> files) throws Exception {
        Path root=Files.createDirectory(temp.resolve("pack"));
        Files.writeString(root.resolve("LICENSE.txt"), "owner license");
        for (var entry:files.entrySet()) { Path path=root.resolve("shaders").resolve(entry.getKey()); Files.createDirectories(path.getParent()); Files.writeString(path,entry.getValue()); }
        return root;
    }
}
