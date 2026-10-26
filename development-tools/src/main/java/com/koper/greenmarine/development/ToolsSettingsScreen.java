package com.koper.greenmarine.development;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class ToolsSettingsScreen extends Screen {
    private final Screen parent;
    private EditBox compiler;
    private String path, status = "Auto reload and verbose debug are optional";
    public ToolsSettingsScreen(Screen parent) { super(Component.literal("Development settings")); this.parent = parent; path = DevelopmentClient.preferences().slangCompiler(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override protected void init() {
        addRenderableWidget(StudioUi.button(width-88,4,74,22,"UI size","Adjust the size of all Minecraft menus.",()->minecraft.gui.setScreen(new UiScaleScreen(this))));
        int w = Math.max(120, Math.min(440, width - 16)), x = (width - w) / 2;
        var current = DevelopmentClient.preferences();
        addRenderableWidget(StudioUi.builder(Component.literal("Auto reload: " + (current.autoReload() ? "On" : "Off")), b -> {
            save(!DevelopmentClient.preferences().autoReload(), DevelopmentClient.preferences().verboseDebug()); rebuildWidgets();
        }).bounds(x, 52, w, 24).build());
        addRenderableWidget(StudioUi.builder(Component.literal("Verbose debug: " + (current.verboseDebug() ? "On" : "Off")), b -> {
            save(DevelopmentClient.preferences().autoReload(), !DevelopmentClient.preferences().verboseDebug()); rebuildWidgets();
        }).bounds(x, 80, w, 24).build());
        compiler = addRenderableWidget(new EditBox(font, x, 126, w, 24, Component.literal("Slang compiler executable (optional)")));
        compiler.setMaxLength(2048); compiler.setValue(path); compiler.setResponder(value -> path = value);
        addRenderableWidget(StudioUi.builder(Component.literal("Save settings"), b -> save(DevelopmentClient.preferences().autoReload(), DevelopmentClient.preferences().verboseDebug()))
            .bounds(x, height - 62, w, 24).build());
        addRenderableWidget(StudioUi.builder(Component.literal("Back"), b -> onClose()).bounds(x, height - 34, w, 24).build());
    }
    private void save(boolean auto, boolean debug) {
        try { DevelopmentClient.preferences(new DevelopmentPreferences(auto, debug, path)); status = "Saved. " + DevelopmentClient.reloadStatus(); }
        catch (Exception e) { status = "Settings error: " + e.getMessage(); }
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        StudioUi.background(graphics,width,height,title.getString(),"Development studio");
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.centeredText(font, "Slang executable path (blank = unavailable)", width / 2, 110, 0xFFCCCCCC);
        graphics.centeredText(font, font.plainSubstrByWidth(status, width - 16), width / 2, height - 78, 0xFFAADDCC);
    }
}
