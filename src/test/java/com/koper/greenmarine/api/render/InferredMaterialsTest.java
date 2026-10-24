package com.koper.greenmarine.api.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InferredMaterialsTest {
    @Test void commonTextureFamiliesHaveGroundedDefaults() {
        for(String name:new String[]{"iron_bars","iron_door_top","anvil_top","copper_grate","cut_copper","chain","iron_chain","copper_chain","copper_bars"}) {
            var material=InferredMaterials.forTexture("minecraft:block/"+name);
            assertNotNull(material,name);assertEquals(2,material.surfaceClass(),name);
            assertTrue(material.reflectance()>=.5f,name);
        }
        for(String name:new String[]{"stone","deepslate","cobblestone","oak_planks","spruce_log","bamboo_block","white_wool","red_concrete"}) {
            var material=InferredMaterials.forTexture("minecraft:block/"+name);
            assertNotNull(material,name);assertEquals(1,material.surfaceClass(),name);
            assertTrue(material.roughness()>=.75f,name);assertTrue(material.reflectance()<=.08f,name);
        }
        var polished=InferredMaterials.forTexture("minecraft:block/polished_granite");
        assertEquals(9,polished.surfaceClass());assertTrue(polished.roughness()<.6f);
        var glass=InferredMaterials.forTexture("custom:block/blue_tempered_glass");
        assertEquals(5,glass.surfaceClass());assertTrue(glass.sunlightTransmission()>.7f);
        var oxidized=InferredMaterials.forTexture("minecraft:block/oxidized_cut_copper");
        assertEquals(9,oxidized.surfaceClass());assertTrue(oxidized.roughness()>.8f);
    }
    @Test void oresPlantsAndUnknownsNeverBecomeMetalOrTransparent() {
        for(String name:new String[]{"iron_ore","deepslate_gold_ore","raw_iron_block","oak_leaves","water_still","unknown","ironwood_planks","redstone_lamp"}) {
            var material=InferredMaterials.forTexture("minecraft:block/"+name);
            assertTrue(material==null || material.surfaceClass()==1,name);
        }
        assertNull(InferredMaterials.forTexture("custom:block/unknown"));
        assertNull(InferredMaterials.forTexture("minecraft:item/iron_ingot"));
    }
    @Test void everyExistingBuiltinProfileIsPreservedExactly() {
        BlockMaterials.atlasProfiles().forEach((name,material)->assertEquals(material,InferredMaterials.forTexture(name),name));
        BlockMaterials.additionalAtlasProfiles().forEach((name,material)->assertEquals(material,InferredMaterials.forTexture(name),name));
    }
}
