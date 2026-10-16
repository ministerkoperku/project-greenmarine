package com.koper.greenmarine.client;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.RayMode;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.nio.*;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

public final class VoxelWorld {
    private static final VoxelScanner SCAN=new VoxelScanner();
    private static final Long2ObjectOpenHashMap<LevelChunk> CHUNKS=new Long2ObjectOpenHashMap<>();
    private static final BlockPos.MutableBlockPos POSITION=new BlockPos.MutableBlockPos();
    private static final ByteBuffer HEADER=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
    private static final ByteBuffer CELLS=ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder());
    private static VoxelGrid grid,uploaded;
    private static GpuBuffer buffer;
    private static GpuTexture materialTexture;
    private static GpuTextureView materialView;
    private static NativeImage materialImage;
    private static VoxelGrid materialUploaded;
    private static boolean materialInitialized;
    private static boolean scanning,initialized;
    private static int ticks;
    private static long scans,scanNanos;
    private VoxelWorld() {}
    public static void tick(Minecraft client) {
        if(client.level==null || client.player==null || !client.gameRenderer.gameRenderState().shouldRenderLevel
            || !GreenMarineClient.enabled() || ShaderRuntime.rays().effective()!=RayMode.VOXEL) return;
        var camera=BlockPos.containing(client.gameRenderer.mainCamera().position());
        boolean moved=grid==null || Math.abs(grid.x()+16-camera.getX())>=4 || Math.abs(grid.y()+16-camera.getY())>=4 || Math.abs(grid.z()+16-camera.getZ())>=4;
        if(!scanning && (moved || ++ticks>=20)) {
            SCAN.begin(camera.getX()-16,camera.getY()-16,camera.getZ()-16); CHUNKS.clear(); scanning=true; ticks=0;
        }
        if(!scanning) return;
        long started=System.nanoTime();
        boolean completed=SCAN.stepMaterials((x,y,z) -> {
            long key=((long)(x>>4)<<32)^((z>>4)&0xFFFFFFFFL);
            LevelChunk chunk;
            if(CHUNKS.containsKey(key)) chunk=CHUNKS.get(key);
            else { chunk=client.level.getChunkSource().getChunk(x>>4,z>>4,ChunkStatus.FULL,false); CHUNKS.put(key,chunk); }
            if(chunk==null) return 0;
            var state=chunk.getBlockState(POSITION.set(x,y,z));
            boolean solid=state.isSolidRender(); int emission=state.getLightEmission();
            int color=solid || emission>0?state.getMapColor(client.level,POSITION).col:0;
            if(color==0 && emission>0) color=0xFFCC66;
            return VoxelGrid.sample(solid,color,emission);
        },2048,750_000);
        scanNanos+=System.nanoTime()-started;
        if(completed) {
            var next=SCAN.completed(); if(!next.sameContent(grid)) grid=next;
            scanning=false; scans++;
            if(scans==1) GreenMarine.LOGGER.info("Voxel snapshot ready: origin=({}, {}, {}), full-block cells=32768, incremental budget=2048 blocks/750us per tick",grid.x(),grid.y(),grid.z());
        }
    }
    public static GpuBuffer prepare(CommandEncoder encoder,double cameraX,double cameraY,double cameraZ) {
        return prepare(encoder,cameraX,cameraY,cameraZ,true);
    }
    public static GpuBuffer prepare(CommandEncoder encoder,double cameraX,double cameraY,double cameraZ,boolean enabled) {
        if(buffer==null) buffer=RenderSystem.getDevice().createBuffer(() -> "GreenMarine voxel occupancy",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_COPY_DST,4128);
        boolean ready=enabled && grid!=null;
        HEADER.putFloat(0,ready?(float)(grid.x()-cameraX):0).putFloat(4,ready?(float)(grid.y()-cameraY):0).putFloat(8,ready?(float)(grid.z()-cameraZ):0).putFloat(12,32);
        HEADER.putFloat(16,ready?1:0).putFloat(20,4).putFloat(24,VoxelGrid.MAX_STEPS).putFloat(28,0.06f);
        HEADER.position(0).limit(32); encoder.writeToBuffer(buffer.slice(0,32),HEADER);
        if(!initialized || grid!=uploaded) {
            if(grid!=null) grid.writeCells(CELLS); else for(int offset=0;offset<4096;offset+=4) CELLS.putInt(offset,0);
            CELLS.position(0).limit(4096); encoder.writeToBuffer(buffer.slice(32,4096),CELLS); uploaded=grid; initialized=true;
        }
        return buffer;
    }
    public static boolean ready() { return grid!=null; }
    public static GpuTextureView materials(CommandEncoder encoder) {
        if(materialTexture==null) {
            materialTexture=RenderSystem.getDevice().createTexture("GreenMarine voxel materials",
                GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST,GpuFormat.RGBA8_UNORM,256,128,1,1);
            materialView=RenderSystem.getDevice().createTextureView(materialTexture);
            materialImage=new NativeImage(256,128,false);
        }
        if(!materialInitialized || grid!=materialUploaded) {
            var bytes=materialImage.getPixelBytes();
            if(grid!=null) grid.writeMaterials(bytes); else for(int i=0;i<VoxelGrid.CELLS*4;i++) bytes.put(i,(byte)0);
            encoder.writeToTexture(materialTexture,materialImage);
            materialUploaded=grid; materialInitialized=true;
        }
        return materialView;
    }
    public static long scans() { return scans; }
    public static long scanNanos() { return scanNanos; }
    public static void reset() {
        if(buffer!=null) buffer.close(); buffer=null; grid=uploaded=null; scanning=initialized=false; ticks=0; scans=scanNanos=0; CHUNKS.clear();
        if(materialView!=null) materialView.close(); materialView=null;
        if(materialTexture!=null) materialTexture.close(); materialTexture=null;
        if(materialImage!=null) materialImage.close(); materialImage=null;
        materialUploaded=null; materialInitialized=false;
    }
}
