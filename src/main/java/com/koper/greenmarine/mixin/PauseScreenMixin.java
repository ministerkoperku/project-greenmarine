package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.ShaderScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A clickable settings entry for launchers without physical function keys. */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    protected PauseScreenMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void greenmarine$settings(CallbackInfo ci) {
        if (!((PauseScreen)(Object)this).showsPauseMenu()) return;
        addRenderableWidget(Button.builder(Component.literal("GreenMarine"), button ->
            minecraft.gui.setScreen(new ShaderScreen((Screen)(Object)this)))
            .bounds(8, 8, 110, 20).build());
    }
}
