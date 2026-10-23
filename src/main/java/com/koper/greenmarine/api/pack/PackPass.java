package com.koper.greenmarine.api.pack;

import java.util.Objects;
import java.util.Map;
import java.util.Set;

public record PackPass(String name, Stage stage, String fragment, String fallback,
                       Requirement requires, Quality minQuality, float scale, String spirv, Format format, boolean history, Map<String,String> inputs, boolean lightingHistory) {
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
    public enum Stage { LIGHTMAP, SCENE }
    public enum Requirement { NONE, VULKAN, HARDWARE_RT, VOXEL_RT }
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
            String sampler=input.getKey();
            if(!sampler.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || sampler.startsWith("gl_") || sampler.contains("__") || RESERVED_BINDINGS.contains(sampler))
                throw new IllegalArgumentException("Invalid or reserved input sampler: "+sampler);
            if(!input.getValue().matches("[a-z0-9_/-]{1,48}")) throw new IllegalArgumentException("Invalid input pass name: "+input.getValue());
        }
        if(stage==Stage.LIGHTMAP && !inputs.isEmpty()) throw new IllegalArgumentException("Lightmaps cannot use scene pass inputs");
        if(lightingHistory && (stage!=Stage.SCENE || !history)) throw new IllegalArgumentException("Lighting history requires scene history");
        if (stage == Stage.LIGHTMAP && history) throw new IllegalArgumentException("Lightmaps cannot use scene history");
    }
}
