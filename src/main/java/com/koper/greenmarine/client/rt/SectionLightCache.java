package com.koper.greenmarine.client.rt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.Predicate;

/** Availability-aware, bounded extraction of nearby emitting sections; caller orders nearest first. */
final class SectionLightCache<T> {
    private static final int RESCAN_TICKS=20;
    record Key(int x,int y,int z) {}
    record Section<T>(Key key,Object identity,boolean emissive,Supplier<List<T>> scan) {}
    private static final class Entry<T> {
        final Object identity;
        List<T> lights=List.of();
        long scannedTick;
        boolean dirty=true,emissive;
        Entry(Object identity) {this.identity=identity;}
    }
    private final Map<Key,Entry<T>> entries=new HashMap<>();
    private Key collectedAt;
    private long collectedTick,collectedRevision,revision;
    private List<T> collected=List.of();

    /** Stable render frames reuse the source list without observing sections or visiting emitters.
     * A tick, camera-block change, or edit notification permits another bounded extraction. */
    List<T> collect(long tick,int x,int y,int z,Supplier<List<Section<T>>> observe,Predicate<T> withinRange,int budget) {
        if(collectedAt!=null && collectedTick==tick && collectedRevision==revision
                && collectedAt.x()==x && collectedAt.y()==y && collectedAt.z()==z)return collected;
        collected=refresh(tick,observe.get(),budget).stream().filter(withinRange).toList();
        collectedAt=new Key(x,y,z);collectedTick=tick;collectedRevision=revision;
        return collected;
    }

    List<T> refresh(long tick,List<Section<T>> sections,int budget) {
        var active=new HashSet<Key>();
        for(var section:sections) {
            if(section.identity()==null)continue;
            var key=section.key();active.add(key);
            var entry=entries.get(key);
            if(entry==null || entry.identity!=section.identity()) {
                entry=new Entry<>(section.identity());entries.put(key,entry);
            }
            if(!section.emissive()) {
                entry.lights=List.of();entry.dirty=false;entry.scannedTick=tick;
            } else if(!entry.emissive)entry.dirty=true;
            entry.emissive=section.emissive();
        }
        entries.keySet().retainAll(active);
        // Loaded/replaced/edited sections precede the periodic fallback, in camera-distance order.
        for(int priority=0;priority<2 && budget>0;priority++)for(var section:sections) {
            var entry=entries.get(section.key());
            if(entry==null || !entry.emissive || entry.dirty!=(priority==0))continue;
            if(!entry.dirty && tick>=entry.scannedTick && tick-entry.scannedTick<RESCAN_TICKS)continue;
            entry.lights=List.copyOf(section.scan().get());entry.scannedTick=tick;entry.dirty=false;
            if(--budget==0)break;
        }
        var result=new ArrayList<T>();
        for(var section:sections) {
            var entry=entries.get(section.key());if(entry!=null)result.addAll(entry.lights);
        }
        return result;
    }
    void invalidateSection(int x,int y,int z) {
        revision++;
        var entry=entries.get(new Key(x,y,z));if(entry!=null)entry.dirty=true;
    }
    void invalidateColumn(int x,int z) {
        revision++;
        entries.forEach((key,entry)->{if(key.x()==x && key.z()==z)entry.dirty=true;});
    }
    void reset() {entries.clear();collectedAt=null;collected=List.of();revision++;}
}
