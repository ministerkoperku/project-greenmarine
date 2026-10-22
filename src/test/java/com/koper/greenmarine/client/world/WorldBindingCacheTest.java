package com.koper.greenmarine.client.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorldBindingCacheTest {
    @Test void identityFrameAndCaptureStateMustAllMatch() {
        var cache=new WorldBindingCache();Object pipeline=new String("same");
        assertFalse(cache.matches(pipeline,1,0));cache.commit(pipeline,1,0);
        assertTrue(cache.matches(pipeline,1,0));
        assertFalse(cache.matches(new String("same"),1,0));
        assertFalse(cache.matches(pipeline,2,0));assertFalse(cache.matches(pipeline,1,1));
        cache.invalidate();assertFalse(cache.matches(pipeline,1,0));
    }
    @Test void switchingAwayAndBackRequiresFreshBindings() {
        var cache=new WorldBindingCache();Object a=new Object(),b=new Object();
        cache.commit(a,1,0);cache.commit(b,1,0);assertFalse(cache.matches(a,1,0));
        cache.commit(a,1,0);assertTrue(cache.matches(a,1,0));
        for(int flags=1;flags<8;flags++)assertFalse(cache.matches(a,1,flags));
    }
    @Test void repeatedSubmissionsReduceOnlyRedundantBindingWork() {
        var cache=new WorldBindingCache();Object pipeline=new Object();int uploads=0;
        for(int i=0;i<20000;i++)if(!cache.matches(pipeline,4,0)) {uploads++;cache.commit(pipeline,4,0);}
        assertEquals(1,uploads);
        cache.commit(pipeline,5,0);assertTrue(cache.matches(pipeline,5,0));
        assertThrows(NullPointerException.class,()->cache.commit(null,1,0));
    }
}
