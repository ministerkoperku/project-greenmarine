package com.koper.greenmarine.client.world;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Top-of-column heights in a 512x512 toroidal map around the camera, for sun shadows beyond the
 * traced scene (distant mountains). Column (x, z) lives at texel (x mod 512, z mod 512); RG hold
 * height+2048, B tags which 512-block tile wrote it (3 bits per axis) and A marks loaded columns.
 */
public final class HeightField implements AutoCloseable {
    public static final int SIZE=512;
    private static final int ROWS_PER_TICK=32,RESCAN_TICKS=40;
    private final int[] heights=new int[SIZE*SIZE];
    private int centreX=Integer.MIN_VALUE,centreZ,row,idle;
    private boolean scanning,dirty;
    private String reported;
    private GpuTexture texture;
    private GpuTextureView view;
    private NativeImage image;

    /** Rescans rows incrementally; a full pass every two seconds or when the camera moves 64 blocks. */
    public void tick(Minecraft client) {
        if(client.level==null)return;
        var camera=client.gameRenderer.mainCamera().position();
        int x=(int)Math.floor(camera.x),z=(int)Math.floor(camera.z);
        if(!scanning && (centreX==Integer.MIN_VALUE || Math.abs(x-centreX)>=64 || Math.abs(z-centreZ)>=64 || ++idle>=RESCAN_TICKS)) {
            centreX=x;centreZ=z;row=0;scanning=true;idle=0;
        }
        if(!scanning)return;
        int minX=centreX-SIZE/2,minZ=centreZ-SIZE/2;
        var source=client.level.getChunkSource();
        for(int end=Math.min(SIZE,row+ROWS_PER_TICK);row<end;row++) {
            int worldZ=minZ+row;LevelChunk chunk=null;int chunkX=Integer.MIN_VALUE;
            for(int column=0;column<SIZE;column++) {
                int worldX=minX+column;
                if(worldX>>4!=chunkX) {chunkX=worldX>>4;chunk=source.getChunk(chunkX,worldZ>>4,ChunkStatus.FULL,false);}
                int index=Math.floorMod(worldZ,SIZE)*SIZE+Math.floorMod(worldX,SIZE);
                if(chunk==null) {heights[index]=0;continue;}
                int top=chunk.getHeight(Heightmap.Types.MOTION_BLOCKING,worldX&15,worldZ&15)+1;
                int tag=((worldX>>9)&7)|(((worldZ>>9)&7)<<3);
                heights[index]=(Math.clamp(top+2048,0,65535))|(tag<<16)|(1<<24);
            }
        }
        if(row>=SIZE) {
            scanning=false;dirty=true;
            int loaded=0;for(int value:heights)if(value>>>24!=0)loaded++;
            int centre=heights[Math.floorMod(centreZ,SIZE)*SIZE+Math.floorMod(centreX,SIZE)];
            String state=loaded/4096+"k columns, camera column top "+((centre&0xFFFF)-2048)+" tag "+((centre>>16)&0xFF);
            if(!state.equals(reported)) {reported=state;com.koper.greenmarine.GreenMarine.LOGGER.info("Height field scan: {}",state);}
        }
    }

    public GpuTextureView bind(CommandEncoder encoder) {
        if(texture==null) {
            texture=RenderSystem.getDevice().createTexture("GreenMarine height field",
                GpuTexture.USAGE_TEXTURE_BINDING|GpuTexture.USAGE_COPY_DST,GpuFormat.RGBA8_UNORM,SIZE,SIZE,1,1);
            view=RenderSystem.getDevice().createTextureView(texture);
            image=new NativeImage(SIZE,SIZE,false);
            dirty=true;
        }
        if(dirty) {
            var bytes=image.getPixelBytes();
            for(int i=0;i<heights.length;i++) {
                int value=heights[i];
                bytes.put(i*4,(byte)value).put(i*4+1,(byte)(value>>8)).put(i*4+2,(byte)(value>>16)).put(i*4+3,(byte)(value>>>24==0?0:255));
            }
            encoder.writeToTexture(texture,image);dirty=false;
        }
        return view;
    }

    public void reset() {centreX=Integer.MIN_VALUE;scanning=false;java.util.Arrays.fill(heights,0);dirty=true;}

    @Override public void close() {
        if(view!=null)view.close();if(texture!=null)texture.close();if(image!=null)image.close();
        view=null;texture=null;image=null;
    }
}
