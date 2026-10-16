package com.koper.greenmarine.compat.fullpack;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.EffectDefinition;
import com.koper.koper_lib.api.FullpackAddons;
import net.fabricmc.loader.api.FabricLoader;
import java.util.List;

public final class GreenFullpackAddon implements Runnable {
    @Override public void run() {
        if (!FabricLoader.getInstance().isModLoaded("koperlib_fullpack")) return;
        FullpackAddons.contentTypes("greenmarine", List.of("greenmarine_effect"), (json, source) -> {
            try { GreenMarine.EFFECTS.stage(EffectDefinition.fromJson(json)); }
            catch (RuntimeException error) {
                FullpackAddons.markReloadFailure();
                throw new IllegalArgumentException("Invalid GreenMarine declaration at " + source, error);
            }
        });
        FullpackAddons.reloadHook("greenmarine", GreenMarine.EFFECTS::beginReload);
        FullpackAddons.afterReloadHook("greenmarine", () -> {
            GreenMarine.EFFECTS.finishReload(FullpackAddons.reloadHealthy());
            GreenMarine.LOGGER.info("Fullpack GreenMarine declarations: {}", GreenMarine.EFFECTS.size());
        });
        GreenMarine.LOGGER.info("Optional Fullpack declaration adapter registered");
    }
}
