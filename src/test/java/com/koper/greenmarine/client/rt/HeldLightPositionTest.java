package com.koper.greenmarine.client.rt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeldLightPositionTest {
    @Test void lookingDownKeepsTheEmitterOutsideTorsoAndRestingArm() {
        var offset=HeldLightPosition.offset(0,-1,0,0,true,.6);
        double y=1.62+offset.y();
        assertFalse(Math.abs(offset.x())<=.25 && Math.abs(offset.z())<=.125 && y>=.6 && y<=1.4,
            "Held emitter is inside the standing torso: "+offset);
        assertFalse(Math.abs(offset.x())>=.25 && Math.abs(offset.x())<=.5 && Math.abs(offset.z())<=.125 && y>=.4 && y<=1.4,
            "Held emitter is inside the resting arm: "+offset);
    }
    @Test void nearVerticalPitchHasNoPositionJump() {
        var vertical=HeldLightPosition.offset(0,-1,0,0,true,.6);
        var almost=HeldLightPosition.offset(0,-Math.sqrt(1-.0002*.0002),.0002,0,true,.6);
        assertEquals(vertical.x(),almost.x(),.001);
        assertEquals(vertical.z(),almost.z(),.001);
    }
    @Test void horizontalPlacementRetainsTheExistingHandSideAndReach() {
        var right=HeldLightPosition.offset(0,0,1,0,true,.6);
        var left=HeldLightPosition.offset(0,0,1,0,false,.6);
        assertEquals(-.35,right.x(),.00001);assertEquals(.35,right.z(),.00001);
        assertEquals(.35,left.x(),.00001);assertEquals(.35,left.z(),.00001);
        assertEquals(-.45,right.y(),.00001);
    }
    @Test void lookingDownRetainsYawAndTheSelectedHand() {
        var north=HeldLightPosition.offset(0,-1,0,180,true,.6);
        var left=HeldLightPosition.offset(0,-1,0,180,false,.6);
        assertTrue(north.z()<-.3);assertTrue(north.x()>.3);
        assertTrue(left.z()<-.3);assertTrue(left.x()<-.3);
    }
    @Test void wideBodiesDoNotContainTheEmitter() {
        var offset=HeldLightPosition.offset(0,-1,0,0,true,1.4);
        assertTrue(Math.abs(offset.x())>.7 && offset.z()>.7);
    }
}
