package com.koper.greenmarine.development.iris;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Preprocessed Iris GLSL (compatibility profile, OpenGL rules) to Vulkan GLSL 460. Host values become
 * members of one std140 block so the runtime can fill them by name; samplers/images get explicit bindings.
 */
public final class IrisTranslator {
    public record Uniform(String type, String name, String array) {}
    public record Opaque(String declaration, String type, String name, int binding) {}
    public record Translation(String text, List<Integer> targets, List<Uniform> uniforms, List<Opaque> opaques, List<String> notes) {}

    public static final int BLOCK_BINDING = 0, FIRST_OPAQUE_BINDING = 1, SSBO_BINDING_BASE = 100;
    private static final Pattern TARGET_PRAGMA = Pattern.compile("(?m)^[ \\t]*#[ \\t]*pragma[ \\t]*iris_rt_([RD])_(\\w+)[ \\t]*$");
    private static final Pattern VERSION_LINE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version[^\\r\\n]*$");
    private static final Pattern LINE_DIRECTIVE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*line[^\\r\\n]*$");
    private static final Pattern EXTENSION = Pattern.compile("(?m)^[ \\t]*#[ \\t]*extension[ \\t]+(\\w+)[^\\r\\n]*$");
    private static final Pattern FRAG_DATA = Pattern.compile("\\bgl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");
    private static final Pattern TEX_COORD = Pattern.compile("\\bgl_TexCoord\\b");
    private static final Pattern SSBO_BINDING = Pattern.compile("(layout\\s*\\([^)]*?\\bbinding\\s*=\\s*)(\\d+)([^)]*\\)\\s*(?:(?:readonly|writeonly|restrict|coherent|volatile)\\s+)*buffer\\b)");
    private static final Set<String> DROPPED_EXTENSIONS = Set.of("GL_EXT_gpu_shader4", "GL_ARB_shader_texture_lod", "GL_ARB_explicit_attrib_location",
        "GL_ARB_shading_language_packing", "GL_ARB_shader_image_load_store", "GL_ARB_gpu_shader5", "GL_ARB_separate_shader_objects",
        "GL_ARB_shader_storage_buffer_object", "GL_ARB_compute_shader", "GL_ARB_texture_gather", "GL_ARB_shader_bit_encoding",
        "GL_EXT_texture_array", "GL_ARB_texture_query_levels", "GL_ARB_shading_language_420pack", "GL_ARB_conservative_depth",
        "GL_AMD_vertex_shader_layer", "GL_ARB_shader_viewport_layer_array");
    private static final Map<String, String> RENAMES = Map.ofEntries(
        Map.entry("texture2D", "texture"), Map.entry("texture3D", "texture"), Map.entry("textureCube", "texture"), Map.entry("texture1D", "texture"),
        Map.entry("texture2DLod", "textureLod"), Map.entry("texture3DLod", "textureLod"), Map.entry("textureCubeLod", "textureLod"),
        Map.entry("texture2DGrad", "textureGrad"), Map.entry("texture2DGradARB", "textureGrad"), Map.entry("texture2DProj", "textureProj"),
        Map.entry("texture2DProjLod", "textureProjLod"), Map.entry("texelFetch2D", "texelFetch"), Map.entry("texture2DLodEXT", "textureLod"),
        Map.entry("shadow2D", "iris_shadow2D"), Map.entry("shadow2DLod", "iris_shadow2DLod"));
    // compat built-ins with a host replacement; types are what the runtime uploads
    private static final Map<String, String> HOST_UNIFORMS = Map.of("iris_ModelViewMat", "mat4", "iris_ModelViewMatInverse", "mat4",
        "iris_ProjMat", "mat4", "iris_ProjMatInverse", "mat4", "iris_TextureMat", "mat4", "iris_NormalMat", "mat3",
        "iris_FogColor", "vec4", "iris_FogStart", "float", "iris_FogEnd", "float", "iris_FogDensity", "float");
    private static final Set<String> OPAQUE_PREFIX = Set.of("sampler", "isampler", "usampler", "image", "iimage", "uimage", "texture", "itexture", "utexture", "atomic_uint", "subpassInput");
    private static final Set<String> QUALIFIERS = Set.of("uniform", "highp", "mediump", "lowp", "readonly", "writeonly", "restrict", "coherent", "volatile", "flat", "smooth", "noperspective");

    private IrisTranslator() {}

