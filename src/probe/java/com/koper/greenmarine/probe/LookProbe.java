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

/** Look development probe: Solstice RT screenshots and FPS with each surface feature toggled. */
final class LookProbe {
    private static final String[] FEATURES={"volumetric","occlusion","specular","relief"};
    private record Variant(String name,float yaw,float pitch,Map<String,Float> parameters,double distance,int time) {
        Variant(String name,float yaw,float pitch,Map<String,Float> parameters,double distance) {this(name,yaw,pitch,parameters,distance,-1);}
        Variant(String name,float yaw,float pitch,Map<String,Float> parameters) {this(name,yaw,pitch,parameters,0);}
    }
    private static final boolean NEAR=Boolean.getBoolean("greenmarine.probe.look.near");
    private static final boolean ROOM=Boolean.getBoolean("greenmarine.probe.look.room");
    private static final boolean TIMES=Boolean.getBoolean("greenmarine.probe.look.times");
    private static final boolean DH=Boolean.getBoolean("greenmarine.probe.look.dh");
    private static final boolean TORCH=Boolean.getBoolean("greenmarine.probe.look.torch");
    private static final boolean MOUNTAIN=Boolean.getBoolean("greenmarine.probe.look.mountain");
    private static final boolean METALS=Boolean.getBoolean("greenmarine.probe.look.metals");
    private static final List<Variant> VARIANTS=new ArrayList<>();
    private static final AtomicBoolean captured=new AtomicBoolean(true);
    private static final com.google.gson.JsonObject result=new com.google.gson.JsonObject();
    private static ShaderSettings saved;
    private static int stage,ticks,variant,fpsTotal,fpsSamples;
    private static double x,y,z,dhOffset;

