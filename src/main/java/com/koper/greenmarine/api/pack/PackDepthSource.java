package com.koper.greenmarine.api.pack;

/** Renderer-owned, full-resolution forward window-depth snapshots. Never writable pack targets. */
public enum PackDepthSource {
    WORLD("world_depth"), OPAQUE("opaque_depth"), OPAQUE_NO_HAND("opaque_no_hand_depth");
    private final String source;
    PackDepthSource(String source) {this.source=source;}
    public String source() {return source;}
    public static PackDepthSource named(String name) {
        for(var value:values())if(value.source.equals(name))return value;
        return null;
    }
}
