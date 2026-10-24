package com.koper.greenmarine.development;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** CPU-only authoring model. Transparent pixels deliberately retain renderer fallback. */
public final class MaterialCanvas {
    public static final List<String> SURFACES=List.of("matte","metal","puddle","water","glass","ice","porous","gem","coated");
    public static final int[] COLORS={0xffffff,0xff5555,0x55aaff,0x55dd88,0xffcc44,0xcc88ff};
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    public record Material(String surface,float roughness,float reflection,float emission){
        public Material {
            if(!SURFACES.contains(surface))throw new IllegalArgumentException("Choose a supported surface");
            for(float value:new float[]{roughness,reflection,emission})if(!Float.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Material values must be finite 0..1");
        }
    }
    public record Saved(Path pack,Path json,Path mask){}
    private final int width,height;
    private int[] pixels,stroke;
    private final Deque<int[]> undo=new ArrayDeque<>();
    private final LinkedHashMap<Integer,Material> palette=new LinkedHashMap<>();
    public MaterialCanvas(int width,int height){
        if(width<1||height<1||width>256||height>256)throw new IllegalArgumentException("Canvas dimensions must be 1..256");
        this.width=width;this.height=height;pixels=new int[width*height];
        for(int color:COLORS)palette.put(color,new Material("matte",.8f,.05f,0));
    }
    public int width(){return width;} public int height(){return height;}
    public int pixel(int x,int y){return pixels[y*width+x];}
    public Material material(int rgb){return palette.get(rgb&0xffffff);}
    public void setMaterial(int rgb,Material material){
        if(!palette.containsKey(rgb&0xffffff)&&palette.size()>=256)throw new IllegalArgumentException("At most 256 palette colors");
        palette.put(rgb&0xffffff,Objects.requireNonNull(material));
    }
    public void beginStroke(){if(stroke==null)stroke=pixels.clone();}
    public void endStroke(){if(stroke!=null&&!Arrays.equals(stroke,pixels)){undo.addLast(stroke);while(undo.size()>32)undo.removeFirst();}stroke=null;}
    public boolean undo(){endStroke();if(undo.isEmpty())return false;pixels=undo.removeLast();return true;}
    public void paintLine(int x0,int y0,int x1,int y1,int argb,int diameter){
        if(stroke==null)throw new IllegalStateException("Begin a stroke first");
        if(diameter<1||diameter>32)throw new IllegalArgumentException("Brush must be 1..32");
        if(argb!=0&&((argb>>>24)!=255||!palette.containsKey(argb&0xffffff)))throw new IllegalArgumentException("Choose an opaque palette color or eraser");
        // Clamp before line walking: dragging outside the canvas cannot create unbounded work.
        x0=Math.clamp(x0,0,width-1);x1=Math.clamp(x1,0,width-1);y0=Math.clamp(y0,0,height-1);y1=Math.clamp(y1,0,height-1);
        int dx=Math.abs(x1-x0),sx=x0<x1?1:-1,dy=-Math.abs(y1-y0),sy=y0<y1?1:-1,error=dx+dy;
        while(true){
            int start=-(diameter-1)/2;
            for(int by=start;by<start+diameter;by++)for(int bx=start;bx<start+diameter;bx++){
                int x=x0+bx,y=y0+by;if(x>=0&&x<width&&y>=0&&y<height)pixels[y*width+x]=argb;
            }
            if(x0==x1&&y0==y1)break;
            int twice=2*error;if(twice>=dy){error+=dy;x0+=sx;}if(twice<=dx){error+=dx;y0+=sy;}
        }
    }
    public static String canonicalTexture(String id){
        if(id==null||!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))throw new IllegalArgumentException("Use namespace:textures/name.png");
        int colon=id.indexOf(':');String path=id.substring(colon+1);
        if(Arrays.stream(path.split("/",-1)).anyMatch(part->part.isEmpty()||part.equals(".")||part.equals("..")))throw new IllegalArgumentException("Unsafe texture path");
        if(!path.startsWith("textures/"))path="textures/"+path;
        if(!path.endsWith(".png"))path+=".png";
        return id.substring(0,colon+1)+path;
    }
    /** Resolves Kodel-style fullpacks directly in mod roots, without requiring KoperLib classes. */
    public static Optional<byte[]> fullpackAlbedo(List<Path> roots,String texture)throws IOException{
        String canonical=canonicalTexture(texture);int colon=canonical.indexOf(':');
        String relative="koperlib/fullpacks/"+canonical.substring(0,colon)+"/"+canonical.substring(colon+1);
        for(Path modRoot:roots){
            Path root=modRoot.toAbsolutePath().normalize(),candidate=root.resolve(relative).normalize();
            if(!candidate.startsWith(root))throw new IOException("Texture escapes mod root");
            if(!Files.isRegularFile(candidate))continue;
            if(!candidate.toRealPath().startsWith(root.toRealPath()))throw new IOException("Texture symlink escapes mod root");
            if(Files.size(candidate)>4*1024*1024)throw new IOException("Albedo exceeds 4 MiB");
            try(var input=Files.newInputStream(candidate)){return Optional.of(previewPng(readPreviewBytes(input)));}
        }
        return Optional.empty();
    }
    public static byte[] readPreviewBytes(java.io.InputStream input)throws IOException{
        byte[] bytes=input.readNBytes(4*1024*1024+1);
        if(bytes.length>4*1024*1024)throw new IOException("Albedo exceeds 4 MiB");return bytes;
    }
    /** Validate PNG dimensions before NativeImage can allocate decoded storage. Bytes stay unchanged. */
    public static byte[] previewPng(byte[] bytes)throws IOException{
        if(bytes.length>4*1024*1024)throw new IOException("Albedo exceeds 4 MiB");
        var header=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN);
        if(bytes.length<33||header.getLong(0)!=0x89504e470d0a1a0aL||header.getInt(8)!=13||header.getInt(12)!=0x49484452)throw new IOException("Source must be a PNG image");
        int width=header.getInt(16),height=header.getInt(20);
        if(width<1||height<1||width>256||height>256)throw new IOException("Preview dimensions must be 1..256");
        return bytes;
    }
    private static String key(String texture){return canonicalTexture(texture).replace(':','/');}
    public static Saved paths(Path gameRoot,String texture){
        Path pack=gameRoot.toAbsolutePath().normalize().resolve("resourcepacks/greenmarine-development-materials");String key=key(texture);
        return new Saved(pack,pack.resolve("assets/greenmarine_dev/greenmarine/materials/"+key+".json"),pack.resolve("assets/greenmarine_dev/greenmarine/masks/"+key));
    }
    public Saved save(Path gameRoot,String texture) throws IOException {
        endStroke();String target=canonicalTexture(texture),key=key(texture);var saved=paths(gameRoot,target);
        var root=new JsonObject();root.addProperty("schema",1);root.addProperty("id","greenmarine_dev:"+key);
        var definition=new JsonObject();definition.addProperty("mask","greenmarine_dev:greenmarine/masks/"+key);
        var colors=new JsonObject();palette.forEach((rgb,material)->colors.add(String.format(Locale.ROOT,"%06x",rgb),GSON.toJsonTree(material)));
        definition.add("palette",colors);var textures=new JsonObject();textures.add(target,definition);root.add("textures",textures);
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,width,height,pixels,0,width);
        var bytes=new java.io.ByteArrayOutputStream();if(!ImageIO.write(image,"PNG",bytes))throw new IOException("PNG encoder unavailable");
        Path metadata=saved.pack().resolve("pack.mcmeta");
        // Validate every destination before creating anything or replacing the first mask.
        for(Path path:List.of(saved.mask(),saved.json(),metadata))preflight(gameRoot,path);
        var writes=new LinkedHashMap<Path,byte[]>();writes.put(saved.mask(),bytes.toByteArray());writes.put(saved.json(),GSON.toJson(root).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // Preserve owner metadata and all other texture definitions already in this editable pack.
        if(!Files.exists(metadata,LinkOption.NOFOLLOW_LINKS))writes.put(metadata,("{\"pack\":{\"description\":\"GreenMarine Development Materials\",\"min_format\":[97,1],\"max_format\":[97,1]}}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        saveTransaction(gameRoot,saved.pack(),writes);
        return saved;
    }
    private static void safePath(Path gameRoot,Path path)throws IOException{
        Path root=gameRoot.toAbsolutePath().normalize(),target=path.toAbsolutePath().normalize();
        if(!target.startsWith(root)||target.equals(root))throw new IOException("Material path escapes game directory");
        if(!Files.isDirectory(root))throw new IOException("Game directory does not exist");
        Path current=root;
        for(Path part:root.relativize(target)){
            current=current.resolve(part);
            if(Files.isSymbolicLink(current))throw new IOException("Material paths cannot contain symbolic links: "+current);
            if(!current.equals(target)&&Files.exists(current,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(current,LinkOption.NOFOLLOW_LINKS))throw new IOException("Material parent is not a directory: "+current);
        }
    }
    private static void preflight(Path root,Path path)throws IOException{
        safePath(root,path);
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Material destination is not a regular file: "+path);
    }
    private static void safeDirectories(Path gameRoot,Path directory)throws IOException{
        safePath(gameRoot,directory);
        Path root=gameRoot.toAbsolutePath().normalize(),current=root;
        for(Path part:root.relativize(directory.toAbsolutePath().normalize())){
            current=current.resolve(part);safePath(root,current);
            if(!Files.exists(current,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(current);
            if(!Files.isDirectory(current,LinkOption.NOFOLLOW_LINKS))throw new IOException("Material parent is not a directory");
        }
    }
    private static void replace(Path source,Path target)throws IOException{
        try{Files.move(source,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);}
    }
    private static void saveTransaction(Path gameRoot,Path pack,LinkedHashMap<Path,byte[]> writes)throws IOException{
        safeDirectories(gameRoot,pack);
        Path staging=Files.createTempDirectory(pack,".greenmarine-save-");
        var staged=new LinkedHashMap<Path,Path>();var backups=new HashMap<Path,Path>();var committed=new ArrayList<Path>();boolean preserveRecovery=false;
        try{
            int index=0;
            // Stage every new file and snapshot all previous files before committing any replacement.
            for(var entry:writes.entrySet()){
                Path target=entry.getKey();preflight(gameRoot,target);safeDirectories(gameRoot,target.getParent());
                Path next=staging.resolve("new-"+index),old=staging.resolve("old-"+index++);Files.write(next,entry.getValue());staged.put(target,next);
                if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){preflight(gameRoot,target);Files.copy(target,old);backups.put(target,old);}
            }
            for(var entry:staged.entrySet()){preflight(gameRoot,entry.getKey());replace(entry.getValue(),entry.getKey());committed.add(entry.getKey());}
        }catch(IOException failure){
            Collections.reverse(committed);
            for(Path target:committed)try{
                preflight(gameRoot,target);Path backup=backups.get(target);
                if(backup!=null)replace(backup,target);else Files.deleteIfExists(target);
            }catch(IOException rollback){preserveRecovery=true;failure.addSuppressed(rollback);}
            throw failure;
        }finally{
            // Keep recovery files if the filesystem prevented restoring a previous destination.
            if(!preserveRecovery){
                try(var children=Files.list(staging)){for(Path child:children.toList())Files.deleteIfExists(child);}
                Files.deleteIfExists(staging);
            }
        }
    }
    public static MaterialCanvas load(Path gameRoot,String texture)throws IOException{
        var saved=paths(gameRoot,texture);
        preflight(gameRoot,saved.json());preflight(gameRoot,saved.mask());
        if(Files.size(saved.json())>262144||Files.size(saved.mask())>4*1024*1024)throw new IOException("Editor files exceed size limits");
        var root=JsonParser.parseString(Files.readString(saved.json())).getAsJsonObject();
        var definition=root.getAsJsonObject("textures").getAsJsonObject(canonicalTexture(texture));
        if(definition==null)throw new IOException("Texture missing from editor JSON");
        // Query dimensions before decode to avoid allocating an externally modified oversized image.
        BufferedImage image;
        try(var input=ImageIO.createImageInputStream(saved.mask().toFile())){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Invalid mask PNG");
            var reader=readers.next();try{reader.setInput(input);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||w>256||h>256)throw new IOException("Editor masks must be 1..256");image=reader.read(0);}finally{reader.dispose();}
        }
        var canvas=new MaterialCanvas(image.getWidth(),image.getHeight());canvas.palette.clear();
        for(var entry:definition.getAsJsonObject("palette").entrySet()){
            var m=entry.getValue().getAsJsonObject();canvas.setMaterial(Integer.parseInt(entry.getKey(),16),new Material(m.get("surface").getAsString(),m.get("roughness").getAsFloat(),m.get("reflection").getAsFloat(),m.get("emission").getAsFloat()));
        }
        for(int color:COLORS)if(canvas.material(color)==null)canvas.setMaterial(color,new Material("matte",.8f,.05f,0));
        image.getRGB(0,0,canvas.width,canvas.height,canvas.pixels,0,canvas.width);return canvas;
    }
}
