package com.koper.greenmarine.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LightTest {
    private Light light() { return new Light(1, 2, 3, 1, 0.5f, 0, 4, 2); }

    @Test void rejectsValuesThatWouldPoisonShaders() {
        assertThrows(IllegalArgumentException.class, () -> new Light(Double.NaN, 0, 0, 1, 1, 1, 4, 1));
        assertThrows(IllegalArgumentException.class, () -> new Light(0, 0, 0, Float.NaN, 1, 1, 4, 1));
        assertThrows(IllegalArgumentException.class, () -> new Light(0, 0, 0, 1, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Light(0, 0, 0, 1, 1, 1, 4, -1));
        assertThrows(IllegalArgumentException.class, () -> new Light(0, 0, 0, 2, 1, 1, 4, 1));
        assertEquals(0, new Light(0, 0, 0, 1, 1, 1, 4, 0).intensity());
    }

    @Test void staleHandlesCannotModifyNewWorldLights() {
        var registry = new LightRegistry();
        long old = registry.create(light());
        registry.clear();
        long fresh = registry.create(light());
        assertNotEquals(old, fresh);
        assertFalse(registry.update(old, light()));
        assertFalse(registry.remove(old));
        assertEquals(1, registry.snapshot().size());
        assertTrue(registry.update(fresh, new Light(4, 5, 6, 0, 1, 0, 3, 1)));
        assertEquals(4, registry.snapshot().getFirst().x());
        assertTrue(registry.remove(fresh));
        assertTrue(registry.snapshot().isEmpty());
    }

    @Test void returnedSnapshotDoesNotChangeUnderCaller() {
        var registry = new LightRegistry();
        registry.create(light());
        var snapshot = registry.snapshot();
        registry.clear();
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }
}
