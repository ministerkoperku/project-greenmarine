package com.koper.greenmarine.probe.mixin;

import com.koper.greenmarine.probe.RuntimeProbe;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ProbeTickMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void greenmarine$probe(CallbackInfo ci) { RuntimeProbe.tick((Minecraft)(Object)this); }
}
