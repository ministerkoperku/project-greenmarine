package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.GreenResources;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.server.packs.repository.BuiltInPackSource;
import net.minecraft.server.packs.repository.Pack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.Consumer;

@Mixin(BuiltInPackSource.class)
public abstract class BuiltInPackSourceMixin {
    @Inject(method = "loadPacks", at = @At("TAIL"))
    private void greenmarine$resources(Consumer<Pack> consumer, CallbackInfo ci) {
        if ((Object)this instanceof ClientPackSource) {
            var mod = FabricLoader.getInstance().getModContainer("greenmarine").orElseThrow();
            consumer.accept(GreenResources.createPack(mod.getRootPaths()));
        }
    }
}
