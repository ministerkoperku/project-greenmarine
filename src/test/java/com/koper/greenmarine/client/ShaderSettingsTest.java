package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ShaderSettingsTest {
    @TempDir Path directory;
    @Test void persistsPackQualityRayModeAndProfiling() throws Exception {
        var path = directory.resolve("shaders.json");
        var expected = new ShaderSettings("demo:advanced", Quality.ULTRA, RayMode.HARDWARE, true);
        expected.save(path);
        assertEquals(expected, ShaderSettings.load(path, ShaderSettings.defaults()));
        Files.writeString(path, "{\"pack\":\"bad\"}");
        assertEquals(expected, ShaderSettings.load(path, expected));
    }
    @Test void parameterOverridesAreIsolatedImmutableAndClampedToReloadedDefinitions() throws Exception {
        var values=new java.util.HashMap<String,Float>(); values.put("gain",3f); values.put("retired",1f);
        var settings=new ShaderSettings("demo:p",Quality.HIGH,RayMode.OFF,false,java.util.Map.of("demo:p",values));
        values.put("gain",99f);
        var pack=new ShaderPack("demo:p","P",null,java.util.List.of(new PackParameter("gain",1,0,2)),java.util.List.of());
        assertEquals(java.util.Map.of("gain",2f),settings.overridesFor(pack));
        assertTrue(settings.overridesFor(new ShaderPack("demo:q","Q",null,pack.parameters(),java.util.List.of())).isEmpty());
        assertThrows(UnsupportedOperationException.class,()->settings.parameters().get("demo:p").clear());
        var path=directory.resolve("parameters.json"); settings.save(path);
        assertEquals(settings,ShaderSettings.load(path,ShaderSettings.defaults()));
        assertThrows(IllegalArgumentException.class,()->settings.withParameter("demo:p","gain",Float.NaN));
        assertTrue(settings.withoutParameters("demo:p").overridesFor(pack).isEmpty());
    }
    @Test void editingARenamedParameterPrunesRetiredKeysBeforeEnforcingTheLimit() {
        var stored=new java.util.LinkedHashMap<String,Float>();
        var definitions=new java.util.ArrayList<PackParameter>();
        for(int i=0;i<32;i++) { stored.put("old"+i,1f); definitions.add(new PackParameter(i==31?"renamed":"old"+i,1,0,4)); }
        var settings=new ShaderSettings("demo:p",Quality.HIGH,RayMode.OFF,false,java.util.Map.of("demo:p",stored));
        var pack=new ShaderPack("demo:p","P",null,definitions,java.util.List.of());
        var updated=settings.withParameter(pack,"renamed",2);
        assertEquals(32,updated.parameters().get("demo:p").size());
        assertFalse(updated.parameters().get("demo:p").containsKey("old31"));
        assertEquals(2f,updated.overridesFor(pack).get("renamed"));
    }
    @Test void parametersCanBeEditedForARegisteredPackWithoutAnActiveScript() throws Exception {
        var pack=new ShaderPack("test:inactive","Inactive",null,
            java.util.List.of(new PackParameter("gain",1,0,4)),java.util.List.of());
        try(var registration=com.koper.greenmarine.GreenMarine.PACKS.register(new PackCatalog.LoadedPack(pack,
            "function update(f) end",java.util.Map.of()))) {
            assertNull(ShaderRuntime.script());
            assertTrue(ShaderRuntime.previewParameter(pack.id(),"gain",2));
            assertEquals(2f,ShaderRuntime.settings().overridesFor(pack).get("gain"));
        }
    }
}
