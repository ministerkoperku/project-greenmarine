package com.koper.greenmarine.nativebridge;

import org.junit.jupiter.api.Test;
import java.lang.foreign.*;
import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeInputTest {
    @Test void heapInputCopiesOnlyTheSelectedBytesAndIsFreedAtReturn() {
        var source=ByteBuffer.allocate(20).order(ByteOrder.nativeOrder());source.putFloat(4,2.5f).putFloat(8,-7.25f);source.position(4).limit(12);
        MemorySegment nativeData;
        try(var input=NativeInput.of(source)) {
            nativeData=input.data();assertTrue(nativeData.isNative());assertEquals(8,nativeData.byteSize());
            assertEquals(2.5f,nativeData.get(ValueLayout.JAVA_FLOAT,0));assertEquals(-7.25f,nativeData.get(ValueLayout.JAVA_FLOAT,4));
            assertEquals(4,source.position());assertEquals(12,source.limit());
        }
        assertThrows(IllegalStateException.class,()->nativeData.get(ValueLayout.JAVA_FLOAT,0));
    }
    @Test void borrowedNativeInputIsNeverFreedByTheBridge() {
        try(var arena=Arena.ofConfined()) {
            var borrowed=arena.allocate(16,4);borrowed.set(ValueLayout.JAVA_FLOAT,0,4);
            try(var input=NativeInput.of(borrowed.asByteBuffer())) {assertEquals(borrowed.address(),input.data().address());}
            assertEquals(4,borrowed.get(ValueLayout.JAVA_FLOAT,0));
        }
    }
}
