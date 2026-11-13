package com.koper.greenmarine.probe;

import com.google.gson.*;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.api.render.BlockRayModels;
import com.koper.greenmarine.client.*;
import com.koper.greenmarine.client.rt.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.nio.*;
import java.nio.file.*;

/** Actual Mechanics Dream attachment -> optional adapter -> scanner triangles -> Vulkan Solstice. */
final class MicroGridProbe {
    private static int stage,ticks;
    private static BlockPos base;
    private static ShaderSettings saved;
    private static WorldSceneScanner scanner;
    private static final JsonObject RESULT=new JsonObject();
    private static boolean done;
    private static final java.util.concurrent.atomic.AtomicBoolean captured=new java.util.concurrent.atomic.AtomicBoolean(true);
    static void tick(Minecraft client,Path output) {
        if(done)return;
        try {
            if(++ticks>1500)throw new IllegalStateException("Microgrid probe timed out at stage "+stage);
            if(stage==0) {
                if(!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("mechanics_dream"))throw new IllegalStateException("Actual Mechanics Dream fixture is required");
                base=client.player.blockPosition().atY(180);saved=ShaderRuntime.settings();
                if(!GreenMarineClient.enabled())GreenMarineClient.toggleEffect();
                ShaderRuntime.apply(new ShaderSettings("greenmarine:solstice_rt",Quality.HIGH,RayMode.HARDWARE,true));
                client.getSingleplayerServer().execute(()->{
                    try {
                        var level=client.getSingleplayerServer().overworld();
                        var server=client.getSingleplayerServer();server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"time set noon");
                        for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)for(int y=-1;y<=3;y++)
                            level.setBlockAndUpdate(base.offset(x,y,z),y==-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());
                        level.setBlockAndUpdate(base.offset(-2,0,0),Blocks.STONE.defaultBlockState());
                        var block=BuiltInRegistries.BLOCK.getValue(Identifier.parse("mechanics_dream:scrap_wood_4"));
                        if(block==Blocks.AIR)throw new IllegalStateException("Missing actual microgrid block");
                        level.setBlockAndUpdate(base,block.defaultBlockState());
                        var grid=Class.forName("com.koper.mechanics_dream.MicroGridData$Grid").getConstructor().newInstance();
                        var add=grid.getClass().getMethod("add",int.class,int.class,int.class,int.class,BlockState.class);
                        if(!(boolean)add.invoke(grid,0,0,0,1,Blocks.STONE.defaultBlockState())
                            || !(boolean)add.invoke(grid,3,0,0,1,Blocks.GLOWSTONE.defaultBlockState()))throw new IllegalStateException("Cannot build microgrid fixture");
                        Class.forName("com.koper.mechanics_dream.MicroGridData").getMethod("set",net.minecraft.world.level.Level.class,BlockPos.class,grid.getClass()).invoke(null,level,base,grid);
                    }catch(Throwable error){synchronized(RESULT){RESULT.addProperty("serverError",error.toString());}}
                });
                stage=1;ticks=0;return;
            }
            synchronized(RESULT){if(RESULT.has("serverError"))throw new IllegalStateException(RESULT.get("serverError").getAsString());}
            client.player.setPos(base.getX()+2.5,base.getY()+1,base.getZ()+3.5);client.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            client.player.setYRot(145);client.player.setYHeadRot(145);client.player.setXRot(22);
            if(stage==1) {
                var parts=BlockRayModels.parts(client.level,base,client.level.getBlockState(base),null);
                if(parts==null || parts.size()!=2)return;
                if(parts.get(1).material().getLightEmission()!=15 || parts.get(1).scale()!=.25f || parts.get(1).x()!=.75f)
                    throw new IllegalStateException("Incorrect actual microgrid adapter output: "+parts);
                RESULT.addProperty("actualParts",parts.size());
                int x=Math.floorDiv(base.getX(),16)*16-32,z=Math.floorDiv(base.getZ(),16)*16-32,y=Math.floorDiv(base.getY(),16)*16-16;
                scanner=new WorldSceneScanner();scanner.begin(new SceneBounds(x,y,z),base.getX(),base.getY(),base.getZ());stage=2;ticks=0;
            }
            if(stage==2 && scanner.step(client,8192,4_000_000)) {
                int triangles=0;float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
                for(var section:scanner.snapshot().sections()) {
                    if(section.key().x()!=Math.floorDiv(base.getX(),16)||section.key().y()!=Math.floorDiv(base.getY(),16)||section.key().z()!=Math.floorDiv(base.getZ(),16))continue;
                    var data=section.mesh().data().order(ByteOrder.nativeOrder());
                    for(int at=0;at<data.remaining();at+=TriangleScene.TRIANGLE_BYTES)if(data.getFloat(at+76)==15) {
                        triangles++;
                        for(int vertex=0;vertex<3;vertex++) {
                            float x=data.getFloat(at+vertex*16)-Math.floorMod(base.getX(),16);
                            min=Math.min(min,x);max=Math.max(max,x);
                            float y=data.getFloat(at+vertex*16+4)-Math.floorMod(base.getY(),16);
                            float z=data.getFloat(at+vertex*16+8)-Math.floorMod(base.getZ(),16);
                            if(x<.74999f||x>1.00001f||y<-.00001f||y>.25001f||z<-.00001f||z>.25001f)
                                throw new IllegalStateException("Micro emitter expanded into a container cube");
                        }
                    }
                }
                if(triangles!=12)throw new IllegalStateException("Expected 12 emitting quarter-cube triangles, got "+triangles);
                RESULT.addProperty("emittingTriangles",triangles);RESULT.addProperty("emitterMinX",min);RESULT.addProperty("emitterMaxX",max);
                stage=3;ticks=0;
            }
            if(stage==3 && ticks>100 && RayWorld.surfaceReady()) {
                RESULT.addProperty("surfaceFrames",RayWorld.surfaceFrames());RESULT.addProperty("status","complete");
                RESULT.addProperty("skyAtContainer",client.level.getBrightness(net.minecraft.world.level.LightLayer.SKY,base));
                RESULT.addProperty("skyAbove",client.level.getBrightness(net.minecraft.world.level.LightLayer.SKY,base.above()));
                capture(client,output,"microgrid.png");stage=4;ticks=0;return;
            }
            if(stage==4 && captured.get()) {ShaderRuntime.previewParameter("rayBlend",0);stage=5;ticks=0;return;}
            if(stage==5 && ticks>40) {capture(client,output,"no-rays.png");stage=6;return;}
            if(stage==6 && captured.get()) {ShaderRuntime.previewParameter("rayBlend",1);ShaderRuntime.previewParameter("surfaceDiagnostic",2);stage=7;ticks=0;return;}
            if(stage==7 && ticks>40) {capture(client,output,"albedo.png");stage=8;return;}
            if(stage==8 && captured.get()) {ShaderRuntime.previewParameter("surfaceDiagnostic",5);stage=9;ticks=0;return;}
            if(stage==9 && ticks>40) {capture(client,output,"sun.png");stage=10;return;}
            if(stage==10 && captured.get()) {ShaderRuntime.previewParameter("surfaceDiagnostic",10);stage=11;ticks=0;return;}
            if(stage==11 && ticks>40) {capture(client,output,"lighting-inputs.png");stage=12;return;}
            if(stage==12 && captured.get()) {ShaderRuntime.previewParameter("surfaceDiagnostic",3);stage=13;ticks=0;return;}
            if(stage==13 && ticks>40) {capture(client,output,"coverage.png");stage=14;return;}
            if(stage==14 && captured.get()) {
                finish(client,output);
            }
        }catch(Throwable error){GreenMarine.LOGGER.error("Microgrid probe failed",error);RESULT.addProperty("status","failed");RESULT.addProperty("error",error.toString());finish(client,output);}
    }
    private static void capture(Minecraft client,Path output,String name) {
        captured.set(false);
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{
            try {image.writeToFile(output.resolve(name));}catch(Exception error){GreenMarine.LOGGER.error("Microgrid capture failed",error);}finally{image.close();captured.set(true);}
        });
    }
    private static void finish(Minecraft client,Path output) {
        done=true;
        try{Files.writeString(output.resolve("microgrid-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(RESULT));}
        catch(Exception error){GreenMarine.LOGGER.error("Cannot save microgrid evidence",error);}
        if(saved!=null)ShaderRuntime.apply(saved);client.stop();
    }
}
