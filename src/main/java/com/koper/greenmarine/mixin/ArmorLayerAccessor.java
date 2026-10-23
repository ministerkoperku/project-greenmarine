package com.koper.greenmarine.mixin;

import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only view of the armor models and equipment renderer for actor reflections. */
@Mixin(HumanoidArmorLayer.class)
public interface ArmorLayerAccessor {
    @Accessor("modelSet") ArmorModelSet<?> greenmarine$models();
    @Accessor("babyModelSet") ArmorModelSet<?> greenmarine$babyModels();
    @Accessor("equipmentRenderer") EquipmentLayerRenderer greenmarine$equipment();
}
