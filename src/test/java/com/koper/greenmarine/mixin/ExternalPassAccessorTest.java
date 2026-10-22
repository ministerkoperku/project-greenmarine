package com.koper.greenmarine.mixin;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.gen.Accessor;
import static org.junit.jupiter.api.Assertions.*;

class ExternalPassAccessorTest {
    @Test void accessorsNameActualHostFieldsWithMatchingTypes() throws Exception {
        check(ExternalPassAccessor.class,com.mojang.renderpearl.frontend.FrontendRenderPass.class);
        check(ExternalVulkanPassAccessor.class,com.mojang.renderpearl.backend.vulkan.VulkanRenderPass.class);
    }
    private static void check(Class<?> accessor,Class<?> host) throws Exception {
        for(var method:accessor.getDeclaredMethods()) {
            var field=host.getDeclaredField(method.getAnnotation(Accessor.class).value());
            assertEquals(field.getType(),method.getReturnType(),accessor.getSimpleName()+" "+field.getName());
        }
    }
}
