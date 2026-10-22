package com.koper.greenmarine.client.rt;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.core.Direction;
import java.util.EnumSet;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerReflectionGeometryTest {
    private static ModelPart.Cube cube(float deformation,boolean mirror) {
        return new ModelPart.Cube(0,0,-4,-8,-4,8,8,8,deformation,deformation,deformation,mirror,64,64,EnumSet.allOf(Direction.class));
    }
    @Test void serializesFixedNativePayloadAndSceneRelativeWorldToRawCubeTransform() {
        var builder=new PlayerReflectionGeometry.Builder();
        assertTrue(builder.add(cube(0,false),new Matrix4f().translation(10,2,-3).rotateY((float)Math.PI/2).scale(.9375f)));
        var data=builder.data();
        assertEquals(4624,data.remaining());assertEquals(1,data.getFloat(0));assertTrue(data.isReadOnly());
        var inverse=new Matrix4f().set(16,data);
        var raw=inverse.transformPosition(new Vector3f(10,2,-3.9375f));
        assertEquals(16,raw.x,1e-4);assertEquals(0,raw.y,1e-4);assertEquals(0,raw.z,1e-4);
        assertEquals(-4,data.getFloat(80));assertEquals(-8,data.getFloat(84));assertEquals(4,data.getFloat(96));
        assertEquals(0,data.getFloat(92));assertEquals(0,data.getFloat(108));
        for(int i=16+288;i<4624;i+=4) assertEquals(0,data.getFloat(i));
    }
    @Test void expandedOverlayBoundsComeFromActualVerticesRatherThanUndeformedCubeMetadata() {
        var builder=new PlayerReflectionGeometry.Builder();var cube=cube(.5f,false);
        assertEquals(-4,cube.minX);
        assertTrue(builder.add(cube,new Matrix4f()));
        assertEquals(-4.5f,builder.data().getFloat(80));assertEquals(4.5f,builder.data().getFloat(96));
    }
    @Test void affineUvRowsMatchEveryActualPolygonVertexIncludingMirroredAndExpandedFaces() {
        for(boolean mirror:new boolean[]{false,true}) for(float deform:new float[]{0,.25f}) {
            var cube=cube(deform,mirror);var builder=new PlayerReflectionGeometry.Builder();assertTrue(builder.add(cube,new Matrix4f()));
            var data=builder.data();
            for(var polygon:cube.polygons) {
                var a=polygon.vertices()[0];var normal=polygon.normal();
                int axis=Math.abs(normal.x())>.5f?0:Math.abs(normal.y())>.5f?1:2;
                float coordinate=axis==0?a.x():axis==1?a.y():a.z();
                float center=axis==1?-4:0;
                int face=axis*2+(coordinate>center?1:0);
                for(var vertex:polygon.vertices()) {
                    var point=new Vector4f(vertex.x(),vertex.y(),vertex.z(),1);
                    assertEquals(vertex.u(),new Vector4f().set(112+face*16,data).dot(point),1e-5);
                    assertEquals(vertex.v(),new Vector4f().set(208+face*16,data).dot(point),1e-5);
                }
            }
        }
    }
    @Test void rejectsNonfiniteSingularAndDegenerateBoxesAndStopsAtSixteen() {
        var builder=new PlayerReflectionGeometry.Builder();
        assertFalse(builder.add(cube(0,false),new Matrix4f().scale(0)));
        assertFalse(builder.add(cube(0,false),new Matrix4f().m30(Float.NaN)));
        assertFalse(builder.add(new ModelPart.Cube(0,0,0,0,0,0,8,8,0,0,0,false,64,64,EnumSet.allOf(Direction.class)),new Matrix4f()));
        for(int i=0;i<16;i++) assertTrue(builder.add(cube(0,false),new Matrix4f()));
        assertFalse(builder.add(cube(0,false),new Matrix4f()));assertEquals(16,builder.count());
        for(int i=0;i<4624;i+=4) assertTrue(Float.isFinite(builder.data().getFloat(i)));
    }
    @Test void visibleTraversalHonorsParentVisibilityAndSkipDrawDespiteVisitIgnoringThem() {
        var child=new ModelPart(java.util.List.of(cube(0,false)),java.util.Map.of());
        var root=new ModelPart(java.util.List.of(),java.util.Map.of("child",child));
        assertEquals(1,PlayerReflectionGeometry.capture(root,new PoseStack()).count());
        child.visible=false;assertEquals(0,PlayerReflectionGeometry.capture(root,new PoseStack()).count());
        child.visible=true;root.visible=false;assertEquals(0,PlayerReflectionGeometry.capture(root,new PoseStack()).count());
        root.visible=true;root.skipDraw=true;assertEquals(1,PlayerReflectionGeometry.capture(root,new PoseStack()).count());
        child.skipDraw=true;assertEquals(0,PlayerReflectionGeometry.capture(root,new PoseStack()).count());
    }

    @Test void actualVanillaAnimationMovesCapturedLimbAndStateFlagsHideOverlays() {
        var model=new PlayerModel(LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE,false),64,64).bakeRoot(),false);
        var state=new net.minecraft.client.renderer.entity.state.AvatarRenderState();
        state.speedValue=1;state.mainArm=net.minecraft.world.entity.HumanoidArm.RIGHT;
        state.leftArmPose=state.rightArmPose=net.minecraft.client.model.HumanoidModel.ArmPose.EMPTY;
        state.showHat=state.showJacket=state.showLeftSleeve=state.showRightSleeve=state.showLeftPants=state.showRightPants=true;
        model.setupAnim(state);
        assertEquals(12,PlayerReflectionGeometry.capture(model.root(),new PoseStack()).count());
        var still=PlayerReflectionGeometry.capture(model.rightArm,new PoseStack()).data();
        state.walkAnimationPos=1.2f;state.walkAnimationSpeed=.8f;model.setupAnim(state);
        var moving=PlayerReflectionGeometry.capture(model.rightArm,new PoseStack()).data();
        assertNotEquals(still.getFloat(16+20),moving.getFloat(16+20));
        state.showHat=state.showJacket=state.showLeftSleeve=state.showRightSleeve=state.showLeftPants=state.showRightPants=false;
        model.setupAnim(state);assertEquals(6,PlayerReflectionGeometry.capture(model.root(),new PoseStack()).count());
    }
    @Test void realVanillaSlimArmsAndOverlayVisibilityAreCapturedWithoutHostModels() {
        for(boolean slim:new boolean[]{false,true}) {
            var model=new PlayerModel(LayerDefinition.create(PlayerModel.createMesh(CubeDeformation.NONE,slim),64,64).bakeRoot(),slim);
            var all=PlayerReflectionGeometry.capture(model.root(),new PoseStack());assertEquals(12,all.count());
            final float[] armWidth={0};
            model.rightArm.visit(new PoseStack(),(pose,path,index,cube)->{if(path.isEmpty())armWidth[0]=cube.maxX-cube.minX;});
            assertEquals(slim?3:4,armWidth[0]);
            model.hat.visible=model.jacket.visible=model.leftSleeve.visible=model.rightSleeve.visible=model.leftPants.visible=model.rightPants.visible=false;
            assertEquals(6,PlayerReflectionGeometry.capture(model.root(),new PoseStack()).count());
        }
    }
}
