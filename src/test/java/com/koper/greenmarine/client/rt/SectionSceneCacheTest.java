package com.koper.greenmarine.client.rt;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionSceneCacheTest {
    @Test void movementKeepsCoveredSceneUntilTheNewVolumeIsReadyWithoutLosingEdits() {
        var cache=cache();fill(cache);var prior=cache.bounds();
        assertEquals(prior,cache.publicationSnapshot(null).bounds());
        cache.retarget(new SceneBounds(16,0,0),56,24,40);
        var stable=cache.publicationSnapshot(prior);assertNotNull(stable);assertEquals(prior,stable.bounds());
        long before=stable.contentHash();
        cache.invalidateBlock(40,24,40);var edit=cache.next();assertTrue(cache.complete(edit,mesh(.75f)));
        assertNotEquals(before,cache.publicationSnapshot(prior).contentHash());
        fill(cache);assertEquals(cache.bounds(),cache.publicationSnapshot(prior).bounds());
    }
    @Test void initialCoverageDoesNotExposeMissingOccludersAsACompleteLightingScene() {
        var cache=cache();cache.complete(cache.next(),mesh(.25f));
        assertNull(cache.publicationSnapshot(null));
        fill(cache);assertNotNull(cache.publicationSnapshot(null));
    }
    @Test void adjacentMovementPreservesAnUnchangedOverlappingExtractionTicket() {
        var cache=cache();var ticket=cache.next();
        cache.retarget(new SceneBounds(16,0,0),56,24,40);
        assertTrue(cache.valid(ticket),"A bounds crossing must not discard overlapping partial extraction");
        assertTrue(cache.complete(ticket,mesh(.25f)));
    }
    @Test void cameraPriorityChangesDoNotRestartValidPartialExtraction() {
        var cache=cache();var ticket=cache.next();
        cache.retarget(cache.bounds(),88,56,88);
        var nearer=cache.next();assertNotEquals(ticket.key(),nearer.key());
        assertFalse(cache.shouldPreempt(ticket,nearer));
        cache.complete(nearer,mesh(.25f));cache.invalidateBlock(88,56,88);
        assertTrue(cache.shouldPreempt(ticket,cache.next()),"An actual urgent edit may preempt coverage work");
    }
    @Test void pendingCountStaysCorrectAcrossRepeatedInvalidationAndColumnTransitions() {
        var cache=cache();fill(cache);assertEquals(0,cache.pendingSections());
        for(int i=0;i<100;i++)cache.invalidateBlock(40,24,40);
        assertEquals(1,cache.pendingSections());
        cache.observeColumn(2,2,false);assertEquals(0,cache.pendingSections());
        cache.observeColumn(2,2,true);assertEquals(4,cache.pendingSections());
        fill(cache);assertEquals(0,cache.pendingSections());
        cache.invalidateAll();assertEquals(144,cache.pendingSections());
        cache.retarget(new SceneBounds(16,0,0),56,24,40);assertEquals(144,cache.pendingSections());
    }
    @Test void nearSectionsPublishBeforeTheDistantQueueCompletes() {
        var cache=cache();var ticket=cache.next();
        assertEquals(new SectionSceneCache.SectionKey(2,1,2),ticket.key());
        assertTrue(cache.complete(ticket,mesh(.25f)));
        var snapshot=cache.snapshot();
        assertFalse(snapshot.complete());assertEquals(143,snapshot.pendingSections());
        assertEquals(1,snapshot.assemble().triangles());
    }
    @Test void movingOneSectionReusesOverlapWithoutExtraction() {
        var cache=cache();fill(cache);
        cache.retarget(new SceneBounds(16,0,0),56,24,40);
        assertEquals(120,cache.snapshot().sections().size());assertEquals(24,cache.pendingSections());
        assertEquals(120,cache.metrics().cacheHits());
        var vertical=cache();fill(vertical);vertical.retarget(new SceneBounds(0,16,0),40,40,40);
        assertEquals(108,vertical.snapshot().sections().size());assertEquals(36,vertical.pendingSections());
    }
    @Test void boundaryEditInvalidatesBothSectionsAndRejectsPartialWork() {
        var cache=cache();fill(cache);cache.invalidateBlock(31,24,40);
        assertEquals(2,cache.pendingSections());
        var ticket=cache.next();cache.invalidateBlock(31,24,40);
        assertFalse(cache.complete(ticket,mesh(.5f)));assertEquals(2,cache.pendingSections());
    }
    @Test void unloadingRemovesGhostsAndRejectsAnInFlightSection() {
        var cache=cache();fill(cache);cache.invalidateBlock(40,24,40);var ticket=cache.next();
        var before=cache.snapshot();cache.unloadColumn(2,2);
        assertEquals(140,cache.snapshot().sections().size());
        assertFalse(cache.complete(ticket,mesh(.5f)));
        assertEquals(144,before.sections().size());assertEquals(144,before.assemble().triangles());
        cache.observeColumn(2,2,true);assertTrue(cache.pendingSections()>=4);
    }
    @Test void snapshotOrderingAndRebasingAreIndependentOfScanPriority() {
        var first=cache();fill(first);
        var second=new SectionSceneCache();second.retarget(new SceneBounds(0,0,0),88,56,88);fill(second);
        assertTrue(first.snapshot().assemble().sameContent(second.snapshot().assemble()));
        int origin=29_999_984;var far=new SectionSceneCache();far.retarget(new SceneBounds(origin,0,origin),origin+8,8,origin+8);
        far.complete(far.next(),mesh(.25f));var assembled=far.snapshot().assemble();
        assertEquals(.25f,assembled.data().getFloat(0));assertEquals(.125f,assembled.data().getFloat(48));
        assertEquals(origin,assembled.x());
    }
    @Test void retentionIsBoundedAndResetRejectsOldTickets() {
        var cache=cache();var ticket=cache.next();cache.reset();
        assertFalse(cache.complete(ticket,mesh(.25f)));
        for(int i=0;i<30;i++){cache.retarget(new SceneBounds(i*16,0,0),i*16+40,24,40);fill(cache);}
        assertTrue(cache.metrics().cachedSections()<=SectionSceneCache.MAX_SECTIONS);
    }
    @Test void columnUpdatesInvalidateBlendedTintNeighbors() {
        var cache=cache();fill(cache);cache.invalidateColumn(2,2);
        assertEquals(36,cache.pendingSections());
        var ticket=cache.next();cache.invalidateAll();assertFalse(cache.complete(ticket,mesh(.25f)));
    }
    @Test void retargetInvalidatesTicketsEvenWhenTheirSectionLaterReenters() {
        var cache=cache();var ticket=cache.next();
        cache.retarget(new SceneBounds(160,0,0),200,24,40);
        cache.retarget(new SceneBounds(0,0,0),40,24,40);
        assertFalse(cache.complete(ticket,mesh(.25f)));
    }
    @Test void nearbyDirtyGeometryPreemptsTheRemainingDistantQueue() {
        var cache=cache();var first=cache.next();cache.complete(first,mesh(.25f));
        var distant=cache.next();assertNotEquals(first.key(),distant.key());
        cache.invalidateBlock(40,24,40);assertEquals(first.key(),cache.next().key());
    }
    @Test void immutableSnapshotCanAssembleOnAWorkerAfterCacheReset() throws Exception {
        var cache=cache();cache.complete(cache.next(),mesh(.25f));var snapshot=cache.snapshot();
        var expected=snapshot.assemble();cache.reset();
        try(var worker=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            assertTrue(worker.submit(snapshot::assemble).get().sameContent(expected));
        }
        assertThrows(UnsupportedOperationException.class,()->snapshot.sections().clear());
    }
    @Test void retainedTriangleBudgetEvictsInactiveGeometryBeforeActiveGeometry() throws Exception {
        var cache=limitedCache(1);cache.retarget(new SceneBounds(0,0,0),8,8,8);
        var old=cache.next();cache.complete(old,mesh(.25f));var snapshot=cache.snapshot();
        cache.retarget(new SceneBounds(16,0,0),24,8,8);cache.complete(cache.next(),mesh(.5f));
        assertFalse(cache.cachedKeys().contains(old.key()),"Inactive mesh must be evicted before exceeding the retained geometry budget");
        assertEquals(1,cache.cachedTriangles());assertEquals(TriangleScene.TRIANGLE_BYTES,cache.cachedBytes());
        assertEquals(1,cache.snapshot().assemble().triangles());
        assertEquals(1,snapshot.assemble().triangles());
    }
    @Test void activeOverflowPreservesThePreviousMeshAndReplacementDoesNotDoubleCount() throws Exception {
        var cache=limitedCache(1);cache.retarget(new SceneBounds(0,0,0),40,24,40);
        var first=cache.next();cache.complete(first,mesh(.25f));cache.invalidateBlock(40,24,40);
        assertTrue(cache.complete(cache.next(),mesh(.5f)));
        assertEquals(1,cache.cachedTriangles());
        var replacement=cache.snapshot().assemble();assertEquals(.5f,replacement.data().getFloat(0)-32);
        cache.invalidateBlock(40,24,40);var retry=cache.next();
        var larger=new TriangleScene.Builder(0,0,0,2);larger.append(mesh(.75f),0,0,0);larger.append(mesh(.75f),0,0,0);
        var rejected=larger.finish();long revision=cache.revision();
        assertThrows(IllegalArgumentException.class,()->cache.complete(retry,rejected));
        assertEquals(revision,cache.revision());assertTrue(cache.valid(retry));
        assertTrue(cache.snapshot().assemble().sameContent(replacement));
        assertEquals(1,cache.cachedTriangles());cache.unloadColumn(first.key().x(),first.key().z());
        assertEquals(0,cache.cachedTriangles());assertEquals(0,cache.cachedBytes());
    }
    private static SectionSceneCache limitedCache(int budget) {return new SectionSceneCache(budget);}
    private static SectionSceneCache cache() {var cache=new SectionSceneCache();cache.retarget(new SceneBounds(0,0,0),40,24,40);return cache;}
    private static void fill(SectionSceneCache cache) {SectionSceneCache.Ticket ticket;while((ticket=cache.next())!=null)assertTrue(cache.complete(ticket,mesh(.25f)));}
    private static TriangleScene mesh(float x) {
        var builder=new TriangleScene.Builder(0,0,0,2);
        builder.triangle(new float[]{x,0,0,x+1,0,0,x,1,0},new float[]{.125f,.25f,.5f,.75f,.875f,1},0x52A9D3,7,true);
        return builder.finish();
    }
    public static void main(String[] args) throws Exception {
        var test=new SectionSceneCacheTest();
        test.nearSectionsPublishBeforeTheDistantQueueCompletes();test.movingOneSectionReusesOverlapWithoutExtraction();
        test.boundaryEditInvalidatesBothSectionsAndRejectsPartialWork();test.unloadingRemovesGhostsAndRejectsAnInFlightSection();
        test.snapshotOrderingAndRebasingAreIndependentOfScanPriority();test.retentionIsBoundedAndResetRejectsOldTickets();
        test.columnUpdatesInvalidateBlendedTintNeighbors();test.retargetInvalidatesTicketsEvenWhenTheirSectionLaterReenters();
        test.nearbyDirtyGeometryPreemptsTheRemainingDistantQueue();test.immutableSnapshotCanAssembleOnAWorkerAfterCacheReset();
        test.retainedTriangleBudgetEvictsInactiveGeometryBeforeActiveGeometry();
        test.activeOverflowPreservesThePreviousMeshAndReplacementDoesNotDoubleCount();
        System.out.println("SectionSceneCache: 12 behavioral checks passed");
    }
}
