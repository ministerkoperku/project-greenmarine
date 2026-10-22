package com.koper.greenmarine.client.rt;

import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerReflectionPoseTest {
    @Test void rootTransformMatchesVanillaOrderAndPreservesPrecisionAtWorldBorder() {
        var state=new AvatarRenderState();state.x=30_000_001.25;state.y=65;state.z=-30_000_000.5;state.scale=1;
        var pose=PlayerReflectionPose.root(state,30_000_000,64,-30_000_000,new Vec3(.2,-.125,.3),
            p->p.rotateDegrees(com.mojang.math.Axis.YP,180),p->p.scale(.9375f,.9375f,.9375f));
        var head=pose.last().pose().transformPosition(new Vector3f(0,0,0));
        assertEquals(1.45,head.x,1e-5);assertEquals(2.2821875,head.y,1e-5);assertEquals(-.2,head.z,1e-5);
        var foot=pose.last().pose().transformPosition(new Vector3f(0,1.5f,0));
        assertEquals(.8759375,foot.y,1e-5);
        var side=pose.last().pose().transformPosition(new Vector3f(1,0,0));
        assertEquals(2.3875,side.x,1e-5);
    }
    @Test void sleepingEyeOffsetPrecedesScaleAndVirtualRotation() {
        var state=new AvatarRenderState();state.scale=2;state.pose=Pose.SLEEPING;state.bedOrientation=Direction.EAST;state.eyeHeight=1.6f;
        var pose=PlayerReflectionPose.root(state,0,0,0,Vec3.ZERO,p->{},p->{});
        var origin=pose.last().pose().transformPosition(new Vector3f());
        assertEquals(-1.5,origin.x,1e-5);assertEquals(3.002,origin.y,1e-5);
    }
    @Test void nullClientReturnsFreshInitializedEmptyPayloadAndNoBorrowedSkin() {
        var first=PlayerReflection.snapshot(null,0,0,0);var second=PlayerReflection.snapshot(null,0,0,0);
        assertEquals(0,first.boxes());assertNull(first.skin());assertEquals(4624,first.data().remaining());
        assertNotSame(first.data(),second.data());assertTrue(first.data().isReadOnly());
        for(int i=0;i<4624;i+=4)assertEquals(0,first.data().getFloat(i));
    }
}
