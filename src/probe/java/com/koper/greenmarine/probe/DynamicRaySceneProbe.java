package com.koper.greenmarine.probe;

import com.google.gson.*;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.api.render.*;
import com.koper.greenmarine.client.*;
import com.koper.greenmarine.client.rt.*;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.*;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.*;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Controlled client-only edits; GPU samples come from native surface lighting, not scene color. */
public final class DynamicRaySceneProbe {
    private static final Gson GSON=new Gson();
    private static final Map<BlockPos,BlockState> BLOCKS=new LinkedHashMap<>();
    private static final JsonObject RESULT=new JsonObject(), MOVEMENT=new JsonObject();
    private static final JsonArray FRAMES=new JsonArray(), EVENTS=new JsonArray(), SAMPLES=new JsonArray(), CASES=new JsonArray();
    private static final AtomicBoolean CAPTURED=new AtomicBoolean(true);
    private static final Vec3 RECEIVER=new Vec3(-56.5,89.01,64.5), CONTROL=new Vec3(-50.5,89.01,64.5);
    private static MarineRenderer.Registration observer;
    private static ShaderSettings saved;
    private static Vec3 savedPosition;
    private static float savedYaw,savedPitch;
    private static boolean savedEnabled;
    private static volatile GameType savedMode;
    private static int stage,ticks,eventNumber,inFlight,screenshotFrames;
    private static long lastFrame,started,legacyGeneration,lastBoundsRequest,lastReady,gapStarted;
    private static Object legacyScene;
    private static JsonObject latestMetrics,activeEvent;
    private static JsonObject latestSample;
    private static SceneBounds lastRequested;
    private static int sectionCrossings,boundsChanges,movementSamples,movementFailures;
    private static double maxReadyGap;
    private static boolean finishing;
    private static Path output;
    private DynamicRaySceneProbe() {}

