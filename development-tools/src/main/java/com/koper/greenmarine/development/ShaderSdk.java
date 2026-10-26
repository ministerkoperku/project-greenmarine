package com.koper.greenmarine.development;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Offline authoring catalog, derived from the renderer checkout at resource-processing time. */
final class ShaderSdk {
    record Entry(String id, String title, String category, String text) {}
    private static final String ENGINE = "src/main/resources/assets/greenmarine/";
    private static final String SAMPLES = "src/samples/resources/assets/greenmarine/";
    private static final Pattern ENGINE_INCLUDE = Pattern.compile("#include\\s+[<\"]greenmarine:([^>\"]+)[>\"]");
    private static final List<String> TEMPLATES = List.of("fullscreen", "compute", "world");
    private ShaderSdk() {}

    private static class Catalog {
        static final List<Entry> ENTRIES = readEntries();
    }
    static List<Entry> entries() { return Catalog.ENTRIES; }
    static List<Entry> search(String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return entries();
        return entries().stream().filter(entry -> (entry.id() + "\n" + entry.title() + "\n" + entry.category() + "\n" + entry.text())
            .toLowerCase(Locale.ROOT).contains(needle)).toList();
    }
    static List<String> templates() { return TEMPLATES; }

    static Map<String,String> starter(String namespace, String name, String template) {
        if (namespace == null || !namespace.matches("[a-z0-9_.-]{1,64}") || namespace.contains(".."))
            throw new IllegalArgumentException("Namespace must use 1..64 lowercase resource namespace characters");
        if (name == null || name.isBlank() || name.length() > 80) throw new IllegalArgumentException("Pack name must use 1..80 characters");
        if (!TEMPLATES.contains(template)) throw new IllegalArgumentException("Unknown SDK template: " + template);
        var files = new LinkedHashMap<String,String>();
        String assets = "assets/" + namespace + "/";
        var json = JsonParser.parseString(repository(template.equals("world") ? ENGINE + "greenmarine/packs/raster.json"
            : SAMPLES + "greenmarine/packs/portable.json")).getAsJsonObject();
        json.addProperty("id", namespace + ":main"); json.addProperty("name", name);
        if (template.equals("world")) {
            Map<String,String> shaders = Map.of("terrain.vsh", "seabreeze_terrain.vsh", "terrain.fsh", "raster_terrain.fsh",
                "sky.fsh", "seabreeze_sky.fsh", "clouds.fsh", "seabreeze_clouds.fsh");
            for (var shader : shaders.entrySet()) files.put(assets + "shaders/core/" + shader.getKey(), repository(ENGINE + "shaders/core/" + shader.getValue()));
            for (var element : json.getAsJsonArray("programs")) {
                var program = element.getAsJsonObject(); String target = program.get("target").getAsString();
                program.addProperty("fragment", namespace + ":core/" + target);
                if (program.has("vertex")) program.addProperty("vertex", namespace + ":core/" + target);
            }
        } else {
            json.addProperty("script", namespace + ":greenmarine/scripts/main.lua");
            files.put(assets + "greenmarine/scripts/main.lua", repository(SAMPLES + "greenmarine/scripts/portable.lua"));
            files.put(assets + "shaders/core/grade.fsh", repository(SAMPLES + "shaders/core/pack_grade.fsh"));
            files.put(assets + "shaders/core/lightmap.fsh", repository(SAMPLES + "shaders/core/pack_lightmap.fsh"));
            var passes = json.getAsJsonArray("passes");
            passes.get(0).getAsJsonObject().addProperty("fragment", namespace + ":core/lightmap");
            var grade = passes.get(1).getAsJsonObject(); grade.addProperty("fragment", namespace + ":core/grade"); grade.remove("spirv");
            if (template.equals("compute")) {
                json.add("resources", JsonParser.parseString("""
                    {"images":{"Computed":{"format":"rgba16f","scale":1}},"buffers":{"Statistics":{"size":4}}}
                    """));
                var compute = JsonParser.parseString("""
                    {"name":"generate","stage":"compute","compute":"demo:compute/main","groups":"image:Computed","requires":"compute"}
                    """).getAsJsonObject();
                compute.addProperty("compute", namespace + ":compute/main");
                var scene = JsonParser.parseString("""
                    {"name":"computed","fragment":"demo:core/computed","requires":"compute","inputs":{"ComputedSampler":"Computed"}}
                    """).getAsJsonObject();
                scene.addProperty("fragment", namespace + ":core/computed");
                passes.remove(1); passes.add(compute); passes.add(scene); passes.add(grade);
                files.put(assets + "shaders/compute/main.csh", resource("snippets/compute.csh"));
                files.put(assets + "shaders/core/computed.fsh", resource("snippets/computed.fsh"));
            }
        }
        for (String source : List.copyOf(files.values())) addEngineIncludes(source, files);
        files.put(assets + "greenmarine/packs/main.json", new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json) + "\n");
        files.put("SDK.md", "# " + name.replace('\n', ' ').replace('\r', ' ') + "\n\nTemplate: " + template + "\n\n"
            + "Edit all files under assets/ and reload with F3+T. Install the folder or its ZIP in shaderpacks/greenmarine/. "
            + "Keep assets/ at the ZIP root. Select " + namespace + ":main in GreenMarine settings.\n\n"
            + "The SDK browser contains the current engine documentation, include code and Java-only API source. "
            + "Engine includes used by this project are copied into its private assets. Minecraft includes come from the installed game. "
            + "Keep parameter declaration order; world starters retain reserved slots used by the copied shaders.\n\n"
            + "Compute/storage uses Vulkan and the host device's enabled capabilities; the scene grade fallback remains GLSL. "
            + "World shaders preserve host draw interfaces and require separate renderer/backend acceptance. "
            + "Custom shader IDs do not imply support by the builtin Sodium adapter.\n\n"
            + resource("reference.md"));
        return Collections.unmodifiableMap(files);
    }

    private static void addEngineIncludes(String source, Map<String,String> files) {
        var matcher = ENGINE_INCLUDE.matcher(source);
        while (matcher.find()) {
            String relative = matcher.group(1), path = "assets/greenmarine/shaders/include/" + relative;
            if (files.containsKey(path)) continue;
            String included = repository(ENGINE + "shaders/include/" + relative);
            files.put(path, included); addEngineIncludes(included, files);
        }
    }
    private static List<Entry> readEntries() {
        var result = new ArrayList<Entry>();
        result.add(new Entry("reference/authoring", "Complete schema 1 authoring reference", "Authoring reference", resource("reference.md")));
        for (String type : List.of("scene", "lightmap", "compute", "include", "vertex", "world", "lua", "material", "manifest")) {
            String text = switch (type) {
                case "scene" -> repository(SAMPLES + "shaders/core/pack_grade.fsh");
                case "lightmap" -> repository(SAMPLES + "shaders/core/pack_lightmap.fsh");
                case "compute" -> resource("snippets/compute.csh");
                case "include" -> repository(ENGINE + "shaders/include/frame.glsl");
                case "vertex" -> repository(ENGINE + "shaders/core/seabreeze_terrain.vsh");
                case "world" -> repository(ENGINE + "shaders/core/raster_terrain.fsh");
                case "lua" -> repository(SAMPLES + "greenmarine/scripts/portable.lua");
                case "material" -> resource("snippets/material.json");
                default -> starter("demo", "Editable fullscreen starter", "fullscreen").get("assets/demo/greenmarine/packs/main.json");
            };
            result.add(new Entry("snippet/" + type, type + " template", "Code template", text));
        }
        for (String row : resource("index.tsv").lines().toList()) {
            if (row.isBlank()) continue;
            String[] fields = row.split("\t", -1);
            if (fields.length != 3) throw new IllegalStateException("Invalid SDK index row: " + row);
            result.add(new Entry(fields[0], fields[1], fields[2], repository(fields[0])));
        }
        return List.copyOf(result);
    }
    private static String repository(String path) { return resource("repository/" + path); }
    private static String resource(String path) {
        try (var stream = ShaderSdk.class.getResourceAsStream("/greenmarine-sdk/" + path)) {
            if (stream == null) throw new IllegalStateException("Missing embedded SDK resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) { throw new IllegalStateException("Cannot read embedded SDK resource: " + path, error); }
    }
}
