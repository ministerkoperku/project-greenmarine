package com.koper.greenmarine.development.iris;

import java.util.*;
import java.util.regex.*;

/** Opt-in Iris terrain semantic lowering for Minecraft's unchanged 28-byte BLOCK vertices.
 * Requires current captured sidecars and separate section draws; source attribute locations do not
 * define host semantics. This adapter does not establish pack runtime readiness or material mappings.
 */
public final class IrisTerrainAdapter {
    public enum ColorProfile { IRIS_SEPARATE_AO, IRIS_MULTIPLIED_AO, HOST_LIT }
    public enum ClipDepth { OPENGL, ZERO_TO_ONE, ZERO_TO_ONE_REVERSED }
    public record Adaptation(IrisProgramLinker.Program program, int verticesBinding, int drawsBinding,
                             Set<String> nativeBlocks, ColorProfile colorProfile, ClipDepth clipDepth) {}
    private static final Set<String> HOST_MATRICES = Set.of("iris_ModelViewMat", "iris_ModelViewMatInverse",
        "iris_NormalMat", "iris_ProjMat", "iris_ProjMatInverse", "iris_TextureMat");
    private static final Set<String> NATIVE_BLOCKS = Set.of("TerrainUniform", "Projection", "Globals", "ChunkSection",
        "MarineTerrainVertices", "MarineTerrainDraws");
    private static final Pattern UNIFORM_BLOCK = Pattern.compile("layout\\(std140, set = 0, binding = 0\\) uniform IrisUniforms \\{[^}]*};\\s*");
    private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
    private IrisTerrainAdapter() {}

    /** Minecraft 26.3 Vulkan's actual projection uses reversed zero-to-one depth. */
    public static Adaptation adapt(IrisProgramLinker.Program linked) {
        return adapt(linked, ColorProfile.IRIS_SEPARATE_AO, ClipDepth.ZERO_TO_ONE_REVERSED);
    }

