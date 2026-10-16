package com.koper.greenmarine.client;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.flag.FeatureFlagSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class GreenResources {
    private GreenResources() {}

    public static Pack createPack(List<Path> roots) {
        Path resources = roots.stream().filter(root -> Files.isDirectory(root.resolve("assets/greenmarine")))
            .findFirst().orElseThrow(() -> new IllegalStateException("GreenMarine assets are missing"));
        var location = new PackLocationInfo("greenmarine:resources", Component.literal("GreenMarine"),
            PackSource.BUILT_IN, Optional.empty());
        var metadata = new Pack.Metadata(Component.literal("GreenMarine shader and effect resources"),
            PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of());
        return new Pack(location, new PathPackResources.PathResourcesSupplier(resources), metadata,
            new PackSelectionConfig(true, Pack.Position.BOTTOM, true));
    }
}
