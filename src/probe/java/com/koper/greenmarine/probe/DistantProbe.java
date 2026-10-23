package com.koper.greenmarine.probe;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.client.*;
import com.koper.greenmarine.api.pack.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Distant Horizons LODs with Solstice on and GreenMarine off; needs the DH jar in run/mods. */
public final class DistantProbe {
    private static int stage,ticks;
    private static Vec3 position;
    private static ShaderSettings saved;
    private static final AtomicBoolean captured=new AtomicBoolean(true);
    static void tick(Minecraft client,Path output) {
        try {
            if(client.player==null)return;
            client.player.setDeltaMovement(Vec3.ZERO);
            if(saved==null) {
                saved=ShaderRuntime.settings();
                position=client.player.position().add(0,50,0);
                var server=client.getSingleplayerServer();var uuid=client.player.getUUID();
                server.execute(()->{
                    server.getPlayerList().getPlayer(uuid).setGameMode(GameType.SPECTATOR);
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"time set noon");
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"gamerule doDaylightCycle false");
                });
                if(!GreenMarineClient.enabled())GreenMarineClient.toggleEffect();
                ShaderRuntime.apply(new ShaderSettings("greenmarine:solstice_rt",Quality.HIGH,RayMode.HARDWARE,false,saved.parameters()));
                client.options.renderDistance().set(6);
                stage=1;ticks=0;return;
            }
            ticks++;
            double walk=stage>=10?(stage-10)/2*.4:0;
            client.player.setPos(position.add(walk,0,walk));client.player.setYRot(30);client.player.setYHeadRot(30);client.player.setXRot(stage==16?35:8);
            switch(stage) {
                case 1 -> {if(ticks<1800)return;capture(client,output,"distant-solstice.png");stage=10;}
                case 10,12,14,16 -> {if(!captured.get())return;stage++;ticks=0;}
                case 11,13,15,17 -> {if(ticks<3)return;capture(client,output,"distant-walk-"+(stage-11)/2+".png");stage=stage==17?2:stage+1;}
                case 2 -> {if(!captured.get())return;GreenMarineClient.toggleEffect();stage=3;ticks=0;}
                case 3 -> {if(ticks<60)return;capture(client,output,"distant-off.png");stage=4;}
                case 4 -> {if(!captured.get())return;GreenMarineClient.toggleEffect();stage=5;ticks=0;}
                case 5 -> {if(ticks<60)return;capture(client,output,"distant-solstice-again.png");stage=6;}
                case 6 -> {if(!captured.get())return;finish(client,output,"complete");}
            }
        } catch(Throwable error) {GreenMarine.LOGGER.error("Distant probe failed",error);finish(client,output,"failed: "+error);}
    }
    private static void capture(Minecraft client,Path output,String name) {
        captured.set(false);Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
            try {image.writeToFile(output.resolve(name));captured.set(true);}
            catch(Exception error) {GreenMarine.LOGGER.error("Distant probe capture failed",error);}finally {image.close();}
        });
    }
    static void finish(Minecraft client,Path output,String status) {
        if(stage==99)return;stage=99;
        if(saved!=null)ShaderRuntime.apply(saved);
        try {Files.writeString(output.resolve("distant-result.txt"),status+"\n");}catch(Exception ignored){}
        client.execute(()->{client.disconnectWithSavingScreen();client.stop();});
    }
}
