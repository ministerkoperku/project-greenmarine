package com.koper.greenmarine.api.pack;

/** Describes available engine paths, not merely physical GPU support. */
public record RenderCapabilities(String backend, boolean nativeProcessing,
                                 boolean hardwareRayTracing, boolean voxelRayTracing, boolean spirv) {
    public boolean vulkan() { return backend.equalsIgnoreCase("Vulkan"); }
}
