package com.koper.greenmarine.api.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarineRendererTest {
    @org.junit.jupiter.api.BeforeAll static void hostThread() { RenderTestHost.initialize(); }
    @Test void rejectsWrongThreadResetWithoutRemovingRegistrations() throws Exception {
        var lease = MarineRenderer.register("test:thread", MarineRenderer.Stage.AFTER_PACKS, session -> frame -> {});
        try {
            var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var thread = new Thread(() -> { try { MarineRenderer.reset(); } catch(Throwable e) { error.set(e); } });
            thread.start(); thread.join();
            assertInstanceOf(IllegalStateException.class, error.get());
            assertTrue(MarineRenderer.status().stream().anyMatch(s -> s.id().equals("test:thread")));
        } finally { lease.close(); MarineRenderer.reset(); }
    }
    @Test void registrationLeasePreservesEntriesAcrossResetAndRejectsDuplicates() {
        var lease = MarineRenderer.register("test:geometry", MarineRenderer.Stage.BEFORE_LIGHTING, session -> frame -> {});
        try {
            assertEquals(1, MarineRenderer.status().stream().filter(s -> s.id().equals("test:geometry")).count());
            assertThrows(IllegalArgumentException.class, () -> MarineRenderer.register("test:geometry", MarineRenderer.Stage.AFTER_PACKS, session -> frame -> {}));
            MarineRenderer.reset();
            assertEquals(1, MarineRenderer.status().stream().filter(s -> s.id().equals("test:geometry")).count());
        } finally { lease.close(); }
        lease.close();
        assertEquals(0, MarineRenderer.status().stream().filter(s -> s.id().equals("test:geometry")).count());
        MarineRenderer.reset();
    }
}
