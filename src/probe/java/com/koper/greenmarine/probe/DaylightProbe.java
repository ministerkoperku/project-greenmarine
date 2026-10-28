package com.koper.greenmarine.probe;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.client.*;
import com.koper.greenmarine.client.rt.RayWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.level.GameType;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent daylight feature toggles at one fixed natural-terrain camera. Scratch worlds only. */
final class DaylightProbe {
    private static final List<Map<String,Float>> VARIANTS=List.of(
        Map.of(),Map.of("volumetric",0f),Map.of("occlusion",0f),
        Map.of("rayBlend",0f),Map.of("volumetric",0f,"occlusion",0f),
        Map.of("surfaceDiagnostic",4f),Map.of("surfaceDiagnostic",6f));
    private static final String[] NAMES={"all","no-volumetric","no-ao","no-rays","no-volume-ao","debug-ao","debug-visibility"};
    private static ShaderSettings saved;
    private static double x,y,z;
    private static float yaw,pitch;
    private static int ticks,index=-1;
    private static final AtomicBoolean captured=new AtomicBoolean(true);
    static void tick(Minecraft client,Path output) {
        try {
            if(client.player==null)return;
            if(saved==null) {
                saved=ShaderRuntime.settings();x=client.player.getX();z=client.player.getZ();
                y=client.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,(int)Math.floor(x),(int)Math.floor(z))+5;
                yaw=client.player.getYRot();pitch=14;
                client.options.enableVsync().set(false);client.getWindow().setWindowed(1200,675);
                var server=client.getSingleplayerServer();var uuid=client.player.getUUID();
                server.execute(()->{
                    server.getPlayerList().getPlayer(uuid).setGameMode(GameType.SPECTATOR);
                    for(var command:List.of("time set 2500","weather clear","gamerule advance_time false"))
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),command);
                });
                ShaderRuntime.apply(new ShaderSettings("greenmarine:solstice_rt",Quality.HIGH,RayMode.HARDWARE,true,Map.of()));
                ticks=0;return;
            }
            client.player.setDeltaMovement(0,0,0);client.player.setPos(x,y,z);
            client.player.setYRot(yaw);client.player.setYHeadRot(yaw);client.player.setXRot(pitch);
            if(!captured.get())return;
            if(++ticks<(index<0?200:60))return;
            if(!ShaderRuntime.status().loaded() || !RayWorld.surfaceReady())return;
            if(index>=0) {
                captured.set(false);var name=NAMES[index];
                Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
                    try {image.writeToFile(output.resolve(name+".png"));}
                    catch(Exception error) {GreenMarine.LOGGER.error("Daylight capture failed",error);}
                    finally {image.close();captured.set(true);}
                });
            }
            if(++index>=VARIANTS.size()) {
                Files.writeString(output.resolve("daylight-result.txt"),"complete; camera="+x+","+y+","+z+" yaw="+yaw+" pitch="+pitch+"\n");
                ShaderRuntime.apply(saved);client.execute(()->{client.disconnectWithSavingScreen();client.stop();});return;
            }
            for(var name:List.of("volumetric","occlusion","rayBlend","surfaceDiagnostic")) {
                var p=ShaderRuntime.active().definition().parameters().stream().filter(v->v.name().equals(name)).findFirst().orElseThrow();
                ShaderRuntime.previewParameter(name,VARIANTS.get(index).getOrDefault(name,p.defaultValue()));
            }
            ticks=0;
        } catch(Exception error) {
            GreenMarine.LOGGER.error("Daylight probe failed",error);
            try {Files.writeString(output.resolve("daylight-result.txt"),"failed: "+error+"\n");}catch(Exception ignored){}
            client.stop();
        }
    }
}
