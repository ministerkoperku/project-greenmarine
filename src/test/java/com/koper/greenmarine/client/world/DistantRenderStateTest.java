package com.koper.greenmarine.client.world;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DistantRenderStateTest {
    @Test void disablingDhStopsRelightingAndReturnsItsSettings() throws Exception {
        var changes=new ArrayList<Boolean>();
        var state=new DistantRenderState();
        assertTrue(state.update(true,true,changes::add));
        assertTrue(state.update(true,true,changes::add));
        assertFalse(state.update(true,false,changes::add));
        assertFalse(state.update(true,false,changes::add));
        assertEquals(List.of(true,false),changes);
        assertTrue(state.update(true,true,changes::add));
        assertEquals(List.of(true,false,true),changes);
    }

    @Test void packResetReleasesOverridesEvenWhenDhRemainsEnabled() throws Exception {
        var changes=new ArrayList<Boolean>();
        var state=new DistantRenderState();
        state.update(true,true,changes::add);
        assertFalse(state.update(false,true,changes::add));
        assertEquals(List.of(true,false),changes);
    }

    @Test void failedOverrideCanBeRetried() throws Exception {
        var state=new DistantRenderState();
        assertThrows(ReflectiveOperationException.class,()->state.update(true,true,active->{throw new ReflectiveOperationException();}));
        var changes=new ArrayList<Boolean>();
        assertTrue(state.update(true,true,changes::add));
        assertEquals(List.of(true),changes);
    }

    @Test void partialActivationFailureStillAllowsReleasingTheSettingAlreadyWritten() throws Exception {
        var state=new DistantRenderState();
        boolean[] fogOverridden={false};
        assertThrows(ReflectiveOperationException.class,()->state.update(true,true,active->{
            fogOverridden[0]=active;
            if(active)throw new ReflectiveOperationException("Shading override failed after fog override");
        }));
        state.update(false,false,active->fogOverridden[0]=active);
        assertFalse(fogOverridden[0]);
    }
}
