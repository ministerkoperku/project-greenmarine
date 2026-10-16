package com.koper.greenmarine.api.render;

import com.mojang.renderpearl.api.GpuFormat;
import java.util.Objects;

/** A single-mip 2D render attachment, sampled between graph passes. */
public record TextureSpec(GpuFormat format, int fixedWidth, int fixedHeight, double scale) {
    public TextureSpec {
        Objects.requireNonNull(format);
        if (!format.hasColorAspect() && !format.hasDepthAspect()) throw new IllegalArgumentException("Unsupported attachment aspect");
        boolean fixed = fixedWidth > 0 && fixedHeight > 0 && scale == 0;
        boolean relative = fixedWidth == 0 && fixedHeight == 0 && Double.isFinite(scale) && scale > 0 && scale <= 1;
        if (!fixed && !relative) throw new IllegalArgumentException("Use positive fixed dimensions or a scale in (0,1]");
    }
    public static TextureSpec scaled(GpuFormat format, double scale) { return new TextureSpec(format, 0, 0, scale); }
    public static TextureSpec fixed(GpuFormat format, int width, int height) { return new TextureSpec(format, width, height, 0); }
    public int width(int frameWidth) { return resolve(fixedWidth, frameWidth); }
    public int height(int frameHeight) { return resolve(fixedHeight, frameHeight); }
    private int resolve(int fixed, int frame) {
        if (frame <= 0) throw new IllegalArgumentException("Frame dimensions must be positive");
        return fixed > 0 ? fixed : Math.max(1, (int)Math.ceil(frame * scale));
    }
}
