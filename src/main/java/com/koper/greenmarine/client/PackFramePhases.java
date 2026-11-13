package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.PackPass.Phase;
import java.util.EnumSet;
import java.util.Objects;

/** Render-thread pack/world/viewport lifetime and exactly-once host boundary admission. */
final class PackFramePhases {
    record Start(boolean reset,boolean frame) {}
    private Object pack,world;
    private int width,height;
    private long epoch;
    private boolean active,setup;
    private final EnumSet<Phase> entered=EnumSet.noneOf(Phase.class);
    private Phase latest;

    Start begin(Object pack,Object world,int width,int height,long epoch) {
        Objects.requireNonNull(pack);Objects.requireNonNull(world);
        if(width<=0 || height<=0)throw new IllegalArgumentException("Invalid pack frame size");
        boolean reset=!active || this.pack!=pack || this.world!=world || this.width!=width || this.height!=height;
        if(!reset && this.epoch==epoch)return new Start(false,false);
        if(reset)setup=false;
        this.pack=pack;this.world=world;this.width=width;this.height=height;this.epoch=epoch;active=true;
        entered.clear();latest=null;
        return new Start(reset,true);
    }
    boolean enter(Phase phase) {
        if(!active)throw new IllegalStateException("Pack frame was not prepared before its host boundary");
        if(entered.contains(phase) || phase==Phase.SETUP && setup)return false;
        if(latest!=null && phase.ordinal()<latest.ordinal())throw new IllegalStateException("Late pack phase: "+phase+" after "+latest);
        entered.add(phase);latest=phase;if(phase==Phase.SETUP)setup=true;
        return true;
    }
    void reset() {active=setup=false;pack=world=null;entered.clear();latest=null;}
}
