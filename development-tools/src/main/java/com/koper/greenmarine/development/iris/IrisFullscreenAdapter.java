package com.koper.greenmarine.development.iris;

import java.util.*;
import java.util.regex.*;

/** Preserves source fullscreen vertex code on Iris's real unit quad, emitted as two triangles. */
public final class IrisFullscreenAdapter {
    public record Adaptation(IrisProgramLinker.Program program,int vertices) {}
    private static final String QUAD="""
        int gm_IrisQuadIndex(){const int indices[6]=int[6](0,1,2,2,3,0);return indices[gl_VertexIndex];}
        vec2 gm_IrisQuadUv(){const vec2 corners[4]=vec2[4](vec2(0,0),vec2(1,0),vec2(1,1),vec2(0,1));return corners[gm_IrisQuadIndex()];}
        """;
    private static final String PROJECTION="mat4(vec4(2,0,0,0),vec4(0,2,0,0),vec4(0),vec4(-1,-1,0,1))";
    private static final Pattern INPUT=Pattern.compile("(?m)(?:layout\\s*\\([^)]*\\)\\s*)?\\b(?:in|attribute)\\s+(vec[234])\\s+(\\w+)\\s*;");
    private IrisFullscreenAdapter() {}
    public static Adaptation link(Map<IrisStage,String> sources) {
        if(!sources.keySet().equals(Set.of(IrisStage.VERTEX,IrisStage.FRAGMENT)))
            throw new IllegalArgumentException("Fullscreen adaptation requires a vertex/fragment pair; intermediate stages need explicit execution support");
        var prepared=new EnumMap<IrisStage,String>(IrisStage.class);
        for(var entry:sources.entrySet()) {
            boolean vertex=entry.getKey()==IrisStage.VERTEX;String text=entry.getValue();
            text=text.replaceAll("\\bgl_ModelViewProjectionMatrix\\b","("+PROJECTION+" * mat4(1.0))")
                .replaceAll("\\bgl_ModelViewMatrixInverse\\b|\\bgl_ModelViewMatrix\\b","mat4(1.0)")
                .replaceAll("\\bgl_ProjectionMatrix\\b",PROJECTION)
                .replaceAll("\\bgl_NormalMatrix\\b","mat3(1.0)")
                .replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*[0-7]\\s*\\]","mat4(1.0)")
                .replaceAll("\\bgl_Color\\b","vec4(1.0)");
            if(vertex) {
                var replacements=Map.of("vaPosition","vec3(gm_IrisQuadUv(),0.0)","Position","vec3(gm_IrisQuadUv(),0.0)",
                    "vaUV0","gm_IrisQuadUv()","UV0","gm_IrisQuadUv()");
                var matcher=INPUT.matcher(text);var body=new StringBuilder();var active=new LinkedHashMap<String,String>();
                while(matcher.find()) {
                    String name=matcher.group(2),value=replacements.get(name);
                    if(value==null)throw new IllegalArgumentException("Unsupported fullscreen vertex attribute: "+name);
                    String expected=name.endsWith("Position")?"vec3":"vec2";
                    if(!matcher.group(1).equals(expected))throw new IllegalArgumentException("Unsupported fullscreen attribute type: "+name);
                    active.put(name,value);matcher.appendReplacement(body,"");
                }
                matcher.appendTail(body);text=body.toString();
                for(var replacement:active.entrySet())text=text.replaceAll("\\b"+replacement.getKey()+"\\b",Matcher.quoteReplacement("("+replacement.getValue()+")"));
                text=text.replaceAll("\\bftransform\\s*\\(\\s*\\)","("+PROJECTION+" * vec4(gm_IrisQuadUv(),0.0,1.0))")
                    .replaceAll("\\bgl_Vertex\\b","vec4(gm_IrisQuadUv(),0.0,1.0)")
                    .replaceAll("\\bgl_MultiTexCoord0\\b","vec4(gm_IrisQuadUv(),0.0,1.0)")
                    .replaceAll("\\bgl_MultiTexCoord[1-7]\\b","vec4(0,0,0,1)")
                    .replaceAll("\\bgl_Normal\\b","vec3(0,0,1)")
                    .replaceAll("\\bgl_VertexID\\b","gm_IrisQuadIndex()");
                var main=Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
                if(!main.matcher(text).find())throw new IllegalArgumentException("Fullscreen vertex shader has no main");
                text=QUAD+main.matcher(text).replaceFirst("void gm_IrisSourceMain()")+"\nvoid main(){gm_IrisSourceMain();gl_Position.z=0.5*(gl_Position.w-gl_Position.z);}\n";
            }
            prepared.put(entry.getKey(),text);
        }
        return new Adaptation(IrisFragmentDepth.adapt(IrisProgramLinker.link(prepared)),6);
    }
}
