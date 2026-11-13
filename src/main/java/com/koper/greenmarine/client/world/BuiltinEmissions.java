package com.koper.greenmarine.client.world;

import java.util.Set;

/** Intrinsic emission for vanilla sprites with no authored material data. */
public final class BuiltinEmissions {
    // These sprites are luminous across their opaque texels. Mixed lamp/torch sprites
    // need authored per-texel masks, rather than making their wood/metal housing glow.
    private static final Set<String> SOURCES=Set.of(
        "lava_still","lava_flow","fire_0","fire_1","soul_fire_0","soul_fire_1",
        "glowstone","sea_lantern","shroomlight");
    private BuiltinEmissions() {}

    /** Existing addon RGBA payload: roughness, reflection, emission, class (ARGB on CPU). */
    public static int addonPixel(String sprite) {
        String prefix="minecraft:block/";
        if(!sprite.startsWith(prefix) || !SOURCES.contains(sprite.substring(prefix.length())))return 0;
        return (1<<24)|(Math.round(.96f*255)<<16)|255;
    }
}
