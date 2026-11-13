package com.koper.greenmarine.client.rt;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import java.util.List;

/** One interpolated source snapshot shared by terrain rays and entity extraction in each host frame. */
public final class DynamicLights {
    private static ClientLevel level;
    private static List<SurfaceLights.Source> sources;

    private DynamicLights() {}

    public static void beginFrame() {
        level=null;
        sources=null;
    }

    static List<SurfaceLights.Source> collect(Minecraft client) {
        if(client.level==null || client.player==null)return List.of();
        if(sources==null || level!=client.level) {
            level=client.level;
            sources=List.copyOf(SurfaceLights.dynamicSources(client));
        }
        return sources;
    }
}
