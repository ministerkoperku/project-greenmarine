package com.koper.greenmarine.client.terrain;

import org.junit.jupiter.api.Test;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class TerrainDrawBindingsTest {
    @Test void resolvesExactBorrowedBufferIdentityAndBaseVertexWithoutReordering() {
        var capture = new TerrainDrawBindings.Capture();
        Object firstBuffer = new String("same"), secondBuffer = new String("same");
        Object firstMesh = new Object(), secondMesh = new Object(), otherLayerMesh = new Object();
        capture.observe(0, firstBuffer, 112, firstMesh);
        capture.observe(0, firstBuffer, 336, secondMesh);
        capture.observe(0, secondBuffer, 112, otherLayerMesh);
        assertSame(firstMesh, capture.mesh(0, firstBuffer, 112));
        assertSame(secondMesh, capture.mesh(0, firstBuffer, 336));
        assertSame(otherLayerMesh, capture.mesh(0, secondBuffer, 112));
        assertNull(capture.mesh(1, firstBuffer, 112));
        assertNull(capture.mesh(0, firstBuffer, 113));
    }

    @Test void metadataKeepsActualHostAllocationAndUnavailableReadiness() {
        var ready = TerrainDrawBindings.metadata(4000, 0, 24, true).order(ByteOrder.nativeOrder());
        assertEquals(16, ready.remaining());
        assertEquals(4000, ready.getInt(0));
        assertEquals(0, ready.getInt(4));
        assertEquals(24, ready.getInt(8));
        assertEquals(1, ready.getInt(12));
        var unavailable = TerrainDrawBindings.metadata(7000, 0, 0, false).order(ByteOrder.nativeOrder());
        assertEquals(7000, unavailable.getInt(0));
        assertEquals(0, unavailable.getInt(8));
        assertEquals(0, unavailable.getInt(12));
        assertEquals(3, TerrainSidecars.vertexIndex(4003, ready.getInt(0), ready.getInt(4)));
    }
}
