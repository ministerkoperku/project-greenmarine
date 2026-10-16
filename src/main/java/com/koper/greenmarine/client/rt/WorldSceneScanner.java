package com.koper.greenmarine.client.rt;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.lang.ref.WeakReference;
import java.util.*;

/** Budgeted render-thread extraction; worker snapshots never access host world/model objects. */
public final class WorldSceneScanner {
    public static final int EDGE=SceneBounds.WIDTH;
    private static final int SECTION_CELLS=4096;
    private static final Direction[] FACES=Direction.values();
    private static final TriangleScene EMPTY=new TriangleScene.Builder(0,0,0,1).finish();
    public record Metrics(long rebuiltSections,long cacheHits,int cachedSections,int pendingSections,
                          long scannedBlocks,long scanNanos,long pendingAgeNanos) {}
    private final SectionSceneCache cache=new SectionSceneCache();
    private final Map<SectionSceneCache.ColumnKey,WeakReference<LevelChunk>> observed=new HashMap<>();
    private final BlockPos.MutableBlockPos position=new BlockPos.MutableBlockPos(),neighbor=new BlockPos.MutableBlockPos();
    private final List<BlockStateModelPart> parts=new ArrayList<>();
    private final RandomSource random=RandomSource.create(0);
    private final float[] vertices=new float[9],uv=new float[6];
    private SectionSceneCache.Ticket ticket;
    private TriangleScene.Builder staging;
    private Object world;
    private int cursor;
    private long scannedBlocks,scanNanos,pendingSince;
    private double cameraX,cameraY,cameraZ;

