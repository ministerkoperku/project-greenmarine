package com.koper.greenmarine.probe;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.client.*;
import com.koper.greenmarine.api.pack.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Vanilla/DH water boundary at noon and midnight; needs the DH jar in run/mods. */
public final class DistantWaterProbe {
    private static int stage,ticks;
    private static Vec3 eye;
    private static float yaw;
    private static ShaderSettings saved;
    private static final AtomicBoolean captured=new AtomicBoolean(true);
    static void tick(Minecraft client,Path output) {
        try {
            if(client.player==null || client.level==null)return;
            client.player.setDeltaMovement(Vec3.ZERO);
            if(saved==null) {
                saved=ShaderRuntime.settings();
                var server=client.getSingleplayerServer();var uuid=client.player.getUUID();
                server.execute(()->{server.getPlayerList().getPlayer(uuid).setGameMode(GameType.SPECTATOR);
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"gamerule doDaylightCycle false");
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"time set noon");});
                if(!GreenMarineClient.enabled())GreenMarineClient.toggleEffect();
                ShaderRuntime.apply(new ShaderSettings("greenmarine:solstice_rt",Quality.HIGH,RayMode.HARDWARE,false,saved.parameters()));
                client.options.renderDistance().set(3);
                stage=1;ticks=0;return;
            }
            ticks++;
            if(stage==1) {
                if(ticks<200)return;
                // Nearest loaded surface water; look at it from 24 blocks up and 40 blocks back.
                var origin=client.player.blockPosition();BlockPos found=null;double best=Double.MAX_VALUE;
                for(int dx=-40;dx<=40;dx+=2)for(int dz=-40;dz<=40;dz+=2) {
                    int x=origin.getX()+dx,z=origin.getZ()+dz;
                    int y=client.level.getHeight(Heightmap.Types.WORLD_SURFACE,x,z)-1;
                    if(client.level.getFluidState(new BlockPos(x,y,z)).is(Fluids.WATER) && dx*dx+dz*dz<best){best=dx*dx+dz*dz;found=new BlockPos(x,y,z);}
                }
                if(found==null)found=origin;
                eye=new Vec3(found.getX()-28,found.getY()+14,found.getZ()+.5);yaw=-90;
                GreenMarine.LOGGER.info("Distant water probe: water={}",found);
                stage=2;ticks=0;return;
            }
            client.player.setPos(eye);client.player.setYRot(yaw);client.player.setYHeadRot(yaw);client.player.setXRot(14);
            switch(stage) {
                case 2 -> {if(ticks<900)return;capture(client,output,"water-noon.png");stage=3;}
                case 3 -> {
                    if(!captured.get())return;
                    var server=client.getSingleplayerServer();
                    server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"time set midnight"));
                    stage=4;ticks=0;
                }
                case 4 -> {if(ticks<80)return;capture(client,output,"water-night.png");stage=5;}
                case 5 -> {if(!captured.get())return;GreenMarineClient.toggleEffect();stage=6;ticks=0;}
                case 6 -> {if(ticks<60)return;capture(client,output,"water-night-off.png");stage=7;}
                case 7 -> {if(!captured.get())return;GreenMarineClient.toggleEffect();finish(client,output,"complete");}
            }
        } catch(Throwable error) {GreenMarine.LOGGER.error("Distant water probe failed",error);finish(client,output,"failed: "+error);}
    }
    private static void capture(Minecraft client,Path output,String name) {
        captured.set(false);Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
            try {image.writeToFile(output.resolve(name));captured.set(true);}
            catch(Exception error) {GreenMarine.LOGGER.error("Water probe capture failed",error);}finally {image.close();}
        });
    }
    static void finish(Minecraft client,Path output,String status) {
        if(stage==99)return;stage=99;
        if(saved!=null)ShaderRuntime.apply(saved);
        try {Files.writeString(output.resolve("water-result.txt"),status+"\n");}catch(Exception ignored){}
        client.execute(()->{client.disconnectWithSavingScreen();client.stop();});
    }
}
