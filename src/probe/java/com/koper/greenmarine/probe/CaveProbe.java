package com.koper.greenmarine.probe;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.client.*;
import com.koper.greenmarine.client.rt.RayWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Enclosed scratch cave: isolate sky leakage, indirect texture detail and reconstruction seams. */
final class CaveProbe {
    private static final List<Map<String,Float>> VARIANTS=List.of(Map.of(),Map.of("volumetric",0f),
        Map.of("giStrength",0f),Map.of("rayBlend",0f),Map.of("surfaceDiagnostic",6f),Map.of("surfaceDiagnostic",3f));
    private static final String[] NAMES={"all","no-volume","no-gi","no-rays","visibility","coverage"};
    private static final AtomicBoolean captured=new AtomicBoolean(true);
    private static volatile boolean built;
    private static BlockPos base;
    private static ShaderSettings saved;
    private static int ticks,index=-1,measurements;
    private static final List<Double> scanTimes=new ArrayList<>();
    private static java.lang.reflect.Method scan;
    static void tick(Minecraft client,Path output) {
        try {
            if(client.player==null)return;
            if(base==null) {
                if(Boolean.getBoolean("greenmarine.probe.cave.wood")
                    && client.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS).getTextureView().texture().getMipLevels()!=1)
                    throw new IllegalStateException("Wood GI fixture requires an actual zero-mip atlas");
                saved=ShaderRuntime.settings();base=Boolean.getBoolean("greenmarine.probe.cave.wood")?new BlockPos(306,120,86):client.player.blockPosition().atY(120);
                client.options.enableVsync().set(false);client.getWindow().setWindowed(1200,675);
                if(Boolean.getBoolean("greenmarine.probe.cave.wood"))client.options.framerateLimit().set(120);
                var server=client.getSingleplayerServer();var uuid=client.player.getUUID();var origin=base;
                server.execute(()->{
                    var player=server.getPlayerList().getPlayer(uuid);player.setGameMode(GameType.SPECTATOR);var level=player.level();
                    for(var command:List.of("time set 6000","weather clear","gamerule advance_time false"))
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),command);
                    for(int x=-8;x<=8;x++)for(int z=-8;z<=12;z++)for(int y=-2;y<=8;y++) {
                        boolean shell=x<-6||x>6||z<-6||z>10||y<0||y>6;
                        level.setBlock(origin.offset(x,y,z),(shell?Blocks.STONE:Blocks.AIR).defaultBlockState(),3);
                    }
                    for(int y=0;y<4;y++)for(int z=-1;z<=1;z++)level.setBlock(origin.offset(-2,y,z),Blocks.STONE.defaultBlockState(),3);
                    level.setBlock(origin.offset(2,0,-3),Blocks.TORCH.defaultBlockState(),3);
                    for(int x=-5;x<=-3;x++)for(int y=1;y<4;y++)level.setBlock(origin.offset(x,y,-6),Blocks.GLOW_LICHEN.defaultBlockState().setValue(BlockStateProperties.NORTH,true),3);
                    // Emission in many nearby sections exposes the old all-at-once scan cost.
                    for(int x=-32;x<=32;x+=16)for(int y=-32;y<=32;y+=16)for(int z=-32;z<=32;z+=16)
                        if(x*x+y*y+z*z<=36*36 && (Math.abs(x)>8||Math.abs(z)>12||y<-2||y>8))
                            level.setBlock(origin.offset(x,y,z),Blocks.GLOWSTONE.defaultBlockState(),3);
                    if(Boolean.getBoolean("greenmarine.probe.cave.wood")) {
                        for(int y=0;y<4;y++)for(int z=-1;z<=1;z++)level.setBlock(origin.offset(-2,y,z),Blocks.OAK_LOG.defaultBlockState(),3);
                        for(int x=-6;x<=6;x++)for(int y=0;y<=5;y++)level.setBlock(origin.offset(x,y,6),Blocks.GLOWSTONE.defaultBlockState(),3);
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"time set midnight");
                    } else if(Boolean.getBoolean("greenmarine.probe.cave.close"))player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TORCH));
                    built=true;
                });
                if(!GreenMarineClient.enabled())GreenMarineClient.toggleEffect();
                ShaderRuntime.apply(new ShaderSettings("greenmarine:solstice_rt",Quality.HIGH,RayMode.HARDWARE,true,Map.of()));return;
            }
            client.player.setDeltaMovement(0,0,0);client.player.setPos(base.getX()+.5,base.getY()+1.5,base.getZ()+7.5);
            client.player.setYRot(180);client.player.setYHeadRot(180);client.player.setXRot(8);
            if(Boolean.getBoolean("greenmarine.probe.cave.close")) {
                client.player.setPos(base.getX()-.75,base.getY()+1,base.getZ()+2.25);
                client.player.setYRot(135);client.player.setYHeadRot(135);client.player.setXRot(15);
            }
            if(Boolean.getBoolean("greenmarine.probe.cave.wood")) {
                var main=client.gameRenderer.mainRenderTarget();
                if(main.width!=1200 || main.height!=675) {
                    var w=client.getWindow();double sx=w.getWidth()/(double)w.getScreenWidth(),sy=w.getHeight()/(double)w.getScreenHeight();
                    w.setWindowed((int)Math.round(1200/sx),(int)Math.round(675/sy));return;
                }
            }
            if(!built||!captured.get())return;
            if(++ticks<(index<0?240:70)||!RayWorld.surfaceReady())return;
            if(measurements<30 && !Boolean.getBoolean("greenmarine.probe.cave.wood")) {
                var type=Class.forName("com.koper.greenmarine.client.rt.SurfaceLights");
                if(scan==null) {scan=type.getDeclaredMethod("scanBlocks",Minecraft.class,BlockPos.class);scan.setAccessible(true);}
                long start=System.nanoTime();scan.invoke(null,client,base);scanTimes.add((System.nanoTime()-start)/1e6);measurements++;return;
            }
            if(index>=0) {
                captured.set(false);var name=NAMES[index];
                var target=client.gameRenderer.mainRenderTarget();
                Files.writeString(output.resolve(name+"-timing.txt"),"capture="+target.width+"x"+target.height
                    +"\nCPU="+RayWorld.surfaceCpuTiming()+"\nGPU="+RayWorld.surfaceGpuTiming());
                Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
                    try {image.writeToFile(output.resolve(name+".png"));}catch(Exception e){GreenMarine.LOGGER.error("Cave capture",e);}
                    finally {image.close();captured.set(true);}
                });
            }
            if(++index>=(Boolean.getBoolean("greenmarine.probe.cave.wood")?3:VARIANTS.size())) {
                var sorted=scanTimes.stream().sorted().toList();
                Files.writeString(output.resolve("cave-result.txt"),"complete; camera="+base+"; scan ms="+scanTimes+"; median="+(sorted.isEmpty()?0:sorted.get(sorted.size()/2))+"; max="+(sorted.isEmpty()?0:sorted.getLast())+"\n"+RayWorld.surfaceDetail()+"\nCPU="+RayWorld.surfaceCpuTiming()+"\nGPU="+RayWorld.surfaceGpuTiming());
                ShaderRuntime.apply(saved);client.execute(()->{client.disconnectWithSavingScreen();client.stop();});return;
            }
            for(var name:List.of("volumetric","giStrength","rayBlend","surfaceDiagnostic")) {
                var p=ShaderRuntime.active().definition().parameters().stream().filter(v->v.name().equals(name)).findFirst().orElseThrow();
                ShaderRuntime.previewParameter(name,VARIANTS.get(index).getOrDefault(name,p.defaultValue()));
            }
            ticks=0;
        } catch(Exception error) {
            GreenMarine.LOGGER.error("Cave probe failed",error);
            try {Files.writeString(output.resolve("cave-result.txt"),"failed: "+error);}catch(Exception ignored){}
            client.stop();
        }
    }
}
