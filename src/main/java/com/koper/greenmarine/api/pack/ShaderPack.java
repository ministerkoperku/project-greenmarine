package com.koper.greenmarine.api.pack;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

public record ShaderPack(String id, String name, String script, List<PackParameter> parameters, List<PackPass> passes, List<PackProgram> programs, List<String> materialAddons) {
    public ShaderPack(String id,String name,String script,List<PackParameter> parameters,List<PackPass> passes,List<PackProgram> programs) {
        this(id,name,script,parameters,passes,programs,List.of());
    }
    public ShaderPack(String id,String name,String script,List<PackParameter> parameters,List<PackPass> passes) {
        this(id,name,script,parameters,passes,List.of());
    }
    public ShaderPack {
        requireId(id);
        if (name == null || name.isBlank() || name.length() > 80) throw new IllegalArgumentException("Invalid pack name");
        if (script != null) requireId(script);
        parameters = List.copyOf(parameters); passes = List.copyOf(passes);programs=List.copyOf(programs);materialAddons=List.copyOf(materialAddons);
        if(materialAddons.size()>16)throw new IllegalArgumentException("At most 16 material addons are supported per shaderpack");
        var addonIds=new HashSet<String>();
        for(String addon:materialAddons) {
            requireId(addon);
            if(!addon.endsWith(".json") || !addonIds.add(addon))throw new IllegalArgumentException("Invalid or duplicate material addon resource: "+addon);
        }
        if (parameters.size() > 32 || passes.size() > 8) throw new IllegalArgumentException("Pack exceeds parameter/pass limit");
        var names = new HashSet<String>();
        for (var parameter : parameters) if (!names.add(parameter.name())) throw new IllegalArgumentException("Duplicate parameter");
        names.clear();
        int lightmaps = 0;
        var earlierScenes=new HashSet<String>();
        for (var pass : passes) {
            if (!names.add(pass.name())) throw new IllegalArgumentException("Duplicate pass name");
            for(var producer:pass.inputs().values()) if(!earlierScenes.contains(producer))
                throw new IllegalArgumentException("Input for "+pass.name()+" requires an earlier scene pass: "+producer);
            if (pass.stage() == PackPass.Stage.LIGHTMAP) lightmaps++;
            else earlierScenes.add(pass.name());
        }
        if (lightmaps > 1) throw new IllegalArgumentException("At most one lightmap pass is supported");
        var targets=new HashSet<PackProgram.Target>();
        for(var program:programs) if(!targets.add(program.target())) throw new IllegalArgumentException("Duplicate world program target");
    }

    public static void requireId(String id) {
        if (id == null || id.length() > 192 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
            || id.contains("..") || id.contains(":/") || id.endsWith("/")) {
            throw new IllegalArgumentException("Expected a namespaced resource id: " + id);
        }
    }

    public static ShaderPack parse(JsonObject json) {
        try {
            var schema=json.getAsJsonPrimitive("schema");
            if (!schema.isNumber() || schema.getAsBigDecimal().compareTo(java.math.BigDecimal.ONE)!=0) {
                throw new IllegalArgumentException("Unsupported pack schema");
            }
            String id = json.get("id").getAsString();
            var parameters = new ArrayList<PackParameter>();
            if (json.has("parameters")) for (var entry : json.getAsJsonObject("parameters").entrySet()) {
                var parameter = entry.getValue().getAsJsonObject();
                parameters.add(new PackParameter(entry.getKey(), parameter.get("default").getAsFloat(),
                    parameter.get("min").getAsFloat(), parameter.get("max").getAsFloat()));
            }
            var passes = new ArrayList<PackPass>();
            for (var element : json.getAsJsonArray("passes")) {
                var pass = element.getAsJsonObject();
                passes.add(new PackPass(pass.get("name").getAsString(), value(pass, "stage", PackPass.Stage.SCENE),
                    pass.get("fragment").getAsString(), optional(pass, "fallback"),
                    value(pass, "requires", PackPass.Requirement.NONE), value(pass, "minQuality", Quality.LOW),
                    pass.has("scale") ? pass.get("scale").getAsFloat() : 1, optional(pass, "spirv"),value(pass,"format",PackPass.Format.SCENE),pass.has("history") && pass.get("history").getAsBoolean(),inputs(pass),pass.has("lightingHistory") && pass.get("lightingHistory").getAsBoolean()));
            }
            var programs=new ArrayList<PackProgram>();
            if(json.has("programs")) for(var entry:json.getAsJsonArray("programs")) {
                var program=entry.getAsJsonObject();
                programs.add(new PackProgram(value(program,"target",PackProgram.Target.TERRAIN),optional(program,"vertex"),
                    program.get("fragment").getAsString(),program.has("worldRays") && program.get("worldRays").getAsBoolean(),optional(program,"surface"),program.has("surfaceOnly") && program.get("surfaceOnly").getAsBoolean(),program.has("voxelShadows") && program.get("voxelShadows").getAsBoolean(),
                    program.has("surfaceBeforeTerrain")?program.get("surfaceBeforeTerrain").getAsBoolean():PackProgram.defaultSurfaceBeforeTerrain(program.get("fragment").getAsString(),optional(program,"surface"))));
            }
            return new ShaderPack(id, json.has("name") ? json.get("name").getAsString() : id,
                optional(json, "script"), parameters, passes,programs,materialAddons(json));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid GreenMarine shaderpack: " + error.getMessage(), error);
        }
    }

    private static List<String> materialAddons(JsonObject json) {
        if(!json.has("materialAddons"))return List.of();
        var result=new ArrayList<String>();
        for(var element:json.getAsJsonArray("materialAddons")) {
            if(!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Material addons must name JSON resources as strings");
            result.add(element.getAsString());
        }
        return result;
    }

    private static Map<String,String> inputs(JsonObject pass) {
        if(!pass.has("inputs")) return Map.of();
        var inputs=new LinkedHashMap<String,String>();
        for(var entry:pass.getAsJsonObject("inputs").entrySet()) {
            var value=entry.getValue();
            if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Pass inputs must name producer passes as strings");
            inputs.put(entry.getKey(),value.getAsString());
        }
        return inputs;
    }
    private static String optional(JsonObject json, String key) { return json.has(key) ? json.get(key).getAsString() : null; }
    private static <E extends Enum<E>> E value(JsonObject json, String key, E fallback) {
        return json.has(key) ? Enum.valueOf(fallback.getDeclaringClass(), json.get(key).getAsString().toUpperCase(Locale.ROOT)) : fallback;
    }
}
