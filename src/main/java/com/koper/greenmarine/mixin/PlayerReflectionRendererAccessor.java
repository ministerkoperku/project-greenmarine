package com.koper.greenmarine.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Run vanilla virtual root transforms on a caller-owned pose, without touching a host model. */
@Mixin(LivingEntityRenderer.class)
public interface PlayerReflectionRendererAccessor {
    @Invoker("setupRotations")
    void greenmarine$reflectionRotations(LivingEntityRenderState state,PoseStack pose,float bodyRot,float scale);
    @Invoker("scale")
    void greenmarine$reflectionScale(LivingEntityRenderState state,PoseStack pose);
    @Accessor("layers")
    java.util.List<net.minecraft.client.renderer.entity.layers.RenderLayer<?,?>> greenmarine$reflectionLayers();
}
