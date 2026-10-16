package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.api.LightRegistry;
import com.koper.greenmarine.script.PackScript;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import java.nio.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MarineFrameTest {
    @Test void writesStd140HeaderMatricesAndThirtyTwoParameterSlots() {
        var pack = new ShaderPack("test:p", "P", null,List.of(new PackParameter("gain", 2, 0, 4)),List.of());
        try (var script = new PackScript(pack, "function update(f) end", new LightRegistry())) {
            var bytes = ByteBuffer.allocateDirect(MarineFrame.BYTES).order(ByteOrder.nativeOrder());
            MarineFrame.write(bytes, 800, 600, 3, 0.5f, true, Quality.HIGH, RayMode.VOXEL,
                new Matrix4f(), new Matrix4f(), script);
            assertEquals(288, MarineFrame.BYTES);
            assertEquals(800, bytes.getFloat(0)); assertEquals(3, bytes.getFloat(8));
            assertEquals(1, bytes.getFloat(16)); assertEquals(Quality.HIGH.ordinal(), bytes.getFloat(20));
            assertEquals(1, bytes.getFloat(32)); assertEquals(1, bytes.getFloat(96));
            assertEquals(2, bytes.getFloat(160)); assertEquals(0, bytes.getFloat(284));
        }
    }
}
