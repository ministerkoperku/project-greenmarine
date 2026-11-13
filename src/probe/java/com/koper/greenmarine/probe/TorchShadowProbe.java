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
    private static int stage,ticks,preflightTicks;
    private static ShaderSettings saved;
    private static AutoCloseable sceneOnlyRegistration;
    private static BlockPos base;
    private static String heldTiming,emptyTiming,droppedTiming;
    private static final AtomicBoolean captured=new AtomicBoolean(true);
    static void tick(Minecraft client,Path output) {
        try {
            if(client.player==null)return;
            client.player.setDeltaMovement(Vec3.ZERO);
            if(stage==0) {
                if(preflightTicks==0) {
                    saved=ShaderRuntime.settings();
                    var pass=new PackPass("copy",PackPass.Stage.SCENE,"greenmarine:core/torch_scene_fixture",null,
                        PackPass.Requirement.NONE,Quality.LOW,1,null);
                    var pack=new ShaderPack("greenmarine:torch_scene_fixture","Scene-only binding fixture",null,
                        java.util.List.of(),java.util.List.of(pass));
                    sceneOnlyRegistration=GreenMarine.PACKS.register(new PackCatalog.LoadedPack(pack,"function update(frame) end",
                        java.util.Map.of("greenmarine:core/torch_scene_fixture","#version 330\n#extension GL_ARB_separate_shader_objects : require\nuniform sampler2D SceneSampler;\nlayout(location=0) in vec2 texCoord;\nlayout(location=0) out vec4 fragColor;\nvoid main(){fragColor=texture(SceneSampler,texCoord);}\n")));
                    if(!GreenMarineClient.enabled())GreenMarineClient.toggleEffect();
                    ShaderRuntime.apply(new ShaderSettings(pack.id(),Quality.HIGH,RayMode.OFF,false));
                    preflightTicks=1;return;
                }
                if(++preflightTicks<40)return;
                if(PackRenderer.failed() || PackRenderer.sceneFrames()==0)throw new IllegalStateException("Scene-only binding fixture failed");
                sceneOnlyRegistration.close();sceneOnlyRegistration=null;
                base=client.player.blockPosition().atY(120);
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
                var quality=Quality.valueOf(System.getProperty("greenmarine.probe.look.quality","high").toUpperCase(java.util.Locale.ROOT));
                ShaderRuntime.apply(new ShaderSettings("greenmarine:solstice_rt",quality,RayMode.HARDWARE,true,saved.parameters()));
                stage=1;ticks=0;return;
            }
            ticks++;
            boolean held=stage>=3;
            client.player.setPos(base.getX()+(held?1.6:-2.5),base.getY()+(held?.2:1.2),base.getZ()+(held?2.5:5.5));
            client.player.setYRot(held?-90:-125);client.player.setYHeadRot(held?-90:-125);client.player.setYBodyRot(held?-90:-125);client.player.setXRot(held?25:18);
            if(stage>=9) {
                client.player.setPos(base.getX()+.5,base.getY()+5,base.getZ()+9);
                client.player.setYRot(180);client.player.setYHeadRot(180);client.player.setYBodyRot(180);client.player.setXRot(45);
            }
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
                case 3 -> {if(ticks<80)return;heldTiming=timing();capture(client,output,"torch-held.png");stage=4;}
                case 4 -> {
                    if(!captured.get())return;
                    var server=client.getSingleplayerServer();var uuid=client.player.getUUID();var origin=base;
                    server.execute(()->{
                        var player=server.getPlayerList().getPlayer(uuid);var level=player.level();
                        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,net.minecraft.world.item.ItemStack.EMPTY);
                    });
                    stage=5;ticks=0;
                }
                case 5 -> {
                    if(ticks<80)return;
                    emptyTiming=timing();capture(client,output,"torch-empty.png");stage=6;
                }
                case 6 -> {
                    if(!captured.get())return;
                    var server=client.getSingleplayerServer();var uuid=client.player.getUUID();var origin=base;
                    server.execute(()->{
                        var level=server.getPlayerList().getPlayer(uuid).level();
                        var item=new net.minecraft.world.entity.item.ItemEntity(level,origin.getX()+.5,origin.getY()+.3,origin.getZ()+.5,
                            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TORCH));
                        item.setNoGravity(true);item.setDeltaMovement(Vec3.ZERO);level.addFreshEntity(item);
                    });
                    stage=7;ticks=0;
                }
                case 7 -> {if(ticks<80)return;verifyDroppedLight(client);capture(client,output,"torch-dropped.png");stage=8;}
                case 8 -> {
                    if(!captured.get())return;droppedTiming=timing();
                    var server=client.getSingleplayerServer();var uuid=client.player.getUUID();var origin=base;
                    server.execute(()->{
                        var level=server.getPlayerList().getPlayer(uuid).level();
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"time set noon");
                        for(int x=-7;x<=7;x++)for(int z=-7;z<=7;z++)for(int y=-7;y<=4;y++) {
                            int bottom=x<0?-2:-7;
                            var state=Math.abs(x)==7 || Math.abs(z)==7 || y<=bottom?Blocks.STONE.defaultBlockState():
                                (y<0?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState());
                            level.setBlockAndUpdate(origin.offset(x,y,z),state);
                        }
                    });
                    stage=9;ticks=0;
                }
                case 9 -> {if(ticks<120)return;capture(client,output,"water-optics.png");stage=10;}
                case 10 -> {if(!captured.get())return;finish(client,output,"complete; sceneOnlyVerified=true; droppedLightVerified=true; lightsActive="+RayWorld.surfaceLightsActive()
                    +"; held="+heldTiming+"; empty="+emptyTiming+"; dropped="+droppedTiming);}
            }
        } catch(Throwable error) {GreenMarine.LOGGER.error("Torch probe failed",error);finish(client,output,"failed: "+error);}
    }
    private static String timing() {return "CPU="+RayWorld.surfaceCpuTiming()+", GPU="+RayWorld.surfaceGpuTiming();}
    private static void verifyDroppedLight(Minecraft client) throws Exception {
        var lights=Class.forName("com.koper.greenmarine.client.rt.SurfaceLights");
        var collect=lights.getDeclaredMethod("collect",Minecraft.class,int.class,int.class,int.class);collect.setAccessible(true);
        var data=lights.getDeclaredMethod("data");data.setAccessible(true);
        int count=(int)collect.invoke(null,client,0,0,0);float[] values=(float[])data.invoke(null);
        for(var entity:client.level.entitiesForRendering())if(entity instanceof net.minecraft.world.entity.item.ItemEntity item
            && item.getItem().getItem()==net.minecraft.world.item.Items.TORCH) {
            for(int i=0;i<count;i++)if(Math.abs(values[i*8]-item.getX())<.05 && Math.abs(values[i*8+2]-item.getZ())<.05
                && Math.abs(values[i*8+1]-item.getY()-item.getBbHeight()*.5)<.05 && values[i*8+3]==14)return;
        }
        throw new IllegalStateException("Dropped torch did not reach the actual surface-light payload");
    }
    private static void capture(Minecraft client,Path output,String name) {
        captured.set(false);Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
            try {image.writeToFile(output.resolve(name));captured.set(true);}
            catch(Exception error) {GreenMarine.LOGGER.error("Torch probe capture failed",error);}finally {image.close();}
        });
    }
    static void finish(Minecraft client,Path output,String status) {
        if(stage==99)return;stage=99;
        try {Files.writeString(output.resolve("actor-memory.json"),new com.google.gson.Gson().toJson(com.koper.greenmarine.client.rt.PlayerReflection.memoryStats()));}
        catch(Exception error) {GreenMarine.LOGGER.warn("Could not save actor memory statistics",error);}
        if(sceneOnlyRegistration!=null)try {sceneOnlyRegistration.close();sceneOnlyRegistration=null;}catch(Exception ignored){}
        if(saved!=null)ShaderRuntime.apply(saved);
        try {Files.writeString(output.resolve("torch-result.txt"),status+"\n");}catch(Exception ignored){}
        client.execute(()->{client.disconnectWithSavingScreen();client.stop();});
    }
}
