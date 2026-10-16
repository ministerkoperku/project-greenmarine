package com.koper.greenmarine;

import com.koper.greenmarine.api.EffectRegistry;
import com.koper.greenmarine.api.LightRegistry;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GreenMarine implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("GreenMarine");
    public static final EffectRegistry EFFECTS = new EffectRegistry();
    public static final LightRegistry LIGHTS = new LightRegistry();
    public static final com.koper.greenmarine.api.pack.PackCatalog PACKS = new com.koper.greenmarine.api.pack.PackCatalog();

    @Override public void onInitialize() {
        LOGGER.info("GreenMarine standalone prototype initialized");
    }
}
