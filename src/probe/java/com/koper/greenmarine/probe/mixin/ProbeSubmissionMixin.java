package com.koper.greenmarine.probe.mixin;

import com.koper.greenmarine.probe.ProbeSubmissionFault;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FrontendCommandEncoder.class)
public abstract class ProbeSubmissionMixin {
    @Inject(method="submit",at=@At("RETURN"))
    private void greenmarine$afterActualSubmit(CallbackInfo ci) {ProbeSubmissionFault.afterHostSubmit();}
}
