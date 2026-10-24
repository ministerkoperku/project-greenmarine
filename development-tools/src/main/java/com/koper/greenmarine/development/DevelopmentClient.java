package com.koper.greenmarine.development;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.koper.greenmarine.client.ShaderRuntime;
import com.koper.greenmarine.client.PackRenderer;

/** All authoring entrypoints live in the optional addon, outside the ordinary renderer. */
public final class DevelopmentClient implements ClientModInitializer {
    private static boolean held;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("greenmarine-development");
    private static DevelopmentPreferences preferences = DevelopmentPreferences.defaults();
    private static AutoReloadWatcher watcher;
    private static boolean polling, reloading;
    private static long nextPoll, nextDebug;
    private static String reloadStatus = "Auto reload off", lastDebug = "";
    @Override public void onInitializeClient() {
        preferences = DevelopmentPreferences.load(gameRoot().resolve("config/greenmarine-development.json"));
        resetWatcher();
        LOGGER.info("Development Tools installed; F9 opens the workbench");
    }
    public static Path gameRoot() { return FabricLoader.getInstance().getGameDir(); }
    public static ShaderWorkspace workspace() { return new ShaderWorkspace(gameRoot().resolve("shaderpacks/greenmarine")); }
    static DevelopmentPreferences preferences() { return preferences; }
    static void preferences(DevelopmentPreferences next) throws java.io.IOException {
        next.save(gameRoot().resolve("config/greenmarine-development.json")); preferences = next; resetWatcher();
    }
    public static String reloadStatus() { return reloadStatus; }
    static void resetWatcher() {
        watcher = new AutoReloadWatcher(List.of(gameRoot().resolve("shaderpacks/greenmarine"), gameRoot().resolve("resourcepacks/greenmarine-development-materials")));
        reloadStatus = preferences.autoReload() ? "Watching editable sources (750ms poll, debounced)" : "Auto reload off";
    }
    public static void tick(Minecraft client) {
        boolean pressed = InputConstants.isKeyDown(InputConstants.KEY_F9);
        if (pressed && !held && client.gui.screen() == null) client.gui.setScreen(new WorkbenchScreen(null));
        held = pressed;
        if (preferences.verboseDebug() && System.nanoTime() >= nextDebug) {
            nextDebug = System.nanoTime() + 1_000_000_000L;
            String state = ShaderRuntime.status() + " | " + ShaderRuntime.rays() + " | material generation " + com.koper.greenmarine.api.material.MaterialAddons.generation();
            if (!state.equals(lastDebug)) {
                lastDebug = state; LOGGER.info("[development] {} | CPU {} | GPU {}", state, PackRenderer.cpuTiming(), PackRenderer.gpuTiming());
                var active = ShaderRuntime.active();
                if (active != null) for (var pass : active.definition().passes()) LOGGER.info("[development] declared pass {} scale={} format={} inputs={}", pass.name(), pass.scale(), pass.format(), pass.inputs());
            }
        }
        if (preferences.autoReload() && !polling && !reloading && System.nanoTime() >= nextPoll) {
            nextPoll = System.nanoTime() + 750_000_000L; polling = true; var currentWatcher = watcher;
            CompletableFuture.supplyAsync(() -> {
                try {
                    var result = currentWatcher.poll();
                    if (!result.changed()) return result;
                    String error = validateSources(result.changedFiles());
                    return error == null ? result : new AutoReloadWatcher.PollResult(false, List.of(), error);
                } catch (Exception e) { return new AutoReloadWatcher.PollResult(false, List.of(), "Watch error: " + e.getMessage()); }
            }).thenAccept(result -> client.execute(() -> {
                polling = false;
                if (!preferences.autoReload() || currentWatcher != watcher) return;
                if (!result.diagnostic().isBlank() && !result.diagnostic().equals("Auto reload: no changes")) reloadStatus = result.diagnostic();
                if (result.changed()) {
                    reloadStatus = "Validated changes; reloading resources"; reloading = true;
                    client.reloadResourcePacks().whenComplete((v, error) -> client.execute(() -> {
                        reloading = false; reloadStatus = error == null ? "Auto reload complete" : "Reload failed: " + error.getMessage();
                        if (preferences.verboseDebug() || error != null) LOGGER.info("[development] {}", reloadStatus);
                    }));
                } else if (preferences.verboseDebug() && !result.diagnostic().isBlank()) LOGGER.info("[development] {}", result.diagnostic());
            }));
        }
    }
    private static String validateSources(List<Path> changed) {
        var validator = new ReloadValidator(gameRoot().resolve("shaderpacks/greenmarine"),
            gameRoot().resolve("resourcepacks/greenmarine-development-materials"), id -> {
                byte[] bytes = mounted(id, 262144);
                return bytes == null ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }, id -> mounted(id, 4 * 1024 * 1024));
        String error = validator.validate(changed);
        if (error != null) LOGGER.warn("[development] Auto reload rejected: {}", error);
        return error;
    }
    private static byte[] mounted(String id, int limit) {
        try {
            var resource = Minecraft.getInstance().getResourceManager().getResource(net.minecraft.resources.Identifier.parse(id));
            if (resource.isEmpty()) return null;
            try (var stream = resource.get().open()) {
                byte[] bytes = stream.readNBytes(limit + 1); return bytes.length > limit ? null : bytes;
            }
        } catch (Exception error) { return null; }
    }
}
