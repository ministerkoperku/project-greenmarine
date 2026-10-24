package com.koper.greenmarine.development.mixin;

import com.koper.greenmarine.development.DevelopmentClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class DevelopmentTickMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void greenmarineDevelopment$tick(CallbackInfo ci) {
        DevelopmentClient.tick((Minecraft)(Object)this);
    }
}
