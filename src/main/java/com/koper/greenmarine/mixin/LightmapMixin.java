package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.PackRenderer;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(Lightmap.class)
public abstract class LightmapMixin {
    @Shadow @Final private GpuTextureView textureView;
    @Inject(method="render",at=@At("HEAD"))
    private void greenmarine$invalidate(LightmapRenderState state,CallbackInfo ci) {
        if (state.needsUpdate) PackRenderer.invalidateLightmap();
    }
    @Inject(method="render",at=@At("RETURN"))
    private void greenmarine$render(LightmapRenderState state,CallbackInfo ci) { PackRenderer.renderLightmap(textureView); }
    @Inject(method="getTextureView",at=@At("RETURN"),cancellable=true)
    private void greenmarine$view(CallbackInfoReturnable<GpuTextureView> ci) { ci.setReturnValue(PackRenderer.lightmapView(textureView)); }
}
