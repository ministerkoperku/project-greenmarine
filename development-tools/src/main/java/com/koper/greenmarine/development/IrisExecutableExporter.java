package com.koper.greenmarine.development;

import com.google.gson.*;
import com.koper.greenmarine.development.iris.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.regex.*;

/** Generic automatic lowering into native manifests. Unsupported requirements block publication. */
public final class IrisExecutableExporter {
    public record Result(boolean executable,List<String> diagnostics) {
        public Result {diagnostics=List.copyOf(diagnostics);}
    }
    private static final Set<String> HOSTS=Set.of(
        "viewWidth","viewHeight","aspectRatio","frameCounter","frameTime","frameTimeCounter","worldTime","worldDay",
        "moonPhase","rainStrength","thunderStrength","cameraPosition","previousCameraPosition","cameraPositionInt","cameraPositionFract",
        "eyeAltitude","eyeBrightness","near","far","sunAngle","sunPosition","moonPosition","upPosition","gbufferModelView",
        "gbufferModelViewInverse","gbufferProjection","gbufferProjectionInverse","gbufferPreviousModelView","gbufferPreviousModelViewInverse",
        "gbufferPreviousProjection","gbufferPreviousProjectionInverse","hideGUI","firstPersonCamera","isEyeInWater","feetInWater","vehicleInWater",
        "relativeEyePosition","relativeVehiclePosition","playerLookVector","vehicleLookVector","isRiding","is_burning","is_hurt","is_invisible",
        "is_on_ground","is_sneaking","is_sprinting","temperature","biome","biome_category","biome_precipitation","screenBrightness","lightningBoltPosition");
    private static final Map<String,String> COLOR_ALIASES=Map.of("gcolor","colortex0","gdepth","colortex1","gnormal","colortex2","composite","colortex3","gaux1","colortex4","gaux2","colortex5","gaux3","colortex6","gaux4","colortex7");
    @FunctionalInterface public interface Output {void write(String relative,String text) throws IOException;}
    private IrisExecutableExporter() {}
    public static Result export(IrisSources sources,IrisPipelinePlan plan,List<IrisProjectExporter.Group> inventory,Output output) throws IOException {
        var nativeResources=IrisNativeResources.compile(plan,inventory);var all=new ArrayList<String>();boolean installed=false;
        var inputs=IrisNativeInputs.export(plan).getAsJsonArray("worlds");
        boolean variants=plan.worlds().stream().anyMatch(w->!w.folder().isEmpty()&&!w.programs().isEmpty());
        for(var world:plan.worlds()) {
            if(world.programs().isEmpty())continue;
            var lowered=nativeResources.worlds().stream().filter(w->w.folder().equals(world.folder())).findFirst().orElseThrow();
            var diagnostics=new ArrayList<String>();
            for(var d:lowered.diagnostics())diagnostics.add(d.code()+": "+d.path()+": "+d.message());
            for(var setting:plan.renderSettings().entrySet())if(!setting.getKey().startsWith("flip.") && !setting.getKey().startsWith("scale.") && !setting.getKey().startsWith("size.buffer.") && !setting.getKey().equals("allowConcurrentCompute")
                && !(setting.getKey().equals("separateAo") && Set.of("true","false").contains(setting.getValue())) && !(setting.getKey().equals("oldLighting") && setting.getValue().equals("false")))
                diagnostics.add("NATIVE_RENDER_STATE: "+setting.getKey()+"="+setting.getValue());
            if(variants)diagnostics.add("DIMENSION_DISPATCH: native per-dimension program selection is required");
            var manifest=new JsonObject();manifest.addProperty("schema",1);
            String tag=hash(world.folder()+UUID.randomUUID());
            manifest.addProperty("id","iris_imported:native_"+tag);manifest.addProperty("name","Converted Iris programs");
            manifest.add("targets",lowered.targets());var resources=lowered.resources();var uniformResources=new JsonObject();resources.add("uniforms",uniformResources);manifest.add("resources",resources);
            JsonObject configuration=null;
            for(var entry:inputs)if(entry.getAsJsonObject().get("folder").getAsString().equals(world.folder())) {
                var selected=entry.getAsJsonObject();
                if(selected.get("configurationValid").getAsBoolean()&&selected.get("optionConstantsComplete").getAsBoolean())configuration=selected.getAsJsonObject("uniformInputs").deepCopy();
                else diagnostics.add("UNIFORM_INPUT_CONFIGURATION: "+selected.get("diagnostics"));
                for(var reference:selected.getAsJsonArray("requiredHostReferences")) {
                    String name=reference.getAsString().split("\\.",2)[0];
                    if(!HOSTS.contains(name))diagnostics.add("EXPRESSION_HOST_INPUT: "+reference.getAsString());
                }
            }
            if(configuration==null)configuration=new JsonObject();
            manifest.add("uniformInputs",configuration);
            var passes=new JsonArray();manifest.add("passes",passes);
            var programs=new JsonArray();manifest.add("programs",programs);
            var terrainRoutes=new LinkedHashMap<String,List<String>>();
            IrisNativeTerrain.REQUESTS.forEach((pipeline,requested)->world.resolve(requested).ifPresent(p->terrainRoutes.computeIfAbsent(p.name(),key->new ArrayList<>()).add(pipeline)));
            var terrainRequests=new HashSet<>(IrisNativeTerrain.REQUESTS.values());terrainRequests.add("gbuffers_terrain");
            for(String requested:IrisProjectExporter.geometryRequests())if(!terrainRequests.contains(requested))world.resolve(requested)
                .ifPresent(p->diagnostics.add("HOST_GEOMETRY_REQUEST: "+requested+" resolves to "+p.name()+" and requires its own native host adapter"));
            if(!terrainRoutes.isEmpty()) {
                manifest.add("terrain",IrisNativeTerrain.capture(sources,plan,diagnostics));
                if(lowered.targets().has("colortex0"))manifest.addProperty("worldColor","colortex0");
                else diagnostics.add("WORLD_COLOR_TARGET: native terrain requires colortex0 for host world composition");
            }
            boolean seeded=false;int sequence=0;
            for(var program:world.programs()) {
                if(!program.enabled())continue;
                if(program.optionalIntegration()) {diagnostics.add("OPTIONAL_HOST_PROGRAM: "+program.name()+" requires its owning host adapter");continue;}
                if(program.phase()==IrisPipelinePlan.Phase.GBUFFERS && terrainRoutes.containsKey(program.name())) {
                    try {
                        if(!program.computes().isEmpty())throw new IllegalArgumentException("Terrain compute siblings require host dispatch");
                        var stageSources=new EnumMap<IrisStage,String>(IrisStage.class);
                        for(var stage:program.stages().values()) {
                            if(!stage.preprocessed())throw new IllegalArgumentException("Source preprocessing failed: "+stage.path());
                            stageSources.put(stage.kind(),stage.preprocessedText());
                        }
                        var original=IrisProgramLinker.link(stageSources);
                        var execution=lowered.passes().stream().filter(p->p.program().equals(program.name())&&p.kind().equals("WORLD")).findFirst().orElseThrow(()->new IllegalArgumentException("Missing native world mapping"));
                        var profile=plan.renderSettings().getOrDefault("separateAo","false").equals("true")?IrisTerrainAdapter.ColorProfile.IRIS_SEPARATE_AO:IrisTerrainAdapter.ColorProfile.IRIS_MULTIPLIED_AO;
                        for(String pipeline:terrainRoutes.get(program.name())) {
                            var linked=IrisTerrainAdapter.adapt(original,profile,IrisTerrainAdapter.ClipDepth.ZERO_TO_ONE_REVERSED,pipeline.equals("minecraft:pipeline/cutout_terrain")).program();
                            var emitted=pass(output,tag,sequence++,program.name(),program.phase(),linked,execution,configuration,uniformResources,lowered,diagnostics);
                            for(String key:List.of("name","phase","stage","vertices"))emitted.remove(key);
                            emitted.addProperty("target","terrain");var selectors=new JsonArray();selectors.add(pipeline);emitted.add("pipelines",selectors);programs.add(emitted);
                        }
                    }catch(IllegalArgumentException unsupported){diagnostics.add(program.name()+": "+unsupported.getMessage());}
                    continue;
                }
                if(!Set.of(IrisPipelinePlan.Phase.SETUP,IrisPipelinePlan.Phase.BEGIN,IrisPipelinePlan.Phase.SHADOWCOMP,IrisPipelinePlan.Phase.PREPARE,IrisPipelinePlan.Phase.DEFERRED,IrisPipelinePlan.Phase.COMPOSITE,IrisPipelinePlan.Phase.FINAL).contains(program.phase())) {
                    diagnostics.add("HOST_GEOMETRY_DISPATCH: "+program.name()+" requires native "+program.phase()+" execution");continue;
                }
                if(!seeded && program.phase().ordinal()>=IrisPipelinePlan.Phase.DEFERRED.ordinal()) {
                    if(terrainRoutes.isEmpty() && lowered.targets().has("colortex0"))passes.add(seed(output,tag,program.phase()==IrisPipelinePlan.Phase.DEFERRED?"deferred":"composite"));
                    seeded=true;
                }
                for(var compute:program.computes()) {
                    var execution=lowered.passes().stream().filter(p->p.source().equals(compute.path())&&p.kind().equals("COMPUTE")).findFirst();
                    if(execution.isEmpty() || execution.get().groups()==null){diagnostics.add("COMPUTE_DISPATCH: "+compute.path());continue;}
                    try {
                        var linked=IrisProgramLinker.link(Map.of(IrisStage.COMPUTE,compute.preprocessedText()));
                        passes.add(pass(output,tag,sequence++,program.name()+"_compute",program.phase(),linked,execution.get(),configuration,uniformResources,lowered,diagnostics));
                    }catch(IllegalArgumentException unsupported){diagnostics.add(compute.path()+": "+unsupported.getMessage());}
                }
                if(!program.stages().isEmpty())try {
                    var preprocessed=new EnumMap<IrisStage,String>(IrisStage.class);
                    for(var stage:program.stages().values()) {
                        if(!stage.preprocessed())throw new IllegalArgumentException("Source preprocessing failed: "+stage.path());
                        preprocessed.put(stage.kind(),stage.preprocessedText());
                    }
                    var linked=IrisFullscreenAdapter.link(preprocessed).program();
                    var execution=lowered.passes().stream().filter(p->p.program().equals(program.name())&&p.kind().equals("SCENE")).findFirst().orElseThrow(()->new IllegalArgumentException("Missing native scene mapping"));
                    passes.add(pass(output,tag,sequence++,program.name(),program.phase(),linked,execution,configuration,uniformResources,lowered,diagnostics));
                }catch(IllegalArgumentException unsupported){diagnostics.add(program.name()+": "+unsupported.getMessage());}
            }
            if(passes.isEmpty())diagnostics.add("NO_NATIVE_PASSES: no executable source programs");
            long computes=passes.asList().stream().filter(p->p.getAsJsonObject().has("compute")).count();
            if(computes>256 || passes.size()-computes>256)diagnostics.add("NATIVE_PASS_LIMIT: at most 256 raster and 256 compute passes");
            if(manifest.toString().length()>65536)diagnostics.add("NATIVE_MANIFEST_LIMIT: manifest exceeds the runtime 64 KiB limit");
            if(passes.asList().stream().noneMatch(p->p.getAsJsonObject().get("phase").getAsString().equals("final")))diagnostics.add("FINAL_PRESENTATION: explicit original final program required");
            if(diagnostics.isEmpty())try {com.koper.greenmarine.api.pack.ShaderPack.parse(manifest);}
            catch(IllegalArgumentException invalid){diagnostics.add("NATIVE_MANIFEST: "+invalid.getMessage());}
            String folder=world.folder().isEmpty()?"root":hash(world.folder());
            var candidate=new JsonObject();candidate.add("manifest",manifest);candidate.addProperty("executable",diagnostics.isEmpty());candidate.addProperty("runtimeVerified",false);
            var messages=new JsonArray();diagnostics.forEach(messages::add);candidate.add("diagnostics",messages);
            write(output,"conversion/native/"+folder+".json",new GsonBuilder().setPrettyPrinting().create().toJson(candidate));
            all.addAll(diagnostics.stream().map(message->(world.folder().isEmpty()?"root":world.folder())+": "+message).toList());
            if(diagnostics.isEmpty() && world.folder().isEmpty()) {
                write(output,"assets/iris_imported/greenmarine/packs/native.json",new GsonBuilder().setPrettyPrinting().create().toJson(manifest));installed=true;
            }
        }
        return new Result(installed,all);
    }
    private static JsonObject pass(Output output,String tag,int index,String name,IrisPipelinePlan.Phase phase,IrisProgramLinker.Program linked,IrisNativeResources.Pass execution,JsonObject configuration,JsonObject uniformResources,IrisNativeResources.World resources,List<String> diagnostics) throws IOException {
        boolean compute=linked.stages().containsKey(IrisStage.COMPUTE);String asset="native/"+tag+"/p"+index;
        var pass=new JsonObject();pass.addProperty("name","imported_"+index);pass.addProperty("phase",phase.name().toLowerCase(Locale.ROOT));
        pass.addProperty("stage",compute?"compute":"scene");pass.addProperty(compute?"compute":"fragment","iris_imported:"+asset);
        if(compute)pass.addProperty("groups",execution.groups());
        else {pass.addProperty("vertex","iris_imported:"+asset);pass.addProperty("vertices",6);}
        if(execution.kind().equals("WORLD")) {
            for(var stage:linked.stages().keySet()) {
                String key=switch(stage){case GEOMETRY->"geometry";case TESS_CONTROL->"tessControl";case TESS_EVALUATION->"tessEvaluation";default->null;};
                if(key!=null)pass.addProperty(key,"iris_imported:"+asset);
            }
            if(linked.stages().containsKey(IrisStage.TESS_CONTROL))pass.addProperty("patchVertices",3);
            // Ordinary terrain draws use Minecraft's existing triangular index stream.
            for(var stage:List.of(IrisStage.GEOMETRY,IrisStage.TESS_EVALUATION))if(linked.stages().containsKey(stage)
                && !Pattern.compile("layout\\s*\\([^)]*\\btriangles\\b[^)]*\\)\\s*in\\s*;").matcher(linked.stages().get(stage).text()).find())
                diagnostics.add("WORLD_INPUT_PRIMITIVE: "+name+" "+stage+" requires a triangular host stream");
        }
        var values=new JsonArray();var supported=new HashSet<>(HOSTS);
        if(configuration.has("constants"))supported.addAll(configuration.getAsJsonObject("constants").keySet());
        if(configuration.has("expressions"))for(var expression:configuration.getAsJsonArray("expressions"))supported.add(expression.getAsJsonObject().get("name").getAsString());
        for(var uniform:linked.uniforms()) {
            if(!supported.contains(uniform.name()))diagnostics.add("HOST_UNIFORM: "+name+": "+uniform.name());
            if(!uniform.type().matches("float|int|uint|bool|[biu]?vec[234]|mat[234](?:x[234])?"))diagnostics.add("UNIFORM_TYPE: "+name+": "+uniform.type());
            var member=new JsonObject();member.addProperty("name",uniform.name());member.addProperty("type",uniform.type());member.addProperty("source",uniform.name());
            if(!uniform.array().isBlank()) {
                var extent=Pattern.compile("\\[\\s*([1-9][0-9]*)\\s*]").matcher(uniform.array());
                if(!extent.matches())diagnostics.add("UNIFORM_ARRAY: "+name+": "+uniform.name()+uniform.array());else member.addProperty("arrayLength",Integer.parseInt(extent.group(1)));
            }
            values.add(member);
        }
        if(!values.isEmpty()) {
            String block="IrisInputs"+index;uniformResources.add(block,values);var uniforms=new JsonObject();uniforms.addProperty("IrisUniforms",block);pass.add("uniforms",uniforms);
        }
        var samplers=new JsonObject();
        for(var opaque:linked.opaques()) {
            if(execution.kind().equals("WORLD") && IrisTerrainAdapter.hostSampler(opaque)!=null)continue;
            String source=COLOR_ALIASES.getOrDefault(opaque.name(),opaque.name());
            String depth=switch(opaque.name()) {case "depthtex0","gdepthtex"->"world_depth";case "depthtex1"->"opaque_depth";case "depthtex2"->"opaque_no_hand_depth";default->null;};
            if(depth!=null && opaque.type().equals("sampler2D"))samplers.addProperty(opaque.name(),depth);
            else if(opaque.type().matches("[iu]?sampler2D") && resources.targets().has(source))samplers.addProperty(opaque.name(),source);
            else if(opaque.type().matches("[iu]?sampler[123]D")) {
                var image=resources.imageBindings().stream().filter(i->opaque.name().equals(i.sampler())).findFirst();
                if(image.isPresent())samplers.addProperty(opaque.name(),image.get().image());else diagnostics.add("HOST_SAMPLER: "+name+": "+opaque.name());
            } else if(opaque.type().matches("[iu]?image[123]D")) {
                if(!resources.resources().getAsJsonObject("images").has(opaque.name()))diagnostics.add("STORAGE_IMAGE: "+name+": "+opaque.name());
            } else diagnostics.add("OPAQUE_RESOURCE: "+name+": "+opaque.name()+" "+opaque.type());
        }
        pass.add("inputs",samplers);
        for(var block:linked.blocks()) {
            if(execution.kind().equals("WORLD") && Set.of("TerrainUniform","Projection","Globals","ChunkSection","MarineTerrainVertices","MarineTerrainDraws").contains(block.name()))continue;
            if(execution.kind().equals("WORLD") || !block.storage().equals("buffer") || !resources.resources().getAsJsonObject("buffers").has(block.name()))diagnostics.add("RESOURCE_BLOCK: "+name+": "+block.name());
        }
        if(!execution.outputs().isEmpty()) {
            var outputs=new JsonObject();execution.outputs().forEach((location,target)->outputs.addProperty(location.toString(),target));pass.add("outputs",outputs);
        }
        for(var stage:linked.stages().entrySet()) {
            if(stage.getValue().text().length()>262144)diagnostics.add("NATIVE_SOURCE_LIMIT: "+name+" "+stage.getKey()+" exceeds the runtime source limit");
            var compiled=IrisCompiler.compileExplicit(stage.getValue().text(),stage.getKey(),name);
            if(!compiled.success())diagnostics.add("NATIVE_STAGE_COMPILE: "+name+" "+stage.getKey()+": "+compiled.diagnostics());
            write(output,"assets/iris_imported/shaders/"+asset+"."+stage.getKey().extension,stage.getValue().text());
        }
        return pass;
    }
    private static JsonObject seed(Output output,String tag,String phase) throws IOException {
        String asset="native/"+tag+"/host_color";
        write(output,"assets/iris_imported/shaders/"+asset+".fsh","#version 450\nuniform sampler2D SceneSampler;layout(location=0)out vec4 color;void main(){color=texelFetch(SceneSampler,ivec2(gl_FragCoord.xy),0);}\n");
        var pass=new JsonObject();pass.addProperty("name","host_color");pass.addProperty("phase",phase);pass.addProperty("fragment","iris_imported:"+asset);
        var outputs=new JsonObject();outputs.addProperty("0","colortex0");pass.add("outputs",outputs);return pass;
    }
    private static void write(Output output,String path,String text) throws IOException {output.write(path,text);}
    private static String hash(String text) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))).substring(0,16);}
        catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
