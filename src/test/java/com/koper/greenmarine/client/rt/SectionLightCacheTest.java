package com.koper.greenmarine.client.rt;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionLightCacheTest {
    private static SectionLightCache.Section<String> section(int x,Object identity,String light,AtomicInteger scans) {
        return new SectionLightCache.Section<>(new SectionLightCache.Key(x,0,0),identity,true,()-> {
            scans.incrementAndGet();return List.of(light);
        });
    }

    @Test void unchangedFramesInTheSameTickDoNotRevisitSectionAvailabilityOrCachedSources() {
        var cache=new SectionLightCache<String>();var scans=new AtomicInteger();var polls=new AtomicInteger();var selections=new AtomicInteger();
        var identity=new Object();
        java.util.function.Supplier<List<SectionLightCache.Section<String>>> observe=()->{
            polls.incrementAndGet();return List.of(section(0,identity,"lava",scans));
        };
        java.util.function.Predicate<String> select=light->{selections.incrementAndGet();return true;};
        assertEquals(List.of("lava"),cache.collect(100,0,0,0,observe,select,4));
        for(int i=0;i<120;i++)assertEquals(List.of("lava"),cache.collect(100,0,0,0,observe,select,4));
        assertEquals(1,polls.get());assertEquals(1,selections.get());assertEquals(1,scans.get());
        cache.collect(101,0,0,0,observe,select,4);
        assertEquals(2,polls.get());assertEquals(1,scans.get());
    }
    @Test void sameTickInvalidationImmediatelyRefreshesTheMemoAndThenReusesIt() {
        var cache=new SectionLightCache<String>();var scans=new AtomicInteger();var polls=new AtomicInteger();var identity=new Object();
        var current=new java.util.concurrent.atomic.AtomicReference<>("torch");
        java.util.function.Supplier<List<SectionLightCache.Section<String>>> observe=()->{
            polls.incrementAndGet();return List.of(section(0,identity,current.get(),scans));
        };
        assertEquals(List.of("torch"),cache.collect(100,0,0,0,observe,l->true,4));
        current.set("lantern");cache.invalidateSection(0,0,0);
        assertEquals(List.of("lantern"),cache.collect(100,0,0,0,observe,l->true,4));
        assertEquals(List.of("lantern"),cache.collect(100,0,0,0,observe,l->true,4));
        assertEquals(2,polls.get());assertEquals(2,scans.get());
    }
    @Test void cameraBlockMovementRetargetsTheMemoInTheSameTick() {
        var cache=new SectionLightCache<Integer>();var polls=new AtomicInteger();var position=new AtomicInteger();
        java.util.function.Supplier<List<SectionLightCache.Section<Integer>>> observe=()->{
            polls.incrementAndGet();int at=position.get();
            return List.of(new SectionLightCache.Section<>(new SectionLightCache.Key(at,0,0),new Object(),true,()->List.of(at)));
        };
        assertEquals(List.of(0),cache.collect(100,0,0,0,observe,light->Math.abs(light)<=1,4));
        position.set(20);
        assertEquals(List.of(20),cache.collect(100,20,0,0,observe,light->Math.abs(light-20)<=1,4));
        assertEquals(2,polls.get());
    }
    @Test void nextTickAdvancesBoundedInitialExtractionWithoutRepeatedPollingWithinATick() {
        var cache=new SectionLightCache<String>();var polls=new AtomicInteger();var scans=new AtomicInteger();
        var sections=List.of(section(0,new Object(),"near",scans),section(1,new Object(),"far",scans));
        java.util.function.Supplier<List<SectionLightCache.Section<String>>> observe=()->{polls.incrementAndGet();return sections;};
        assertEquals(List.of("near"),cache.collect(100,0,0,0,observe,l->true,1));
        assertEquals(List.of("near"),cache.collect(100,0,0,0,observe,l->true,1));
        assertEquals(List.of("near","far"),cache.collect(101,0,0,0,observe,l->true,1));
        assertEquals(2,polls.get());assertEquals(2,scans.get());
    }
    @Test void newlyLoadedSectionAddsItsLavaWithoutWaitingForThePeriodicScan() {
        var cache=new SectionLightCache<String>();var scans=new AtomicInteger();
        assertEquals(List.of(),cache.refresh(100,List.of(),4));
        assertEquals(List.of("lava"),cache.refresh(101,List.of(section(0,new Object(),"lava",scans)),4));
        assertEquals(1,scans.get());
    }
    @Test void unloadingRemovesLightsBeforeTheNextPeriodicScan() {
        var cache=new SectionLightCache<String>();var scans=new AtomicInteger();
        cache.refresh(100,List.of(section(0,new Object(),"lava",scans)),4);
        assertEquals(List.of(),cache.refresh(101,List.of(),4));
    }
    @Test void blockEditRefreshesOnlyItsSectionAndCoalescesRepeatedNotifications() {
        var cache=new SectionLightCache<String>();var a=new Object();var b=new Object();var scans=new AtomicInteger();
        cache.refresh(100,List.of(section(0,a,"torch",scans),section(1,b,"lava",scans)),4);
        for(int i=0;i<50;i++)cache.invalidateSection(0,0,0);
        assertEquals(List.of("lantern","lava"),cache.refresh(101,
            List.of(section(0,a,"lantern",scans),section(1,b,"lava",scans)),4));
        assertEquals(3,scans.get());
    }
    @Test void firstFrameHonoursTheSectionBudgetAndDrainsTheRemainderOnFollowingFrames() {
        var cache=new SectionLightCache<String>();var scans=new AtomicInteger();
        var sections=List.of(section(0,new Object(),"near",scans),section(1,new Object(),"middle",scans),section(2,new Object(),"far",scans));
        assertEquals(List.of("near","middle"),cache.refresh(100,sections,2));
        assertEquals(2,scans.get());
        assertEquals(List.of("near","middle","far"),cache.refresh(100,sections,2));
        assertEquals(3,scans.get());
    }
    @Test void unchangedOverlapIsReusedAcrossCameraMovement() {
        var cache=new SectionLightCache<String>();var scans=new AtomicInteger();var identity=new Object();
        var sections=List.of(section(0,identity,"lava",scans));
        cache.refresh(100,sections,4);
        assertEquals(List.of("lava","torch"),cache.refresh(101,
            List.of(section(0,identity,"lava",scans),section(1,new Object(),"torch",scans)),4));
        assertEquals(2,scans.get());
    }
    @Test void newlyAvailableLightsTakePriorityOverPeriodicRefreshes() {
        var cache=new SectionLightCache<String>();var scans=new AtomicInteger();var a=new Object();
        cache.refresh(100,List.of(section(0,a,"torch",scans)),4);
        assertEquals(List.of("torch","lava"),cache.refresh(120,
            List.of(section(0,a,"lantern",scans),section(1,new Object(),"lava",scans)),1));
        assertEquals(2,scans.get());
    }
    @Test void replacingAChunkCannotKeepItsOldLightsWhenTheScanBudgetIsExhausted() {
        var cache=new SectionLightCache<String>();var scans=new AtomicInteger();
        cache.refresh(100,List.of(section(0,new Object(),"lava",scans)),4);
        assertEquals(List.of(),cache.refresh(101,List.of(section(0,new Object(),"torch",scans)),0));
        assertEquals(1,scans.get());
    }
    @Test void emptySectionsDoNotConsumeTheEmitterScanBudget() {
        var cache=new SectionLightCache<String>();var scans=new AtomicInteger();
        var empty=new SectionLightCache.Section<String>(new SectionLightCache.Key(0,0,0),new Object(),false,
            ()->{throw new AssertionError("Empty palette must skip extraction");});
        assertEquals(List.of("lava"),cache.refresh(100,List.of(empty,section(1,new Object(),"lava",scans)),1));
        assertEquals(1,scans.get());
    }
    @Test void sectionPaletteBecomingEmptyRemovesItsOldLightWithoutAnExtraction() {
        var cache=new SectionLightCache<String>();var scans=new AtomicInteger();var identity=new Object();
        cache.refresh(100,List.of(section(0,identity,"lava",scans)),4);
        var empty=new SectionLightCache.Section<String>(new SectionLightCache.Key(0,0,0),identity,false,
            ()->{throw new AssertionError("Empty palette must skip extraction");});
        assertEquals(List.of(),cache.refresh(101,List.of(empty),0));
        assertEquals(1,scans.get());
    }
    @Test void columnInvalidationAndResetDiscardOnlyTheAppropriateCachedState() {
        var cache=new SectionLightCache<String>();var scans=new AtomicInteger();var a=new Object();var b=new Object();
        cache.refresh(100,List.of(section(0,a,"torch",scans),section(1,b,"lava",scans)),4);
        cache.invalidateColumn(1,0);
        var sections=List.of(section(0,a,"torch",scans),section(1,b,"lantern",scans));
        assertEquals(List.of("torch","lantern"),cache.refresh(101,sections,4));
        assertEquals(3,scans.get());
        cache.reset();assertEquals(List.of(),cache.refresh(101,sections,0));
    }
    @Test void missedNotificationsEventuallyRefreshAndClockRewindDoesNotFreezeTheCache() {
        var cache=new SectionLightCache<String>();var scans=new AtomicInteger();var identity=new Object();
        cache.refresh(100,List.of(section(0,identity,"torch",scans)),4);
        assertEquals(List.of("torch"),cache.refresh(119,List.of(section(0,identity,"lantern",scans)),4));
        assertEquals(List.of("lantern"),cache.refresh(120,List.of(section(0,identity,"lantern",scans)),4));
        assertEquals(List.of("lava"),cache.refresh(50,List.of(section(0,identity,"lava",scans)),4));
        assertEquals(3,scans.get());
    }
}
