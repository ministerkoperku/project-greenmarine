package com.koper.greenmarine.client.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldFrameInputsTest {
    @Test void preparationConsumesEarlyInputsWithoutResettingEarlyPhaseWork() {
        var lifecycle=new WorldPrograms.InputLifecycle();
        int[] initializations={0};boolean[] surfaceReady={true};int[] origin={12};
        Runnable initialize=()->{initializations[0]++;surfaceReady[0]=false;origin[0]=34;};
        lifecycle.begin(initialize);
        assertFalse(surfaceReady[0]);assertEquals(34,origin[0]);
        // An early phase can initialize owned state between the host begin and prepare hooks.
        origin[0]=56;
        lifecycle.consume(initialize);
        assertEquals(1,initializations[0]);assertEquals(56,origin[0]);
        // A later opaque/material capture remains ready until the next frame starts.
        surfaceReady[0]=true;
        lifecycle.finish();assertTrue(surfaceReady[0]);
        lifecycle.begin(initialize);assertFalse(surfaceReady[0]);assertEquals(2,initializations[0]);
    }
    @Test void directPreparationInitializesEachFrameWithoutABeginHook() {
        var lifecycle=new WorldPrograms.InputLifecycle();int[] initializations={0};
        lifecycle.consume(()->initializations[0]++);
        lifecycle.consume(()->initializations[0]++);
        assertEquals(2,initializations[0]);
    }
    @Test void worldFinishAndResetInvalidateAnUnconsumedBegin() throws Exception {
        var field=WorldPrograms.class.getDeclaredField("INPUTS");field.setAccessible(true);
        var lifecycle=(WorldPrograms.InputLifecycle)field.get(null);int[] initializations={0};
        lifecycle.begin(()->initializations[0]++);WorldPrograms.finishWorld();
        lifecycle.consume(()->initializations[0]++);
        assertEquals(2,initializations[0]);
        lifecycle.begin(()->initializations[0]++);WorldPrograms.reset();
        lifecycle.consume(()->initializations[0]++);
        assertEquals(4,initializations[0]);
    }
    @Test void failedInitializationCannotLeaveAnOlderFramePending() {
        var lifecycle=new WorldPrograms.InputLifecycle();int[] initializations={0};
        lifecycle.begin(()->initializations[0]++);
        assertThrows(IllegalStateException.class,()->lifecycle.begin(()->{throw new IllegalStateException("inputs unavailable");}));
        lifecycle.consume(()->initializations[0]++);
        assertEquals(2,initializations[0]);
        assertThrows(IllegalStateException.class,()->lifecycle.consume(()->{throw new IllegalStateException("inputs unavailable");}));
        lifecycle.consume(()->initializations[0]++);assertEquals(3,initializations[0]);
    }
}
