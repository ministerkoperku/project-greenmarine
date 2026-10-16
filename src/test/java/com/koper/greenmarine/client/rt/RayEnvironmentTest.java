package com.koper.greenmarine.client.rt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RayEnvironmentTest {
    @org.junit.jupiter.api.Test void ambientTracksNightAndWeather() {
        assertEquals(.45f,RaySun.ambient(0,1,true),.00001f);
        assertTrue(RaySun.ambient((float)Math.PI,1,true)<.06f);
        assertTrue(RaySun.ambient(0,.3f,true)<RaySun.ambient(0,1,true));
        assertTrue(RaySun.ambient(0,1,false)<.2f);
    }
    @Test void sceneAnchorDoesNotFollowEveryCameraStepIncludingNegativeCoordinates() {
        assertEquals(SceneBounds.around(-57,71,68),SceneBounds.around(-56,71,69));
        var bounds=SceneBounds.around(-57,71,68);
        assertEquals(0,Math.floorMod(bounds.x(),16));
        assertTrue(-57-bounds.x()>=24);
        assertTrue(bounds.x()+SceneBounds.WIDTH+57>=24);
    }
    @Test void sunDisappearsAtNightAndInDimensionsWithoutTheOverworldSky() {
        assertEquals(0,RaySun.sample((float)Math.PI,1,true,2).w,.00001f);
        assertEquals(0,RaySun.sample(0,1,false,2).w,.00001f);
        assertEquals(0,RaySun.sample(0,0,true,2).w,.00001f);
        var noon=RaySun.sample(0,1,true,2);assertEquals(1,noon.y,.00001f);assertEquals(2,noon.w,.00001f);
        assertEquals(-1,RaySun.sample((float)Math.PI/2,1,true,2).x,.00001f);
    }
}
