package com.koper.greenmarine.development;

import com.koper.greenmarine.client.*;
import com.koper.greenmarine.client.rt.RayWorld;
import com.koper.greenmarine.client.world.WorldPrograms;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Touch-accessible launcher; opening the addon never changes the selected shaderpack. */
public final class WorkbenchScreen extends Screen {
    private record Row(String label, Runnable action) {}
    private record Layout(int x, int width, int bodyY, int rowPitch, int rows, int navigationY, int doneY, int buttonHeight) {}
    private final Screen parent;
    private String view = "home", status = "Development Tools is a separate addon";
    private int page;
    public WorkbenchScreen(Screen parent) { super(Component.literal("GreenMarine Development Tools")); this.parent = parent; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    private void open(String next) { view = next; page = 0; rebuildWidgets(); }
    private List<Row> rows() {
        var rows = new ArrayList<Row>();
        if (view.equals("home")) {
            rows.add(new Row("New editable shader pack", () -> {
                try { minecraft.gui.setScreen(new ShaderEditorScreen(this, DevelopmentClient.workspace().createExample())); }
                catch (Exception e) { status = e.getMessage(); rebuildWidgets(); }
            }));
            rows.add(new Row("Edit directory shaderpacks", () -> open("files")));
            rows.add(new Row("Import Iris pack as editable project", () -> minecraft.gui.setScreen(new IrisImportScreen(this))));
            rows.add(new Row("Development settings / auto reload / debug", () -> minecraft.gui.setScreen(new ToolsSettingsScreen(this))));
            rows.add(new Row("Paint material masks", () -> minecraft.gui.setScreen(new MaterialEditorScreen(this))));
            rows.add(new Row("Renderer / pass diagnostics", () -> open("diagnostics")));
            rows.add(new Row("GreenMarine settings", () -> minecraft.gui.setScreen(new ShaderScreen(this))));
            rows.add(new Row("Reload resources", () -> {
                status = "Reload requested";
                minecraft.reloadResourcePacks().whenComplete((v, e) -> minecraft.execute(() -> {
                    status = e == null ? "Resources reloaded" : "Reload failed: " + e.getMessage();
                    if (minecraft.gui.screen() == this) rebuildWidgets();
                }));
            }));
        } else if (view.equals("files")) {
            try {
                for (String file : DevelopmentClient.workspace().files()) rows.add(new Row(file, () -> minecraft.gui.setScreen(new ShaderEditorScreen(this, file))));
                if (rows.isEmpty()) rows.add(new Row("No directory packs. Create an editable pack first.", () -> {}));
            } catch (Exception error) { rows.add(new Row("Read failed: " + error.getMessage(), () -> {})); }
        } else {
            rows.add(new Row("Renderer profiling: " + (ShaderRuntime.settings().profiling() ? "On" : "Off"), () -> {
                var s = ShaderRuntime.settings();
                ShaderRuntime.apply(new ShaderSettings(s.pack(), s.quality(), s.rays(), !s.profiling(), s.parameters()));
                rebuildWidgets();
            }));
            rows.add(new Row("Backend: " + ShaderRuntime.capabilities().backend(), () -> {}));
            rows.add(new Row("Renderer: " + ShaderRuntime.status().state(), () -> {}));
            rows.add(new Row(ShaderRuntime.status().detail(), () -> {}));
            rows.add(new Row("Ray path: " + ShaderRuntime.rays().effective() + " | " + ShaderRuntime.rays().reason(), () -> {}));
            rows.add(new Row("Material catalog generation: " + com.koper.greenmarine.api.material.MaterialAddons.generation(), () -> {}));
            timing(rows, "Pack CPU", PackRenderer.cpuTiming()); timing(rows, "Pack GPU", PackRenderer.gpuTiming());
            timing(rows, "World GPU", WorldPrograms.mainGpu()); timing(rows, "Surface rays GPU", RayWorld.surfaceGpuTiming());
            var pack = ShaderRuntime.active();
            if (pack != null) {
                rows.add(new Row("Active pack: " + pack.definition().id(), () -> {}));
                rows.add(new Row("Source snapshot: " + pack.sources().size() + " texts / " + pack.binaries().size() + " SPIR-V modules", () -> {}));
                for (var pass : pack.definition().passes()) rows.add(new Row("Declared pass " + pass.name() + " | " + pass.scale() + "x | " + pass.format() + " | inputs " + pass.inputs(), () -> {}));
            }
            rows.add(new Row("Refresh diagnostics", () -> rebuildWidgets()));
        }
        return rows;
    }
    private static void timing(List<Row> rows, String name, PerformanceSamples.Snapshot s) {
        rows.add(new Row(name + (s.count() == 0 ? ": no samples (enable profiling in settings)" : String.format(Locale.ROOT, ": %.2f ms / p95 %.2f ms", s.meanMs(), s.p95Ms())), () -> {}));
    }
    @Override protected void init() {
        int w = Math.max(1, Math.min(460, width - 16)), h = 24, pitch = 28, done = height - h - 6, navigation = done - pitch;
        int body = Math.min(60, Math.max(18, navigation - pitch));
        var layout = new Layout((width - w) / 2, w, body, pitch, Math.max(1, (navigation - body) / pitch), navigation, done, h);
        var rows = rows(); int pages = Math.max(1, (rows.size() + layout.rows() - 1) / layout.rows());
        page = Math.clamp(page, 0, pages - 1); int start = page * layout.rows(), end = Math.min(rows.size(), start + layout.rows());
        for (int i = start; i < end; i++) {
            Row row = rows.get(i);
            var button = addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(row.label(), layout.width() - 16)), b -> row.action().run())
                .bounds(layout.x(), layout.bodyY() + (i - start) * layout.rowPitch(), layout.width(), layout.buttonHeight()).build());
            button.setTooltip(Tooltip.create(Component.literal(row.label())));
        }
        int half = (layout.width() - 4) / 2;
        var previous = addRenderableWidget(Button.builder(Component.literal("<"), b -> { page--; rebuildWidgets(); }).bounds(layout.x(), layout.navigationY(), half, layout.buttonHeight()).build());
        previous.active = page > 0;
        var next = addRenderableWidget(Button.builder(Component.literal((page + 1) + "/" + pages + " >"), b -> { page++; rebuildWidgets(); }).bounds(layout.x() + half + 4, layout.navigationY(), half, layout.buttonHeight()).build());
        next.active = page + 1 < pages;
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> { if (view.equals("home")) onClose(); else open("home"); })
            .bounds(layout.x(), layout.doneY(), layout.width(), layout.buttonHeight()).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, "GreenMarine Development / " + view, width / 2, 10, 0xFFFFFFFF);
        graphics.centeredText(font, font.plainSubstrByWidth(status, width - 16), width / 2, 30, 0xFFAACCCC);
        graphics.centeredText(font, font.plainSubstrByWidth(DevelopmentClient.reloadStatus(), width - 16), width / 2, 44, 0xFFCCCCAA);
    }
}
