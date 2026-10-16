package com.koper.greenmarine.api.render;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceFrameContextTest {
    @Test void legacyFramesKeepSurfaceReadinessFalse() {
        var frame=new FrameContext(Map.of(),16,16,new Matrix4f(),new Matrix4f(),0,0,0,0,true,true);
        assertTrue(frame.worldRaysReady());assertFalse(frame.surfacesReady());assertFalse(frame.surfaceRaysReady());
    }
    @Test void rasterSurfacesRemainAvailableWithoutHardwareRaysAndExpire() {
        var frame=new FrameContext(Map.of(),16,16,new Matrix4f(),new Matrix4f(),0,0,0,0,true,false,true,false);
        assertTrue(frame.surfacesReady());assertFalse(frame.surfaceRaysReady());frame.expire();
        assertThrows(IllegalStateException.class,frame::surfacesReady);
        assertThrows(IllegalStateException.class,frame::surfaceRaysReady);
    }
}
