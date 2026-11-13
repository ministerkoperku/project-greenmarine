package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.rt.EntityLights;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Preserve renderer-specific vanilla lighting before adding Solstice's dynamic block light. */
@Mixin(EntityRenderer.class)
public abstract class EntityLightMixin {
    @Inject(method="getPackedLightCoords",at=@At("RETURN"),cancellable=true)
    private void greenmarine$dynamicLight(Entity entity,float partialTick,CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(EntityLights.packedLight(entity,partialTick,cir.getReturnValueI()));
    }
}
