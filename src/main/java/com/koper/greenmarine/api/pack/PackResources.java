package com.koper.greenmarine.api.pack;

import java.util.*;

/**
 * Pack-owned GPU resources that live across frames (temporal data, sky LUTs, compute results).
 * Images are storage-capable 2D textures: compute binds them as {@code image2D} or {@code sampler2D}
 * by name, scene passes sample them through {@code inputs}. Buffers are {@code buffer} blocks named by
 * block name. Contents are cleared on pack change, reload and (for scaled images) resize.
 */
public record PackResources(Map<String, Image> images, Map<String, Buffer> buffers) {
    public static final PackResources NONE = new PackResources(Map.of(), Map.of());
    public enum Format {
        RGBA8, RGBA16F, RGBA32F, R32F, RG32F, R16F, RG16F, R32UI, RG32UI, RGBA32UI, R11G11B10F;
        public String glsl() { return switch (this) { case RGBA8 -> "rgba8"; case RGBA16F -> "rgba16f"; case RGBA32F -> "rgba32f";
            case R32F -> "r32f"; case RG32F -> "rg32f"; case R16F -> "r16f"; case RG16F -> "rg16f"; case R32UI -> "r32ui";
            case RG32UI -> "rg32ui"; case RGBA32UI -> "rgba32ui"; case R11G11B10F -> "r11f_g11f_b10f"; }; }
    }
    /** Either fixed {@code width x height} or {@code scale} of the scene (0.25..1). */
    public record Image(Format format, int width, int height, float scale) {
        public Image {
            Objects.requireNonNull(format);
            boolean fixed = width > 0 && height > 0 && width <= 8192 && height <= 8192 && scale == 0;
            boolean relative = width == 0 && height == 0 && (scale == 1 || scale == 0.75f || scale == 0.5f || scale == 0.25f);
            if (!fixed && !relative) throw new IllegalArgumentException("Images need size [w,h] up to 8192 or scale 1/0.75/0.5/0.25");
        }
        public int width(int sceneWidth) { return scale == 0 ? width : Math.max(1, (int) Math.ceil(sceneWidth * scale)); }
        public int height(int sceneHeight) { return scale == 0 ? height : Math.max(1, (int) Math.ceil(sceneHeight * scale)); }
    }
    public record Buffer(int size) {
        public Buffer { if (size < 4 || size > 64 << 20 || size % 4 != 0) throw new IllegalArgumentException("Buffer size must be a multiple of 4 up to 64 MiB"); }
    }
    public PackResources {
        images = Collections.unmodifiableMap(new LinkedHashMap<>(images));
        buffers = Collections.unmodifiableMap(new LinkedHashMap<>(buffers));
        if (images.size() > 16 || buffers.size() > 16) throw new IllegalArgumentException("At most 16 images and 16 buffers per pack");
        long bytes = 0;
        for (var b : buffers.values()) bytes += b.size();
        if (bytes > 128L << 20) throw new IllegalArgumentException("Pack buffers exceed 128 MiB in total");
        for (var name : images.keySet()) PackPass.requireBindingName(name);
        for (var name : buffers.keySet()) { PackPass.requireBindingName(name); if (images.containsKey(name)) throw new IllegalArgumentException("Resource declared twice: " + name); }
    }
    public boolean isEmpty() { return images.isEmpty() && buffers.isEmpty(); }
}
