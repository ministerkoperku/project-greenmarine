package com.koper.greenmarine.api.pack;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

public record ShaderPack(String id, String name, String script, List<PackParameter> parameters, List<PackPass> passes, List<PackProgram> programs, List<String> materialAddons, PackResources resources) {
    public ShaderPack(String id,String name,String script,List<PackParameter> parameters,List<PackPass> passes,List<PackProgram> programs,List<String> materialAddons) {
        this(id,name,script,parameters,passes,programs,materialAddons,PackResources.NONE);
    }
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
        parameters = List.copyOf(parameters); passes = List.copyOf(passes); java.util.Objects.requireNonNull(resources);programs=List.copyOf(programs);materialAddons=List.copyOf(materialAddons);
        if(materialAddons.size()>16)throw new IllegalArgumentException("At most 16 material addons are supported per shaderpack");
        var addonIds=new HashSet<String>();
        for(String addon:materialAddons) {
            requireId(addon);
            if(!addon.endsWith(".json") || !addonIds.add(addon))throw new IllegalArgumentException("Invalid or duplicate material addon resource: "+addon);
        }
        long computes=passes.stream().filter(p->p.stage()==PackPass.Stage.COMPUTE).count();
        if (parameters.size() > 32 || passes.size()-computes > 8 || computes > 16) throw new IllegalArgumentException("Pack exceeds parameter/pass limit");
        var names = new HashSet<String>();
        for (var parameter : parameters) if (!names.add(parameter.name())) throw new IllegalArgumentException("Duplicate parameter");
        names.clear();
        int lightmaps = 0;
        var earlierScenes=new HashSet<String>();
        for (var pass : passes) {
            if (!names.add(pass.name())) throw new IllegalArgumentException("Duplicate pass name");
            if(resources.images().containsKey(pass.name()) || resources.buffers().containsKey(pass.name()))
                throw new IllegalArgumentException("Pass and resource share a name: "+pass.name());
            for(var sampler:pass.inputs().keySet()) if(resources.images().containsKey(sampler) || resources.buffers().containsKey(sampler))
                throw new IllegalArgumentException("Input sampler "+sampler+" of "+pass.name()+" shadows a pack resource; pick another sampler name");
            for(var producer:pass.inputs().values()) if(!earlierScenes.contains(producer) && !resources.images().containsKey(producer))
                throw new IllegalArgumentException("Input for "+pass.name()+" requires an earlier scene pass or pack image: "+producer);
            if(pass.groups()!=null && pass.groups().startsWith("image:") && !resources.images().containsKey(pass.groups().substring(6)))
                throw new IllegalArgumentException("Compute pass "+pass.name()+" sizes by an undeclared image: "+pass.groups());
            if (pass.stage() == PackPass.Stage.LIGHTMAP) lightmaps++;
            else if (pass.stage() == PackPass.Stage.SCENE) earlierScenes.add(pass.name());
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
                var stage = value(pass, "stage", PackPass.Stage.SCENE);
                if (stage == PackPass.Stage.COMPUTE ? pass.has("fragment") : pass.has("compute"))
                    throw new IllegalArgumentException("Compute passes use \"compute\", other passes \"fragment\"");
                passes.add(new PackPass(pass.get("name").getAsString(), stage,
                    pass.get(stage == PackPass.Stage.COMPUTE ? "compute" : "fragment").getAsString(), optional(pass, "fallback"),
                    value(pass, "requires", PackPass.Requirement.NONE), value(pass, "minQuality", Quality.LOW),
                    pass.has("scale") ? pass.get("scale").getAsFloat() : 1, optional(pass, "spirv"),value(pass,"format",PackPass.Format.SCENE),pass.has("history") && pass.get("history").getAsBoolean(),inputs(pass),pass.has("lightingHistory") && pass.get("lightingHistory").getAsBoolean(),groups(pass)));
            }
            var programs=new ArrayList<PackProgram>();
            if(json.has("programs")) for(var entry:json.getAsJsonArray("programs")) {
                var program=entry.getAsJsonObject();
                programs.add(new PackProgram(value(program,"target",PackProgram.Target.TERRAIN),optional(program,"vertex"),
                    program.get("fragment").getAsString(),program.has("worldRays") && program.get("worldRays").getAsBoolean(),optional(program,"surface"),program.has("surfaceOnly") && program.get("surfaceOnly").getAsBoolean(),program.has("voxelShadows") && program.get("voxelShadows").getAsBoolean(),
                    program.has("surfaceBeforeTerrain")?program.get("surfaceBeforeTerrain").getAsBoolean():PackProgram.defaultSurfaceBeforeTerrain(program.get("fragment").getAsString(),optional(program,"surface"))));
            }
            return new ShaderPack(id, json.has("name") ? json.get("name").getAsString() : id,
                optional(json, "script"), parameters, passes,programs,materialAddons(json),resources(json));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid GreenMarine shaderpack: " + error.getMessage(), error);
        }
    }

    private static String groups(JsonObject pass) {
        if (!pass.has("groups")) return null;
        var value = pass.get("groups");
        if (value.isJsonArray()) {
            var array = value.getAsJsonArray();
            if (array.size() != 3) throw new IllegalArgumentException("groups array needs [x,y,z]");
            return array.get(0).getAsInt() + "," + array.get(1).getAsInt() + "," + array.get(2).getAsInt();
        }
        return value.getAsString();
    }
    private static PackResources resources(JsonObject json) {
        if (!json.has("resources")) return PackResources.NONE;
        var root = json.getAsJsonObject("resources");
        for (var key : root.keySet()) if (!key.equals("images") && !key.equals("buffers")) throw new IllegalArgumentException("Unknown resource kind: " + key);
        var images = new LinkedHashMap<String, PackResources.Image>(); var buffers = new LinkedHashMap<String, PackResources.Buffer>();
        if (root.has("images")) for (var entry : root.getAsJsonObject("images").entrySet()) {
            var image = entry.getValue().getAsJsonObject();
            var format = value(image, "format", PackResources.Format.RGBA16F);
            if (image.has("size")) {
                var size = image.getAsJsonArray("size");
                if (size.size() != 2 || image.has("scale")) throw new IllegalArgumentException("Image " + entry.getKey() + " takes size [w,h] or scale");
                images.put(entry.getKey(), new PackResources.Image(format, size.get(0).getAsInt(), size.get(1).getAsInt(), 0));
            } else images.put(entry.getKey(), new PackResources.Image(format, 0, 0, image.has("scale") ? image.get("scale").getAsFloat() : 1));
        }
        if (root.has("buffers")) for (var entry : root.getAsJsonObject("buffers").entrySet())
            buffers.put(entry.getKey(), new PackResources.Buffer(entry.getValue().getAsJsonObject().get("size").getAsInt()));
        return new PackResources(images, buffers);
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
