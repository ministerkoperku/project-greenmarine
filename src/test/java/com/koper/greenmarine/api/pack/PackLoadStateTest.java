package com.koper.greenmarine.api.pack;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class PackLoadStateTest {
    @Test void waitsForEveryPipelineSubmissionAndRaySceneBeforeReportingLoaded() {
        var load = new PackLoadState(); load.begin("test:pack", Set.of("lightmap|a", "scene|b"));
        load.path(false, false, "Building RT scene");
        load.submitted("lightmap|a"); load.submitted("scene|b");
        assertEquals(PackStatus.State.LOADING, load.status().state());
        load.path(true, false, "Hardware ray queries active");
        assertEquals(PackStatus.State.LOADED, load.status().state());
    }
    @Test void failureStaysFailedUntilExplicitResetAndFallbackIsVisible() {
        var load = new PackLoadState(); load.begin("test:pack", Set.of("scene|b"));
        load.path(true, true, "OpenGL software fallback"); load.submitted("scene|b");
        assertEquals(PackStatus.State.FALLBACK, load.status().state());
        load.fail("Shader compile failed"); load.submitted("scene|b"); load.path(true, false, "Hardware active");
        assertEquals(PackStatus.State.FAILED, load.status().state());
        assertTrue(load.status().detail().contains("Shader compile"));
        load.begin("test:pack", Set.of("scene|new"));
        assertEquals(PackStatus.State.LOADING, load.status().state());
        load.submitted("scene|b"); assertEquals(PackStatus.State.LOADING, load.status().state());
        load.submitted("scene|new"); assertEquals(PackStatus.State.LOADED, load.status().state());
    }
}
