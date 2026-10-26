package com.koper.greenmarine.development;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Consumer;

final class ShaderSdkScreen extends Screen {
    private final Screen parent;private final Consumer<String> insert;private String search="";private int page;
    ShaderSdkScreen(Screen parent,Consumer<String> insert){super(Component.literal("GreenMarine SDK"));this.parent=parent;this.insert=insert;}
    @Override protected void init(){
        var filter=addRenderableWidget(new EditBox(font,14,38,width-28,22,Component.literal("Search SDK documentation, API or code")));filter.setMaxLength(256);filter.setValue(search);filter.setResponder(v->{if(!v.equals(search)){search=v;page=0;rebuildWidgets();children().stream().filter(c->c instanceof EditBox).findFirst().ifPresent(this::setFocused);}});
        List<ShaderSdk.Entry> entries=ShaderSdk.search(search);int count=Math.max(1,(height-130)/24),pages=Math.max(1,(entries.size()+count-1)/count);page=Math.clamp(page,0,pages-1);
        for(int i=page*count;i<Math.min(entries.size(),(page+1)*count);i++){var entry=entries.get(i);addRenderableWidget(StudioUi.button(14,66+(i-page*count)*24,width-28,22,entry.category()+" / "+entry.title(),entry.id(),()->minecraft.gui.setScreen(new Reader(this,entry,insert))));}
        addRenderableWidget(StudioUi.button(14,height-56,64,20,"Previous","Previous SDK entries.",()->{page--;rebuildWidgets();})).active=page>0;
        addRenderableWidget(StudioUi.button(82,height-56,96,20,(page+1)+" / "+pages+" Next","Next SDK entries.",()->{page++;rebuildWidgets();})).active=page+1<pages;
        addRenderableWidget(StudioUi.button(14,height-30,width-28,24,"Back to project","Return to your files.",this::onClose));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float tick){g.fill(0,0,width,height,StudioUi.BG);g.text(font,"GreenMarine SDK / offline reference",14,12,StudioUi.ACCENT,false);super.extractRenderState(g,x,y,tick);}
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
    private static final class Reader extends Screen {
        private final Screen parent;private final ShaderSdk.Entry entry;private final Consumer<String> insert;
        Reader(Screen parent,ShaderSdk.Entry entry,Consumer<String> insert){super(Component.literal(entry.title()));this.parent=parent;this.entry=entry;this.insert=insert;}
        @Override protected void init(){
            var code=addRenderableWidget(new CodeEditBox(font,12,34,width-24,Math.max(36,height-72),Component.literal("SDK reference")));code.setCharacterLimit(524288);code.setValue(entry.text());code.moveCursor(0);code.setReadOnly(true);
            int cw=(width-32)/3;
            addRenderableWidget(StudioUi.button(12,height-30,cw,24,"Copy text","Copy this reference or template to the clipboard.",()->minecraft.keyboardHandler.setClipboard(entry.text())));
            addRenderableWidget(StudioUi.button(16+cw,height-30,cw,24,"Insert template","Insert this code at the current file's caret.",()->{insert.accept(entry.text());minecraft.gui.setScreen(((ShaderSdkScreen)parent).parent);})).active=entry.category().equals("Code template");
            addRenderableWidget(StudioUi.button(20+2*cw,height-30,cw,24,"Back","Return to SDK search.",this::onClose));
            setInitialFocus(code);
        }
        @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float tick){g.fill(0,0,width,height,StudioUi.BG);g.text(font,font.plainSubstrByWidth(entry.category()+" / "+entry.title(),width-24),12,12,StudioUi.TEXT,false);super.extractRenderState(g,x,y,tick);}
        @Override public void onClose(){minecraft.gui.setScreen(parent);}
        @Override public boolean isPauseScreen(){return false;}
    }
}
