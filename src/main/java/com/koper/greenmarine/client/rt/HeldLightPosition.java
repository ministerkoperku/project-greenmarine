package com.koper.greenmarine.client.rt;

/** Interpolated hand-light offset relative to the entity eye. */
final class HeldLightPosition {
    record Offset(double x,double y,double z) {}
    private HeldLightPosition() {}
    static Offset offset(double lookX,double lookY,double lookZ,double yawDegrees,boolean right,double width) {
        // Pitch must not collapse the horizontal hand frame into the torso.
        // Keep the emitter forward of the body/arm even when looking straight down.
        double yaw=Math.toRadians(yawDegrees),forwardX=-Math.sin(yaw),forwardZ=Math.cos(yaw);
        double reach=Math.max(.35,width*.5+.05),side=right?1:-1;
        return new Offset(forwardX*reach-forwardZ*reach*side,lookY*.35-.45,
            forwardZ*reach+forwardX*reach*side);
    }
}
