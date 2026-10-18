package com.koper.greenmarine.client.world;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.client.renderer.oit.OitPipelineSet;
import net.minecraft.client.renderer.oit.OitStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

class SodiumShaderAdapterTest {
    @TempDir Path temporary;
    private static final Path ASSETS = Path.of("src/main/resources/assets");
    private static final Path MINECRAFT = Path.of(System.getProperty("user.home"), ".gradle/caches/fabric-loom/26.3/minecraft-client.jar");
    private static final Pattern INCLUDE = Pattern.compile("#include\\s*<([^>]+)>");
    private record Variant(String defines, int outputs) {}

    private String disassemble(Path file) throws Exception {
        var process = new ProcessBuilder("spirv-dis", file.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private Set<String> interfaceNames(String binary, String storage) {
        var names = new HashMap<String,String>();
        var name = Pattern.compile("OpName (%\\S+) \"([^\"]+)\"").matcher(binary);
        while(name.find()) names.put(name.group(1),name.group(2));
        var variables = Pattern.compile("(?m)^\\s*(%\\S+) = OpVariable %\\S+ " + storage + "\\s*$").matcher(binary);
        var result = new HashSet<String>();
        while(variables.find()) {
            String value = names.getOrDefault(variables.group(1), variables.group(1));
            if(!value.startsWith("gl_") && !value.equals("")) result.add(value);
        }
        return result;
    }

    private int colorSlots(String binary) {
        var constants = new HashMap<String,Integer>();
        var matcher = Pattern.compile("(%\\S+) = OpConstant %\\S+ (\\d+)").matcher(binary);
        while(matcher.find()) constants.put(matcher.group(1),Integer.parseInt(matcher.group(2)));
        var arrays = new HashMap<String,Integer>();
        matcher = Pattern.compile("(%\\S+) = OpTypeArray %\\S+ (%\\S+)").matcher(binary);
        while(matcher.find()) arrays.put(matcher.group(1),constants.get(matcher.group(2)));
        var pointers = new HashMap<String,String>();
        matcher = Pattern.compile("(%\\S+) = OpTypePointer Output (%\\S+)").matcher(binary);
        while(matcher.find()) pointers.put(matcher.group(1),matcher.group(2));
        var outputs = new HashMap<String,Integer>();
        matcher = Pattern.compile("(%\\S+) = OpVariable (%\\S+) Output").matcher(binary);
        while(matcher.find()) outputs.put(matcher.group(1),arrays.getOrDefault(pointers.get(matcher.group(2)),1));
        var slots = new HashSet<Integer>();
        matcher = Pattern.compile("OpDecorate (%\\S+) Location (\\d+)").matcher(binary);
        while(matcher.find()) {
            int start = Integer.parseInt(matcher.group(2));
            for(int i=0; i<outputs.getOrDefault(matcher.group(1),0); i++) assertTrue(slots.add(start+i),"Overlapping color outputs");
        }
        return slots.size();
    }

    private String flatten(String source, Path sodiumAssets) throws Exception {
        var matcher = INCLUDE.matcher(source);
        var output = new StringBuffer();
        while (matcher.find()) {
            String[] name = matcher.group(1).split(":", 2);
            String resource = "assets/" + name[0] + "/shaders/include/" + name[1];
            Path file = (name[0].equals("sodium") ? sodiumAssets : ASSETS.getParent()).resolve(resource);
            String text;
            if (Files.exists(file)) text = Files.readString(file);
            else try (var zip = new ZipFile(MINECRAFT.toFile())) {
                var entry = zip.getEntry(resource);
                assertNotNull(entry, resource);
                text = new String(zip.getInputStream(entry).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            text = SodiumShaderAdapter.include(text);
            matcher.appendReplacement(output, java.util.regex.Matcher.quoteReplacement(flatten(text, sodiumAssets)));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    @Test void packedBridgeLinksRasterCaptureAndEveryOitVariant() throws Exception {
        String sodium = System.getenv("GREENMARINE_TEST_SODIUM_RESOURCES");
        assumeTrue(sodium != null && Files.isDirectory(Path.of(sodium)), "Set GREENMARINE_TEST_SODIUM_RESOURCES to matching Sodium common/src/main/resources");
        assumeTrue(Files.exists(MINECRAFT), "Cached Minecraft resources required");
        var variants = new ArrayList<Variant>();
        String base = SodiumCompatibilityTest.host().getShaderDefines().asSourceDirectives();
        variants.add(new Variant(base,1));
        variants.add(new Variant(base + "#define ALPHA_CUTOUT 0.5\n",1));
        var oit = OitPipelineSet.builder("sodium_terrain", SodiumCompatibilityTest.hostBuilder(false)).build();
        for (var stage : OitStage.values()) {
            var pipeline = oit.getPipeline(stage);
            variants.add(new Variant(pipeline.getShaderDefines().asSourceDirectives(), pipeline.getColorTargetStates().size()));
        }
        int count = 0;
        for (String pack : List.of("solstice", "seabreeze")) {
            for (boolean capture : pack.equals("solstice") ? List.of(false,true) : List.of(false)) {
                for (Variant variant : variants) {
                    for (boolean vulkan : List.of(true,false)) {
                        Path dir = Files.createDirectory(temporary.resolve("variant" + count++));
                        for (ShaderType type : List.of(ShaderType.VERTEX, ShaderType.FRAGMENT)) {
                            String extension = type == ShaderType.VERTEX ? "vsh" : "fsh";
                            String program = pack + (capture && type == ShaderType.FRAGMENT ? "_surface" : "_terrain");
                            String source = Files.readString(ASSETS.resolve("greenmarine/shaders/core/" + program + "." + extension));
                            source = flatten(SodiumShaderAdapter.shader(source, type), Path.of(sodium));
                            source = source.replaceFirst("#version 330", "#version 450");
                            String defines = "\n" + variant.defines();
                            source = source.replaceFirst("#version 450", "#version 450" + defines);
                            Files.writeString(dir.resolve(type == ShaderType.VERTEX ? "shader.vert" : "shader.frag"), source);
                        }
                        var command = List.of("glslangValidator", vulkan ? "-V" : "-G", "--auto-map-bindings", "--auto-map-locations", "-l", "shader.vert", "shader.frag");
                        var process = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
                        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        assertEquals(0, process.waitFor(), pack + " capture=" + capture + " vulkan=" + vulkan + "\n" + variant + output);
                        for (String binary : List.of("vert.spv", "frag.spv")) {
                            var validation = new ProcessBuilder("spirv-val", "--target-env", vulkan ? "vulkan1.0" : "opengl4.5", dir.resolve(binary).toString()).redirectErrorStream(true).start();
                            String errors = new String(validation.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                            assertEquals(0, validation.waitFor(), errors);
                        }
                        String vertex = disassemble(dir.resolve("vert.spv"));
                        assertEquals(Set.of("a_Position","a_Color","a_TexCoord","a_LightAndData"), interfaceNames(vertex,"Input"), vertex);
                        String fragment = disassemble(dir.resolve("frag.spv"));
                        assertEquals(capture ? 2 : variant.outputs(), colorSlots(fragment), fragment);
                    }
                }
            }
        }
        assertEquals(30, count);
    }
}
