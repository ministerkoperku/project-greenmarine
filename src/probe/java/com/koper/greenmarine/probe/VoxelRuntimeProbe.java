package com.koper.greenmarine.probe;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.Light;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.client.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.*;

final class VoxelRuntimeProbe {
    private static int ticks,stage;
    private static volatile boolean captured,failed;
    private static ShaderSettings original;
    private static PerformanceSamples.Snapshot offGpu,onGpu;
    private static volatile net.minecraft.world.level.GameType originalMode;
    private static boolean restoring;
    private VoxelRuntimeProbe() {}
    static void tick(Minecraft client,Path output) {
        if(client.player==null) return;
        client.player.setYRot(0); client.player.setYHeadRot(0); client.player.setXRot(20);
        if(failed) { finish(client,output,"failed"); return; }
        if(++ticks<60) return;
        switch(stage) {
            case 0 -> {
                original=ShaderRuntime.settings();
                for(int x=-44;x<=-33;x++) for(int z=53;z<=65;z++) {
                    client.level.setBlock(new BlockPos(x,66,z),Blocks.SMOOTH_STONE.defaultBlockState(),3);
                    for(int y=67;y<=72;y++) client.level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
                }
                for(int x=-40;x<=-37;x++) for(int y=67;y<=70;y++) client.level.setBlock(new BlockPos(x,y,58),Blocks.STONE.defaultBlockState(),3);
                var server=client.getSingleplayerServer(); var uuid=client.player.getUUID();
                server.execute(() -> {
                    var player=server.getPlayerList().getPlayer(uuid);
                    originalMode=player.gameMode(); player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
                    player.teleportTo(-38.5,67.05,54.5);
                });
                ShaderRuntime.apply(new ShaderSettings("greenmarine:none",Quality.HIGH,RayMode.OFF,true));
                stage=7; ticks=0;
            }
            case 7 -> {
                if(!client.player.isSpectator() || Math.abs(client.player.getX()+38.5)>0.1 || Math.abs(client.player.getZ()-54.5)>0.1) return;
                GreenMarine.LIGHTS.create(new Light(-38.5,69,60.5,0.15f,1,0.35f,8,2));
                stage=1; ticks=0;
            }
            case 1 -> {
                offGpu=GreenPostEffect.gpuTiming(); captured=false;
                capture(client,output,"voxel-rays-off.png"); stage=2;
            }
            case 2 -> {
                if(!captured) return;
                ShaderRuntime.apply(new ShaderSettings("greenmarine:none",Quality.HIGH,RayMode.VOXEL,true));
                stage=3; ticks=0;
            }
            case 3 -> {
                if(!VoxelWorld.ready()) return;
                stage=4; ticks=0;
            }
            case 4 -> {
                onGpu=GreenPostEffect.gpuTiming(); captured=false;
                capture(client,output,"voxel-rays-on.png"); stage=5;
            }
            case 5 -> { if(captured) finish(client,output,"complete"); }
            case 8 -> {
                if(client.player.isSpectator()) return;
                client.disconnectWithSavingScreen(); client.stop(); stage=9;
            }
            default -> {}
        }
    }
    private static void capture(Minecraft client,Path output,String name) {
        if(Math.abs(client.player.getX()+38.5)>0.1 || Math.abs(client.player.getZ()-54.5)>0.1) {
            failed=true; GreenMarine.LOGGER.error("Voxel fixture camera left the test location"); return;
        }
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image -> {
            try(image) { image.writeToFile(output.resolve(name)); GreenMarine.LOGGER.info("Voxel wall screenshot saved: {}",name); }
            catch(Exception error) { failed=true; GreenMarine.LOGGER.error("Voxel wall screenshot failed",error); }
            finally { captured=true; }
        });
    }
    private static void finish(Minecraft client,Path output,String status) {
        if(restoring) return; restoring=true;
        stage=6;
        try {
            var result=new com.google.gson.JsonObject(); result.addProperty("status",status); result.addProperty("voxelScans",VoxelWorld.scans());
            var gson=new com.google.gson.Gson(); result.add("raysOffGpu",gson.toJsonTree(offGpu)); result.add("raysOnGpu",gson.toJsonTree(onGpu));
            Files.writeString(output.resolve("result.json"),result+"\n");
        } catch(Exception error) { GreenMarine.LOGGER.error("Voxel probe result failed",error); }
        if(original!=null) ShaderRuntime.apply(original);
        if(originalMode!=null) {
            var server=client.getSingleplayerServer(); var uuid=client.player.getUUID();
            server.execute(() -> server.getPlayerList().getPlayer(uuid).setGameMode(originalMode));
            stage=8; ticks=0; failed=false;
        } else { client.disconnectWithSavingScreen(); client.stop(); }
    }
}
