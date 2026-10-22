package com.koper.greenmarine.api.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockMaterialsTest {
    private static com.google.gson.JsonObject vanillaCopperFixture(String path) throws java.io.IOException {
        try(var input=BlockMaterialsTest.class.getResourceAsStream("/materials/vanilla-copper/"+path)) {
            assertNotNull(input,"Missing vanilla copper asset fixture: "+path);
            return com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    @Test void copperOxidationUsesCoatedDielectricProfilesAndActualWaxedModelTextures() throws java.io.IOException {
        var names=new String[]{"copper_block","exposed_copper","weathered_copper","oxidized_copper"};
        var roughness=new float[]{.48f,.60f,.76f,.90f};
        var reflectance=new float[]{.65f,.35f,.22f,.12f};
        for(int stage=0;stage<names.length;stage++) {
            String name=names[stage];
            var material=BlockMaterials.forBlock("minecraft:"+name);
            assertEquals(stage==0?2:9,material.surfaceClass());
            assertEquals(roughness[stage],material.roughness());
            assertEquals(reflectance[stage],material.reflectance());
            assertEquals(0,material.sunlightTransmission());
            for(String block:new String[]{name,"waxed_"+name}) {
                var state=vanillaCopperFixture("blockstates/"+block+".json");
                String model=state.getAsJsonObject("variants").getAsJsonObject("").get("model").getAsString();
                var definition=vanillaCopperFixture("models/"+model.substring(model.lastIndexOf('/')+1)+".json");
                assertEquals("minecraft:block/cube_all",definition.get("parent").getAsString());
                String texture=definition.getAsJsonObject("textures").get("all").getAsString();
                assertEquals(material,BlockMaterials.forBlock("minecraft:"+block));
                var profiles=stage==0?BlockMaterials.atlasProfiles():BlockMaterials.additionalAtlasProfiles();
                assertEquals(material,profiles.get(texture),"Actual vanilla model texture must classify the surface");
                var bytes=java.nio.ByteBuffer.allocate(stage==0?1040:272).order(java.nio.ByteOrder.nativeOrder());
                java.util.function.Function<String,float[]> rectangles=id->id.equals(texture)?new float[]{.125f,.25f,.375f,.5f}:null;
                if(stage==0)BlockMaterials.writeAtlas(bytes,rectangles);else BlockMaterials.writeAtlasExtension(bytes,rectangles);
                int propertyOffset=stage==0?512:128,countOffset=stage==0?1024:256;
                assertEquals(1,bytes.getFloat(countOffset));
                assertEquals(.125f,bytes.getFloat(0));assertEquals(.5f,bytes.getFloat(12));
                assertEquals(roughness[stage],bytes.getFloat(propertyOffset));
                assertEquals(reflectance[stage],bytes.getFloat(propertyOffset+4));
                assertEquals(stage==0?2:9,bytes.getFloat(propertyOffset+12));
            }
        }
        assertEquals(32,BlockMaterials.atlasProfiles().size());
        assertEquals(5,BlockMaterials.additionalAtlasProfiles().size());
        assertFalse(BlockMaterials.additionalAtlasProfiles().keySet().stream().anyMatch(id->id.contains("waxed")),
            "Waxed blocks reuse vanilla textures without duplicate atlas slots");
    }

    @Test void extensionProfilesProduceRealAtlasClassificationWithoutChangingLegacyFrame() {
        assertEquals(32,BlockMaterials.atlasProfiles().size());
        assertFalse(BlockMaterials.atlasProfiles().containsKey("minecraft:block/diamond_block"));
        assertFalse(BlockMaterials.atlasProfiles().containsKey("minecraft:block/netherite_block"));
        var diamond=BlockMaterials.forBlock("minecraft:diamond_block");
        assertEquals(8,diamond.surfaceClass());assertEquals(.22f,diamond.roughness());
        assertTrue(diamond.reflectance()>0);assertEquals(0,diamond.sunlightTransmission());
        var netherite=BlockMaterials.forBlock("minecraft:netherite_block");
        assertEquals(2,netherite.surfaceClass());assertEquals(.65f,netherite.roughness());
        assertEquals(.55f,netherite.reflectance());assertEquals(0,netherite.sunlightTransmission());
        var bytes=java.nio.ByteBuffer.allocate(272).order(java.nio.ByteOrder.nativeOrder());
        for(var entry:BlockMaterials.additionalAtlasProfiles().entrySet()) {
            BlockMaterials.writeAtlasExtension(bytes,id->id.equals(entry.getKey())?new float[]{.2f,.3f,.4f,.5f}:null);
            assertEquals(1,bytes.getFloat(256));
            assertEquals(.2f,bytes.getFloat(0));assertEquals(.5f,bytes.getFloat(12));
            var surface=entry.getValue();
            assertEquals(surface.roughness(),bytes.getFloat(128));
            assertEquals(surface.reflectance(),bytes.getFloat(132));
            assertEquals(surface.sunlightTransmission(),bytes.getFloat(136));
            assertEquals(surface.surfaceClass(),bytes.getFloat(140));
            assertEquals(0,bytes.getFloat(16));assertEquals(0,bytes.getFloat(144));
        }
        assertEquals(diamond,BlockMaterials.additionalAtlasProfiles().get("minecraft:block/diamond_block"));
        assertEquals(netherite,BlockMaterials.additionalAtlasProfiles().get("minecraft:block/netherite_block"));
    }
    @Test void extensionInitializesMissingEntriesAndValidatesRectanglesAndStorage() {
        var bytes=java.nio.ByteBuffer.allocate(272).order(java.nio.ByteOrder.nativeOrder());
        for(int i=0;i<272;i+=4)bytes.putFloat(i,99);
        BlockMaterials.writeAtlasExtension(bytes,id->null);
        for(int i=0;i<272;i+=4)assertEquals(0,bytes.getFloat(i));
        BlockMaterials.writeAtlasExtension(bytes,id->new float[]{.5f,.5f,.2f,.2f});
        assertEquals(0,bytes.getFloat(256));
        assertThrows(IllegalArgumentException.class,()->BlockMaterials.writeAtlasExtension(java.nio.ByteBuffer.allocate(271),id->null));
        assertThrows(IllegalArgumentException.class,()->BlockMaterials.writeAtlasExtension(bytes,id->new float[]{0,0,Float.NaN,1}));
        assertThrows(IllegalArgumentException.class,()->BlockMaterials.writeAtlasExtension(bytes,id->new float[]{0,0,Float.POSITIVE_INFINITY,1}));
        assertThrows(IllegalArgumentException.class,()->BlockMaterials.writeAtlasExtension(bytes,id->new float[]{0,1}));
        assertThrows(IllegalArgumentException.class,()->new BlockMaterials.Surface(.2f,.2f,0,10));
        assertThrows(UnsupportedOperationException.class,()->BlockMaterials.additionalAtlasProfiles().clear());
    }

    @Test void sandAndGravelArePorousWithoutBecomingMirrors() {
        var sand=BlockMaterials.forBlock("minecraft:sand");
        assertEquals(7,sand.surfaceClass());
        assertEquals(0,sand.reflectance());assertEquals(0,sand.sunlightTransmission());
        assertTrue(sand.roughness()>.9f);
        assertEquals(7,BlockMaterials.forBlock("minecraft:gravel").surfaceClass());
    }
    @Test void atlasFrameInitializesMissingEntriesAndUsesStd140Offsets() {
        var bytes=java.nio.ByteBuffer.allocate(BlockMaterials.FRAME_BYTES).order(java.nio.ByteOrder.nativeOrder());
        for(int i=0;i<bytes.capacity();i+=4)bytes.putFloat(i,99);
        BlockMaterials.writeAtlas(bytes,id->id.equals("minecraft:block/glass")?new float[]{.1f,.2f,.3f,.4f}:null);
        assertEquals(1,bytes.getFloat(1024));
        assertEquals(.1f,bytes.getFloat(0));assertEquals(.4f,bytes.getFloat(12));
        assertEquals(BlockMaterials.forBlock("minecraft:glass").roughness(),bytes.getFloat(512));
        assertEquals(.98f,bytes.getFloat(520));assertEquals(5,bytes.getFloat(524));
        assertEquals(0,bytes.getFloat(16));assertEquals(0,bytes.getFloat(1028));
    }
    @Test void glassTransmitsLightWhileTintedGlassAndStoneBlockIt() {
        assertTrue(BlockMaterials.forBlock("minecraft:glass").sunlightTransmission() > .9f);
        assertTrue(BlockMaterials.forBlock("minecraft:red_stained_glass_pane").sunlightTransmission() > 0);
        assertEquals(0, BlockMaterials.forBlock("minecraft:tinted_glass").sunlightTransmission());
        assertEquals(0, BlockMaterials.forBlock("minecraft:stone").sunlightTransmission());
    }
    @Test void ordinaryBlocksIceGlassAndMetalHaveDistinctSurfaceResponses() {
        var stone=BlockMaterials.forBlock("minecraft:stone");
        var glass=BlockMaterials.forBlock("minecraft:glass");
        var ice=BlockMaterials.forBlock("minecraft:ice");
        var metal=BlockMaterials.forBlock("minecraft:iron_block");
        assertTrue(stone.roughness() > ice.roughness());
        assertEquals(0,stone.reflectance());
        assertNotEquals(glass,ice);
        assertTrue(metal.reflectance() > glass.reflectance());
        assertTrue(BlockMaterials.atlasProfiles().size() <= 32);
        assertEquals(stone,BlockMaterials.forBlock("custom:unknown"));
    }
}
