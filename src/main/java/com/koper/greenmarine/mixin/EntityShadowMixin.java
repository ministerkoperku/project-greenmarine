package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.rt.RayWorld;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Hardware surface RT already casts the sun shadow of every traced actor; drop the vanilla blob under it. */
@Mixin(EntityRenderer.class)
public abstract class EntityShadowMixin {
    @Inject(method="finalizeRenderState",at=@At("TAIL"))
    private void greenmarine$tracedPlayerShadow(Entity entity,EntityRenderState state,CallbackInfo ci) {
        if(RayWorld.playerShadowTraced() && com.koper.greenmarine.client.rt.PlayerReflection.traced(entity.getId())) {state.shadowPieces.clear();state.shadowRadius=0;}
    }
}
