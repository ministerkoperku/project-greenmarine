package com.koper.greenmarine.api.pack;

import java.util.Objects;
import java.util.Map;
import java.util.Set;

/**
 * One manifest pass. For {@code COMPUTE} passes {@code fragment} names the compute program
 * ({@code assets/<ns>/shaders/<path>.csh}) and {@code groups} sizes the dispatch:
 * {@code "screen"}, {@code "image:<Name>"} or {@code "x,y,z"}.
 */
public record PackPass(String name, Stage stage, String fragment, String fallback,
                       Requirement requires, Quality minQuality, float scale, String spirv, Format format, boolean history, Map<String,String> inputs, boolean lightingHistory, String groups) {
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
        "MarineOpaqueColor","MarineOpaqueDepth","RaySurfaceFrame","RaySurfaceLighting","RayReflection","RaySurfaceExtra",
        "MarineHistoryFrame","HistorySampler","HistoryNormalDepth","HistoryAlbedoMaterial","HistorySceneDepth");
    public PackPass(String name,Stage stage,String fragment,String fallback,Requirement requires,Quality minQuality,float scale,String spirv,Format format,boolean history) {
        this(name,stage,fragment,fallback,requires,minQuality,scale,spirv,format,history,Map.of());
    }
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
        Objects.requireNonNull(format);
        ShaderPack.requireId(fragment);
        if (fallback != null) ShaderPack.requireId(fallback);
        if (spirv != null) ShaderPack.requireId(spirv);
        if (scale != 1 && scale != 0.75f && scale != 0.5f && scale != 0.25f) throw new IllegalArgumentException("Unsupported render scale");
        if (stage == Stage.LIGHTMAP && scale != 1) throw new IllegalArgumentException("Lightmaps require scale 1");
        if (stage == Stage.LIGHTMAP && format != Format.SCENE) throw new IllegalArgumentException("Lightmaps use the scene format");
        inputs=Map.copyOf(inputs);
        if(inputs.size()>8) throw new IllegalArgumentException("At most eight named pass inputs are supported");
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
        if(lightingHistory && (stage!=Stage.SCENE || !history)) throw new IllegalArgumentException("Lighting history requires scene history");
        if (stage == Stage.LIGHTMAP && history) throw new IllegalArgumentException("Lightmaps cannot use scene history");
    }
}
