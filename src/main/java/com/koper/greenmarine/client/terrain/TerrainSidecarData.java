package com.koper.greenmarine.client.terrain;

import com.koper.greenmarine.api.render.TerrainCapture;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import java.nio.*;
import java.util.*;

/** Owned records, attached by mesh identity, never by section coordinates. */
public final class TerrainSidecarData implements AutoCloseable {
    public static final class Layer {
        private final byte[] bytes,hostVertices;
        private GpuBuffer gpu,drawMetadata;
        private int hostBaseVertex;
        public Layer(byte[] bytes,byte[] hostVertices){this.bytes=bytes.clone();this.hostVertices=hostVertices.clone();}
        public ByteBuffer records(){return ByteBuffer.wrap(bytes).asReadOnlyBuffer().order(ByteOrder.nativeOrder());}
        /** Optional verification copy, retained only while the diagnostic flag is enabled. */
        public ByteBuffer hostVertices(){return ByteBuffer.wrap(hostVertices).asReadOnlyBuffer().order(ByteOrder.nativeOrder());}
        public int vertexCount(){return bytes.length/TerrainVertex.BYTES;}
        public GpuBuffer gpu(){return gpu;}
        public void gpu(GpuBuffer value){gpu=value;}
        public GpuBuffer drawMetadata(){return drawMetadata;}
        public int hostBaseVertex(){return hostBaseVertex;}
        public void drawMetadata(GpuBuffer value,int baseVertex){if(drawMetadata!=null)drawMetadata.close();drawMetadata=value;hostBaseVertex=baseVertex;}
        private void close(){if(gpu!=null){gpu.close();gpu=null;}if(drawMetadata!=null){drawMetadata.close();drawMetadata=null;}}
    }
    private final long generation;
    private final int sectionX,sectionY,sectionZ;
    private final Map<ChunkSectionLayer,Layer> layers;
    private volatile boolean closed;
    public TerrainSidecarData(long generation,int x,int y,int z,Map<ChunkSectionLayer,Layer> layers) {
        this.generation=generation;sectionX=x;sectionY=y;sectionZ=z;this.layers=Map.copyOf(layers);
    }
    public boolean current(){return !closed&&generation==TerrainCapture.configuration().generation();}
    public Map<ChunkSectionLayer,Layer> layers(){return layers;}
    public int sectionX(){return sectionX;}public int sectionY(){return sectionY;}public int sectionZ(){return sectionZ;}
    public long generation(){return generation;}
    /** Mesh release may run on a section worker; only render-thread collection destroys GPU storage. */
    @Override public synchronized void close(){if(closed)return;closed=true;TerrainSidecars.retire(this);}
    void releaseGpu(){layers.values().forEach(Layer::close);}
}
