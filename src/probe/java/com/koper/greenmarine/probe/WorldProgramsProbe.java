package com.koper.greenmarine.probe;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.client.*;
import com.koper.greenmarine.client.rt.RayWorld;
import com.koper.greenmarine.client.world.WorldPrograms;
import net.minecraft.client.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Client-only lake fixture; server blocks are never changed. */
public final class WorldProgramsProbe {
    public static boolean bobbing,night,rainy,flashing,wireframe;
    private static int stage,ticks;
    private static final boolean SOLSTICE=Boolean.getBoolean("greenmarine.probe.solstice");
    private static ShaderSettings saved;
    private static AutoCloseable failurePack,originalPrismarine,fixedPrismarine,environmentPack;
    public static float originalRain,originalThunder;
    private static boolean enabled,oit;
    private static com.koper.greenmarine.api.render.MarineRenderer.Registration bloomFixture;
    private static volatile GameType mode;
    private static Vec3 position;
    private static float yaw,pitch;
    private static final Map<BlockPos,BlockState> BLOCKS=new LinkedHashMap<>();
    private static final AtomicBoolean captured=new AtomicBoolean();
    private static final com.google.gson.JsonObject result=new com.google.gson.JsonObject();
    static void tick(Minecraft client,Path output) {
        try {
            if(client.player==null)return;client.player.setDeltaMovement(Vec3.ZERO);
            if(saved==null) {
                originalRain=client.level.getRainLevel(0);originalThunder=client.level.getThunderLevel(0);
                oit=client.options.improvedTransparency().get();
                if(Boolean.getBoolean("greenmarine.probe.oit"))client.options.improvedTransparency().set(true);
                saved=ShaderRuntime.settings();enabled=GreenMarineClient.enabled();position=client.player.position();yaw=client.player.getYRot();pitch=client.player.getXRot();
                saved.save(output.resolve("original-settings.json"));
                Files.writeString(output.resolve("original-pose.json"),"{\"x\":"+position.x+",\"y\":"+position.y+",\"z\":"+position.z+",\"yaw\":"+yaw+",\"pitch\":"+pitch+"}\n");
                var server=client.getSingleplayerServer();var uuid=client.player.getUUID();
                server.execute(()->{var player=server.getPlayerList().getPlayer(uuid);mode=player.gameMode();player.setGameMode(GameType.SPECTATOR);});
                if(!enabled)GreenMarineClient.toggleEffect();stage=1;ticks=0;return;
            }
            if(mode==null || !client.player.isSpectator())return;
            double moved=stage==7?Math.min(ticks,120)*.08:stage>=8?9.6:0;
            client.player.setPos(-55.5+moved,stage==38?87.7:stage==47?120:93,76.5);client.player.setYRot(180);client.player.setYHeadRot(180);client.player.setXRot(35);
            ticks++;
            if(stage!=12 && ShaderRuntime.status().state()==PackStatus.State.FAILED)throw new IllegalStateException(ShaderRuntime.status().detail());
            switch(stage) {
                case 1 -> {
                    if(ticks<60)return;
                    for(int x=-65;x<=-45;x++)for(int z=54;z<=75;z++) {
                        set(client,new BlockPos(x,87,z),((x+z)&1)==0?Blocks.SAND.defaultBlockState():Blocks.SMOOTH_STONE.defaultBlockState());
                        for(int y=88;y<=98;y++)set(client,new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
                        if(x>=-62 && x<=-48 && z>=56 && z<=70) {
                            set(client,new BlockPos(x,88,z),Blocks.WATER.defaultBlockState());set(client,new BlockPos(x,89,z),Blocks.WATER.defaultBlockState());
                        }
                    }
                    for(int y=90;y<93;y++)set(client,new BlockPos(-48,y,66),Blocks.GLASS.defaultBlockState());
                    set(client,new BlockPos(-58,88,62),Blocks.SEA_LANTERN.defaultBlockState());
                    select(RayMode.HARDWARE);stage=2;ticks=0;
                }
                case 2 -> {
                    if(ticks<120 || !ShaderRuntime.status().loaded())return;
                    if(SOLSTICE && ShaderRuntime.rays().effective()==RayMode.HARDWARE && RayWorld.surfaceFrames()<160)return;
                    if(WorldPrograms.draws()==0 || WorldPrograms.captures()==0)throw new IllegalStateException("World pipelines/copy were not submitted");
                    result.add("capabilities",new com.google.gson.Gson().toJsonTree(ShaderRuntime.capabilities()));
                    result.add("packStatus",new com.google.gson.Gson().toJsonTree(ShaderRuntime.status()));
                    result.addProperty("oit",client.options.improvedTransparency().get());result.add("mainWorldGpu",new com.google.gson.Gson().toJsonTree(WorldPrograms.mainGpu()));
                    if(SOLSTICE) {
                        if(!WorldPrograms.surfaceReady())throw new IllegalStateException("Surface capture not ready");
                        boolean hardware=ShaderRuntime.rays().effective()==RayMode.HARDWARE;
                        if(hardware && (!RayWorld.surfaceReady() || RayWorld.surfaceFrames()==0))throw new IllegalStateException("Native surface rays did not submit");
                        result.addProperty("surfaceReady",WorldPrograms.surfaceReady());result.addProperty("surfaceRaysReady",RayWorld.surfaceReady());result.addProperty("surfaceRayFrames",RayWorld.surfaceFrames());result.addProperty("legacyRayFrames",RayWorld.frames());
                        result.add("surfaceCpu",new com.google.gson.Gson().toJsonTree(RayWorld.surfaceCpuTiming()));result.add("surfaceGpu",new com.google.gson.Gson().toJsonTree(RayWorld.surfaceGpuTiming()));
                    }
                    result.addProperty("variants",WorldPrograms.variants());result.addProperty("draws",WorldPrograms.draws());result.addProperty("opaqueCopies",WorldPrograms.captures());
                    result.add("opaqueCopyCpu",new com.google.gson.Gson().toJsonTree(WorldPrograms.copyCpu()));
                    result.add("traceCpu",new com.google.gson.Gson().toJsonTree(RayWorld.cpuTiming()));result.add("traceGpu",new com.google.gson.Gson().toJsonTree(RayWorld.gpuTiming()));
                    capture(client,output,"water.png");stage=3;
                }
                case 3 -> {if(!captured.get())return;ShaderRuntime.previewParameter("waves",0);ShaderRuntime.previewParameter("refraction",0);ShaderRuntime.previewParameter("reflection",0);ShaderRuntime.previewParameter("absorption",0);stage=4;ticks=0;}
                case 4 -> {if(ticks<30)return;capture(client,output,"water-flat.png");stage=5;}
                case 5 -> {if(!captured.get())return;for(String p:List.of("waves","refraction","reflection","absorption"))ShaderRuntime.previewParameter(p,1);bobbing=true;stage=6;ticks=0;}
                case 6 -> {if(ticks<30)return;capture(client,output,"water-bob.png");stage=7;ticks=0;}
                case 7 -> {if(ticks<120)return;capture(client,output,"water-moving.png");stage=8;ticks=0;}
                case 8 -> {if(!captured.get())return;bobbing=false;client.getWindow().setWindowed(1000,750);captured.set(false);client.reloadResourcePacks().whenComplete((v,error)->{if(error!=null)client.execute(()->abort(client,output,error));else captured.set(true);});stage=9;ticks=0;}
                case 9 -> {if(!captured.get() || ticks<120 || !ShaderRuntime.status().loaded())return;capture(client,output,"water-resized.png");stage=10;}
                case 10 -> {if(!captured.get())return;select(RayMode.OFF);stage=11;ticks=0;}
                case 11 -> {
                    if(ticks<40 || !ShaderRuntime.status().loaded())return;
                    result.add("offStatus",new com.google.gson.Gson().toJsonTree(ShaderRuntime.status()));capture(client,output,"water-no-rays.png");stage=14;
                }
                case 14 -> {
                    if(!captured.get())return;
                    var definition=new ShaderPack("probe:world_failure","Broken world program",null,List.of(),List.of(),List.of(new PackProgram(PackProgram.Target.TERRAIN,null,"probe:broken",false)));
                    failurePack=GreenMarine.PACKS.register(new PackCatalog.LoadedPack(definition,"function update(frame) end",Map.of("probe:broken","#version 330\ninvalid GLSL\n")));
                    stage=12;ticks=0;ShaderRuntime.apply(new ShaderSettings(definition.id(),Quality.ULTRA,RayMode.OFF,false));
                }
                case 12 -> {
                    if(ShaderRuntime.status().state()!=PackStatus.State.FAILED) {if(ticks>60)throw new IllegalStateException("Invalid world shader not reported Failed");return;}
                    result.add("failureStatus",new com.google.gson.Gson().toJsonTree(ShaderRuntime.status()));failurePack.close();failurePack=null;select(RayMode.OFF);stage=13;ticks=0;
                }
                case 13 -> {
                    if(ticks<40 || !ShaderRuntime.status().loaded())return;
                    if(WorldPrograms.draws()==0)throw new IllegalStateException("World shader did not recover");
                    result.addProperty("recovered",true);
                    if(SOLSTICE){ShaderRuntime.previewParameter("surfaceDiagnostic",1);stage=35;ticks=0;}
                    else{ShaderRuntime.previewParameter("waveDiagnostic",1);stage=32;ticks=0;}
                }
                case 35 -> {if(ticks<30)return;capture(client,output,"surface-normals.png");stage=36;ticks=0;}
                case 36 -> {if(!captured.get())return;ShaderRuntime.previewParameter("surfaceDiagnostic",2);stage=37;ticks=0;}
                case 37 -> {if(ticks<30)return;capture(client,output,"surface-albedo.png");stage=38;ticks=0;}
                case 38 -> {if(ticks==1)ShaderRuntime.previewParameter("surfaceDiagnostic",0);if(ticks<30)return;capture(client,output,"underwater.png");stage=39;ticks=0;}
                case 39 -> {if(!captured.get())return;rainy=flashing=true;select(RayMode.HARDWARE);stage=40;ticks=0;}
                case 40 -> {if(ticks<100 || !ShaderRuntime.status().loaded())return;capture(client,output,"wet-lightning.png");stage=41;ticks=0;}
                case 41 -> {if(!captured.get())return;rainy=flashing=false;ShaderRuntime.previewParameter("surfaceDiagnostic",7);stage=32;ticks=0;}
                case 32 -> {if(ticks<30)return;capture(client,output,"water-wave-vertices.png");stage=33;}
                case 33 -> {if(!captured.get())return;ShaderRuntime.previewParameter(SOLSTICE?"surfaceDiagnostic":"waveDiagnostic",0);night=true;select(RayMode.HARDWARE);stage=20;ticks=0;}
                case 20 -> {
                    if(ticks<100 || !ShaderRuntime.status().loaded())return;
                    capture(client,output,"water-night.png");stage=21;
                }
                case 21 -> {if(!captured.get())return;night=false;if(SOLSTICE){wireframe=true;stage=42;ticks=0;return;}ShaderRuntime.apply(new ShaderSettings("greenmarine:none",Quality.ULTRA,RayMode.OFF,true));stage=22;ticks=0;}
                case 42 -> {
                    if(ticks<30)return;
                    if(WorldPrograms.surfaceReady() || RayWorld.surfaceReady())throw new IllegalStateException("Wireframe published material outputs");
                    if(ShaderRuntime.status().state()!=PackStatus.State.FALLBACK)throw new IllegalStateException("Wireframe fallback not reported");
                    result.add("wireframeStatus",new com.google.gson.Gson().toJsonTree(ShaderRuntime.status()));wireframe=false;
                    if(ShaderRuntime.capabilities().hardwareRayTracing()){ProbeSurfaceBridge.hideOptionalSymbols();select(RayMode.HARDWARE);stage=44;ticks=0;}
                    else{ShaderRuntime.apply(new ShaderSettings("greenmarine:none",Quality.ULTRA,RayMode.OFF,true));stage=22;ticks=0;}
                }
                case 44 -> {
                    if(ticks<160 || !ShaderRuntime.status().loaded())return;
                    if(ShaderRuntime.status().state()!=PackStatus.State.FALLBACK || RayWorld.surfaceReady())throw new IllegalStateException("Missing optional ABI not reported as fallback");
                    result.add("legacyNativeStatus",new com.google.gson.Gson().toJsonTree(ShaderRuntime.status()));ProbeSurfaceBridge.restore();ShaderRuntime.reset();select(RayMode.HARDWARE);stage=45;ticks=0;
                }
                case 45 -> {
                    if(ticks<160 || !ShaderRuntime.status().loaded())return;
                    if(!RayWorld.surfaceReady() || ShaderRuntime.status().state()!=PackStatus.State.LOADED)throw new IllegalStateException("Surface native API did not recover");
                    result.addProperty("surfaceNativeRecovered",true);ShaderRuntime.apply(new ShaderSettings("greenmarine:none",Quality.ULTRA,RayMode.OFF,true));stage=22;ticks=0;
                }
                case 22 -> {
                    if(ticks<60)return;
                    result.add("vanillaMainGpu",new com.google.gson.Gson().toJsonTree(WorldPrograms.mainGpu()));
                    capture(client,output,"vanilla.png");stage=23;
                }
                case 23 -> {
                    if(!captured.get())return;
                    if(SOLSTICE){
                        var definition=new ShaderPack("probe:environment","Environment probe",null,List.of(),List.of(new PackPass("environment",PackPass.Stage.SCENE,"probe:environment",null,PackPass.Requirement.NONE,Quality.LOW,1,null)));
                        String shader="#version 330\n#extension GL_ARB_separate_shader_objects : require\nlayout(std140) uniform MarineEnvironment {vec4 Environment;vec4 Climate;vec4 SkyColor;vec4 EnvironmentFogColor;vec4 Celestial;vec4 Moon;vec4 Dimension;vec4 EnvironmentTime;};layout(location=0)out vec4 color;void main(){color=vec4(clamp(Environment.x/128.0,0,1),Environment.y,Dimension.x,1);}";
                        environmentPack=GreenMarine.PACKS.register(new PackCatalog.LoadedPack(definition,"function update(frame) end",Map.of("probe:environment",shader)));
                        ShaderRuntime.apply(new ShaderSettings(definition.id(),Quality.ULTRA,RayMode.OFF,false));stage=46;ticks=0;return;
                    }
                    originalPrismarine=registerPrismarine(false);ShaderRuntime.apply(new ShaderSettings("prismarine:showcase",Quality.ULTRA,RayMode.HARDWARE,true));stage=24;ticks=0;
                }
                case 46 -> {if(ticks<30 || !ShaderRuntime.status().loaded())return;result.addProperty("environmentLowAltitude",client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos.y);capture(client,output,"environment-low.png");stage=47;ticks=0;rainy=true;}
                case 47 -> {if(ticks<30)return;result.addProperty("environmentHighAltitude",client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos.y);result.addProperty("environmentRain",client.level.getRainLevel(0));capture(client,output,"environment-high.png");stage=48;ticks=0;}
                case 48 -> {if(!captured.get())return;result.addProperty("status","complete");finish(client,output);}
                case 24 -> {if(ticks<120 || !ShaderRuntime.status().loaded())return;capture(client,output,"prismarine-original.png");stage=25;}
                case 25 -> {if(!captured.get())return;fixedPrismarine=registerPrismarine(true);ShaderRuntime.apply(new ShaderSettings("prismarine:showcase_fixed",Quality.ULTRA,RayMode.HARDWARE,true));stage=26;ticks=0;}
                case 26 -> {
                    if(ticks<120 || !ShaderRuntime.status().loaded())return;
                    result.add("prismarineStatus",new com.google.gson.Gson().toJsonTree(ShaderRuntime.status()));capture(client,output,"prismarine-corrected.png");stage=27;
                }
                case 27 -> {if(!captured.get())return;registerBloomFixture();ShaderRuntime.apply(new ShaderSettings("prismarine:showcase_fixed",Quality.ULTRA,RayMode.OFF,true));stage=28;ticks=0;}
                case 28 -> {if(ticks<40 || !ShaderRuntime.status().loaded())return;capture(client,output,"prismarine-no-rays.png");stage=29;}
                case 29 -> {if(!captured.get())return;ShaderRuntime.previewParameter("bloom",0);stage=30;ticks=0;}
                case 30 -> {if(ticks<30)return;capture(client,output,"prismarine-no-bloom.png");stage=31;}
                case 31 -> {if(!captured.get())return;result.addProperty("status","complete");finish(client,output);}
            }
        } catch(Throwable error) {abort(client,output,error);}
    }
    private static void registerBloomFixture() {
        bloomFixture=com.koper.greenmarine.api.render.MarineRenderer.register("probe:bloom_patch",com.koper.greenmarine.api.render.MarineRenderer.Stage.BEFORE_PACKS,session->{
            var vertex=net.minecraft.resources.Identifier.parse("probe:bloom_quad");var fragment=net.minecraft.resources.Identifier.parse("probe:bloom_patch");
            var format=Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture().getFormat();
            var pipeline=com.mojang.renderpearl.api.pipeline.RenderPipeline.builder().withLocation(fragment).withVertexShader(vertex).withFragmentShader(fragment)
                .withColorTargetState(new com.mojang.renderpearl.api.pipeline.ColorTargetState(Optional.empty(),format,com.mojang.renderpearl.api.pipeline.ColorTargetState.WRITE_ALL))
                .withPrimitiveTopology(com.mojang.renderpearl.api.pipeline.PrimitiveTopology.TRIANGLES).withCull(false).build();
            String header="#version 330\n#extension GL_ARB_separate_shader_objects : require\n";
            var shader=new com.koper.greenmarine.api.render.ShaderProgram(pipeline,Map.of(
                vertex,header+"layout(location=0) out vec2 uv;void main(){uv=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(uv*2.0-1.0,0,1);}",
                fragment,header+"layout(location=0) in vec2 uv;layout(location=0) out vec4 color;void main(){if(uv.x<.45 || uv.x>.62 || uv.y<.4 || uv.y>.58)discard;color=vec4(.98,.94,.85,1);}"),Map.of(),Map.of());
            var program=session.compile(shader);
            var graph=session.graph(com.koper.greenmarine.api.render.RenderGraph.builder().importTexture("sceneColor")
                .pass(new com.koper.greenmarine.api.render.RenderGraph.Pass("bright_source",Set.of(),Set.of("sceneColor"),Set.of(),ctx->
                    ctx.draw(ctx.material(program),List.of("sceneColor"),null,null,false))).build());
            return graph::execute;
        });
    }
    private static AutoCloseable registerPrismarine(boolean corrected) throws Exception {
        var resources=new HashMap<String,String>();
        if(corrected) {
            var root=Path.of("../examples/prismarine-fixed");
            try(var files=Files.walk(root.resolve("assets"))) {for(var file:files.filter(Files::isRegularFile).toList()) {
                String name=root.relativize(file).toString();var parts=name.split("/",3);resources.put(parts[1]+":"+parts[2],Files.readString(file));
            }}
        } else try(var zip=new java.util.zip.ZipFile("resourcepacks/prismarine-showcase-0.4.0.zip")) {
            for(var item:zip.stream().filter(e->e.getName().startsWith("assets/") && !e.isDirectory() && !e.getName().endsWith(".spv")).toList()) {
                var parts=item.getName().split("/",3);resources.put(parts[1]+":"+parts[2],new String(zip.getInputStream(item).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        var manifests=new LinkedHashMap<String,String>();resources.forEach((id,text)->{if(id.contains(":greenmarine/packs/")) {
            var manifest=com.google.gson.JsonParser.parseString(text).getAsJsonObject();for(var pass:manifest.getAsJsonArray("passes"))pass.getAsJsonObject().remove("spirv");manifests.put(id,manifest.toString());
        }});
        var catalog=new PackCatalog();if(!catalog.reload(manifests,id->{var value=resources.get(id);if(value!=null)return value;try{return Files.readString(Path.of("../src/main/resources/assets/"+id.replace(':','/')));}catch(Exception error){return null;}}))throw new IllegalStateException(catalog.error());
        return GreenMarine.PACKS.register(catalog.packs().getFirst());
    }
    private static void set(Minecraft client,BlockPos pos,BlockState state) {BLOCKS.putIfAbsent(pos,client.level.getBlockState(pos));client.level.setBlock(pos,state,3);}
    private static void select(RayMode mode) {ShaderRuntime.apply(new ShaderSettings(SOLSTICE?"greenmarine:solstice_rt":"greenmarine:seabreeze",Quality.ULTRA,mode,true,saved.parameters()));}
    private static void capture(Minecraft client,Path output,String name) {
        captured.set(false);Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{try {image.writeToFile(output.resolve(name));captured.set(true);}catch(Exception error){client.execute(()->abort(client,output,error));}finally{image.close();}});
    }
    private static void finish(Minecraft client,Path output) {
        if(stage==99)return;stage=99;bobbing=night=rainy=flashing=wireframe=false;
        try{ProbeSurfaceBridge.restore();}catch(Exception error){GreenMarine.LOGGER.error("Surface bridge fixture cleanup failed",error);}
        if(bloomFixture!=null){bloomFixture.close();bloomFixture=null;}
        if(failurePack!=null){try{failurePack.close();}catch(Exception error){GreenMarine.LOGGER.error("Probe lease cleanup failed",error);}failurePack=null;}
        if(SOLSTICE && client.level!=null){client.level.setRainLevel(originalRain);client.level.setThunderLevel(originalThunder);client.level.setSkyFlashTime(0);}
        client.options.improvedTransparency().set(oit);if(saved!=null)ShaderRuntime.apply(saved);if(GreenMarineClient.enabled()!=enabled)GreenMarineClient.toggleEffect();
        for(var lease:new AutoCloseable[]{originalPrismarine,fixedPrismarine,environmentPack})if(lease!=null)try{lease.close();}catch(Exception error){GreenMarine.LOGGER.error("Prismarine probe cleanup failed",error);}
        if(client.level!=null)for(var entry:BLOCKS.entrySet())client.level.setBlock(entry.getKey(),entry.getValue(),3);BLOCKS.clear();
        try{Files.writeString(output.resolve("result.json"),result+"\n");}catch(Exception error){GreenMarine.LOGGER.error("Probe result write failed",error);}
        if(mode==null || client.player==null){client.stop();return;}
        var server=client.getSingleplayerServer();var uuid=client.player.getUUID();
        server.execute(()->{var player=server.getPlayerList().getPlayer(uuid);if(player!=null){player.setGameMode(mode);player.teleportTo(position.x,position.y,position.z);player.setYRot(yaw);player.setXRot(pitch);}client.execute(()->{client.disconnectWithSavingScreen();client.stop();});});
    }
    static void abort(Minecraft client,Path output,Throwable error) {GreenMarine.LOGGER.error("World programs probe failed at stage "+stage,error);result.addProperty("status","failed");result.addProperty("error",error.toString());finish(client,output);}
}
