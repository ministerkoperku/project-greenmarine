package com.koper.greenmarine.client.terrain;

import com.koper.greenmarine.client.HostStorage;
import com.mojang.renderpearl.api.buffers.*;
import com.mojang.renderpearl.api.device.GpuDevice;
import net.minecraft.client.renderer.chunk.*;
import java.util.*;

/** Render-thread upload/lookup. Every returned slice borrows a GreenMarine-owned buffer. */
public final class TerrainSidecars {
    private static final java.util.concurrent.ConcurrentLinkedQueue<java.lang.ref.WeakReference<Object>> PUBLISHED=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final Set<TerrainSidecarData> UPLOADED=Collections.newSetFromMap(new IdentityHashMap<>());
    private static final java.util.concurrent.ConcurrentLinkedQueue<TerrainSidecarData> RETIRED=new java.util.concurrent.ConcurrentLinkedQueue<>();
    public record Ready(GpuBufferSlice records,int vertexCount,long generation) {}
    private TerrainSidecars() {}
    public static void published(Object mesh){if(Boolean.getBoolean("greenmarine.terrain.diagnostics"))PUBLISHED.add(new java.lang.ref.WeakReference<>(mesh));}
    public static java.util.List<Object> diagnosticMeshes(){var result=new java.util.ArrayList<Object>();java.lang.ref.WeakReference<Object> next;while((next=PUBLISHED.poll())!=null){var mesh=next.get();if(mesh!=null&&data(mesh)!=null&&data(mesh).current())result.add(mesh);}return result;}
    public static TerrainSidecarData data(Object mesh){return mesh instanceof TerrainAttachment a?a.greenmarine$terrainData():null;}
    public static Ready lookup(Object mesh,ChunkSectionLayer layer){
        var data=data(mesh);if(data==null||!data.current())return null;
        var record=data.layers().get(layer);if(record==null||record.gpu()==null)return null;
        return new Ready(record.gpu().slice(),record.vertexCount(),data.generation());
    }
    public static Ready prepare(Object mesh,ChunkSectionLayer layer,GpuDevice device){
        var data=data(mesh);if(data==null||!data.current())return null;
        var record=data.layers().get(layer);if(record==null||record.vertexCount()==0)return null;
        if(record.gpu()==null){
            var bytes=record.records();var direct=java.nio.ByteBuffer.allocateDirect(bytes.remaining());direct.put(bytes).flip();
            record.gpu(HostStorage.create(()->device.createBuffer(()->"GreenMarine terrain attributes",GpuBuffer.USAGE_COPY_SRC,direct)));
            synchronized(UPLOADED){UPLOADED.add(data);}
        }
        return lookup(mesh,layer);
    }
    /** Drained on the render thread; configure can be called from a pack loader thread. */
    static void retire(TerrainSidecarData data){RETIRED.add(data);}
    public static void collectRetired(){TerrainDrawBindings.collectRetired();synchronized(UPLOADED){
        for(var data:UPLOADED)if(!data.current())data.close();
        TerrainSidecarData data;while((data=RETIRED.poll())!=null){data.releaseGpu();UPLOADED.remove(data);}
    }}
    public static void invalidate(){/* Generation check prevents publication; GPU disposal is render-thread-only. */}
    public static void release(Object mesh){var data=data(mesh);if(data!=null)data.close();}
    /** Draw lookup preserves separate host and sidecar allocation offsets. */
    public static int vertexIndex(int glVertexIndex,int hostBaseVertex,int sidecarBase){return Math.addExact(sidecarBase,Math.subtractExact(glVertexIndex,hostBaseVertex));}
}
