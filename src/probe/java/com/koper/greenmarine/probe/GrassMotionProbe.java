package com.koper.greenmarine.probe;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.client.*;
import com.koper.greenmarine.client.rt.RayWorld;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import org.joml.Matrix4f;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Static cutout casters; deterministic motion and host bobbing at identical poses per quality. */
public final class GrassMotionProbe {
    private static final String[] MODES={"high","ultra","high-direct","high-coverage","high-visibility","high-no-history"};
    private static final int POSES=8;
    private static final AtomicBoolean captured=new AtomicBoolean(true);
    private static final List<Map<String,Object>> rows=new ArrayList<>();
    private static volatile boolean built;
    private static ShaderSettings saved;
    private static BlockPos base;
    private static int mode,pose,ticks;
    private static float[] projection,view;
    private static double[] camera;
    public static void camera(GameRenderer renderer) {
        if(base==null)return;
        var state=renderer.gameRenderState();var entity=state.levelRenderState.cameraRenderState.entityRenderState;
        state.optionsRenderState.bobView=true;entity.isPlayer=true;
        entity.bob=.05f;entity.backwardsInterpolatedWalkDistance=pose*.13f;
        state.levelRenderState.skyRenderState.sunAngle=-.65f;
        state.levelRenderState.skyRenderState.rainBrightness=1;
        if(MODES[mode].equals("high-no-history"))PackRenderer.invalidateHistory();
    }
    public static void projection(GameRenderer renderer,Matrix4f matrix) {
        if(base==null)return;
        projection=matrix.get(new float[16]);var c=renderer.gameRenderState().levelRenderState.cameraRenderState;
        view=c.viewRotationMatrix.get(new float[16]);camera=new double[]{c.pos.x,c.pos.y,c.pos.z};
    }
    static void tick(Minecraft client,Path output) {
        try {
            if(client.player==null)return;
            client.getFramerateLimitTracker().onInputReceived();
            if(base==null) {
                saved=ShaderRuntime.settings();base=new BlockPos(306,140,86);
                client.options.enableVsync().set(false);client.options.framerateLimit().set(120);
                client.getWindow().setWindowed(1200,675);
                var server=client.getSingleplayerServer();var uuid=client.player.getUUID();var origin=base;
                server.execute(()->{
                    var player=server.getPlayerList().getPlayer(uuid);player.setGameMode(GameType.SPECTATOR);var level=player.level();
                    for(var command:List.of("time set 2500","weather clear","gamerule advance_time false"))server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),command);
                    for(int x=-14;x<=14;x++)for(int z=-12;z<=14;z++)for(int y=-1;y<=5;y++)level.setBlock(origin.offset(x,y,z),(y<0?Blocks.SMOOTH_STONE:Blocks.AIR).defaultBlockState(),3);
                    for(int x=-6;x<=6;x+=2)for(int z=-6;z<=6;z+=2)level.setBlock(origin.offset(x,0,z),Blocks.SHORT_GRASS.defaultBlockState(),3);
                    for(int y=0;y<3;y++)level.setBlock(origin.offset(4,y,-2),Blocks.STONE.defaultBlockState(),3);
                    built=true;
                });
                if(!GreenMarineClient.enabled())GreenMarineClient.toggleEffect();select();return;
            }
            client.player.setDeltaMovement(0,0,0);client.player.setPos(base.getX()+.5+pose*.035,base.getY()+3,base.getZ()+10.5);
            client.player.setYRot(180);client.player.setYHeadRot(180);client.player.setXRot(32);
            if(!built||!captured.get()||!RayWorld.surfaceReady())return;
            var main=client.gameRenderer.mainRenderTarget();
            if(main.width!=1200||main.height!=675) {
                var w=client.getWindow();double sx=w.getWidth()/(double)w.getScreenWidth(),sy=w.getHeight()/(double)w.getScreenHeight();
                w.setWindowed((int)Math.round(1200/sx),(int)Math.round(675/sy));return;
            }
            if(++ticks<(pose==0?mode==0?180:45:6))return;
            var row=new LinkedHashMap<String,Object>();var name=MODES[mode]+"-"+pose;
            row.put("name",name);row.put("projection",projection.clone());row.put("view",view.clone());row.put("camera",camera.clone());
            row.put("floorY",base.getY());row.put("base",new int[]{base.getX(),base.getY(),base.getZ()});
            row.put("surfaceCpu",RayWorld.surfaceCpuTiming());row.put("surfaceGpu",RayWorld.surfaceGpuTiming());rows.add(row);
            captured.set(false);
            Screenshot.takeScreenshot(main,image->{
                try {image.writeToFile(output.resolve(name+".png"));}catch(Exception e){GreenMarine.LOGGER.error("Grass capture",e);}
                finally {image.close();captured.set(true);}
            });
            ticks=0;
            if(++pose==POSES) {
                pose=0;
                if(++mode==MODES.length) {
                    mode=0;Files.writeString(output.resolve("grass-result.json"),new Gson().toJson(Map.of("status","complete","frames",rows)));
                    ShaderRuntime.apply(saved);client.execute(()->{client.disconnectWithSavingScreen();client.stop();});return;
                }
                select();
            }
        } catch(Exception error) {
            GreenMarine.LOGGER.error("Grass motion probe failed",error);
            try {Files.writeString(output.resolve("grass-result.json"),new Gson().toJson(Map.of("status","failed","error",error.toString())));}catch(Exception ignored){}
            client.stop();
        }
    }
    private static void select() {
        Map<String,Float> overrides=mode==2?Map.of("giStrength",0f,"volumetric",0f):mode==3?Map.of("surfaceDiagnostic",3f):mode==4?Map.of("surfaceDiagnostic",6f):Map.of();
        ShaderRuntime.apply(new ShaderSettings("greenmarine:solstice_rt",mode==1?Quality.ULTRA:Quality.HIGH,RayMode.HARDWARE,true,Map.of("greenmarine:solstice_rt",overrides)));
    }
}
