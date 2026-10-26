package com.koper.greenmarine.development;

import java.util.*;
import java.util.regex.Pattern;

/** Lexical assistance; the compiler remains the authority for types and bindings. */
final class ShaderCompletions {
    record Choice(String label,String insert,String help) {}
    record Edit(String text,int cursor) {}
    private static final List<Choice> BUILTINS=List.of(
        function("normalize","Return a vector with length 1. normalize(vector)"),
        function("texture","Sample a texture. texture(sampler, uv)"),
        function("textureLod","Sample an explicit mip level. textureLod(sampler, uv, lod)"),
        function("texelFetch","Read an exact texel. texelFetch(sampler, ivec2, mip)"),
        function("mix","Blend x and y by a. mix(x, y, a)"),
        function("clamp","Limit a value. clamp(value, minimum, maximum)"),
        function("smoothstep","Smooth transition between two edges. smoothstep(edge0, edge1, x)"),
        function("dot","Scalar product. dot(vectorA, vectorB)"),
        function("cross","Vector perpendicular to two vec3 inputs. cross(a, b)"),
        function("length","Length of a vector. length(vector)"),
        function("reflect","Reflection direction. reflect(incident, normal)"),
        function("refract","Refraction direction. refract(incident, normal, eta)"),
        function("pow","Raise x to a power. pow(x, exponent)"),
        function("min","Smaller value. min(x, y)"),function("max","Larger value. max(x, y)"),
        function("abs","Absolute value. abs(x)"),function("sin","Sine in radians. sin(x)"),
        function("cos","Cosine in radians. cos(x)"),function("fract","Fractional part. fract(x)"),
        function("floor","Round down. floor(x)"),function("ceil","Round up. ceil(x)"),
        function("sqrt","Square root. sqrt(x)"),function("exp","Exponential. exp(x)"),
        function("dFdx","Screen-space derivative along x (fragment stage). dFdx(x)"),
        function("dFdy","Screen-space derivative along y (fragment stage). dFdy(x)"),
        function("imageLoad","Read a storage image. imageLoad(image, coordinate)"),
        function("imageStore","Write a storage image. imageStore(image, coordinate, value)"),
        new Choice("main","main()","Shader entry-point name. Declare it with void main()."),
        new Choice("entry","void main() {\n    \n}","Insert a complete shader entry-point template."),
        new Choice("if","if () {\n    \n}","Conditional block."),
        new Choice("for","for (int i = 0; i < 4; i++) {\n    \n}","Counted loop template."),
        new Choice("gl_Position","gl_Position","Vertex output: clip-space position (vec4)."),
        new Choice("gl_FragCoord","gl_FragCoord","Fragment pixel coordinates (vec4)."),
        new Choice("gl_GlobalInvocationID","gl_GlobalInvocationID","Compute invocation coordinates (uvec3).")
    );
    private static final Pattern WORD=Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");
    private static Choice function(String name,String help){return new Choice(name,name+"()",help);}
    static int start(String source,int cursor){
        int row=source.lastIndexOf('\n',Math.max(0,cursor-1))+1;String line=source.substring(row,cursor);
        int quotes=0;for(int i=0;i<line.length();i++)if(line.charAt(i)=='"'&&(i==0||line.charAt(i-1)!='\\'))quotes++;
        boolean resource=line.contains("#include")||line.contains("#moj_import")||quotes%2!=0;
        int start=cursor;while(start>0&&(Character.isJavaIdentifierPart(source.charAt(start-1))||source.charAt(start-1)=='.'||resource&&":/-".indexOf(source.charAt(start-1))>=0))start--;return start;
    }
    static List<Choice> suggest(String source,int cursor) {
        cursor=Math.clamp(cursor,0,source.length());int start=start(source,cursor);
        if(start==cursor)return List.of();
        int[] colors=GlslHighlighter.colors(source);
        if(cursor>0&&colors[cursor-1]==GlslHighlighter.COMMENT)return List.of();
        String prefix=source.substring(start,cursor);var options=new LinkedHashMap<String,Choice>();
        for(var choice:BUILTINS)options.put(choice.label(),choice);
        for(String type:List.of("float","int","uint","bool","vec2","vec3","vec4","ivec2","ivec3","ivec4","uvec2","uvec3","mat3","mat4","sampler2D","uniform","layout","const","in","out","return"))
            options.putIfAbsent(type,new Choice(type,type,"GLSL type or keyword: "+type));
        for(String name:List.of("radians","degrees","tan","asin","acos","atan","sinh","cosh","tanh","asinh","acosh","atanh","exp2","log","log2","inversesqrt","sign","trunc","round","roundEven","mod","modf","isnan","isinf","fma","frexp","ldexp","distance","faceforward","step","matrixCompMult","outerProduct","transpose","determinant","inverse","lessThan","lessThanEqual","greaterThan","greaterThanEqual","equal","notEqual","any","all","not","floatBitsToInt","floatBitsToUint","intBitsToFloat","uintBitsToFloat","packUnorm2x16","packSnorm2x16","packUnorm4x8","packSnorm4x8","unpackUnorm2x16","unpackSnorm2x16","unpackUnorm4x8","unpackSnorm4x8","packHalf2x16","unpackHalf2x16","uaddCarry","usubBorrow","umulExtended","imulExtended","bitfieldExtract","bitfieldInsert","bitfieldReverse","bitCount","findLSB","findMSB","textureSize","textureQueryLod","textureQueryLevels","textureSamples","textureProj","textureOffset","textureProjOffset","textureLodOffset","textureProjLod","textureProjLodOffset","textureGrad","textureGradOffset","textureProjGrad","textureProjGradOffset","texelFetchOffset","textureGather","textureGatherOffset","textureGatherOffsets","fwidth","dFdxFine","dFdyFine","fwidthFine","dFdxCoarse","dFdyCoarse","fwidthCoarse","interpolateAtCentroid","interpolateAtSample","interpolateAtOffset","atomicCounterIncrement","atomicCounterDecrement","atomicCounter","atomicAdd","atomicMin","atomicMax","atomicAnd","atomicOr","atomicXor","atomicExchange","atomicCompSwap","imageSize","imageSamples","imageAtomicAdd","imageAtomicMin","imageAtomicMax","imageAtomicAnd","imageAtomicOr","imageAtomicXor","imageAtomicExchange","imageAtomicCompSwap","barrier","memoryBarrier","memoryBarrierAtomicCounter","memoryBarrierBuffer","memoryBarrierShared","memoryBarrierImage","groupMemoryBarrier"))
            options.putIfAbsent(name,function(name,"GLSL "+name+"(...). The compiler checks argument types and stage/capability availability."));
        for(String word:List.of("double","bvec2","bvec3","bvec4","dvec2","dvec3","dvec4","mat2","mat2x3","mat2x4","mat3x2","mat3x4","mat4x2","mat4x3","dmat2","dmat3","dmat4","isampler2D","usampler2D","image2D","iimage2D","uimage2D","buffer","shared","coherent","readonly","writeonly","restrict","volatile","discard","flat","smooth","noperspective","centroid","sample","invariant","precise","std140","std430","local_size_x","local_size_y","local_size_z","binding","location"))
            options.putIfAbsent(word,new Choice(word,word,"GLSL type, storage qualifier or layout field."));
        var words=WORD.matcher(source);
        while(words.find())if(words.start()!=start)options.putIfAbsent(words.group(),new Choice(words.group(),words.group(),"Identifier from this source."));
        return options.values().stream().filter(c->c.label().startsWith(prefix)&&!c.label().equals(prefix)).limit(6).toList();
    }
    static List<Choice> suggest(String source,int cursor,String path,List<Choice> extra){
        cursor=Math.clamp(cursor,0,source.length());int start=start(source,cursor);if(start==cursor)return List.of();
        String prefix=source.substring(start,cursor);var choices=new LinkedHashMap<String,Choice>();
        if(path.endsWith(".json")){
            for(String key:List.of("schema","id","name","script","parameters","default","min","max","passes","programs","materialAddons","resources","images","buffers","size","format","scale","stage","fragment","vertex","compute","fallback","requires","minQuality","spirv","history","inputs","lightingHistory","groups","target","worldRays","surface","surfaceOnly","voxelShadows","surfaceBeforeTerrain","textures","mask","palette","roughness","reflection","emission","shaderpacks"))
                choices.put(key,new Choice(key,key,"GreenMarine schema field. See SDK / Authoring reference for types and limits."));
            for(String value:List.of("scene","lightmap","compute","none","vulkan","hardware_rt","voxel_rt","low","medium","high","ultra","terrain","block","entity","sky","clouds","weather","particles","background","rgba8","rgba16f","rgba32f","r32f","rg32f","r16f","rg16f","r32ui","rg32ui","rgba32ui","r11g11b10f","screen","true","false"))
                choices.put(value,new Choice(value,value,"GreenMarine schema value."));
        }else if(path.endsWith(".lua")){
            if(source.substring(Math.max(0,source.lastIndexOf('\n',cursor-1)+1),cursor).stripLeading().startsWith("--"))return List.of();
            for(var choice:List.of(function("marine.uniform","marine.uniform(name, value): update a declared pack parameter."),function("marine.light","marine.light(slot, x,y,z, r,g,b, intensity,radius): own a dynamic light."),function("marine.remove_light","marine.remove_light(slot): release a script-owned light."),function("marine.clamp","marine.clamp(value, minimum, maximum)"),function("marine.mix","marine.mix(a, b, weight)")))choices.put(choice.label(),choice);
            for(String value:List.of("frame.time","frame.day","frame.camera.x","frame.camera.y","frame.camera.z","frame.quality","frame.rays","frame.capabilities.compute","frame.capabilities.hardware_rt","function","local","end","return","if","then","else"))choices.put(value,new Choice(value,value,"GreenMarine Lua update(frame) API or Lua keyword."));
        }else{
            for(var choice:suggest(source,cursor))choices.put(choice.label(),choice);
            int[] colors=GlslHighlighter.colors(source);if(cursor>0&&colors[cursor-1]==GlslHighlighter.COMMENT)return List.of();
        }
        for(var choice:extra)choices.putIfAbsent(choice.label(),choice);
        return choices.values().stream().filter(c->c.label().startsWith(prefix)&&!c.label().equals(prefix)).limit(12).toList();
    }
    static List<Choice> projectChoices(ShaderProject project){
        var choices=new LinkedHashMap<String,Choice>();
        for(String name:List.of("MarineFrame","Frame","Context","InverseProjection","InverseViewRotation","Parameters","ColorSampler","SceneSampler","DepthSampler","LightmapSampler","RayFrame","RayLighting","RayNormalDepth","RayAlbedo","VoxelFrame","VoxelMaterials","MarineEnvironment","MarineSurfaceFrame","MarineSurfaceNormalDepth","MarineSurfaceAlbedo","MarineOpaqueColor","MarineOpaqueDepth","RaySurfaceFrame","RaySurfaceLighting","RayReflection","RaySurfaceExtra","MarineHistoryFrame","HistorySampler","HistoryNormalDepth","HistoryAlbedoMaterial","HistorySceneDepth"))
            choices.put(name,new Choice(name,name,"GreenMarine binding or frame field; see SDK for its GLSL layout and stage availability."));
        Pattern words=Pattern.compile("[A-Za-z_][A-Za-z_0-9]{2,}");int total=0;
        try{for(String path:project.files()){
            int assets=path.indexOf("/assets/");
            if(assets>=0){String relative=path.substring(assets+8);int slash=relative.indexOf('/');
                if(slash>0){String ns=relative.substring(0,slash),resource=relative.substring(slash+1);String id=ns+":"+resource;
                    choices.put(id,new Choice(id,id,"Project resource: "+path));
                    if(resource.startsWith("shaders/include/")){id=ns+":"+resource.substring(16);choices.put(id,new Choice(id,id,"Project shader include: "+path));}
                }
            }
            if(total>2*1024*1024)break;
            try{String text=project.text(path);total+=text.length();var matcher=words.matcher(text);int tokens=0;
                while(matcher.find()&&tokens++<2048){String word=matcher.group();choices.putIfAbsent(word,new Choice(word,word,"Identifier from "+path));}
            }catch(Exception ignored){}
        }}catch(Exception ignored){}
        for(var entry:ShaderSdk.entries())if(entry.category().toLowerCase(java.util.Locale.ROOT).contains("include")){
            String path=entry.id();int include=path.indexOf("shaders/include/");
            if(include>=0){String id="greenmarine:"+path.substring(include+16);choices.putIfAbsent(id,new Choice(id,id,"SDK include / "+entry.title()));}
            var matcher=Pattern.compile("\\b(marine[A-Za-z0-9_]+)\\s*\\(").matcher(entry.text());
            while(matcher.find()){String name=matcher.group(1);choices.putIfAbsent(name,function(name,"GreenMarine helper from "+entry.title()));}
        }
        return List.copyOf(choices.values());
    }
    static Edit accept(String source,int cursor,Choice choice){
        int start=start(source,cursor);String insertion=choice.insert();
        if(cursor<source.length()&&source.charAt(cursor)=='('&&insertion.endsWith("()"))insertion=choice.label();
        return new Edit(source.substring(0,start)+insertion+source.substring(cursor),start+insertion.length()-(insertion.endsWith("()")?1:0));
    }
}
