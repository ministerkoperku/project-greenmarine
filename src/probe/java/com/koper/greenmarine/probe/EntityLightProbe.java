package com.koper.greenmarine.probe;

import com.google.gson.JsonObject;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.client.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Live entity-renderer light readings in an explicitly owned scratch world. */
final class EntityLightProbe {
    private static final String TAG="greenmarine_entity_light_probe";
    private static final AtomicBoolean BUILT=new AtomicBoolean(),CAPTURED=new AtomicBoolean(true);
    private static final AtomicReference<Throwable> ASYNC_FAILURE=new AtomicReference<>();
    private static final JsonObject RESULT=new JsonObject();
    private static int stage,ticks,totalTicks,near,empty,far,raster,none;
    private static ShaderSettings saved;
    private static boolean savedEnabled;
    private static BlockPos base;

    static void tick(Minecraft client,Path output) {
        if(stage==99 || client.player==null)return;
        try {
            if(ASYNC_FAILURE.get()!=null)throw new IllegalStateException("Asynchronous fixture/capture failed",ASYNC_FAILURE.get());
            if(++totalTicks>3000)throw new IllegalStateException("Entity-light probe timed out at stage "+stage);
            if(stage==0) {
                if(!Boolean.getBoolean("greenmarine.probe.entity_light.scratch"))
                    throw new IllegalStateException("Entity-light room requires explicit greenmarine.probe.entity_light.scratch=true");
                if(client.getSingleplayerServer()==null)throw new IllegalStateException("Scratch integrated server required");
                Files.createDirectories(output);
                saved=ShaderRuntime.settings();savedEnabled=GreenMarineClient.enabled();
                base=client.player.blockPosition().atY(160);
                RESULT.addProperty("backend",RenderSystem.getDevice().getDeviceInfo().backendName());
                RESULT.addProperty("fixture","sealed stone room, no block emitters, stationary NoAI pig");
                client.options.bobView().set(false);
                buildRoom(client);
                if(!savedEnabled)GreenMarineClient.toggleEffect();
                solstice(RayMode.HARDWARE);
                stage=1;ticks=0;return;
            }
            ticks++;
            holdCamera(client,stage==6);
            if(!BUILT.get() || !CAPTURED.get())return;
            Entity pig=pig(client);
            if(pig==null) {if(ticks>400)throw new IllegalStateException("Summoned fixture pig not synchronized");return;}
            switch(stage) {
                case 1 -> {
                    if(ticks<140 || !torch(client) || !activeSolstice())return;
                    near=read(client,pig,"heldNearHardware");
                    require((near&255)>=128,"Near torch did not illuminate the pig: "+near);
                    capture(client,output,"entity-held-on.png");stage=2;ticks=0;
                }
                case 2 -> {hand(client,false);stage=3;ticks=0;}
                case 3 -> {
                    if(ticks<70 || torch(client))return;
                    empty=read(client,pig,"emptyHand");
                    require(empty==0,"Empty-hand fixture is not dark: "+empty);
                    require((near&255)>(empty&255),"Removing torch did not dim entity light");
                    capture(client,output,"entity-held-off.png");stage=4;ticks=0;
                }
                case 4 -> {hand(client,true);stage=5;ticks=0;}
                case 5 -> {
                    if(ticks<60 || !torch(client))return;
                    int restored=read(client,pig,"heldNearRestored");
                    require(Math.abs((restored&255)-(near&255))<=2,"Re-equipping torch did not restore near light");
                    teleport(client,true);stage=6;ticks=0;
                }
                case 6 -> {
                    if(ticks<70 || client.player.distanceToSqr(pig)<=256)return;
                    far=read(client,pig,"heldFar");
                    require(far==empty,"Far source still contributes entity light: "+far);
                    teleport(client,false);solstice(RayMode.OFF);stage=7;ticks=0;
                }
                case 7 -> {
                    if(ticks<100 || !torch(client) || !activeSolstice())return;
                    require(ShaderRuntime.settings().rays()==RayMode.OFF,"Ray mode OFF not retained");
                    raster=read(client,pig,"heldNearRaysOff");
                    require(Math.abs((raster&255)-(near&255))<=2,"Entity light incorrectly depends on hardware RT");
                    capture(client,output,"entity-rays-off.png");stage=8;ticks=0;
                }
                case 8 -> {
                    ShaderRuntime.apply(new ShaderSettings("greenmarine:none",Quality.MEDIUM,RayMode.OFF,false));
                    stage=9;ticks=0;
                }
                case 9 -> {
                    if(ticks<100 || ShaderRuntime.active()!=null)return;
                    none=read(client,pig,"heldNearPackNone");
                    require(none==empty,"Inactive Solstice still contributes entity light: "+none);
                    capture(client,output,"entity-pack-none.png");stage=10;ticks=0;
                }
                case 10 -> {
                    RESULT.addProperty("status","passed");
                    RESULT.addProperty("checks","near > empty; far = empty; rays OFF = near; pack none = empty; vanilla block/sky unchanged");
                    finish(client,output);
                }
            }
        } catch(Throwable failure) {
            GreenMarine.LOGGER.error("Entity-light runtime probe failed",failure);
            RESULT.addProperty("status","failed");RESULT.addProperty("stage",stage);RESULT.addProperty("error",failure.toString());
            finish(client,output);
        }
    }

