package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;

public final class ShaderScreen extends Screen {
    private final Screen parent;
    public ShaderScreen() { this(null); }
    public ShaderScreen(Screen parent) { super(Component.literal("GreenMarine Shaders")); this.parent = parent; }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override protected void init() {
        var settings = ShaderRuntime.settings(); int x = width / 2 - 130, y = Math.max(45,height/2-90);
        addRenderableWidget(Button.builder(Component.literal("Pack: "+settings.pack()), button -> {
            var ids = new ArrayList<String>(); ids.add("greenmarine:none"); ResourcePacks.CATALOG.packs().forEach(pack -> ids.add(pack.definition().id()));
            String next = ids.get((ids.indexOf(settings.pack())+1)%ids.size());
            apply(new ShaderSettings(next,settings.quality(),settings.rays(),settings.profiling(),settings.parameters()));
        }).bounds(x,y,260,20).build());
        addRenderableWidget(Button.builder(Component.literal("Quality: "+settings.quality()), button ->
            apply(new ShaderSettings(settings.pack(),Quality.values()[(settings.quality().ordinal()+1)%Quality.values().length],settings.rays(),settings.profiling(),settings.parameters())))
            .bounds(x,y+25,260,20).build());
        addRenderableWidget(Button.builder(Component.literal("Ray lighting: "+settings.rays()), button ->
            apply(new ShaderSettings(settings.pack(),settings.quality(),RayMode.values()[(settings.rays().ordinal()+1)%RayMode.values().length],settings.profiling(),settings.parameters())))
            .bounds(x,y+50,260,20).build());
        addRenderableWidget(Button.builder(Component.literal("Profiling: "+(settings.profiling()?"On":"Off")), button ->
            apply(new ShaderSettings(settings.pack(),settings.quality(),settings.rays(),!settings.profiling(),settings.parameters())))
            .bounds(x,y+75,260,20).build());
        var parameters=addRenderableWidget(Button.builder(Component.literal("Pack parameters"),button ->
            ResourcePacks.CATALOG.get(settings.pack()).ifPresent(pack -> minecraft.gui.setScreen(new ParameterScreen(this,pack.definition()))))
            .bounds(x,y+100,260,20).build());
        parameters.active=ResourcePacks.CATALOG.get(settings.pack()).map(pack -> !pack.definition().parameters().isEmpty()).orElse(false);
        addRenderableWidget(Button.builder(Component.literal("Done"),button -> onClose()).bounds(x,y+130,260,20).build());
    }
    private void apply(ShaderSettings settings) { ShaderRuntime.apply(settings); rebuildWidgets(); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partialTick) {
        super.extractRenderState(graphics,mouseX,mouseY,partialTick);
        graphics.centeredText(font,"GreenMarine — Vulkan first",width/2,20,0xFFFFFFFF);
        var status=ShaderRuntime.status();
        graphics.centeredText(font,"Pack status: "+status.state(),width/2,height-60,status.state()==com.koper.greenmarine.api.pack.PackStatus.State.FAILED?0xFFFF7777:0xFFBBDDCC);
        graphics.centeredText(font,font.plainSubstrByWidth(status.detail(),Math.max(1,width-24)),width/2,height-46,0xFFBBDDCC);
        graphics.centeredText(font,font.plainSubstrByWidth("Backend: "+ShaderRuntime.capabilities().backend()+" | "+ShaderRuntime.rays().reason(),Math.max(1,width-24)),width/2,height-32,0xFFCCCCCC);
    }
    @Override public boolean isPauseScreen() { return false; }
}
