package com.koper.greenmarine.client.world;

import java.util.Objects;

/** Per-pass binding identity, invalidated by frame/resource changes and capture variants. */
public final class WorldBindingCache {
    private Object pipeline;
    private long epoch;
    private int flags;
    public boolean matches(Object pipeline,long epoch,int flags) {
        return pipeline!=null && this.pipeline==pipeline && this.epoch==epoch && this.flags==flags;
    }
    public void commit(Object pipeline,long epoch,int flags) {
        this.pipeline=Objects.requireNonNull(pipeline);this.epoch=epoch;this.flags=flags;
    }
    public void invalidate() {pipeline=null;}
}
