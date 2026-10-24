package com.koper.greenmarine.api.pack;

/** Describes available engine paths, not merely physical GPU support. */
public record RenderCapabilities(String backend, boolean nativeProcessing,
                                 boolean hardwareRayTracing, boolean voxelRayTracing, boolean spirv, boolean compute) {
    public RenderCapabilities(String backend, boolean nativeProcessing, boolean hardwareRayTracing, boolean voxelRayTracing, boolean spirv) {
        this(backend, nativeProcessing, hardwareRayTracing, voxelRayTracing, spirv, false);
    }
    public boolean vulkan() { return backend.equalsIgnoreCase("Vulkan"); }
}
