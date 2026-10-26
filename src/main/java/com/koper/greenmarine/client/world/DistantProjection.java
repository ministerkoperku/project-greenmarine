package com.koper.greenmarine.client.world;

/** Depth reconstruction coefficients for the active DH projection. */
final class DistantProjection {
    private DistantProjection() {}

    static float[] clip(float near,float far,String range,String direction) {
        boolean zeroToOne=switch(range) {
            case "ZERO_TO_POS_ONE" -> true;
            case "NEG_ONE_TO_POS_ONE" -> false;
            default -> throw new IllegalArgumentException("Unsupported Distant Horizons depth range: "+range);
        };
        boolean reverse=switch(direction) {
            case "REVERSE_Z" -> true;
            case "FORWARD_Z" -> false;
            default -> throw new IllegalArgumentException("Unsupported Distant Horizons depth direction: "+direction);
        };
        return clip(near,far,zeroToOne,reverse);
    }

    static float[] clip(float near,float far,boolean zeroToOne,boolean reverse) {
        if(!Float.isFinite(near) || !Float.isFinite(far) || near<=0 || far<=near)
            throw new IllegalArgumentException("Invalid Distant Horizons clip planes: "+near+", "+far);
        double a=reverse?far:near,b=reverse?near:far;
        float m22=(float)((zeroToOne?b:b+a)/(a-b));
        float m23=(float)((zeroToOne?b:2*b)*a/(a-b));
        return new float[]{m22,m23,zeroToOne?1:0,reverse?1:0};
    }
}
