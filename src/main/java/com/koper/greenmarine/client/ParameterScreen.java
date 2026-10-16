package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.Locale;

public final class ParameterScreen extends Screen {
    private final Screen parent;
    private final ShaderPack pack;
    private int page;
    private String error="";
    public ParameterScreen(Screen parent,ShaderPack pack) {
        super(Component.literal("Parameters: "+pack.name())); this.parent=parent; this.pack=pack;
    }
    @Override protected void init() {
        int rows=Math.max(1,Math.min(8,(height-90)/26)),pages=Math.max(1,(pack.parameters().size()+rows-1)/rows);
        page=Math.clamp(page,0,pages-1); int x=width/2-130;
        var overrides=ShaderRuntime.settings().overridesFor(pack);
        for(int i=page*rows;i<Math.min(pack.parameters().size(),(page+1)*rows);i++) {
            var parameter=pack.parameters().get(i);
            float current=ShaderRuntime.script()==null?overrides.getOrDefault(parameter.name(),parameter.defaultValue()):ShaderRuntime.script().value(parameter.name());
            addRenderableWidget(new ParameterSlider(x,42+(i-page*rows)*26,parameter,current));
        }
        var previous=addRenderableWidget(Button.builder(Component.literal("Previous"),b->{ page--; rebuildWidgets(); }).bounds(x,height-50,80,20).build());
        previous.active=page>0;
        addRenderableWidget(Button.builder(Component.literal("Reset pack"),b->{
            ShaderRuntime.apply(ShaderRuntime.settings().withoutParameters(pack.id())); rebuildWidgets();
        }).bounds(x+85,height-50,90,20).build());
        var next=addRenderableWidget(Button.builder(Component.literal("Next"),b->{ page++; rebuildWidgets(); }).bounds(x+180,height-50,80,20).build());
        next.active=page+1<pages;
        addRenderableWidget(Button.builder(Component.literal("Done"),b->onClose()).bounds(x,height-25,260,20).build());
    }
    @Override public void onClose() { ShaderRuntime.saveSettings(); minecraft.gui.setScreen(parent); }
    @Override public void removed() { ShaderRuntime.saveSettings(); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partialTick) {
        super.extractRenderState(graphics,mouseX,mouseY,partialTick);
        graphics.centeredText(font,title,width/2,18,0xFFFFFFFF);
        if(!error.isEmpty()) graphics.centeredText(font,error,width/2,30,0xFFFF8888);
    }
    private final class ParameterSlider extends AbstractSliderButton {
        private final PackParameter parameter;
        ParameterSlider(int x,int y,PackParameter parameter,float current) {
            super(x,y,260,20,Component.empty(),parameter.max()==parameter.min()?0:(current-parameter.min())/(parameter.max()-parameter.min()));
            this.parameter=parameter; active=parameter.max()>parameter.min(); updateMessage();
        }
        @Override protected void updateMessage() {
            setMessage(Component.literal(parameter.name()+": "+String.format(Locale.ROOT,"%.3f",current())));
        }
        private float current() { return Math.clamp((float)(parameter.min()+value*(parameter.max()-parameter.min())),parameter.min(),parameter.max()); }
        @Override protected void applyValue() {
            if(!ShaderRuntime.previewParameter(pack.id(),parameter.name(),current())) {
                error=ShaderRuntime.parameterError();
                float restored=ShaderRuntime.settings().overridesFor(pack).getOrDefault(parameter.name(),parameter.defaultValue());
                value=(restored-parameter.min())/(parameter.max()-parameter.min());
            } else error="";
        }
        @Override public void onRelease(MouseButtonEvent event) { super.onRelease(event); ShaderRuntime.saveSettings(); }
    }
}
