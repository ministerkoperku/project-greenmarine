package com.koper.greenmarine.development;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Uses the game's own scale so pointer positions and rendering stay in the same space. */
final class UiScaleScreen extends Screen {
    private final Screen parent;
    UiScaleScreen(Screen parent){super(Component.literal("UI size"));this.parent=parent;}
    @Override protected void init(){
        int x=14,w=width-28,max=minecraft.getWindow().calculateScale(0,minecraft.isEnforceUnicode());
        int selected=minecraft.options.guiScale().get(),columns=Math.max(1,Math.min(6,w/58)),cw=w/columns;
        for(int i=0;i<=max;i++){
            final int scale=i;
            addRenderableWidget(StudioUi.button(x+(i%columns)*cw,60+(i/columns)*28,cw-4,24,(selected==i?"• ":"")+(i==0?"Auto":i+"x"),i==1?"Small controls and the largest workspace.":"Larger controls and text. Applies to all Minecraft menus.",()->{
                minecraft.options.guiScale().set(scale);minecraft.options.save();minecraft.resizeGui();
            }));
        }
        addRenderableWidget(StudioUi.button(14,height-32,w,24,"Back to editor","Keep this UI size and return.",this::onClose));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float tick){
        StudioUi.background(g,width,height,"UI size","Smaller UI = more room to work");
        super.extractRenderState(g,mx,my,tick);
        g.text(font,"1x is smallest. Changes apply to all Minecraft menus.",14,height-49,StudioUi.MUTED,false);
    }
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
