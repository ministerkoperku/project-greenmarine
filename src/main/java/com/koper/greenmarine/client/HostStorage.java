package com.koper.greenmarine.client;

import java.util.function.Supplier;

/**
 * Renderpearl has no storage usage bit, so GreenMarine adds VK storage usage only to resources
 * created inside {@link #create}. Everything else keeps its host flags (Adreno framebuffer
 * compression stays on for ordinary attachments).
 */
public final class HostStorage {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private HostStorage() {}
    public static boolean active() { return ACTIVE.get(); }
    public static <T> T create(Supplier<T> factory) {
        boolean previous = ACTIVE.get();
        ACTIVE.set(true);
        try { return factory.get(); } finally { ACTIVE.set(previous); }
    }
}
