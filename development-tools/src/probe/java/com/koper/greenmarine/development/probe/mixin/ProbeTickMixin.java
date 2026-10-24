package com.koper.greenmarine.development.probe.mixin;

import com.koper.greenmarine.development.probe.DevelopmentGuiProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ProbeTickMixin {
    @Inject(method="runTick",at=@At("TAIL"))
    private void greenmarineDevelopmentProbe$frame(boolean render,CallbackInfo callback){if(render)DevelopmentGuiProbe.renderedFrame();}
    @Inject(method="tick",at=@At("TAIL"))
    private void greenmarineDevelopmentProbe$tick(CallbackInfo callback){DevelopmentGuiProbe.tick((Minecraft)(Object)this);}
}
