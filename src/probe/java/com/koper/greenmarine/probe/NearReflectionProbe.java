package com.koper.greenmarine.probe;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.client.*;
import com.koper.greenmarine.client.rt.RayWorld;
import com.koper.greenmarine.api.pack.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Solstice surface RT while the camera approaches an iron wall: plain and diagnostic-3 captures per distance. */
public final class NearReflectionProbe {
    private static final double[] DISTANCES={3.0,1.5,0.8,0.45,0.31};
    private static final float[] PITCHES={0,35};
    private static int stage,ticks,shot;
    private static ShaderSettings saved;
    private static boolean enabled;
    private static BlockPos base;
    private static final AtomicBoolean captured=new AtomicBoolean(true);
    private static final com.google.gson.JsonObject result=new com.google.gson.JsonObject();
    static void tick(Minecraft client,Path output) {
        try {
            if(client.player==null)return;
            client.player.setDeltaMovement(Vec3.ZERO);
            if(saved==null) {
                saved=ShaderRuntime.settings();enabled=GreenMarineClient.enabled();
                base=client.player.blockPosition().atY(120);
                var server=client.getSingleplayerServer();var uuid=client.player.getUUID();var origin=base;
                server.execute(()->{
                    var player=server.getPlayerList().getPlayer(uuid);player.setGameMode(GameType.CREATIVE);
                    var level=player.level();
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"time set noon");
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"gamerule doDaylightCycle false");
                    for(int x=-6;x<=8;x++)for(int z=-6;z<=6;z++)for(int y=-1;y<=6;y++)
                        level.setBlockAndUpdate(origin.offset(x,y,z),y==-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
                    // Iron wall at +X with a roof stub casting a shadow onto the floor and the wall.
                    for(int z=-3;z<=3;z++)for(int y=0;y<=4;y++)level.setBlockAndUpdate(origin.offset(4,y,z),Blocks.IRON_BLOCK.defaultBlockState());
                    for(int z=-1;z<=1;z++)level.setBlockAndUpdate(origin.offset(2,3,z),Blocks.OAK_PLANKS.defaultBlockState());
                    level.setBlockAndUpdate(origin.offset(1,0,2),Blocks.CAMPFIRE.defaultBlockState());
                });
                client.player.getAbilities().flying=true;
                if(!enabled)GreenMarineClient.toggleEffect();
                ShaderRuntime.apply(new ShaderSettings("greenmarine:solstice_rt",Quality.HIGH,RayMode.HARDWARE,true,saved.parameters()));
                stage=1;ticks=0;return;
            }
            ticks++;
            if(ShaderRuntime.status().state()==PackStatus.State.FAILED)throw new IllegalStateException(ShaderRuntime.status().detail());
            int view=shot/2;
            double distance=DISTANCES[Math.min(view/PITCHES.length,DISTANCES.length-1)];
            float pitch=PITCHES[view%PITCHES.length];
            // Eye height 1.62 above feet, centred on the wall face at x=4.
            client.player.setPos(base.getX()+4-distance,base.getY()+.5,base.getZ()+.5);
            client.player.setYRot(-90);client.player.setYHeadRot(-90);client.player.setXRot(pitch);
            switch(stage) {
                case 1 -> {
                    if(ticks<200 || !ShaderRuntime.status().loaded() || !RayWorld.surfaceReady() || RayWorld.surfaceFrames()<120) {
                        if(ticks>2400)throw new IllegalStateException("Surface RT not ready: "+RayWorld.surfaceDetail());return;
                    }
                    result.addProperty("surfaceDetail",RayWorld.surfaceDetail());stage=2;ticks=0;
                }
                case 2 -> {
                    if(!captured.get() || ticks<30)return;
                    if(shot>=DISTANCES.length*PITCHES.length*2) {result.addProperty("status","complete");finish(client,output);return;}
                    boolean diagnostic=shot%2==1;
                    ShaderRuntime.previewParameter("surfaceDiagnostic",diagnostic?3:0);
                    stage=3;ticks=0;
                }
                case 3 -> {
                    if(ticks<20)return;
                    capture(client,output,String.format(java.util.Locale.ROOT,"near-d%.2f-p%.0f-%s.png",distance,pitch,shot%2==1?"diag":"plain"));
                    shot++;stage=2;ticks=0;
                }
            }
        } catch(Throwable error) {abort(client,output,error);}
    }
    private static void capture(Minecraft client,Path output,String name) {
        captured.set(false);Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
            try {image.writeToFile(output.resolve(name));captured.set(true);}
            catch(Exception error) {client.execute(()->abort(client,output,error));}finally {image.close();}
        });
    }
    private static void finish(Minecraft client,Path output) {
        if(stage==99)return;stage=99;
        if(saved!=null)ShaderRuntime.apply(saved);
        try {Files.writeString(output.resolve("near-result.json"),result+"\n");}catch(Exception error){GreenMarine.LOGGER.error("Unable to save near reflection result",error);}
        client.execute(()->{client.disconnectWithSavingScreen();client.stop();});
    }
    static void abort(Minecraft client,Path output,Throwable error) {
        GreenMarine.LOGGER.error("Near reflection probe failed at stage "+stage,error);
        result.addProperty("status","failed");result.addProperty("error",error.toString());finish(client,output);
    }
}
