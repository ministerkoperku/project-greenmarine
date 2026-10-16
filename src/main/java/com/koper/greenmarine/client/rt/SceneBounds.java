package com.koper.greenmarine.client.rt;

/** Loaded-model scene extent and stable world grid anchor. */
public record SceneBounds(int x, int y, int z) {
    public static final int WIDTH=96, HEIGHT=64;
    public static SceneBounds around(int x,int y,int z) {
        return new SceneBounds(Math.floorDiv(x,16)*16-32,Math.floorDiv(y,16)*16-16,Math.floorDiv(z,16)*16-32);
    }
}
