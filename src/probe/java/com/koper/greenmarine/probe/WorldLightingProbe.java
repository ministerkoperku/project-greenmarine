package com.koper.greenmarine.probe;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.client.*;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameType;
import java.nio.file.*;
import java.util.*;

final class WorldLightingProbe {
    private static int stage,ticks;
    private static volatile boolean captured,failed;
    private static volatile GameType originalMode;
    private static ShaderSettings original;
    private static AutoCloseable hdrPack;
    private static long scansBeforeWall,worldScans;
    private static boolean hdrVerified,parametersVerified;
    private static PerformanceSamples.Snapshot gpu,cpu;
    private static Path output;
    private WorldLightingProbe() {}
    static void abort(Minecraft client,Path directory) { output=directory;failed=true;finish(client,"failed"); }
    static void tick(Minecraft client,Path directory) {
        output=directory;
        if(client.player==null) return;
        client.player.setYRot(0);client.player.setYHeadRot(0);client.player.setXRot(20);
        if(stage>0 && stage!=11 && stage!=12 && client.player.isSpectator()) {
            client.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            client.player.setPos(-38.5,67.05,54.5);
            var entities=new ArrayList<net.minecraft.world.entity.Entity>();
            client.level.entitiesForRendering().forEach(entities::add);
            for(var entity:entities) if(entity!=client.player) entity.discard();
        }
        if(failed) { finish(client,"failed");return; }
        if(++ticks<60) return;
        switch(stage) {
            case 0 -> {
                if(GreenMarine.PACKS.get("greenmarine:radiance").isEmpty()) {
                    failed=true;GreenMarine.LOGGER.error("World lighting fixture requires the Radiance pack");return;
                }
                original=ShaderRuntime.settings();
                try { original.save(output.resolve("original-shader-settings.json")); }
                catch(Exception error) {failed=true;return;}
                var server=client.getSingleplayerServer();var uuid=client.player.getUUID();
                server.execute(()->{var player=server.getPlayerList().getPlayer(uuid);
                    originalMode=System.getProperty("greenmarine.probe.restore_mode")==null?player.gameMode():
                        GameType.valueOf(System.getProperty("greenmarine.probe.restore_mode").toUpperCase(Locale.ROOT));
                    player.setGameMode(GameType.SPECTATOR);player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);player.teleportTo(-38.5,67.05,54.5);});
                stage=15;ticks=0;
            }
            case 15 -> {
                if(!client.player.isSpectator() || Math.abs(client.player.getX()+38.5)>0.1 || Math.abs(client.player.getZ()-54.5)>0.1) return;
                for(int x=-45;x<=-32;x++) for(int z=53;z<=66;z++) {
                    client.level.setBlock(new BlockPos(x,66,z),Blocks.SMOOTH_STONE.defaultBlockState(),3);
                    for(int y=67;y<=73;y++) client.level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
                }
                for(int x=-43;x<=-34;x++) for(int z=53;z<=59;z++)
                    client.level.setBlock(new BlockPos(x,71,z),Blocks.VERDANT_FROGLIGHT.defaultBlockState(),3);
                ShaderRuntime.apply(new ShaderSettings("greenmarine:radiance",Quality.HIGH,RayMode.VOXEL,true,Map.of("greenmarine:radiance",Map.of("gain",4f))));
                stage=16;ticks=0;
                client.getWindow().setWindowed(800,600);
                client.reloadResourcePacks().whenComplete((ignored,error)->{
                    if(error!=null) failed=true;captured=true;
                });
            }
            case 16 -> {
                if(!captured || !VoxelWorld.ready()) return;
                stage=1;ticks=0;
            }
            case 1 -> {
                if(!client.player.isSpectator() || !VoxelWorld.ready() || PackRenderer.sceneFrames()<80) return;
                gpu=PackRenderer.gpuTiming();cpu=PackRenderer.cpuTiming();
                captured=false;capture(client,"emitter-on.png");stage=2;
            }
            case 2 -> { if(captured) {GreenMarineClient.toggleEffect();stage=3;ticks=0;} }
            case 3 -> { captured=false;capture(client,"emitter-off.png");stage=4; }
            case 4 -> {
                if(!captured) return;
                GreenMarineClient.toggleEffect();scansBeforeWall=VoxelWorld.scans();
                for(int x=-55;x<=-24;x++) for(int z=38;z<=69;z++)
                    client.level.setBlock(new BlockPos(x,70,z),Blocks.STONE.defaultBlockState(),3);
                stage=5;ticks=0;
            }
            case 5 -> {
                if(VoxelWorld.scans()<scansBeforeWall+2) return;
                captured=false;capture(client,"emitter-blocked.png");stage=6;
            }
            case 6 -> {
                if(!captured) return;
                worldScans=VoxelWorld.scans();
                GreenMarineClient.toggleEffect();stage=13;ticks=0;
            }
            case 13 -> { captured=false;capture(client,"emitter-blocked-off.png");stage=14; }
            case 14 -> {
                if(!captured) return;
                GreenMarineClient.toggleEffect();
                var passes=List.of(new PackPass("hdr",PackPass.Stage.SCENE,"greenmarine:probe/hdr",null,
                    PackPass.Requirement.NONE,Quality.LOW,1,null,PackPass.Format.RGBA16F),
                    new PackPass("scale",PackPass.Stage.SCENE,"greenmarine:probe/scale",null,PackPass.Requirement.NONE,Quality.LOW,1,null));
                var definition=new ShaderPack("greenmarine:hdr_fixture","HDR fixture",null,List.of(),passes);
                String header="#version 330\n#extension GL_ARB_separate_shader_objects : require\nlayout(location=0) in vec2 texCoord;\nlayout(location=0) out vec4 fragColor;\n";
                hdrPack=GreenMarine.PACKS.register(new PackCatalog.LoadedPack(definition,"function update(f) end",Map.of(
                    "greenmarine:probe/hdr",header+"void main(){fragColor=vec4(4.0,2.0,1.0,1.0);}",
                    "greenmarine:probe/scale",header+"uniform sampler2D ColorSampler;uniform sampler2D VoxelMaterials;"
                        +"layout(std140) uniform VoxelFrame{vec4 VoxelOriginSize;vec4 VoxelTracing;uvec4 VoxelOccupancy[256];};"
                        +"void main(){vec3 extra=vec3(VoxelTracing.x)+texelFetch(VoxelMaterials,ivec2(0),0).rgb;"
                        +"fragColor=vec4(texture(ColorSampler,texCoord).rgb*0.125+extra,1.0);}")));
                ShaderRuntime.apply(new ShaderSettings(definition.id(),Quality.LOW,RayMode.OFF,false));stage=7;ticks=0;
            }
            case 7 -> { captured=false;capture(client,"hdr.png");stage=8; }
            case 8 -> {
                if(!captured) return;
                removeHdr();ShaderRuntime.apply(new ShaderSettings("greenmarine:radiance",Quality.MEDIUM,RayMode.OFF,false));stage=9;ticks=0;
            }
            case 9 -> {
                if(ShaderRuntime.script()==null) return;
                var script=ShaderRuntime.script();float before=script.value("gain");
                client.gui.setScreen(new ShaderScreen());
                for(var child:client.gui.screen().children()) if(child instanceof Button button && button.getMessage().getString().equals("Pack parameters")) {
                    button.onPress(new KeyEvent(InputConstants.KEY_RETURN,0,0));break;
                }
                if(!(client.gui.screen() instanceof ParameterScreen)) {failed=true;return;}
                for(var child:client.gui.screen().children()) if(child instanceof AbstractSliderButton slider) {
                    slider.keyPressed(new KeyEvent(InputConstants.KEY_RETURN,0,0));
                    slider.keyPressed(new KeyEvent(InputConstants.KEY_RIGHT,0,0));break;
                }
                if(ShaderRuntime.script()!=script || script.value("gain")<=before) {
                    failed=true;GreenMarine.LOGGER.error("Parameter slider failed or restarted the script");return;
                }
                captured=false;capture(client,"parameters.png");stage=10;
            }
            case 10 -> {
                if(!captured) return;
                client.gui.screen().onClose();
                var saved=ShaderSettings.load(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("greenmarine-shaders.json"),ShaderSettings.defaults());
                if(!saved.parameters().getOrDefault("greenmarine:radiance",Map.of()).containsKey("gain")) {failed=true;return;}
                parametersVerified=true;GreenMarine.LOGGER.info("Parameter slider live update/persistence without script restart passed");
                finish(client,"complete");
            }
            case 11 -> {
                if(originalMode!=null && client.player.isSpectator()!=(originalMode==GameType.SPECTATOR)) return;
                client.disconnectWithSavingScreen();client.stop();stage=12;
            }
            default -> {}
        }
    }
    private static void capture(Minecraft client,String name) {
        if(name.startsWith("emitter")) {
            var nearby=new java.util.ArrayList<String>();
            for(var entity:client.level.entitiesForRendering()) if(entity!=client.player && entity.distanceToSqr(client.player)<25)
                nearby.add(entity.getType()+" at "+entity.position());
            var camera=client.gameRenderer.mainCamera().position();
            if(Math.abs(camera.x+38.5)>0.01 || Math.abs(camera.z-54.5)>0.01) {
                failed=true;GreenMarine.LOGGER.error("Fixture camera left its fixed anchor: {}",camera);return;
            }
            GreenMarine.LOGGER.info("World fixture capture camera={}, nearby entities={}",camera,nearby);
        }
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
            try(image) {
                if(name.equals("hdr.png")) {
                    int pixel=image.getPixel(image.getWidth()/2,image.getHeight()/2);
                    int red=(pixel>>>16)&255,green=(pixel>>>8)&255,blue=pixel&255;
                    hdrVerified=red>116 && red<140 && green>52 && green<76 && blue>20 && blue<44;
                    GreenMarine.LOGGER.info("HDR fixture center RGB=({}, {}, {}), preserved above-one input={}",red,green,blue,hdrVerified);
                    if(!hdrVerified) failed=true;
                }
                image.writeToFile(output.resolve(name));GreenMarine.LOGGER.info("World lighting capture: {}",name);
            } catch(Exception error) {failed=true;GreenMarine.LOGGER.error("World lighting capture failed",error);}
            finally {captured=true;}
        });
    }
    private static void removeHdr() {if(hdrPack!=null) {try {hdrPack.close();} catch(Exception error){throw new RuntimeException(error);}hdrPack=null;}}
    private static void finish(Minecraft client,String status) {
        if(stage==11 || stage==12) return;
        try {
            var result=new com.google.gson.JsonObject();result.addProperty("status",status);
            result.addProperty("hdrVerified",hdrVerified);result.addProperty("parametersVerified",parametersVerified);
            result.addProperty("voxelScans",worldScans);var gson=new com.google.gson.Gson();
            result.add("sceneGpu",gson.toJsonTree(gpu));result.add("sceneCpu",gson.toJsonTree(cpu));
            Files.writeString(output.resolve("result.json"),result+"\n");
        } catch(Exception error) {GreenMarine.LOGGER.error("World lighting result failed",error);}
        removeHdr();if(original!=null) ShaderRuntime.apply(original);
        client.gui.setScreen(null);
        if(originalMode!=null) {
            var server=client.getSingleplayerServer();var uuid=client.player.getUUID();
            server.execute(()->server.getPlayerList().getPlayer(uuid).setGameMode(originalMode));
            stage=11;ticks=0;failed=false;
        } else {client.disconnectWithSavingScreen();client.stop();stage=12;}
    }
}
