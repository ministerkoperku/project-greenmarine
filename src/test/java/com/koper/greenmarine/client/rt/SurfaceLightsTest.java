package com.koper.greenmarine.client.rt;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurfaceLightsTest {
    @BeforeAll static void bootstrap() {SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    // Item components are data-pack bound in 26.3; this test only needs real item
    // identities and block states, without loading a server's resource packs.
    private static ItemStack stack(Item item) {return new ItemStack(Holder.direct(item,DataComponentMap.EMPTY));}

    @Test void droppedTorchUsesBlockEmissionAndSoulColour() {
        var torch=SurfaceLights.itemSource(stack(Items.TORCH),1.25,2.5,-3.75);
        assertNotNull(torch);assertEquals(14,torch.level());
        assertEquals(1.25,torch.x());assertEquals(2.5,torch.y());assertEquals(-3.75,torch.z());
        var soul=SurfaceLights.itemSource(stack(Items.SOUL_TORCH),0,0,0);
        assertNotNull(soul);assertEquals(10,soul.level());assertTrue(soul.blue()>soul.red());
    }
    @Test void emptyAndOrdinaryItemsDoNotBecomeLights() {
        assertNull(SurfaceLights.itemSource(ItemStack.EMPTY,0,0,0));
        assertNull(SurfaceLights.itemSource(stack(Items.STONE),0,0,0));
        assertNull(SurfaceLights.itemSource(stack(Items.DIAMOND),0,0,0));
    }
    @Test void luminousNonBlockItemsHaveAnExplicitEmissionProfile() {
        assertEquals(15,SurfaceLights.itemSource(stack(Items.LAVA_BUCKET),0,0,0).level());
        assertEquals(10,SurfaceLights.itemSource(stack(Items.BLAZE_ROD),0,0,0).level());
        assertEquals(8,SurfaceLights.itemSource(stack(Items.GLOW_BERRIES),0,0,0).level());
    }
}
