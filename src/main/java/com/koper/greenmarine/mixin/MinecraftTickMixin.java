package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.GreenMarineClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftTickMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void greenmarine$tick(CallbackInfo ci) { GreenMarineClient.tick((Minecraft)(Object)this); }
}
