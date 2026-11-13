package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.PackPass.Phase;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PackFramePhasesTest {
    @Test void setupRunsOnceAndEachHostPhaseRunsOncePerFrame() {
        var state=new PackFramePhases();var pack=new Object();var world=new Object();
        assertTrue(state.begin(pack,world,80,60,1).reset());
        for(var phase:Phase.values()) {assertTrue(state.enter(phase));assertFalse(state.enter(phase));}
        assertFalse(state.begin(pack,world,80,60,1).frame());
        assertFalse(state.enter(Phase.FINAL));
        var next=state.begin(pack,world,80,60,2);
        assertTrue(next.frame());assertFalse(next.reset());
        assertFalse(state.enter(Phase.SETUP));
        assertTrue(state.enter(Phase.BEGIN));assertTrue(state.enter(Phase.DEFERRED));assertTrue(state.enter(Phase.COMPOSITE));assertTrue(state.enter(Phase.FINAL));
    }
    @Test void setupRestartsOnPackWorldViewportOrExplicitReset() {
        var state=new PackFramePhases();var pack=new Object();var world=new Object();
        state.begin(pack,world,80,60,1);assertTrue(state.enter(Phase.SETUP));
        assertTrue(state.begin(pack,new Object(),80,60,2).reset());assertTrue(state.enter(Phase.SETUP));
        assertTrue(state.begin(pack,world,81,60,3).reset());assertTrue(state.enter(Phase.SETUP));
        assertTrue(state.begin(new Object(),world,81,60,4).reset());assertTrue(state.enter(Phase.SETUP));
        state.reset();assertTrue(state.begin(pack,world,81,60,4).reset());assertTrue(state.enter(Phase.SETUP));
    }
    @Test void refusesLateExecutionOfAnEarlierHostBoundary() {
        var state=new PackFramePhases();state.begin(new Object(),new Object(),80,60,1);
        assertTrue(state.enter(Phase.COMPOSITE));
        assertThrows(IllegalStateException.class,()->state.enter(Phase.DEFERRED));
    }
}
