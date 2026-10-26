package com.koper.greenmarine.development;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Session buffers are shared by every document in a project, including export/validation. */
final class ShaderProject {
    record Document(String path,String baseline,String text,int cursor){boolean dirty(){return !baseline.equals(text);}}
    private final ShaderWorkspace workspace;
    private final String folder;
    private final LinkedHashMap<String,Document> documents=new LinkedHashMap<>();
    private final LinkedHashMap<String,TextureDocument> images=new LinkedHashMap<>();
    record Entry(String path,String name,boolean directory) {}
    List<Entry> children(String relative)throws IOException {
        Path directory=workspace.assetPath(folder+"/"+(relative.isEmpty()?".index":relative+"/.index")).getParent();
        try(var entries=Files.list(directory)) {
            return entries.filter(p->!Files.isSymbolicLink(p)).filter(p->Files.isDirectory(p)||Files.isRegularFile(p))
                .map(p->new Entry(workspace.root().relativize(p).toString().replace('\\','/'),p.getFileName().toString(),Files.isDirectory(p)))
                .sorted(Comparator.comparing(Entry::directory).reversed().thenComparing(Entry::name,String.CASE_INSENSITIVE_ORDER)).limit(8192).toList();
        }
    }
    String createFolder(String relative)throws IOException {
        String path=qualify(relative);Files.createDirectories(workspace.assetPath(path));workspace.assetPath(path);return path;
    }
    TextureDocument openImage(String path)throws IOException {
        check(path);if(!path.toLowerCase(Locale.ROOT).endsWith(".png"))throw new IOException("Choose a PNG texture");
        var image=images.get(path);
        if(image==null||!image.dirty()) {
            try(var input=Files.newInputStream(workspace.assetPath(path),LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes=MaterialCanvas.readPreviewBytes(input);
                if(image==null||!Arrays.equals(bytes,image.baseline())){image=TextureDocument.decode(bytes);images.put(path,image);}
            }
        }
        return image;
    }
    String createImage(String relative,int width,int height)throws IOException {
        if(!relative.toLowerCase(Locale.ROOT).endsWith(".png"))throw new IOException("Texture name must end in .png");
        String path=qualify(relative);Path file=workspace.assetPath(path);byte[] png=new TextureDocument(width,height).encode();
        Files.createDirectories(file.getParent());workspace.assetPath(path);Files.write(file,png,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS);
        openImage(path);return path;
    }
    Map<String,byte[]> imageDrafts()throws IOException {
        var drafts=new LinkedHashMap<String,byte[]>();for(var entry:images.entrySet())if(entry.getValue().dirty())drafts.put(entry.getKey(),entry.getValue().encode());return drafts;
    }
    boolean hasDrafts(){return !drafts().isEmpty()||images.values().stream().anyMatch(TextureDocument::dirty);}
    List<String> openPaths(){var paths=new LinkedHashSet<>(documents.keySet());paths.addAll(images.keySet());return List.copyOf(paths);}
    boolean dirty(String path){return documents.containsKey(path)?documents.get(path).dirty():images.containsKey(path)&&images.get(path).dirty();}
    ShaderProject(ShaderWorkspace workspace,String source)throws IOException{
        this.workspace=workspace;int slash=source.indexOf('/');if(slash<1)throw new IOException("Choose a project source");
        folder=source.substring(0,slash);workspace.assetPath(source);open(source);
    }
    String folder(){return folder;}
    ShaderWorkspace workspace(){return workspace;}
    String qualify(String relative)throws IOException{
        if(relative.startsWith("/")||relative.startsWith(folder+"/"))throw new IOException("Enter a path relative to the project");
        String path=folder+"/"+relative;workspace.assetPath(path);return path;
    }
    private void check(String path)throws IOException{if(!path.startsWith(folder+"/"))throw new IOException("File is outside this project");workspace.assetPath(path);}
    Document open(String path)throws IOException{
        check(path);Document current=documents.get(path);
        if(current==null||!current.dirty()){
            String text=workspace.read(path);int cursor=current==null?0:Math.min(current.cursor(),text.length());
            current=new Document(path,text,text,cursor);documents.put(path,current);
        }
        return current;
    }
    void edit(String path,String text,int cursor)throws IOException{
        Document before=documents.get(path);if(before==null)before=open(path);
        if(text.length()>ShaderWorkspace.MAX_SOURCE_BYTES)throw new IOException("Source exceeds editor limit");
        documents.put(path,new Document(path,before.baseline(),text,Math.clamp(cursor,0,text.length())));
    }
    List<String> files()throws IOException{return workspace.projectFiles(folder);}
    List<Document> documents(){return List.copyOf(documents.values());}
    Map<String,String> drafts(){var result=new LinkedHashMap<String,String>();for(var doc:documents.values())if(doc.dirty())result.put(doc.path(),doc.text());return Map.copyOf(result);}
    String text(String path)throws IOException{Document doc=documents.get(path);return doc!=null?doc.text():workspace.read(path);}
    String create(String relative,String text)throws IOException{String path=qualify(relative);workspace.create(path,text);open(path);return path;}
    String rename(String source,String relative)throws IOException{
        check(source);String target=qualify(relative);Path destination=workspace.assetPath(target);
        if(source.equals(target))return source;
        if(target.startsWith(source+"/"))throw new IOException("Cannot move a folder inside itself");
        if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new IOException("Destination already exists");
        Files.createDirectories(destination.getParent());workspace.assetPath(target);
        Files.move(workspace.assetPath(source),destination);
        var moved=new LinkedHashMap<String,Document>();
        for(var doc:documents.values()){
            String next=relocated(doc.path(),source,target);moved.put(next,new Document(next,doc.baseline(),doc.text(),doc.cursor()));
        }
        documents.clear();documents.putAll(moved);
        var movedImages=new LinkedHashMap<String,TextureDocument>();images.forEach((path,image)->movedImages.put(relocated(path,source,target),image));
        images.clear();images.putAll(movedImages);return target;
    }
    static String relocated(String path,String source,String target){return path.equals(source)||path.startsWith(source+"/")?target+path.substring(source.length()):path;}
    void delete(String source)throws IOException{check(source);Files.delete(workspace.assetPath(source));documents.remove(source);images.remove(source);}
    String importAsset(Path source,String relative)throws IOException{
        String target=qualify(relative);Path destination=workspace.assetPath(target);
        if(!Files.isRegularFile(source)||Files.size(source)>64L*1024*1024)throw new IOException("Choose a regular asset up to 64 MiB");
        Files.createDirectories(destination.getParent());workspace.assetPath(target);
        try(var in=Files.newInputStream(source);var out=Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW,LinkOption.NOFOLLOW_LINKS)){
            byte[] buffer=new byte[16384];long total=0;int read;while((read=in.read(buffer))!=-1){total+=read;if(total>64L*1024*1024)throw new IOException("Asset grew beyond 64 MiB");out.write(buffer,0,read);}
        }return target;
    }
    void saveAll()throws IOException{
        Map<String,String> drafts=drafts();Map<String,byte[]> pngs=imageDrafts();
        for(String path:pngs.keySet())if(!Arrays.equals(Files.readAllBytes(workspace.assetPath(path)),images.get(path).baseline()))throw new IOException("Texture changed externally: "+path);
        for(var entry:drafts.entrySet())if(!workspace.read(entry.getKey()).equals(documents.get(entry.getKey()).baseline()))throw new IOException("Source changed externally: "+entry.getKey()+". Keep/export the draft before resolving the conflict.");
        workspace.saveAll(drafts,pngs);
        pngs.forEach((path,bytes)->images.get(path).saved(bytes));
        for(var entry:drafts.entrySet()){var doc=documents.get(entry.getKey());documents.put(entry.getKey(),new Document(doc.path(),doc.text(),doc.text(),doc.cursor()));}
    }
    ShaderPackExporter.Exported export(Path destination,boolean dirty)throws IOException{
        String source=folder+"/.project-anchor";return ShaderPackExporter.exportProject(workspace.root(),source,dirty?drafts():Map.of(),dirty?imageDrafts():Map.of(),destination);
    }
}
