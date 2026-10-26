package com.koper.greenmarine.development.probe;

import com.koper.greenmarine.development.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.input.*;
import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.*;
import java.util.*;

/** Runs only in an isolated GUI fixture; never packaged in the authoring addon. */
final class WorkspaceGuiProbe {
    private static final Path OUTPUT=Path.of(System.getProperty("greenmarine.development.probe.output"));
    private static final Map<String,Object> evidence=new LinkedHashMap<>();
    private static int stage,ticks;
    private static boolean done,captured;
    private static String pendingCapture;
    private static long started=System.nanoTime();
    private static ShaderEditorScreen editor;
    private static MaterialEditorScreen material;
    private static String source;
    static void tick(Minecraft client){
        if(done)return;
        try{
            if((System.nanoTime()-started)/1e9>160)throw new IllegalStateException("Workspace probe timeout at "+stage);
            ticks++;
            if(pendingCapture!=null&&ticks>=8){String name=pendingCapture;pendingCapture=null;screenshot(client,name);}
            if(ticks<12)return;
            switch(stage){
                case 0->{
                    if(client.gui.overlay()!=null)return;
                    if(client.gui.screen()!=null&&client.gui.screen().getClass().getSimpleName().equals("AccessibilityOnboardingScreen")){client.gui.setScreen(new TitleScreen());return;}
                    if(!(client.gui.screen() instanceof TitleScreen))return;
                    source=DevelopmentClient.workspace().createExample();editor=new ShaderEditorScreen(new WorkbenchScreen(null),source);client.gui.setScreen(editor);layout(editor);next();
                }
                case 1->{
                    sidebarDrag(editor);
                    var initialRows=(List<?>)get(editor,"treeRows");int fileRow=-1;
                    for(int i=0;i<initialRows.size();i++){Object entry=call(initialRows.get(i),"entry",new Class[]{});if(call(entry,"path",new Class[]{}).equals(source)){fileRow=i;break;}}
                    require(fileRow>=0,"Source row missing");
                    editor.mouseClicked(new MouseButtonEvent(100,48+fileRow*18+8,new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0)),false);
                    require(get(editor,"inlineKind")==null,"Single click entered rename instead of opening the file");
                    editor.keyPressed(new KeyEvent(InputConstants.KEY_N,0,InputConstants.MOD_CONTROL));
                    require(get(editor,"inlineKind").equals("file"),"Ctrl+N did not start inline creation");
                    ((EditBox)get(editor,"inline")).setValue("notes.txt");editor.keyPressed(new KeyEvent(InputConstants.KEY_RETURN,0,0));
                    require(((String)get(editor,"path")).endsWith("/notes.txt"),"New source was not opened");
                    call(editor,"startInline",new Class[]{String.class},"folder");((EditBox)get(editor,"inline")).setValue("empty");editor.keyPressed(new KeyEvent(InputConstants.KEY_RETURN,0,0));
                    require(((String)get(editor,"selected")).endsWith("/empty"),"Empty folder missing");
                    // Use the explorer's actual double-click route to enter rename.
                    var rows=(List<?>)get(editor,"treeRows");String selected=(String)get(editor,"selected");int rowIndex=-1;
                    for(int i=0;i<rows.size();i++){Object entry=call(rows.get(i),"entry",new Class[]{});if(call(entry,"path",new Class[]{}).equals(selected)){rowIndex=i;break;}}
                    require(rowIndex>=0,"Empty folder omitted from explorer");
                    set(editor,"treeScroll",Math.max(0,rowIndex-3));call(editor,"rebuildWidgets",new Class[]{});
                    int scroll=(int)get(editor,"treeScroll");editor.mouseClicked(new MouseButtonEvent(35,48+(rowIndex-scroll)*18+8,new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0)),true);
                    require("rename".equals(get(editor,"inlineKind")),"Double click did not start rename");
                    ((EditBox)get(editor,"inline")).setValue("renamed");editor.keyPressed(new KeyEvent(InputConstants.KEY_RETURN,0,0));
                    require(((String)get(editor,"selected")).endsWith("/renamed"),"Folder rename failed");
                    evidence.put("inlineFileFolderAndDoubleClickRename",true);call(editor,"openFile",new Class[]{String.class},source);capture(client,"workspace.png");next();
                }
                case 2->{
                    if(!captured)return;
                    editor.keyPressed(new KeyEvent(InputConstants.KEY_BACKSLASH,0,InputConstants.MOD_CONTROL));
                    var panes=editor.children().stream().filter(c->c instanceof CodeEditBox).map(c->(CodeEditBox)c).toList();require(panes.size()==2,"Split did not create two groups");
                    String draft=panes.get(1).getValue()+"\n// Shared split draft\n";panes.get(1).setValue(draft);require(panes.get(0).getValue().equals(draft),"Split documents not synchronized");
                    splitDrag(editor);
                    layout(editor);capture(client,"workspace-split.png");evidence.put("sharedSplitDraftAndResponsiveBounds",true);next();
                }
                case 3->{
                    if(!captured)return;
                    call(editor,"startInline",new Class[]{String.class},"file");((EditBox)get(editor,"inline")).setValue("texture.png");editor.keyPressed(new KeyEvent(InputConstants.KEY_RETURN,0,0));
                    require(((String)get(editor,"path")).endsWith(".png"),"PNG not opened in workspace");next();
                }
                case 4->{
                    Object canvas=editor.children().stream().filter(c->c.getClass().getSimpleName().equals("TextureCanvasWidget")).findFirst().orElseThrow();
                    int x=(int)get(canvas,"cx"),y=(int)get(canvas,"cy"),w=(int)get(canvas,"cw"),h=(int)get(canvas,"ch");
                    var painter=(net.minecraft.client.gui.components.AbstractWidget)canvas;
                    editor.mouseClicked(new MouseButtonEvent(x+w*.25,y+h*.25,new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0)),false);editor.mouseMoved(x+w*.75,y+h*.75);editor.mouseClicked(new MouseButtonEvent(x+w*.75,y+h*.75,new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0)),false);
                    Object doc=get(canvas,"image");require((boolean)call(doc,"dirty",new Class[]{}),"PNG pen did not change pixels");
                    require((int)call(doc,"pixel",new Class[]{int.class,int.class},20,20)!=0,"Screen did not route virtual pen movement");capture(client,"workspace-texture.png");evidence.put("embeddedTexturePainting",true);next();
                }
                case 5->{
                    if(!captured)return;material=new MaterialEditorScreen(editor);client.gui.setScreen(material);layout(material);
                    button(material,"Preview").onPress(new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0));next();
                }
                case 6->{
                    var sliders=material.children().stream().filter(c->c.getClass().getSimpleName().equals("MaterialSlider")).map(c->(AbstractWidget)c).toList();
                    require(sliders.size()==3,"Expected direct material sliders");var slider=sliders.get(0);
                    slider.mouseClicked(new MouseButtonEvent(slider.getRight()-4,slider.getY()+18,new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0)),false);
                    capture(client,"workspace-material.png");evidence.put("materialInspectorSlidersAndCubePreview",true);next();
                }
                case 7->{if(!captured)return;finish(client,null);}
            }
        }catch(Throwable e){finish(client,e);}
    }
    private static void sidebarDrag(ShaderEditorScreen screen)throws Exception {
        var before=(CodeEditBox)get(screen,"editor");String original=before.getValue();
        before.setValue(original+"\n// Sidebar drag keeps this draft\n");before.moveCursor(3);
        var textField=get(before,"field");
        call(textField,"setSelecting",new Class[]{boolean.class},true);
        call(textField,"seekCursor",new Class[]{Whence.class,int.class},Whence.ABSOLUTE,10);
        call(textField,"setSelecting",new Class[]{boolean.class},false);
        String selection=(String)call(textField,"getSelectedText",new Class[]{});
        int sidebar=(int)get(screen,"sidebar");
        var left=new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0);
        require(screen.mouseClicked(new MouseButtonEvent(sidebar,100,left),false),"Sidebar handle did not capture click");
        require((int)get(screen,"dragging")==1,"Sidebar drag not started");
        for(int i=1;i<=40;i++){
            screen.mouseDragged(new MouseButtonEvent(sidebar+i,100,left),1,0);
            require(get(screen,"editor")==before,"Sidebar drag rebuilt the focused editor during pointer movement");
            require(screen.getFocused()==before,"Sidebar drag changed text input focus during pointer movement");
            require(selection.equals(call(get(before,"field"),"getSelectedText",new Class[]{})),"Sidebar rewrap lost selected text");
        }
        screen.mouseReleased(new MouseButtonEvent(sidebar+40,100,left));
        require((int)get(screen,"dragging")==0,"Sidebar drag stuck after release");
        var after=(CodeEditBox)get(screen,"editor");
        require(after==before,"Sidebar release replaced the focused editor");
        require(after.getValue().equals(original+"\n// Sidebar drag keeps this draft\n"),"Sidebar drag lost unsaved text");
        require(after.cursorPosition()==10,"Sidebar drag lost caret");
        screen.mouseClicked(new MouseButtonEvent((int)get(screen,"sidebar"),100,left),false);
        screen.mouseDragged(new MouseButtonEvent(sidebar,100,left),-40,0);
        screen.mouseReleased(new MouseButtonEvent(sidebar,100,left));
        ((CodeEditBox)get(screen,"editor")).setValue(original);
        evidence.put("sidebarDragPreservesFocusDraftAndCaret",true);
    }
    private static void splitDrag(ShaderEditorScreen screen)throws Exception {
        var panes=screen.children().stream().filter(c->c instanceof CodeEditBox).map(c->(CodeEditBox)c).toList();
        var active=(CodeEditBox)get(screen,"editor");var focused=screen.getFocused();
        int border=panes.get(0).getRight()+1;
        var left=new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,0);
        screen.mouseClicked(new MouseButtonEvent(border,110,left),false);
        require((int)get(screen,"dragging")==2,"Split divider did not capture click");
        for(int i=1;i<=20;i++){
            screen.mouseDragged(new MouseButtonEvent(border+i,110,left),1,0);
            require(get(screen,"editor")==active&&screen.getFocused()==focused,"Split drag restarted text input");
            require(screen.children().containsAll(panes),"Split drag replaced an editor group");
        }
        screen.mouseReleased(new MouseButtonEvent(border+20,110,left));
        require((int)get(screen,"dragging")==0,"Split divider stuck after release");
        evidence.put("splitDragPreservesEditorWidgetsAndFocus",true);
    }
    private static void next(){stage++;ticks=0;}
    private static Button button(Screen s,String label){return s.children().stream().filter(c->c instanceof Button b&&b.getMessage().getString().equals(label)).map(c->(Button)c).findFirst().orElseThrow();}
    private static Object get(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static Object call(Object o,String name,Class<?>[] types,Object... args)throws Exception{
        Class<?> owner=o.getClass();java.lang.reflect.Method m=null;while(owner!=null){try{m=owner.getDeclaredMethod(name,types);break;}catch(NoSuchMethodException e){owner=owner.getSuperclass();}}
        if(m==null)throw new NoSuchMethodException(name);m.setAccessible(true);return m.invoke(o,args);
    }
    private static void layout(Screen screen){
        int w=screen.width,h=screen.height;
        for(int[] size:new int[][]{{320,180},{480,270},{854,480}}){screen.init(size[0],size[1]);for(var c:screen.children())if(c instanceof AbstractWidget widget&&widget.visible)require(widget.getX()>=0&&widget.getY()>=0&&widget.getRight()<=size[0]&&widget.getBottom()<=size[1],"Offscreen "+widget.getMessage()+" at "+size[0]+"x"+size[1]);}
        screen.init(w,h);
    }
    private static void capture(Minecraft client,String name){captured=false;pendingCapture=name;}
    private static void screenshot(Minecraft client,String name){Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(),image->{try{Files.createDirectories(OUTPUT);image.writeToFile(OUTPUT.resolve(name));captured=true;}catch(Exception e){finish(client,e);}finally{image.close();}});}
    private static void require(boolean result,String message){if(!result)throw new IllegalStateException(message);}
    private static void finish(Minecraft client,Throwable failure){
        if(done)return;done=true;evidence.put("outcome",failure==null?"passed":"failed");evidence.put("stage",stage);
        if(failure!=null){evidence.put("error",failure.toString());failure.printStackTrace();}
        try{Files.createDirectories(OUTPUT);Files.writeString(OUTPUT.resolve("workspace-result.json"),new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(evidence));}catch(Exception e){e.printStackTrace();}
        client.stop();
    }
}
