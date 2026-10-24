package com.koper.greenmarine.development;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import java.nio.charset.StandardCharsets;

final class ShaderIncludes {
    private ShaderIncludes() {}
    static String read(ShaderWorkspace workspace, String path, String name) {
        try {
            String normalized = path.replace('\\', '/'); int assets = normalized.indexOf("/assets/");
            String namespace = assets < 0 ? "minecraft" : normalized.substring(assets + 8).split("/", 2)[0];
            Identifier id = name.indexOf(':') < 0 ? Identifier.fromNamespaceAndPath(namespace, name) : Identifier.parse(name);
            if (assets >= 0) try { return workspace.read(normalized.substring(0, assets) + "/assets/" + id.getNamespace() + "/shaders/include/" + id.getPath()); }
            catch (Exception ignored) {}
            var resource = Minecraft.getInstance().getResourceManager().getResource(Identifier.fromNamespaceAndPath(id.getNamespace(), "shaders/include/" + id.getPath()));
            if (resource.isEmpty()) return null;
            try (var stream = resource.get().open()) {
                byte[] bytes = stream.readNBytes(262145); return bytes.length > 262144 ? null : new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) { return null; }
    }
}
