package com.koper.greenmarine.client;

import com.koper.greenmarine.api.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LightSelectionTest {
    private Light lamp(double x,float radius) { return new Light(x,64,0,1,1,1,radius,1); }
    @Test void registryReusesImmutableSnapshotsUntilActualMutation() {
        var registry = new LightRegistry(); long id=registry.create(lamp(0,4));
        var first=registry.snapshot(); assertSame(first,registry.snapshot());
        assertTrue(registry.update(id,lamp(0,4))); assertSame(first,registry.snapshot());
        registry.update(id,lamp(1,4)); assertNotSame(first,registry.snapshot());
        assertEquals(0,first.getFirst().x());
    }
    @Test void distantInsertionOrderCannotStarveNearbySources() {
        var lights=new ArrayList<Light>(); for (int i=0;i<100;i++) lights.add(lamp(1000+i,4));
        var near=lamp(2,4); lights.add(near);
        var selector=new LightSelector(); selector.select(lights,0,64,0,16);
        assertEquals(16,selector.size()); assertSame(near,selector.get(0));
    }
    @Test void keepsOutOfRadiusLightsAndPrioritizesLargeInfluenceRegions() {
        var wide=lamp(10,12); var small=lamp(5,1);
        var selector=new LightSelector(); selector.select(List.of(small,wide),0,64,0,16);
        assertEquals(2,selector.size()); assertSame(wide,selector.get(0));
        selector.select(List.of(),0,0,0,16); assertEquals(0,selector.size());
    }

    @Test void conservativeFrustumCullingKeepsIntersectingSpheresOnBothBackends() {
        var behind=new Light(0,0,10,1,1,1,1,1);
        var front=new Light(0,0,-10,1,1,1,2,1);
        var crossing=new Light(0,0,1,1,1,1,4,1);
        for(boolean zeroToOne:new boolean[]{true,false}) {
            var clip=new org.joml.Matrix4f().perspective((float)Math.PI/2,1,100,1,zeroToOne);
            var selector=new LightSelector(); selector.select(List.of(behind,front,crossing),0,0,0,16,clip);
            assertEquals(2,selector.size());
            assertSame(crossing,selector.get(0)); assertSame(front,selector.get(1));
        }
    }
}
