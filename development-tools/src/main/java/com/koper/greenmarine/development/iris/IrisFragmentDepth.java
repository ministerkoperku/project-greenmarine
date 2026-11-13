package com.koper.greenmarine.development.iris;

import java.util.*;
import java.util.function.Function;
import java.util.regex.*;
import java.nio.*;

/** Source forward window depth on Minecraft's reversed-depth Vulkan pipelines. */
public final class IrisFragmentDepth {
    private static final Pattern COMMENTS=Pattern.compile("//[^\\r\\n]*|/\\*[\\s\\S]*?\\*/");
    private static final Pattern TOKENS=Pattern.compile("\\bgl_FragCoord\\b|\\bgl_FragDepth\\b");
    private static final Pattern DEPTH_DECL=Pattern.compile("\\b(?:(?:layout\\s*\\([^)]*\\)|invariant|precise|out|highp|mediump|lowp)\\s*)+float\\s+gl_FragDepth\\s*;");
    private static final Pattern INVARIANT=Pattern.compile("\\b(?:(?:invariant|precise)\\s+)+\\w+(?:\\s*,\\s*\\w+)*\\s*;");
    private static final Pattern COORD_DECL=Pattern.compile("\\bin\\s+(?:(?:highp|mediump|lowp)\\s+)?vec4\\s+gl_FragCoord\\b");
    private static final Pattern MAIN=Pattern.compile("\\bvoid\\s+(main)\\s*\\(\\s*(?:void\\s*)?\\)\\s*(?=\\{)");
    private IrisFragmentDepth() {}

    public static IrisProgramLinker.Program adapt(IrisProgramLinker.Program linked) {
        var fragment=linked.stages().get(IrisStage.FRAGMENT);if(fragment==null)return linked;
        var stages=new EnumMap<IrisStage,IrisTranslator.Translation>(IrisStage.class);stages.putAll(linked.stages());
        stages.put(IrisStage.FRAGMENT,new IrisTranslator.Translation(adapt(fragment.text()),fragment.targets(),fragment.uniforms(),fragment.opaques(),fragment.notes()));
        return new IrisProgramLinker.Program(Collections.unmodifiableMap(stages),linked.uniforms(),linked.opaques(),linked.varyings(),linked.blocks());
    }

    public static String adapt(String source) {
        String code=maskComments(source);
        if(!TOKENS.matcher(code).find())return source;
        if(Pattern.compile("\\bgm_IrisDepth\\w*\\b").matcher(code).find())throw invalid("reserved gm_IrisDepth namespace");
        if(COORD_DECL.matcher(code).find())throw invalid("FRAGMENT_COORD_LAYOUT: explicit gl_FragCoord redeclarations require a coordinate-layout adapter");
        // Keep actual built-in declarations separate from references to the source-value proxy.
        var declarations=new ArrayList<String>();var bounds=new HashSet<String>();
        String body=replace(source,DEPTH_DECL,m->{
            String declaration=source.substring(m.start(),m.end());
            String converted=replace(declaration,Pattern.compile("\\bdepth_(greater|less|any|unchanged)\\b"),q->{
                bounds.add(q.group(1));return switch(q.group(1)){case "greater"->"depth_less";case "less"->"depth_greater";default->q.group();};
            });
            int index=declarations.size();declarations.add(converted);
            return "gm_IrisDepthDeclaration"+index+";";
        });
        String metadataSource=body;
        body=replace(body,INVARIANT,m->{
            if(!Pattern.compile("\\bgl_FragDepth\\b").matcher(m.group()).find())return metadataSource.substring(m.start(),m.end());
            int index=declarations.size();declarations.add(metadataSource.substring(m.start(),m.end()));
            return "gm_IrisDepthDeclaration"+index+";";
        });
        String active=maskComments(body);
        boolean coordinates=Pattern.compile("\\bgl_FragCoord\\b").matcher(active).find();
        boolean depth=Pattern.compile("\\bgl_FragDepth\\b").matcher(active).find();
        boolean writes=depth && writesDepth(source);
        body=replace(body,TOKENS,m->m.group().equals("gl_FragCoord")?"gm_IrisDepthCoord()":"gm_IrisDepthValue");
        if(writes) {
            var main=MAIN.matcher(maskComments(body));
            if(!main.find())throw invalid("missing fragment main");
            body=body.substring(0,main.start(1))+"gm_IrisDepthMain"+body.substring(main.end(1));
            String output="1.0 - gm_IrisDepthValue";
            // A round trip through forward float depth can move a reversed-depth value by an ULP.
            // Preserve conservative promises at equality, including exact depth_unchanged output.
            if(bounds.contains("unchanged"))output="gl_FragCoord.z";
            else if(bounds.contains("greater"))output="min("+output+", gl_FragCoord.z)";
            else if(bounds.contains("less"))output="max("+output+", gl_FragCoord.z)";
            body+="\nvoid main() { gm_IrisDepthMain(); gl_FragDepth = "+output+"; }\n";
        }
        body=replace(body,Pattern.compile("\\bgm_IrisDepthDeclaration([0-9]+);"),m->declarations.get(Integer.parseInt(m.group(1))));
        var header=new StringBuilder();
        if(coordinates)header.append("vec4 gm_IrisDepthCoord() { return vec4(gl_FragCoord.xy, 1.0 - gl_FragCoord.z, gl_FragCoord.w); }\n");
        // Reading a source output before writing it remains undefined, as in GLSL.
        if(depth)header.append("float gm_IrisDepthValue;\n");
        int at=body.indexOf('\n')+1;
        var extensions=Pattern.compile("(?m)^#extension[^\\n]*\\n").matcher(body);
        while(extensions.find())at=extensions.end();
        return body.substring(0,at)+header+body.substring(at);
    }

    private static boolean writesDepth(String source) {
        // The compiler handles stores through out/inout parameters, macros and compound writes.
        // A reference or redeclaration alone must not turn fixed-function depth into a shader write.
        var result=IrisCompiler.compileExplicit(source,IrisStage.FRAGMENT,"fragment-depth-analysis");
        if(!result.success())throw invalid(result.diagnostics());
        var words=ByteBuffer.wrap(result.spirv()).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        for(int at=5;at<words.limit();) {
            int instruction=words.get(at),count=instruction>>>16;
            if(count==0 || at+count>words.limit())throw invalid("malformed depth-analysis SPIR-V");
            // OpExecutionMode(entry point, DepthReplacing).
            if((instruction&0xffff)==16 && count>=3 && words.get(at+2)==12)return true;
            at+=count;
        }
        return false;
    }
    private static String maskComments(String source) {
        return COMMENTS.matcher(source).replaceAll(m->m.group().replaceAll("[^\\r\\n]"," "));
    }
    private static String replace(String source,Pattern pattern,Function<Matcher,String> replacement) {
        var matcher=pattern.matcher(maskComments(source));var result=new StringBuilder();int at=0;
        while(matcher.find()){result.append(source,at,matcher.start()).append(replacement.apply(matcher));at=matcher.end();}
        return result.append(source,at,source.length()).toString();
    }
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException("Iris fragment depth: "+message);}
}
