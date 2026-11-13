package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.rt.DynamicLights;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class DynamicLightFrameMixin {
    @Inject(method="renderFrame",at=@At("HEAD"))
    private void greenmarine$beginDynamicLights(boolean renderLevel,CallbackInfo ci) {
        DynamicLights.beginFrame();
    }
}
