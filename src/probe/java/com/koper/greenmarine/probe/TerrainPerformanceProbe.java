package com.koper.greenmarine.probe;

import com.google.gson.Gson;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.api.render.*;
import com.koper.greenmarine.client.*;
import com.koper.greenmarine.client.rt.*;
import com.koper.greenmarine.client.world.WorldPrograms;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.*;
import jdk.jfr.Recording;
import jdk.jfr.Configuration;

/** Natural terrain route: no fabricated empty sections or edited lighting fixtures. */
public final class TerrainPerformanceProbe {
    private static final Gson JSON=new Gson();
    private static final List<Map<String,Object>> PHASES=new ArrayList<>(),ROWS=new ArrayList<>();
    private static final String[] NAMES={"hardware-still","hardware-moving","raster-still","raster-moving","disabled-still"};
    private static final int[] DURATIONS={8,16,8,16,8};
    private static MarineRenderer.Registration observer;
    private static Recording recording;
    private static Path output;
    private static int phase=-1,ticks;
    private static boolean initialized,finished;
    private static long phaseStart,lastFrame,priorScan;
    private static Vec3 origin;
    private static RayWorld.DynamicMetrics phaseMetrics;

    public static void tick(Minecraft client,Path folder) {
        output=folder;
        if(finished)return;
        try {
            if(!initialized) {
                initialized=true;origin=new Vec3(-57.5,78,68.5);
                client.options.framerateLimit().set(net.minecraft.client.Options.UNLIMITED_FRAMERATE_CUTOFF);
                client.options.enableVsync().set(false);
                var uuid=client.player.getUUID();
                client.getSingleplayerServer().execute(()->{
                    var player=client.getSingleplayerServer().getPlayerList().getPlayer(uuid);
                    player.setGameMode(GameType.SPECTATOR);player.teleportTo(origin.x,origin.y,origin.z);
                });
                if(!GreenMarineClient.enabled())GreenMarineClient.toggleEffect();
                ShaderRuntime.apply(new ShaderSettings("greenmarine:solstice_rt",Quality.ULTRA,RayMode.HARDWARE,true));
                recording=new Recording(Configuration.getConfiguration("profile"));recording.start();
                observer=MarineRenderer.register("probe:terrain_performance",MarineRenderer.Stage.AFTER_PACKS,session->TerrainPerformanceProbe::frame);
            }
            client.getFramerateLimitTracker().onInputReceived();
            ticks++;
            if(!client.player.isSpectator())return;
            if(phase<0) {
                var window=client.getWindow();var main=client.gameRenderer.mainRenderTarget();
                if(main.width!=1200 || main.height!=675) {
                    double sx=window.getWidth()/(double)window.getScreenWidth(),sy=window.getHeight()/(double)window.getScreenHeight();
                    window.setWindowed((int)Math.round(1200/sx),(int)Math.round(675/sy));
                    return;
                }
                move(client,0);
                if(ticks>100 && RayWorld.surfaceFrames()>80 && RayWorld.dynamicMetrics().pendingSections()==0)begin(client,0);
                return;
            }
            double elapsed=(System.nanoTime()-phaseStart)/1e9;
            move(client,(phase==1 || phase==3)?Math.min(1,elapsed/16.0):0);
            if(elapsed>=DURATIONS[phase]) {
                finishPhase(client);
                if(phase+1==NAMES.length)finish(client,null);else begin(client,phase+1);
            }
        } catch(Throwable error) {abort(client,folder,error);}
    }
    private static void move(Minecraft client,double progress) {
        client.player.setDeltaMovement(Vec3.ZERO);
        client.player.setPos(origin.x+progress*96,origin.y,origin.z+Math.sin(progress*Math.PI)*24);
        client.player.setYRot(180);client.player.setYHeadRot(180);client.player.setXRot(18);
    }
    private static void begin(Minecraft client,int next) {
        phase=next;
        if(next==2)ShaderRuntime.apply(new ShaderSettings("greenmarine:solstice_rt",Quality.ULTRA,RayMode.OFF,true));
        if(next==4)GreenMarineClient.toggleEffect();
        phaseStart=System.nanoTime();lastFrame=0;phaseMetrics=RayWorld.dynamicMetrics();priorScan=phaseMetrics.scanner().scanNanos();
        GreenMarine.LOGGER.info("Terrain performance phase: {}",NAMES[phase]);
    }
    private static void frame(FrameContext context) {
        if(phase<0 || finished)return;
        long now=System.nanoTime();
        if(lastFrame!=0 && now-phaseStart>1_000_000_000L) {
            var row=new LinkedHashMap<String,Object>();
            row.put("phase",NAMES[phase]);row.put("nanos",now);row.put("intervalMs",(now-lastFrame)/1e6);
            row.put("surfaceReady",context.surfaceRaysReady());
            // Sample expensive native diagnostics at most once per 16 frames.
            if((ROWS.size()&15)==0) {
                var m=RayWorld.dynamicMetrics();row.put("metrics",m);
                row.put("scanSinceSampleMs",(m.scanner().scanNanos()-priorScan)/1e6);priorScan=m.scanner().scanNanos();
                var camera=Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
                row.put("camera",camera);row.put("requestedBounds",SceneBounds.around((int)Math.floor(camera.x),(int)Math.floor(camera.y),(int)Math.floor(camera.z)));
            }
            ROWS.add(row);
        }
        lastFrame=now;
    }
    private static void finishPhase(Minecraft client) {
        var result=new LinkedHashMap<String,Object>();result.put("name",NAMES[phase]);
        var sky=client.gameRenderer.gameRenderState().levelRenderState.skyRenderState;
        result.put("sunAngle",sky.sunAngle);result.put("rainBrightness",sky.rainBrightness);result.put("rainLevel",client.level.getRainLevel(0));
        result.put("width",client.gameRenderer.mainRenderTarget().width);result.put("height",client.gameRenderer.mainRenderTarget().height);
        result.put("framerateSetting",client.options.framerateLimit().get());result.put("effectiveFramerateLimit",client.getFramerateLimitTracker().getFramerateLimit());
        result.put("throttleReason",client.getFramerateLimitTracker().getThrottleReason());result.put("vsync",client.options.enableVsync().get());
        result.put("startMetrics",phaseMetrics);result.put("endMetrics",RayWorld.dynamicMetrics());result.put("status",ShaderRuntime.status());
        result.put("worldGpu",WorldPrograms.mainGpu());result.put("surfaceCpu",RayWorld.surfaceCpuTiming());result.put("surfaceGpu",RayWorld.surfaceGpuTiming());
        result.put("composeCpu",PackRenderer.cpuTiming());result.put("composeGpu",PackRenderer.gpuTiming());PHASES.add(result);
    }
    public static void abort(Minecraft client,Path folder,Throwable error) {
        output=folder;GreenMarine.LOGGER.error("Terrain performance probe failed",error);finish(client,error);
    }
    private static void finish(Minecraft client,Throwable error) {
        if(finished)return;finished=true;
        try {
            if(observer!=null)observer.close();
            if(recording!=null){recording.stop();recording.dump(output.resolve("profile.jfr"));recording.close();}
            var result=new LinkedHashMap<String,Object>();result.put("status",error==null?"complete":"failed");
            if(error!=null)result.put("error",error.toString());
            result.put("phases",PHASES);result.put("frames",ROWS);
            Files.writeString(output.resolve("result.json"),JSON.toJson(result)+"\n");
        } catch(Exception failure){GreenMarine.LOGGER.error("Performance evidence save failed",failure);}
        client.disconnectWithSavingScreen();client.stop();
    }
}
