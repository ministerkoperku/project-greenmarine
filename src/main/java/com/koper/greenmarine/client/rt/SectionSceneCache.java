package com.koper.greenmarine.client.rt;

import java.util.*;

/** Render-thread planner; snapshots contain only immutable section-local meshes. */
public final class SectionSceneCache {
    public static final int MAX_SECTIONS=216;
    public static final int MAX_CACHED_TRIANGLES=2*TriangleScene.MAX_TRIANGLES;
    public record SectionKey(int x,int y,int z) implements Comparable<SectionKey> {
        public int blockX() {return x*16;}
        public int blockY() {return y*16;}
        public int blockZ() {return z*16;}
        @Override public int compareTo(SectionKey other) {
            int value=Integer.compare(y,other.y);if(value==0)value=Integer.compare(z,other.z);
            return value==0?Integer.compare(x,other.x):value;
        }
    }
    public record Ticket(SectionKey key,long epoch,long version) {}
    public record ColumnKey(int x,int z) {}
    public record Section(SectionKey key,TriangleScene mesh) {}
    public record Snapshot(SceneBounds bounds,long revision,boolean complete,int pendingSections,List<Section> sections) {
        public Snapshot {sections=List.copyOf(sections);}
        /** Compare immutable section metadata before packing every triangle again. */
        public long contentHash() {
            long value=0xcbf29ce484222325L;
            value=(value^bounds.x())*0x100000001b3L;value=(value^bounds.y())*0x100000001b3L;value=(value^bounds.z())*0x100000001b3L;
            for(var section:sections){var key=section.key();value=(value^key.x())*0x100000001b3L;value=(value^key.y())*0x100000001b3L;
                value=(value^key.z())*0x100000001b3L;value=(value^section.mesh().hash())*0x100000001b3L;value=(value^section.mesh().triangles())*0x100000001b3L;}
            return value;
        }
        /** No Minecraft objects are accessed here; safe on an assembly worker. */
        public TriangleScene assemble() {
            long triangles=0;for(var section:sections)triangles+=section.mesh().triangles();
            if(triangles>TriangleScene.MAX_TRIANGLES)throw new IllegalArgumentException("World triangle budget exceeded");
            var builder=new TriangleScene.Builder(bounds.x(),bounds.y(),bounds.z(),TriangleScene.MAX_TRIANGLES,(int)triangles);
            for(var section:sections) builder.append(section.mesh(),section.key().blockX()-bounds.x(),
                section.key().blockY()-bounds.y(),section.key().blockZ()-bounds.z());
            return builder.finishOwned();
        }
    }
    public record Metrics(long rebuiltSections,long cacheHits,int cachedSections,int pendingSections) {}
    private static final class Entry {
        final long identity;
        long version;
        boolean dirty=true,loaded=true,urgent;
        TriangleScene mesh;
        Entry(long identity) {this.identity=identity;}
    }
    private final LinkedHashMap<SectionKey,Entry> entries=new LinkedHashMap<>(256,.75f,true);
    private final Set<SectionKey> active=new HashSet<>();
    private final int triangleBudget;
    private Set<ColumnKey> columns;
    private int pending;
    private SceneBounds bounds;
    private double cameraX,cameraY,cameraZ;
    private long epoch,revision,rebuilt,hits,cachedTriangles;

    public SectionSceneCache() {this(MAX_CACHED_TRIANGLES);}
    SectionSceneCache(int triangleBudget) {
        if(triangleBudget<1||triangleBudget>MAX_CACHED_TRIANGLES)throw new IllegalArgumentException("Invalid retained triangle budget");
        this.triangleBudget=triangleBudget;
    }

