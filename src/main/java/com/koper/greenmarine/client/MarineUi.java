package com.koper.greenmarine.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Shared visual vocabulary for the authoring screens. */
final class MarineUi {
    static final int BG=0xff0b111c,PANEL=0xff131f30,BORDER=0xff26384d,TEXT=0xffe8f0f7,MUTED=0xff98adbf,ACCENT=0xff5ce0c4;
    static void background(GuiGraphicsExtractor g,int width,int height,String title,String subtitle) {
        var font=Minecraft.getInstance().font;
        g.fill(0,0,width,height,BG);g.fill(0,0,3,height,ACCENT);
        g.text(font,"GREENMARINE",14,10,ACCENT,false);
        g.text(font,title,14,25,TEXT,false);
        if(width>480)g.text(font,font.plainSubstrByWidth(subtitle,width-290),170,25,MUTED,false);
        g.fill(12,43,width-12,44,BORDER);
    }
    static Button button(int x,int y,int w,int h,String text,String help,Runnable action) {
        var button=new FlatButton(x,y,Math.max(12,w),h,text,action);
        if(!help.isBlank())button.setTooltip(Tooltip.create(Component.literal(help)));
        return button;
    }
    static Builder builder(Component text,Button.OnPress action){return new Builder(text,action);}
    static final class Builder extends Button.Builder {
        private final Component text;
        private final Button.OnPress action;
        private int x,y,w=150,h=20;
        Builder(Component text,Button.OnPress action){super(text,action);this.text=text;this.action=action;}
        @Override public Builder bounds(int x,int y,int w,int h){this.x=x;this.y=y;this.w=w;this.h=h;return this;}
        @Override public Button build(){return new FlatButton(x,y,w,h,text,action);}
    }
    static final class FlatButton extends Button {
        FlatButton(int x,int y,int w,int h,String text,Runnable action){this(x,y,w,h,Component.literal(text),b->action.run());}
        FlatButton(int x,int y,int w,int h,Component text,Button.OnPress action){super(x,y,w,h,text,action,DEFAULT_NARRATION);}
        @Override protected void extractContents(GuiGraphicsExtractor g,int mx,int my,float tick) {
            var font=Minecraft.getInstance().font;boolean hover=isHoveredOrFocused();
            g.fill(getX(),getY(),getRight(),getBottom(),active?(hover?0xff244338:PANEL):0xff101824);
            g.fill(getX(),getBottom()-1,getRight(),getBottom(),active&&hover?ACCENT:BORDER);
            if(isFocused())g.outline(getX(),getY(),width,height,ACCENT);
            String label=font.plainSubstrByWidth(getMessage().getString(),Math.max(1,width-14));
            g.text(font,label,getX()+7,getY()+(height>=36?7:(height-8)/2),active?(hover?ACCENT:TEXT):0xff607185,false);
        }
    }
}
