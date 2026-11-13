package com.koper.greenmarine.api.pack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackUniformBlockTest {
    private static PackUniformBlock block() {
        return new PackUniformBlock(List.of(
            new PackUniformBlock.Member("time","float","frame.time"),
            new PackUniformBlock.Member("camera","vec3","cameraPosition"),
            new PackUniformBlock.Member("weights","float",2,"custom.weights")));
    }

    @Test void packingMapsExplicitHostKeysInDeclarationOrder() {
        var block=block();
        assertEquals(List.of("time","camera","weights"),new ArrayList<>(block.sources().keySet()));
        assertEquals("frame.time",block.sources().get("time"));
        assertEquals(List.of(0,16,32),block.layout().slots().stream().map(slot -> slot.offset()).toList());
        var packed=block.pack(Map.of("frame.time",2f,"cameraPosition",new float[]{3,4,5},
            "custom.weights",List.of(6,7),"unrelatedHostValue",99));
        assertEquals(64,packed.limit());
        assertEquals(2,packed.getFloat(0));assertEquals(3,packed.getFloat(16));assertEquals(5,packed.getFloat(24));
        assertEquals(6,packed.getFloat(32));assertEquals(7,packed.getFloat(48));
        assertEquals(0,packed.getInt(28));assertEquals(0,packed.getInt(52));
    }

    @Test void missingNullAndWrongHostValuesNeverBecomeZeroAndDoNotMutateTheDestination() {
        var block=block();var destination=ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);destination.position(3);
        var nullSource=new HashMap<String,Object>();nullSource.put("frame.time",null);
        nullSource.put("cameraPosition",new float[]{1,2,3});nullSource.put("custom.weights",List.of(1,2));
        for(var host:List.<Map<String,?>>of(Map.of(),nullSource,
            Map.of("frame.time",1,"cameraPosition",new float[]{1,2},"custom.weights",List.of(1,2)),
            Map.of("frame.time",1,"cameraPosition",new float[]{1,2,3},"custom.weights",List.of(1,Double.NaN)))) {
            for(int i=0;i<64;i++)destination.put(i,(byte)7);
            assertThrows(IllegalArgumentException.class,() -> block.write(destination,host));
            for(int i=0;i<64;i++)assertEquals(7,destination.get(i));
            assertEquals(3,destination.position());assertEquals(ByteOrder.BIG_ENDIAN,destination.order());
        }
        var missing=assertThrows(IllegalArgumentException.class,() -> block.pack(Map.of()));
        assertTrue(missing.getMessage().contains("frame.time"),missing.getMessage());
    }

    @Test void memberListsAndSourceMapsAreImmutableSnapshots() {
        var members=new ArrayList<PackUniformBlock.Member>();members.add(new PackUniformBlock.Member("time","float","frameTime"));
        var block=new PackUniformBlock(members);members.clear();
        assertEquals(1,block.members().size());
        assertThrows(UnsupportedOperationException.class,() -> block.members().clear());
        assertThrows(UnsupportedOperationException.class,() -> block.sources().clear());
        var packed=block.pack(Map.of("frameTime",0));assertEquals(0,packed.getFloat(0));
    }

    @Test void oldConstructorsKeepEmptyUniformDeclarations() {
        assertTrue(new PackResources(Map.of(),Map.of()).uniforms().isEmpty());
        var pass=new PackPass("scene",PackPass.Stage.SCENE,"demo:scene",null,PackPass.Requirement.NONE,Quality.LOW,1,null);
        assertTrue(pass.uniforms().isEmpty());
    }
}
