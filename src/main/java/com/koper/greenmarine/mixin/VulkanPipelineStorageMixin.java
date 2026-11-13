package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.RasterStorage;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

/** Storage bindings get descriptor types 7/3 in the push-descriptor set layout. */
@Mixin(VulkanRenderPipeline.class)
public abstract class VulkanPipelineStorageMixin implements RasterStorage.Pipeline {
    @Unique private static final ThreadLocal<int[]> greenmarine$types = new ThreadLocal<>();
    @Unique private static final ThreadLocal<int[]> greenmarine$index = ThreadLocal.withInitial(() -> new int[1]);
    @Unique private int[] greenmarine$storage;
    @Override public int[] greenmarine$storageTypes() { return greenmarine$storage; }
    @Override public void greenmarine$storageTypes(int[] types) { greenmarine$storage = types; }

    @WrapMethod(method="compile")
    private static VulkanRenderPipeline greenmarine$begin(VulkanDevice device,BackendRenderPipeline.CreateInfo info,Operation<VulkanRenderPipeline> original) {
        greenmarine$types.set(RasterStorage.any(info.name())
            ? RasterStorage.descriptorTypes(info.name(),info.uniforms().stream().map(u -> u.name()).toList()) : null);
        greenmarine$index.get()[0] = 0;
        try {
            var pipeline=com.koper.greenmarine.client.GraphicsStagePipelines.compile(device,info,()->original.call(device,info));
            var types=greenmarine$types.get();
            if(types!=null&&pipeline!=null)((RasterStorage.Pipeline)(Object)pipeline).greenmarine$storageTypes(types);
            return pipeline;
        }finally{greenmarine$types.remove();greenmarine$index.remove();}
    }
    @ModifyArg(method="compile",at=@At(value="INVOKE",target="Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;descriptorType(I)Lorg/lwjgl/vulkan/VkDescriptorSetLayoutBinding;"))
    private static int greenmarine$layoutType(int type) {
        var types = greenmarine$types.get();
        int i = greenmarine$index.get()[0]++;
        return types != null && i < types.length && types[i] != 0 ? types[i] : type;
    }
}
