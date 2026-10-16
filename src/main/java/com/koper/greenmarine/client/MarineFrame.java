package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.script.PackScript;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import java.nio.ByteBuffer;

public final class MarineFrame {
    public static final int BYTES = 288;
    private static final ThreadLocal<Matrix4f[]> MATRICES = ThreadLocal.withInitial(() -> new Matrix4f[]{new Matrix4f(),new Matrix4f()});
    private MarineFrame() {}
    public static void write(ByteBuffer data, int width, int height, float time, float day, boolean zeroToOne,
                             Quality quality, RayMode rays, Matrix4fc projection, Matrix4fc view, PackScript script) {
        data.putFloat(0, width).putFloat(4, height).putFloat(8, time).putFloat(12, day);
        data.putFloat(16, zeroToOne ? 1 : 0).putFloat(20, quality.ordinal()).putFloat(24, rays.ordinal()).putFloat(28, 0);
        var matrices=MATRICES.get();
        matrices[0].set(projection).invert().get(32,data); matrices[1].set(view).invert().get(96,data);
        script.writeParameters(data, 160);
    }
}
