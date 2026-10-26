package com.koper.greenmarine.development;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** In-game path picker works without desktop dialogs, including on Android launchers. */
final class PathBrowserScreen extends Screen {
    private final Screen parent;
    private final Consumer<Path> selected;
    private final boolean directory;
    private boolean openIris,openAssets;
    private String search="";
    private Path folder;
    private String name,status="Choose a folder or type an absolute path.",confirmed="";
    private EditBox location,filename;
    private List<Path> entries=List.of();
    private int page;
    private boolean compact;
    PathBrowserScreen(Screen parent,Path initial,boolean directory,Consumer<Path> selected) {
        super(Component.literal(directory?"Choose pack folder":"Save file"));this.parent=parent;this.directory=directory;this.selected=selected;
        initial=initial.toAbsolutePath().normalize();folder=initial.getParent();name=initial.getFileName().toString();
        while(folder!=null&&!Files.isDirectory(folder))folder=folder.getParent();
        if(folder==null)folder=Path.of("/");
    }
    static PathBrowserScreen iris(Screen parent,Path initial,Consumer<Path> selected){
        var browser=new PathBrowserScreen(parent,initial,true,selected);
        browser.openIris=true;
        if(Files.isDirectory(initial)){browser.folder=initial.toAbsolutePath().normalize();browser.name="";}
        return browser;
    }
    static PathBrowserScreen asset(Screen parent,Path initial,Consumer<Path> selected){var browser=iris(parent,initial,selected);browser.openAssets=true;return browser;}
    @Override protected void init() {
        if(openIris){initOpen();return;}
        compact=height<240;int top=compact?28:52,listY=top+(compact?52:56),pitch=compact?20:24;
        int navigationY=height-(compact?80:102);
        location=addRenderableWidget(new EditBox(font,14,top,Math.max(30,width-88),22,Component.literal("Directory path")));
        location.setMaxLength(4096);location.setValue(folder.toString());
        addRenderableWidget(StudioUi.button(width-68,top,54,22,"Go","Open the directory typed on the left.",()->navigate(location.getValue())));
        int col=(width-28)/3;
        addRenderableWidget(StudioUi.button(14,top+28,col-4,22,"Parent","Open the parent directory.",()->{if(folder.getParent()!=null)navigate(folder.getParent().toString());}));
        addRenderableWidget(StudioUi.button(14+col,top+28,col-4,22,"Game folder","Browse the current Minecraft instance.",()->navigate(DevelopmentClient.gameRoot().toString())));
        addRenderableWidget(StudioUi.button(14+2*col,top+28,col,22,"Documents","Browse Android shared Documents, or your home directory.",()->navigate(Files.isDirectory(Path.of("/storage/emulated/0/Documents"))?"/storage/emulated/0/Documents":System.getProperty("user.home"))));
        try(var children=Files.list(folder)) {
            entries=children.filter(p->Files.isDirectory(p)||!directory).sorted(Comparator.<Path,Boolean>comparing(p->!Files.isDirectory(p)).thenComparing(p->p.getFileName().toString().toLowerCase(Locale.ROOT))).limit(512).toList();
        }catch(Exception e){entries=List.of();status="Cannot list folder: "+e.getMessage();}
        int count=Math.max(1,(navigationY-listY)/pitch),pages=Math.max(1,(entries.size()+count-1)/count);page=Math.clamp(page,0,pages-1);
        for(int i=page*count;i<Math.min(entries.size(),(page+1)*count);i++) {
            Path entry=entries.get(i);boolean dir=Files.isDirectory(entry);
            addRenderableWidget(StudioUi.button(14,listY+(i-page*count)*pitch,width-28,pitch-2,(dir?"/  ":"   ")+entry.getFileName(),entry.toString(),()->{
                if(dir)navigate(entry.toString());else{ name=entry.getFileName().toString();confirmed="";filename.setValue(name); }
            }));
        }
        int y=navigationY;
        addRenderableWidget(StudioUi.button(14,y,44,20,"<","Previous entries",()->{page--;rebuildWidgets();})).active=page>0;
        addRenderableWidget(StudioUi.button(62,y,88,20,(page+1)+" / "+pages+"  >","Next entries",()->{page++;rebuildWidgets();})).active=page+1<pages;
        filename=addRenderableWidget(new EditBox(font,14,height-(compact?58:66),width-28,22,Component.literal(directory?"Pack folder name":"File name")));
        filename.setMaxLength(4096);filename.setValue(name);filename.setResponder(v->{name=v;confirmed="";});
        addRenderableWidget(StudioUi.button(14,height-(compact?28:36),(width-32)/2,24,"Cancel","Return without saving.",this::onClose));
        addRenderableWidget(StudioUi.button(18+(width-32)/2,height-(compact?28:36),(width-32)/2,24,directory?"Save in this pack":"Save file","Existing destinations require a second press to confirm.",this::choose));
    }
    private void initOpen(){
        compact=height<240;if(compact)search="";int top=compact?28:32;
        location=addRenderableWidget(new EditBox(font,14,top,width-88,22,Component.literal("Directory path")));
        location.setMaxLength(4096);location.setValue(folder.toString());
        addRenderableWidget(StudioUi.button(width-68,top,54,22,"Go","Open the typed folder.",()->navigate(location.getValue())));
        int col=(width-28)/4;
        addRenderableWidget(StudioUi.button(14,top+28,col-3,22,"Parent","Open parent folder.",()->{if(folder.getParent()!=null)navigate(folder.getParent().toString());}));
        addRenderableWidget(StudioUi.button(14+col,top+28,col-3,22,"Packs","Open this instance's shaderpacks.",()->navigate(DevelopmentClient.gameRoot().resolve("shaderpacks").toString())));
        addRenderableWidget(StudioUi.button(14+2*col,top+28,col-3,22,"Documents","Open Android shared Documents.",()->navigate(Files.isDirectory(Path.of("/storage/emulated/0/Documents"))?"/storage/emulated/0/Documents":System.getProperty("user.home"))));
        addRenderableWidget(StudioUi.button(14+3*col,top+28,col,22,"Downloads","Open Android shared Download folder.",()->navigate(Files.isDirectory(Path.of("/storage/emulated/0/Download"))?"/storage/emulated/0/Download":System.getProperty("user.home")+"/Downloads")));
        int listY=top+56;
        if(!compact){
            var filter=addRenderableWidget(new EditBox(font,14,listY,width-28,20,Component.literal("Search names")));
            filter.setMaxLength(256);filter.setValue(search);
            filter.setResponder(value->{if(!value.equals(search)){search=value;page=0;rebuildWidgets();var focus=children().stream().filter(c->c instanceof EditBox box&&box.getMessage().getString().equals("Search names")).findFirst();focus.ifPresent(this::setFocused);}});
            listY+=26;
        }
        try(var children=Files.list(folder)){
            String query=search.toLowerCase(Locale.ROOT);
            entries=children.filter(p->Files.isDirectory(p)||openAssets||p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                .filter(p->p.getFileName().toString().toLowerCase(Locale.ROOT).contains(query))
                .sorted(Comparator.<Path,Boolean>comparing(p->!Files.isDirectory(p)).thenComparing(p->p.getFileName().toString().toLowerCase(Locale.ROOT))).limit(512).toList();
        }catch(Exception e){entries=List.of();status="Cannot read folder: "+e.getMessage();}
        int pitch=compact?20:24,nav=height-58,count=Math.max(1,(nav-listY)/pitch),pages=Math.max(1,(entries.size()+count-1)/count);
        page=Math.clamp(page,0,pages-1);
        for(int i=page*count;i<Math.min(entries.size(),(page+1)*count);i++){
            Path entry=entries.get(i);boolean dir=Files.isDirectory(entry);
            addRenderableWidget(StudioUi.button(14,listY+(i-page*count)*pitch,width-28,pitch-2,(dir?"/  ":openAssets?"FILE  ":"ZIP  ")+entry.getFileName(),entry.toString(),()->{if(dir)navigate(entry.toString());else selectOpen(entry);}));
        }
        addRenderableWidget(StudioUi.button(14,nav,48,20,"<","Previous",()->{page--;rebuildWidgets();})).active=page>0;
        addRenderableWidget(StudioUi.button(66,nav,88,20,(page+1)+" / "+pages+" >","Next",()->{page++;rebuildWidgets();})).active=page+1<pages;
        addRenderableWidget(StudioUi.button(14,height-28,(width-32)/2,24,"Cancel","Return without importing.",this::onClose));
        addRenderableWidget(StudioUi.button(18+(width-32)/2,height-28,(width-32)/2,24,"Import this folder","Use the current directory as an Iris pack.",()->selectOpen(folder))).active=!openAssets;
    }
    private void selectOpen(Path path){
        try{selected.accept(path);if(minecraft.gui.screen()==this)minecraft.gui.setScreen(parent);}catch(Exception e){status="Cannot select: "+e.getMessage();}
    }
    private void navigate(String value){
        try{Path next=Path.of(value).toAbsolutePath().normalize();if(!Files.isDirectory(next))throw new IllegalArgumentException("Directory does not exist");folder=next;search="";if(directory)name="";page=0;confirmed="";status="Choose a folder or type an absolute path.";rebuildWidgets();}
        catch(Exception e){status="Cannot open: "+e.getMessage();}
    }
    private void choose(){
        try {
            Path base=Path.of(location.getValue()).toAbsolutePath().normalize();
            if(!Files.isDirectory(base))throw new IllegalArgumentException("Open an existing parent folder first");
            if(name.isBlank()&&!directory)throw new IllegalArgumentException("Enter a name or absolute destination path");
            Path target=base.resolve(name).normalize();
            if(Files.exists(target)&&!confirmed.equals(target.toString())){confirmed=target.toString();status="Destination exists. Press Save again to confirm replacement.";return;}
            selected.accept(target);
            minecraft.gui.setScreen(parent);
        }catch(Exception e){status="Save failed: "+e.getMessage();}
    }
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void extractRenderState(GuiGraphicsExtractor g,int mx,int my,float tick){
        if(openIris){g.fill(0,0,width,height,StudioUi.BG);g.text(font,openAssets?"Choose project asset":"Choose Iris ZIP or folder",14,10,StudioUi.TEXT,false);}
        else StudioUi.background(g,width,height,title.getString(),"Your files / Your destination");
        super.extractRenderState(g,mx,my,tick);
        if(!compact&&!openIris)g.text(font,font.plainSubstrByWidth(status,width-172),158,height-97,StudioUi.MUTED,false);
        if(my>=height-102)g.setTooltipForNextFrame(Component.literal(status),mx,my);
        if(!compact&&!openIris)g.text(font,directory?"Pack folder name or absolute path":"File name or absolute path",14,height-78,StudioUi.MUTED,false);
    }
}