    public static Adaptation adapt(IrisProgramLinker.Program linked, ColorProfile profile, ClipDepth depth) {
        return adapt(linked,profile,depth,false);
    }
    public static Adaptation adapt(IrisProgramLinker.Program linked, ColorProfile profile, ClipDepth depth,boolean cutout) {
        Objects.requireNonNull(linked); Objects.requireNonNull(profile); Objects.requireNonNull(depth);
        if (!linked.stages().containsKey(IrisStage.VERTEX)) throw invalid("requires a vertex program");
        IrisStage rasterOutput = linked.stages().containsKey(IrisStage.GEOMETRY) ? IrisStage.GEOMETRY
            : linked.stages().containsKey(IrisStage.TESS_EVALUATION) ? IrisStage.TESS_EVALUATION : IrisStage.VERTEX;
        for (var block : linked.blocks()) if (NATIVE_BLOCKS.contains(block.name())) throw invalid("reserved native resource " + block.name());
        for (var translation : linked.stages().values()) if (Pattern.compile("\\bgm_\\w+").matcher(translation.text()).find())
            throw invalid("reserved gm_ terrain adapter namespace is already used");
        var occupied = new HashSet<Integer>(); occupied.add(IrisTranslator.BLOCK_BINDING);
        linked.blocks().forEach(b -> occupied.add(b.binding())); linked.opaques().forEach(o -> occupied.add(o.binding()));
        var blocks = new ArrayList<>(linked.blocks()); var added = new LinkedHashMap<String, IrisProgramLinker.ResourceBlock>();
        add(added, occupied, "uniform", "TerrainUniform", "mat4 gm_TerrainModelView; ivec2 gm_TextureSize;");
        add(added, occupied, "uniform", "Projection", "mat4 gm_TerrainProjection;");
        add(added, occupied, "uniform", "Globals", "ivec3 gm_CameraBlockPos; float gm_GlintAlpha; vec3 gm_CameraOffset; float gm_GameTime; vec2 gm_ScreenSize; int gm_MenuBlurRadius; int gm_UseRgss;");
        add(added, occupied, "uniform", "ChunkSection", "ivec3 gm_ChunkPosition; float gm_ChunkVisibility;");
        add(added, occupied, "buffer", "MarineTerrainVertices", "uvec4 marineTerrainVertices[];");
        add(added, occupied, "buffer", "MarineTerrainDraws", "ivec4 marineTerrainDraws[];");
        blocks.addAll(added.values());
        var uniforms = linked.uniforms().stream().filter(u -> !HOST_MATRICES.contains(u.name())).toList();
        String uniformBlock = uniformBlock(uniforms);
        var stages = new EnumMap<IrisStage, IrisTranslator.Translation>(IrisStage.class);
        for (var entry : linked.stages().entrySet()) {
            var original = entry.getValue(); String source = original.text();
            if (entry.getKey() == IrisStage.VERTEX) source = vertex(source, profile, rasterOutput == IrisStage.VERTEX ? depth : ClipDepth.OPENGL);
            else if (entry.getKey() == rasterOutput && rasterOutput == IrisStage.TESS_EVALUATION) source = finalMain(source, depth);
            else if (entry.getKey() == IrisStage.GEOMETRY) source = geometry(source, depth);
            if(entry.getKey()==IrisStage.FRAGMENT && depth==ClipDepth.ZERO_TO_ONE_REVERSED)source=IrisFragmentDepth.adapt(source);
            if(entry.getKey()==IrisStage.FRAGMENT && cutout)source=alphaTest(source);
            source = UNIFORM_BLOCK.matcher(source).replaceAll(Matcher.quoteReplacement(uniformBlock));
            for (String name : HOST_MATRICES) source = source.replaceAll("\\b" + name + "\\b", Matcher.quoteReplacement(matrix(name)));
            for(var opaque:linked.opaques()) {
                String host=hostSampler(opaque);
                if(host!=null)source=source.replaceAll("\\b"+Pattern.quote(opaque.name())+"\\b(?!\\s*\\()",host);
            }
            var header = new StringBuilder();
            // Preserve the version and extension directive ordering required by GLSL.
            for (var block : added.values()) if (entry.getKey() == IrisStage.VERTEX || block.storage().equals("uniform")) header.append(block.declaration()).append('\n');
            header.append(projection(depth));
            source = insertHeader(source, header.toString());
            var notes = new ArrayList<>(original.notes());
            notes.add("Native terrain ABI: current 32-byte sidecars, separate host section draws, actual host matrices; material mapping must match capture generation");
            stages.put(entry.getKey(), new IrisTranslator.Translation(source, original.targets(), uniforms, original.opaques(), List.copyOf(notes)));
        }
        return new Adaptation(new IrisProgramLinker.Program(Collections.unmodifiableMap(stages), uniforms,
            linked.opaques(), linked.varyings(), List.copyOf(blocks)), added.get("MarineTerrainVertices").binding(),
            added.get("MarineTerrainDraws").binding(), NATIVE_BLOCKS, profile, depth);
    }

    private static String vertex(String source, ColorProfile profile, ClipDepth depth) {
        var attributes = IrisProgramLinker.vertexAttributes(source); var body = new StringBuilder(source);
        var assignments = new StringBuilder();
        for (var attribute : attributes) assignments.append("    ").append(attribute.name()).append(" = ").append(value(attribute, profile)).append(";\n");
        for (int i = attributes.size() - 1; i >= 0; i--) {
            var attribute = attributes.get(i);
            body.replace(attribute.start(), attribute.end(), attribute.type() + " " + attribute.name() + ";");
        }
        var main = MAIN.matcher(body); if (!main.find()) throw invalid("vertex main is missing");
        String renamed = main.replaceFirst("void gm_TerrainOriginalMain()");
        if (MAIN.matcher(renamed).find()) throw invalid("multiple vertex main declarations");
        return insertHeader(renamed, """
            layout(location = 0) in vec3 Position;
            layout(location = 1) in vec4 Color;
            layout(location = 2) in vec2 UV0;
            layout(location = 3) in ivec2 UV2;
            ivec4 gm_SignedBytes(uint word) {
                return ivec4(int(word << 24u) >> 24, int(word << 16u) >> 24, int(word << 8u) >> 24, int(word) >> 24);
            }
            """) + """
            \nvoid main() {
                ivec4 gm_Draw = marineTerrainDraws[gl_InstanceIndex];
                int gm_Vertex = gm_Draw.y + gl_VertexIndex - gm_Draw.x;
                if (gm_Draw.w == 0 || gm_Vertex < gm_Draw.y || gm_Vertex >= gm_Draw.y + gm_Draw.z) {
                    gl_Position = vec4(0.0, 0.0, 0.0, 0.0);
                    return;
                }
                uvec4 gm_Record0 = marineTerrainVertices[2 * gm_Vertex];
                uvec4 gm_Record1 = marineTerrainVertices[2 * gm_Vertex + 1];
                vec4 gm_RawColor = unpackUnorm4x8(gm_Record1.z).bgra;
                ivec4 gm_Mid = gm_SignedBytes(gm_Record0.w);
            """ + assignments + "    gm_TerrainOriginalMain();\n" + clipOutput(depth) + "}\n";
    }

