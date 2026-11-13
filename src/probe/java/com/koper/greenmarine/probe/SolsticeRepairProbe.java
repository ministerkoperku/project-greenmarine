package com.koper.greenmarine.probe;

import com.google.gson.JsonObject;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.client.*;
import com.koper.greenmarine.client.rt.RayWorld;
import com.koper.greenmarine.mixin.TextureAtlasAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Default-resource texture diagnosis. Only run in an owned scratch game/world. */
final class SolsticeRepairProbe {
    private record Variant(String name,RayMode rays,Map<String,Float> parameters) {}
    private static final List<Variant> VARIANTS=List.of(
        new Variant("hardware-default",RayMode.HARDWARE,Map.of()),
        new Variant("hardware-no-relief",RayMode.HARDWARE,Map.of("relief",0f)),
        new Variant("hardware-no-gi",RayMode.HARDWARE,Map.of("giStrength",0f)),
        new Variant("hardware-no-history",RayMode.HARDWARE,Map.of()),
        new Variant("raster-default",RayMode.OFF,Map.of()),
        new Variant("raster-no-relief",RayMode.OFF,Map.of("relief",0f)),
        new Variant("raster-no-history",RayMode.OFF,Map.of()),
        new Variant("hardware-coverage",RayMode.HARDWARE,Map.of("surfaceDiagnostic",3f)),
        new Variant("hardware-albedo",RayMode.HARDWARE,Map.of("surfaceDiagnostic",2f)),
        new Variant("hardware-diagnostic",RayMode.HARDWARE,Map.of("surfaceDiagnostic",10f)));
    private static final AtomicBoolean CAPTURED=new AtomicBoolean(true),BUILT=new AtomicBoolean();
    private static final JsonObject RESULT=new JsonObject();
    private static ShaderSettings saved;
    private static int stage,ticks,index;
    private static BlockPos base;
    private static final boolean OWNER=Boolean.getBoolean("greenmarine.probe.solstice_repair.owner");
    private static final boolean MOTION=Boolean.getBoolean("greenmarine.probe.solstice_repair.motion");
    private static double ownerX,ownerY,ownerZ;

    static void tick(Minecraft client,Path output) {
        try {
            if(client.player==null)return;
            if(stage==0) {
                saved=ShaderRuntime.settings();base=client.player.blockPosition().atY(160);
                ownerX=client.player.getX();ownerY=client.player.getY();ownerZ=client.player.getZ();
                client.options.enableVsync().set(false);client.options.framerateLimit().set(260);
                client.options.bobView().set(false);
                client.getWindow().setWindowed(2560,1370);
                var server=client.getSingleplayerServer();var uuid=client.player.getUUID();var origin=base;
                server.execute(()->{
                    var player=server.getPlayerList().getPlayer(uuid);player.setGameMode(GameType.SPECTATOR);
                    var level=player.level();
                    if(!OWNER)for(int x=-14;x<=14;x++)for(int z=-14;z<=14;z++)for(int y=-2;y<=4;y++)
                        level.setBlock(origin.offset(x,y,z),y<0?Blocks.SANDSTONE.defaultBlockState():y==0?Blocks.SAND.defaultBlockState():Blocks.AIR.defaultBlockState(),2);
                    if(!OWNER)for(int x=3;x<=7;x++)for(int z=1;z<=7;z++)level.setBlock(origin.offset(x,1,z),Blocks.SAND.defaultBlockState(),2);
                    for(String command:List.of("time set "+Integer.getInteger("greenmarine.probe.look.time",3000),"weather clear","gamerule advance_time false","gamerule advance_weather false"))
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),command);
                    BUILT.set(true);
                });
                var atlas=client.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS);
                int width=atlas.getTexture().getWidth(0),height=atlas.getTexture().getHeight(0);
                RESULT.addProperty("atlasWidth",width);RESULT.addProperty("atlasHeight",height);
                for(var sprite:((TextureAtlasAccessor)atlas).greenmarine$sprites().values()) {
                    if(!sprite.contents().name().toString().equals("minecraft:block/sand"))continue;
                    var rect=new JsonObject();rect.addProperty("x",Math.round(sprite.getU0()*width));rect.addProperty("y",Math.round(sprite.getV0()*height));
                    rect.addProperty("width",sprite.contents().width());rect.addProperty("height",sprite.contents().height());RESULT.add("sandSprite",rect);
                }
                stage=1;return;
            }
            client.player.setDeltaMovement(0,0,0);
            if(OWNER)client.player.setPos(ownerX,ownerY,ownerZ);else client.player.setPos(base.getX()+.5,base.getY()+2.4,base.getZ()+.5);
            float yaw=-35+(MOTION && stage==2?Math.min(ticks,100)*.2f:0);
            client.player.setYRot(yaw);client.player.setYHeadRot(yaw);client.player.setYBodyRot(yaw);client.player.setXRot(65);
            if(index<VARIANTS.size() && VARIANTS.get(index).name().contains("no-history"))PackRenderer.invalidateHistory();
            if(!BUILT.get() || !CAPTURED.get())return;
            if(stage==1) {
                if(++ticks<120)return;
                if(index==VARIANTS.size()) {
                    RESULT.addProperty("result","captured");Files.writeString(output.resolve("solstice-repair.json"),RESULT+"\n");
                    ShaderRuntime.apply(saved);stage=99;client.execute(()->{client.disconnectWithSavingScreen();client.stop();});return;
                }
                var variant=VARIANTS.get(index);
                ShaderRuntime.apply(new ShaderSettings("greenmarine:solstice_rt",Quality.MEDIUM,variant.rays(),true,
                    Map.of("greenmarine:solstice_rt",variant.parameters())));
                stage=2;ticks=0;return;
            }
            if(stage==2) {
                if(++ticks<100 || ShaderRuntime.status().state()==PackStatus.State.FAILED)return;
                var variant=VARIANTS.get(index);
                if(variant.rays()==RayMode.HARDWARE && !RayWorld.surfaceReady())return;
                var timing=new JsonObject();timing.addProperty("detail",ShaderRuntime.status().detail());
                timing.addProperty("surfaceGpu",String.valueOf(RayWorld.surfaceGpuTiming()));RESULT.add(variant.name(),timing);
                timing.addProperty("worldGpu",String.valueOf(com.koper.greenmarine.client.world.WorldPrograms.mainGpu()));
                timing.addProperty("packGpu",String.valueOf(PackRenderer.gpuTiming()));
                CAPTURED.set(false);
                Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
                    try {image.writeToFile(output.resolve(variant.name()+".png"));}
                    catch(Exception error) {GreenMarine.LOGGER.error("Solstice repair capture failed",error);}
                    finally {image.close();CAPTURED.set(true);}
                });
                index++;stage=1;ticks=100;
            }
        } catch(Exception error) {
            GreenMarine.LOGGER.error("Solstice repair probe failed",error);
            try {Files.writeString(output.resolve("solstice-repair.json"),"{\"result\":\"failed\"}\n");}catch(Exception ignored){}
            if(saved!=null)ShaderRuntime.apply(saved);stage=99;client.stop();
        }
    }
}