    public static void camera(GameRenderer renderer) {
        var state=renderer.gameRenderState();
        state.levelRenderState.skyRenderState.sunAngle=0;
        state.levelRenderState.skyRenderState.rainBrightness=1;
        var entity=state.levelRenderState.cameraRenderState.entityRenderState;
        entity.isPlayer=true;entity.bob=0;state.optionsRenderState.bobView=false;
    }
    static void tick(Minecraft client,Path folder) {
        output=folder;
        try {
            if(client.player==null || client.level==null)return;
            if(stage==99)return;
            if(saved==null) {
                started=System.nanoTime();saved=ShaderRuntime.settings();savedEnabled=GreenMarineClient.enabled();
                savedPosition=client.player.position();savedYaw=client.player.getYRot();savedPitch=client.player.getXRot();
                saved.save(output.resolve("original-settings.json"));
                Files.writeString(output.resolve("original-pose.json"),GSON.toJson(Map.of("x",savedPosition.x,"y",savedPosition.y,"z",savedPosition.z,"yaw",savedYaw,"pitch",savedPitch)));
                var server=client.getSingleplayerServer();if(server==null)throw new IllegalStateException("Singleplayer fixture required");
                var uuid=client.player.getUUID();server.execute(()->{var player=server.getPlayerList().getPlayer(uuid);savedMode=player.gameMode();player.setGameMode(GameType.SPECTATOR);});
                if(!savedEnabled)GreenMarineClient.toggleEffect();stage=1;return;
            }
            if(savedMode==null || !client.player.isSpectator())return;
            client.player.setDeltaMovement(Vec3.ZERO);
            double x=stage==20?-65.5+Math.min(ticks,180)*.1:stage>=21?-47.5:-56.5;
            client.player.setPos(x,95,80.5);client.player.setYRot(180);client.player.setYHeadRot(180);client.player.setXRot(25);
            ticks++;
            if(stage<90 && ShaderRuntime.status().state()==PackStatus.State.FAILED)throw new IllegalStateException(ShaderRuntime.status().detail());
            if(System.nanoTime()-started>240_000_000_000L)throw new IllegalStateException("Dynamic fixture timed out at stage "+stage);
            switch(stage) {
                case 1 -> {
                    if(ticks<40)return;
                    for(int bx=-66;bx<=-44;bx++)for(int bz=54;bz<=76;bz++) {
                        set(client,new BlockPos(bx,88,bz),Blocks.SMOOTH_STONE.defaultBlockState());
                        for(int by=89;by<=100;by++)set(client,new BlockPos(bx,by,bz),Blocks.AIR.defaultBlockState());
                    }
                    ShaderRuntime.apply(new ShaderSettings("greenmarine:solstice_rt",Quality.ULTRA,RayMode.HARDWARE,true));
                    ShaderRuntime.previewParameter("giStrength",0);ShaderRuntime.previewParameter("bloom",0);
                    observer=MarineRenderer.register("probe:dynamic_rays",MarineRenderer.Stage.AFTER_PACKS,DynamicRaySceneProbe::observer);
                    stage=2;ticks=0;
                }
                case 2 -> {
                    if(ticks<30 || latestSample==null || RayWorld.surfaceFrames()<40)return;
                    if(!goodSample(latestSample,true))return;
                    RESULT.add("capabilities",GSON.toJsonTree(ShaderRuntime.capabilities()));RESULT.add("packStatus",GSON.toJsonTree(ShaderRuntime.status()));
                    RESULT.addProperty("metricsMode",latestMetrics.has("mode")?latestMetrics.get("mode").getAsString():"dynamic");
                    capture(client,"warm-unblocked.png");stage=3;ticks=0;
                }
                case 3 -> {if(!CAPTURED.get())return;beginEdit(client,true);stage=4;ticks=0;}
                case 4 -> {
                    if(activeEvent==null || !activeEvent.has("gpuObservedNanos")) {if(ticks>600)throw new IllegalStateException("Occluder add did not alter GPU sunlight");return;}
                    if(System.nanoTime()-activeEvent.get("gpuObservedNanos").getAsLong()<240_000_000L)return;
                    if(!CAPTURED.get())return;capture(client,"cycle-"+(eventNumber/2+1)+"-blocked.png");stage=5;ticks=0;
                }
                case 5 -> {if(!CAPTURED.get() || ticks<10)return;beginEdit(client,false);stage=6;ticks=0;}
                case 6 -> {
                    if(activeEvent==null || !activeEvent.has("gpuObservedNanos")) {if(ticks>600)throw new IllegalStateException("Occluder undo did not restore GPU sunlight");return;}
                    if(System.nanoTime()-activeEvent.get("gpuObservedNanos").getAsLong()<240_000_000L)return;
                    if(!CAPTURED.get())return;capture(client,"cycle-"+(eventNumber/2)+"-restored.png");stage=7;ticks=0;
                }
                case 7 -> {if(!CAPTURED.get() || ticks<10)return;if(eventNumber<4){stage=3;ticks=0;}else{beginEdit(client,true);stage=18;ticks=0;}}
                case 18 -> {if(activeEvent==null || !activeEvent.has("gpuObservedNanos")){if(ticks>600)throw new IllegalStateException("Walking occluder was not published");return;}if(System.nanoTime()-activeEvent.get("gpuObservedNanos").getAsLong()<240_000_000L)return;capture(client,"movement-start.png");stage=19;ticks=0;}
                case 19 -> {if(ticks<20 || !CAPTURED.get())return;stage=20;ticks=0;lastRequested=null;lastReady=System.nanoTime();}
                case 20 -> {if(ticks<180)return;stage=21;ticks=0;}
                case 21 -> {
                    if(ticks<20 || latestMetrics==null)return;
                    var expected=SceneBounds.around(-48,96,80);
                    boolean matched=metricsBounds(latestMetrics).equals(expected);
                    if(!matched){if(ticks>600)throw new IllegalStateException("Native bounds did not catch up after walking");return;}
                    MOVEMENT.addProperty("sectionCrossings",sectionCrossings);MOVEMENT.addProperty("requestedBoundsChanges",boundsChanges);
                    MOVEMENT.addProperty("finalBoundsMatched",true);MOVEMENT.addProperty("maxReadyGapMs",maxReadyGap);
                    MOVEMENT.addProperty("gpuSamples",movementSamples);MOVEMENT.addProperty("gpuFailures",movementFailures);
                    capture(client,"movement-end.png");RESULT.addProperty("surfaceRayFrames",RayWorld.surfaceFrames());RESULT.add("finalMetrics",latestMetrics.deepCopy());
                    RESULT.add("surfaceCpu",GSON.toJsonTree(RayWorld.surfaceCpuTiming()));RESULT.add("surfaceGpu",GSON.toJsonTree(RayWorld.surfaceGpuTiming()));
                    RESULT.add("worldGpu",GSON.toJsonTree(com.koper.greenmarine.client.world.WorldPrograms.mainGpu()));
                    RESULT.add("opaqueCopyCpu",GSON.toJsonTree(com.koper.greenmarine.client.world.WorldPrograms.copyCpu()));
                    finishing=true;ticks=0;
                    if(RESULT.get("metricsMode").getAsString().equals("dynamic"))stage=22;
                    else{RESULT.addProperty("status","complete");stage=90;}
                }
                case 22 -> {
                    if(!CAPTURED.get() || inFlight>0)return;
                    if(!ProbeSubmissionFault.armed() && !ProbeSubmissionFault.triggered())ProbeSubmissionFault.arm();
                    if(!ProbeSubmissionFault.triggered() || !RayWorld.surfaceUnavailable()) {if(ticks>100)throw new IllegalStateException("Post-submit fault did not reach surface fallback");return;}
                    if(ShaderRuntime.status().state()!=PackStatus.State.FALLBACK)throw new IllegalStateException("Post-submit failure did not report fallback");
                    RESULT.addProperty("submissionFaultTriggered",true);RESULT.add("submissionFaultStatus",GSON.toJsonTree(ShaderRuntime.status()));
                    ShaderRuntime.reset();stage=23;ticks=0;
                }
                case 23 -> {
                    if(!RayWorld.surfaceReady() || RayWorld.surfaceFrames()<20 || ShaderRuntime.status().state()!=PackStatus.State.LOADED) {
                        if(ticks>600)throw new IllegalStateException("Surface native path did not recover after actual post-submit exception");return;
                    }
                    RESULT.addProperty("submissionFaultRecovered",true);RESULT.add("submissionRecoveredMetrics",latestMetrics.deepCopy());
                    RESULT.addProperty("status","complete");stage=90;ticks=0;
                }
                case 90 -> {if(!CAPTURED.get() || inFlight>0)return;finish(client);}
            }
        } catch(Throwable error) {abort(client,folder,error);}
    }
    private static java.util.function.Consumer<FrameContext> observer(RenderSession session) {
        var vertex=Identifier.parse("probe:dynamic_sample_vertex");var fragment=Identifier.parse("probe:dynamic_sample_fragment");
        String header="#version 330\n#extension GL_ARB_separate_shader_objects : require\n";
        String vs=header+"void main(){vec2 uv=vec2((gl_VertexIndex<<1)&2,gl_VertexIndex&2);gl_Position=vec4(uv*2-1,0,1);}";
        String fs=header+"layout(std140)uniform ProbeSample{vec4 SampleUV;};uniform sampler2D SurfaceLight;uniform sampler2D SurfaceNormal;uniform sampler2D SceneColor;layout(location=0)out vec4 color;void main(){vec2 uv=(int(gl_FragCoord.x)%2)==0?SampleUV.xy:SampleUV.zw;vec2 pixel=1.0/vec2(textureSize(SurfaceLight,0));vec4 sum=vec4(0),scene=vec4(0);float normalY=0;for(int y=-1;y<=1;y++)for(int x=-1;x<=1;x++){vec2 p=uv+vec2(x,y)*pixel*.45;sum+=texture(SurfaceLight,p);normalY+=texture(SurfaceNormal,p).y;scene+=texture(SceneColor,p);}sum/=9;color=gl_FragCoord.x<2?vec4(sum.a,dot(sum.rgb,vec3(.2126,.7152,.0722)),normalY/9,1):scene/9;}";
        var pipeline=RenderPipeline.builder().withLocation(fragment).withVertexShader(vertex).withFragmentShader(fragment)
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("ProbeSample",UniformType.UNIFORM_BUFFER).withUniform("SurfaceLight",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("SurfaceNormal",UniformType.COMBINED_IMAGE_SAMPLER).withUniform("SceneColor",UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withColorTargetState(new ColorTargetState(Optional.empty(),GpuFormat.RGBA32_FLOAT,ColorTargetState.WRITE_ALL)).withPrimitiveTopology(PrimitiveTopology.TRIANGLES).withCull(false).build();
        var program=session.compile(new ShaderProgram(pipeline,Map.of(vertex,vs,fragment,fs),Map.of(),Map.of()));
        var target=session.texture("Dynamic ray GPU sample",TextureSpec.fixed(GpuFormat.RGBA32_FLOAT,4,1),4,1);
        var buffers=new BufferHandle[3];var busy=new boolean[3];
        for(int i=0;i<3;i++)buffers[i]=session.buffer("Dynamic ray readback "+i,GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,ByteBuffer.allocateDirect(64));
        var sampleUniform=ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
        var graph=session.graph(RenderGraph.builder().importTexture("surfaceLighting").importTexture("surfaceNormalDepth").importTexture("sceneColor").importTexture("sampleOutput")
            .pass(new RenderGraph.Pass("sample_native_sun",Set.of("surfaceLighting","surfaceNormalDepth","sceneColor"),Set.of("sampleOutput"),Set.of(),context->{
                var material=context.material(program).uniform("ProbeSample",sampleUniform).texture("SurfaceLight","surfaceLighting",FilterMode.NEAREST).texture("SurfaceNormal","surfaceNormalDepth",FilterMode.NEAREST).texture("SceneColor","sceneColor",FilterMode.NEAREST);
                context.draw(material,List.of("sampleOutput"),null,null,false);
            })).build());
        return frame->{
            try {
                long now=System.nanoTime();JsonObject metrics=metrics();latestMetrics=metrics;
                var row=new JsonObject();row.addProperty("nanos",now);row.addProperty("phase",stage);row.addProperty("event",eventNumber);
                row.addProperty("deltaMs",lastFrame==0?0:(now-lastFrame)/1e6);lastFrame=now;
                row.addProperty("instrumentation",!CAPTURED.get() || screenshotFrames>0 || stage>=22);if(screenshotFrames>0)screenshotFrames--;
                row.addProperty("surfaceReady",frame.surfaceRaysReady());row.add("metrics",metrics);FRAMES.add(row);
                if(stage==20 || stage==21) {
                    var camera=Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
                    var requested=SceneBounds.around((int)Math.floor(camera.x),(int)Math.floor(camera.y),(int)Math.floor(camera.z));
                    if(lastRequested==null || !lastRequested.equals(requested)) {
                        if(lastRequested!=null)sectionCrossings++;boundsChanges++;lastRequested=requested;lastBoundsRequest=now;
                    }
                    boolean current=metricsBounds(metrics).equals(requested) && frame.surfaceRaysReady();
                    row.addProperty("boundsMatched",current);row.add("requestedBounds",GSON.toJsonTree(requested));
                    if(current){if(gapStarted>0){maxReadyGap=Math.max(maxReadyGap,(now-gapStarted)/1e6);gapStarted=0;}lastReady=now;}
                    else if(gapStarted==0)gapStarted=now;
                    if(gapStarted>0)maxReadyGap=Math.max(maxReadyGap,(now-gapStarted)/1e6);
                }
                observePublication(metrics,now,frame.surfaceRaysReady());
                if(finishing || !frame.surfaceRaysReady() || !frame.surfacesReady() )return;
                Vector4f receiver=project(frame,RECEIVER),control=project(frame,CONTROL);
                if(receiver.w<=0 || control.w<=0)return;
                sampleUniform.putFloat(0,receiver.x/receiver.w*.5f+.5f).putFloat(4,receiver.y/receiver.w*.5f+.5f)
                    .putFloat(8,control.x/control.w*.5f+.5f).putFloat(12,control.y/control.w*.5f+.5f).position(0).limit(16);
                int slot=-1;for(int i=0;i<busy.length;i++)if(!busy[i]){slot=i;break;}if(slot<0)return;
                graph.execute(frame,Map.of("sampleOutput",target));
                int index=slot;busy[index]=true;inFlight++;int sampleEvent=eventNumber;int sampleStage=stage;JsonObject sampleMetrics=metrics.deepCopy();
                var encoder=session.device().createCommandEncoder();
                encoder.copyTextureToBuffer(target.texture(),buffers[index].buffer(),0,()->{
                    try(var mapped=buffers[index].buffer().map(true,false)) {
                        var values=mapped.data().order(ByteOrder.nativeOrder());var sample=new JsonObject();
                        sample.addProperty("frameNanos",now);sample.addProperty("observedNanos",System.nanoTime());sample.addProperty("event",sampleEvent);sample.addProperty("phase",sampleStage);
                        sample.addProperty("receiverVisibility",values.getFloat(0));sample.addProperty("receiverIrradiance",values.getFloat(4));sample.addProperty("receiverNormalY",values.getFloat(8));
                        sample.addProperty("controlVisibility",values.getFloat(16));sample.addProperty("controlIrradiance",values.getFloat(20));sample.addProperty("controlNormalY",values.getFloat(24));
                        sample.addProperty("receiverColor",values.getFloat(32)*.2126+values.getFloat(36)*.7152+values.getFloat(40)*.0722);
                        sample.addProperty("controlColor",values.getFloat(48)*.2126+values.getFloat(52)*.7152+values.getFloat(56)*.0722);
                        sample.add("metrics",sampleMetrics);SAMPLES.add(sample);latestSample=sample;observeGpu(sample);
                        if(sampleStage==20 || sampleStage==21){movementSamples++;if(!goodSample(sample,false))movementFailures++;}
                    }catch(Throwable error){Minecraft.getInstance().execute(()->abort(Minecraft.getInstance(),output,error));}
                    finally{busy[index]=false;inFlight--;}
                },0);encoder.submit();
            }catch(Throwable error){Minecraft.getInstance().execute(()->abort(Minecraft.getInstance(),output,error));}
        };
    }
    private static Vector4f project(FrameContext frame,Vec3 point) {return frame.projection().mul(frame.view()).mul(frame.modelAt(point.x,point.y,point.z)).transform(new Vector4f(0,0,0,1));}
    private static void beginEdit(Minecraft client,boolean add) {
        activeEvent=new JsonObject();activeEvent.addProperty("cycle",eventNumber/2+1);activeEvent.addProperty("kind",add?"add":"undo");
        activeEvent.addProperty("requestedNanos",System.nanoTime());activeEvent.addProperty("initialGeneration",number(latestMetrics,"generation"));
        activeEvent.addProperty("beforeVisibility",latestSample.get("receiverVisibility").getAsFloat());activeEvent.add("metricsBefore",latestMetrics.deepCopy());
        for(int x=-58;x<=-55;x++)for(int z=62;z<=65;z++)set(client,new BlockPos(x,93,z),(add?Blocks.STONE:Blocks.AIR).defaultBlockState());
        activeEvent.addProperty("editFinishedNanos",System.nanoTime());EVENTS.add(activeEvent);eventNumber++;
    }
    private static void observePublication(JsonObject metrics,long now,boolean ready) {
        if(activeEvent==null || activeEvent.has("gpuObservedNanos"))return;
        if(number(metrics,"generation")>activeEvent.get("initialGeneration").getAsLong()) {
            if(!activeEvent.has("nativePublishedNanos"))activeEvent.addProperty("nativePublishedNanos",Math.max(activeEvent.get("requestedNanos").getAsLong(),number(metrics,"lastPublishNanos")>0?number(metrics,"lastPublishNanos"):now));
            if(ready && !activeEvent.has("firstSubmittedNanos"))activeEvent.addProperty("firstSubmittedNanos",now);
        }
    }
    private static boolean goodSample(JsonObject sample,boolean clear) {
        float receiver=sample.get("receiverVisibility").getAsFloat(),control=sample.get("controlVisibility").getAsFloat();
        return sample.get("receiverNormalY").getAsFloat()>.8 && sample.get("controlNormalY").getAsFloat()>.8 && control>.75 && (clear?receiver>.75:receiver<.3);
    }
    private static void observeGpu(JsonObject sample) {
        if(activeEvent==null || activeEvent.has("gpuObservedNanos") || sample.get("event").getAsInt()!=eventNumber
            || sample.get("frameNanos").getAsLong()<activeEvent.get("requestedNanos").getAsLong())return;
        if(!goodSample(sample,activeEvent.get("kind").getAsString().equals("undo")))return;
        if(!activeEvent.has("nativePublishedNanos") || !activeEvent.has("firstSubmittedNanos"))return;
        activeEvent.addProperty("gpuChangedFrameNanos",sample.get("frameNanos").getAsLong());activeEvent.addProperty("gpuObservedNanos",sample.get("observedNanos").getAsLong());
        activeEvent.addProperty("afterVisibility",sample.get("receiverVisibility").getAsFloat());activeEvent.addProperty("controlVisibility",sample.get("controlVisibility").getAsFloat());
        activeEvent.addProperty("receiverNormalY",sample.get("receiverNormalY").getAsFloat());activeEvent.add("metricsAfter",sample.get("metrics"));
    }
    private static long number(JsonObject metrics,String field) {return metrics!=null && metrics.has(field)?metrics.get(field).getAsLong():0;}
    private static JsonObject metrics() throws Exception {
        try {var value=GSON.toJsonTree(RayWorld.class.getMethod("dynamicMetrics").invoke(null)).getAsJsonObject();value.addProperty("mode","dynamic");return value;}
        catch(NoSuchMethodException missing) {
            var field=RayWorld.class.getDeclaredField("scene");field.setAccessible(true);Object current=field.get(null);
            if(current!=legacyScene){legacyScene=current;if(current!=null)legacyGeneration++;}
            var metrics=new JsonObject();metrics.addProperty("mode","legacy_scene_identity");metrics.addProperty("generation",legacyGeneration);metrics.addProperty("submittedGeneration",legacyGeneration);
            metrics.addProperty("scanNanos",RayWorld.scanNanos());metrics.addProperty("publishNanos",RayWorld.buildNanos());
            if(current instanceof TriangleScene scene){metrics.addProperty("x",scene.x());metrics.addProperty("y",scene.y());metrics.addProperty("z",scene.z());}
            return metrics;
        }
    }
    private static SceneBounds metricsBounds(JsonObject metrics) {return new SceneBounds((int)number(metrics,"x"),(int)number(metrics,"y"),(int)number(metrics,"z"));}
    private static void set(Minecraft client,BlockPos position,BlockState state) {BLOCKS.putIfAbsent(position,client.level.getBlockState(position));client.level.setBlock(position,state,3);}
    private static void capture(Minecraft client,String name) {
        CAPTURED.set(false);screenshotFrames=3;CASES.add(name);
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{try{image.writeToFile(output.resolve(name));}catch(Throwable error){client.execute(()->abort(client,output,error));}finally{image.close();CAPTURED.set(true);screenshotFrames=3;}});
    }
    static void abort(Minecraft client,Path folder,Throwable error) {
        if(stage==99)return;output=folder;RESULT.addProperty("status","failed");RESULT.addProperty("error",error.toString());
        GreenMarine.LOGGER.error("Dynamic ray scene probe failed at stage "+stage,error);finishing=true;
        try{finish(client);}catch(Throwable cleanup){GreenMarine.LOGGER.error("Dynamic probe restoration failed",cleanup);client.stop();}
    }
    private static void finish(Minecraft client) throws Exception {
        if(stage==99)return;stage=99;finishing=true;
        try {
            if(observer!=null){observer.close();observer=null;}
            if(client.level!=null)for(var entry:BLOCKS.entrySet())client.level.setBlock(entry.getKey(),entry.getValue(),3);
        } finally {
            BLOCKS.clear();if(saved!=null)ShaderRuntime.apply(saved);if(GreenMarineClient.enabled()!=savedEnabled)GreenMarineClient.toggleEffect();
            RESULT.add("frames",FRAMES);RESULT.add("gpuSamples",SAMPLES);RESULT.add("events",EVENTS);RESULT.add("movement",MOVEMENT);RESULT.add("sourceCases",CASES);
            RESULT.addProperty("restored",true);Files.writeString(output.resolve("result.json"),GSON.toJson(RESULT)+"\n");
            if(savedMode!=null && client.player!=null) {
                var server=client.getSingleplayerServer();var uuid=client.player.getUUID();
                server.execute(()->{var player=server.getPlayerList().getPlayer(uuid);if(player!=null){player.setGameMode(savedMode);player.teleportTo(savedPosition.x,savedPosition.y,savedPosition.z);player.setYRot(savedYaw);player.setXRot(savedPitch);}client.execute(()->{client.disconnectWithSavingScreen();client.stop();});});
            }else client.stop();
        }
    }
}
