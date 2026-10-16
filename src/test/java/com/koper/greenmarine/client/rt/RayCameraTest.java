package com.koper.greenmarine.client.rt;

import org.joml.*;
import org.junit.jupiter.api.Test;
import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;

class RayCameraTest {
    @Test void nativePrimaryRayPassesThroughRasterSurfaceWithTranslatedBobbing() {
        for (boolean zeroToOne : new boolean[]{true, false}) {
            var projection = new Matrix4f().perspective(1.2f, 1.6f, .05f, 256f, zeroToOne)
                .translate(.12f, -.19f, .03f).rotateZ(.04f).rotateX(.06f);
            check(projection, new Matrix4f().rotateY(.7f).rotateX(-.2f), zeroToOne);
        }
    }
    @Test void primaryRayMatchesHurtAndNauseaProjection() {
        var projection = new Matrix4f().perspective(1.0f, 1.6f, .05f, 256f, true)
            .translate(.02f, -.1f, 0).rotateZ(.18f).rotate(.2f, 0, .7071f, .7071f)
            .scale(.85f, 1, 1).rotate(-.2f, 0, .7071f, .7071f);
        check(projection, new Matrix4f().rotateY(-.4f), true);
    }
    private void check(Matrix4f projection, Matrix4f view, boolean zeroToOne) {
        var bytes = ByteBuffer.allocateDirect(320).order(ByteOrder.nativeOrder());
        RayCamera.write(bytes, projection, view);
        var inverseProjection = new Matrix4f(0, bytes);
        var inverseView = new Matrix4f(64, bytes);
        var target = new Vector3f(1.2f, -.7f, -7f);
        var clip = projection.transform(view.transform(new Vector4f(target, 1)));
        var near = inverseProjection.transform(new Vector4f(clip.x/clip.w, clip.y/clip.w, zeroToOne?1:-1, 1));
        var direction = inverseView.transformDirection(new Vector3f(near.x, near.y, near.z).div(near.w)).normalize();
        var origin = inverseView.getTranslation(new Vector3f());
        var relative = new Vector3f(target).sub(origin);
        assertTrue(new Vector3f(relative).cross(direction).length() < 0.0001f,
            "Ray missed the rasterized surface: " + relative.cross(direction).length());
        var cameraClip = projection.transform(view.transform(new Vector4f(origin, 1)));
        assertEquals(0, cameraClip.w, .00001f, "Ray origin must be the effective perspective eye");
    }
}
