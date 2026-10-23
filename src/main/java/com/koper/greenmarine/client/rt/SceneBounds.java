package com.koper.greenmarine.client.rt;

/** Loaded-model scene extent and stable world grid anchor. */
public record SceneBounds(int x, int y, int z) {
    public static final int WIDTH=96, HEIGHT=64;
    public static SceneBounds around(int x,int y,int z) {
        // Two sections below and above the camera's: with one below, rising a few blocks dropped the
        // ground out of the traced scene (and its 8-block fade) and every shadow vanished.
        return new SceneBounds(Math.floorDiv(x,16)*16-32,Math.floorDiv(y,16)*16-32,Math.floorDiv(z,16)*16-32);
    }
}
