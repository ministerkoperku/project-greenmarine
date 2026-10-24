package com.koper.greenmarine.mixin;

import com.koper.greenmarine.client.rt.RtFeatures;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;

/** Requests RT on the actual host device; unsupported complete feature sets are skipped by the host. */
@Mixin(VulkanFeatureSets.class)
public abstract class VulkanRtFeaturesMixin {
    @Inject(method="optionalFeatureSets",at=@At("RETURN"),cancellable=true)
    private static void greenmarine$optionalRt(CallbackInfoReturnable<Set<FeatureSet>> ci) {
        var features=new HashSet<>(ci.getReturnValue());
        features.addAll(com.koper.greenmarine.client.StorageFeatures.OPTIONAL);
        if(!Boolean.getBoolean("greenmarine.native.disable")) features.add(RtFeatures.OPTIONAL);
        ci.setReturnValue(Set.copyOf(features));
    }
}
