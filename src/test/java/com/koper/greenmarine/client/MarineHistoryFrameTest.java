package com.koper.greenmarine.client;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MarineHistoryFrameTest {
    @Test void elapsedTimeUsesOnlyTheLastSuccessfulSubmission() {
        var history=new MarineHistoryFrame();var data=bytes();var camera=camera(0,0,0);
        history.write(data,camera,1_000_000_000L);assertEquals(0,data.getFloat(156));
        history.submitted(camera,true,1_000_000_000L);
        history.write(data,camera,1_010_000_000L);assertEquals(.01f,data.getFloat(156),1e-6);
        history.write(data,camera,1_020_000_000L);assertEquals(.02f,data.getFloat(156),1e-6);
        history.write(data,camera,5_000_000_000L);assertEquals(.25f,data.getFloat(156),1e-6);
        history.invalidate();history.write(data,camera,5_010_000_000L);assertEquals(0,data.getFloat(156));
    }
    private final Object world = new Object();
    private Matrix4f projection() { return new Matrix4f().perspective(1.2f, 1.6f, .05f, 256f, true); }
    private MarineHistoryFrame.Camera camera(double x, double y, double z) {
        return new MarineHistoryFrame.Camera(projection(),new Matrix4f(),x,y,z,world,Map.of());
    }
    private ByteBuffer bytes() { return ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder()); }

    @Test void unsubmittedFramesNeverAdvertiseHistoryAndWriteInitializedFallbacks() {
        var history = new MarineHistoryFrame(); var frame = bytes();
        history.write(frame,camera(0,0,0));
        assertEquals(0,frame.getFloat(144)); assertEquals(0,frame.getFloat(148));
        assertEquals(1,frame.getFloat(64)); assertEquals(0,frame.getFloat(128));
        assertTrue(Float.isFinite(frame.getFloat(0)));
    }

    @Test void submissionPublishesPreviousMatricesAndCurrentMinusPreviousCameraDelta() {
        var history = new MarineHistoryFrame(); var frame = bytes();
        history.submitted(camera(100,30,-20),true);
        history.write(frame,camera(102,29,-17));
        assertEquals(1,frame.getFloat(144)); assertEquals(1,frame.getFloat(148));
        assertEquals(2,frame.getFloat(128)); assertEquals(-1,frame.getFloat(132)); assertEquals(3,frame.getFloat(136));
        // A surface at world z=-27 has current-relative z=-10 and previous-relative z=-7.
        var relative = new Vector4f(-2,1,-10,1);
        relative.add(frame.getFloat(128),frame.getFloat(132),frame.getFloat(136),0);
        var clip = new Matrix4f(0,frame).transform(new Matrix4f(64,frame).transform(relative));
        var expected = projection().transform(new Vector4f(0,0,-7,1));
        assertEquals(expected.x/expected.w,clip.x/clip.w,1e-6);
        assertEquals(expected.z/expected.w,clip.z/clip.w,1e-6);
    }

    @Test void missingNormalsCannotAdvertiseNormalHistory() {
        var history = new MarineHistoryFrame(); var frame = bytes();
        history.submitted(camera(0,0,0),false); history.write(frame,camera(0,0,0));
        assertEquals(1,frame.getFloat(144)); assertEquals(0,frame.getFloat(148));
    }

    @Test void teleportAndRotationDiscontinuitiesRejectHistory() {
        var history = new MarineHistoryFrame(); var frame = bytes();
        history.submitted(camera(0,0,0),true);
        history.write(frame,camera(8,0,0)); assertEquals(1,frame.getFloat(144));
        history.write(frame,camera(8.01,0,0)); assertEquals(0,frame.getFloat(144));
        var rotated = new MarineHistoryFrame.Camera(projection(),new Matrix4f().rotateY((float)Math.toRadians(46)),0,0,0,world,Map.of());
        history.write(frame,rotated); assertEquals(0,frame.getFloat(144));
    }

    @Test void bobAndHurtDoNotRejectRigidProjectionChanges() {
        var history = new MarineHistoryFrame(); var frame = bytes();
        history.submitted(camera(0,0,0),true);
        var bob = projection().translate(.12f,-.19f,.03f).rotateZ(.08f).rotateX(.06f);
        history.write(frame,new MarineHistoryFrame.Camera(bob,new Matrix4f(),0,0,0,world,Map.of()));
        assertEquals(1,frame.getFloat(144));
    }

    @Test void fovAndAspectChangesRejectHistory() {
        var history = new MarineHistoryFrame(); var frame = bytes();
        history.submitted(camera(0,0,0),true);
        for(var changed : new Matrix4f[]{new Matrix4f().perspective(1.21f,1.6f,.05f,256f,true),new Matrix4f().perspective(1.2f,1.61f,.05f,256f,true)}) {
            history.write(frame,new MarineHistoryFrame.Camera(changed,new Matrix4f(),0,0,0,world,Map.of()));
            assertEquals(0,frame.getFloat(144));
        }
    }

    @Test void worldAndParameterChangesRejectHistory() {
        var history = new MarineHistoryFrame(); var frame = bytes();
        history.submitted(camera(0,0,0),true);
        history.write(frame,new MarineHistoryFrame.Camera(projection(),new Matrix4f(),0,0,0,new Object(),Map.of()));
        assertEquals(0,frame.getFloat(144));
        history.write(frame,new MarineHistoryFrame.Camera(projection(),new Matrix4f(),0,0,0,world,Map.of("exposure",1.5f)));
        assertEquals(0,frame.getFloat(144));
    }

    @Test void invalidationResetsHistoryAgeAndReadiness() {
        var history = new MarineHistoryFrame(); var frame = bytes();
        history.submitted(camera(0,0,0),true); history.submitted(camera(0,0,0),true);
        history.write(frame,camera(0,0,0)); assertEquals(2,frame.getFloat(152));
        history.invalidate(); history.write(frame,camera(0,0,0)); assertEquals(0,frame.getFloat(144)); assertEquals(0,frame.getFloat(152));
        history.submitted(camera(0,0,0),true); history.write(frame,camera(0,0,0)); assertEquals(1,frame.getFloat(152));
    }

    @Test void invalidCameraCannotPublishHistory() {
        var history = new MarineHistoryFrame(); var frame = bytes();
        history.submitted(camera(0,0,0),true);
        history.write(frame,camera(Double.NaN,0,0)); assertEquals(0,frame.getFloat(144));
        history.submitted(camera(Double.NaN,0,0),true);
        history.write(frame,camera(0,0,0)); assertEquals(0,frame.getFloat(144));
    }

    @Test void preparingAnUnsubmittedFrameDoesNotAdvanceStoredCamera() {
        var history = new MarineHistoryFrame(); var frame = bytes();
        history.submitted(camera(0,0,0),true);
        history.write(frame,camera(2,0,0)); history.write(frame,camera(3,0,0));
        assertEquals(3,frame.getFloat(128)); assertEquals(1,frame.getFloat(152));
    }

    @Test void capturedMatricesDoNotFollowLaterMutableHostChanges() {
        var history = new MarineHistoryFrame(); var frame = bytes();
        var projection = projection(); var view = new Matrix4f();
        var captured = new MarineHistoryFrame.Camera(projection,view,0,0,0,world,Map.of());
        history.submitted(captured,true);
        projection.identity(); view.rotateY(1);
        history.write(frame,camera(0,0,0));
        assertEquals(1,frame.getFloat(144)); assertEquals(0,frame.getFloat(72));
        assertEquals(projection().m00(),frame.getFloat(0),1e-6);
    }
}
