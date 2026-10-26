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
    private SettingsUiModel.Layout layout;
    private SettingsUiModel.Page pagination;
    public ParameterScreen(Screen parent,ShaderPack pack) {
        super(Component.literal("Parameters: "+pack.name()));this.parent=parent;this.pack=pack;
    }
    @Override protected void init() {
        layout=SettingsUiModel.layout(width,height);
        pagination=SettingsUiModel.page(page,pack.parameters().size(),layout.rows());page=pagination.index();
        var overrides=ShaderRuntime.settings().overridesFor(pack);
        for(int i=pagination.start();i<pagination.end();i++) {
            var parameter=pack.parameters().get(i);
            float current=overrides.getOrDefault(parameter.name(),parameter.defaultValue());
            // A script can animate an unoverridden value; retain its current preview.
            var activePack=ShaderRuntime.active();
            if(ShaderRuntime.script()!=null && activePack!=null && activePack.definition().id().equals(pack.id())
                && activePack.definition().parameters().stream().anyMatch(p->p.name().equals(parameter.name()))) {
                current=ShaderRuntime.script().value(parameter.name());
            }
            addRenderableWidget(new ParameterSlider(layout.bodyY()+(i-pagination.start())*layout.rowPitch(),parameter,current));
        }
        int gap=4,third=(layout.width()-2*gap)/3;
        var previous=addRenderableWidget(MarineUi.builder(Component.literal("< "+(page+1)),b->{page--;rebuildWidgets();})
            .bounds(layout.x(),layout.navigationY(),third,layout.buttonHeight()).build());
        previous.active=page>0;previous.setTooltip(Tooltip.create(Component.literal("Previous page")));
        var reset=addRenderableWidget(MarineUi.builder(Component.literal("Reset"),b->{
            ShaderRuntime.apply(ShaderRuntime.settings().withoutParameters(pack.id()));error="";rebuildWidgets();
        }).bounds(layout.x()+third+gap,layout.navigationY(),third,layout.buttonHeight()).build());
        reset.setTooltip(Tooltip.create(Component.literal("Reset all parameters for "+pack.name()+" to their defaults. Other packs retain their values.")));
        var next=addRenderableWidget(MarineUi.builder(Component.literal(pagination.pages()+" >"),b->{page++;rebuildWidgets();})
            .bounds(layout.x()+2*(third+gap),layout.navigationY(),layout.width()-2*(third+gap),layout.buttonHeight()).build());
        next.active=page+1<pagination.pages();next.setTooltip(Tooltip.create(Component.literal("Next page")));
        addRenderableWidget(MarineUi.builder(Component.literal("Done"),b->onClose())
            .bounds(layout.x(),layout.doneY(),layout.width(),layout.buttonHeight()).build());
    }
    @Override public void onClose() {ShaderRuntime.saveSettings();minecraft.gui.setScreen(parent);}
    @Override public void removed() {ShaderRuntime.saveSettings();}
    @Override public boolean isPauseScreen() {return false;}
    private String fit(String text) {return font.plainSubstrByWidth(text,Math.max(1,layout.width()-12));}
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partialTick) {
        graphics.fill(0,0,width,height,MarineUi.BG);
        graphics.fill(0,0,3,height,MarineUi.ACCENT);
        super.extractRenderState(graphics,mouseX,mouseY,partialTick);
        graphics.centeredText(font,fit(title.getString()),width/2,10,0xFFFFFFFF);
        if(layout.bodyY()>=60) {
            graphics.centeredText(font,"Page "+(page+1)+" / "+pagination.pages()+" | "+pack.parameters().size()+" parameters",width/2,30,0xFFAAEEDD);
            graphics.centeredText(font,fit(error.isEmpty()?"Changes preview immediately and save when you leave.":error),width/2,44,error.isEmpty()?0xFFCCCCCC:0xFFFF8888);
        }
    }
    private final class ParameterSlider extends AbstractSliderButton {
        private final PackParameter parameter;
        ParameterSlider(int y,PackParameter parameter,float current) {
            super(layout.x(),y,layout.width(),layout.buttonHeight(),Component.empty(),parameter.max()==parameter.min()?0:(current-parameter.min())/(parameter.max()-parameter.min()));
            this.parameter=parameter;active=parameter.max()>parameter.min();updateMessage();
            setTooltip(Tooltip.create(Component.literal(SettingsUiModel.friendlyName(parameter.name())+" ("+parameter.name()+")\nRange "+number(parameter.min())+" to "+number(parameter.max())+" | Default "+number(parameter.defaultValue())+"\nDrag to preview; use arrow keys for fine adjustments.")));
        }
        @Override protected void updateMessage() {
            String number=number(current());int labelWidth=Math.max(1,layout.width()-font.width(number)-28);
            setMessage(Component.literal(font.plainSubstrByWidth(SettingsUiModel.friendlyName(parameter.name()),labelWidth)+": "+number));
        }
        private float current() {return Math.clamp((float)(parameter.min()+value*(parameter.max()-parameter.min())),parameter.min(),parameter.max());}
        @Override protected void applyValue() {
            if(!ShaderRuntime.previewParameter(pack.id(),parameter.name(),current())) {
                error=ShaderRuntime.parameterError();
                float restored=ShaderRuntime.settings().overridesFor(pack).getOrDefault(parameter.name(),parameter.defaultValue());
                value=parameter.max()==parameter.min()?0:(restored-parameter.min())/(parameter.max()-parameter.min());
                updateMessage();
            } else error="";
        }
        @Override public void onRelease(MouseButtonEvent event) {super.onRelease(event);ShaderRuntime.saveSettings();}
    }
    private static String number(float value) {return String.format(Locale.ROOT,"%.3f",value);}
}
