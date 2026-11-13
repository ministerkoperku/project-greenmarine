package com.koper.greenmarine.client.rt;

import org.junit.jupiter.api.Test;
import java.lang.foreign.MemorySegment;
import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;

class ActorPayloadsTest {
    private static ByteBuffer value(float value) {return ByteBuffer.allocate(16).order(ByteOrder.nativeOrder()).putFloat(0,value);}
    @Test void heldSubmissionsKeepTheirBytesAndOnlyReleasedSlotsAreReused() {
        try(var pool=new ActorPayloads(2)) {
            var a=pool.copy(value(1),PlayerReflection.BYTES);var b=pool.copy(value(2),PlayerReflection.EXTENDED_BYTES);
            long address=MemorySegment.ofBuffer(a.data()).address();
            assertThrows(IllegalStateException.class,()->pool.copy(value(9),PlayerReflection.BYTES));
            assertEquals(1,a.data().getFloat(0));assertEquals(2,b.data().getFloat(0));
            a.close();var c=pool.copy(value(3),PlayerReflection.BYTES);
            assertEquals(address,MemorySegment.ofBuffer(c.data()).address());assertEquals(2,b.data().getFloat(0));
            a.close();assertThrows(IllegalStateException.class,()->pool.copy(value(4),PlayerReflection.BYTES));
            c.close();b.close();
        }
    }
    @Test void closeWaitsForRetainedSubmissionsAndExplicitlyInvalidatesReleasedMemory() {
        var pool=new ActorPayloads(1);var lease=pool.copy(value(7),PlayerReflection.EXTENDED_BYTES);var bytes=lease.data();
        assertThrows(IllegalStateException.class,pool::close);assertEquals(7,bytes.getFloat(0));
        lease.close();pool.close();
        assertThrows(IllegalStateException.class,()->bytes.getFloat(0));
    }
    @Test void steadyStateReusesNativeAllocationAndClearsPreviousActorTail() {
        try(var pool=new ActorPayloads(1)) {
            for(int frame=0;frame<50_000;frame++)try(var lease=pool.copy(value(frame%65),PlayerReflection.EXTENDED_BYTES)) {
                assertEquals(frame%65,lease.data().getFloat(0));assertEquals(0,lease.data().getLong(PlayerReflection.EXTENDED_BYTES-8));
            }
            assertEquals(1,pool.allocations());assertEquals(0,pool.active());
        }
    }
}
