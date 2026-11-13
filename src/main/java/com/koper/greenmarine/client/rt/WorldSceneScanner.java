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
    private final Map<net.minecraft.world.level.block.Block,Float> transmission=new IdentityHashMap<>();
    private final BlockTintMemo tints=new BlockTintMemo();
    private final java.util.function.IntUnaryOperator tintLookup=this::tintColour;
    private net.minecraft.world.level.block.state.BlockState tintState;
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
    public int boundsReadyPending() {return cache.pendingSections()+cache.unavailableSections();}
    public SectionSceneCache.Snapshot snapshot() {return cache.snapshot();}
    public SectionSceneCache.Snapshot publicationSnapshot(SceneBounds previous) {return cache.publicationSnapshot(previous);}
    public boolean canPublish(SceneBounds bounds,long contentHash) {return cache.canPublish(bounds,contentHash);}
    public Metrics metrics() {
        var m=cache.metrics();return new Metrics(m.rebuiltSections(),m.cacheHits(),m.cachedSections(),m.pendingSections(),
            scannedBlocks,scanNanos,pendingSince==0?0:System.nanoTime()-pendingSince);
    }
    private void trackPending() {
        if(boundsReadyPending()==0)pendingSince=0;else if(pendingSince==0)pendingSince=System.nanoTime();
    }
    public void invalidateBlock(BlockPos pos) {cache.invalidateBlock(pos.getX(),pos.getY(),pos.getZ());trackPending();}
    public void invalidateSection(int x,int y,int z) {cache.invalidateSection(x,y,z);trackPending();}
    public void invalidateColumn(int x,int z) {cache.invalidateColumn(x,z);trackPending();}
    public void unloadColumn(int x,int z) {cache.unloadColumn(x,z);observed.remove(new SectionSceneCache.ColumnKey(x,z));trackPending();}
    public void invalidateAll() {cache.invalidateAll();trackPending();}
    public void reset() {
        cache.reset();observed.clear();transmission.clear();ticket=null;staging=null;world=null;cursor=0;
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
                    var custom=com.koper.greenmarine.api.render.BlockRayModels.parts(client.level,position,state,null);
                    if(custom==null)captureModel(client,state,dx,dy,dz,1,63,true);
                    else for(var piece:custom)captureModel(client,piece.material(),dx+piece.x(),dy+piece.y(),dz+piece.z(),piece.scale(),piece.faceMask(),false);
                }
                if(cursor!=SECTION_CELLS)break;
                cache.complete(ticket,staging==null?EMPTY:staging.finish());ticket=null;staging=null;cursor=0;
            }
            trackPending();return boundsReadyPending()==0;
        } finally {scanNanos+=System.nanoTime()-started;}
    }
    private void captureModel(Minecraft client,net.minecraft.world.level.block.state.BlockState state,float x,float y,float z,float scale,int mask,boolean neighborCull) {
        if(mask==0 || state.getRenderShape()!=RenderShape.MODEL)return;
        tints.reset();tintState=state;parts.clear();random.setSeed(state.getSeed(position));
        client.getModelManager().getBlockStateModelSet().get(state).collectParts(random,parts);
        var offset=state.getOffset(position);x+=(float)offset.x*scale;y+=(float)offset.y*scale;z+=(float)offset.z*scale;
        for(var part:parts) {
            add(client,state,part.getQuads(null),x,y,z,scale,mask,state.getLightEmission());
            for(var face:FACES) {
                if((mask&1<<face.ordinal())==0)continue;
                if(neighborCull && state.isSolidRender() && client.level.getBlockState(neighbor.set(position.getX()+face.getStepX(),
                    position.getY()+face.getStepY(),position.getZ()+face.getStepZ())).isSolidRender())continue;
                add(client,state,part.getQuads(face),x,y,z,scale,mask,state.getLightEmission());
            }
        }
    }
    private void add(Minecraft client,net.minecraft.world.level.block.state.BlockState state,List<BakedQuad> quads,float x,float y,float z,float scale,int mask,int emission) {
        float sunlight=transmission.computeIfAbsent(state.getBlock(),block -> com.koper.greenmarine.api.render.BlockMaterials
            .forBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString()).sunlightTransmission());
        for(var quad:quads) {
            if((mask&1<<quad.direction().ordinal())==0)continue;
            if(!quad.materialInfo().sprite().atlasLocation().equals(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS))continue;
            int tint=0xFFFFFF;
            if(quad.materialInfo().isTinted())tint=tints.colour(quad.materialInfo().tintIndex(),tintLookup);
            for(int triangle=0;triangle<2;triangle++) {
                for(int corner=0;corner<3;corner++) {
                    int index=triangle==0?corner:corner==0?0:corner==1?2:3;
                    var vertex=quad.position(index);vertices[corner*3]=vertex.x()*scale+x;vertices[corner*3+1]=vertex.y()*scale+y;vertices[corner*3+2]=vertex.z()*scale+z;
                    long packed=quad.packedUV(index);uv[corner*2]=UVPair.unpackU(packed);uv[corner*2+1]=UVPair.unpackV(packed);
                }
                staging.triangle(vertices,uv,tint,Math.max(emission,quad.materialInfo().lightEmission()),
                    quad.materialInfo().layer()!=net.minecraft.client.renderer.chunk.ChunkSectionLayer.SOLID,sunlight,
                    quad.materialInfo().layer()==net.minecraft.client.renderer.chunk.ChunkSectionLayer.CUTOUT
                        && !quad.materialInfo().sprite().transparency().hasTranslucent());
            }
        }
    }
    private int tintColour(int index) {
        var client=Minecraft.getInstance();var source=client.getBlockColors().getTintSource(tintState,index);
        return source==null?0xFFFFFF:source.colorInWorld(tintState,client.level,position);
    }
    /** Explicit legacy assembly. New callers assemble snapshot() on their worker. */
    public TriangleScene completed() {
        if(cache.bounds()==null||boundsReadyPending()!=0)return null;
        var snapshot=cache.snapshot();
        return snapshot.complete()?snapshot.assemble():null;
    }
    public int progress() {return cache.resolvedSections()*SECTION_CELLS+(ticket!=null&&cache.valid(ticket)?cursor:0);}
}
