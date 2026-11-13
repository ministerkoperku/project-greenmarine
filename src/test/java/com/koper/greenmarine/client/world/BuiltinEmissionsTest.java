package com.koper.greenmarine.client.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuiltinEmissionsTest {
    @Test void lavaSourceAndFlowCarryIntrinsicEmissionWithMatteNonreflectiveResponse() {
        for(String name:new String[]{"lava_still","lava_flow"}) {
            int encoded=BuiltinEmissions.addonPixel("minecraft:block/"+name);
            assertEquals(1,encoded>>>24,"Lava remains an ordinary opaque surface for ray classification");
            assertTrue(((encoded>>>16)&255)>240,"Emission must not turn lava into a mirror");
            assertEquals(0,(encoded>>>8)&255,"No reflected terrain on the lava surface");
            assertEquals(255,encoded&255,"Intrinsic source emission is independent of sampled albedo/lightmap");
        }
    }
    @Test void ordinaryTerrainAndMixedUnlitHousingDoNotBecomeEmissive() {
        for(String name:new String[]{"sand","sandstone_top","grass_block_top","dirt","stone","torch","lantern","lava_unlit"})
            assertEquals(0,BuiltinEmissions.addonPixel("minecraft:block/"+name),name);
        assertEquals(0,BuiltinEmissions.addonPixel("custom:block/lava_still"));
        assertEquals(0,BuiltinEmissions.addonPixel("minecraft:item/lava_bucket"));
    }
}
