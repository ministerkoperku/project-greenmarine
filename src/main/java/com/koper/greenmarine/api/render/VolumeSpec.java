package com.koper.greenmarine.api.render;

import com.mojang.renderpearl.api.GpuFormat;
import java.util.Objects;

/** A single-mip 3D sampled/storage image, not a layered 2D attachment. Vulkan only. */
public record VolumeSpec(GpuFormat format, int width, int height, int depth) {
    public VolumeSpec {
        Objects.requireNonNull(format);
        if (!format.hasColorAspect() || format.hasDepthAspect()) throw new IllegalArgumentException("Volumes need a colour format");
        if (width < 1 || height < 1 || depth < 1) throw new IllegalArgumentException("Volume dimensions must be positive");
    }
}
