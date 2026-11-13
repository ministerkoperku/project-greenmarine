package com.koper.greenmarine.api.pack;

import java.util.Objects;
import java.util.Map;
import java.util.Set;

/**
 * One manifest pass. For {@code COMPUTE} passes {@code fragment} names the compute program
 * ({@code assets/<ns>/shaders/<path>.csh}) and {@code groups} sizes the dispatch:
 * {@code "screen"}, {@code "image:<Name>"} or {@code "x,y,z"}.
 * Uniform bindings map GLSL block names to pack uniform resources independently per pass.
 */
public record PackPass(String name, Stage stage, String fragment, String fallback,
                       Requirement requires, Quality minQuality, float scale, String spirv, Format format, boolean history, Map<String,String> inputs, boolean lightingHistory, String groups, Map<Integer,String> outputs, Map<String,String> uniforms, Phase phase, String vertex, int vertices) {
    public PackPass(String name,Stage stage,String fragment,String fallback,Requirement requires,Quality minQuality,float scale,String spirv,Format format,boolean history,Map<String,String> inputs,boolean lightingHistory,String groups,Map<Integer,String> outputs,Map<String,String> uniforms,Phase phase) {
        this(name,stage,fragment,fallback,requires,minQuality,scale,spirv,format,history,inputs,lightingHistory,groups,outputs,uniforms,phase,null,3);
    }
    /** Source-compatible constructor: legacy scene and compute passes run after the world. */
    public PackPass(String name,Stage stage,String fragment,String fallback,Requirement requires,Quality minQuality,float scale,String spirv,Format format,boolean history,Map<String,String> inputs,boolean lightingHistory,String groups,Map<Integer,String> outputs,Map<String,String> uniforms) {
        this(name,stage,fragment,fallback,requires,minQuality,scale,spirv,format,history,inputs,lightingHistory,groups,outputs,uniforms,Phase.COMPOSITE);
    }
    public PackPass(String name,Stage stage,String fragment,String fallback,Requirement requires,Quality minQuality,float scale,String spirv,Format format,boolean history,Map<String,String> inputs,boolean lightingHistory,String groups,Map<Integer,String> outputs) {
        this(name,stage,fragment,fallback,requires,minQuality,scale,spirv,format,history,inputs,lightingHistory,groups,outputs,Map.of());
    }
    public PackPass(String name,Stage stage,String fragment,String fallback,Requirement requires,Quality minQuality,float scale,String spirv,Format format,boolean history,Map<String,String> inputs,boolean lightingHistory,String groups) {
        this(name,stage,fragment,fallback,requires,minQuality,scale,spirv,format,history,inputs,lightingHistory,groups,Map.of());
    }
    public PackPass(String name,Stage stage,String fragment,String fallback,Requirement requires,Quality minQuality,float scale,String spirv,Format format,boolean history,Map<String,String> inputs,boolean lightingHistory) {
        this(name,stage,fragment,fallback,requires,minQuality,scale,spirv,format,history,inputs,lightingHistory,null);
    }
    public PackPass(String name,Stage stage,String fragment,String fallback,Requirement requires,Quality minQuality,float scale,String spirv,Format format,boolean history,Map<String,String> inputs) {
        this(name,stage,fragment,fallback,requires,minQuality,scale,spirv,format,history,inputs,false);
    }
    private static final Set<String> RESERVED_BINDINGS=Set.of(
        "MarineFrame","ColorSampler","DepthSampler","LightmapSampler","SceneSampler",
        "RayFrame","RayLighting","RayNormalDepth","RayAlbedo","VoxelFrame","VoxelMaterials",
        "MarineEnvironment","MarineSurfaceFrame","MarineSurfaceNormalDepth","MarineSurfaceAlbedo",
        "MarineOpaqueColor","MarineOpaqueDepth","RaySurfaceFrame","RaySurfaceLighting","RayReflection","RaySurfaceExtra","MarineHeightField",
        "MarineHistoryFrame","HistorySampler","HistoryNormalDepth","HistoryAlbedoMaterial","HistorySceneDepth");
    public PackPass(String name,Stage stage,String fragment,String fallback,Requirement requires,Quality minQuality,float scale,String spirv,Format format,boolean history) {
        this(name,stage,fragment,fallback,requires,minQuality,scale,spirv,format,history,Map.of());
    }
    /** Host execution order. Names match Iris program families; SHADOWCOMP does not imply shadow rendering. */
    public enum Phase { SETUP, BEGIN, SHADOWCOMP, PREPARE, DEFERRED, COMPOSITE, FINAL }
    public enum Stage { LIGHTMAP, SCENE, COMPUTE }
    public enum Requirement { NONE, VULKAN, HARDWARE_RT, VOXEL_RT, COMPUTE }
    public static void requireBindingName(String name) {
        if(name==null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || name.startsWith("gl_") || name.contains("__") || RESERVED_BINDINGS.contains(name))
            throw new IllegalArgumentException("Invalid or reserved binding name: "+name);
    }
    private static final java.util.regex.Pattern GROUPS=java.util.regex.Pattern.compile("screen|image:[A-Za-z_][A-Za-z0-9_]{0,63}|[1-9][0-9]{0,4},[1-9][0-9]{0,4},[1-9][0-9]{0,4}");
    public enum Format { SCENE, RGBA16F }
    public PackPass(String name,Stage stage,String fragment,String fallback,Requirement requires,Quality minQuality,float scale,String spirv,Format format) {
        this(name,stage,fragment,fallback,requires,minQuality,scale,spirv,format,false);
    }
    public PackPass(String name,Stage stage,String fragment,String fallback,Requirement requires,Quality minQuality,float scale,String spirv) {
        this(name,stage,fragment,fallback,requires,minQuality,scale,spirv,Format.SCENE);
    }

    public PackPass {
        if (name == null || !name.matches("[a-z0-9_/-]{1,48}")) throw new IllegalArgumentException("Invalid pass name");
        Objects.requireNonNull(stage); Objects.requireNonNull(requires); Objects.requireNonNull(minQuality);
        Objects.requireNonNull(format); Objects.requireNonNull(phase);
        if(vertex!=null) {
            ShaderPack.requireId(vertex);
            if(stage!=Stage.SCENE)throw new IllegalArgumentException("Custom procedural vertex shaders require a scene pass");
        }
        if(vertices<3 || vertices>65536 || vertices%3!=0 || vertex==null && vertices!=3)
            throw new IllegalArgumentException("Scene vertex count requires complete triangles and an explicit custom vertex shader");
        if(stage==Stage.LIGHTMAP && phase!=Phase.COMPOSITE)throw new IllegalArgumentException("Lightmaps cannot select a world phase");
        ShaderPack.requireId(fragment);
        if (fallback != null) ShaderPack.requireId(fallback);
        if (spirv != null) ShaderPack.requireId(spirv);
        if (scale != 1 && scale != 0.75f && scale != 0.5f && scale != 0.25f) throw new IllegalArgumentException("Unsupported render scale");
        if (stage == Stage.LIGHTMAP && scale != 1) throw new IllegalArgumentException("Lightmaps require scale 1");
        if (stage == Stage.LIGHTMAP && format != Format.SCENE) throw new IllegalArgumentException("Lightmaps use the scene format");
        inputs=Map.copyOf(inputs);
        uniforms=java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(uniforms));
        if(uniforms.size()>64)throw new IllegalArgumentException("At most 64 named uniform blocks per pass");
        for(var uniform:uniforms.entrySet()) {
            requireBindingName(uniform.getKey());requireBindingName(uniform.getValue());
            if(inputs.containsKey(uniform.getKey()))throw new IllegalArgumentException("Uniform block shadows an input sampler: "+uniform.getKey());
        }
        if(inputs.size()>32) throw new IllegalArgumentException("At most 32 named pass inputs are supported");
        for(var input:inputs.entrySet()) {
            requireBindingName(input.getKey());
            // Producers are earlier scene pass names or pack image resources (checked by ShaderPack).
            if(!input.getValue().matches("[a-z0-9_/-]{1,48}") && !input.getValue().matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
                throw new IllegalArgumentException("Invalid input producer: "+input.getValue());
        }
        if(stage==Stage.COMPUTE) {
            if(groups==null || !GROUPS.matcher(groups).matches()) throw new IllegalArgumentException("Compute pass "+name+" needs groups: screen, image:<Name> or x,y,z");
            if(fallback!=null || spirv!=null || scale!=1 || format!=Format.SCENE || history) throw new IllegalArgumentException("Compute pass "+name+" only takes compute, groups, inputs, requires and minQuality");
        } else if(groups!=null) throw new IllegalArgumentException("groups is only valid on compute passes");
        if(stage==Stage.LIGHTMAP && !inputs.isEmpty()) throw new IllegalArgumentException("Lightmaps cannot use scene pass inputs");
        if(stage==Stage.LIGHTMAP && !uniforms.isEmpty()) throw new IllegalArgumentException("Lightmaps cannot use custom world uniform blocks");
        outputs=java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(outputs));
        if(!outputs.isEmpty()) {
            if(stage!=Stage.SCENE || history || scale!=1 || format!=Format.SCENE)
                throw new IllegalArgumentException("Named outputs require a scene pass without legacy history, format or scale; declare those on targets");
            if(outputs.size()>32)throw new IllegalArgumentException("At most 32 colour attachment locations may be declared");
            var targets=new java.util.HashSet<String>();
            for(int location=0;location<outputs.size();location++) {
                var target=outputs.get(location);
                if(target==null || !targets.add(target))throw new IllegalArgumentException("Outputs require unique targets at consecutive locations starting at zero");
                requireBindingName(target);
            }
        }
        if(lightingHistory && (stage!=Stage.SCENE || !history)) throw new IllegalArgumentException("Lighting history requires scene history");
        if (stage == Stage.LIGHTMAP && history) throw new IllegalArgumentException("Lightmaps cannot use scene history");
    }
}
