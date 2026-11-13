package com.koper.greenmarine.api.pack;

import java.util.*;

/**
 * Pack-owned GPU resources that live across frames (temporal data, sky LUTs, compute results).
 * Images are storage-capable 2D or 3D textures: compute binds them by their declared dimension
 * by name, scene passes sample them through {@code inputs}. Buffers are {@code buffer} blocks named by
 * block name. Contents are cleared on pack change, reload and (for scaled images) resize.
 * Uniform resources describe immutable std140 layouts with explicit host sources; pass-level
 * bindings select a resource independently of the block's GLSL name.
 */
public record PackResources(Map<String, Image> images, Map<String, Buffer> buffers, Map<String,PackUniformBlock> uniforms) {
    public PackResources(Map<String,Image> images,Map<String,Buffer> buffers) { this(images,buffers,Map.of()); }
    public static final PackResources NONE = new PackResources(Map.of(), Map.of());
    public enum Format {
        RGBA8, RGBA16F, RGBA32F, R32F, RG32F, R16F, RG16F, R32UI, RG32UI, RGBA32UI, R11G11B10F;
        public String glsl() { return switch (this) { case RGBA8 -> "rgba8"; case RGBA16F -> "rgba16f"; case RGBA32F -> "rgba32f";
            case R32F -> "r32f"; case RG32F -> "rg32f"; case R16F -> "r16f"; case RG16F -> "rg16f"; case R32UI -> "r32ui";
            case RG32UI -> "rg32ui"; case RGBA32UI -> "rgba32ui"; case R11G11B10F -> "r11f_g11f_b10f"; }; }
    }
    /** Either fixed {@code width x height} or {@code scale} of the scene (0.25..1). */
    public record Image(Format format, int width, int height, float scale, int depth, int dimensions, boolean clearEachFrame) {
        public Image(Format format,int width,int height,float scale,int depth,int dimensions) {this(format,width,height,scale,depth,dimensions,false);}
        /** Retains the original 2D resource constructor. A positive depth explicitly selects 3D. */
        public Image(Format format, int width, int height, float scale) { this(format, width, height, scale, 0, 2); }
        public Image(Format format, int width, int height, float scale, int depth) { this(format, width, height, scale, depth, depth > 0 ? 3 : 2); }
        public Image {
            Objects.requireNonNull(format);
            boolean fixed = width > 0 && height > 0 && width <= 8192 && height <= 8192 && scale == 0;
            boolean relative = width == 0 && height == 0 && (scale == 1 || scale == 0.75f || scale == 0.5f || scale == 0.25f);
            if (!fixed && !relative) throw new IllegalArgumentException("Images need size [w,h] up to 8192 or scale 1/0.75/0.5/0.25");
            if (depth < 0 || depth > 2048 || (depth > 0 && !fixed))
                throw new IllegalArgumentException("Volume images need fixed size [w,h,d] with depth up to 2048");
            if (dimensions < 1 || dimensions > 3 || (dimensions == 1 && (!fixed || height != 1 || depth != 0))
                || (dimensions == 2 && depth != 0) || (dimensions == 3 && depth == 0))
                throw new IllegalArgumentException("Image dimensions do not match the declared extent");
        }
        public int width(int sceneWidth) { return scale == 0 ? width : Math.max(1, (int) Math.ceil(sceneWidth * scale)); }
        public int height(int sceneHeight) { return scale == 0 ? height : Math.max(1, (int) Math.ceil(sceneHeight * scale)); }
        public boolean volume() { return dimensions == 3; }
        public int layers() { return Math.max(1, depth); }
    }
    public record Buffer(int size) {
        public Buffer { if (size < 4 || size > 64 << 20 || size % 4 != 0) throw new IllegalArgumentException("Buffer size must be a multiple of 4 up to 64 MiB"); }
    }
    public PackResources {
        images = Collections.unmodifiableMap(new LinkedHashMap<>(images));
        buffers = Collections.unmodifiableMap(new LinkedHashMap<>(buffers));
        uniforms = Collections.unmodifiableMap(new LinkedHashMap<>(uniforms));
        if (images.size() > 16 || buffers.size() > 16) throw new IllegalArgumentException("At most 16 images and 16 buffers per pack");
        if (uniforms.size() > 64) throw new IllegalArgumentException("At most 64 uniform blocks per pack");
        long bytes = 0;
        for (var b : buffers.values()) bytes += b.size();
        if (bytes > 128L << 20) throw new IllegalArgumentException("Pack buffers exceed 128 MiB in total");
        for (var name : images.keySet()) PackPass.requireBindingName(name);
        for (var name : buffers.keySet()) { PackPass.requireBindingName(name); if (images.containsKey(name)) throw new IllegalArgumentException("Resource declared twice: " + name); }
        for (var entry : uniforms.entrySet()) {
            PackPass.requireBindingName(entry.getKey());Objects.requireNonNull(entry.getValue());
            if(images.containsKey(entry.getKey()) || buffers.containsKey(entry.getKey()))throw new IllegalArgumentException("Resource declared twice: "+entry.getKey());
        }
    }
    public boolean isEmpty() { return images.isEmpty() && buffers.isEmpty() && uniforms.isEmpty(); }
}
