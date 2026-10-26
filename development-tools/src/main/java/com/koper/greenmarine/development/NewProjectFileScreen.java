package com.koper.greenmarine.development;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Consumer;

final class NewProjectFileScreen extends Screen {
    private final Screen parent;private final ShaderProject project;private final Consumer<String> open;
    private String path,kind="scene",status="",custom;
    NewProjectFileScreen(Screen parent,ShaderProject project,Consumer<String> open){super(Component.literal("New project file"));this.parent=parent;this.project=project;this.open=open;path=defaultPath();}
    private String namespace(){try{return project.files().stream().filter(p->p.contains("/greenmarine/packs/")).findFirst().orElseThrow().split("/assets/")[1].split("/")[0];}catch(Exception e){return "demo";}}
    private String defaultPath(){String ns=namespace();return switch(kind){case "compute"->"assets/"+ns+"/shaders/compute/new.csh";case "include"->"assets/"+ns+"/shaders/include/new.glsl";case "vertex"->"assets/"+ns+"/shaders/core/new.vsh";case "lua"->"assets/"+ns+"/scripts/new.lua";case "material"->"assets/"+ns+"/greenmarine/materials/new.json";case "manifest"->"assets/"+ns+"/greenmarine/packs/new.json";default->"assets/"+ns+"/shaders/core/new.fsh";};}
    @Override protected void init(){
        int y=height<240?32:52;var field=addRenderableWidget(new EditBox(font,14,y,width-28,22,Component.literal("Project-relative file path")));field.setMaxLength(1024);field.setValue(path);field.setResponder(v->path=v);
        var kinds=List.of("scene","lightmap","compute","include","vertex","world","lua","material","manifest");int cw=(width-28)/3;
        for(int i=0;i<kinds.size();i++){String value=kinds.get(i);addRenderableWidget(StudioUi.button(14+(i%3)*cw,y+28+(i/3)*24,cw-4,22,(kind.equals(value)?"• ":"")+value,"Use SDK "+value+" source template.",()->{kind=value;custom=null;path=defaultPath();rebuildWidgets();}));}
        addRenderableWidget(StudioUi.button(14,height-30,(width-32)/2,24,"Cancel","Keep the project unchanged.",this::onClose));
        addRenderableWidget(StudioUi.button(18+(width-32)/2,height-30,(width-32)/2,24,"Create file","Create this source without replacing an existing file.",()->{
            try{String text=custom==null?ShaderSdk.entries().stream().filter(e->e.id().equals("snippet/"+kind)).findFirst().orElseThrow().text().replace("demo:",namespace()+":"):custom;
                String created=project.create(path.strip(),text);open.accept(created);minecraft.gui.setScreen(parent);
            }catch(Exception e){status=e.getMessage();}
        }));
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float tick){StudioUi.background(g,width,height,title.getString(),"Project / "+project.folder());super.extractRenderState(g,x,y,tick);if(!status.isEmpty())g.setTooltipForNextFrame(Component.literal(status),x,y);}
    @Override public void onClose(){minecraft.gui.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
