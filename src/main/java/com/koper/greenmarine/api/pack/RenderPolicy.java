package com.koper.greenmarine.api.pack;

import java.util.Optional;

public final class RenderPolicy {
    public record RaySelection(RayMode effective, String reason) {}
    public record ResolvedPass(PackPass definition, String fragment, boolean fallback) {}
    private RenderPolicy() {}
    public static RaySelection rays(RayMode requested, Quality quality, RenderCapabilities caps, ShaderPack pack) {
        boolean usesHardware=pack!=null && (pack.passes().stream().anyMatch(pass->pass.requires()==PackPass.Requirement.HARDWARE_RT)
            || pack.programs().stream().anyMatch(PackProgram::worldRays));
        if(usesHardware) {
            var selected=rays(requested,quality,caps);
            boolean worldOnly=!pack.programs().isEmpty() && pack.programs().stream().noneMatch(PackProgram::voxelShadows)
                && pack.passes().stream().noneMatch(pass->pass.requires()==PackPass.Requirement.VOXEL_RT);
            if(worldOnly && selected.effective()==RayMode.VOXEL)
                return new RaySelection(RayMode.OFF,"Hardware RT unavailable for this pack; world programs use raster lighting");
            return selected;
        }
        var selection=rays(requested,quality,new RenderCapabilities(caps.backend(),caps.nativeProcessing(),false,caps.voxelRayTracing(),caps.spirv()));
        return requested==RayMode.HARDWARE && selection.effective()==RayMode.VOXEL
            ?new RaySelection(RayMode.VOXEL,"Selected pack uses portable voxel rays; no hardware ray passes declared"):selection;
    }

    public static RaySelection rays(RayMode requested, Quality quality, RenderCapabilities caps) {
        if (requested == RayMode.OFF) return new RaySelection(RayMode.OFF, "Disabled by user");
        if (requested == RayMode.AUTO && quality.ordinal() < Quality.HIGH.ordinal()) {
            return new RaySelection(RayMode.OFF, "Portable quality profile");
        }
        if (requested != RayMode.VOXEL && caps.hardwareRayTracing()) return new RaySelection(RayMode.HARDWARE, "Hardware path available");
        if (caps.voxelRayTracing()) return new RaySelection(RayMode.VOXEL,
            requested == RayMode.HARDWARE ? "Hardware path unavailable; using voxel rays" : "Using portable voxel rays");
        return new RaySelection(RayMode.OFF, "No compatible ray-lighting path; using raster lighting");
    }

    public static Optional<ResolvedPass> pass(PackPass pass, Quality quality, RenderCapabilities caps, RayMode rays) {
        if (quality.ordinal() < pass.minQuality().ordinal()) return Optional.empty();
        boolean supported = switch (pass.requires()) {
            case NONE -> true;
            case VULKAN -> caps.vulkan();
            case HARDWARE_RT -> caps.hardwareRayTracing() && rays == RayMode.HARDWARE;
            case VOXEL_RT -> caps.voxelRayTracing() && rays == RayMode.VOXEL;
        };
        if (!supported && pass.fallback() == null) return Optional.empty();
        return Optional.of(new ResolvedPass(pass, supported ? pass.fragment() : pass.fallback(), !supported));
    }
}