    private static void solstice(RayMode rays) {
        ShaderRuntime.apply(new ShaderSettings("greenmarine:solstice_rt",Quality.MEDIUM,rays,false,saved.parameters()));
    }

    private static boolean activeSolstice() {
        if(ShaderRuntime.status().state()==PackStatus.State.FAILED)
            throw new IllegalStateException("Solstice failed: "+ShaderRuntime.status().detail());
        return ShaderRuntime.active()!=null && ShaderRuntime.active().definition().id().equals("greenmarine:solstice_rt")
            && ShaderRuntime.script()!=null && !ShaderRuntime.script().failed();
    }

    private static void buildRoom(Minecraft client) {
        var server=client.getSingleplayerServer();var uuid=client.player.getUUID();var origin=base;
        server.execute(()->{
            try {
                var player=server.getPlayerList().getPlayer(uuid);player.setGameMode(GameType.CREATIVE);
                var level=player.level();
                var source=server.createCommandSourceStack();
                for(String command:java.util.List.of("time set midnight","weather clear","gamerule advance_time false","gamerule advance_weather false"))
                    server.getCommands().performPrefixedCommand(source,command);
                for(int x=-6;x<=6;x++)for(int z=-24;z<=24;z++)for(int y=-1;y<=6;y++) {
                    boolean shell=Math.abs(x)==6 || Math.abs(z)==24 || y==-1 || y==6;
                    level.setBlockAndUpdate(origin.offset(x,y,z),(shell?Blocks.STONE:Blocks.AIR).defaultBlockState());
                }
                server.getCommands().performPrefixedCommand(source,"kill @e[tag="+TAG+"]");
                String command=String.format(java.util.Locale.ROOT,
                    "summon minecraft:pig %.2f %d %.2f {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:[\"%s\"]}",
                    origin.getX()+.5,origin.getY(),origin.getZ()+.5,TAG);
                server.getCommands().performPrefixedCommand(source,command);
                server.getCommands().performPrefixedCommand(source,"item replace entity "+player.getScoreboardName()+" weapon.mainhand with minecraft:torch");
                server.getCommands().performPrefixedCommand(source,"item replace entity "+player.getScoreboardName()+" weapon.offhand with minecraft:air");
                server.getCommands().performPrefixedCommand(source,teleportCommand(player.getScoreboardName(),origin,false));
                BUILT.set(true);
            } catch(Throwable failure) {ASYNC_FAILURE.set(failure);}
        });
    }

    private static String teleportCommand(String name,BlockPos origin,boolean farSource) {
        return String.format(java.util.Locale.ROOT,"tp %s %.2f %d %.2f 180 20",name,
            origin.getX()+.5,origin.getY(),origin.getZ()+(farSource?22.5:2.5));
    }

