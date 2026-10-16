package com.koper.greenmarine.api.render;

/** Establishes only the host thread identity, without a GPU device. */
final class RenderTestHost {
    static void initialize() {
        if (!com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) com.mojang.blaze3d.systems.RenderSystem.initRenderThread();
    }
}
