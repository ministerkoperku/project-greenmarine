package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.world.WorldPrograms;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FrontendCommandEncoder.class)
public abstract class WorldSubmitMixin {
    @Inject(method="submit",at=@At("RETURN"))
    private void greenmarine$submitted(CallbackInfo ci) {WorldPrograms.submitted();}
}
