package com.koper.greenmarine.client.rt;

import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActorAtlasPlanTest {
    @Test void packsDifferentTexturesWithTwoPixelGuttersWithoutOverlapsAndDeduplicatesIds() {
        var sources=List.of(new ActorAtlasPlan.Source("skin",64,64),new ActorAtlasPlan.Source("ears",32,16),new ActorAtlasPlan.Source("hat",128,64),new ActorAtlasPlan.Source("skin",64,64));
        var plan=ActorAtlasPlan.pack(sources);
        assertEquals(3,plan.rectangles().size());assertTrue(plan.width()<=2048);assertTrue(plan.height()<=2048);
        var rectangles=new ArrayList<>(plan.rectangles().values());
        for(var rect:rectangles) {
            assertTrue(rect.x()>=2&&rect.y()>=2);assertTrue(rect.x()+rect.width()+2<=plan.width());assertTrue(rect.y()+rect.height()+2<=plan.height());
            for(var other:rectangles)if(rect!=other)assertTrue(rect.x()+rect.width()+2<=other.x()-2 || other.x()+other.width()+2<=rect.x()-2
                || rect.y()+rect.height()+2<=other.y()-2 || other.y()+other.height()+2<=rect.y()-2);
        }
        assertEquals(plan,ActorAtlasPlan.pack(sources));
    }
    @Test void cannotSilentlyResizeOrDropTexturesThatExceedTheAtlasBudget() {
        assertThrows(IllegalArgumentException.class,()->ActorAtlasPlan.pack(List.of(new ActorAtlasPlan.Source("huge",2048,2048),new ActorAtlasPlan.Source("second",64,64))));
        assertThrows(IllegalArgumentException.class,()->ActorAtlasPlan.pack(List.of(new ActorAtlasPlan.Source("bad",0,64))));
        assertThrows(IllegalArgumentException.class,()->ActorAtlasPlan.pack(List.of(new ActorAtlasPlan.Source("duplicate",16,16),new ActorAtlasPlan.Source("duplicate",32,16))));
    }
    @Test void uvRemapPreservesSpatialMatricesAndPlacesEveryFaceInsideItsTextureRectangle() {
        var buffer=ByteBuffer.allocateDirect(18448).order(ByteOrder.nativeOrder());buffer.putFloat(0,2);
        for(int box=0;box<2;box++) {
            int offset=16+box*288;buffer.putFloat(offset,123+box);
            for(int face=0;face<6;face++) {
                buffer.putFloat(offset+96+face*16,.25f).putFloat(offset+96+face*16+12,.5f);
                buffer.putFloat(offset+192+face*16+4,.5f).putFloat(offset+192+face*16+12,.25f);
            }
        }
        ActorAtlasPlan.remap(buffer,1,1,new ActorAtlasPlan.Rect(34,18,64,32),256,128);
        assertEquals(123,buffer.getFloat(16));assertEquals(124,buffer.getFloat(304));
        assertEquals(.25f,buffer.getFloat(16+96));assertEquals(.0625f,buffer.getFloat(304+96));
        assertEquals(.5f*64/256+34f/256,buffer.getFloat(304+96+12));
        assertEquals(.125f,buffer.getFloat(304+192+4));assertEquals(.25f*32/128+18f/128,buffer.getFloat(304+192+12));
        assertEquals(2,buffer.getFloat(0));
    }
    @Test void extendedBuilderRetainsAllSixtyFourBoxesAndKeepsLegacyDefaultSize() {
        var cube=new net.minecraft.client.model.geom.ModelPart.Cube(0,0,0,0,0,1,1,1,0,0,0,false,64,64,EnumSet.allOf(net.minecraft.core.Direction.class));
        var extended=new PlayerReflectionGeometry.Builder(64);
        for(int i=0;i<64;i++)assertTrue(extended.add(cube,new org.joml.Matrix4f().translation(i,0,0)));
        assertFalse(extended.add(cube,new org.joml.Matrix4f()));assertEquals(64,extended.count());assertEquals(18448,extended.data().remaining());
        assertEquals(4624,new PlayerReflectionGeometry.Builder().data().remaining());
    }
    @Test void legacyPayloadsArePaddedForTheExtendedAbiAndNeverTruncated() {
        var legacy=new PlayerReflectionGeometry.Builder().data();
        var padded=PlayerReflection.sized(legacy,true);
        assertEquals(PlayerReflection.EXTENDED_BYTES,padded.remaining());assertTrue(padded.isReadOnly());
        for(int offset=0;offset<PlayerReflection.EXTENDED_BYTES;offset+=4)assertEquals(offset<PlayerReflection.BYTES?legacy.getFloat(offset):0f,padded.getFloat(offset));
        assertSame(legacy,PlayerReflection.sized(legacy,false));
        assertThrows(IllegalArgumentException.class,()->PlayerReflection.sized(new PlayerReflectionGeometry.Builder(64).data(),false));
    }
    @Test void atlasPipelineUsesGreenMarineIdentifiersOnBothShaderStages() {
        var pipeline=ActorAtlas.pipeline();
        assertEquals("greenmarine:actor_atlas",pipeline.getLocation().toString());
        for(var shader:pipeline.getShaders().values())assertEquals("greenmarine:core/actor_atlas",shader.toString());
    }
}
