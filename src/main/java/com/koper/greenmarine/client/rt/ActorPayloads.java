package com.koper.greenmarine.client.rt;

import java.lang.foreign.*;
import java.nio.*;
import java.util.*;

/** Bounded render-thread payload storage. A slot returns only when its submission releases it. */
final class ActorPayloads implements AutoCloseable {
    private final Arena arena=Arena.ofShared();
    private final List<Slot> slots=new ArrayList<>();
    private final int limit;
    private boolean closed;
    private static final class Slot {
        final MemorySegment memory;boolean busy;
        Slot(MemorySegment memory) {this.memory=memory;}
    }
    final class Lease implements AutoCloseable {
        private final Slot slot;private final ByteBuffer data;private boolean released;
        private Lease(Slot slot,ByteBuffer data) {this.slot=slot;this.data=data;}
        ByteBuffer data() {if(released || closed)throw new IllegalStateException("Actor payload has been released");return data.asReadOnlyBuffer().order(ByteOrder.nativeOrder());}
        @Override public void close() {if(!released) {released=true;slot.busy=false;}}
    }
    ActorPayloads(int limit) {if(limit<1)throw new IllegalArgumentException("Invalid payload slot limit");this.limit=limit;}
    Lease copy(ByteBuffer source,int bytes) {
        if(closed)throw new IllegalStateException("Actor payload storage is closed");
        if(bytes!=PlayerReflectionGeometry.BYTES && bytes!=PlayerReflectionGeometry.EXTENDED_BYTES || source.remaining()>bytes)
            throw new IllegalArgumentException("Invalid actor payload size");
        Slot slot=slots.stream().filter(s->!s.busy).findFirst().orElse(null);
        if(slot==null) {
            if(slots.size()==limit)throw new IllegalStateException("Actor payload slots retained by unfinished submissions");
            slot=new Slot(arena.allocate(PlayerReflectionGeometry.EXTENDED_BYTES,16));slots.add(slot);
        }
        slot.memory.fill((byte)0);var target=slot.memory.asSlice(0,bytes).asByteBuffer().order(ByteOrder.nativeOrder());
        target.put(source.duplicate()).clear();slot.busy=true;
        return new Lease(slot,target.asReadOnlyBuffer().order(ByteOrder.nativeOrder()));
    }
    int allocations() {return slots.size();}
    int active() {return (int)slots.stream().filter(s->s.busy).count();}
    @Override public void close() {
        if(closed)return;
        if(active()!=0)throw new IllegalStateException("Actor submissions must drain before freeing payload storage");
        closed=true;arena.close();slots.clear();
    }
}
