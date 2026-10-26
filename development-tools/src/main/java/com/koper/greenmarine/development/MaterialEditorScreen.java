package com.koper.greenmarine.development;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.InputConstants;
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
    private record Draft(Path root,MaterialCanvas canvas,String target,String source,String size,String roughness,String reflection,String emission,int color,int surface,int brush,boolean eraser,boolean maskOnly,int zoom,int x,int y,Preview albedo,Path destination,boolean penMode){}
    private Draft restored;
    private int tab,buildingTab;
    private boolean showLighting;
    private final java.util.List<AbstractWidget> panelWidgets=new java.util.ArrayList<>();
    private Path packFolder;
    private int[] previewPixels;
    private boolean previewDirty=true;
    private MaterialCanvas canvas=new MaterialCanvas(64,64);
    private EditBox texture,source,dimensions,roughness,reflection,emission;
    private int color=MaterialCanvas.COLORS[0],surface,brush=1;
    private boolean eraser,painting,panning,maskOnly,touchPan;
    private boolean penMode=true;
    private double panPositionX,panPositionY;
    private int zoom=1,viewX,viewY;
    private int lastX,lastY,canvasX,canvasY,canvasSize,panelX,panelWidth,controlsY;
    private record Preview(int width,int height,int[] pixels){int pixel(int x,int y){return pixels[y*width+x];}}
    private Preview albedo;
    private String status="Paint pixels, then Save pack. Enable it in Options > Resource Packs.";
    private Button surfaceButton;
    public MaterialEditorScreen(Screen parent){this(parent,Minecraft.getInstance().gameDirectory.toPath());}
    public MaterialEditorScreen(Screen parent,Path workRoot){super(Component.literal("GreenMarine material painter"));this.parent=parent;gameRoot=workRoot.toAbsolutePath().normalize();packFolder=MaterialCanvas.paths(gameRoot,"development:preview").pack();
        if(sessionDraft!=null&&sessionDraft.root().equals(gameRoot)){
            restored=sessionDraft;canvas=restored.canvas();color=restored.color();surface=restored.surface();brush=restored.brush();eraser=restored.eraser();maskOnly=restored.maskOnly();zoom=restored.zoom();viewX=restored.x();viewY=restored.y();albedo=restored.albedo();packFolder=restored.destination();penMode=restored.penMode();status="Restored unsaved session. Save pack to write PNG + JSON.";
        }
    }
    @Override protected void init(){
        painting=panning=false;canvas.endStroke();previewDirty=true;panelWidgets.clear();tab=0;buildingTab=-1;
        panelWidth=Math.min(228,Math.max(142,width/3));panelX=width-panelWidth;
        int x=panelX+10,w=panelWidth-20,contentY=44-panelScroll;
        canvasY=50;canvasSize=Math.max(16,Math.min(panelX-28,height-90));canvasX=(panelX-canvasSize)/2;
        button(8,3,58,21,"Back",this::onClose);
        button(70,3,62,21,showLighting?"Paint":"Preview",()->{finishStroke();showLighting=!showLighting;rebuildWidgets();});
        button(136,3,54,21,"Save",()->attempt(()->savePack(packFolder)));
        button(194,3,64,21,"Save as",()->minecraft.gui.setScreen(new PathBrowserScreen(this,packFolder,true,target->{try{savePack(target);}catch(Exception e){throw new IllegalStateException(e.getMessage(),e);}})));
        button(width-54,3,22,21,"-",()->zoomAt(Math.max(1,zoom/2),canvasX+canvasSize/2.0,canvasY+canvasSize/2.0));
        button(width-28,3,22,21,"+",()->zoomAt(Math.min(32,zoom*2),canvasX+canvasSize/2.0,canvasY+canvasSize/2.0));
        // Fields remain the shared serialization source while sliders provide direct editing.
        roughness=new EditBox(font,0,0,1,1,Component.literal("Roughness"));roughness.setValue(restoredValue("roughness"));
        reflection=new EditBox(font,0,0,1,1,Component.literal("Reflection"));reflection.setValue(restoredValue("reflection"));
        emission=new EditBox(font,0,0,1,1,Component.literal("Emission"));emission.setValue(restoredValue("emission"));
        buildingTab=0;
        texture=field(x,contentY+14,w,"Target texture ID",value(texture,restored==null?"minecraft:textures/block/stone.png":restored.target()));
        button(x,contentY+40,w,"Load texture",()->attempt(()->{loadAlbedo();rebuildWidgets();}));
        surfaceButton=button(x,contentY+62,w,"Surface: "+MaterialCanvas.SURFACES.get(surface),()->{
            applyMaterialSafely();surface=(surface+1)%MaterialCanvas.SURFACES.size();
            var name=MaterialCanvas.SURFACES.get(surface);
            roughness.setValue(name.equals("metal")?"0.15":name.equals("glass")?"0.05":"0.8");
            reflection.setValue(name.equals("metal")?"0.9":name.equals("glass")?"0.8":"0.05");applyMaterialSafely();rebuildWidgets();
        });
        for(int i=0;i<MaterialCanvas.COLORS.length;i++){
            int rgb=MaterialCanvas.COLORS[i];
            button(x+i*w/6,contentY+88,w/6-2,20,color==rgb?"•":"",()->attempt(()->{applyMaterial();color=rgb;eraser=false;readMaterial();rebuildWidgets();}));
        }
        addSlider(x,contentY+120,w,"Roughness",roughness);
        addSlider(x,contentY+152,w,"Reflection",reflection);
        addSlider(x,contentY+184,w,"Emission / glow",emission);
        source=field(x,contentY+284,w,"Source albedo ID (optional)",value(source,restored==null?"":restored.source()));
        dimensions=field(x,contentY+334,62,"Canvas dimensions",value(dimensions,restored==null?"64x64":restored.size()));
        button(x+68,contentY+334,Math.max(30,w-68),"Resize",()->attempt(()->{
            String[] size=dimensions.getValue().toLowerCase(java.util.Locale.ROOT).split("x");if(size.length!=2)throw new IllegalArgumentException("Use width x height, up to 256x256");
            canvas=new MaterialCanvas(Integer.parseInt(size[0].strip()),Integer.parseInt(size[1].strip()));zoom=1;viewX=viewY=0;rebuildWidgets();
        }));
        button(x,contentY+364,w,"Open saved mask",()->attempt(()->{canvas=MaterialCanvas.loadFromPack(packFolder,texture.getValue().strip());readMaterial();rebuildWidgets();}));
        buildingTab=-1;
        int toolsY=height-28,toolWidth=Math.max(24,(panelX-28)/5);
        button(8,toolsY,toolWidth,20,"Brush "+brush,()->{finishStroke();eraser=false;brush=brush==8?1:brush*2;rebuildWidgets();});
        button(12+toolWidth,toolsY,toolWidth,20,eraser?"• Erase":"Erase",()->{finishStroke();eraser=!eraser;rebuildWidgets();});
        button(16+2*toolWidth,toolsY,toolWidth,20,"Undo",()->{finishStroke();canvas.undo();previewDirty=true;});
        button(20+3*toolWidth,toolsY,toolWidth,20,penMode?"Pen":touchPan?"Pan":"Drag",()->{finishStroke();if(penMode){penMode=false;touchPan=false;}else if(!touchPan)touchPan=true;else{penMode=true;touchPan=false;}rebuildWidgets();});
        button(24+4*toolWidth,toolsY,Math.max(20,panelX-28-4*toolWidth),20,maskOnly?"Mask":"Albedo",()->{maskOnly=!maskOnly;previewDirty=true;});
        for(var widget:panelWidgets)widget.visible=widget.getY()>=44&&widget.getBottom()<=height-12;
    }
    private String restoredValue(String property){
        var material=canvas.material(color);
        if(material!=null)return Float.toString(switch(property){case "roughness"->material.roughness();case "reflection"->material.reflection();default->material.emission();});
        return "0";
    }
    private void addSlider(int x,int y,int w,String label,EditBox field){
        var slider=new MaterialSlider(x,y,w,label,Float.parseFloat(field.getValue()),v->{field.setValue(Float.toString(v));applyMaterialSafely();});
        panelWidgets.add(slider);addRenderableWidget(slider);
    }
    private int panelScroll;
    private void applyPanelScroll(){
        if(tab==0)return;
        for(var widget:panelWidgets)if(widget.visible){
            widget.setY(widget.getY()-panelScroll);
            widget.visible=widget.getY()>=80&&widget.getBottom()<=height-60;
        }
    }
    private void savePack(Path destination)throws Exception {
        applyMaterial();var saved=canvas.saveToPack(destination,texture.getValue().strip());packFolder=saved.pack();
        status="Saved to "+packFolder+". Enable this pack in Resource Packs to apply it.";
    }
    private static String value(EditBox field,String fallback){return field==null?fallback:field.getValue();}
    private EditBox field(int x,int y,int w,String label,String value){
        var box=new EditBox(font,x,y,Math.max(20,w),18,Component.literal(label));box.setMaxLength(512);box.setValue(value);box.setTooltip(Tooltip.create(Component.literal(switch(label){
            case "Roughness 0..1" -> "0 = polished, 1 = rough. Applies to the selected mask color.";
            case "Reflection 0..1" -> "Reflection strength from 0 to 1 for the selected mask color.";
            case "Emission 0..1" -> "Emissive intensity from 0 to 1 for the selected mask color.";
            default -> label;
        })));box.visible=buildingTab<0||buildingTab==tab;if(buildingTab>=0)panelWidgets.add(box);return addRenderableWidget(box);
    }
    private Button button(int x,int y,int w,String text,Runnable action){return button(x,y,w,20,text,action);}
    private Button button(int x,int y,int w,int h,String text,Runnable action){var button=StudioUi.button(x,y,w,h,text,help(text),action);button.visible=buildingTab<0||buildingTab==tab;if(buildingTab>=0)panelWidgets.add(button);return addRenderableWidget(button);}
    private static String help(String text){
        if(text.startsWith("Brush"))return "Paint with the selected material. Press to cycle 1, 2, 4 or 8 texels; this also leaves eraser mode.";
        if(text.contains("Eraser"))return "Toggle erasing. Transparent mask pixels restore the previous material or renderer fallback.";
        if(text.startsWith("Surface:"))return "Cycle the selected color's surface type. Each mask color has independent material properties.";
        return switch(text){
            case "Undo" -> "Undo the last complete stroke (up to 32).";
            case "Mask","Albedo" -> "Switch between mask colors and the source texture with a material overlay.";
            case "Pen" -> "Virtual mouse mode: click once to lower the pen, move the cursor to draw, click again to lift it. A held button is not required. Press to switch to Drag mode.";
            case "Drag" -> "Mouse mode: hold the left button while moving to draw. Press to switch to Pan mode.";
            case "Pan" -> "Hold and drag to move the viewport. Press to return to virtual-mouse Pen mode.";
            case "+","-" -> "Change canvas zoom. Scroll over the canvas also zooms around the pointer.";
            case "Load albedo" -> "Preview the source texture from mounted resources or mod fullpacks. Its pixels are preserved.";
            case "Open saved mask" -> "Open this texture's PNG mask and material JSON from the current export folder.";
            case "New canvas" -> "Create a blank canvas with the entered dimensions; clears unsaved strokes.";
            case "Save" -> "Write PNG mask and material JSON to the current pack destination. Does not enable the pack.";
            case "Save as" -> "Choose any writable pack folder, including Android Documents.";
            default -> text;
        };
    }
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
        previewDirty=true;status="Albedo loaded "+albedo.width()+"x"+albedo.height()+"; target ID stays unchanged.";
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){
        if((event.button()==InputConstants.MOUSE_BUTTON_RIGHT||event.button()==InputConstants.MOUSE_BUTTON_LEFT&&touchPan)&&inside(event.x(),event.y())){panning=true;panPositionX=viewX;panPositionY=viewY;return true;}
        if(event.button()==InputConstants.MOUSE_BUTTON_LEFT&&inside(event.x(),event.y())){
            if(penMode&&painting){finishStroke();status="Pen up. Click the canvas to draw again.";return true;}
            setFocused(null);
            attempt(()->{applyMaterial();canvas.beginStroke();painting=true;lastX=px(event.x());lastY=py(event.y());paint(lastX,lastY);status="Painting "+MaterialCanvas.SURFACES.get(surface)+" mask. Save pack to export.";});return true;
        }
        if(painting)finishStroke();
        return super.mouseClicked(event,doubleClick);
    }
    private void finishStroke(){painting=panning=false;canvas.endStroke();}
    @Override public void mouseMoved(double x,double y){
        if(penMode&&painting){if(inside(x,y))paint(px(x),py(y));else finishStroke();}
        super.mouseMoved(x,y);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event,double dx,double dy){
        if(panning){panPositionX=Math.clamp(panPositionX-dx*viewWidth()/canvasSize,0,canvas.width()-viewWidth());panPositionY=Math.clamp(panPositionY-dy*viewHeight()/canvasSize,0,canvas.height()-viewHeight());viewX=(int)Math.round(panPositionX);viewY=(int)Math.round(panPositionY);previewDirty=true;return true;}
        if(painting){paint(px(event.x()),py(event.y()));return true;}return super.mouseDragged(event,dx,dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event){if(panning){panning=false;return true;}if(painting){if(!penMode)finishStroke();return true;}return super.mouseReleased(event);}
    private boolean inside(double x,double y){return !showLighting&&tab==0&&x>=canvasX&&y>=canvasY&&x<canvasX+canvasSize&&y<canvasY+canvasSize;}
    private int viewWidth(){return Math.max(1,canvas.width()/zoom);}
    private int viewHeight(){return Math.max(1,canvas.height()/zoom);}
    private int px(double x){return Math.clamp(viewX+(int)((x-canvasX)*viewWidth()/canvasSize),0,canvas.width()-1);}
    private int py(double y){return Math.clamp(viewY+(int)((y-canvasY)*viewHeight()/canvasSize),0,canvas.height()-1);}
    private void zoomAt(int nextZoom,double x,double y){
        previewDirty=true;
        int anchorX=px(x),anchorY=py(y);zoom=nextZoom;
        viewX=Math.clamp(anchorX-(int)((x-canvasX)*viewWidth()/canvasSize),0,canvas.width()-viewWidth());
        viewY=Math.clamp(anchorY-(int)((y-canvasY)*viewHeight()/canvasSize),0,canvas.height()-viewHeight());
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(inside(x,y)){
            zoomAt(vertical>0?Math.min(32,zoom*2):Math.max(1,zoom/2),x,y);return true;
        }
        if(x>=panelX&&y>=44){panelScroll=Math.clamp(panelScroll-(int)(vertical*24),0,Math.max(0,445-height));rebuildWidgets();return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    private void paint(int x,int y){previewDirty=true;canvas.paintLine(lastX,lastY,x,y,eraser?0:0xff000000|color,brush);lastX=x;lastY=y;}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mouseX,int mouseY,float tick){
        g.fill(0,0,width,height,StudioUi.BG);g.fill(0,0,width,27,0xff303033);
        g.fill(panelX,27,width,height,StudioUi.PANEL);g.fill(panelX,27,panelX+1,height,StudioUi.BORDER);
        g.text(font,"MATERIAL",panelX+10,33,StudioUi.MUTED,false);
        g.text(font,showLighting?"Illustrative lighting preview":"Texture / material mask",12,34,StudioUi.MUTED,false);
        if(showLighting){
            int size=Math.min(128,canvasSize);int[] cube=MaterialLightingPreview.render(size,canvas.material(color));
            for(int y=0;y<size;y++)for(int x=0;x<size;){int rgb=cube[y*size+x],end=x+1;while(end<size&&cube[y*size+end]==rgb)end++;g.fill(canvasX+x*canvasSize/size,canvasY+y*canvasSize/size,canvasX+end*canvasSize/size,canvasY+(y+1)*canvasSize/size,rgb);x=end;}
        }else{
            if(previewDirty||previewPixels==null){previewPixels=MaterialPreview.render(canvas,canvasSize,viewX,viewY,viewWidth(),viewHeight(),albedo==null?null:albedo.pixels(),albedo==null?0:albedo.width(),albedo==null?0:albedo.height(),maskOnly);previewDirty=false;}
            for(int y=0;y<canvasSize;y++)for(int x=0;x<canvasSize;){int rgb=previewPixels[y*canvasSize+x],end=x+1;while(end<canvasSize&&previewPixels[y*canvasSize+end]==rgb)end++;g.fill(canvasX+x,canvasY+y,canvasX+end,canvasY+y+1,rgb);x=end;}
            if(inside(mouseX,mouseY))g.outline(canvasX+(px(mouseX)-viewX)*canvasSize/viewWidth(),canvasY+(py(mouseY)-viewY)*canvasSize/viewHeight(),Math.max(2,brush*canvasSize/viewWidth()),Math.max(2,brush*canvasSize/viewHeight()),StudioUi.ACCENT);
        }
        g.outline(canvasX-1,canvasY-1,canvasSize+2,canvasSize+2,StudioUi.BORDER);
        g.enableScissor(panelX,44,width,height-12);
        int x=panelX+10,w=panelWidth-20,y=44-panelScroll;
        g.text(font,"Target texture",x,y,StudioUi.MUTED,false);g.text(font,"SURFACE",x,y+51,StudioUi.MUTED,false);
        g.text(font,"MASK REGIONS",x,y+111,StudioUi.MUTED,false);
        g.text(font,"Source override (optional)",x,y+271,StudioUi.MUTED,false);g.text(font,"Canvas size",x,y+321,StudioUi.MUTED,false);
        for(var widget:panelWidgets)if(widget.visible)widget.extractRenderState(g,mouseX,mouseY,tick);
        for(int i=0;i<MaterialCanvas.COLORS.length;i++)g.fill(x+i*w/6+3,y+103,x+(i+1)*w/6-5,y+106,0xff000000|MaterialCanvas.COLORS[i]);
        g.disableScissor();
        for(var child:children())if(child instanceof AbstractWidget widget&&widget.visible&&!panelWidgets.contains(widget))widget.extractRenderState(g,mouseX,mouseY,tick);
        String info=status.startsWith("Error")?status:showLighting?"Adjust sliders to preview the selected region":penMode?(painting?"Pen down · click to stop":"Click, move, click to paint"):touchPan?"Pan":"Drag to paint";
        g.text(font,font.plainSubstrByWidth(info,panelX-20),10,height-42,status.startsWith("Error")?0xffff8888:StudioUi.MUTED,false);
    }
    @Override public void removed(){
        canvas.endStroke();painting=panning=false;
        if(texture!=null&&roughness!=null)sessionDraft=new Draft(gameRoot,canvas,texture.getValue(),source.getValue(),dimensions.getValue(),roughness.getValue(),reflection.getValue(),emission.getValue(),color,surface,brush,eraser,maskOnly,zoom,viewX,viewY,albedo,packFolder,penMode);
    }
    @Override public void onClose(){removed();minecraft.gui.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
