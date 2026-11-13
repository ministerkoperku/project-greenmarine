package com.koper.greenmarine.probe;

import com.google.gson.*;
import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.render.*;
import com.koper.greenmarine.client.terrain.*;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real mesher records and host Vulkan buffer readback, without replacing terrain shaders. */
final class TerrainAttributesProbe {
    private static final AtomicReference<Object> RESULT=new AtomicReference<>();
    private static final Set<String> SECTIONS=new HashSet<>();
    private static final List<Object> VERIFIED_MESHES=new ArrayList<>();
    private static MarineRenderer.Registration registration;
    private static int ticks,vertices,fluids,slopes,gpuBytes,tinted,occluded,emissive,lava;
    private static boolean done;
    private static final boolean DRAW_BINDINGS=Boolean.getBoolean("greenmarine.probe.terrain_draws");
    private static long hostBoundDraws;
    static void tick(Minecraft client,Path output) {
        if(done)return;
        try {
            if(registration==null) {
                System.setProperty("greenmarine.terrain.diagnostics","true");
                
                TerrainCapture.configure(TerrainCapture.MappingSnapshot.capture(state->7000+BuiltInRegistries.BLOCK.getId(state.getBlock())),TerrainCapture.Profile.IRIS_SEPARATE_AO);
                long generation=TerrainCapture.configuration().generation();
                client.levelRenderer.invalidateCompiledGeometry(client.level,client.options,client.gameRenderer.mainCamera(),client.getBlockColors());
                boolean invalidated=TerrainCapture.configuration().generation()!=generation;
                generation=TerrainCapture.configuration().generation();com.koper.greenmarine.client.ShaderRuntime.reset();
                boolean packInvalidated=TerrainCapture.configuration().generation()!=generation;
                if(DRAW_BINDINGS)TerrainDrawBindings.configure(true);
                var compiler=new net.minecraft.client.renderer.chunk.SectionCompiler(true,true,null,null,client.getBlockColors());
                try{compiler.compile(net.minecraft.core.SectionPos.of(0,11,0),null,null,null);throw new IllegalStateException("Invalid compile unexpectedly succeeded");}
                catch(NullPointerException expected){}
                var field=TerrainMesher.class.getDeclaredField("BUILD");field.setAccessible(true);
                boolean cleaned=((ThreadLocal<?>)field.get(null)).get()==null;
                if(!invalidated || !cleaned || !packInvalidated)throw new IllegalStateException("Terrain lifecycle hooks incomplete: compilerGeneration="+invalidated+", exceptionCleanup="+cleaned+", packGeneration="+packInvalidated);
                client.player.respawn();
                client.getSingleplayerServer().execute(()->{
                    var server=client.getSingleplayerServer();
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"gamemode creative GreenMarineDev");
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"tp GreenMarineDev 0.5 184 3.5");
                    var level=server.overworld();
                    for(int x=-4;x<=4;x++)for(int z=-4;z<=4;z++) {
                        level.setBlockAndUpdate(new BlockPos(x,179,z),Blocks.STONE.defaultBlockState());
                        for(int y=180;y<=182;y++)level.setBlockAndUpdate(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState());
                    }
                    level.setBlockAndUpdate(new BlockPos(-1,180,-1),Blocks.WATER.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(0,180,-1),Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL,5));
                    level.setBlockAndUpdate(new BlockPos(1,180,1),Blocks.GRASS_BLOCK.defaultBlockState());
                    if(DRAW_BINDINGS){
                        level.setBlockAndUpdate(new BlockPos(-2,180,1),Blocks.GLOWSTONE.defaultBlockState());
                        for(var wall:List.of(new BlockPos(2,180,3),new BlockPos(4,180,3),new BlockPos(3,180,2),new BlockPos(3,180,4)))level.setBlockAndUpdate(wall,Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(3,180,3),Blocks.LAVA.defaultBlockState());
                    }
                });
                client.levelRenderer.resetLevelRenderData();
                registration=MarineRenderer.register("probe:terrain_attributes",MarineRenderer.Stage.AFTER_PACKS,session->frame->capture(session,client));
                return;
            }
            if(++ticks>240)throw new IllegalStateException("No complete terrain capture: sections="+SECTIONS+", vertices="+vertices+", fluids="+fluids+", slopes="+slopes+", captured="+TerrainMesher.capturedSections()+", rejected="+TerrainMesher.rejectedSections());
            if(RESULT.get() instanceof Throwable failure)throw new IllegalStateException("Terrain attributes failed",failure);
            if(RESULT.get() instanceof JsonObject result)finish(client,output,result);
        }catch(Throwable failure) {
            GreenMarine.LOGGER.error("Terrain attributes probe failed",failure);
            var result=new JsonObject();result.addProperty("status","failed");result.addProperty("error",failure.toString());finish(client,output,result);
        }
    }
    private static void capture(RenderSession session,Minecraft client) {
        if(ticks<60 || RESULT.get()!=null)return;
        if(DRAW_BINDINGS && TerrainDrawBindings.boundDraws()==0)return;
        if(DRAW_BINDINGS)hostBoundDraws=TerrainDrawBindings.boundDraws();
        try {
            for(var mesh:TerrainSidecars.diagnosticMeshes()) {
                var data=TerrainSidecars.data(mesh);
                if(data.sectionY()!=11 || data.sectionX()<-1 || data.sectionX()>0 || data.sectionZ()<-1 || data.sectionZ()>0)continue;
                for(var entry:data.layers().entrySet()) {
                    var layer=entry.getValue();var records=layer.records();var host=layer.hostVertices();
                    if(host.remaining()!=layer.vertexCount()*28)throw new IllegalStateException("Host vertex count differs from captured records");
                    for(int quad=0;quad<layer.vertexCount();quad+=4) {
                        float u=0,v=0;
                        for(int k=0;k<4;k++){u+=host.getFloat((quad+k)*28+16)*.25f;v+=host.getFloat((quad+k)*28+20)*.25f;}
                        for(int k=0;k<4;k++) {
                            int at=(quad+k)*32,h=(quad+k)*28,mid=records.getInt(at+12);
                            if(Math.abs(records.getFloat(at+16)-u)>1e-6f || Math.abs(records.getFloat(at+20)-v)>1e-6f)throw new IllegalStateException("UV midpoint or vertex order differs");
                            int[] local=new int[3];
                            for(int axis=0;axis<3;axis++) {
                                float block=host.getFloat(h+axis*4)+(byte)(mid>>>(axis*8))/64f-.5f;
                                local[axis]=Math.round(block);
                                if(Math.abs(block-local[axis])>1f/64+1e-5f)throw new IllegalStateException("Block midpoint differs from emitted host vertex");
                            }
                            var pos=new BlockPos(data.sectionX()*16+local[0],data.sectionY()*16+local[1],data.sectionZ()*16+local[2]);
                            var state=client.level.getBlockState(pos);
                            boolean fluid=(byte)(mid>>>24)==1;
                            if(fluid)state=state.getFluidState().createLegacyBlock();
                            if(records.getInt(at+28)!=state.getLightEmission())throw new IllegalStateException("Intrinsic emission differs at "+pos);
                            if(records.getInt(at+28)>0)emissive++;
                            if(fluid && state.is(Blocks.LAVA))lava++;
                            if(records.getInt(at+8)!=7000+BuiltInRegistries.BLOCK.getId(state.getBlock()))throw new IllegalStateException("Actual block mapping differs at "+pos);
                            int tintAo=records.getInt(at+24),tint=tintAo&0xffffff;
                            if(tint!=0xffffff) {
                                int expected=fluid?net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(client.level,pos):client.getBlockColors().getTintSource(state,0).colorInWorld(state,client.level,pos);
                                if(tint!=(expected&0xffffff))throw new IllegalStateException("Tint contains directional lighting or AO at "+pos);
                                tinted++;
                            }
                            if((tintAo>>>24)<255)occluded++;
                            int normal=records.getInt(at),nx=(byte)normal,ny=(byte)(normal>>>8),nz=(byte)(normal>>>16);
                            if(nx==0&&ny==0&&nz==0)throw new IllegalStateException("Missing emitted normal");
                            if(fluid){fluids++;if(ny!=0&&(nx!=0||nz!=0))slopes++;}
                            vertices++;
                        }
                    }
                    var ready=TerrainSidecars.prepare(mesh,entry.getKey(),session.device());
                    if(ready==null)throw new IllegalStateException("Current captured mesh did not upload");
                    try(var readback=session.device().createBuffer(()->"Terrain attribute readback",GpuBuffer.USAGE_COPY_DST|GpuBuffer.USAGE_MAP_READ,records.remaining())) {
                        var encoder=session.device().createCommandEncoder();encoder.copyToBuffer(ready.records(),readback.slice());
                        try(var fence=encoder.createFence()){encoder.submit();if(!fence.awaitCompletion(10_000_000_000L))throw new IllegalStateException("Terrain readback timeout");}
                        try(var mapped=readback.map(true,false)){if(!records.equals(mapped.data()))throw new IllegalStateException("GPU records differ from mesher bytes");}
                        gpuBytes+=records.remaining();
                        if(DRAW_BINDINGS)TerrainDrawReadback.verify(session,client,mesh,entry.getKey());
                    }
                }
                SECTIONS.add(data.sectionX()+","+data.sectionZ());
                VERIFIED_MESHES.add(mesh);
                // The host can release a previously uploaded mesh on a section worker.
                java.util.concurrent.CompletableFuture.runAsync(data::close).join();
                for(var layer:data.layers().values())if(layer.gpu()==null || layer.gpu().isClosed() || (DRAW_BINDINGS && (layer.drawMetadata()==null || layer.drawMetadata().isClosed())))
                    throw new IllegalStateException("Worker release destroyed GPU terrain storage before render-thread collection");
                if(DRAW_BINDINGS)TerrainDrawReadback.verifyUnavailable(session,client,mesh,data.layers().keySet().iterator().next());
                TerrainSidecars.collectRetired();
                for(var layer:data.layers().values())if(layer.drawMetadata()!=null)throw new IllegalStateException("Retired draw metadata remains allocated");
            }
            if(SECTIONS.size()==4 && fluids>0 && slopes>0 && vertices>100 && tinted>0) {
                if(DRAW_BINDINGS && (emissive==0 || lava==0))throw new IllegalStateException("No emissive solid/lava capture verified");
                if(DRAW_BINDINGS && TerrainDrawReadback.nonzeroBases==0)throw new IllegalStateException("No nonzero host base vertices verified");
                TerrainCapture.invalidate();TerrainSidecars.collectRetired();
                for(var mesh:VERIFIED_MESHES) {
                    var stale=TerrainSidecars.data(mesh);
                    for(var entry:stale.layers().entrySet())if(TerrainSidecars.lookup(mesh,entry.getKey())!=null || entry.getValue().gpu()!=null)
                        throw new IllegalStateException("Retired terrain generation remains GPU-ready");
                }
                var result=new JsonObject();result.addProperty("status","complete");result.addProperty("sections",SECTIONS.size());
                result.addProperty("vertices",vertices);result.addProperty("fluidVertices",fluids);result.addProperty("slopedFluidVertices",slopes);
                result.addProperty("gpuBytes",gpuBytes);result.addProperty("mismatches",0);result.addProperty("device",session.device().getDeviceInfo().name());
                result.addProperty("tintedVertices",tinted);result.addProperty("occludedVertices",occluded);result.addProperty("generationRetirement",true);
                result.addProperty("workerRetirement",true);
                if(DRAW_BINDINGS){result.addProperty("hostBoundDraws",hostBoundDraws);result.addProperty("vertexStageReadbacks",TerrainDrawReadback.vertices);result.addProperty("drawCallbacks",TerrainDrawReadback.callbacks);result.addProperty("unavailableVertexChecks",TerrainDrawReadback.unavailableChecks);result.addProperty("drawMetadataRetirement",true);result.addProperty("nonzeroBaseDraws",TerrainDrawReadback.nonzeroBases);result.addProperty("generatedAdapterVertexChecks",TerrainDrawReadback.adapterVertices);result.addProperty("emissiveVertices",emissive);result.addProperty("lavaVertices",lava);}
                result.addProperty("compilerGeneration",true);result.addProperty("exceptionCleanup",true);
                result.addProperty("packGeneration",true);
                RESULT.set(result);
            }
        }catch(Throwable failure){RESULT.set(failure);}
    }
    private static void finish(Minecraft client,Path output,JsonObject result) {
        done=true;
        try{Files.writeString(output.resolve(DRAW_BINDINGS?"terrain-draws-result.json":"terrain-attributes-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));}
        catch(Exception failure){GreenMarine.LOGGER.error("Cannot write terrain evidence",failure);}
        if(registration!=null)registration.close();
        TerrainDrawBindings.configure(false);
        TerrainCapture.configure(TerrainCapture.MappingSnapshot.unmapped(),TerrainCapture.Profile.DISABLED);
        TerrainSidecars.collectRetired();client.stop();
    }
}
