package com.koper.greenmarine.development;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Source conversion is explicit and preserves the Iris project for further editing. */
public final class IrisImportScreen extends Screen {
    private final Screen parent;
    private EditBox source;
    private String input = "", status = "Choose an Iris ZIP/directory. Original files are preserved.";
    private boolean busy;
    private IrisImporter.Report report;
    public IrisImportScreen(Screen parent) { super(Component.literal("Import Iris shaderpack")); this.parent = parent; }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public void onFilesDrop(List<Path> paths) {
        if (!busy && !paths.isEmpty()) { input = paths.getFirst().toAbsolutePath().toString(); source.setValue(input); }
    }
    @Override protected void init() {
        int w = Math.max(120, Math.min(460, width - 16)), x = (width - w) / 2;
        source = addRenderableWidget(new EditBox(font, x, 42, w, 24, Component.literal("Iris source path")));
        source.setMaxLength(2048); source.setValue(input); source.setResponder(value -> input = value);
        var importButton = addRenderableWidget(Button.builder(Component.literal(busy ? "Importing..." : "Import editable project"), b -> convert()).bounds(x, 70, w, 24).build());
        importButton.active = !busy; importButton.setTooltip(Tooltip.create(Component.literal(status)));
        int y = 102;
        try {
            Path directory = DevelopmentClient.gameRoot().resolve("shaderpacks");
            if (Files.isDirectory(directory)) try (var files = Files.list(directory)) {
                for (Path path : files.sorted().filter(p -> !p.getFileName().toString().equals("greenmarine"))
                        .filter(p -> Files.isDirectory(p) || p.toString().toLowerCase(Locale.ROOT).endsWith(".zip")).limit(4).toList()) {
                    if (y + 24 >= height - 96) break;
                    var select = addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(path.getFileName().toString(), w - 16)), b -> {
                        input = path.toAbsolutePath().toString(); source.setValue(input);
                    }).bounds(x, y, w, 24).build()); select.active = !busy; y += 28;
                }
            }
        } catch (Exception error) { status = "Source list: " + error.getMessage(); }
        var edit = addRenderableWidget(Button.builder(Component.literal("Open converted project"), b -> {
            String folder = report.destination().getFileName().toString();
            String converted = folder + (report.runnable() ? "/assets/iris_imported/shaders/core/converted.fsh" : "/conversion-candidate/converted.fsh");
            try { DevelopmentClient.workspace().read(converted); minecraft.gui.setScreen(new ShaderEditorScreen(this, converted)); }
            catch (Exception error) { status = "See IMPORT-REPORT.md in " + report.destination(); rebuildWidgets(); }
        }).bounds(x, height - 62, w, 24).build()); edit.active = !busy && report != null;
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(x, height - 34, w, 24).build());
    }
    private void convert() {
        if (busy) return;
        final Path original;
        try { original = Path.of(input).toAbsolutePath(); }
        catch (Exception error) { status = "Choose a source path"; rebuildWidgets(); return; }
        Path output = DevelopmentClient.gameRoot().resolve("shaderpacks/greenmarine/iris-import-" + UUID.randomUUID().toString().substring(0, 8));
        busy = true; status = "Importing bounded source snapshot..."; rebuildWidgets();
        CompletableFuture.supplyAsync(() -> {
            try { return IrisImporter.convert(original, output); }
            catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        }).whenComplete((converted, error) -> minecraft.execute(() -> {
            busy = false;
            if (error != null) { status = "Import failed: " + error.getCause().getMessage(); }
            else {
                report = converted;
                status = (converted.runnable() ? "Fullscreen candidate compiled" : "Manual work required; no selectable pack generated")
                    + " | " + converted.sourceFiles() + " original files preserved\n" + String.join("\n", converted.diagnostics());
                if (converted.runnable()) minecraft.reloadResourcePacks();
            }
            if (minecraft.gui.screen() == this) rebuildWidgets();
        }));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, "Import Iris → editable GreenMarine project", width / 2, 12, 0xFFFFFFFF);
        graphics.centeredText(font, font.plainSubstrByWidth(status.replace('\n', ' '), width - 16), width / 2, height - 80, 0xFFFFDD99);
    }
}