    private static void teleport(Minecraft client,boolean farSource) {
        var server=client.getSingleplayerServer();var uuid=client.player.getUUID();
        server.execute(()->{
            var player=server.getPlayerList().getPlayer(uuid);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),teleportCommand(player.getScoreboardName(),base,farSource));
        });
    }

    private static void holdCamera(Minecraft client,boolean farSource) {
        client.player.setDeltaMovement(0,0,0);
        client.player.setPos(base.getX()+.5,base.getY(),base.getZ()+(farSource?22.5:2.5));
        client.player.setYRot(180);client.player.setYHeadRot(180);client.player.setYBodyRot(180);client.player.setXRot(20);
    }

    private static void hand(Minecraft client,boolean torch) {
        var server=client.getSingleplayerServer();var uuid=client.player.getUUID();
        server.execute(()->{
            var player=server.getPlayerList().getPlayer(uuid);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"item replace entity "+player.getScoreboardName()
                +" weapon.mainhand with minecraft:"+(torch?"torch":"air"));
        });
    }

    private static boolean torch(Minecraft client) {return client.player.getMainHandItem().is(Items.TORCH);}

    private static Entity pig(Minecraft client) {
        for(var entity:client.level.entitiesForRendering())
            if(entity instanceof Pig && entity.entityTags().contains(TAG))return entity;
        // Entity tags are server-owned and may not synchronize; this sealed fixture has one nearby pig.
        for(var entity:client.level.entitiesForRendering())
            if(entity instanceof Pig && entity.position().distanceToSqr(base.getX()+.5,base.getY(),base.getZ()+.5)<1)return entity;
        return null;
    }

    private static int read(Minecraft client,Entity pig,String name) {
        float partial=client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        var renderer=client.getEntityRenderDispatcher().getRenderer(pig);
        int packed=renderer.getPackedLightCoords(pig,partial);
        int extracted=renderer.createRenderState(pig,partial).lightCoords;
        require(packed==extracted,"Renderer state does not carry sampled light at "+name);
        var pos=BlockPos.containing(pig.getLightProbePosition(partial));
        int block=client.level.getBrightness(LightLayer.BLOCK,pos),sky=client.level.getBrightness(LightLayer.SKY,pos);
        require(block==0 && sky==0,"Fixture vanilla light is not dark at "+name+": block="+block+", sky="+sky);
        require((packed>>>16)==0,"Dynamic light modified skylight at "+name);
        var reading=new JsonObject();reading.addProperty("packed",packed);reading.addProperty("extractedPacked",extracted);
        reading.addProperty("blockCoordinate",packed&255);reading.addProperty("worldBlock",block);reading.addProperty("worldSky",sky);
        reading.addProperty("playerDistance",Math.sqrt(client.player.distanceToSqr(pig)));
        reading.addProperty("torch",torch(client));reading.addProperty("pack",ShaderRuntime.settings().pack());
        reading.addProperty("rays",ShaderRuntime.settings().rays().name());RESULT.add(name,reading);
        GreenMarine.LOGGER.info("Entity-light probe {}: packed={}, world block={}, sky={}",name,packed,block,sky);
        return packed;
    }

    private static void capture(Minecraft client,Path output,String name) {
        CAPTURED.set(false);
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
            try {image.writeToFile(output.resolve(name));}
            catch(Throwable failure) {ASYNC_FAILURE.set(failure);}
            finally {image.close();CAPTURED.set(true);}
        });
    }

    private static void require(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}

    private static void finish(Minecraft client,Path output) {
        if(stage==99)return;stage=99;
        try {Files.createDirectories(output);Files.writeString(output.resolve("entity-light-result.json"),RESULT+"\n");}
        catch(Exception failure) {GreenMarine.LOGGER.error("Could not save entity-light result",failure);}
        if(saved!=null)ShaderRuntime.apply(saved);
        if(saved!=null && GreenMarineClient.enabled()!=savedEnabled)GreenMarineClient.toggleEffect();
        client.execute(()->{client.disconnectWithSavingScreen();client.stop();});
    }
}
