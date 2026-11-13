package com.koper.greenmarine.api.render;

/** Ordered graphics execution stages, independent of the host's two-stage enum. */
public enum GraphicsStage {
    VERTEX(0,1), TESS_CONTROL(1,2), TESS_EVALUATION(2,4), GEOMETRY(3,8), FRAGMENT(4,16);
    private final int executionModel,vulkanBit;
    GraphicsStage(int executionModel,int vulkanBit) {this.executionModel=executionModel;this.vulkanBit=vulkanBit;}
    public int executionModel() {return executionModel;}
    public int vulkanBit() {return vulkanBit;}
    public boolean intermediate() {return this!=VERTEX && this!=FRAGMENT;}
}
