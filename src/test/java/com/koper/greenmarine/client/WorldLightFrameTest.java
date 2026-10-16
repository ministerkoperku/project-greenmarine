package com.koper.greenmarine.client;

import com.koper.greenmarine.api.Light;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WorldLightFrameTest {
    @Test void reconstructsReverseDepthForBothBackendConventions() {
        for (boolean vulkan : new boolean[]{true, false}) {
            var projection = new Matrix4f().perspective((float)(Math.PI / 2), 1, 100, 1, vulkan);
            var data = ByteBuffer.allocateDirect(WorldLightFrame.BYTES).order(ByteOrder.nativeOrder());
            WorldLightFrame.writeCamera(data, projection, new Matrix4f(), 800, 600, vulkan);
            var inverse = new Matrix4f().set(32, data);
            float depth = 49f / 99f;
            var position = new Vector4f(-0.5f, 0.5f, data.getFloat(8) > 0 ? depth : depth * 2 - 1, 1).mul(inverse);
            position.div(position.w);
            assertEquals(-1, position.x, 0.0001);
            assertEquals(1, position.y, 0.0001);
            assertEquals(-2, position.z, 0.0001);
        }
    }

    @Test void preservesSmallOffsetsAtLargeWorldCoordinates() {
        var light = new Light(30_000_000.25, 64.5, -30_000_000.75, 0, 1, 0, 4, 2);
        var data = ByteBuffer.allocateDirect(WorldLightFrame.BYTES).order(ByteOrder.nativeOrder());
        int count = WorldLightFrame.writeLights(data, List.of(light), 30_000_000, 64, -30_000_001, 1);
        assertEquals(1, count);
        assertEquals(0.25f, data.getFloat(WorldLightFrame.HEADER_BYTES));
        assertEquals(0.5f, data.getFloat(WorldLightFrame.HEADER_BYTES + 4));
        assertEquals(0.25f, data.getFloat(WorldLightFrame.HEADER_BYTES + 8));
        assertEquals(4, data.getFloat(WorldLightFrame.HEADER_BYTES + 12));
        assertEquals(2, data.getFloat(WorldLightFrame.HEADER_BYTES + 28));
    }

    @Test void boundsGpuLightCountAndClearsUnusedSlots() {
        var data = ByteBuffer.allocateDirect(WorldLightFrame.BYTES).order(ByteOrder.nativeOrder());
        var lamp = new Light(0, 0, -2, 0, 1, 0, 4, 1);
        assertEquals(16, WorldLightFrame.writeLights(data, java.util.Collections.nCopies(20, lamp), 0, 0, 0, 1));
        assertEquals(0, WorldLightFrame.writeLights(data, List.of(), 0, 0, 0, 1));
        for (int offset = WorldLightFrame.HEADER_BYTES; offset < WorldLightFrame.BYTES; offset += 4) {
            assertEquals(0, data.getFloat(offset));
        }
    }

    @Test void reconstructsWorldDirectionAfterCameraTurns() {
        var data = ByteBuffer.allocateDirect(WorldLightFrame.BYTES).order(ByteOrder.nativeOrder());
        WorldLightFrame.writeCamera(data, new Matrix4f().perspective((float)(Math.PI / 2), 1, 100, 1, true),
            new Matrix4f().rotationY((float)(Math.PI / 2)), 800, 600, true);
        var view = new Vector4f(-0.5f, 0.5f, 49f / 99f, 1).mul(new Matrix4f().set(32, data));
        view.div(view.w);
        view.w = 0;
        view.mul(new Matrix4f().set(96, data));
        assertEquals(2, view.x, 0.0001);
        assertEquals(1, view.y, 0.0001);
        assertEquals(-1, view.z, 0.0001);
    }

}
