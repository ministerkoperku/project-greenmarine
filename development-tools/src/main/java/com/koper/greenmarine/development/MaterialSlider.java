package com.koper.greenmarine.development;

import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.*;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

/** Direct manipulation for bounded material properties; arrow keys offer precise adjustment. */
final class MaterialSlider extends AbstractWidget {
    private final String label;
    private float value;
    private final Consumer<Float> change;
    MaterialSlider(int x,int y,int width,String label,float value,Consumer<Float> change){super(x,y,width,27,Component.literal(label));this.label=label;this.value=value;this.change=change;}
    private void update(double x){value=(float)Math.clamp((x-getX())/getWidth(),0,1);change.accept(value);}
    @Override public void onClick(MouseButtonEvent event,boolean twice){update(event.x());}
    @Override protected void onDrag(MouseButtonEvent event,double dx,double dy){update(event.x());}
    @Override public boolean keyPressed(KeyEvent event){if(event.key()==InputConstants.KEY_LEFT||event.key()==InputConstants.KEY_RIGHT){value=Math.clamp(value+(event.key()==InputConstants.KEY_LEFT?-.01f:.01f),0,1);change.accept(value);return true;}return false;}
    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float tick){
        var font=net.minecraft.client.Minecraft.getInstance().font;
        g.text(font,label,getX(),getY(),StudioUi.TEXT,false);String number=String.format(java.util.Locale.ROOT,"%.2f",value);
        g.text(font,number,getRight()-font.width(number),getY(),StudioUi.MUTED,false);
        g.fill(getX(),getY()+17,getRight(),getY()+20,StudioUi.BORDER);g.fill(getX(),getY()+17,getX()+(int)(value*getWidth()),getY()+20,StudioUi.ACCENT);
        int handle=getX()+(int)(value*(getWidth()-4));g.fill(handle,getY()+14,handle+4,getY()+23,StudioUi.TEXT);
    }
    @Override protected void updateWidgetNarration(NarrationElementOutput output){output.add(NarratedElementType.TITLE,Component.literal(label+" "+value));}
}
