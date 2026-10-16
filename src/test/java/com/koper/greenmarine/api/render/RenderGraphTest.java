package com.koper.greenmarine.api.render;

import com.mojang.renderpearl.api.GpuFormat;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RenderGraphTest {
    private static final TextureSpec COLOR = TextureSpec.scaled(GpuFormat.RGBA8_UNORM, 1);
    private static RenderGraph.Pass pass(String id, Set<String> reads, Set<String> writes, Set<String> after) {
        return new RenderGraph.Pass(id, reads, writes, after, ctx -> {});
    }
    @Test void ordersProducerBeforeConsumerEvenWhenDeclaredLast() {
        var graph = RenderGraph.builder().texture("albedo", COLOR).importTexture("scene")
            .pass(pass("compose", Set.of("albedo"), Set.of("scene"), Set.of()))
            .pass(pass("geometry", Set.of(), Set.of("albedo"), Set.of())).build();
        assertEquals(List.of("geometry", "compose"), graph.passes().stream().map(RenderGraph.Pass::id).toList());
    }
    @Test void readsImportedSnapshotBeforeItsWriter() {
        var graph = RenderGraph.builder().importTexture("scene").texture("copy", COLOR)
            .pass(pass("write", Set.of("copy"), Set.of("scene"), Set.of()))
            .pass(pass("snapshot", Set.of("scene"), Set.of("copy"), Set.of())).build();
        assertEquals(List.of("snapshot", "write"), graph.passes().stream().map(RenderGraph.Pass::id).toList());
    }
    @Test void rejectsFeedbackAndAmbiguousWriters() {
        assertThrows(IllegalArgumentException.class, () -> RenderGraph.builder().importTexture("scene")
            .pass(pass("bad", Set.of("scene"), Set.of("scene"), Set.of())).build());
        assertThrows(IllegalArgumentException.class, () -> RenderGraph.builder().texture("x", COLOR)
            .pass(pass("a", Set.of(), Set.of("x"), Set.of()))
            .pass(pass("b", Set.of(), Set.of("x"), Set.of())).build());
    }
    @Test void rejectsUninitializedUnknownAndCyclicInputs() {
        assertThrows(IllegalArgumentException.class, () -> RenderGraph.builder().texture("x", COLOR)
            .pass(pass("a", Set.of("x"), Set.of(), Set.of())).build());
        assertThrows(IllegalArgumentException.class, () -> RenderGraph.builder()
            .pass(pass("a", Set.of("missing"), Set.of(), Set.of())).build());
        assertThrows(IllegalArgumentException.class, () -> RenderGraph.builder()
            .pass(pass("a", Set.of(), Set.of(), Set.of("b")))
            .pass(pass("b", Set.of(), Set.of(), Set.of("a"))).build());
        assertThrows(IllegalArgumentException.class, () -> RenderGraph.builder()
            .pass(pass("a", Set.of(), Set.of(), Set.of("missing"))).build());
    }
    @Test void preservesStableOrderAndImmutableDeclarations() {
        var reads = new HashSet<String>();
        var p = pass("a", reads, Set.of(), Set.of()); reads.add("missing");
        var graph = RenderGraph.builder().pass(p).pass(pass("b", Set.of(), Set.of(), Set.of())).build();
        assertEquals(List.of("a", "b"), graph.passes().stream().map(RenderGraph.Pass::id).toList());
        assertThrows(UnsupportedOperationException.class, () -> graph.passes().clear());
    }
    @Test void resolvesOddSizesAndRejectsInvalidTextureDeclarations() {
        var spec = TextureSpec.scaled(GpuFormat.RGBA16_FLOAT, .5);
        assertEquals(481, spec.width(961)); assertEquals(271, spec.height(541));
        assertThrows(IllegalArgumentException.class, () -> TextureSpec.scaled(GpuFormat.RGBA8_UNORM, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> TextureSpec.fixed(GpuFormat.RGBA8_UNORM, 0, 4));
    }
    @Test void computesCameraRelativeModelsWithoutFloatCancellation() {
        var matrix = FrameContext.relativeModel(30_000_000.25, 70, -30_000_000.75, 30_000_001.5, 72, -30_000_002.25);
        assertEquals(1.25f, matrix.m30()); assertEquals(2, matrix.m31()); assertEquals(-1.5f, matrix.m32());
    }
}
