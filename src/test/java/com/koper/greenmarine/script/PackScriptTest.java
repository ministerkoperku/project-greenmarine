package com.koper.greenmarine.script;

import com.koper.greenmarine.api.LightRegistry;
import com.koper.greenmarine.api.pack.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PackScriptTest {
    private final LightRegistry lights = new LightRegistry();
    private final ShaderPack pack = new ShaderPack("test:pack", "Test", "test:script.lua",
        List.of(new PackParameter("exposure", 1, 0, 4)), List.of());
    private PackScript.Frame frame(double time) {
        return new PackScript.Frame(time, 0.5, 100, 64, -20,
            new RenderCapabilities("Vulkan", false, false, true, true), Quality.HIGH, RayMode.VOXEL);
    }

    @Test void evaluatesEngineParametersAndOwnedWorldLights() {
        try (var script = new PackScript(pack, """
            function update(frame)
                marine.uniform('exposure', 1 + math.sin(frame.time))
                marine.light(0, frame.camera.x, frame.camera.y + 2, frame.camera.z, 1, 0.5, 0.1, 4, 2)
            end
            """, lights)) {
            script.tick(frame(0));
            assertEquals(1, script.value("exposure"));
            assertEquals(1, lights.snapshot().size());
            assertEquals(66, lights.snapshot().getFirst().y());
            script.tick(frame(Math.PI/2));
            assertEquals(2, script.value("exposure"), 0.0001);
            assertEquals(1, lights.snapshot().size());
        }
        assertTrue(lights.snapshot().isEmpty());
    }

    @Test void instructionBudgetStopsInfiniteLoopsInInitializationAndUpdates() {
        assertThrows(IllegalArgumentException.class, () -> new PackScript(pack, "while true do end", lights));
        try (var script = new PackScript(pack, "function update(frame) while true do end end", lights)) {
            script.tick(frame(0));
            assertTrue(script.failed());
            assertTrue(script.error().contains("budget"));
        }
    }

    @Test void exposesEngineHelpersButNoProcessFilesystemReflectionOrDynamicCode() {
        try (var script = new PackScript(pack, """
            assert(io == nil and os == nil and luajava == nil and debug == nil)
            assert(require == nil and load == nil and loadfile == nil and dofile == nil)
            function update(frame)
                marine.uniform('exposure', marine.clamp(marine.mix(0, 4, 0.5), 0, 3))
            end
            """, lights)) {
            script.tick(frame(0)); assertFalse(script.failed()); assertEquals(2, script.value("exposure"));
        }
    }

    @Test void failedTickRollsBackUniformsAndRemovesOwnedLightsOnly() {
        long external = lights.create(new com.koper.greenmarine.api.Light(0,0,0,1,1,1,4,1));
        try (var script = new PackScript(pack, """
            function update(frame)
                marine.light(0, 0,0,0, 1,1,1, 4,1)
                marine.uniform('exposure', 2)
                marine.uniform('exposure', 0/0)
            end
            """, lights)) {
            script.tick(frame(0));
            assertTrue(script.failed()); assertEquals(1, script.value("exposure"));
            assertEquals(1, lights.snapshot().size());
            assertTrue(lights.remove(external));
        }
    }

    @Test void boundsSourceAndRejectsUnknownParameters() {
        assertThrows(IllegalArgumentException.class, () -> new PackScript(pack, " ".repeat(65_537), lights));
        try (var script = new PackScript(pack, "function update(f) marine.uniform('typo', 1) end", lights)) {
            script.tick(frame(0)); assertTrue(script.failed());
        }
    }

    @Test void modsCanExtendTheMarineApiWithPerPackFunctions() throws Exception {
        try (var registration = PackScript.registerExtension("double_gain", () -> new org.luaj.vm2.lib.OneArgFunction() {
            @Override public org.luaj.vm2.LuaValue call(org.luaj.vm2.LuaValue value) {
                return org.luaj.vm2.LuaValue.valueOf(value.checkdouble() * 2);
            }
        }); var script = new PackScript(pack, "function update(f) marine.uniform('exposure', marine.double_gain(1.5)) end", lights)) {
            script.tick(frame(0)); assertEquals(3, script.value("exposure"));
            assertThrows(IllegalArgumentException.class, () -> PackScript.registerExtension("uniform", () -> org.luaj.vm2.LuaValue.NIL));
        }
    }

    @Test void oversizedStringsAndRetainedTablesCannotGrowWithoutABudget() {
        try(var script=new PackScript(pack,"function update(f) local s='x'; for i=1,20 do s=s..s end end",lights)) {
            script.tick(frame(0)); assertTrue(script.failed()); assertTrue(script.error().contains("budget"));
        }
        try(var script=new PackScript(pack,"local t={}; function update(f) for i=1,3000 do t[i]=i end end",lights)) {
            script.tick(frame(0)); assertTrue(script.failed()); assertTrue(script.error().contains("budget"));
        }
    }

    @Test void memoryBudgetTraversesRetainedMetatablesAndTheirCycles() {
        try (var script = new PackScript(pack, """
            local head = {}
            function update(frame)
                local s = 'x'
                for i = 1, 16 do s = s .. s end
                local node = {payload = s}
                setmetatable(node, {previous = head})
                head = node
            end
            """, lights)) {
            for (int i = 0; i < 40 && !script.failed(); i++) script.tick(frame(i));
            assertTrue(script.failed(), "Retained metatable chains must count toward memory limits");
            assertTrue(script.error().contains("memory budget"));
        }
        try (var script = new PackScript(pack, """
            local node = {}
            setmetatable(node, node)
            function update(frame)
                assert(getmetatable(node) == node)
                marine.uniform('exposure', 2)
            end
            """, lights)) {
            script.tick(frame(0));
            assertFalse(script.failed());
            assertEquals(2, script.value("exposure"));
        }
    }
    @Test void explicitOverridesWinOverLuaAndRemovingThemRestoresAuthoredValues() {
        try(var script=new PackScript(pack,"function update(f) marine.uniform('exposure',1+f.time) end",lights)) {
            script.setOverrides(java.util.Map.of("exposure",3f));
            script.tick(frame(0.5)); assertEquals(3,script.value("exposure"));
            script.setOverrides(java.util.Map.of()); assertEquals(1.5f,script.value("exposure"));
            assertThrows(IllegalArgumentException.class,()->script.setOverrides(java.util.Map.of("exposure",5f)));
            assertThrows(IllegalArgumentException.class,()->script.setOverrides(java.util.Map.of("typo",1f)));
            script.tick(frame(1)); assertEquals(2,script.value("exposure"));
        }
    }
}
