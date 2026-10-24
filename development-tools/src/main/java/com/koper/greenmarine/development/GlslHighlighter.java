package com.koper.greenmarine.development;

import java.util.Set;

/** One color per char; block comments carry state across lines, so the whole text is scanned at once. */
public final class GlslHighlighter {
    public static final int TEXT = 0xFFE0E0E0, COMMENT = 0xFF7F9F7F, PREPROCESSOR = 0xFFD7A1E8, KEYWORD = 0xFF79B8FF,
        TYPE = 0xFF4EC9B0, NUMBER = 0xFFF0C674, STRING = 0xFFCE9178, BUILTIN = 0xFFDCDCAA;
    private static final Set<String> KEYWORDS = Set.of("if","else","for","while","do","return","break","continue","discard","switch","case","default",
        "struct","uniform","in","out","inout","const","layout","flat","smooth","noperspective","centroid","buffer","shared","readonly","writeonly",
        "coherent","volatile","restrict","precision","highp","mediump","lowp","invariant","true","false","attribute","varying");
    private static final Set<String> TYPES = Set.of("void","bool","int","uint","float","double","vec2","vec3","vec4","ivec2","ivec3","ivec4",
        "uvec2","uvec3","uvec4","bvec2","bvec3","bvec4","dvec2","dvec3","dvec4","mat2","mat3","mat4","mat2x2","mat2x3","mat2x4","mat3x2","mat3x3",
        "mat3x4","mat4x2","mat4x3","mat4x4","sampler2D","sampler3D","samplerCube","sampler2DShadow","sampler2DArray","usampler2D","isampler2D",
        "image2D","image3D","uimage2D","iimage2D","uimage3D","float16_t","f16vec2","f16vec3","f16vec4");
    private GlslHighlighter() {}

    public static int[] colors(String text) {
        int length = text.length(); int[] colors = new int[length];
        int i = 0;
        boolean lineStart = true;
        while (i < length) {
            char c = text.charAt(i);
            if (c == '\n') { colors[i++] = TEXT; lineStart = true; continue; }
            if (c == ' ' || c == '\t' || c == '\r') { colors[i++] = TEXT; continue; }
            int start = i, color;
            if (c == '/' && i + 1 < length && text.charAt(i + 1) == '/') { i = lineEnd(text, i); color = COMMENT; }
            else if (c == '/' && i + 1 < length && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2); i = end < 0 ? length : end + 2; color = COMMENT;
            }
            else if (c == '#' && lineStart) { i = preprocessorEnd(text, i); color = PREPROCESSOR; }
            else if (c == '"') {
                i++; while (i < length && text.charAt(i) != '"' && text.charAt(i) != '\n') i++;
                if (i < length && text.charAt(i) == '"') i++;
                color = STRING;
            }
            else if (Character.isDigit(c) || (c == '.' && i + 1 < length && Character.isDigit(text.charAt(i + 1)))) {
                while (i < length && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '.')) i++;
                color = NUMBER;
            }
            else if (Character.isJavaIdentifierStart(c)) {
                while (i < length && Character.isJavaIdentifierPart(text.charAt(i))) i++;
                String word = text.substring(start, i);
                color = KEYWORDS.contains(word) ? KEYWORD : TYPES.contains(word) ? TYPE : word.startsWith("gl_") ? BUILTIN : TEXT;
            }
            else { i++; color = TEXT; }
            for (int k = start; k < i; k++) colors[k] = text.charAt(k) == '\n' ? TEXT : color;
            lineStart = false;
        }
        return colors;
    }
    private static int lineEnd(String text, int from) { int end = text.indexOf('\n', from); return end < 0 ? text.length() : end; }
    // trailing-backslash macros keep going, otherwise half a #define turns white
    private static int preprocessorEnd(String text, int from) {
        int end = lineEnd(text, from);
        while (end < text.length() && end > from && (text.charAt(end - 1) == '\\' || (text.charAt(end - 1) == '\r' && end > 1 && text.charAt(end - 2) == '\\')))
            end = lineEnd(text, end + 1);
        return end;
    }
}
