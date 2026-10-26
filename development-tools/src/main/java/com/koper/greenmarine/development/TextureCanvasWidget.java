package com.koper.greenmarine.development;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

/** Local PNG painter; the project owns the draft so navigation never discards pixels. */
final class TextureCanvasWidget extends AbstractWidget {
    private final TextureDocument image;
    private int color=0xffffffff,brush=1,zoom=1,vx,vy,cx,cy,cw,ch,lastX,lastY;
    private boolean painting,eraser;
    TextureCanvasWidget(EditorLayout.Rect bounds,TextureDocument image) {
        super(bounds.x(),bounds.y()+20,bounds.width(),bounds.height()-20,Component.literal("Texture canvas"));this.image=image;
    }
    void color(String hex){color=(int)Long.parseLong(hex.replace("#",""),16);if(hex.replace("#","").length()==6)color|=0xff000000;}
    void finish(){image.endStroke();painting=false;}
    private int viewW(){return Math.max(1,image.width()/zoom);} private int viewH(){return Math.max(1,image.height()/zoom);}
    private boolean inside(double x,double y){return x>=cx&&x<cx+cw&&y>=cy&&y<cy+ch;}
    private int px(double x){return Math.clamp(vx+(int)((x-cx)*viewW()/cw),0,image.width()-1);}
    private int py(double y){return Math.clamp(vy+(int)((y-cy)*viewH()/ch),0,image.height()-1);}
    private void paint(double x,double y){int nextX=px(x),nextY=py(y);image.paint(lastX,lastY,nextX,nextY,eraser?0:color,brush);lastX=nextX;lastY=nextY;}
    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor g,int mx,int my,float tick) {
        var font=net.minecraft.client.Minecraft.getInstance().font;
        g.enableScissor(getX(),getY(),getRight(),getBottom());
        g.fill(getX(),getY(),getRight(),getBottom(),StudioUi.BG);
        int available=Math.max(1,getHeight()-48);double scale=Math.min((getWidth()-16.0)/viewW(),available/(double)viewH());
        cw=Math.max(1,(int)(viewW()*scale));ch=Math.max(1,(int)(viewH()*scale));cx=getX()+(getWidth()-cw)/2;cy=getY()+24+(available-ch)/2;
        g.text(font,"Undo",getX()+90,getY()+7,StudioUi.MUTED,false);
        g.text(font,"Erase",getX()+126,getY()+7,eraser?StudioUi.ACCENT:StudioUi.MUTED,false);
        g.text(font,brush+"px",getX()+168,getY()+7,StudioUi.MUTED,false);
        for(int y=0;y<viewH();y++)for(int x=0;x<viewW();x++){
            int pixel=image.pixel(vx+x,vy+y);int bg=((x+vx)/4+(y+vy)/4)%2==0?0xff343438:0xff252528;
            if((pixel>>>24)<255){int a=pixel>>>24,r=((pixel>>16&255)*a+(bg>>16&255)*(255-a))/255,b=((pixel&255)*a+(bg&255)*(255-a))/255,gr=((pixel>>8&255)*a+(bg>>8&255)*(255-a))/255;pixel=0xff000000|r<<16|gr<<8|b;}
            g.fill(cx+x*cw/viewW(),cy+y*ch/viewH(),cx+(x+1)*cw/viewW(),cy+(y+1)*ch/viewH(),pixel);
        }
        g.outline(cx-1,cy-1,cw+2,ch+2,StudioUi.BORDER);
        if(cw/viewW()>=8)for(int x=0;x<=viewW();x++)g.verticalLine(cx+x*cw/viewW(),cy,cy+ch,0x33333333);
        if(ch/viewH()>=8)for(int y=0;y<=viewH();y++)g.horizontalLine(cx,cx+cw,cy+y*ch/viewH(),0x33333333);
        if(inside(mx,my))g.outline(cx+(px(mx)-vx)*cw/viewW(),cy+(py(my)-vy)*ch/viewH(),Math.max(1,brush*cw/viewW()),Math.max(1,brush*ch/viewH()),StudioUi.ACCENT);
        g.text(font,font.plainSubstrByWidth(image.width()+" × "+image.height()+"  ·  "+zoom+"x  ·  "+(painting?"Click to lift pen":"Click, move, click to paint"),getWidth()-12),getX()+6,getBottom()-12,StudioUi.MUTED,false);
        g.disableScissor();
    }
    @Override public boolean mouseClicked(MouseButtonEvent e,boolean twice) {
        if(!visible||!isMouseOver(e.x(),e.y()))return false;
        if(e.y()<getY()+22){
            finish();int x=(int)e.x()-getX();if(x>=88&&x<124)image.undo();else if(x>=124&&x<166)eraser=!eraser;else if(x>=166)brush=brush==8?1:brush*2;return x>=88;
        }
        if(!inside(e.x(),e.y())){finish();return true;}
        if(e.button()==InputConstants.MOUSE_BUTTON_RIGHT){color=image.pixel(px(e.x()),py(e.y()));return true;}
        if(e.button()!=InputConstants.MOUSE_BUTTON_LEFT)return false;
        if(painting){finish();return true;}
        image.beginStroke();painting=true;lastX=px(e.x());lastY=py(e.y());paint(e.x(),e.y());return true;
    }
    @Override public void mouseMoved(double x,double y){if(painting){if(inside(x,y))paint(x,y);else finish();}}
    @Override public boolean mouseDragged(MouseButtonEvent e,double dx,double dy){if(painting&&inside(e.x(),e.y())){paint(e.x(),e.y());return true;}return false;}
    @Override public boolean mouseScrolled(double x,double y,double h,double v){
        if(!inside(x,y))return false;finish();int ax=px(x),ay=py(y);zoom=Math.clamp(v>0?zoom*2:zoom/2,1,Math.min(32,Math.min(image.width(),image.height())));
        vx=Math.clamp(ax-viewW()/2,0,image.width()-viewW());vy=Math.clamp(ay-viewH()/2,0,image.height()-viewH());return true;
    }
    @Override public boolean keyPressed(KeyEvent event){if(event.key()==InputConstants.KEY_E&&!event.hasControlDown()){finish();eraser=!eraser;return true;}if(event.hasControlDown()&&event.key()==InputConstants.KEY_Z){finish();image.undo();return true;}return false;}
    @Override protected void updateWidgetNarration(NarrationElementOutput out){out.add(NarratedElementType.TITLE,getMessage());}
}
