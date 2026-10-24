package com.koper.greenmarine.client;

import com.koper.greenmarine.GreenMarine;
import com.koper.greenmarine.api.pack.*;
import com.koper.greenmarine.script.PackScript;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import java.nio.file.Path;

public final class ShaderRuntime {
    private static ShaderSettings settings = ShaderSettings.defaults();
    private static PackCatalog.LoadedPack active;
    private static PackScript script;
    private static boolean failureReported;
    private static String parameterError="";
    private static final PackLoadState LOAD=new PackLoadState();
    private static java.util.Set<String> expected=java.util.Set.of();
    private static PackStatus lastNotice;
    public static PackStatus status() {
        if(!GreenMarineClient.enabled() || settings.pack().equals("greenmarine:none")) return new PackStatus(settings.pack(),PackStatus.State.DISABLED,"Effects disabled");
        return LOAD.status();
    }
    public static String programKey(RenderPolicy.ResolvedPass pass) {return pass.definition().name()+"|"+pass.fragment();}
    public static void submitted(RenderPolicy.ResolvedPass pass) {LOAD.submitted(programKey(pass));notice();}
    public static void submittedWorld() {LOAD.submitted("world");notice();}
    public static void renderFailure(Throwable error) {LOAD.fail("Rendering failed: "+error.getMessage());}
    public static void frameStatus() {renderContract();notice();}
    private static void notice() {
        var status=status();
        if(status.state()==PackStatus.State.LOADING || status.state()==PackStatus.State.DISABLED) return;
        if(lastNotice!=null && status.pack().equals(lastNotice.pack()) && status.state()==lastNotice.state()) return;
        lastNotice=status;
        GreenMarine.LOGGER.info("Shaderpack status: pack={}, state={}, detail={}",status.pack(),status.state(),status.detail());
        var client=Minecraft.getInstance();
        if(client.player!=null) client.gui.hud.getChat().addClientSystemMessage(net.minecraft.network.chat.Component.literal("[GreenMarine] "+status.state()+": "+status.pack()+" — "+status.detail()));
    }
    private static void renderContract() {
        if(active==null || LOAD.status().state()==PackStatus.State.FAILED) return;
        var rays=rays();var caps=capabilities();var programs=new java.util.LinkedHashSet<String>();boolean fallback=false,needsRt=false;
        for(var pass:active.definition().passes()) {
            var resolved=RenderPolicy.pass(pass,settings.quality(),caps,rays.effective());
            if(resolved.isPresent()) {programs.add(programKey(resolved.get()));fallback|=resolved.get().fallback();}
            needsRt|=pass.requires()==PackPass.Requirement.HARDWARE_RT;
        }
        if(!active.definition().programs().isEmpty()) {programs.add("world");needsRt|=active.definition().programs().stream().anyMatch(PackProgram::worldRays);}
        if(active.definition().passes().isEmpty() && active.definition().programs().isEmpty()) programs.add("script");
        if(!programs.equals(expected)) {expected=java.util.Set.copyOf(programs);LOAD.begin(active.definition().id(),expected);lastNotice=null;}
        boolean surfaceRt=active.definition().programs().stream().anyMatch(p->p.worldRays() && p.surface()!=null);
        boolean surfaceFallback=surfaceRt && (com.koper.greenmarine.client.rt.RayWorld.surfaceUnavailable() || com.koper.greenmarine.client.world.WorldPrograms.surfaceCaptureUnavailable());
        boolean ready=surfaceFallback || !needsRt || rays.effective()!=RayMode.HARDWARE || (surfaceRt?com.koper.greenmarine.client.rt.RayWorld.surfaceReady():com.koper.greenmarine.client.rt.RayWorld.ready());
        String detail=needsRt ? rays.effective()==RayMode.HARDWARE ? (surfaceRt?com.koper.greenmarine.client.rt.RayWorld.surfaceDetail():com.koper.greenmarine.client.rt.RayWorld.detail())
            : rays.reason()+"; portable shader fallback (hardware dispatch inactive)"
                +(settings.rays()==RayMode.OFF?"":"; "+com.koper.greenmarine.client.rt.RayWorld.detail()) : rays.reason();
        if(active.definition().passes().isEmpty() && active.definition().programs().isEmpty()) detail="Script ready; this pack declares no rendering passes";
        if(surfaceRt && com.koper.greenmarine.client.world.WorldPrograms.surfaceCaptureUnavailable())detail="Wireframe terrain: surface capture unavailable; raster fallback";
        LOAD.path(ready,surfaceFallback || fallback || (needsRt && rays.effective()!=RayMode.HARDWARE),detail);
        if(programs.isEmpty()) LOAD.fail("No compatible shader passes for the selected backend/quality");
        notice();
    }
    private ShaderRuntime() {}
    private static Path settingsPath() { return FabricLoader.getInstance().getConfigDir().resolve("greenmarine-shaders.json"); }
    public static ShaderSettings settings() { return settings; }
    public static PackCatalog.LoadedPack active() { return active; }
    public static PackScript script() { return script; }
    public static RenderCapabilities capabilities() {
        var gpu = RenderSystem.tryGetDevice();
        String backend = gpu == null ? "Unavailable" : gpu.getDeviceInfo().backendName();
        var engine = GreenMarineClient.engine();
        return new RenderCapabilities(backend, engine != null && engine.available(), com.koper.greenmarine.client.rt.RayWorld.available(), gpu!=null, backend.equalsIgnoreCase("Vulkan"),
            gpu!=null && ComputeBackend.initialized(gpu));
    }
    public static RenderPolicy.RaySelection rays() {
        var selected=active!=null?active:ResourcePacks.CATALOG.get(settings.pack()).orElse(null);
        return RenderPolicy.rays(settings.rays(), settings.quality(), capabilities(),selected==null?null:selected.definition());
    }
    public static void reloadSettings() { settings = ShaderSettings.load(settingsPath(), settings); reset(); }
    public static void apply(ShaderSettings next) {
        try {
            next.save(settingsPath());
            boolean rebuild=next.requiresRendererRebuild(settings);
            boolean parametersChanged=!next.parameters().equals(settings.parameters());
            settings = next;
            if(rebuild) reset(); else if(parametersChanged) updateOverrides();
        }
        catch (java.io.IOException error) { GreenMarine.LOGGER.error("Unable to save shader settings", error); }
    }
    public static void previewParameter(String name,float value) {
        previewParameter(settings.pack(),name,value);
    }
    public static String parameterError() { return parameterError; }
    public static boolean previewParameter(String packId,String name,float value) {
        try {
            var definition=ResourcePacks.CATALOG.get(packId).orElseThrow(
                ()->new IllegalArgumentException("Pack was removed by resource reload")).definition();
            settings=settings.withParameter(definition,name,value);
            if(active!=null && active.definition().id().equals(packId)) updateOverrides();
            parameterError="";return true;
        } catch(IllegalArgumentException error) {
            parameterError=error.getMessage();return false;
        }
    }
    public static void saveSettings() {
        try { settings.save(settingsPath()); }
        catch(java.io.IOException error) { GreenMarine.LOGGER.error("Unable to save shader parameters",error); }
    }
    private static void updateOverrides() {
        if(script!=null && active!=null) script.setOverrides(settings.overridesFor(active.definition()));
        PackRenderer.invalidateLightmap();
    }
    public static void tick(Minecraft client) {
        if (client.level == null || client.player == null || !GreenMarineClient.enabled()) return;
        var selected = ResourcePacks.CATALOG.get(settings.pack()).orElse(null);
        if (selected == null && !settings.pack().equals("greenmarine:none")) {if(active!=null) reset();LOAD.begin(settings.pack(),java.util.Set.of());LOAD.fail("Selected shaderpack was not found in loaded resources");notice();return;}
        if (selected != active) {
            reset(); active = selected;
            if (active != null) {
                try {
                    script = new PackScript(active.definition(), active.script(), GreenMarine.LIGHTS);
                    updateOverrides();
                    GreenMarine.LOGGER.info("Initialized shaderpack script {}: quality={}, rays={}, reason={}", active.definition().id(), settings.quality(), rays().effective(), rays().reason());
                } catch (RuntimeException error) { LOAD.begin(settings.pack(),java.util.Set.of());LOAD.fail("Script initialization failed: "+error.getMessage());notice();GreenMarine.LOGGER.error("Shaderpack script initialization failed", error); }
            }
        }
        if (script != null) {
            renderContract();
            var position = client.gameRenderer.mainCamera().position();
            script.tick(new PackScript.Frame(GreenMarineClient.seconds(0), Math.floorMod(client.level.getDefaultClockTime(), 24000) / 24000.0,
                position.x, position.y, position.z, capabilities(), settings.quality(), rays().effective()));
            if(!script.failed() && active.definition().passes().isEmpty() && active.definition().programs().isEmpty()) {LOAD.submitted("script");notice();}
            if (script.failed() && !failureReported) {
                failureReported = true; LOAD.fail("Script failed: "+script.error());notice();GreenMarine.LOGGER.error("Shaderpack script disabled: {}", script.error());
            }
        }
    }
    public static void reset() {
        com.koper.greenmarine.api.material.MaterialAddons.setActivePackId(settings.pack());
        if (script != null) script.close(); script = null; active = null; failureReported = false;
        expected=java.util.Set.of();LOAD.begin(settings.pack(),expected);lastNotice=null;
        com.koper.greenmarine.client.rt.RayWorld.reset();
        com.koper.greenmarine.client.world.WorldPrograms.reset();
        PackRenderer.close();
        GreenPostEffect.resetProfiling();
        VoxelWorld.reset();
    }
}
