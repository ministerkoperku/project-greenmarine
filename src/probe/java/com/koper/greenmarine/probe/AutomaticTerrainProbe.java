package com.koper.greenmarine.probe;

import com.google.gson.*;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.api.render.*;
import com.koper.greenmarine.client.*;
import com.koper.greenmarine.client.terrain.TerrainDrawBindings;
import com.koper.greenmarine.client.world.WorldPrograms;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import java.nio.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;

/** Ordinary world draws only: no direct capture configuration or replacement shader registration. */
final class AutomaticTerrainProbe {
    private static final AtomicReference<Object> RESULT=new AtomicReference<>();
    private static MarineRenderer.Registration registration;
    private static ShaderSettings saved;
    private static ShaderSettings imported;
    private static JsonObject accepted;
    private static int rejectionTicks;
    private static boolean selected,submitted,done,transparency;
    private static boolean savedHudHidden;
    private static int ticks;
    private static final float STAGE_PROOF=Float.parseFloat(System.getProperty("greenmarine.probe.world_stage_proof","0"));
    private static final boolean DEPTH_PROOF=Boolean.getBoolean("greenmarine.probe.fragment_depth");
    private static final boolean SAMPLED_DEPTH_PROOF=Boolean.getBoolean("greenmarine.probe.sampled_depth");
    static void tick(Minecraft client,Path output) {
        if(done || client.level==null || client.player==null)return;
        try {
            if(!selected) {
                saved=ShaderRuntime.settings();transparency=client.options.improvedTransparency().get();
                savedHudHidden=client.gui.hud.isHidden();
                if(SAMPLED_DEPTH_PROOF && !savedHudHidden)client.gui.hud.toggle();
                client.options.improvedTransparency().set(false);
                var pack=ResourcePacks.CATALOG.packs().stream().filter(p->p.definition().id().startsWith("iris_imported:native_")&&p.definition().terrain().enabled()).findFirst()
                    .orElseThrow(()->new IllegalStateException("Automatically converted terrain pack is missing"));
                imported=new ShaderSettings(pack.definition().id(),Quality.HIGH,RayMode.OFF,false);
                selected=true;ShaderRuntime.apply(imported);
                if(!GreenMarineClient.enabled())GreenMarineClient.toggleEffect();
                client.player.respawn();
                client.getSingleplayerServer().execute(()->{
                    var server=client.getSingleplayerServer();var level=server.overworld();
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"gamemode creative GreenMarineDev");
                    if(SAMPLED_DEPTH_PROOF)server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"kill @e[type=!minecraft:player]");
                    var player=server.getPlayerList().getPlayers().getFirst();
                    player.getAbilities().flying=true;player.onUpdateAbilities();
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"tp GreenMarineDev 0.5 194 12.5 180 60");
                    for(int x=-12;x<=12;x++)for(int z=-12;z<=12;z++) {
                        level.setBlock(new BlockPos(x,179,z),Blocks.STONE.defaultBlockState(),2);
                        for(int y=180;y<=182;y++)level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),2);
                    }
                    for(int x=-9;x<=-3;x++)for(int z=-7;z<=5;z++)level.setBlock(new BlockPos(x,180,z),Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT,true),2);
                    for(int x=3;x<=9;x++)for(int z=-7;z<=5;z++)level.setBlock(new BlockPos(x,180,z),Blocks.WATER.defaultBlockState(),2);
                });
                registration=MarineRenderer.register("probe:automatic_terrain",MarineRenderer.Stage.AFTER_PACKS,session->frame->capture(session,frame));
                return;
            }
            client.player.getAbilities().flying=true;client.player.setDeltaMovement(0,0,0);
            if(SAMPLED_DEPTH_PROOF)client.particleEngine.clearParticles();
            client.player.setPos(.5,194,12.5);client.player.setYRot(180);client.player.setXRot(60);
            if(accepted!=null) {
                if(++rejectionTicks<3)return;
                if(ShaderRuntime.status().state()!=PackStatus.State.FAILED || ShaderRuntime.script()!=null || TerrainCapture.configuration().enabled() || TerrainDrawBindings.enabled())
                    throw new IllegalStateException("Unsupported transparency was not rejected with full initialization cleanup: "+ShaderRuntime.status());
                if(SAMPLED_DEPTH_PROOF) {requireDepthReleased();accepted.addProperty("initializationFailureDepthReleased",true);}
                accepted.addProperty("initializationFailureCleanup",true);finish(client,output,accepted);return;
            }
            if(++ticks>500)throw new IllegalStateException("Automatic terrain probe timed out");
            if(ShaderRuntime.status().state()==PackStatus.State.FAILED)throw new IllegalStateException(ShaderRuntime.status().detail());
            if(RESULT.get() instanceof Throwable failure)throw new IllegalStateException("Generated world draw failed",failure);
            if(RESULT.get() instanceof JsonObject result) {
                accepted=result;ShaderRuntime.apply(saved);
                if(TerrainCapture.configuration().enabled() || TerrainDrawBindings.enabled())throw new IllegalStateException("Terrain capture survived pack switch");
                if(SAMPLED_DEPTH_PROOF) {requireDepthReleased();accepted.addProperty("packSwitchDepthReleased",true);}
                client.options.improvedTransparency().set(true);ShaderRuntime.apply(imported);
            }
        }catch(Throwable failure) {
            GreenMarine.LOGGER.error("Automatic terrain probe failed",failure);
            var result=new JsonObject();result.addProperty("status","failed");result.addProperty("error",failure.toString());
            if(failure.getCause()!=null)result.addProperty("cause",failure.getCause().toString());finish(client,output,result);
        }
    }
    private static void requireDepthReleased() {
        for(String source:new String[]{"world_depth","opaque_depth","opaque_no_hand_depth"}) {
            boolean available;
            try {available=PackRenderer.worldInput(source)!=null;}
            catch(IllegalStateException unavailable){available=false;}
            if(available)throw new IllegalStateException("Depth snapshot survived pack teardown: "+source);
        }
    }
    private static void capture(RenderSession session,FrameContext frame) {
        if(submitted || ticks<100 || WorldPrograms.draws()==0)return;
        submitted=true;
        try {
            if(SAMPLED_DEPTH_PROOF) {SampledDepthReadback.capture(session,frame,RESULT::set);return;}
            if(!TerrainCapture.configuration().enabled() || !TerrainDrawBindings.enabled() || TerrainDrawBindings.boundDraws()==0)
                throw new IllegalStateException("Manifest failed to activate actual terrain capture/draw bindings");
            var target=PackRenderer.worldInput("colortex0");
            if(target==null || target.texture().getFormat()!=GpuFormat.RGBA32_FLOAT)throw new IllegalStateException("Unexpected native terrain target");
            int width=frame.width(),pixels=width*frame.height();
            var readback=session.device().createBuffer(()->"Automatic terrain acceptance",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,(long)pixels*16);
            var samplers=PackRenderer.worldInput("colortex1");
            if(samplers==null || samplers.texture().getFormat()!=GpuFormat.RGBA32_FLOAT)throw new IllegalStateException("Independent sampler target is unavailable");
            var samplerReadback=session.device().createBuffer(()->"Independent terrain samplers",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,(long)pixels*16);
            var encoder=session.device().createCommandEncoder();
            var stageTexture=STAGE_PROOF==0&&!DEPTH_PROOF?null:PackRenderer.worldInput("colortex2");
            if(stageTexture==null && (STAGE_PROOF!=0 || DEPTH_PROOF) || stageTexture!=null && stageTexture.texture().getFormat()!=(DEPTH_PROOF?GpuFormat.RGBA32_FLOAT:GpuFormat.RGBA16_FLOAT))throw new IllegalStateException("Proof target is unavailable or has an unexpected format");
            var stageReadback=stageTexture==null?null:session.device().createBuffer(()->"World shader proof",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,(long)pixels*(DEPTH_PROOF?16:8));
            var hostDepth=DEPTH_PROOF?frame.texture("sceneDepth"):null;
            if(hostDepth!=null && hostDepth.texture().getFormat()!=GpuFormat.D32_FLOAT)throw new IllegalStateException("Unexpected host depth format");
            var depthReadback=hostDepth==null?null:session.device().createBuffer(()->"Actual host depth proof",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,(long)pixels*4);
            if(hostDepth!=null)encoder.copyTextureToBuffer(hostDepth.texture(),depthReadback,0,()->{},0);
            var fullscreenDepth=DEPTH_PROOF?PackRenderer.worldInput("colortex3"):null;
            if(DEPTH_PROOF && (fullscreenDepth==null||fullscreenDepth.texture().getFormat()!=GpuFormat.RGBA32_FLOAT))throw new IllegalStateException("Missing fullscreen depth proof");
            var fullscreenReadback=fullscreenDepth==null?null:session.device().createBuffer(()->"Fullscreen forward depth",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,(long)pixels*16);
            if(fullscreenDepth!=null)encoder.copyTextureToBuffer(fullscreenDepth.texture(),fullscreenReadback,0,()->{},0);
            if(stageTexture!=null)encoder.copyTextureToBuffer(stageTexture.texture(),stageReadback,0,()->{},0);
            encoder.copyTextureToBuffer(samplers.texture(),samplerReadback,0,()->{},0);
            encoder.copyTextureToBuffer(target.texture(),readback,0,()->{
                try(var mapped=readback.map(true,false);var sampled=samplerReadback.map(true,false)) {
                    var bytes=mapped.data().order(ByteOrder.nativeOrder());var textures=sampled.data().order(ByteOrder.nativeOrder());
                    int[] matched=new int[3];int bad=0,cutoutLeaks=0,atlasPixels=0,lightmapPixels=0,stagePixels=0,stageMismatches=0;
                    int depthPixels=0,depthMismatches=0,hostDepthPixels=0,hostDepthMismatches=0,returnPixels=0,fallthroughPixels=0;
                    int fullscreenPixels=0,fullscreenMismatches=0;
                    com.mojang.renderpearl.api.buffers.GpuBufferSlice.MappedView stageMapping=stageReadback==null?null:stageReadback.map(true,false);
                    com.mojang.renderpearl.api.buffers.GpuBufferSlice.MappedView depthMapping=depthReadback==null?null:depthReadback.map(true,false);
                    com.mojang.renderpearl.api.buffers.GpuBufferSlice.MappedView fullscreenMapping=fullscreenReadback==null?null:fullscreenReadback.map(true,false);
                    try {
                    var stageBytes=stageMapping==null?null:stageMapping.data().order(ByteOrder.nativeOrder());
                    var depthBytes=depthMapping==null?null:depthMapping.data().order(ByteOrder.nativeOrder());
                    var fullscreenBytes=fullscreenMapping==null?null:fullscreenMapping.data().order(ByteOrder.nativeOrder());
                    for(int pixel=0;pixel<pixels;pixel++) {
                        if(fullscreenBytes!=null) {
                            boolean exact=fullscreenBytes.getFloat(pixel*16)==.25f&&fullscreenBytes.getFloat(pixel*16+4)==.25f
                                &&fullscreenBytes.getFloat(pixel*16+8)==.375f&&fullscreenBytes.getFloat(pixel*16+12)==1;
                            if(exact)fullscreenPixels++;else fullscreenMismatches++;
                        }
                        float r=bytes.getFloat(pixel*16),g=bytes.getFloat(pixel*16+4),b=bytes.getFloat(pixel*16+8),a=bytes.getFloat(pixel*16+12);
                        if(!Float.isFinite(r)||!Float.isFinite(g)||!Float.isFinite(b)||!Float.isFinite(a)){bad++;continue;}
                        int variant=Math.round(g*4)-1;
                        if(variant==1 && Math.abs(g-.5f)<1e-6f && pixel%width%8<4 && Math.abs(r-73/255f)<1e-6f)cutoutLeaks++;
                        if(variant<0 || variant>2 || Math.abs(g-(variant+1)*.25f)>1e-6f || Math.abs(a-1)>1e-6f)continue;
                        float expected=new int[]{37,73,111}[variant]/255f;
                        if(Math.abs(r-expected)<1e-6f && b>0 && b<.02001f) {
                            matched[variant]++;
                            if(DEPTH_PROOF) {
                                float coordinate=stageBytes.getFloat(pixel*16),independent=stageBytes.getFloat(pixel*16+4),written=stageBytes.getFloat(pixel*16+8);
                                boolean returned=pixel%width%2==0;
                                float expectedWritten=coordinate+.00003f+(returned?0:.00002f);
                                if(Float.isFinite(coordinate)&&Float.isFinite(independent)&&Float.isFinite(written)
                                    &&coordinate>.9f&&coordinate<1&&Math.abs(coordinate-independent)<1e-6f&&Math.abs(written-expectedWritten)<1e-6f)depthPixels++;
                                else depthMismatches++;
                                if(returned)returnPixels++;else fallthroughPixels++;
                                // Translucent water uses the host pipeline's disabled depth writes.
                                if(variant<2) {
                                    float actual=depthBytes.getFloat(pixel*4);
                                    if(Float.isFinite(actual)&&Math.abs(actual-(1-written))<1e-6f)hostDepthPixels++;else hostDepthMismatches++;
                                }
                            } else if(stageBytes!=null){
                                // Both independent expected proof values are exactly representable as IEEE binary16.
                                int expectedBits=STAGE_PROOF==.5f?0x3800:STAGE_PROOF==.875f?0x3b00:-1;
                                if(expectedBits<0)throw new IllegalArgumentException("Unsupported fixture proof value");
                                if(Short.toUnsignedInt(stageBytes.getShort(pixel*8))==expectedBits)stagePixels++;else stageMismatches++;
                            }
                            float atlas=textures.getFloat(pixel*16+8),lightmap=textures.getFloat(pixel*16+12);
                            if(Float.isFinite(atlas)&&atlas>0&&atlas<=1)atlasPixels++;
                            if(Float.isFinite(lightmap)&&lightmap>0&&lightmap<=1)lightmapPixels++;
                        }
                    }
                    }finally{if(stageMapping!=null)stageMapping.close();if(depthMapping!=null)depthMapping.close();if(fullscreenMapping!=null)fullscreenMapping.close();}
                    var result=new JsonObject();result.addProperty("status","complete");result.addProperty("pixels",pixels);result.addProperty("nonfinitePixels",bad);
                    result.addProperty("solidPixels",matched[0]);result.addProperty("cutoutPixels",matched[1]);result.addProperty("waterPixels",matched[2]);
                    result.addProperty("boundDraws",TerrainDrawBindings.boundDraws());result.addProperty("worldDraws",WorldPrograms.draws());
                    result.addProperty("device",session.device().getDeviceInfo().name());
                    result.addProperty("externalConverterAssets",true);result.addProperty("manualCaptureConfiguration",false);
                    result.addProperty("cutoutLeaks",cutoutLeaks);result.addProperty("atlasPixels",atlasPixels);result.addProperty("lightmapPixels",lightmapPixels);
                    result.addProperty("stagePixels",stagePixels);result.addProperty("stageMismatches",stageMismatches);result.addProperty("expectedStageProof",STAGE_PROOF);
                    result.addProperty("depthPixels",depthPixels);result.addProperty("depthMismatches",depthMismatches);result.addProperty("hostDepthPixels",hostDepthPixels);result.addProperty("hostDepthMismatches",hostDepthMismatches);
                    result.addProperty("depthReturnPixels",returnPixels);result.addProperty("depthFallthroughPixels",fallthroughPixels);
                    result.addProperty("fullscreenDepthPixels",fullscreenPixels);result.addProperty("fullscreenDepthMismatches",fullscreenMismatches);
                    if(DEPTH_PROOF && (depthMismatches!=0||hostDepthMismatches!=0||depthPixels<100||hostDepthPixels<100||returnPixels<100||fallthroughPixels<100||fullscreenPixels!=pixels||fullscreenMismatches!=0))throw new IllegalStateException("Incomplete fragment depth output: "+result);
                    if(bad!=0 || cutoutLeaks!=0 || matched[0]<100 || matched[1]<100 || matched[2]<100 || atlasPixels<100 || lightmapPixels<100 || stageMismatches!=0 || (STAGE_PROOF!=0 && stagePixels<100))throw new IllegalStateException("Incomplete automatic terrain output: "+result);
                    RESULT.set(result);
                }catch(Throwable failure){RESULT.set(failure);}finally{readback.close();samplerReadback.close();if(stageReadback!=null)stageReadback.close();if(depthReadback!=null)depthReadback.close();if(fullscreenReadback!=null)fullscreenReadback.close();}
            },0);encoder.submit();
        }catch(Throwable failure){RESULT.set(failure);}
    }
    private static void finish(Minecraft client,Path output,JsonObject result) {
        done=true;
        if(registration!=null)registration.close();
        if(saved!=null)ShaderRuntime.apply(saved);
        if(result.get("status").getAsString().equals("complete")) {
            boolean released=!TerrainCapture.configuration().enabled()&&!TerrainDrawBindings.enabled();
            result.addProperty("packCaptureReleased",released);
            if(!released){result.addProperty("status","failed");result.addProperty("error","Capture remained active after pack switch");}
        }
        client.options.improvedTransparency().set(transparency);
        if(SAMPLED_DEPTH_PROOF && client.gui.hud.isHidden()!=savedHudHidden)client.gui.hud.toggle();
        try{Files.writeString(output.resolve("automatic-terrain-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));}
        catch(Exception failure){GreenMarine.LOGGER.error("Cannot save terrain acceptance result",failure);}
        client.stop();
    }
}