    public void retarget(SceneBounds next,double x,double y,double z) {
        Objects.requireNonNull(next);
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z))throw new IllegalArgumentException("Invalid scene camera");
        cameraX=x;cameraY=y;cameraZ=z;
        if(next.equals(bounds))return;
        bounds=next;var previous=new HashSet<>(active);active.clear();
        int sx=Math.floorDiv(next.x(),16),sy=Math.floorDiv(next.y(),16),sz=Math.floorDiv(next.z(),16);
        for(int dy=0;dy<SceneBounds.HEIGHT/16;dy++)for(int dz=0;dz<SceneBounds.WIDTH/16;dz++)for(int dx=0;dx<SceneBounds.WIDTH/16;dx++) {
            var key=new SectionKey(sx+dx,sy+dy,sz+dz);active.add(key);
            var entry=entries.get(key);
            if(entry==null){entries.put(key,new Entry(++epoch));columns=null;}else if(!entry.dirty)hits++;
        }
        for(var key:previous)if(!active.contains(key))entries.get(key).version++;
        trim();pending=0;for(var key:active){var entry=entries.get(key);if(entry.loaded&&entry.dirty)pending++;}revision++;
    }
    private void trim() {
        var iterator=entries.entrySet().iterator();
        while(entries.size()>MAX_SECTIONS && iterator.hasNext()) {
            var pair=iterator.next();if(!active.contains(pair.getKey())){cachedTriangles-=triangles(pair.getValue().mesh);iterator.remove();columns=null;}
        }
    }
    public SceneBounds bounds() {return bounds;}
    public Set<SectionKey> cachedKeys() {return Set.copyOf(entries.keySet());}
    /** Stable until membership changes; callers do not need to rebuild it every frame. */
    public Set<ColumnKey> cachedColumns() {
        if(columns==null){var keys=new HashSet<ColumnKey>();for(var key:entries.keySet())keys.add(new ColumnKey(key.x(),key.z()));columns=Set.copyOf(keys);}
        return columns;
    }
    public long revision() {return revision;}
    public long cachedTriangles() {return cachedTriangles;}
    public long cachedBytes() {return cachedTriangles*TriangleScene.TRIANGLE_BYTES;}
    public int pendingSections() {return pending;}
    public int resolvedSections() {return active.size()-pendingSections();}
    public Metrics metrics() {return new Metrics(rebuilt,hits,entries.size(),pendingSections());}
    public Ticket next() {
        if(pending==0)return null;
        SectionKey best=null;double distance=Double.POSITIVE_INFINITY;boolean urgent=false;
        for(var key:active) {
            var entry=entries.get(key);if(!entry.loaded||!entry.dirty)continue;
            double dx=key.blockX()+8-cameraX,dy=key.blockY()+8-cameraY,dz=key.blockZ()+8-cameraZ;
            double next=dx*dx+dy*dy+dz*dz;
            if(best==null || entry.urgent&&!urgent || entry.urgent==urgent && (next<distance || next==distance && key.compareTo(best)<0)){
                distance=next;best=key;urgent=entry.urgent;
            }
        }
        return best==null?null:new Ticket(best,entries.get(best).identity,entries.get(best).version);
    }
    public boolean valid(Ticket ticket) {
        var entry=entries.get(ticket.key());
        return active.contains(ticket.key()) && entry!=null && ticket.epoch()==entry.identity && entry.loaded
            && entry.dirty && entry.version==ticket.version();
    }
    /** Camera distance alone cannot repeatedly throw away unfinished section work. */
    public boolean shouldPreempt(Ticket current,Ticket candidate) {
        if(!valid(current))return true;
        return candidate!=null && valid(candidate) && entries.get(candidate.key()).urgent && !entries.get(current.key()).urgent;
    }
    public boolean complete(Ticket ticket,TriangleScene mesh) {
        Objects.requireNonNull(mesh);
        if(mesh.x()!=0||mesh.y()!=0||mesh.z()!=0)throw new IllegalArgumentException("Section meshes must use local coordinates");
        if(!valid(ticket))return false;
        var entry=entries.get(ticket.key());
        long required=cachedTriangles-triangles(entry.mesh)+mesh.triangles();
        if(required>triangleBudget) {
            var iterator=entries.entrySet().iterator();
            while(required>triangleBudget && iterator.hasNext()) {
                var pair=iterator.next();
                if(!active.contains(pair.getKey()) && pair.getValue().mesh!=null) {
                    int count=pair.getValue().mesh.triangles();cachedTriangles-=count;required-=count;iterator.remove();columns=null;
                }
            }
            if(required>triangleBudget)throw new IllegalArgumentException("Retained world triangle budget exceeded");
        }
        cachedTriangles=required;entry.mesh=mesh;entry.dirty=false;entry.urgent=false;pending--;rebuilt++;revision++;return true;
    }
    private static int triangles(TriangleScene mesh) {return mesh==null?0:mesh.triangles();}
    public void invalidateBlock(int x,int y,int z) {
        for(int sy=Math.floorDiv(y-1,16);sy<=Math.floorDiv(y+1,16);sy++)
            for(int sz=Math.floorDiv(z-1,16);sz<=Math.floorDiv(z+1,16);sz++)
                for(int sx=Math.floorDiv(x-1,16);sx<=Math.floorDiv(x+1,16);sx++)invalidate(new SectionKey(sx,sy,sz));
    }
    private void invalidate(SectionKey key) {
        var entry=entries.get(key);if(entry==null)return;
        if(active.contains(key)&&entry.loaded&&!entry.dirty)pending++;
        entry.version++;entry.dirty=true;entry.urgent=true;
    }
    /** Full packets and biome blending affect neighboring columns too. */
    public void invalidateColumn(int x,int z) {
        for(var pair:entries.entrySet())if(Math.abs((long)pair.getKey().x()-x)<=1 && Math.abs((long)pair.getKey().z()-z)<=1) {
            var entry=pair.getValue();if(active.contains(pair.getKey())&&entry.loaded&&!entry.dirty)pending++;
            entry.version++;entry.dirty=true;entry.urgent=true;
        }
    }
    public void observeColumn(int x,int z,boolean loaded) {
        for(var pair:entries.entrySet())if(pair.getKey().x()==x && pair.getKey().z()==z) {
            var entry=pair.getValue();if(entry.loaded==loaded)continue;
            if(active.contains(pair.getKey())&&entry.loaded&&entry.dirty)pending--;
            entry.loaded=loaded;entry.version++;entry.dirty=loaded;
            if(active.contains(pair.getKey())&&loaded)pending++;
            if(!loaded){cachedTriangles-=triangles(entry.mesh);entry.mesh=null;}
            if(active.contains(pair.getKey()))revision++;
        }
    }
    public void unloadColumn(int x,int z) {observeColumn(x,z,false);invalidateColumn(x,z);}
    public void invalidateAll() {for(var pair:entries.entrySet()){var entry=pair.getValue();if(active.contains(pair.getKey())&&entry.loaded&&!entry.dirty)pending++;entry.version++;entry.dirty=true;entry.urgent=true;}}
    public void reset() {epoch++;revision++;entries.clear();active.clear();columns=null;pending=0;bounds=null;rebuilt=hits=cachedTriangles=0;}
    public Snapshot snapshot() {
        if(bounds==null)throw new IllegalStateException("Scene bounds have not been selected");
        return snapshot(bounds);
    }
    /** Keep the previous covered volume while new outer sections are extracted. */
    public Snapshot publicationSnapshot(SceneBounds previous) {
        if(bounds==null)return null;
        if(covered(bounds))return snapshot(bounds);
        return previous!=null && covered(previous)?snapshot(previous):null;
    }
    private boolean covered(SceneBounds area) {
        for(int y=Math.floorDiv(area.y(),16);y<Math.floorDiv(area.y(),16)+SceneBounds.HEIGHT/16;y++)
            for(int z=Math.floorDiv(area.z(),16);z<Math.floorDiv(area.z(),16)+SceneBounds.WIDTH/16;z++)
                for(int x=Math.floorDiv(area.x(),16);x<Math.floorDiv(area.x(),16)+SceneBounds.WIDTH/16;x++){
                    var entry=entries.get(new SectionKey(x,y,z));if(entry==null || entry.loaded&&entry.mesh==null)return false;
                }
        return true;
    }
    private Snapshot snapshot(SceneBounds area) {
        var sections=new ArrayList<Section>();
        int missing=0;
        for(int y=Math.floorDiv(area.y(),16);y<Math.floorDiv(area.y(),16)+SceneBounds.HEIGHT/16;y++)
            for(int z=Math.floorDiv(area.z(),16);z<Math.floorDiv(area.z(),16)+SceneBounds.WIDTH/16;z++)
                for(int x=Math.floorDiv(area.x(),16);x<Math.floorDiv(area.x(),16)+SceneBounds.WIDTH/16;x++) {
                    var key=new SectionKey(x,y,z);var entry=entries.get(key);
                    if(entry==null){missing++;continue;}
                    if(entry.loaded&&entry.dirty)missing++;
                    if(entry.loaded&&entry.mesh!=null)sections.add(new Section(key,entry.mesh));
                }
        sections.sort(Comparator.comparing(Section::key));
        return new Snapshot(area,revision,missing==0,missing,sections);
    }
}
