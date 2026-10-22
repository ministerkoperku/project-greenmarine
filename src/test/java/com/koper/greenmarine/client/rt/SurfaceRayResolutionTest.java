package com.koper.greenmarine.client.rt;

import com.koper.greenmarine.api.pack.Quality;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceRayResolutionTest {
    @Test void externalPacksKeepTheirExistingResolution() {
        assertEquals(SurfaceRayResolution.forViewport(Quality.HIGH,1920,1080),
            SurfaceRayResolution.forPack("custom:surface",Quality.MEDIUM,1920,1080));
        assertEquals(SurfaceRayResolution.forViewport(Quality.MEDIUM,1920,1080),
            SurfaceRayResolution.forPack("greenmarine:solstice_rt",Quality.MEDIUM,1920,1080));
    }
    @Test void mediumHasAFourTimesSmallerDispatchThanHighAt1080p() {
        var medium=SurfaceRayResolution.forViewport(Quality.MEDIUM,1920,1080);
        var high=SurfaceRayResolution.forViewport(Quality.HIGH,1920,1080);
        assertEquals(480,medium.width());assertEquals(270,medium.height());
        assertEquals(960,high.width());assertEquals(540,high.height());
        assertEquals(4L*medium.width()*medium.height(),(long)high.width()*high.height());
    }
    @Test void oddAndTinyViewportsStayPositiveAndQualityCostIsMonotonic() {
        for(int[] viewport:new int[][]{{-1,-9},{1,1},{3,5},{1919,1079},{1921,1081},{0,0},{Integer.MAX_VALUE,Integer.MAX_VALUE}}) {
            long previous=0;
            for(var quality:Quality.values()) {
                var size=SurfaceRayResolution.forViewport(quality,viewport[0],viewport[1]);
                assertTrue(size.width()>0 && size.height()>0);
                assertTrue(size.width()<=(long)Math.max(1,viewport[0]));
                assertTrue(size.height()<=(long)Math.max(1,viewport[1]));
                long pixels=(long)size.width()*size.height();assertTrue(pixels>=previous);previous=pixels;
            }
        }
        var odd=SurfaceRayResolution.forViewport(Quality.MEDIUM,1921,1081);
        assertEquals(481,odd.width());assertEquals(271,odd.height());
    }
}
