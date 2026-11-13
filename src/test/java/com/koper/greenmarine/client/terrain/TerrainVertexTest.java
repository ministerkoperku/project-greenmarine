package com.koper.greenmarine.client.terrain;

import java.nio.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainVertexTest {
    private static float[][] slope() {return new float[][]{{15,1,0,0,0},{15,1,1,0,1},{16,1.5f,1,1,1},{16,1.5f,0,1,0}};}
    @Test void quadUsesGeometryArithmeticUvAndSignedMidpointBytes() {
        var data=TerrainVertex.encodeQuad(slope(),15,0,0,7301,-1,new int[]{0x00abcdef,0x40abcdef,0x80abcdef,0xffabcdef});
        assertEquals(128,data.remaining());
        assertEquals(7301,data.getInt(8));assertEquals(.5f,data.getFloat(16));assertEquals(.5f,data.getFloat(20));
        assertEquals(32,(byte)data.getInt(12));assertEquals(-32,(byte)(data.getInt(12)>>>8));assertEquals(32,(byte)(data.getInt(12)>>>16));
        assertEquals(-1,(byte)(data.getInt(12)>>>24));assertEquals(0x00abcdef,data.getInt(24));
        int normal=data.getInt(0);assertNotEquals(0,(byte)normal);assertTrue((byte)(normal>>>8)>0);assertEquals(0,(byte)(normal>>>16));
        for(int i=0;i<4;i++)assertEquals(normal,data.getInt(i*32));
    }
    @Test void intrinsicEmissionKeepsItsOwnBoundedByteAcrossEveryQuadVertex() throws Exception {
        var encoder = assertDoesNotThrow(() -> TerrainVertex.class.getMethod("encodeQuad",float[][].class,int.class,int.class,int.class,int.class,int.class,int[].class,int.class));
        for (int emission : new int[]{-3,0,7,15,30}) {
            var records = (ByteBuffer)encoder.invoke(null,slope(),15,0,0,7301,-1,new int[4],emission);
            int expected = emission < 0 ? 0 : emission > 15 ? 15 : emission;
            for(int vertex=0;vertex<4;vertex++) {
                assertEquals(expected,records.getInt(vertex*32+28));
                assertEquals(-1,(byte)(records.getInt(vertex*32+12)>>>24));
            }
        }
        assertEquals(0,TerrainVertex.encodeQuad(slope(),15,0,0,-1,1,new int[4]).getInt(28));
    }
    @Test void uvHandednessAndDegenerateFallbackRemainFinite() {
        var a=slope();var b=slope();for(var v:b)v[3]=1-v[3];
        int ta=TerrainVertex.encodeQuad(a,15,0,0,-1,1,new int[4]).getInt(4);
        int tb=TerrainVertex.encodeQuad(b,15,0,0,-1,1,new int[4]).getInt(4);
        assertEquals(-(byte)(ta>>>24),(byte)(tb>>>24));
        var zero=new float[4][5];var data=TerrainVertex.encodeQuad(zero,0,0,0,-1,1,new int[4]);
        assertEquals(-1,data.getInt(8));assertEquals(0,data.getInt(0));assertEquals(0,data.getInt(4));
    }
}