    /** Preserved legacy full-scan API; overlap is retained rather than scanned again. */
    public void begin(int x,int y,int z) {
        var bounds=new SceneBounds(x,y,z);if(bounds.equals(cache.bounds()))invalidateAll();
        retarget(bounds,x+EDGE*.5,y+SceneBounds.HEIGHT*.5,z+EDGE*.5);
    }
    public void begin(SceneBounds bounds,double x,double y,double z) {retarget(bounds,x,y,z);}
    public void retarget(SceneBounds bounds,double x,double y,double z) {
        cameraX=x;cameraY=y;cameraZ=z;cache.retarget(bounds,x,y,z);trackPending();
    }
    public long revision() {return cache.revision();}
    public int boundsReadyPending() {return cache.pendingSections();}
    public SectionSceneCache.Snapshot snapshot() {return cache.snapshot();}
    public SectionSceneCache.Snapshot publicationSnapshot(SceneBounds previous) {return cache.publicationSnapshot(previous);}
    public Metrics metrics() {
        var m=cache.metrics();return new Metrics(m.rebuiltSections(),m.cacheHits(),m.cachedSections(),m.pendingSections(),
            scannedBlocks,scanNanos,pendingSince==0?0:System.nanoTime()-pendingSince);
    }
    private void trackPending() {
        if(cache.pendingSections()==0)pendingSince=0;else if(pendingSince==0)pendingSince=System.nanoTime();
    }
    public void invalidateBlock(BlockPos pos) {cache.invalidateBlock(pos.getX(),pos.getY(),pos.getZ());trackPending();}
    public void invalidateColumn(int x,int z) {cache.invalidateColumn(x,z);trackPending();}
    public void unloadColumn(int x,int z) {cache.unloadColumn(x,z);observed.remove(new SectionSceneCache.ColumnKey(x,z));trackPending();}
    public void invalidateAll() {cache.invalidateAll();trackPending();}
    public void reset() {
        cache.reset();observed.clear();ticket=null;staging=null;world=null;cursor=0;
        scannedBlocks=scanNanos=pendingSince=0;
    }
    private void observeLoadedColumns(Minecraft client) {
        var columns=cache.cachedColumns();
        observed.keySet().retainAll(columns);
        for(var column:columns) {
            var chunk=client.level.getChunkSource().getChunk(column.x(),column.z(),ChunkStatus.FULL,false);
            var reference=observed.get(column);var prior=reference==null?null:reference.get();
            if(reference==null || chunk!=prior) {
                if(reference!=null)cache.invalidateColumn(column.x(),column.z());
                cache.observeColumn(column.x(),column.z(),chunk!=null);
                observed.put(column,new WeakReference<>(chunk));
            }
        }
    }
    /** True still means a complete legacy scan. revision()/snapshot() expose earlier near updates. */
    public boolean step(Minecraft client,int blocks,long nanos) {
        if(cache.bounds()==null||blocks<1||nanos<1||client.level==null)throw new IllegalArgumentException("Invalid scene scan");
        long started=System.nanoTime();
        try {
            if(world!=client.level) {
                var bounds=cache.bounds();reset();world=client.level;cache.retarget(bounds,cameraX,cameraY,cameraZ);
            }
            observeLoadedColumns(client);
            var next=cache.next();
            // An edit near the camera preempts unfinished distant extraction.
            if(ticket!=null && cache.shouldPreempt(ticket,next)) {ticket=null;staging=null;cursor=0;}
            int budget=blocks;
            while(budget>0 && System.nanoTime()-started<nanos) {
                if(ticket==null) {
                    ticket=cache.next();if(ticket==null)break;
                    cursor=0;
                }
                var key=ticket.key();
                // This reference is local to this step and is never retained across frames.
                var chunk=client.level.getChunkSource().getChunk(key.x(),key.z(),ChunkStatus.FULL,false);
                if(chunk==null) {cache.unloadColumn(key.x(),key.z());ticket=null;staging=null;cursor=0;continue;}
                boolean outside=client.level.isOutsideBuildHeight(key.blockY());
                if(outside || chunk.getSections()[chunk.getSectionIndex(key.blockY())].hasOnlyAir()){cursor=SECTION_CELLS;staging=null;}
                else if(staging==null)staging=new TriangleScene.Builder(0,0,0,TriangleScene.MAX_TRIANGLES);
                while(cursor<SECTION_CELLS && budget>0) {
                    if((cursor&15)==0 && System.nanoTime()-started>=nanos)break;
                    int cell=cursor++,dx=cell&15,dy=cell>>8,dz=(cell>>4)&15;
                    budget--;scannedBlocks++;
                    var state=chunk.getBlockState(position.set(key.blockX()+dx,key.blockY()+dy,key.blockZ()+dz));
                    if(state.getRenderShape()!=RenderShape.MODEL)continue;
                    parts.clear();random.setSeed(state.getSeed(position));
                    client.getModelManager().getBlockStateModelSet().get(state).collectParts(random,parts);
                    var offset=state.getOffset(position);
                    for(var part:parts) {
                        add(client,state,part.getQuads(null),dx+(float)offset.x,dy+(float)offset.y,dz+(float)offset.z,state.getLightEmission());
                        for(var face:FACES) {
                            if(state.isSolidRender() && client.level.getBlockState(neighbor.set(position.getX()+face.getStepX(),
                                position.getY()+face.getStepY(),position.getZ()+face.getStepZ())).isSolidRender())continue;
                            add(client,state,part.getQuads(face),dx+(float)offset.x,dy+(float)offset.y,dz+(float)offset.z,state.getLightEmission());
                        }
                    }
                }
                if(cursor!=SECTION_CELLS)break;
                cache.complete(ticket,staging==null?EMPTY:staging.finish());ticket=null;staging=null;cursor=0;
            }
            trackPending();return cache.pendingSections()==0;
        } finally {scanNanos+=System.nanoTime()-started;}
    }
    private void add(Minecraft client,net.minecraft.world.level.block.state.BlockState state,List<BakedQuad> quads,float x,float y,float z,int emission) {
        for(var quad:quads) {
            if(!quad.materialInfo().sprite().atlasLocation().equals(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS))continue;
            int tint=0xFFFFFF;
            if(quad.materialInfo().isTinted()) {
                var source=client.getBlockColors().getTintSource(state,quad.materialInfo().tintIndex());
                if(source!=null)tint=source.colorInWorld(state,client.level,position);
            }
            for(int triangle=0;triangle<2;triangle++) {
                for(int corner=0;corner<3;corner++) {
                    int index=triangle==0?corner:corner==0?0:corner==1?2:3;
                    var vertex=quad.position(index);vertices[corner*3]=vertex.x()+x;vertices[corner*3+1]=vertex.y()+y;vertices[corner*3+2]=vertex.z()+z;
                    long packed=quad.packedUV(index);uv[corner*2]=UVPair.unpackU(packed);uv[corner*2+1]=UVPair.unpackV(packed);
                }
                staging.triangle(vertices,uv,tint,Math.max(emission,quad.materialInfo().lightEmission()),
                    quad.materialInfo().layer()!=net.minecraft.client.renderer.chunk.ChunkSectionLayer.SOLID);
            }
        }
    }
    /** Explicit legacy assembly. New callers assemble snapshot() on their worker. */
    public TriangleScene completed() {
        if(cache.bounds()==null||cache.pendingSections()!=0)return null;
        return cache.snapshot().assemble();
    }
    public int progress() {return cache.resolvedSections()*SECTION_CELLS+(ticket!=null&&cache.valid(ticket)?cursor:0);}
}
