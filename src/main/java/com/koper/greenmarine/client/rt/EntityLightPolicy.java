package com.koper.greenmarine.client.rt;

/** Backend-independent dynamic-light contribution to Minecraft's packed entity lightmap coordinates. */
final class EntityLightPolicy {
    private EntityLightPolicy() {}

    static int apply(int vanilla,boolean enabled,double distanceSquared,int emission) {
        if(!enabled || !Double.isFinite(distanceSquared) || distanceSquared<0 || emission<=0)return vanilla;
        double light=Math.min(15,emission)-Math.sqrt(distanceSquared);
        int block=(int)Math.max(0,Math.min(240,Math.round(light*16)));
        // The low byte holds smooth block-light coordinates; the high half contains skylight.
        return block>(vanilla&255)?(vanilla&~255)|block:vanilla;
    }
}
