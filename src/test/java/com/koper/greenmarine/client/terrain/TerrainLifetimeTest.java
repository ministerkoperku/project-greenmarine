package com.koper.greenmarine.client.terrain;
import com.koper.greenmarine.api.render.TerrainCapture;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class TerrainLifetimeTest {
    @Test void oldBuildsCannotPublishAcrossWorldAtlasOrPackGenerations(){
        TerrainCapture.configure(TerrainCapture.MappingSnapshot.unmapped(),TerrainCapture.Profile.IRIS_SEPARATE_AO);
        long stamp=TerrainCapture.configuration().generation();
        var first=new TerrainSidecarData(stamp,-3,4,2,Map.of());assertTrue(first.current());
        TerrainCapture.invalidate();assertFalse(first.current());
        var rebuilt=new TerrainSidecarData(TerrainCapture.configuration().generation(),-3,4,2,Map.of());
        assertTrue(rebuilt.current());first.close();assertTrue(rebuilt.current());
        rebuilt.close();rebuilt.close();assertFalse(rebuilt.current());
        TerrainCapture.configure(TerrainCapture.MappingSnapshot.unmapped(),TerrainCapture.Profile.DISABLED);
    }
    @Test void layerBytesAreImmutableAndDrawOffsetsStayIndependent(){
        byte[] source=new byte[32],host=new byte[28];source[3]=7;host[2]=9;
        var layer=new TerrainSidecarData.Layer(source,host);source[3]=0;host[2]=0;
        assertEquals(7,layer.records().get(3));assertEquals(9,layer.hostVertices().get(2));
        assertTrue(layer.records().isReadOnly());assertEquals(1,layer.vertexCount());
        assertEquals(7003,TerrainSidecars.vertexIndex(4003,4000,7000));
        assertEquals(9003,TerrainSidecars.vertexIndex(8003,8000,9000));
    }
}
