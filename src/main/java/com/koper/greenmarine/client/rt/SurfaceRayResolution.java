package com.koper.greenmarine.client.rt;

import com.koper.greenmarine.api.pack.Quality;
import java.util.Objects;

/** Solstice dispatch budget; capture remains at full resolution for edge rejection. */
final class SurfaceRayResolution {
    record Size(int width,int height) {}
    private SurfaceRayResolution() {}
    static Size forPack(String id,Quality quality,int width,int height) {
        return forViewport("greenmarine:solstice_rt".equals(id)?quality:Quality.HIGH,width,height);
    }
    static Size forViewport(Quality quality,int width,int height) {
        int divisor=switch(Objects.requireNonNull(quality)) {case LOW,MEDIUM->4;case HIGH,ULTRA->2;};
        // Ceil without adding divisor-1 to a potentially large viewport integer.
        return new Size((Math.max(1,width)-1)/divisor+1,(Math.max(1,height)-1)/divisor+1);
    }
}
