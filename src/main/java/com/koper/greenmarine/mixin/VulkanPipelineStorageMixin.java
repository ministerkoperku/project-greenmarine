package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.RasterStorage;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Storage bindings get descriptor types 7/3 in the push-descriptor set layout. */
@Mixin(VulkanRenderPipeline.class)
public abstract class VulkanPipelineStorageMixin implements RasterStorage.Pipeline {
    @Unique private static final ThreadLocal<int[]> greenmarine$types = new ThreadLocal<>();
    @Unique private static final ThreadLocal<int[]> greenmarine$index = ThreadLocal.withInitial(() -> new int[1]);
    @Unique private int[] greenmarine$storage;
    @Override public int[] greenmarine$storageTypes() { return greenmarine$storage; }
    @Override public void greenmarine$storageTypes(int[] types) { greenmarine$storage = types; }

    @Inject(method="compile",at=@At("HEAD"))
    private static void greenmarine$begin(VulkanDevice device,BackendRenderPipeline.CreateInfo info,CallbackInfoReturnable<VulkanRenderPipeline> ci) {
        greenmarine$types.set(RasterStorage.any(info.name())
            ? RasterStorage.descriptorTypes(info.name(),info.uniforms().stream().map(u -> u.name()).toList()) : null);
        greenmarine$index.get()[0] = 0;
    }
    @ModifyArg(method="compile",at=@At(value="INVOKE",target="Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;descriptorType(I)Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;"))
    private static int greenmarine$layoutType(int type) {
        var types = greenmarine$types.get();
        int i = greenmarine$index.get()[0]++;
        return types != null && i < types.length && types[i] != 0 ? types[i] : type;
    }
    @Inject(method="compile",at=@At("RETURN"))
    private static void greenmarine$attach(VulkanDevice device,BackendRenderPipeline.CreateInfo info,CallbackInfoReturnable<VulkanRenderPipeline> ci) {
        var types = greenmarine$types.get();
        if (types != null && ci.getReturnValue() != null) ((RasterStorage.Pipeline) (Object) ci.getReturnValue()).greenmarine$storageTypes(types);
        greenmarine$types.remove();
    }
}
