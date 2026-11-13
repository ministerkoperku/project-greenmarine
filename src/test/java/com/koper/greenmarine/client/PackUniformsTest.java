package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.*;
import java.lang.foreign.MemorySegment;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackUniformsTest {
    private static PackUniformBlock block(String name,String type,String source) {
        return new PackUniformBlock(List.of(new PackUniformBlock.Member(name,type,0,source)));
    }
    private static PackResources resources(Map<String,PackUniformBlock> blocks) {
        return new PackResources(Map.of(),Map.of(),blocks);
    }

    @Test void selectedBlocksPackExactValuesWithoutResolvingUnusedSources() {
        var blocks=new LinkedHashMap<String,PackUniformBlock>();
        blocks.put("Unused",block("unavailable","float","missing"));
        blocks.put("Frame",block("position","vec3","camera"));
        try(var uniforms=new PackUniforms()) {
            var staged=uniforms.stage(resources(blocks),Set.of("Frame"),Map.of("camera",new float[]{2.5f,-3,7}));
            assertEquals(Set.of("Frame"),staged.keySet());
            var bytes=staged.get("Frame");
            assertTrue(bytes.isDirect());assertEquals(16,bytes.remaining());
            assertEquals(2.5f,bytes.getFloat(0));assertEquals(-3,bytes.getFloat(4));assertEquals(7,bytes.getFloat(8));
            assertEquals(0,bytes.getInt(12));
        }
    }

    @Test void repeatedFramesReuseScratchAndResetExplicitlyInvalidatesIt() {
        var resources=resources(Map.of("Frame",block("time","float","tick")));
        var uniforms=new PackUniforms();
        var first=uniforms.stage(resources,Set.of("Frame"),Map.of("tick",1)).get("Frame");
        long address=MemorySegment.ofBuffer(first).address();
        for(int frame=0;frame<10_000;frame++) {
            var next=uniforms.stage(resources,Set.of("Frame"),Map.of("tick",frame)).get("Frame");
            assertEquals(address,MemorySegment.ofBuffer(next).address());assertEquals(frame,next.getFloat(0));
        }
        uniforms.reset();assertThrows(IllegalStateException.class,()->first.getFloat(0));
        var next=uniforms.stage(resources,Set.of("Frame"),Map.of("tick",9)).get("Frame");
        assertEquals(9,next.getFloat(0));uniforms.close();
        assertThrows(IllegalStateException.class,()->next.getFloat(0));
    }

    @Test void changedSizeAndRetiredResourceReleaseTheirScratch() {
        try(var uniforms=new PackUniforms()) {
            var first=uniforms.stage(resources(Map.of("Frame",block("time","float","tick"))),Set.of("Frame"),Map.of("tick",1)).get("Frame");
            var next=uniforms.stage(resources(Map.of("Frame",block("matrix","mat4","matrix"))),Set.of("Frame"),
                Map.of("matrix",new float[16])).get("Frame");
            assertEquals(64,next.remaining());assertThrows(IllegalStateException.class,()->first.getFloat(0));
            uniforms.stage(PackResources.NONE,Set.of(),Map.of());
            assertThrows(IllegalStateException.class,()->next.getFloat(0));
        }
    }

    @Test void invalidLateBlockFailsBeforeTouchingTheGpuEncoder() {
        var blocks=new LinkedHashMap<String,PackUniformBlock>();
        blocks.put("First",block("time","float","tick"));
        blocks.put("Second",block("camera","vec3","camera"));
        try(var uniforms=new PackUniforms()) {
            assertThrows(IllegalArgumentException.class,()->uniforms.prepare(resources(blocks),blocks.keySet(),null,Map.of("tick",1)));
            assertThrows(IllegalArgumentException.class,()->uniforms.prepare(resources(blocks),blocks.keySet(),null,
                Map.of("tick",1,"camera",new float[]{1,2,Float.NaN})));
            assertThrows(IllegalArgumentException.class,()->uniforms.slice("First"));
            assertThrows(IllegalArgumentException.class,()->uniforms.stage(resources(blocks),Set.of("Unknown"),Map.of()));
        }
    }

    @Test void hostUniformRangeRejectsLargeBlocksButAllowsTheExactLimit() {
        var blocks=Map.of("Small",ByteBuffer.allocate(16*1024),"Large",ByteBuffer.allocate(64*1024));
        assertThrows(IllegalArgumentException.class,()->PackUniforms.validateRanges(blocks,16*1024));
        assertDoesNotThrow(()->PackUniforms.validateRanges(blocks,64*1024));
        assertDoesNotThrow(()->PackUniforms.validateRanges(Map.of("Small",blocks.get("Small")),16*1024));
        assertThrows(IllegalArgumentException.class,()->PackUniforms.validateRanges(blocks,0));
    }
}
