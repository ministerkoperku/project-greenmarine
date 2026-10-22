package com.koper.greenmarine.client;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.PackCatalog;
import com.koper.greenmarine.api.pack.PackSource;
import com.koper.greenmarine.api.pack.ExternalPacks;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;

public final class ResourcePacks {
    public static final PackCatalog CATALOG = GreenMarine.PACKS;
    private ResourcePacks() {}
    public static void reload(ResourceManager resources) {
        var manifests = new ArrayList<PackSource>();
        try {
            for (var entry : resources.listResources("greenmarine/packs", id -> id.getPath().endsWith(".json")).entrySet()) {
                manifests.add(new PackSource(entry.getKey().toString(), read(resources, entry.getKey().toString()),
                    id -> read(resources, id), id -> readBinary(resources, id)));
            }
            manifests.addAll(ExternalPacks.scan(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                .resolve("shaderpacks/greenmarine"), id -> read(resources, id), id -> readBinary(resources, id)));
            boolean healthy = CATALOG.reload(manifests);
            GreenMarine.LOGGER.info("Shaderpack catalog: packs={}, generation={}, reloadHealthy={}, reason={}",
                CATALOG.packs().size(), CATALOG.generation(), healthy, CATALOG.error());
        } catch (RuntimeException error) { GreenMarine.LOGGER.warn("Keeping shaderpack catalog after read failure: {}", error.getMessage()); }
        ShaderRuntime.reset();
    }
    private static String read(ResourceManager resources, String id) {
        var resource = resources.getResource(Identifier.parse(id));
        if (resource.isEmpty()) return null;
        try (var reader = resource.get().openAsReader()) {
            StringBuilder text = new StringBuilder(); char[] chunk = new char[4096]; int count;
            while ((count = reader.read(chunk)) >= 0) {
                if (text.length() + count > 262_144) throw new IllegalArgumentException("Shaderpack resource exceeds 256 KiB: " + id);
                text.append(chunk, 0, count);
            }
            return text.toString();
        } catch (IOException error) { throw new UncheckedIOException(error); }
    }
    private static byte[] readBinary(ResourceManager resources,String id) {
        var resource=resources.getResource(Identifier.parse(id)); if(resource.isEmpty()) return null;
        try(var input=resource.get().open()) { return input.readNBytes(com.koper.greenmarine.api.pack.ShaderBinary.MAX_BYTES+1); }
        catch(IOException exception) { throw new UncheckedIOException(exception); }
    }
}
