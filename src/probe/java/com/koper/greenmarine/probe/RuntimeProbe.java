package com.koper.greenmarine.probe;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.client.GreenMarineClient;
import com.koper.greenmarine.client.ShaderRuntime;
import com.koper.greenmarine.client.PackRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RuntimeProbe implements ClientModInitializer {
    private static long started;
    private static int worldTicks, stage;
    private static final AtomicBoolean CAPTURED = new AtomicBoolean();
    private static String lastScreen;
    private static boolean failed;
    private static final Path OUTPUT = Path.of(System.getProperty("greenmarine.probe.output", "probe-output"));
    private static java.util.List<com.koper.greenmarine.api.Light> beforeTurn;
    private static com.google.gson.JsonObject capturedResult;
    private static com.koper.greenmarine.client.ShaderSettings savedSettings;
    private static boolean profilingControlsVerified;
    private static boolean shaderFailureRecoveryVerified;
    private static AutoCloseable failurePack;

    @Override public void onInitializeClient() {
        started = System.nanoTime();
        try {
            Files.createDirectories(OUTPUT);
            for (String filename : new String[]{"glow-on.png", "glow-off.png", "glow-after-reload.png", "glow-after-reload-off.png", "light-after-camera-turn.png", "light-after-camera-turn-off.png", "settings.png", "voxel-rays-off.png", "voxel-rays-on.png", "result.json"}) {
                Files.deleteIfExists(OUTPUT.resolve(filename));
            }
            result("running");
        } catch (Exception error) {
            failed = true;
            GreenMarine.LOGGER.error("Probe output initialization failed", error);
        }
        GreenMarine.LOGGER.info("Runtime probe active; screenshots stay in the development run directory");
    }

    public static void tick(Minecraft client) {
        if (started == 0) return;
        client.options.pauseOnLostFocus = false;
        if(Boolean.getBoolean("greenmarine.probe.world") && PackRenderer.failed()) WorldLightingProbe.abort(client,OUTPUT);
        if (stage!=18 && PackRenderer.failed()
            && !(Boolean.getBoolean("greenmarine.probe.hardware_rt") && HardwareRtProbe.expectsShaderFailure())) {
            failed = true; GreenMarine.LOGGER.error("Probe detected a failed shaderpack renderer");
        }
        if (failed) {
            if(Boolean.getBoolean("greenmarine.probe.performance")) {TerrainPerformanceProbe.abort(client,OUTPUT,new IllegalStateException("Renderer failed"));return;}
            if(Boolean.getBoolean("greenmarine.probe.named_passes")) {NamedPassProbe.abort(client,OUTPUT,new IllegalStateException("Renderer failed"));return;}
            if(Boolean.getBoolean("greenmarine.probe.history_compatibility")) {HistoryCompatibilityProbe.abort(client,OUTPUT,new IllegalStateException("Renderer failed"));return;}
            if(Boolean.getBoolean("greenmarine.probe.dynamic_rays")) {DynamicRaySceneProbe.abort(client,OUTPUT,new IllegalStateException("Probe failed or timed out"));return;}
            if(Boolean.getBoolean("greenmarine.probe.world_programs")) {WorldProgramsProbe.abort(client,OUTPUT,new IllegalStateException("Probe failed or timed out"));return;}
            if(Boolean.getBoolean("greenmarine.probe.hardware_rt")) {HardwareRtProbe.abort(client,OUTPUT,new IllegalStateException("Probe detected renderer failure"));return;}
            restoreSettings(); result("failed"); client.stop(); return;
        }
        if ((System.nanoTime() - started) / 1_000_000_000L > (Boolean.getBoolean("greenmarine.probe.look")?400:Boolean.getBoolean("greenmarine.probe.dynamic_rays") || Boolean.getBoolean("greenmarine.probe.performance") || Boolean.getBoolean("greenmarine.probe.near_reflection") || Boolean.getBoolean("greenmarine.probe.distant") || Boolean.getBoolean("greenmarine.probe.mobs") || Boolean.getBoolean("greenmarine.probe.torch") || Boolean.getBoolean("greenmarine.probe.water")?300:150)) {
            if(Boolean.getBoolean("greenmarine.probe.performance")) {TerrainPerformanceProbe.abort(client,OUTPUT,new IllegalStateException("Performance probe timed out"));return;}
            if(Boolean.getBoolean("greenmarine.probe.named_passes")) {NamedPassProbe.abort(client,OUTPUT,new IllegalStateException("Performance probe timed out"));return;}
            if(Boolean.getBoolean("greenmarine.probe.history_compatibility")) {HistoryCompatibilityProbe.abort(client,OUTPUT,new IllegalStateException("Performance probe timed out"));return;}
            if(Boolean.getBoolean("greenmarine.probe.dynamic_rays")) {DynamicRaySceneProbe.abort(client,OUTPUT,new IllegalStateException("Probe failed or timed out"));return;}
            if(Boolean.getBoolean("greenmarine.probe.world_programs")) {WorldProgramsProbe.abort(client,OUTPUT,new IllegalStateException("Probe failed or timed out"));return;}
            if(Boolean.getBoolean("greenmarine.probe.water")) {DistantWaterProbe.finish(client,OUTPUT,"timed out");return;}
            if(Boolean.getBoolean("greenmarine.probe.torch")) {TorchShadowProbe.finish(client,OUTPUT,"timed out");return;}
            if(Boolean.getBoolean("greenmarine.probe.mobs")) {MobReflectionProbe.finish(client,OUTPUT,"timed out");return;}
            if(Boolean.getBoolean("greenmarine.probe.distant")) {DistantProbe.finish(client,OUTPUT,"timed out");return;}
            if(Boolean.getBoolean("greenmarine.probe.near_reflection")) {NearReflectionProbe.abort(client,OUTPUT,new IllegalStateException("Runtime probe timed out"));return;}
            if(Boolean.getBoolean("greenmarine.probe.hardware_rt")) {HardwareRtProbe.abort(client,OUTPUT,new IllegalStateException("Runtime probe timed out"));return;}
            GreenMarine.LOGGER.error("Runtime probe timed out at stage {}", stage);
            failed = true;
            result("failed");
            client.stop();
            return;
        }
        var screen = client.gui.screen();
        String name = screen == null ? "none" : screen.getClass().getSimpleName();
        if (!name.equals(lastScreen)) { lastScreen = name; GreenMarine.LOGGER.info("Probe screen: {}", name); }
        if (client.gui.overlay() != null) return;
        if (stage == 0 && screen instanceof TitleScreen) {
            for (var child : screen.children()) {
                if (child instanceof Button button && button.getMessage().getString().equals(Component.translatable("menu.playdemo").getString())) {
                    stage = 1;
                    button.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
                    return;
                }
            }
        }
        if (screen != null && name.equals("AccessibilityOnboardingScreen")) {
            for (var child : screen.children()) {
                if (child instanceof Button button && button.getMessage().getString().equals("Continue")) {
                    button.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0)); return;
                }
            }
        }
        if (client.level == null) return;
        if (client.player != null) client.player.input = new net.minecraft.client.player.ClientInput();
        if (screen != null && (name.equals("PauseScreen") || name.equals("DemoIntroScreen") || (client.isDemo() && name.equals("PopupScreen")))) {
            client.gui.setScreen(null);
        }
        if(Boolean.getBoolean("greenmarine.probe.look")) { LookProbe.tick(client,OUTPUT); return; }
        if(Boolean.getBoolean("greenmarine.probe.dynamic_rays")) { DynamicRaySceneProbe.tick(client,OUTPUT); return; }
        if(Boolean.getBoolean("greenmarine.probe.performance")) { TerrainPerformanceProbe.tick(client,OUTPUT); return; }
        if(Boolean.getBoolean("greenmarine.probe.named_passes")) { NamedPassProbe.tick(client,OUTPUT); return; }
        if(Boolean.getBoolean("greenmarine.probe.history_compatibility")) { HistoryCompatibilityProbe.tick(client,OUTPUT); return; }
        if(Boolean.getBoolean("greenmarine.probe.world_programs")) { WorldProgramsProbe.tick(client,OUTPUT); return; }
        if(Boolean.getBoolean("greenmarine.probe.water")) { DistantWaterProbe.tick(client,OUTPUT); return; }
        if(Boolean.getBoolean("greenmarine.probe.torch")) { TorchShadowProbe.tick(client,OUTPUT); return; }
        if(Boolean.getBoolean("greenmarine.probe.mobs")) { MobReflectionProbe.tick(client,OUTPUT); return; }
        if(Boolean.getBoolean("greenmarine.probe.distant")) { DistantProbe.tick(client,OUTPUT); return; }
        if(Boolean.getBoolean("greenmarine.probe.near_reflection")) { NearReflectionProbe.tick(client,OUTPUT); return; }
        if(Boolean.getBoolean("greenmarine.probe.hardware_rt")) { HardwareRtProbe.tick(client,OUTPUT); return; }
        if(Boolean.getBoolean("greenmarine.probe.render_api")) { RenderApiProbe.tick(client,OUTPUT); return; }
        if(Boolean.getBoolean("greenmarine.probe.voxel")) { VoxelRuntimeProbe.tick(client,OUTPUT); return; }
        if(Boolean.getBoolean("greenmarine.probe.world")) { WorldLightingProbe.tick(client,OUTPUT); return; }
        if (++worldTicks < 80) return;
        switch (stage) {
            case 1 -> {
                if (GreenMarine.LIGHTS.snapshot().isEmpty()) return;
                if (packSelected() && (ShaderRuntime.script() == null || ShaderRuntime.script().ticks() < 2 || PackRenderer.sceneFrames() == 0)) return;
                stage = 2; CAPTURED.set(false); capture(client, "glow-on.png");
            }
            case 2 -> {
                if (!CAPTURED.get()) return;
                GreenMarineClient.toggleEffect();
                stage = 3; worldTicks = 0;
            }
            case 3 -> {
                stage = 4; CAPTURED.set(false); capture(client, "glow-off.png");
            }
            case 4 -> {
                if (!CAPTURED.get()) return;
                GreenMarineClient.toggleEffect();
                client.getWindow().setWindowed(800, 600);
                stage = 5; worldTicks = 0;
            }
            case 5 -> {
                stage = 6; CAPTURED.set(false);
                client.reloadResourcePacks().whenComplete((ignored, error) -> {
                    if (error != null) {
                        failed = true;
                        GreenMarine.LOGGER.error("Probe resource reload failed", error);
                    }
                    CAPTURED.set(true);
                });
            }
            case 6 -> {
                if (!CAPTURED.get()) return;
                stage = 7; worldTicks = 0;
            }
            case 7 -> {
                stage = 8; CAPTURED.set(false); capture(client, "glow-after-reload.png");
            }
            case 8 -> {
                if (!CAPTURED.get()) return;
                GreenMarineClient.toggleEffect();
                stage = 9; worldTicks = 0;
            }
            case 9 -> {
                stage = 10; CAPTURED.set(false); capture(client, "glow-after-reload-off.png");
            }
            case 10 -> {
                if (!CAPTURED.get()) return;
                beforeTurn = GreenMarine.LIGHTS.snapshot();
                client.player.setYRot(client.player.getYRot() + 20);
                client.player.setYHeadRot(client.player.getYRot());
                GreenMarineClient.toggleEffect();
                stage = 11; worldTicks = 0;
            }
            case 11 -> {
                if (!beforeTurn.equals(GreenMarine.LIGHTS.snapshot())) {
                    failed = true;
                    GreenMarine.LOGGER.error("World light moved with the camera");
                    return;
                }
                GreenMarine.LOGGER.info("World light position unchanged after camera turn: {}", beforeTurn);
                stage = 12; CAPTURED.set(false); capture(client, "light-after-camera-turn.png");
            }
            case 12 -> {
                if (!CAPTURED.get()) return;
                GreenMarineClient.toggleEffect();
                stage = 13; worldTicks = 0;
            }
            case 13 -> {
                stage = 14; CAPTURED.set(false); capture(client, "light-after-camera-turn-off.png");
            }
            case 14 -> {
                if (!CAPTURED.get()) return;
                result("running");
                try { capturedResult=com.google.gson.JsonParser.parseString(Files.readString(OUTPUT.resolve("result.json"))).getAsJsonObject(); }
                catch(Exception error) { failed=true; return; }
                savedSettings=ShaderRuntime.settings();
                GreenMarineClient.toggleEffect();
                if (!savedSettings.profiling()) { beginFailure(); return; }
                client.gui.setScreen(new com.koper.greenmarine.client.ShaderScreen());
                pressProfiling(client);
                stage=15; worldTicks=0;
            }
            case 15 -> {
                if (ShaderRuntime.settings().profiling() || com.koper.greenmarine.client.GreenPostEffect.gpuTiming().count()!=0) {
                    failed=true; GreenMarine.LOGGER.error("Point-light profiling continued after the F6 Off button"); return;
                }
                pressProfiling(client);
                stage=16; worldTicks=0;
            }
            case 16 -> {
                if (!ShaderRuntime.settings().profiling() || com.koper.greenmarine.client.GreenPostEffect.gpuTiming().count()==0
                    || ShaderRuntime.settings().rays()!=savedSettings.rays()) {
                    failed=true; GreenMarine.LOGGER.error("Point-light profiling failed to restart with unchanged ray mode"); return;
                }
                profilingControlsVerified=true;
                GreenMarine.LOGGER.info("F6 profiling On -> Off -> On passed with unchanged ray mode");
                stage=17; CAPTURED.set(false); capture(client,"settings.png");
            }
            case 17 -> {
                if (!CAPTURED.get()) return;
                ShaderRuntime.apply(savedSettings);
                client.gui.setScreen(null);
                beginFailure();
            }
            case 18 -> {
                if(!PackRenderer.failed()) { failed=true; GreenMarine.LOGGER.error("Invalid GPU shader was not rejected"); return; }
                GreenMarine.LOGGER.info("Expected invalid shader rejected; restoring a valid pack");
                restoreSettings(); stage=19; worldTicks=0;
            }
            case 19 -> {
                if(packSelected() && (PackRenderer.sceneFrames()==0 || ShaderRuntime.script()==null || ShaderRuntime.script().failed())) {
                    failed=true; GreenMarine.LOGGER.error("Valid pack failed to recover after GPU shader error"); return;
                }
                shaderFailureRecoveryVerified=true;
                GreenMarine.LOGGER.info("Java pack registration and GPU shader failure recovery passed");
                finish(client);
            }
            default -> {}
        }
    }

    private static void beginFailure() {
        try {
            var pass=new com.koper.greenmarine.api.pack.PackPass("broken",
                com.koper.greenmarine.api.pack.PackPass.Stage.SCENE,"greenmarine:core/probe_bad",null,
                com.koper.greenmarine.api.pack.PackPass.Requirement.NONE,com.koper.greenmarine.api.pack.Quality.LOW,1,null);
            var pack=new com.koper.greenmarine.api.pack.ShaderPack("greenmarine:probe_failure","Probe failure",null,
                java.util.List.of(),java.util.List.of(pass));
            failurePack=GreenMarine.PACKS.register(new com.koper.greenmarine.api.pack.PackCatalog.LoadedPack(pack,
                "function update(frame) end",java.util.Map.of("greenmarine:core/probe_bad","#version 330\ninvalid GLSL\n")));
            GreenMarine.LOGGER.info("Injecting an intentionally invalid GPU shader for recovery verification");
            stage=18; worldTicks=0;
            ShaderRuntime.apply(new com.koper.greenmarine.client.ShaderSettings(pack.id(),savedSettings.quality(),savedSettings.rays(),false));
        } catch(Exception error) { failed=true; GreenMarine.LOGGER.error("Failure fixture registration failed",error); }
    }

    private static void restoreSettings() {
        if(failurePack!=null) {
            try { failurePack.close(); } catch(Exception error) { GreenMarine.LOGGER.error("Failure fixture removal failed",error); }
            failurePack=null;
        }
        if(savedSettings!=null) ShaderRuntime.apply(savedSettings);
    }

    private static void pressProfiling(Minecraft client) {
        var screen=client.gui.screen();
        if(screen!=null) for(var child:screen.children()) if(child instanceof Button button
            && button.getMessage().getString().startsWith("Profiling: ")) {
            button.onPress(new KeyEvent(InputConstants.KEY_RETURN,0,0)); return;
        }
        failed=true; GreenMarine.LOGGER.error("F6 profiling button missing");
    }

    private static void finish(Minecraft client) {
        GreenMarine.LOGGER.info("Runtime probe completed; disconnecting the development world");
        stage=20; result("complete"); client.disconnectWithSavingScreen(); client.stop();
    }

    private static void capture(Minecraft client, String filename) {
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(OUTPUT.resolve(filename));
                GreenMarine.LOGGER.info("Probe screenshot saved: {}", filename);
            } catch (Exception error) {
                failed = true;
                GreenMarine.LOGGER.error("Probe screenshot failed", error);
            } finally { CAPTURED.set(true); }
        });
    }

    private static void result(String status) {
        try {
            var result = new com.google.gson.JsonObject(); result.addProperty("status",status);
            result.addProperty("pack",ShaderRuntime.settings().pack()); result.addProperty("sceneFrames",PackRenderer.sceneFrames());
            result.addProperty("cpuSubmissionMicros",PackRenderer.averageSubmissionMicros()); result.addProperty("shadedPixels",PackRenderer.shadedPixels());
            var gson=new com.google.gson.Gson(); result.add("sceneCpu",gson.toJsonTree(PackRenderer.cpuTiming())); result.add("sceneGpu",gson.toJsonTree(PackRenderer.gpuTiming()));
            result.add("lightsCpu",gson.toJsonTree(com.koper.greenmarine.client.GreenPostEffect.cpuTiming())); result.add("lightsGpu",gson.toJsonTree(com.koper.greenmarine.client.GreenPostEffect.gpuTiming()));
            result.addProperty("voxelScans",com.koper.greenmarine.client.VoxelWorld.scans()); result.addProperty("voxelScanNanos",com.koper.greenmarine.client.VoxelWorld.scanNanos());
            if (ShaderRuntime.script() != null) { result.addProperty("scriptTicks",ShaderRuntime.script().ticks()); result.addProperty("scriptMicros",ShaderRuntime.script().averageMicros()); }
            if(capturedResult!=null) result=capturedResult.deepCopy();
            result.addProperty("status",status);
            result.addProperty("profilingControlsVerified",profilingControlsVerified);
            result.addProperty("shaderFailureRecoveryVerified",shaderFailureRecoveryVerified);
            Files.writeString(OUTPUT.resolve("result.json"),result+"\n");
        }
        catch (Exception error) { failed = true; GreenMarine.LOGGER.error("Probe result write failed", error); }
    }

    private static boolean packSelected() { return !ShaderRuntime.settings().pack().equals("greenmarine:none"); }
}
