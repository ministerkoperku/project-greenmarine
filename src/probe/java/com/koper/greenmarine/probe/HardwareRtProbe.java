package com.koper.greenmarine.probe;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.client.*;
import com.koper.greenmarine.client.rt.RayWorld;
import com.koper.greenmarine.api.pack.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual ray dispatch, moving camera alignment, odd resize, reload and fallback verification. */
public final class HardwareRtProbe {
    public static boolean bobbing;
    private static int stage,ticks;
    private static ShaderSettings saved;
    private static AutoCloseable failurePack;
    private static boolean enabled;
    private static volatile GameType mode;
    private static Vec3 position;
    private static float yaw,pitch;
    private static final AtomicBoolean captured=new AtomicBoolean();
    private static final com.google.gson.JsonObject result=new com.google.gson.JsonObject();
    static boolean expectsShaderFailure() {return stage==18;}
    static void tick(Minecraft client,Path output) {
        try {
            if(client.player!=null) client.player.setDeltaMovement(Vec3.ZERO);
            if(saved==null) {
                saved=ShaderRuntime.settings();enabled=GreenMarineClient.enabled();
                position=client.player.position();yaw=client.player.getYRot();pitch=client.player.getXRot();
                Files.writeString(output.resolve("original-settings.json"),new com.google.gson.Gson().toJson(saved));
                var server=client.getSingleplayerServer();var uuid=client.player.getUUID();
                server.execute(()->{var player=server.getPlayerList().getPlayer(uuid);mode=player.gameMode();player.setGameMode(GameType.SPECTATOR);});
                if(!enabled) GreenMarineClient.toggleEffect();
                select(RayMode.HARDWARE);stage=1;ticks=0;return;
            }
            if(mode==null || !client.player.isSpectator()) return;
            client.player.setPos(position.x+(stage==9?Math.min(ticks,120)*.17:stage>=10?20.4:0),position.y+(stage==16?144:0),position.z);
            client.player.setYRot(yaw);client.player.setYHeadRot(yaw);client.player.setXRot(pitch);
            ticks++;
            if(stage!=18 && ShaderRuntime.status().state()==PackStatus.State.FAILED) throw new IllegalStateException(ShaderRuntime.status().detail());
            switch(stage) {
                case 1 -> {
                    if(ticks<120 || !ShaderRuntime.status().loaded()) return;
                    if(RayWorld.available() && (!RayWorld.ready() || RayWorld.frames()<120)) {
                        if(ticks>1200) throw new IllegalStateException("Hardware trace not ready: "+RayWorld.detail());return;
                    }
                    result.add("capabilities",new com.google.gson.Gson().toJsonTree(ShaderRuntime.capabilities()));
                    result.add("packStatus",new com.google.gson.Gson().toJsonTree(ShaderRuntime.status()));
                    var publicCaps=com.koper.greenmarine.api.render.MarineRenderer.capabilities();
                    if(publicCaps.hardwareRayTracing()!=ShaderRuntime.capabilities().hardwareRayTracing()) throw new IllegalStateException("Public hardware capabilities differ from pack capabilities");
                    result.add("publicCapabilities",new com.google.gson.Gson().toJsonTree(publicCaps));
                    result.addProperty("triangles",RayWorld.triangles());result.addProperty("traceFrames",RayWorld.frames());
                    result.addProperty("scanNanos",RayWorld.scanNanos());result.addProperty("allocationNanos",RayWorld.buildNanos());
                    result.add("traceCpu",new com.google.gson.Gson().toJsonTree(RayWorld.cpuTiming()));result.add("traceGpu",new com.google.gson.Gson().toJsonTree(RayWorld.gpuTiming()));
                    capture(client,output,"rays.png");stage=2;
                }
                case 2 -> {if(!captured.get()) return;ShaderRuntime.previewParameter("diagnostic",3);stage=5;ticks=0;}
                case 5 -> {if(ticks<30) return;capture(client,output,"alignment.png");stage=6;}
                case 6 -> {if(!captured.get()) return;bobbing=true;stage=7;ticks=0;}
                case 7 -> {if(ticks<30) return;capture(client,output,"alignment-bobbing.png");stage=8;}
                case 8 -> {if(!captured.get()) return;stage=9;ticks=0;}
                case 9 -> {if(ticks<120) return;capture(client,output,"alignment-moving.png");stage=10;}
                case 10 -> {if(!captured.get()) return;ShaderRuntime.previewParameter("diagnostic",0);stage=11;ticks=0;}
                case 11 -> {if(ticks<40) return;capture(client,output,"rays-moving.png");stage=12;}
                case 12 -> {
                    if(!captured.get()) return;bobbing=false;captured.set(false);
                    client.getWindow().setWindowed(1000,750);
                    client.reloadResourcePacks().whenComplete((ignored,error)->{if(error!=null) client.execute(()->abort(client,output,error));else captured.set(true);});stage=13;ticks=0;
                }
                case 13 -> {
                    if(!captured.get() || ticks<120 || !ShaderRuntime.status().loaded() || (RayWorld.available() && RayWorld.frames()<40)) return;
                    if(RayWorld.ready()) {
                        int w=RayWorld.lighting().getWidth(0),h=RayWorld.lighting().getHeight(0);
                        result.addProperty("nativeWidth",w);result.addProperty("nativeHeight",h);
                        result.addProperty("oddNativeOutput",(w*h&1)==1);
                    }
                    capture(client,output,"resized.png");stage=14;
                }
                case 14 -> {if(!captured.get()) return;stage=16;ticks=0;}
                case 16 -> {
                    if(RayWorld.available()) {
                        if(RayWorld.triangles()!=0 || RayWorld.ready()) {if(ticks>400) throw new IllegalStateException("Empty scene retained stale geometry");return;}
                        if(ShaderRuntime.status().state()!=PackStatus.State.LOADING) throw new IllegalStateException("Empty hardware scene still reports Loaded");
                        result.addProperty("emptySceneCleared",true);
                    }
                    select(RayMode.OFF);stage=3;ticks=0;
                }
                case 3 -> {
                    if(ticks<60 || !ShaderRuntime.status().loaded()) return;
                    if(ShaderRuntime.status().state()!=PackStatus.State.FALLBACK || RayWorld.ready()) throw new IllegalStateException("Rays Off failed to report fallback");
                    result.add("offStatus",new com.google.gson.Gson().toJsonTree(ShaderRuntime.status()));capture(client,output,"rays-off.png");stage=4;
                }
                case 4 -> {
                    if(!captured.get()) return;
                    var pass=new PackPass("broken",PackPass.Stage.SCENE,"probe:broken",null,PackPass.Requirement.NONE,Quality.LOW,1,null);
                    var definition=new ShaderPack("probe:hardware_failure","RT probe shader failure",null,java.util.List.of(),java.util.List.of(pass));
                    failurePack=GreenMarine.PACKS.register(new PackCatalog.LoadedPack(definition,"function update(frame) end",java.util.Map.of("probe:broken","#version 330\ninvalid GLSL\n")));
                    ShaderRuntime.apply(new ShaderSettings(definition.id(),Quality.ULTRA,RayMode.OFF,false));stage=18;ticks=0;
                }
                case 18 -> {
                    if(ShaderRuntime.status().state()!=PackStatus.State.FAILED) {if(ticks>40) throw new IllegalStateException("Invalid shader did not report Failed");return;}
                    result.add("failureStatus",new com.google.gson.Gson().toJsonTree(ShaderRuntime.status()));
                    failurePack.close();failurePack=null;select(RayMode.OFF);stage=19;ticks=0;
                }
                case 19 -> {
                    if(ticks<40 || !ShaderRuntime.status().loaded()) return;
                    if(PackRenderer.failed() || PackRenderer.sceneFrames()==0) throw new IllegalStateException("Valid pack did not recover from shader failure");
                    result.addProperty("shaderFailureRecovered",true);result.addProperty("status","complete");finish(client,output);
                }
            }
        } catch(Throwable error) {abort(client,output,error);}
    }
    private static void select(RayMode rays) {ShaderRuntime.apply(new ShaderSettings("greenmarine:aurora_rt",Quality.ULTRA,rays,true,saved.parameters()));}
    private static void capture(Minecraft client,Path output,String name) {
        captured.set(false);Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
            try {image.writeToFile(output.resolve(name));captured.set(true);}
            catch(Exception error) {client.execute(()->abort(client,output,error));}finally {image.close();}
        });
    }
    private static void restore(Minecraft client) {
        if(failurePack!=null) {try {failurePack.close();} catch(Exception error){GreenMarine.LOGGER.error("Probe shader lease cleanup failed",error);}failurePack=null;}
        if(saved!=null && (ShaderRuntime.settings().pack().equals("greenmarine:aurora_rt") || ShaderRuntime.settings().pack().equals("probe:hardware_failure"))) ShaderRuntime.apply(saved);
        if(GreenMarineClient.enabled()!=enabled) GreenMarineClient.toggleEffect();bobbing=false;
    }
    private static void finish(Minecraft client,Path output) {
        if(stage==99) return;stage=99;restore(client);
        try {Files.writeString(output.resolve("result.json"),result+"\n");}catch(Exception error){GreenMarine.LOGGER.error("Unable to save RT probe result",error);}
        if(mode==null || client.player==null) {client.stop();return;}
        var server=client.getSingleplayerServer();var uuid=client.player.getUUID();
        server.execute(()->{var player=server.getPlayerList().getPlayer(uuid);if(player!=null){player.setGameMode(mode);player.teleportTo(position.x,position.y,position.z);player.setYRot(yaw);player.setXRot(pitch);}
            client.execute(()->{client.disconnectWithSavingScreen();client.stop();});});
    }
    static void abort(Minecraft client,Path output,Throwable error) {
        GreenMarine.LOGGER.error("Hardware RT probe failed at stage "+stage,error);
        result.addProperty("status","failed");result.addProperty("error",error.toString());finish(client,output);
    }
}
