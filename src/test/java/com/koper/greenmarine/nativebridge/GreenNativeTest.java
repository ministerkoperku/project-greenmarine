package com.koper.greenmarine.nativebridge;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GreenNativeTest {
    @Test void missingLibraryLeavesNativePathUnavailable() {
        try (var engine = GreenNative.open(Path.of("/missing-greenmarine/library.so"))) {
            assertFalse(engine.available());
            assertFalse(engine.status().isBlank());
            assertEquals(0, engine.sample(0, 2));
        }
    }

    @Test void abiMismatchIsRejectedBeforeCallingOtherSymbols() {
        assertThrows(IllegalStateException.class, () -> GreenNative.checkAbi(0));
        assertThrows(IllegalStateException.class, () -> GreenNative.checkAbi(2));
        assertDoesNotThrow(() -> GreenNative.checkAbi(1));
    }

    @Test void bundledLibraryExercisesTheRealFfmContract() {
        try (var engine = GreenNative.openBundled()) {
            assertTrue(engine.available(), engine.status());
            assertEquals(1.5f, engine.sample(0, 2));
            assertEquals(2, engine.sample((float)(Math.PI / 2), 2), 0.00001f);
            assertEquals(0, engine.sample(Float.NaN, 2));
            assertEquals(-1, engine.attach(0, 0, 0, 0, 0));
        }
    }
}
