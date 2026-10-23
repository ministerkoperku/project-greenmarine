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

/** Night scene: a floor torch behind a stone pillar should shadow the wall; then a held torch. */
public final class TorchShadowProbe {
    private static int stage,ticks;
    private static ShaderSettings saved;
    private static BlockPos base;
    private static final AtomicBoolean captured=new AtomicBoolean(true);
    static void tick(Minecraft client,Path output) {
        try {
            if(client.player==null)return;
            client.player.setDeltaMovement(Vec3.ZERO);
            if(saved==null) {
                saved=ShaderRuntime.settings();base=client.player.blockPosition().atY(120);
                var server=client.getSingleplayerServer();var uuid=client.player.getUUID();var origin=base;
                server.execute(()->{
                    var player=server.getPlayerList().getPlayer(uuid);player.setGameMode(GameType.CREATIVE);var level=player.level();
                    var commands=server.getCommands();var source=server.createCommandSourceStack();
                    commands.performPrefixedCommand(source,"time set midnight");commands.performPrefixedCommand(source,"gamerule doDaylightCycle false");
                    for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)for(int y=-1;y<=6;y++)
                        level.setBlockAndUpdate(origin.offset(x,y,z),y==-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
                    for(int z=-4;z<=4;z++)for(int y=0;y<=4;y++)level.setBlockAndUpdate(origin.offset(4,y,z),Blocks.SMOOTH_STONE.defaultBlockState());
                    level.setBlockAndUpdate(origin,Blocks.TORCH.defaultBlockState());
                    level.setBlockAndUpdate(origin.offset(2,0,0),Blocks.COBBLESTONE.defaultBlockState());
                    level.setBlockAndUpdate(origin.offset(2,1,0),Blocks.COBBLESTONE.defaultBlockState());
                });
                client.player.getAbilities().flying=true;
                if(!GreenMarineClient.enabled())GreenMarineClient.toggleEffect();
                ShaderRuntime.apply(new ShaderSettings("greenmarine:solstice_rt",Quality.HIGH,RayMode.HARDWARE,true,saved.parameters()));
                stage=1;ticks=0;return;
            }
            ticks++;
            boolean held=stage>=3;
            client.player.setPos(base.getX()+(held?1.6:-2.5),base.getY()+(held?.2:1.2),base.getZ()+(held?2.5:5.5));
            client.player.setYRot(held?-90:-125);client.player.setYHeadRot(held?-90:-125);client.player.setYBodyRot(held?-90:-125);client.player.setXRot(held?25:18);
            switch(stage) {
                case 1 -> {
                    if(ticks<240 || !RayWorld.surfaceReady() || RayWorld.surfaceFrames()<120) {if(ticks>2400)throw new IllegalStateException(RayWorld.surfaceDetail());return;}
                    capture(client,output,"torch-floor.png");stage=2;
                }
                case 2 -> {
                    if(!captured.get())return;
                    var server=client.getSingleplayerServer();
                    server.execute(()->{
                        var commands=server.getCommands();var source=server.createCommandSourceStack();
                        commands.performPrefixedCommand(source,String.format("setblock %d %d %d air",base.getX(),base.getY(),base.getZ()));
                        commands.performPrefixedCommand(source,"item replace entity @p weapon.mainhand with minecraft:torch");
                        commands.performPrefixedCommand(source,String.format("setblock %d %d %d cobblestone",base.getX()+3,base.getY(),base.getZ()+2));
                    });
                    stage=3;ticks=0;
                }
                case 3 -> {if(ticks<80)return;capture(client,output,"torch-held.png");stage=4;}
                case 4 -> {if(!captured.get())return;finish(client,output,"complete; lightsActive="+RayWorld.surfaceLightsActive());}
            }
        } catch(Throwable error) {GreenMarine.LOGGER.error("Torch probe failed",error);finish(client,output,"failed: "+error);}
    }
    private static void capture(Minecraft client,Path output,String name) {
        captured.set(false);Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
            try {image.writeToFile(output.resolve(name));captured.set(true);}
            catch(Exception error) {GreenMarine.LOGGER.error("Torch probe capture failed",error);}finally {image.close();}
        });
    }
    static void finish(Minecraft client,Path output,String status) {
        if(stage==99)return;stage=99;
        if(saved!=null)ShaderRuntime.apply(saved);
        try {Files.writeString(output.resolve("torch-result.txt"),status+"\n");}catch(Exception ignored){}
        client.execute(()->{client.disconnectWithSavingScreen();client.stop();});
    }
}
