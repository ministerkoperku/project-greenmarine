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

/** Living entities in Solstice surface reflections and RT sun shadows, in front of an iron wall. */
public final class MobReflectionProbe {
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
                    commands.performPrefixedCommand(source,"time set noon");commands.performPrefixedCommand(source,"gamerule doDaylightCycle false");
                    for(int x=-8;x<=6;x++)for(int z=-6;z<=6;z++)for(int y=-1;y<=6;y++)
                        level.setBlockAndUpdate(origin.offset(x,y,z),y==-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
                    for(int z=-4;z<=4;z++)for(int y=0;y<=4;y++)level.setBlockAndUpdate(origin.offset(4,y,z),Blocks.IRON_BLOCK.defaultBlockState());
                    String[] mobs={"cow -2 0 -1.5","pig -3 0 1","zombie -1 0 1.8"};
                    commands.performPrefixedCommand(source,"item replace entity @p armor.head with minecraft:diamond_helmet");commands.performPrefixedCommand(source,"item replace entity @p armor.chest with minecraft:iron_chestplate");
                    for(var mob:mobs) {
                        var p=mob.split(" ");
                        commands.performPrefixedCommand(source,String.format(java.util.Locale.ROOT,"summon minecraft:%s %.1f %d %.1f {NoAI:1b,Rotation:[90f,0f],equipment:{head:{id:\"minecraft:golden_helmet\",count:1},chest:{id:\"minecraft:diamond_chestplate\",count:1}}}",
                            p[0],origin.getX()+.5+Double.parseDouble(p[1]),origin.getY(),origin.getZ()+.5+Double.parseDouble(p[3])));
                    }
                });
                client.player.getAbilities().flying=true;
                if(!GreenMarineClient.enabled())GreenMarineClient.toggleEffect();
                ShaderRuntime.apply(new ShaderSettings("greenmarine:solstice_rt",Quality.HIGH,RayMode.HARDWARE,true,saved.parameters()));
                stage=1;ticks=0;return;
            }
            ticks++;
            boolean side=stage>=3;
            client.player.setPos(base.getX()+(side?-1.5:2.0),base.getY()+(side?1.5:.4),base.getZ()+(side?-5.0:.5));
            client.player.setYRot(side?0:-90);client.player.setYHeadRot(side?0:-90);client.player.setXRot(side?30:12);
            switch(stage) {
                case 1 -> {
                    if(ticks<240 || !RayWorld.surfaceReady() || RayWorld.surfaceFrames()<120) {if(ticks>2400)throw new IllegalStateException(RayWorld.surfaceDetail());return;}
                    capture(client,output,"mobs-reflection.png");stage=2;
                }
                case 2 -> {if(!captured.get())return;stage=3;ticks=0;}
                case 3 -> {if(ticks<40)return;capture(client,output,"mobs-shadows.png");stage=4;}
                case 4 -> {if(!captured.get())return;finish(client,output,"complete; "+RayWorld.surfaceDetail());}
            }
        } catch(Throwable error) {GreenMarine.LOGGER.error("Mob reflection probe failed",error);finish(client,output,"failed: "+error);}
    }
    private static void capture(Minecraft client,Path output,String name) {
        captured.set(false);Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
            try {image.writeToFile(output.resolve(name));captured.set(true);}
            catch(Exception error) {GreenMarine.LOGGER.error("Mob probe capture failed",error);}finally {image.close();}
        });
    }
    static void finish(Minecraft client,Path output,String status) {
        if(stage==99)return;stage=99;
        if(saved!=null)ShaderRuntime.apply(saved);
        try {Files.writeString(output.resolve("mobs-result.txt"),status+"\n");}catch(Exception ignored){}
        client.execute(()->{client.disconnectWithSavingScreen();client.stop();});
    }
}
