package com.koper.greenmarine.client.world;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.util.regex.Pattern;

/** Translate the supported pack terrain ABI; decoding stays in Sodium's own runtime include. */
final class SodiumShaderAdapter {
    private static final Pattern VANILLA_INPUT = Pattern.compile(
        "(?m)^\\s*layout\\s*\\(\\s*location\\s*=\\s*\\d+\\s*\\)\\s*in\\s+\\w+\\s+(?:Position|Color|UV0|UV2|ChunkPosition|ChunkVisibility)\\s*;[ \\t]*$");
    private static final Pattern HOST_INCLUDE = Pattern.compile(
        "(?m)^\\s*#include\\s*<minecraft:(?:globals|projection|terrainglobals|chunksection)\\.glsl>[ \\t]*$");
    private SodiumShaderAdapter() {}

    static String include(String text) { return HOST_INCLUDE.matcher(text).replaceAll(""); }
    static String shader(String text, ShaderType type) {
        String adapted = include(text);
        if (type == ShaderType.VERTEX) {
            adapted = VANILLA_INPUT.matcher(adapted).replaceAll("");
            var main = Pattern.compile("void\\s+main\\s*\\(\\s*\\)\\s*\\{").matcher(adapted);
            if (!main.find()) throw new IllegalArgumentException("Missing supported terrain vertex entrypoint");
            adapted = main.replaceFirst("void greenmarineSodiumMain() {")
                + "\nvoid main() { _vert_init(); greenmarineSodiumMain(); }\n";
        }
        int insertion = adapted.indexOf("#include");
        if (insertion < 0) throw new IllegalArgumentException("Missing supported terrain shader includes");
        String bridge = (type == ShaderType.VERTEX ? "#define GREENMARINE_SODIUM_VERTEX\n" : "")
            + "#include <greenmarine:sodium_terrain.glsl>\n";
        return adapted.substring(0, insertion) + bridge + adapted.substring(insertion);
    }
}
