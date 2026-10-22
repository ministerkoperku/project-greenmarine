package com.koper.greenmarine.client.rt;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import java.util.function.Consumer;

/** Exact Minecraft 26.3 EntityRenderDispatcher/LivingEntityRenderer root order. */
final class PlayerReflectionPose {
    private PlayerReflectionPose() {}
    static PoseStack root(AvatarRenderState state,int originX,int originY,int originZ,Vec3 offset,
                          Consumer<PoseStack> rotations,Consumer<PoseStack> rendererScale) {
        var pose=new PoseStack();
        // Subtract in double precision before PoseStack converts to float coordinates.
        pose.translate(state.x-originX+offset.x(),state.y-originY+offset.y(),state.z-originZ+offset.z());
        if(state.hasPose(Pose.SLEEPING) && state.bedOrientation!=null) {
            float eye=state.eyeHeight-.1f;
            pose.translate(-state.bedOrientation.getStepX()*eye,0,-state.bedOrientation.getStepZ()*eye);
        }
        pose.scale(state.scale,state.scale,state.scale);
        rotations.accept(pose);
        pose.scale(-1,-1,1);
        rendererScale.accept(pose);
        pose.translate(0,-1.501f,0);
        return pose;
    }
}
