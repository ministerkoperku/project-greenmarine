package com.koper.greenmarine.development;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.NativeImage;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Path;

/** Developer addon screen; exports regular resource-pack assets without altering game jars. */
public final class MaterialEditorScreen extends Screen {
    private final Screen parent;
    private final Path gameRoot;
    private static Draft sessionDraft;
    private record Draft(Path root,MaterialCanvas canvas,String target,String source,String size,String roughness,String reflection,String emission,int color,int surface,int brush,boolean eraser,boolean maskOnly,int zoom,int x,int y,Preview albedo){}
    private Draft restored;
    private MaterialCanvas canvas=new MaterialCanvas(64,64);
    private EditBox texture,source,dimensions,roughness,reflection,emission;
    private int color=MaterialCanvas.COLORS[0],surface,brush=1;
    private boolean eraser,painting,panning,maskOnly,touchPan;
    private double panPositionX,panPositionY;
    private int zoom=1,viewX,viewY;
    private int lastX,lastY,canvasX,canvasY,canvasSize,panelX,panelWidth,controlsY;
    private record Preview(int width,int height,int[] pixels){int pixel(int x,int y){return pixels[y*width+x];}}
    private Preview albedo;
    private String status="Paint pixels, then Save pack. Enable it in Options > Resource Packs.";
    private Button surfaceButton;
    public MaterialEditorScreen(Screen parent){this(parent,Minecraft.getInstance().gameDirectory.toPath());}
    public MaterialEditorScreen(Screen parent,Path workRoot){super(Component.literal("GreenMarine material painter"));this.parent=parent;gameRoot=workRoot.toAbsolutePath().normalize();
        if(sessionDraft!=null&&sessionDraft.root().equals(gameRoot)){
            restored=sessionDraft;canvas=restored.canvas();color=restored.color();surface=restored.surface();brush=restored.brush();eraser=restored.eraser();maskOnly=restored.maskOnly();zoom=restored.zoom();viewX=restored.x();viewY=restored.y();albedo=restored.albedo();status="Restored unsaved session. Save pack to write PNG + JSON.";
        }
    }
    @Override protected void init(){
        controlsY=Math.max(80,Math.min(91,height-158));
        canvasX=8;canvasY=104;panelWidth=Math.min(204,Math.max(150,width/2));panelX=width-panelWidth-8;
        canvasSize=Math.max(24,Math.min(256,Math.min(panelX-16,height-canvasY-58)));
        texture=field(8,28,width-16,"Texture ID",value(texture,restored==null?"mechanics_dream:textures/craftbot.png":restored.target()));
        source=field(8,58,width-16,"Source albedo ID (optional)",value(source,restored==null?"":restored.source()));
        dimensions=field(panelX,controlsY,62,"Canvas dimensions",value(dimensions,restored==null?"64x64":restored.size()));
        button(panelX+66,controlsY,66,"New",()->attempt(()->{
            String[] wh=dimensions.getValue().toLowerCase(java.util.Locale.ROOT).split("x");if(wh.length!=2)throw new IllegalArgumentException("Dimensions: 64x64, 128x128; maximum 256");
            canvas=new MaterialCanvas(Integer.parseInt(wh[0].strip()),Integer.parseInt(wh[1].strip()));zoom=1;viewX=viewY=0;status="New transparent canvas; previous unsaved strokes cleared.";
        }));
        button(panelX+136,controlsY,panelWidth-136,"Load",()->attempt(()->{
            canvas=MaterialCanvas.load(gameRoot,texture.getValue().strip());dimensions.setValue(canvas.width()+"x"+canvas.height());zoom=1;viewX=viewY=0;readMaterial();status="Loaded saved PNG + JSON.";
        }));
        surfaceButton=button(panelX,controlsY+22,panelWidth,"Surface: "+MaterialCanvas.SURFACES.get(surface),()->{
            surface=(surface+1)%MaterialCanvas.SURFACES.size();surfaceButton.setMessage(Component.literal("Surface: "+MaterialCanvas.SURFACES.get(surface)));applyMaterialSafely();
        });
        int third=(panelWidth-8)/3;
        roughness=field(panelX,controlsY+55,third,"Roughness 0..1",value(roughness,restored==null?"0.8":restored.roughness()));
        reflection=field(panelX+third+4,controlsY+55,third,"Reflection 0..1",value(reflection,restored==null?"0.05":restored.reflection()));
        emission=field(panelX+2*(third+4),controlsY+55,panelWidth-2*(third+4),"Emission 0..1",value(emission,restored==null?"0":restored.emission()));
        for(int i=0;i<MaterialCanvas.COLORS.length;i++){
            int rgb=MaterialCanvas.COLORS[i];button(panelX+i*(panelWidth/6),controlsY+77,panelWidth/6-2,18,Integer.toString(i+1),()->attempt(()->{
                applyMaterial();color=rgb;eraser=false;readMaterial();
            })).setTooltip(Tooltip.create(Component.literal(String.format(java.util.Locale.ROOT,"Palette #%06x",rgb))));
        }
        button(panelX,controlsY+99,panelWidth/2-2,"Eraser",()->{eraser=true;status="Eraser: transparent pixels keep earlier material/fallback.";});
        button(panelX+panelWidth/2+2,controlsY+99,panelWidth/2-2,"Brush: "+brush,()->{
            brush=brush==1?2:brush==2?4:brush==4?8:1;rebuildWidgets();
        });
        int touchY=canvasY+canvasSize+3,touchColumn=canvasSize/3;
        button(canvasX,touchY,touchColumn-2,18,"-",()->zoomAt(Math.max(1,zoom/2),canvasX+canvasSize/2.0,canvasY+canvasSize/2.0));
        button(canvasX+touchColumn,touchY,touchColumn-2,18,"+",()->zoomAt(Math.min(32,zoom*2),canvasX+canvasSize/2.0,canvasY+canvasSize/2.0));
        var panButton=button(canvasX+2*touchColumn,touchY,canvasSize-2*touchColumn,18,touchPan?"Pan":"Paint",()->{touchPan=!touchPan;rebuildWidgets();});
        panButton.setTooltip(Tooltip.create(Component.literal("Touch mode: "+(touchPan?"drag to pan; tap for painting":"drag to paint; tap for panning")+". Right mouse drag also pans.")));
        int bottom=height-34,col=(width-20)/5;
        button(8,bottom,col-4,"Undo",()->{canvas.undo();status="Undid last paint stroke.";});
        button(8+col,bottom,col-4,maskOnly?"Albedo view":"Mask view",()->{maskOnly=!maskOnly;rebuildWidgets();});
        button(8+2*col,bottom,col-4,"Load albedo",()->attempt(this::loadAlbedo));
        button(8+3*col,bottom,col-4,"Save pack",()->attempt(()->{
            applyMaterial();canvas.save(gameRoot,texture.getValue().strip());
            status="Saved. Enable GreenMarine Development Materials in Resource Packs; reload F3+T.";
        }));
        button(8+4*col,bottom,col-4,"Done",this::onClose);
    }
    private static String value(EditBox field,String fallback){return field==null?fallback:field.getValue();}
    private EditBox field(int x,int y,int w,String label,String value){
        var box=new EditBox(font,x,y,Math.max(20,w),18,Component.literal(label));box.setMaxLength(512);box.setValue(value);box.setTooltip(Tooltip.create(Component.literal(label)));return addRenderableWidget(box);
    }
    private Button button(int x,int y,int w,String text,Runnable action){return button(x,y,w,20,text,action);}
    private Button button(int x,int y,int w,int h,String text,Runnable action){return addRenderableWidget(Button.builder(Component.literal(text),b->action.run()).bounds(x,y,Math.max(1,w),h).build());}
    private void applyMaterial(){canvas.setMaterial(color,new MaterialCanvas.Material(MaterialCanvas.SURFACES.get(surface),Float.parseFloat(roughness.getValue()),Float.parseFloat(reflection.getValue()),Float.parseFloat(emission.getValue())));}
    private void applyMaterialSafely(){attempt(this::applyMaterial);}
    private void readMaterial(){
        var material=canvas.material(color);surface=MaterialCanvas.SURFACES.indexOf(material.surface());
        roughness.setValue(Float.toString(material.roughness()));reflection.setValue(Float.toString(material.reflection()));emission.setValue(Float.toString(material.emission()));
        surfaceButton.setMessage(Component.literal("Surface: "+material.surface()));
    }
    private interface Action {void run()throws Exception;}
    private void attempt(Action action){try{action.run();}catch(Exception e){status="Error: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}}
    private void loadAlbedo()throws Exception{
        String target=MaterialCanvas.canonicalTexture(texture.getValue().strip());
        String sourceId=source.getValue().strip();if(sourceId.isEmpty())sourceId=target;
        var mounted=minecraft.getResourceManager().getResource(Identifier.parse(sourceId));byte[] bytes;
        if(mounted.isPresent()){
            try(var input=mounted.get().open()){bytes=MaterialCanvas.previewPng(MaterialCanvas.readPreviewBytes(input));}
        }else{
            var roots=FabricLoader.getInstance().getAllMods().stream().flatMap(mod->mod.getRootPaths().stream()).toList();
            bytes=MaterialCanvas.fullpackAlbedo(roots,target).orElseThrow(()->new IllegalArgumentException("Albedo unavailable in mounted resources or mod fullpacks."));
        }
        try(var image=NativeImage.read(bytes)){
            // PNG IHDR is checked before decoding; check the decoder's dimensions as well.
            if(image.getWidth()>256||image.getHeight()>256)throw new IllegalArgumentException("Preview dimensions must be 1..256");
            albedo=new Preview(image.getWidth(),image.getHeight(),image.getPixels());
        }
        status="Albedo loaded "+albedo.width()+"x"+albedo.height()+"; target ID stays unchanged.";
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){
        if((event.button()==1||event.button()==0&&touchPan)&&inside(event.x(),event.y())){panning=true;panPositionX=viewX;panPositionY=viewY;return true;}
        if(event.button()==0&&inside(event.x(),event.y())){
            attempt(()->{applyMaterial();canvas.beginStroke();painting=true;lastX=px(event.x());lastY=py(event.y());paint(lastX,lastY);});return true;
        }
        return super.mouseClicked(event,doubleClick);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){
        if(panning){panPositionX=Math.clamp(panPositionX-dx*viewWidth()/canvasSize,0,canvas.width()-viewWidth());panPositionY=Math.clamp(panPositionY-dy*viewHeight()/canvasSize,0,canvas.height()-viewHeight());viewX=(int)Math.round(panPositionX);viewY=(int)Math.round(panPositionY);return true;}
        if(painting){paint(px(event.x()),py(event.y()));return true;}return super.mouseDragged(event,dx,dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event){if(panning){panning=false;return true;}if(painting){painting=false;canvas.endStroke();return true;}return super.mouseReleased(event);}
    private boolean inside(double x,double y){return x>=canvasX&&y>=canvasY&&x<canvasX+canvasSize&&y<canvasY+canvasSize;}
    private int viewWidth(){return Math.max(1,canvas.width()/zoom);}
    private int viewHeight(){return Math.max(1,canvas.height()/zoom);}
    private int px(double x){return Math.clamp(viewX+(int)((x-canvasX)*viewWidth()/canvasSize),0,canvas.width()-1);}
    private int py(double y){return Math.clamp(viewY+(int)((y-canvasY)*viewHeight()/canvasSize),0,canvas.height()-1);}
    private void zoomAt(int nextZoom,double x,double y){
        int anchorX=px(x),anchorY=py(y);zoom=nextZoom;
        viewX=Math.clamp(anchorX-(int)((x-canvasX)*viewWidth()/canvasSize),0,canvas.width()-viewWidth());
        viewY=Math.clamp(anchorY-(int)((y-canvasY)*viewHeight()/canvasSize),0,canvas.height()-viewHeight());
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(inside(x,y)){
            zoomAt(vertical>0?Math.min(32,zoom*2):Math.max(1,zoom/2),x,y);return true;
        }
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    private void paint(int x,int y){canvas.paintLine(lastX,lastY,x,y,eraser?0:0xff000000|color,brush);lastX=x;lastY=y;}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float tick){
        super.extractRenderState(g,mouseX,mouseY,tick);
        g.centeredText(font,title,width/2,5,0xffffffff);
        g.text(font,"Target texture ID (exact renderer ID)",8,18,0xffdddddd);
        g.text(font,"Source albedo ID (optional; blank also searches mod fullpacks)",8,48,0xffdddddd);
        g.text(font,"Canvas / WxH",8,88,0xffcccccc);
        g.text(font,"Roughness    Reflection    Emission",panelX,controlsY+45,0xffcccccc);
        int cellsX=Math.min(viewWidth(),canvasSize),cellsY=Math.min(viewHeight(),canvasSize);
        for(int y=0;y<cellsY;y++)for(int x=0;x<cellsX;x++){
            int cx=viewX+x*viewWidth()/cellsX,cy=viewY+y*viewHeight()/cellsY;
            int background=((cx/4+cy/4)%2==0)?0xff555555:0xff777777;
            if(!maskOnly&&albedo!=null)background=0xff000000|albedo.pixel(cx*albedo.width()/canvas.width(),cy*albedo.height()/canvas.height())&0xffffff;
            int mask=canvas.pixel(cx,cy),shown=mask==0?background:maskOnly?mask:blend(background,mask);
            g.fill(canvasX+x*canvasSize/cellsX,canvasY+y*canvasSize/cellsY,canvasX+(x+1)*canvasSize/cellsX,canvasY+(y+1)*canvasSize/cellsY,shown);
        }
        if(canvasSize/viewWidth()>=5&&canvasSize/viewHeight()>=5){
            for(int x=0;x<=viewWidth();x++)g.verticalLine(canvasX+x*canvasSize/viewWidth(),canvasY,canvasY+canvasSize,0x44777777);
            for(int y=0;y<=viewHeight();y++)g.horizontalLine(canvasX,canvasX+canvasSize,canvasY+y*canvasSize/viewHeight(),0x44777777);
        }
        g.outline(canvasX-1,canvasY-1,canvasSize+2,canvasSize+2,eraser?0xffff8888:0xff000000|color);
        for(int i=0;i<MaterialCanvas.COLORS.length;i++)g.fill(panelX+i*(panelWidth/6)+3,controlsY+94,panelX+i*(panelWidth/6)+panelWidth/6-5,controlsY+98,0xff000000|MaterialCanvas.COLORS[i]);
        g.text(font,font.plainSubstrByWidth(status,width-16),8,height-11,status.startsWith("Error")?0xffff8888:0xffbbdddd);
        if(mouseY>=height-14)g.setTooltipForNextFrame(Component.literal(status),mouseX,mouseY);
        if(inside(mouseX,mouseY))g.setTooltipForNextFrame(Component.literal(px(mouseX)+", "+py(mouseY)+" | "+canvas.width()+"x"+canvas.height()+" | "+zoom+"x; scroll zoom; right-drag pan"),mouseX,mouseY);
    }
    private static int blend(int a,int b){return 0xff000000|((((a>>16&255)+(b>>16&255))/2)<<16)|((((a>>8&255)+(b>>8&255))/2)<<8)|((a&255)+(b&255))/2;}
    @Override public void removed(){
        canvas.endStroke();painting=panning=false;
        if(texture!=null&&roughness!=null)sessionDraft=new Draft(gameRoot,canvas,texture.getValue(),source.getValue(),dimensions.getValue(),roughness.getValue(),reflection.getValue(),emission.getValue(),color,surface,brush,eraser,maskOnly,zoom,viewX,viewY,albedo);
    }
    @Override public void onClose(){removed();minecraft.gui.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