    public static Translation translate(String preprocessed, IrisStage stage) {
        var notes = new ArrayList<String>();
        String text = LINE_DIRECTIVE.matcher(preprocessed).replaceAll("");
        List<Integer> targets = targets(text);
        text = TARGET_PRAGMA.matcher(text).replaceAll("");
        text = VERSION_LINE.matcher(text).replaceAll("");
        var extensions = new StringBuilder();
        var extensionMatcher = EXTENSION.matcher(text);
        while (extensionMatcher.find()) if (!DROPPED_EXTENSIONS.contains(extensionMatcher.group(1))) extensions.append(extensionMatcher.group().strip()).append('\n');
        text = EXTENSION.matcher(text).replaceAll("");

        // reserved words in Vulkan GLSL that OpenGL packs use as plain names
        text = text.replaceAll("\\b(sampler|texture2DArray|subpassInput|demote)\\b(?!\\s*\\()", "iris_$1_");
        text = renameCalls(text);
        text = compatBuiltins(text, stage, notes);
        text = storage(text, stage);
        var outputs = new TreeSet<Integer>();
        if (stage == IrisStage.FRAGMENT) {
            text = text.replaceAll("\\bgl_FragColor\\b", "iris_FragData_0");
            var frag = FRAG_DATA.matcher(text); var sb = new StringBuilder();
            while (frag.find()) { outputs.add(Integer.parseInt(frag.group(1))); frag.appendReplacement(sb, "iris_FragData_" + frag.group(1)); }
            frag.appendTail(sb); text = sb.toString();
            if (text.contains("iris_FragData_0")) outputs.add(0);
            if (Pattern.compile("\\bgl_FragData\\b").matcher(text).find()) notes.add("gl_FragData indexed dynamically; not translated");
        }
        text = SSBO_BINDING.matcher(text).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + (SSBO_BINDING_BASE + Integer.parseInt(m.group(2))) + m.group(3)));

        var uniforms = new ArrayList<Uniform>(); var opaques = new ArrayList<Opaque>();
        int[] insertAt = {-1};
        text = extractUniforms(text, uniforms, opaques, insertAt);
        for (var host : HOST_UNIFORMS.entrySet())
            if (Pattern.compile("\\b" + host.getKey() + "\\b").matcher(text).find() && uniforms.stream().noneMatch(u -> u.name().equals(host.getKey())))
                uniforms.add(new Uniform(host.getValue(), host.getKey(), ""));
        uniforms.sort(Comparator.comparing(Uniform::name));

        var header = new StringBuilder("#version 460\n").append(extensions);
        header.append(helpers(text, stage));
        if (stage == IrisStage.FRAGMENT) for (int output : outputs) header.append("layout(location = ").append(output).append(") out vec4 iris_FragData_").append(output).append(";\n");
        var block = new StringBuilder();
        if (!uniforms.isEmpty()) {
            block.append("layout(std140, set = 0, binding = ").append(BLOCK_BINDING).append(") uniform IrisUniforms {\n");
            for (var u : uniforms) block.append("    ").append(u.type()).append(' ').append(u.name()).append(u.array()).append(";\n");
            block.append("};\n");
        }
        String body = insertAt[0] < 0 ? block + text : text.substring(0, insertAt[0]) + "\n" + block + text.substring(insertAt[0]);
        return new Translation(header + body, targets, List.copyOf(uniforms), List.copyOf(opaques), List.copyOf(notes));
    }

    static List<Integer> targets(String text) {
        var matcher = TARGET_PRAGMA.matcher(text); List<Integer> found = List.of();
        while (matcher.find()) {
            var list = new ArrayList<Integer>();
            if (matcher.group(1).equals("D")) for (char c : matcher.group(2).toCharArray()) { if (Character.isDigit(c)) list.add(c - '0'); }
            else for (String part : matcher.group(2).split("_")) if (!part.isBlank()) list.add(Integer.parseInt(part));
            found = list;
        }
        return found;
    }

    private static String renameCalls(String text) {
        var matcher = Pattern.compile("\\b(\\w+)(\\s*\\()").matcher(text); var sb = new StringBuilder();
        while (matcher.find()) {
            String replacement = RENAMES.get(matcher.group(1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement == null ? matcher.group() : replacement + matcher.group(2)));
        }
        return matcher.appendTail(sb).toString();
    }

    private static String compatBuiltins(String text, IrisStage stage, List<String> notes) {
        text = text.replaceAll("\\bgl_ModelViewProjectionMatrix\\b", "(iris_ProjMat * iris_ModelViewMat)")
            .replaceAll("\\bgl_ModelViewMatrixInverse\\b", "iris_ModelViewMatInverse").replaceAll("\\bgl_ModelViewMatrix\\b", "iris_ModelViewMat")
            .replaceAll("\\bgl_ProjectionMatrixInverse\\b", "iris_ProjMatInverse").replaceAll("\\bgl_ProjectionMatrix\\b", "iris_ProjMat")
            .replaceAll("\\bgl_NormalMatrix\\b", "iris_NormalMat")
            .replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*0\\s*\\]", "iris_TextureMat").replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*[12]\\s*\\]", "iris_LightmapTextureMatrix")
            .replaceAll("\\bgl_Fog\\.color\\b", "iris_FogColor").replaceAll("\\bgl_Fog\\.start\\b", "iris_FogStart")
            .replaceAll("\\bgl_Fog\\.end\\b", "iris_FogEnd").replaceAll("\\bgl_Fog\\.density\\b", "iris_FogDensity")
            .replaceAll("\\bgl_Fog\\.scale\\b", "(1.0 / (iris_FogEnd - iris_FogStart))");
        if (stage == IrisStage.VERTEX) {
            text = text.replaceAll("\\bftransform\\s*\\(\\s*\\)", "(iris_ProjMat * iris_ModelViewMat * vec4(iris_Position, 1.0))")
                .replaceAll("\\bgl_Vertex\\b", "vec4(iris_Position, 1.0)").replaceAll("\\bgl_Normal\\b", "iris_Normal")
                .replaceAll("\\bgl_Color\\b", "iris_Color").replaceAll("\\bgl_MultiTexCoord0\\b", "vec4(iris_UV0, 0.0, 1.0)")
                .replaceAll("\\bgl_MultiTexCoord[12]\\b", "vec4(iris_UV2, 0.0, 1.0)").replaceAll("\\bgl_MultiTexCoord3\\b", "vec4(0.0, 0.0, 0.0, 1.0)")
                .replaceAll("\\bgl_FrontColor\\b", "iris_FrontColor").replaceAll("\\bgl_FogFragCoord\\b", "iris_FogFragCoord");
        } else if (stage == IrisStage.FRAGMENT) {
            text = text.replaceAll("\\bgl_Color\\b", "iris_FrontColor").replaceAll("\\bgl_FogFragCoord\\b", "iris_FogFragCoord");
        }
        if (TEX_COORD.matcher(text).find()) {
            if (stage == IrisStage.VERTEX || stage == IrisStage.FRAGMENT) text = TEX_COORD.matcher(text).replaceAll("iris_TexCoord");
            else notes.add("gl_TexCoord in " + stage + " left untranslated");
        }
        return text;
    }

    private static String storage(String text, IrisStage stage) {
        if (stage == IrisStage.VERTEX) text = text.replaceAll("(?m)^(\\s*)attribute\\b", "$1in").replaceAll("(?m)^(\\s*(?:(?:flat|smooth|noperspective|centroid|invariant)\\s+)*)varying\\b", "$1out");
        if (stage == IrisStage.FRAGMENT) text = text.replaceAll("(?m)^(\\s*(?:(?:flat|smooth|noperspective|centroid|invariant)\\s+)*)varying\\b", "$1in");
        return text;
    }

    private static String helpers(String text, IrisStage stage) {
        var out = new StringBuilder();
        if (has(text, "iris_LightmapTextureMatrix"))
            out.append("const mat4 iris_LightmapTextureMatrix = mat4(vec4(0.00390625, 0.0, 0.0, 0.0), vec4(0.0, 0.00390625, 0.0, 0.0), vec4(0.0, 0.0, 0.00390625, 0.0), vec4(0.03125, 0.03125, 0.03125, 1.0));\n");
        if (stage == IrisStage.VERTEX) {
            if (has(text, "iris_Position")) out.append("layout(location = 0) in vec3 iris_Position;\n");
            if (has(text, "iris_Color")) out.append("layout(location = 1) in vec4 iris_Color;\n");
            if (has(text, "iris_UV0")) out.append("layout(location = 2) in vec2 iris_UV0;\n");
            if (has(text, "iris_UV2")) out.append("layout(location = 4) in vec2 iris_UV2;\n");
            if (has(text, "iris_Normal")) out.append("layout(location = 5) in vec3 iris_Normal;\n");
            if (has(text, "iris_FrontColor")) out.append("out vec4 iris_FrontColor;\n");
            if (has(text, "iris_FogFragCoord")) out.append("out float iris_FogFragCoord;\n");
            if (has(text, "iris_TexCoord")) out.append("out vec4 iris_TexCoord[4];\n");
        }
        if (stage == IrisStage.FRAGMENT) {
            if (has(text, "iris_FrontColor")) out.append("in vec4 iris_FrontColor;\n");
            if (has(text, "iris_FogFragCoord")) out.append("in float iris_FogFragCoord;\n");
            if (has(text, "iris_TexCoord")) out.append("in vec4 iris_TexCoord[4];\n");
        }
        if (has(text, "iris_shadow2D")) out.append("vec4 iris_shadow2D(sampler2DShadow s, vec3 c) { return vec4(texture(s, c)); }\n");
        if (has(text, "iris_shadow2DLod")) out.append("vec4 iris_shadow2DLod(sampler2DShadow s, vec3 c, float lod) { return vec4(textureLod(s, c, lod)); }\n");
        return out.toString();
    }
    private static boolean has(String text, String name) { return Pattern.compile("\\b" + name + "\\b").matcher(text).find(); }

    /** Walks top-level statements only; anything inside braces or parentheses is never a global uniform. */
    private static String extractUniforms(String text, List<Uniform> uniforms, List<Opaque> opaques, int[] insertAt) {
        var out = new StringBuilder(text.length()); var seen = new HashSet<String>();
        int depth = 0, statement = 0, binding = FIRST_OPAQUE_BINDING;
        boolean functionBody = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '#' && depth == 0 && text.substring(statement, i).isBlank()) {
                int end = text.indexOf('\n', i); end = end < 0 ? text.length() : end;
                out.append(text, statement, end); statement = end; i = end - 1; continue;
            }
            if (c == '{' && depth == 0) {
                int before = i - 1; while (before >= statement && Character.isWhitespace(text.charAt(before))) before--;
                functionBody = before >= statement && text.charAt(before) == ')';
            }
            if (c == '{' || c == '(' || c == '[') { depth++; continue; }
            if (c == '}' || c == ')' || c == ']') {
                depth--;
                // a function body ends its statement; struct and block bodies run on to their ';'
                if (c == '}' && depth == 0 && functionBody) { out.append(text, statement, i + 1); statement = i + 1; functionBody = false; }
                continue;
            }
            if (c != ';' || depth != 0) continue;
            String raw = text.substring(statement, i + 1);
            String declaration = raw.strip();
            if (declaration.contains("{") || !Pattern.compile("(^|\\s|\\))uniform\\s").matcher(declaration).find()) { out.append(raw); statement = i + 1; continue; }
            String leading = raw.substring(0, raw.indexOf(declaration.charAt(0)));
            String layout = "";
            String rest = declaration.substring(0, declaration.length() - 1).strip();
            if (rest.startsWith("layout")) {
                int close = matching(rest, rest.indexOf('('));
                layout = rest.substring(0, close + 1); rest = rest.substring(close + 1).strip();
            }
            var words = new ArrayList<>(Arrays.asList(rest.split("\\s+", -1)));
            var qualifiers = new ArrayList<String>();
            while (!words.isEmpty() && QUALIFIERS.contains(words.getFirst())) qualifiers.add(words.removeFirst());
            if (words.isEmpty()) { out.append(raw); statement = i + 1; continue; }
            String type = words.removeFirst(), declarators = String.join(" ", words);
            boolean opaque = OPAQUE_PREFIX.stream().anyMatch(type::startsWith);
            var kept = new StringBuilder(leading);
            for (String declarator : splitTop(declarators)) {
                String d = declarator.strip(); int eq = topLevelEquals(d);
                if (eq >= 0) d = d.substring(0, eq).strip();
                int bracket = d.indexOf('[');
                String name = bracket < 0 ? d : d.substring(0, bracket).strip(), array = bracket < 0 ? "" : d.substring(bracket).replace(" ", "");
                if (name.isEmpty() || !seen.add(name)) continue;
                if (opaque) {
                    String newLayout = layout.isEmpty() ? "layout(binding = " + binding + ")" : layout.contains("binding")
                        ? layout : layout.substring(0, layout.length() - 1) + ", binding = " + binding + ")";
                    String q = qualifiers.stream().filter(qq -> !qq.equals("uniform")).reduce("", (a, b) -> a + b + " ");
                    String line = newLayout + " " + q + "uniform " + type + " " + name + array + ";";
                    opaques.add(new Opaque(line, type, name, layout.contains("binding") ? -1 : binding));
                    if (!layout.contains("binding")) binding++;
                    kept.append(line).append('\n');
                } else {
                    uniforms.add(new Uniform(type, name, array));
                    if (insertAt[0] < 0) insertAt[0] = -2; // resolved below to the current output position
                }
            }
            if (insertAt[0] == -2) insertAt[0] = out.length();
            out.append(kept);
            statement = i + 1;
        }
        out.append(text.substring(Math.min(statement, text.length())));
        return out.toString();
    }
    private static int matching(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) { if (text.charAt(i) == '(') depth++; else if (text.charAt(i) == ')' && --depth == 0) return i; }
        return text.length() - 1;
    }
    private static List<String> splitTop(String text) {
        var parts = new ArrayList<String>(); int depth = 0, start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++; else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) { parts.add(text.substring(start, i)); start = i + 1; }
        }
        parts.add(text.substring(start)); return parts;
    }
    private static int topLevelEquals(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[') depth++; else if (c == ')' || c == ']') depth--; else if (c == '=' && depth == 0) return i;
        }
        return -1;
    }
}
