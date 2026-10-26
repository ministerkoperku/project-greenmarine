package com.koper.greenmarine.development;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.function.Consumer;

final class TextPromptScreen extends Screen {
    private final Screen parent;private final Consumer<String> action;private final String help;private String value,status="";
    TextPromptScreen(Screen parent,String title,String value,String help,Consumer<String> action){super(Component.literal(title));this.parent=parent;this.value=value;this.help=help;this.action=action;}
    @Override protected void init(){
        var input=addRenderableWidget(new EditBox(font,14,62,width-28,24,Component.literal(help)));input.setMaxLength(2048);input.setValue(value);input.setResponder(v->value=v);setInitialFocus(input);
        addRenderableWidget(StudioUi.button(14,height-34,(width-32)/2,24,"Cancel","Keep the existing file.",this::onClose));
        addRenderableWidget(StudioUi.button(18+(width-32)/2,height-34,(width-32)/2,24,"Apply",help,()->{
            try{action.accept(value.strip());if(minecraft.gui.screen()==this)minecraft.gui.setScreen(parent);}catch(Exception e){status=e.getMessage();}
        }));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float tick){StudioUi.background(g,width,height,title.getString(),"");super.extractRenderState(g,x,y,tick);g.text(font,font.plainSubstrByWidth(help,width-28),14,94,StudioUi.MUTED,false);g.text(font,font.plainSubstrByWidth(status==null?"Action failed":status,width-28),14,height-52,0xffffaaaa,false);}
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
