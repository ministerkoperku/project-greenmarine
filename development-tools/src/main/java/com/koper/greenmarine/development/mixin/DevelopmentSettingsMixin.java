package com.koper.greenmarine.development.mixin;

import com.koper.greenmarine.client.ShaderScreen;
import com.koper.greenmarine.development.WorkbenchScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ShaderScreen.class, remap = false)
public abstract class DevelopmentSettingsMixin extends Screen {
    protected DevelopmentSettingsMixin(Component title) { super(title); }
    @Inject(method = "init", at = @At("TAIL"))
    private void greenmarineDevelopment$button(CallbackInfo ci) {
        addRenderableWidget(com.koper.greenmarine.development.StudioUi.button(width-96,10,82,24,"Dev tools","Open shader and material authoring tools.",()->minecraft.gui.setScreen(new WorkbenchScreen(this))));
    }
}
