package com.koper.greenmarine.client.rt;

import com.koper.greenmarine.mixin.ArmorLayerAccessor;
import com.koper.greenmarine.mixin.EquipmentAssetsAccessor;
import com.koper.greenmarine.mixin.PlayerReflectionRendererAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import java.util.List;

/** Worn humanoid armor as actor boxes: the slots, armor models, assets and base texture HumanoidArmorLayer uses.
 * Dye tints and trims are not represented; only the first non-player-texture layer of each piece is traced. */
final class ArmorReflection {
    private static final EquipmentSlot[] SLOTS={EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET};
    private ArmorReflection() {}

    /** Returns how many armor pieces were appended whole after the wearer's body. */
    static int append(Minecraft client,PlayerReflectionGeometry.Builder builder,List<PlayerReflectionKAvatar.TextureSpan> spans,
                      Object renderer,LivingEntityRenderState state,PoseStack pose) {
        if(!(state instanceof HumanoidRenderState humanoid) || !(renderer instanceof PlayerReflectionRendererAccessor living))return 0;
        for(var layer:living.greenmarine$reflectionLayers()) {
            if(!(layer instanceof HumanoidArmorLayer<?,?,?> armor))continue;
            var models=(ArmorLayerAccessor)armor;
            var assets=((EquipmentAssetsAccessor)models.greenmarine$equipment()).greenmarine$assets();
            boolean baby=humanoid.isBaby && humanoid.entityType!=EntityTypes.ARMOR_STAND && models.greenmarine$babyModels()!=null;
            int added=0;
            for(var slot:SLOTS) {
                var stack=equipment(humanoid,slot);
                if(stack==null || !HumanoidArmorLayer.shouldRender(stack,slot))continue;
                var equippable=stack.get(DataComponents.EQUIPPABLE);
                if(equippable==null || equippable.assetId().isEmpty())continue;
                var type=baby?EquipmentClientInfo.LayerType.HUMANOID_BABY
                    :slot==EquipmentSlot.LEGS?EquipmentClientInfo.LayerType.HUMANOID_LEGGINGS:EquipmentClientInfo.LayerType.HUMANOID;
                var texture=assets.get(equippable.assetId().get()).getLayers(type).stream()
                    .filter(piece->!piece.usePlayerTexture()).findFirst().map(piece->piece.getTextureLocation(type)).orElse(null);
                if(texture==null || !PlayerReflection.usableTexture(client,texture))continue;
                @SuppressWarnings({"unchecked","rawtypes"})
                HumanoidModel model=(HumanoidModel)(baby?models.greenmarine$babyModels():models.greenmarine$models()).get(slot);
                if(model==null)continue;
                model.setupAnim(humanoid);
                int first=builder.count();
                if(!PlayerReflectionGeometry.appendWhole(builder,model.root(),pose))continue;
                spans.add(new PlayerReflectionKAvatar.TextureSpan(texture,first,builder.count()-first));added++;
            }
            return added;
        }
        return 0;
    }
    private static ItemStack equipment(HumanoidRenderState state,EquipmentSlot slot) {
        return switch(slot) {
            case HEAD->state.headEquipment;case CHEST->state.chestEquipment;case LEGS->state.legsEquipment;case FEET->state.feetEquipment;
            default->null;
        };
    }
}