    private static String finalMain(String source, ClipDepth depth) {
        var main = MAIN.matcher(source);
        if (!main.find()) throw invalid("final position stage main is missing");
        return main.replaceFirst("void gm_TerrainOriginalMain()")
            + "\nvoid main() { gm_TerrainOriginalMain();\n" + clipOutput(depth) + "}\n";
    }

    private static String geometry(String source, ClipDepth depth) {
        if (depth == ClipDepth.OPENGL) return source;
        // Restore Iris clip coordinates after every emission, so subsequent geometry math sees
        // the original value even when one output position is emitted more than once.
        if (Pattern.compile("\\bEmitStreamVertex\\s*\\(").matcher(source).find())
            throw invalid("geometry stream emission requires a stream-specific clip adapter");
        source = source.replaceAll("\\bEmitVertex\\s*\\(", "gm_TerrainEmitVertex(");
        return insertHeader(source, "void gm_TerrainEmitVertex();\n") + """
            \nvoid gm_TerrainEmitVertex() {
                vec4 gm_SavedPosition = gl_Position;
            """ + clipOutput(depth) + "    EmitVertex();\n    gl_Position = gm_SavedPosition;\n}\n";
    }

    private static String value(IrisProgramLinker.VertexAttribute a, ColorProfile profile) {
        if (!a.array().isEmpty()) throw invalid("array terrain attribute " + a.name());
        String name = a.name(), type = a.type();
        return switch (name) {
            case "iris_Position", "vaPosition" -> typed(a, "vec3", "Position + vec3(gm_ChunkPosition - gm_CameraBlockPos) + gm_CameraOffset");
            // Capture retains the host ARGB integer: low bytes are blue, green, red, raw AO.
            case "iris_Color", "vaColor" -> typed(a, "vec4", switch(profile) {
                case IRIS_SEPARATE_AO->"unpackUnorm4x8(gm_Record1.z).bgra";
                case IRIS_MULTIPLIED_AO->"vec4(gm_RawColor.rgb * gm_RawColor.a, 1.0)";
                case HOST_LIT->"Color";
            });
            case "iris_UV0", "vaUV0" -> typed(a, "vec2", "UV0");
            case "iris_UV2" -> typed(a, "vec2", "vec2(UV2)");
            case "vaUV2" -> typed(a, "ivec2", "UV2");
            case "iris_Normal", "vaNormal" -> typed(a, "vec3", "max(vec3(gm_SignedBytes(gm_Record0.x).xyz) / 127.0, vec3(-1.0))");
            case "at_tangent" -> typed(a, "vec4", "max(vec4(gm_SignedBytes(gm_Record0.y)) / 127.0, vec4(-1.0))");
            case "mc_midTexCoord" -> {
                if (type.equals("vec2")) yield "uintBitsToFloat(gm_Record1.xy)";
                yield typed(a, "vec4", "vec4(uintBitsToFloat(gm_Record1.xy), 0.0, 1.0)");
            }
            case "at_midBlock" -> {
                if (type.equals("vec3")) yield "vec3(gm_Mid.xyz)";
                yield typed(a, "vec4", "vec4(vec3(gm_Mid.xyz), float(gm_Record1.w & 15u))");
            }
            case "mc_Entity" -> {
                if (type.equals("float")) yield "float(int(gm_Record0.z))";
                if (type.equals("int")) yield "int(gm_Record0.z)";
                if (type.equals("ivec2")) yield "ivec2(int(gm_Record0.z), gm_Mid.w)";
                if (type.equals("ivec3")) yield "ivec3(int(gm_Record0.z), gm_Mid.w, 0)";
                if (type.equals("ivec4")) yield "ivec4(int(gm_Record0.z), gm_Mid.w, 0, 1)";
                if (type.equals("vec2")) yield "vec2(float(int(gm_Record0.z)), float(gm_Mid.w))";
                if (type.equals("vec3")) yield "vec3(float(int(gm_Record0.z)), float(gm_Mid.w), 0.0)";
                yield typed(a, "vec4", "vec4(float(int(gm_Record0.z)), float(gm_Mid.w), 0.0, 1.0)");
            }
            default -> throw invalid("unsupported terrain attribute " + name + " (" + type + ")");
        };
    }
    private static String alphaTest(String source) {
        var output=Pattern.compile("layout\\s*\\([^)]*\\blocation\\s*=\\s*0\\s*[^)]*\\)\\s*out\\s+vec4\\s+(\\w+)\\s*;").matcher(source);
        if(!output.find())throw invalid("cutout alpha test requires a vec4 output at location zero");
        String color=output.group(1);var main=MAIN.matcher(source);
        if(!main.find())throw invalid("fragment main is missing");
        return main.replaceFirst("void gm_TerrainOriginalFragment()")+"\nvoid main(){gm_TerrainOriginalFragment();if (!("+color+".a > 0.1)) discard;}\n";
    }
    private static String typed(IrisProgramLinker.VertexAttribute a, String expected, String value) {
        if (!a.type().equals(expected)) throw invalid("terrain attribute " + a.name() + " requires " + expected + ", got " + a.type());
        return value;
    }
    private static String matrix(String name) {
        return switch(name) {
            case "iris_ModelViewMat" -> "gm_TerrainModelView";
            case "iris_ModelViewMatInverse" -> "inverse(gm_TerrainModelView)";
            case "iris_NormalMat" -> "transpose(inverse(mat3(gm_TerrainModelView)))";
            case "iris_ProjMat" -> "gm_IrisProjection()";
            case "iris_ProjMatInverse" -> "inverse(gm_IrisProjection())";
            case "iris_TextureMat" -> "mat4(1.0)";
            default -> throw new AssertionError(name);
        };
    }
    /** These descriptors are bound by the actual host terrain draw, using the host sampler. */
    public static String hostSampler(IrisTranslator.Opaque opaque) {
        if(!opaque.type().equals("sampler2D"))return null;
        return switch(opaque.name()) {
            case "tex","texture","gtexture"->"Sampler0";
            case "lightmap"->"Sampler2";
            default->null;
        };
    }
    private static String projection(ClipDepth depth) {
        String row = switch(depth) {
            case OPENGL -> "";
            case ZERO_TO_ONE -> "    for (int c=0;c<4;c++) p[c][2] = 2.0*p[c][2] - p[c][3];\n";
            case ZERO_TO_ONE_REVERSED -> "    for (int c=0;c<4;c++) p[c][2] = p[c][3] - 2.0*p[c][2];\n";
        };
        return "mat4 gm_IrisProjection() {\n    mat4 p = gm_TerrainProjection;\n" + row + "    return p;\n}\n";
    }
    private static String clipOutput(ClipDepth depth) {
        return switch(depth) {
            case OPENGL -> "";
            case ZERO_TO_ONE -> "    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n";
            case ZERO_TO_ONE_REVERSED -> "    gl_Position.z = (gl_Position.w - gl_Position.z) * 0.5;\n";
        };
    }
    private static String uniformBlock(List<IrisTranslator.Uniform> uniforms) {
        if (uniforms.isEmpty()) return "";
        var text = new StringBuilder("layout(std140, set = 0, binding = 0) uniform IrisUniforms {\n");
        for (var u : uniforms) text.append("    ").append(u.type()).append(' ').append(u.name()).append(u.array()).append(";\n");
        return text.append("};\n").toString();
    }
    private static void add(Map<String, IrisProgramLinker.ResourceBlock> blocks, Set<Integer> occupied, String storage, String name, String body) {
        int binding = 1; while (occupied.contains(binding)) binding++; occupied.add(binding);
        String declaration = "layout(" + (storage.equals("buffer") ? "std430" : "std140") + ", set = 0, binding = " + binding + ") "
            + (storage.equals("buffer") ? "readonly " : "") + storage + " " + name + " { " + body + " };";
        blocks.put(name, new IrisProgramLinker.ResourceBlock(storage, name, declaration, binding));
    }
    private static String insertHeader(String source, String header) {
        int at = source.indexOf('\n') + 1;
        var extensions = Pattern.compile("(?m)^#extension[^\\n]*\\n").matcher(source);
        while (extensions.find()) at = extensions.end();
        return source.substring(0, at) + header + source.substring(at);
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("Iris terrain adapter: " + message); }
}
