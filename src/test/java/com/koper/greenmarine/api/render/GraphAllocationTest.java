package com.koper.greenmarine.api.render;

import com.mojang.renderpearl.api.GpuFormat;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GraphAllocationTest {
    private static final TextureSpec COLOR = TextureSpec.scaled(GpuFormat.RGBA8_UNORM, 1);
    private static RenderGraph.Pass pass(String id, Set<String> reads, Set<String> writes, String... after) {
        return new RenderGraph.Pass(id, reads, writes, Set.of(after), context -> {});
    }
    private static RenderGraph disjoint() {
        return RenderGraph.builder().transientTexture("a", COLOR).transientTexture("b", COLOR).transientTexture("unused", COLOR)
            .importTexture("scene")
            .pass(pass("a", Set.of(), Set.of("a")))
            .pass(pass("consume-a", Set.of("a"), Set.of()))
            .pass(pass("b", Set.of(), Set.of("b"), "consume-a"))
            .pass(pass("consume-b", Set.of("b"), Set.of("scene"))).build();
    }
    @Test void legacyTextureDeclarationsRetainDedicatedPersistentStorage() {
        var graph = RenderGraph.builder().texture("a", COLOR).texture("b", COLOR).texture("unused", COLOR)
            .pass(pass("a", Set.of(), Set.of("a")))
            .pass(pass("b", Set.of(), Set.of("b"), "a")).build();
        assertEquals(3, graph.allocationPlan().physicalTextureCount());
        assertNotEquals(graph.allocationPlan().assignments().get("a"), graph.allocationPlan().assignments().get("b"));
        assertNotNull(graph.allocationPlan().assignments().get("unused"));
    }
    @Test void aliasesDisjointExactSpecsAndSkipsUnusedAndImports() {
        var plan = disjoint().allocationPlan();
        assertEquals(1, plan.physicalTextureCount());
        assertEquals(Set.of("a", "b", "unused"), plan.transientTextures());
        assertEquals(Map.of("a", 0, "b", 0), plan.assignments());
        assertEquals(new GraphAllocationPlan.Lifetime(0, 1), plan.lifetimes().get("a"));
        assertEquals(new GraphAllocationPlan.Lifetime(2, 3), plan.lifetimes().get("b"));
        assertThrows(UnsupportedOperationException.class, () -> plan.assignments().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.lifetimes().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.physicalSpecs().clear());
        assertEquals(plan.assignments(), disjoint().allocationPlan().assignments());
    }
    @Test void transientTexturesNeverBorrowPersistentSlots() {
        var graph = RenderGraph.builder().texture("persistent", COLOR).transientTexture("temporary", COLOR)
            .pass(pass("persistent", Set.of(), Set.of("persistent")))
            .pass(pass("temporary", Set.of(), Set.of("temporary"), "persistent")).build();
        assertEquals(2, graph.allocationPlan().physicalTextureCount());
        assertEquals(Set.of("temporary"), graph.transientTextures());
        assertThrows(UnsupportedOperationException.class, () -> graph.transientTextures().clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.allocationPlan().transientTextures().clear());
    }
    @Test void samePassReadAndWriteAndOverlappingReadersCannotAlias() {
        var graph = RenderGraph.builder().transientTexture("a", COLOR).transientTexture("b", COLOR).transientTexture("c", COLOR)
            .pass(pass("a", Set.of(), Set.of("a")))
            .pass(pass("b", Set.of("a"), Set.of("b")))
            .pass(pass("c", Set.of("a", "b"), Set.of("c"))).build();
        assertEquals(3, graph.allocationPlan().physicalTextureCount());
    }
    @Test void equalResolvedDimensionsDoNotPermitDifferentSpecAliasing() {
        var graph = RenderGraph.builder().transientTexture("a", COLOR)
            .transientTexture("b", TextureSpec.fixed(GpuFormat.RGBA8_UNORM, 64, 64))
            .transientTexture("c", TextureSpec.scaled(GpuFormat.RGBA16_FLOAT, 1))
            .pass(pass("a", Set.of(), Set.of("a")))
            .pass(pass("b", Set.of(), Set.of("b"), "a"))
            .pass(pass("c", Set.of(), Set.of("c"), "b")).build();
        assertEquals(3, graph.allocationPlan().physicalTextureCount());
    }
    @Test void intervalsFollowCompiledDependenciesRatherThanDeclarationOrder() {
        var graph = RenderGraph.builder().transientTexture("b", COLOR).transientTexture("a", COLOR)
            .pass(pass("consume", Set.of("a"), Set.of("b")))
            .pass(pass("produce", Set.of(), Set.of("a"))).build();
        assertEquals(new GraphAllocationPlan.Lifetime(0, 1), graph.allocationPlan().lifetimes().get("a"));
        assertEquals(new GraphAllocationPlan.Lifetime(1, 1), graph.allocationPlan().lifetimes().get("b"));
        assertEquals(2, graph.allocationPlan().physicalTextureCount());
    }
    @Test void picksLowestFreeSlotDeterministically() {
        var graph = RenderGraph.builder().transientTexture("a", COLOR).transientTexture("b", COLOR).transientTexture("c", COLOR)
            .pass(pass("both", Set.of(), Set.of("a", "b")))
            .pass(pass("next", Set.of(), Set.of("c"), "both")).build();
        assertEquals(Map.of("a", 0, "b", 1, "c", 0), graph.allocationPlan().assignments());
    }
    @Test void emptyAndUnusedGraphsNeedNoPhysicalStorage() {
        var plan = RenderGraph.builder().transientTexture("unused", COLOR).build().allocationPlan();
        assertEquals(0, plan.physicalTextureCount());
        assertTrue(plan.assignments().isEmpty());
    }
    static final class Resource implements AutoCloseable {
        int closes;
        @Override public void close() { closes++; }
    }
    @Test void resizeIsTransactionalAndClosesPhysicalStorageOnlyOnce() {
        var graph = RenderGraph.builder().transientTexture("a", COLOR).transientTexture("b", COLOR)
            .transientTexture("fixed", TextureSpec.fixed(GpuFormat.RGBA8_UNORM, 8, 8))
            .transientTexture("other", TextureSpec.scaled(GpuFormat.RGBA16_FLOAT, 1))
            .pass(pass("a", Set.of(), Set.of("a", "fixed", "other")))
            .pass(pass("b", Set.of(), Set.of("b"), "a")).build();
        var pool = new GraphTexturePool<Resource>(graph.allocationPlan());
        var created = new ArrayList<Resource>();
        pool.prepare(20, 20, (slot, spec) -> { var r = new Resource(); created.add(r); return r; });
        var old = pool.resources();
        assertEquals(3, old.size());
        assertSame(pool.resource("a"), pool.resource("b"));
        var partial = new Resource();
        assertThrows(IllegalStateException.class, () -> pool.prepare(30, 30, (slot, spec) -> {
            if (slot == 0) return partial;
            throw new IllegalStateException("allocation failed");
        }));
        assertEquals(1, partial.closes);
        assertEquals(old, pool.resources());
        assertTrue(old.stream().allMatch(r -> r.closes == 0));
        pool.prepare(30, 30, (slot, spec) -> { var r = new Resource(); created.add(r); return r; });
        assertSame(old.get(1), pool.resource("fixed"));
        assertEquals(1, old.get(0).closes);
        assertEquals(1, old.get(2).closes);
        pool.prepare(30, 30, (slot, spec) -> { fail("unnecessary allocation"); return null; });
        pool.close(); pool.close();
        assertTrue(created.stream().allMatch(r -> r.closes == 1));
    }
}