    static void tick(Minecraft client,Path output) {
        try {
            if(client.player==null)return;
            ticks++;
            if(stage==0) {
                if(ticks<60)return;
                saved=ShaderRuntime.settings();
                x=client.player.getX();y=client.player.getY()+1.5;z=client.player.getZ();
                client.options.enableVsync().set(false);client.options.framerateLimit().set(260);
                client.getWindow().setWindowed(1920,1080);
                var server=client.getSingleplayerServer();var uuid=client.player.getUUID();
                server.execute(()->{
                    var player=server.getPlayerList().getPlayer(uuid);player.setGameMode(DH || TORCH?GameType.CREATIVE:GameType.SPECTATOR);
                    for(String command:List.of("time set "+Integer.getInteger("greenmarine.probe.look.time",1200),"weather clear","gamerule advance_time false"))
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),command);
                });
                Map<String,Float> on=new LinkedHashMap<>(),off=new LinkedHashMap<>();
                for(String feature:FEATURES) {on.put(feature,1f);off.put(feature,0f);}
                if(METALS) {
                    // A 2-high wall of metal blocks 4 blocks north on a cleared stone platform high in the air.
                    var server8=client.getSingleplayerServer();int bx=(int)Math.floor(x),by=200,bz=(int)Math.floor(z);
                    server8.execute(()->{
                        var level=server8.overworld();
                        var blocks=new net.minecraft.world.level.block.Block[]{net.minecraft.world.level.block.Blocks.IRON_BLOCK,net.minecraft.world.level.block.Blocks.GOLD_BLOCK,
                            net.minecraft.world.level.block.Blocks.COPPER_BLOCK.weathering().unaffected(),net.minecraft.world.level.block.Blocks.NETHERITE_BLOCK,net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK};
                        for(int dx=-8;dx<=8;dx++)for(int dz=-8;dz<=8;dz++)level.setBlock(new net.minecraft.core.BlockPos(bx+dx,by-1,bz+dz),net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState(),2);
                        for(int i=0;i<blocks.length;i++)for(int dy=0;dy<2;dy++)for(int w=0;w<2;w++)
                            level.setBlock(new net.minecraft.core.BlockPos(bx-5+i*2+w,by+dy,bz-4),blocks[i].defaultBlockState(),2);
                        level.setBlock(new net.minecraft.core.BlockPos(bx+2,by,bz-1),net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK.defaultBlockState(),2);
                    });
                    VARIANTS.add(new Variant("metals-front",180,12,on,-5,4500));
                    VARIANTS.add(new Variant("metals-angle",215,8,on,-5,4500));
                    VARIANTS.add(new Variant("metals-sunset",180,12,on,-5,11800));
                    var pbrDebug=new LinkedHashMap<>(on);pbrDebug.put("surfaceDiagnostic",9f);
                    VARIANTS.add(new Variant("metals-pbr",215,8,pbrDebug,-5,4500));
                }
                if(MOUNTAIN) {
                    // Stone wall 40 wide, 80 tall, 80 blocks east (towards the morning sun): outside the traced scene.
                    var server7=client.getSingleplayerServer();int bx=(int)Math.floor(x)+80,by=(int)Math.floor(y)-20,bz=(int)Math.floor(z);
                    server7.execute(()->{
                        var level=server7.overworld();
                        for(int dy=0;dy<80;dy++)for(int dz=-20;dz<=20;dz++)
                            level.setBlock(new net.minecraft.core.BlockPos(bx,by+dy,bz+dz),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),2);
                    });
                    var shadowOnly=new LinkedHashMap<>(on);shadowOnly.put("surfaceDiagnostic",8f);
                    VARIANTS.add(new Variant("mountain-ground",90,35,on,-4,1500));
                    VARIANTS.add(new Variant("mountain-heightfield",90,35,shadowOnly,-4,1500));
                    VARIANTS.add(new Variant("mountain-towards",-90,10,on,-4,1500));
                }
                if(TORCH) {
                    // Oak plank box at dusk, torch in hand one block from the wall.
                    var server5=client.getSingleplayerServer();int bx=(int)Math.floor(x),by=(int)Math.floor(y)-1,bz=(int)Math.floor(z);var uuid5=client.player.getUUID();
                    server5.execute(()->{
                        var level=server5.overworld();
                        for(int dx=-4;dx<=4;dx++)for(int dy=-1;dy<=4;dy++)for(int dz=-4;dz<=4;dz++) {
                            boolean shell=Math.abs(dx)==4 || dy==-1 || dy==4 || Math.abs(dz)==4;
                            level.setBlock(new net.minecraft.core.BlockPos(bx+dx,by+dy,bz+dz),shell?net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState():net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),2);
                        }
                        var player=server5.getPlayerList().getPlayer(uuid5);
                        player.getInventory().setItem(player.getInventory().getSelectedSlot(),new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TORCH));
                    });
                    var noGi=new LinkedHashMap<>(on);noGi.put("giStrength",0f);
                    VARIANTS.add(new Variant("torch-gi",180,10,on,-3,13000));
                    VARIANTS.add(new Variant("torch-nogi",180,10,noGi,-3,13000));
                    VARIANTS.add(new Variant("torch-gi-turned",160,10,on,-3,13000));
                }
                if(DH) {
                    // Distant Horizons LOD brightness standing, walking (real movement, view bobbing) and standing again.
                    VARIANTS.add(new Variant("dh-stand",90,4,on,-1,6000));
                    VARIANTS.add(new Variant("dh-walk",90,4,on,-2,6000));
                    VARIANTS.add(new Variant("dh-stand-again",90,4,on,-1,6000));
                }
                if(TIMES) {
                    // Sun rises at +X (yaw -90) and sets at -X (yaw 90); noon and night look across.
                    int[] times={1000,6000,12400,13000,18000};String[] names={"morning","noon","sunset","dusk","night"};
                    for(int i=0;i<times.length;i++) {
                        VARIANTS.add(new Variant("time-"+names[i]+"-west",90,6,on,-1,times[i]));
                        VARIANTS.add(new Variant("time-"+names[i]+"-east",-90,6,on,-1,times[i]));
                    }
                    for(int mode=1;mode<=4;mode+=3) {
                        var weather=new LinkedHashMap<>(on);weather.put("weather",(float)mode);
                        VARIANTS.add(new Variant("weather"+mode+"-noon",90,6,weather,-1,6000));
                    }
                }
                if(ROOM) {
                    // Closed stone brick room with a 2x2 window facing the morning sun (+X).
                    var server3=client.getSingleplayerServer();int bx=(int)Math.floor(x),by=(int)Math.floor(y)-1,bz=(int)Math.floor(z);
                    server3.execute(()->{
                        var level=server3.overworld();
                        for(int dx=-5;dx<=5;dx++)for(int dy=-1;dy<=5;dy++)for(int dz=-5;dz<=5;dz++) {
                            boolean shell=Math.abs(dx)==5 || dy==-1 || dy==5 || Math.abs(dz)==5;
                            boolean window=dx==5 && (dy==2 || dy==3) && (dz==0 || dz==1);
                            level.setBlock(new net.minecraft.core.BlockPos(bx+dx,by+dy,bz+dz),shell && !window
                                ?net.minecraft.world.level.block.Blocks.STONE_BRICKS.defaultBlockState():net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),2);
                        }
                    });
                    for(int mode=0;mode<=1;mode++) {
                        var parameters=new LinkedHashMap<>(mode==0?on:off);
                        VARIANTS.add(new Variant("room-window"+(mode==0?"-on":"-off"),-120,5,parameters,-1));
                        VARIANTS.add(new Variant("room-side"+(mode==0?"-on":"-off"),180,25,parameters,-1));
                    }
                }
                if(NEAR) {
                    // A metal block straight ahead (yaw 0 looks toward +Z), seen from shrinking distances.
                    var server2=client.getSingleplayerServer();var block=net.minecraft.core.BlockPos.containing(Math.floor(x)+.5,Math.floor(y)+1.5,Math.floor(z)+3.5);
                    server2.execute(()->server2.overworld().setBlock(block,net.minecraft.world.level.block.Blocks.IRON_BLOCK.defaultBlockState(),3));
                    for(double distance:new double[]{2.5,1.2,0.6})
                        for(int mode:new int[]{0,1,6}) {
                            var parameters=new LinkedHashMap<>(on);parameters.put("surfaceDiagnostic",(float)mode);
                            VARIANTS.add(new Variant("near"+distance+"-d"+mode,0,0,parameters,distance));
                        }
                } else
                for(float yaw:new float[]{-90,0,90,180}) VARIANTS.add(new Variant("on-yaw"+(int)yaw,yaw,8,on));
                VARIANTS.add(new Variant("off-yaw-90",-90,8,off));
                for(String feature:FEATURES) {var only=new LinkedHashMap<>(on);only.put(feature,0f);VARIANTS.add(new Variant("no-"+feature+"-yaw-90",-90,8,only));}
                VARIANTS.add(new Variant("on-ground",-60,40,on));VARIANTS.add(new Variant("off-ground",-60,40,off));
                for(int mode=4;mode<=5;mode++) {var debug=new LinkedHashMap<>(on);debug.put("surfaceDiagnostic",(float)mode);VARIANTS.add(new Variant("debug"+mode+"-ground",-60,40,debug));}
                var reset=new LinkedHashMap<>(on);reset.put("surfaceDiagnostic",0f);VARIANTS.add(new Variant("on-final-yaw-90",-90,8,reset));
                if(NEAR || ROOM || TIMES || DH || TORCH || MOUNTAIN || METALS)VARIANTS.removeIf(v->v.distance()==0);
                if(DH) {
                    client.options.renderDistance().set(6);client.options.bobView().set(true);
                }
                ShaderRuntime.apply(new ShaderSettings("greenmarine:solstice_rt",Quality.valueOf(System.getProperty("greenmarine.probe.look.quality","MEDIUM")),RayMode.HARDWARE,true,saved.parameters()));
                stage=1;ticks=0;return;
            }
            var current=variant<VARIANTS.size()?VARIANTS.get(variant):null;
            float yaw=current==null?0:current.yaw(),pitch=current==null?0:current.pitch();
            double offset=current==null || current.distance()<=0?0:3-current.distance()-.5;
            if(METALS) {client.player.setPos(Math.floor(x)+.5,200,Math.floor(z)+.5);client.player.setYRot(yaw);client.player.setYHeadRot(yaw);client.player.setXRot(pitch);}
            else if(MOUNTAIN) {client.player.setPos(x,y+20,z);client.player.setYRot(yaw);client.player.setYHeadRot(yaw);client.player.setXRot(pitch);}
            else if(TORCH) {
                client.player.setPos(Math.floor(x)+.5,Math.floor(y)-1,Math.floor(z)-2.3);
                client.player.setYRot(yaw);client.player.setYHeadRot(yaw);client.player.setXRot(pitch);
            }
            else if(DH) {
                // High above the landscape, away from boxes earlier probes left; the walk variant moves west each tick.
                if(current!=null && current.distance()==-2 && stage==3) dhOffset+=.25;
                client.player.getAbilities().flying=true;client.player.setDeltaMovement(0,0,0);
                client.player.setPos(x-dhOffset,180,z);
                client.player.setYRot(yaw);client.player.setYHeadRot(yaw);client.player.setXRot(pitch);
            }
            else if(TIMES)client.player.setPos(x,y+12,z);
            else if(ROOM)client.player.setPos(Math.floor(x)-3.5,Math.floor(y)-1,Math.floor(z)+3.5);
            else if(NEAR)client.player.setPos(Math.floor(x)+.5,Math.floor(y)+.3,Math.floor(z)+.5+offset);else client.player.setPos(x,y,z);client.player.setYRot(yaw);client.player.setYHeadRot(yaw);client.player.setXRot(pitch);
            switch(stage) {
                case 1 -> {
                    if(ticks<200 || !ShaderRuntime.status().loaded() || RayWorld.surfaceFrames()<120)return;
                    result.add("status",new com.google.gson.Gson().toJsonTree(ShaderRuntime.status()));
                    stage=2;ticks=0;
                }
                case 2 -> {
                    if(current==null) {finish(client,output);return;}
                    current.parameters().forEach(ShaderRuntime::previewParameter);
                    if(current.time()>=0) {
                        var server4=client.getSingleplayerServer();int time=current.time();
                        server4.execute(()->server4.getCommands().performPrefixedCommand(server4.createCommandSourceStack(),"time set "+time));
                    }
                    fpsTotal=fpsSamples=0;stage=3;ticks=0;
                }
                case 3 -> {
                    if(ticks>60 && ticks%20==0) {fpsTotal+=client.getFps();fpsSamples++;}
                    if(ticks<160)return;
                    var entry=new com.google.gson.JsonObject();
                    entry.addProperty("fps",fpsSamples==0?0:(double)fpsTotal/fpsSamples);
                    entry.add("surfaceGpu",new com.google.gson.Gson().toJsonTree(RayWorld.surfaceGpuTiming()));
                    entry.addProperty("detail",RayWorld.surfaceDetail());
                    result.add(current.name(),entry);
                    capture(client,output,current.name()+".png");stage=4;
                }
                case 4 -> {if(!captured.get())return;variant++;stage=2;}
                default -> {}
            }
        } catch(Exception error) {
            GreenMarine.LOGGER.error("Look probe failed at stage "+stage,error);result.addProperty("error",error.toString());finish(client,output);
        }
    }
    private static void capture(Minecraft client,Path output,String name) {
        captured.set(false);
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
            try {image.writeToFile(output.resolve(name));}
            catch(Exception error) {GreenMarine.LOGGER.error("Look probe screenshot failed",error);}
            finally {image.close();captured.set(true);}
        });
    }
    private static void finish(Minecraft client,Path output) {
        if(stage==99)return;stage=99;
        if(saved!=null)ShaderRuntime.apply(saved);
        try {Files.writeString(output.resolve("look.json"),result+"\n");} catch(Exception error) {GreenMarine.LOGGER.error("Look probe result write failed",error);}
        client.execute(()->{client.disconnectWithSavingScreen();client.stop();});
    }
}
