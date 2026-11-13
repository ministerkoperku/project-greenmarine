package com.koper.greenmarine.nativebridge;

import java.lang.foreign.*;
import java.nio.ByteBuffer;

/** A synchronous ABI argument. Native scene/mesh preparation copies/uploads before returning. */
final class NativeInput implements AutoCloseable {
    private final Arena owner;
    private final MemorySegment data;
    private NativeInput(Arena owner,MemorySegment data) {this.owner=owner;this.data=data;}
    static NativeInput of(ByteBuffer source) {
        if(source.isDirect())return new NativeInput(null,MemorySegment.ofBuffer(source));
        var arena=Arena.ofConfined();
        try {
            var data=arena.allocate(source.remaining(),4);data.copyFrom(MemorySegment.ofBuffer(source));
            return new NativeInput(arena,data);
        }catch(RuntimeException|Error failure){arena.close();throw failure;}
    }
    MemorySegment data() {return data;}
    @Override public void close() {if(owner!=null)owner.close();}
}
