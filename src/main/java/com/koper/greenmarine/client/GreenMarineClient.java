package com.koper.greenmarine.client;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.nativebridge.GreenNative;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import com.mojang.blaze3d.platform.InputConstants;
import com.koper.greenmarine.api.Light;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class GreenMarineClient implements ClientModInitializer {
    private static GreenConfig config = GreenConfig.defaults();
    private static GreenNative engine;
    private static boolean enabled, toggleHeld, placeHeld, settingsHeld;
    private static long demoLight;
    private static Vec3 lightPosition;
    private static ClientLevel lastLevel;
    private static long ticks;

    @Override public void onInitializeClient() {
        engine = GreenNative.openBundled();
        reload();
        GreenMarine.LOGGER.info("{}; F7 places a world light, F8 toggles effects; Vulkan is the primary backend", engine.status());
    }

    public static GreenConfig config() { return config; }
    public static GreenNative engine() { return engine; }
    public static boolean enabled() { return enabled; }
    public static float seconds(float partial) { return (ticks + partial) / 20f; }

    public static void tick(Minecraft client) {
        if (client.level != lastLevel) {
            com.koper.greenmarine.api.render.MarineRenderer.reset();
            GreenMarine.LIGHTS.clear();
            GreenPostEffect.close();
            lastLevel = client.level;
            ticks = 0;
            demoLight = 0;
            lightPosition = null;
            ShaderRuntime.reset();
        }
        if (client.level != null && client.player != null) {
            if (lightPosition == null && ticks >= 20) placeLight(client);
            if (lightPosition != null) updateLight();
        }
        if (client.level != null && !client.isPaused()) ticks++;
        if (!client.isPaused()) ShaderRuntime.tick(client);
        if (!client.isPaused()) com.koper.greenmarine.client.rt.RayWorld.tick(client);
        if (!client.isPaused()) VoxelWorld.tick(client);
        boolean pressed = InputConstants.isKeyDown(InputConstants.KEY_F8);
        if (pressed && !toggleHeld && client.gui.screen() == null) {
            toggleEffect();
        }
        toggleHeld = pressed;
        boolean place = InputConstants.isKeyDown(InputConstants.KEY_F7);
        if (place && !placeHeld && client.gui.screen() == null) placeLight(client);
        placeHeld = place;
        boolean settings = InputConstants.isKeyDown(InputConstants.KEY_F6);
        if (settings && !settingsHeld && client.gui.screen() == null) client.gui.setScreen(new ShaderScreen());
        settingsHeld = settings;
    }

    public static void toggleEffect() {
        enabled = !enabled;
        GreenMarine.LOGGER.info("GreenMarine world effects {}", enabled ? "enabled" : "disabled");
    }

    public static void placeLight(Minecraft client) {
        if (client.player == null) return;
        var hit = client.player.pick(6, 1, false);
        if (hit instanceof BlockHitResult block && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            var face = block.getDirection();
            lightPosition = block.getLocation().add(face.getStepX() * 0.6, face.getStepY() * 0.6, face.getStepZ() * 0.6);
        } else {
            lightPosition = client.player.getEyePosition().add(client.player.getLookAngle().scale(3));
        }
        updateLight();
        GreenMarine.LOGGER.info("World lamp placed at {}, handle={}", lightPosition, demoLight);
    }

    private static void updateLight() {
        var definition = ResourceEffects.selected(config.effectId());
        int color = definition == null ? config.color() : definition.color();
        var light = new Light(lightPosition.x, lightPosition.y, lightPosition.z,
            (color >> 16 & 255) / 255f, (color >> 8 & 255) / 255f, (color & 255) / 255f,
            definition == null ? config.radius() : definition.radius(), definition == null ? config.intensity() : definition.intensity());
        if (demoLight == 0 || !GreenMarine.LIGHTS.update(demoLight, light)) demoLight = GreenMarine.LIGHTS.create(light);
    }

    public static void reload() {
        com.koper.greenmarine.api.render.MarineRenderer.reset();
        config = GreenConfig.load(FabricLoader.getInstance().getConfigDir().resolve("greenmarine.json"), config);
        enabled = config.enabled();
        GreenPostEffect.close();
        ShaderRuntime.reloadSettings();
    }

    public static void close() {
        com.koper.greenmarine.api.render.MarineRenderer.reset();
        ShaderRuntime.reset();
        GreenPostEffect.close();
        if (engine != null) { engine.close(); engine = null; }
    }
}
