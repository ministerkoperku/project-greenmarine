package com.koper.greenmarine.client.world;

/** Couples LOD relighting admission to the lifetime of DH's temporary setting overrides. */
final class DistantRenderState {
    private boolean overriding,dirty;

    @FunctionalInterface
    interface Override {
        void set(boolean active) throws ReflectiveOperationException;
    }

    boolean update(boolean requested,boolean rendering,Override settings) throws ReflectiveOperationException {
        boolean active=requested && rendering;
        if(active!=overriding || dirty) {
            // A failed multi-setting write still owns any settings already changed.
            dirty=true;
            settings.set(active);
            overriding=active;
            dirty=false;
        }
        return active;
    }
}
