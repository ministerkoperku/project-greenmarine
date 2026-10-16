package com.koper.greenmarine.client;

import com.koper.greenmarine.api.Light;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import java.nio.ByteBuffer;
import java.util.List;

public final class WorldLightFrame {
    public static final int MAX_LIGHTS = 16;
    public static final int HEADER_BYTES = 160;
    public static final int BYTES = HEADER_BYTES + MAX_LIGHTS * 32;
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private static final class Scratch {
        final Matrix4f projection = new Matrix4f(), view = new Matrix4f();
        final LightSelector lights = new LightSelector();
    }

    private WorldLightFrame() {}

    public static void writeCamera(ByteBuffer data, Matrix4fc projection, Matrix4fc viewRotation,
                                   int width, int height, boolean zeroToOne) {
        data.putFloat(0, width).putFloat(4, height).putFloat(8, zeroToOne ? 1 : 0).putFloat(12, 0);
        var scratch = SCRATCH.get();
        scratch.projection.set(projection).invert().get(32, data);
        scratch.view.set(viewRotation).invert().get(96, data);
    }

    public static int writeLights(ByteBuffer data, List<Light> lights, double cameraX,
                                  double cameraY, double cameraZ, float pulse) {
        return writeLights(data,lights,cameraX,cameraY,cameraZ,pulse,null);
    }
    public static int writeLights(ByteBuffer data,List<Light> lights,double cameraX,double cameraY,double cameraZ,float pulse,Matrix4fc clip) {
        int count = 0;
        var selected = SCRATCH.get().lights;
        selected.select(lights,cameraX,cameraY,cameraZ,MAX_LIGHTS,clip);
        for (int i=0;i<selected.size();i++) {
            Light light = selected.get(i);
            float x = (float)(light.x() - cameraX), y = (float)(light.y() - cameraY), z = (float)(light.z() - cameraZ);
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || light.intensity() == 0) continue;
            int offset = HEADER_BYTES + count * 32;
            data.putFloat(offset, x).putFloat(offset + 4, y).putFloat(offset + 8, z).putFloat(offset + 12, light.radius());
            data.putFloat(offset + 16, light.red()).putFloat(offset + 20, light.green()).putFloat(offset + 24, light.blue())
                .putFloat(offset + 28, Math.min(light.intensity(), 16) * pulse);
            count++;
        }
        for (int offset = HEADER_BYTES + count * 32; offset < BYTES; offset += 4) data.putFloat(offset, 0);
        data.putFloat(12, count);
        return count;
    }
}
