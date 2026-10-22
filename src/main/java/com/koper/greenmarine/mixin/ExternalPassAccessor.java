package com.koper.greenmarine.mixin;

import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Frontend passes record immediately into their backend pass; external renderers append to it. */
@Mixin(FrontendRenderPass.class)
public interface ExternalPassAccessor {
    @Accessor("backend") RenderPassBackend greenmarine$backend();
}
