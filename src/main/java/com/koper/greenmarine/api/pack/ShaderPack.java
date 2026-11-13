package com.koper.greenmarine.api.pack;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

public record ShaderPack(String id, String name, String script, List<PackParameter> parameters, List<PackPass> passes, List<PackProgram> programs, List<String> materialAddons, PackResources resources, Map<String,PackTarget> targets,PackInputs uniformInputs,String worldColor,PackTerrain terrain) {
    public ShaderPack(String id,String name,String script,List<PackParameter> parameters,List<PackPass> passes,List<PackProgram> programs,List<String> materialAddons,PackResources resources,Map<String,PackTarget> targets,PackInputs uniformInputs,String worldColor) {
        this(id,name,script,parameters,passes,programs,materialAddons,resources,targets,uniformInputs,worldColor,PackTerrain.NONE);
    }
    public ShaderPack(String id,String name,String script,List<PackParameter> parameters,List<PackPass> passes,List<PackProgram> programs,List<String> materialAddons,PackResources resources,Map<String,PackTarget> targets,PackInputs uniformInputs) {
        this(id,name,script,parameters,passes,programs,materialAddons,resources,targets,uniformInputs,null);
    }
    public ShaderPack(String id,String name,String script,List<PackParameter> parameters,List<PackPass> passes,List<PackProgram> programs,List<String> materialAddons,PackResources resources,Map<String,PackTarget> targets) {
        this(id,name,script,parameters,passes,programs,materialAddons,resources,targets,PackInputs.NONE);
    }
    public ShaderPack(String id,String name,String script,List<PackParameter> parameters,List<PackPass> passes,List<PackProgram> programs,List<String> materialAddons,PackResources resources) {
        this(id,name,script,parameters,passes,programs,materialAddons,resources,Map.of());
    }
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
        targets=java.util.Collections.unmodifiableMap(new LinkedHashMap<>(targets));
        java.util.Objects.requireNonNull(uniformInputs,"Pack uniform inputs");
        java.util.Objects.requireNonNull(terrain,"Pack terrain");
        for(var resourceName:resources.images().keySet())requireUserResource(resourceName);
        for(var resourceName:resources.buffers().keySet())requireUserResource(resourceName);
        for(var resourceName:resources.uniforms().keySet())requireUserResource(resourceName);
        if(terrain.enabled() && programs.stream().noneMatch(p->p.target()==PackProgram.Target.TERRAIN && p.vertex()!=null && !p.pipelines().isEmpty()))
            throw new IllegalArgumentException("Captured terrain requires an explicit vanilla terrain program and exact pipeline selectors");
        if(terrain.enabled())for(var program:programs)if(program.target()==PackProgram.Target.TERRAIN
            && (program.vertex()==null || program.pipelines().isEmpty() || !PackTerrain.PIPELINES.containsAll(program.pipelines()) || program.surface()!=null || program.worldRays()))
            throw new IllegalArgumentException("Captured terrain programs require supported exact vanilla pipelines without surface replay");
        if(targets.size()>64)throw new IllegalArgumentException("At most 64 named colour targets per pack");
        for(var target:targets.entrySet()) {
            requireUserResource(target.getKey());
            PackPass.requireBindingName(target.getKey());java.util.Objects.requireNonNull(target.getValue());
            if(resources.images().containsKey(target.getKey()) || resources.buffers().containsKey(target.getKey()) || resources.uniforms().containsKey(target.getKey()))
                throw new IllegalArgumentException("Target and storage resource share a name: "+target.getKey());
        }
        if(worldColor!=null) {
            PackPass.requireBindingName(worldColor);
            var main=targets.get(worldColor);
            if(main==null || PackTarget.integer(main.format()) || main.scale()!=1 || main.width()!=0 || main.height()!=0)
                throw new IllegalArgumentException("worldColor requires a declared floating-point full-resolution target");
            if(passes.stream().noneMatch(pass->pass.stage()==PackPass.Stage.SCENE && (pass.phase()==PackPass.Phase.COMPOSITE || pass.phase()==PackPass.Phase.FINAL)))
                throw new IllegalArgumentException("worldColor requires an explicit post-world scene presentation pass");
        }
        if(materialAddons.size()>16)throw new IllegalArgumentException("At most 16 material addons are supported per shaderpack");
        var addonIds=new HashSet<String>();
        for(String addon:materialAddons) {
            requireId(addon);
            if(!addon.endsWith(".json") || !addonIds.add(addon))throw new IllegalArgumentException("Invalid or duplicate material addon resource: "+addon);
        }
        long computes=passes.stream().filter(p->p.stage()==PackPass.Stage.COMPUTE).count();
        if (parameters.size() > 32 || passes.size()-computes > 256 || computes > 256) throw new IllegalArgumentException("Pack exceeds parameter/pass limit");
        var names = new HashSet<String>();
        for (var parameter : parameters) if (!names.add(parameter.name())) throw new IllegalArgumentException("Duplicate parameter");
        names.clear();
        var passNames=passes.stream().map(PackPass::name).collect(java.util.stream.Collectors.toSet());
        int lightmaps = 0;
        var earlierScenes=new HashSet<String>();
        for (var pass : passes.stream().sorted(java.util.Comparator.comparing(PackPass::phase)).toList()) {
            requireUserResource(pass.name());
            if (!names.add(pass.name())) throw new IllegalArgumentException("Duplicate pass name");
            if(resources.images().containsKey(pass.name()) || resources.buffers().containsKey(pass.name()) || resources.uniforms().containsKey(pass.name()) || targets.containsKey(pass.name()))
                throw new IllegalArgumentException("Pass and resource share a name: "+pass.name());
            for(var sampler:pass.inputs().keySet()) if(resources.images().containsKey(sampler) || resources.buffers().containsKey(sampler) || resources.uniforms().containsKey(sampler))
                throw new IllegalArgumentException("Input sampler "+sampler+" of "+pass.name()+" shadows a pack resource; pick another sampler name");
            for(var uniform:pass.uniforms().entrySet()) {
                String binding=uniform.getKey(),resource=uniform.getValue();
                if(!resources.uniforms().containsKey(resource))throw new IllegalArgumentException("Uniform block "+binding+" of "+pass.name()+" uses an undeclared uniform resource: "+resource);
                if(resources.images().containsKey(binding) || resources.buffers().containsKey(binding) || targets.containsKey(binding)
                    || passNames.contains(binding) || (resources.uniforms().containsKey(binding) && !binding.equals(resource)))
                    throw new IllegalArgumentException("Uniform block binding shadows a pack resource or pass: "+binding);
            }
            for(var producer:pass.inputs().values()) if(PackDepthSource.named(producer)==null && !earlierScenes.contains(producer) && !resources.images().containsKey(producer) && !targets.containsKey(producer))
                throw new IllegalArgumentException("Input for "+pass.name()+" requires an earlier scene pass or pack image: "+producer);
            for(var output:pass.outputs().values()) if(!targets.containsKey(output))
                throw new IllegalArgumentException("Output of "+pass.name()+" uses an undeclared colour target: "+output);
            if(pass.groups()!=null && pass.groups().startsWith("image:") && !resources.images().containsKey(pass.groups().substring(6)))
                throw new IllegalArgumentException("Compute pass "+pass.name()+" sizes by an undeclared image: "+pass.groups());
            if (pass.stage() == PackPass.Stage.LIGHTMAP) lightmaps++;
            else if (pass.stage() == PackPass.Stage.SCENE) earlierScenes.add(pass.name());
        }
        if (lightmaps > 1) throw new IllegalArgumentException("At most one lightmap pass is supported");
        var programTargets=new HashSet<PackProgram.Target>();
        if((worldColor!=null || programs.stream().anyMatch(program->!program.outputs().isEmpty()))
            && programs.stream().anyMatch(program->program.target()==PackProgram.Target.BACKGROUND))
            throw new IllegalArgumentException("Background programs cannot share named world attachments; use the sky family");
        var selectedPipelines=new HashSet<String>();
        for(var program:programs) {
            if(program.pipelines().isEmpty() && !programTargets.add(program.target()))throw new IllegalArgumentException("Duplicate world program family fallback");
            for(String pipeline:program.pipelines())if(!selectedPipelines.add(pipeline))throw new IllegalArgumentException("Overlapping world pipeline selectors: "+pipeline);
            for(var output:program.outputs().values()) {
                var declaration=targets.get(output);
                if(declaration==null)throw new IllegalArgumentException("World program uses an undeclared colour target: "+output);
                if(declaration.scale()!=1 || declaration.width()!=0 || declaration.height()!=0)
                    throw new IllegalArgumentException("World target must match the borrowed host depth dimensions: "+output);
            }
            for(var input:program.inputs().entrySet()) {
                if(PackDepthSource.named(input.getValue())==null && !targets.containsKey(input.getValue()) && !resources.images().containsKey(input.getValue()))
                    throw new IllegalArgumentException("World input requires a named target or pack image: "+input.getValue());
                if(resources.images().containsKey(input.getKey()) || resources.buffers().containsKey(input.getKey()) || resources.uniforms().containsKey(input.getKey()))
                    throw new IllegalArgumentException("World input shadows a pack resource: "+input.getKey());
            }
            for(var uniform:program.uniforms().entrySet()) {
                if(!resources.uniforms().containsKey(uniform.getValue()))throw new IllegalArgumentException("World program uses an undeclared uniform resource: "+uniform.getValue());
                if(resources.images().containsKey(uniform.getKey()) || resources.buffers().containsKey(uniform.getKey()) || targets.containsKey(uniform.getKey())
                    || passNames.contains(uniform.getKey()) || (resources.uniforms().containsKey(uniform.getKey()) && !uniform.getKey().equals(uniform.getValue())))
                    throw new IllegalArgumentException("World uniform binding shadows a pack resource or pass: "+uniform.getKey());
            }
        }
    }

    private static void requireUserResource(String name) {
        if(PackDepthSource.named(name)!=null)throw new IllegalArgumentException("Pack resource shadows a renderer depth source: "+name);
    }

    /** Exact host pipeline selectors take precedence over the matching family fallback. */
    public PackProgram worldProgram(PackProgram.Target target,String pipeline) {
        for(var program:programs)if(program.target()==target && program.pipelines().contains(pipeline))return program;
        for(var program:programs)if(program.target()==target && program.pipelines().isEmpty())return program;
        return null;
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
                    pass.has("scale") ? pass.get("scale").getAsFloat() : 1, optional(pass, "spirv"),value(pass,"format",PackPass.Format.SCENE),pass.has("history") && pass.get("history").getAsBoolean(),inputs(pass),pass.has("lightingHistory") && pass.get("lightingHistory").getAsBoolean(),groups(pass),outputs(pass),uniformBindings(pass),value(pass,"phase",PackPass.Phase.COMPOSITE),optional(pass,"vertex"),vertexCount(pass)));
            }
            var programs=new ArrayList<PackProgram>();
            if(json.has("programs")) for(var entry:json.getAsJsonArray("programs")) {
                var program=entry.getAsJsonObject();
                programs.add(new PackProgram(value(program,"target",PackProgram.Target.TERRAIN),optional(program,"vertex"),
                    program.get("fragment").getAsString(),program.has("worldRays") && program.get("worldRays").getAsBoolean(),optional(program,"surface"),program.has("surfaceOnly") && program.get("surfaceOnly").getAsBoolean(),program.has("voxelShadows") && program.get("voxelShadows").getAsBoolean(),
                    program.has("surfaceBeforeTerrain")?program.get("surfaceBeforeTerrain").getAsBoolean():PackProgram.defaultSurfaceBeforeTerrain(program.get("fragment").getAsString(),optional(program,"surface")),outputs(program),inputs(program),uniformBindings(program),pipelineSelectors(program),worldStages(program),patchVertices(program)));
            }
            return new ShaderPack(id, json.has("name") ? json.get("name").getAsString() : id,
                optional(json, "script"), parameters, passes,programs,materialAddons(json),resources(json),targets(json),uniformInputs(json),optional(json,"worldColor"),PackTerrain.parse(json.has("terrain")?json.getAsJsonObject("terrain"):null));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid GreenMarine shaderpack: " + error.getMessage(), error);
        }
    }
    private static Map<com.koper.greenmarine.api.render.GraphicsStage,String> worldStages(JsonObject program) {
        var stages=new java.util.EnumMap<com.koper.greenmarine.api.render.GraphicsStage,String>(com.koper.greenmarine.api.render.GraphicsStage.class);
        for(var stage:com.koper.greenmarine.api.render.GraphicsStage.values()) {
            String key=switch(stage){case GEOMETRY->"geometry";case TESS_CONTROL->"tessControl";case TESS_EVALUATION->"tessEvaluation";default->null;};
            if(key!=null && program.has(key))stages.put(stage,program.get(key).getAsString());
        }
        return stages;
    }
    private static int patchVertices(JsonObject program) {
        if(!program.has("patchVertices"))return 0;
        var value=program.getAsJsonPrimitive("patchVertices");
        if(!value.isNumber())throw new IllegalArgumentException("patchVertices must be an integer");
        return value.getAsBigDecimal().intValueExact();
    }
    private static int vertexCount(JsonObject pass) {
        if(!pass.has("vertices"))return 3;
        var value=pass.getAsJsonPrimitive("vertices");
        if(!value.isNumber())throw new IllegalArgumentException("vertices must be an integer");
        return value.getAsBigDecimal().intValueExact();
    }
    private static Set<String> pipelineSelectors(JsonObject program) {
        if(!program.has("pipelines"))return Set.of();
        var selectors=new LinkedHashSet<String>();
        for(var value:program.getAsJsonArray("pipelines")) {
            if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || !selectors.add(value.getAsString()))
                throw new IllegalArgumentException("World pipeline selectors require unique resource ids");
        }
        return selectors;
    }
    private static PackInputs uniformInputs(JsonObject json) {
        if(!json.has("uniformInputs"))return PackInputs.NONE;
        if(!json.get("uniformInputs").isJsonObject())throw new IllegalArgumentException("uniformInputs must be an object");
        return PackInputs.parse(json.getAsJsonObject("uniformInputs"));
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
    private static Map<String,String> uniformBindings(JsonObject pass) {
        if(!pass.has("uniforms"))return Map.of();
        var bindings=new LinkedHashMap<String,String>();
        for(var entry:pass.getAsJsonObject("uniforms").entrySet()) {
            if(!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Uniform block resource must be a string: "+entry.getKey());
            bindings.put(entry.getKey(),entry.getValue().getAsString());
        }
        return bindings;
    }
    private static Map<Integer,String> outputs(JsonObject pass) {
        if(!pass.has("outputs"))return Map.of();
        var result=new LinkedHashMap<Integer,String>();
        for(var entry:pass.getAsJsonObject("outputs").entrySet()) {
            if(!entry.getKey().matches("0|[1-9][0-9]?"))throw new IllegalArgumentException("Invalid fragment output location: "+entry.getKey());
            if(!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())throw new IllegalArgumentException("Output target must be a string");
            result.put(Integer.parseInt(entry.getKey()),entry.getValue().getAsString());
        }
        return result;
    }
    private static Map<String,PackTarget> targets(JsonObject json) {
        if(!json.has("targets"))return Map.of();
        var result=new LinkedHashMap<String,PackTarget>();
        for(var entry:json.getAsJsonObject("targets").entrySet()) {
            var target=entry.getValue().getAsJsonObject();
            for(var key:target.keySet())if(!java.util.Set.of("format","size","scale","clear","clearEachFrame").contains(key))throw new IllegalArgumentException("Unknown target property: "+key);
            int width=0,height=0;float scale=target.has("scale")?target.get("scale").getAsFloat():1;
            if(target.has("size")) {
                var size=target.getAsJsonArray("size");
                if(size.size()!=2 || target.has("scale"))throw new IllegalArgumentException("Colour targets take size [w,h] or scale");
                width=size.get(0).getAsBigDecimal().intValueExact();height=size.get(1).getAsBigDecimal().intValueExact();scale=0;
            }
            List<Double> clear=List.of(0d,0d,0d,0d);
            if(target.has("clear")) {
                var components=new ArrayList<Double>();
                for(var component:target.getAsJsonArray("clear"))components.add(component.getAsDouble());
                clear=components;
            }
            result.put(entry.getKey(),new PackTarget(value(target,"format",PackResources.Format.RGBA16F),width,height,scale,clear,target.has("clearEachFrame") && target.get("clearEachFrame").getAsBoolean()));
        }
        return result;
    }
    private static PackResources resources(JsonObject json) {
        if (!json.has("resources")) return PackResources.NONE;
        var root = json.getAsJsonObject("resources");
        for (var key : root.keySet()) if (!key.equals("images") && !key.equals("buffers") && !key.equals("uniforms")) throw new IllegalArgumentException("Unknown resource kind: " + key);
        var images = new LinkedHashMap<String, PackResources.Image>(); var buffers = new LinkedHashMap<String, PackResources.Buffer>();
        if (root.has("images")) for (var entry : root.getAsJsonObject("images").entrySet()) {
            var image = entry.getValue().getAsJsonObject();
            var format = value(image, "format", PackResources.Format.RGBA16F);
            boolean clearEachFrame=image.has("clearEachFrame") && image.get("clearEachFrame").getAsBoolean();
            if (image.has("size")) {
                var size = image.getAsJsonArray("size");
                if (size.size() < 1 || size.size() > 3 || image.has("scale")) throw new IllegalArgumentException("Image " + entry.getKey() + " takes size [w], [w,h], [w,h,d] or scale");
                if (size.size() == 3 && size.get(2).getAsInt() <= 0) throw new IllegalArgumentException("Volume image depth must be positive");
                images.put(entry.getKey(), new PackResources.Image(format, size.get(0).getAsInt(), size.size() == 1 ? 1 : size.get(1).getAsInt(), 0, size.size() == 3 ? size.get(2).getAsInt() : 0, size.size(),clearEachFrame));
            } else images.put(entry.getKey(), new PackResources.Image(format, 0, 0, image.has("scale") ? image.get("scale").getAsFloat() : 1,0,2,clearEachFrame));
        }
        if (root.has("buffers")) for (var entry : root.getAsJsonObject("buffers").entrySet())
            buffers.put(entry.getKey(), new PackResources.Buffer(entry.getValue().getAsJsonObject().get("size").getAsInt()));
        var uniforms=new LinkedHashMap<String,PackUniformBlock>();
        if(root.has("uniforms"))for(var entry:root.getAsJsonObject("uniforms").entrySet()) {
            var members=new ArrayList<PackUniformBlock.Member>();
            for(var element:entry.getValue().getAsJsonArray()) {
                var member=element.getAsJsonObject();
                for(String key:member.keySet())if(!java.util.Set.of("name","type","arrayLength","source").contains(key))
                    throw new IllegalArgumentException("Unknown uniform member property: "+key);
                int length=0;
                if(member.has("arrayLength")) {
                    var arrayLength=member.getAsJsonPrimitive("arrayLength");
                    if(!arrayLength.isNumber() || (length=arrayLength.getAsBigDecimal().intValueExact())<=0)
                        throw new IllegalArgumentException("Uniform arrayLength must be a positive integer");
                }
                members.add(new PackUniformBlock.Member(uniformMemberString(member,"name"),uniformMemberString(member,"type"),length,uniformMemberString(member,"source")));
            }
            uniforms.put(entry.getKey(),new PackUniformBlock(members));
        }
        return new PackResources(images, buffers,uniforms);
    }
    private static String uniformMemberString(JsonObject member,String key) {
        var value=member.get(key);
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Uniform member needs a string "+key);
        return value.getAsString();
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
