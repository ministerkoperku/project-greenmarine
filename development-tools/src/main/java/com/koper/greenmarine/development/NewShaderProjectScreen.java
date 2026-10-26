package com.koper.greenmarine.development;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.nio.file.*;

final class NewShaderProjectScreen extends Screen {
    private final Screen parent;private String namespace="shader_"+java.util.UUID.randomUUID().toString().substring(0,6),name="My shaderpack",template="fullscreen",status="";
    NewShaderProjectScreen(Screen parent){super(Component.literal("New shaderpack project"));this.parent=parent;}
    @Override protected void init(){
        int top=height<240?32:52,w=width-28,half=(w-4)/2;
        var ns=addRenderableWidget(new EditBox(font,14,top,half,22,Component.literal("Namespace")));ns.setMaxLength(64);ns.setValue(namespace);ns.setResponder(v->namespace=v);
        var title=addRenderableWidget(new EditBox(font,18+half,top,half,22,Component.literal("Pack name")));title.setMaxLength(80);title.setValue(name);title.setResponder(v->name=v);
        int cw=w/3;for(int i=0;i<ShaderSdk.templates().size();i++){String choice=ShaderSdk.templates().get(i);addRenderableWidget(StudioUi.button(14+i*cw,top+30,cw-4,24,(template.equals(choice)?"• ":"")+choice,"Create the SDK's "+choice+" project with all source files and required includes.",()->{template=choice;rebuildWidgets();}));}
        addRenderableWidget(StudioUi.button(14,height-32,half,24,"Cancel","Return to studio.",this::onClose));
        addRenderableWidget(StudioUi.button(18+half,height-32,half,24,"Create project","Generate an independent editable project.",this::create));
    }
    private void create(){
        try{
            var files=ShaderSdk.starter(namespace.strip(),name.strip(),template);var ws=DevelopmentClient.workspace();
            ws.assetPath(namespace+"/.check");Files.createDirectories(ws.root());Path directory=null;
            for(int i=1;i<10000;i++)try{directory=Files.createDirectory(ws.root().resolve(namespace+(i==1?"":"-"+i)));break;}catch(FileAlreadyExistsException ignored){}
            if(directory==null)throw new IllegalArgumentException("No available project name");
            String folder=directory.getFileName().toString();
            try{for(var entry:files.entrySet())ws.create(folder+"/"+entry.getKey(),entry.getValue());}
            catch(Exception failure){try(var paths=Files.walk(directory)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}throw failure;}
            minecraft.gui.setScreen(new ShaderEditorScreen(parent,folder+"/assets/"+namespace.strip()+"/greenmarine/packs/main.json"));
        }catch(Exception e){status=e.getMessage();}
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float tick){StudioUi.background(g,width,height,title.getString(),"GreenMarine SDK");super.extractRenderState(g,x,y,tick);g.text(font,"Namespace / pack name",14,height<240?21:40,StudioUi.MUTED,false);g.text(font,font.plainSubstrByWidth(status.isEmpty()?"Fullscreen: post effects + Lua. Compute: storage + fallback. World: terrain / sky / clouds.":status,width-28),14,height-52,StudioUi.MUTED,false);}
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
