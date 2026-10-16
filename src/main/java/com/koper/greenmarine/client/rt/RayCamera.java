package com.koper.greenmarine.client.rt;

import org.joml.*;
import java.nio.ByteBuffer;

/** Camera matrices for the native 320-byte frame. */
public final class RayCamera {
    private RayCamera() {}
    public static void write(ByteBuffer frame, Matrix4fc projection, Matrix4fc view) {
        var inverseProjection = new Matrix4f(projection).invert();
        // P includes bob/hurt translation. Its perspective eye is not the physical camera.
        var eye = inverseProjection.transform(new Vector4f(0, 0, 1, 0));
        if (!Float.isFinite(eye.w) || java.lang.Math.abs(eye.w) < 1e-8f)
            throw new IllegalArgumentException("World ray camera requires a perspective projection");
        var offset = new Vector3f(eye.x, eye.y, eye.z).div(eye.w);
        new Matrix4f().translation(new Vector3f(offset).negate()).mul(inverseProjection).get(0, frame);
        new Matrix4f(view).invert().translate(offset).get(64, frame);
        projection.get(128, frame);
        view.get(192, frame);
    }
}
