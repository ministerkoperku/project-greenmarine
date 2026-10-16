package com.koper.greenmarine.client.rt;

import com.koper.greenmarine.api.render.FrameContext;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RayFrameContextTest {
    @Test void preservesOldConstructorAndExpiresWorldReadinessWithCallback() {
        var legacy=new FrameContext(Map.of(),1,1,new Matrix4f(),new Matrix4f(),0,0,0,0,true);
        assertFalse(legacy.worldRaysReady());
        var ready=new FrameContext(Map.of(),1,1,new Matrix4f(),new Matrix4f(),0,0,0,0,true,true);
        assertTrue(ready.worldRaysReady());ready.expire();
        assertThrows(IllegalStateException.class,ready::worldRaysReady);
    }
}
