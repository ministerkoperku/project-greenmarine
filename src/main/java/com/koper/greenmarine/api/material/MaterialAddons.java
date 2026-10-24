package com.koper.greenmarine.api.material;

import com.google.gson.*;
import com.koper.greenmarine.api.pack.ShaderPack;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Function;
import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;

/** CPU-only, atomically published material catalog. GPU textures remain owned by consumers. */
public final class MaterialAddons {
    public static final int API_VERSION=1;
    private static final int MAX_PIXELS=2048*2048, MAX_BINARY=4*1024*1024, CACHE_BYTES=32*1024*1024;
    public record Source(String origin,String json,Function<String,byte[]> binaries,String shaderPackId) {
        public Source {
            Objects.requireNonNull(origin);Objects.requireNonNull(json);Objects.requireNonNull(binaries);
            if(shaderPackId!=null)ShaderPack.requireId(shaderPackId);
        }
    }
    /** Each pixel: roughness, reflection strength, emission, surface class (0 means keep fallback). */
    public record Compiled(int width,int height,ByteBuffer pixels,long revision) {
        public Compiled {
            dimensions(width,height);Objects.requireNonNull(pixels);
            if(pixels.remaining()!=width*height*4)throw new IllegalArgumentException("Material pixel length mismatch");
            pixels=pixels.slice().asReadOnlyBuffer();
        }
        @Override public ByteBuffer pixels(){return pixels.asReadOnlyBuffer();}
    }
    private record Material(int roughness,int reflection,int emission,int surface) {}
    private record Texture(Material base,int maskWidth,int maskHeight,int[] mask,Map<Integer,Material> palette) {}
    private record Layer(String origin,Set<String> packs,String owner,Map<String,Texture> textures) {
        boolean specific(){return owner!=null || !packs.isEmpty();}
        boolean matches(String pack){return (owner==null || owner.equals(pack)) && (packs.isEmpty() || packs.contains(pack));}
    }
    private record Key(String texture,String pack,int width,int height) {}
    private static List<Layer> layers=List.of();
    private static final LinkedHashMap<Key,Compiled> cache=new LinkedHashMap<>(16,.75f,true);
    private static int cacheBytes;
    private static long revision;
    private static String active="greenmarine:none";
    private MaterialAddons() {}

    public static synchronized String activePackId(){return active;}
    public static synchronized long generation(){return revision;}
    public static synchronized void setActivePackId(String id) {
        ShaderPack.requireId(id);
        if(!id.equals(active)){active=id;revision++;clearCache();}
    }
    /** Replaces the complete catalog once; malformed sources are isolated and reported by origin. */
    public static synchronized List<String> reload(List<Source> sources) {
        Objects.requireNonNull(sources);
        if(sources.size()>128)throw new IllegalArgumentException("At most 128 material addons");
        var next=new ArrayList<Layer>();var errors=new ArrayList<String>();long maskPixels=0;
        for(var source:sources)try {
            var layer=parse(source,16L*1024*1024-maskPixels);long count=layer.textures().values().stream().mapToLong(t->t.mask()==null?0:t.mask().length).sum();
            if(maskPixels+count>16L*1024*1024)throw new IllegalArgumentException("Material masks exceed 64 MiB");
            maskPixels+=count;next.add(layer);
        }catch(RuntimeException error){errors.add(source.origin()+": "+error.getMessage());}
        // Stable sort keeps deterministic source/resource priority within each specificity tier.
        next.sort(Comparator.comparing(Layer::specific));layers=List.copyOf(next);revision++;clearCache();
        return List.copyOf(errors);
    }
    private static void clearCache(){cache.clear();cacheBytes=0;}

    public static synchronized Optional<Compiled> resolve(String textureId,String shaderPackId,int width,int height) {
        dimensions(width,height);ShaderPack.requireId(shaderPackId);
        String id=canonicalTexture(textureId);var key=new Key(id,shaderPackId,width,height);
        var existing=cache.get(key);if(existing!=null)return Optional.of(existing);
        var applicable=layers.stream().filter(l->l.matches(shaderPackId) && l.textures().containsKey(id)).toList();
        if(applicable.isEmpty())return Optional.empty();
        var pixels=ByteBuffer.allocateDirect(width*height*4);
        for(var layer:applicable) {
            var texture=layer.textures().get(id);
            for(int y=0;y<height;y++)for(int x=0;x<width;x++) {
                Material value=texture.base();
                if(texture.mask()!=null) {
                    int color=texture.mask()[(y*texture.maskHeight()/height)*texture.maskWidth()+x*texture.maskWidth()/width];
                    if((color>>>24)==255)value=texture.palette().getOrDefault(color&0xffffff,value);
                }
                if(value==null)continue;
                int offset=(y*width+x)*4;
                pixels.put(offset,(byte)value.roughness()).put(offset+1,(byte)value.reflection())
                    .put(offset+2,(byte)value.emission()).put(offset+3,(byte)value.surface());
            }
        }
        var compiled=new Compiled(width,height,pixels,revision);
        while(cacheBytes+pixels.capacity()>CACHE_BYTES && !cache.isEmpty()) {
            var first=cache.entrySet().iterator();var old=first.next();cacheBytes-=old.getValue().width()*old.getValue().height()*4;first.remove();
        }
        cache.put(key,compiled);cacheBytes+=pixels.capacity();return Optional.of(compiled);
    }
    private static void dimensions(int width,int height) {
        if(width<1 || height<1 || width>2048 || height>2048 || (long)width*height>MAX_PIXELS)
            throw new IllegalArgumentException("Material dimensions must be 1..2048");
    }
    private static String canonicalTexture(String id) {
        ShaderPack.requireId(id);int colon=id.indexOf(':');String path=id.substring(colon+1);
        if(!path.startsWith("textures/"))path="textures/"+path;
        if(!path.endsWith(".png"))path+=".png";
        return id.substring(0,colon+1)+path;
    }
    /** Snapshots private external-pack PNGs while its resource resolver is available. */
    public static Source snapshot(Source source) {
        var root=document(source);var binaries=new HashMap<String,byte[]>();int total=0;
        for(var entry:root.getAsJsonObject("textures").entrySet()) {
            var definition=entry.getValue().getAsJsonObject();
            if(!definition.has("mask"))continue;
            String id=definition.get("mask").getAsString();ShaderPack.requireId(id);
            if(binaries.containsKey(id))continue;
            byte[] data=source.binaries().apply(id);
            if(data==null || data.length>MAX_BINARY || (total+=data.length)>16*1024*1024)throw new IllegalArgumentException("Missing or excessive material mask: "+id);
            binaries.put(id,data.clone());
        }
        var frozen=Map.copyOf(binaries);
        return new Source(source.origin(),source.json(),id->{var bytes=frozen.get(id);return bytes==null?null:bytes.clone();},source.shaderPackId());
    }
    private static JsonObject document(Source source) {
        if(source.json().length()>262144)throw new IllegalArgumentException("Material JSON exceeds 256 KiB");
        var root=JsonParser.parseString(source.json()).getAsJsonObject();
        if(!root.has("schema") || !root.get("schema").isJsonPrimitive() || !root.getAsJsonPrimitive("schema").isNumber()
            || root.get("schema").getAsBigDecimal().compareTo(java.math.BigDecimal.ONE)!=0)throw new IllegalArgumentException("Unsupported material schema");
        ShaderPack.requireId(root.get("id").getAsString());
        if(!root.has("textures") || !root.get("textures").isJsonObject() || root.getAsJsonObject("textures").size()>512)
            throw new IllegalArgumentException("Expected at most 512 textures");
        return root;
    }
    private static Layer parse(Source source,long remainingMaskPixels) {
        var root=document(source);var packs=new LinkedHashSet<String>();
        if(root.has("shaderpacks"))for(var element:root.getAsJsonArray("shaderpacks")) {String id=element.getAsString();ShaderPack.requireId(id);packs.add(id);}
        if(packs.size()>64)throw new IllegalArgumentException("Too many shaderpack selectors");
        var textures=new LinkedHashMap<String,Texture>();
        for(var entry:root.getAsJsonObject("textures").entrySet()) {
            String id=canonicalTexture(entry.getKey());var definition=entry.getValue().getAsJsonObject();
            Material base=definition.has("default")?material(definition.getAsJsonObject("default")):null;
            var palette=new HashMap<Integer,Material>();
            if(definition.has("palette"))for(var color:definition.getAsJsonObject("palette").entrySet()) {
                String hex=color.getKey().replaceFirst("^#","");if(!hex.matches("[0-9a-fA-F]{6}"))throw new IllegalArgumentException("Palette needs RGB hex colors");
                if(palette.put(Integer.parseInt(hex,16),material(color.getValue().getAsJsonObject()))!=null)throw new IllegalArgumentException("Duplicate palette color");
            }
            if(palette.size()>256)throw new IllegalArgumentException("At most 256 palette colors");
            int maskWidth=0,maskHeight=0;int[] mask=null;
            if(definition.has("mask")) {
                String maskId=definition.get("mask").getAsString();ShaderPack.requireId(maskId);
                var bytes=source.binaries().apply(maskId);
                if(bytes==null || bytes.length>MAX_BINARY)throw new IllegalArgumentException("Missing or excessive mask: "+maskId);
                try(var input=new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                    var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IllegalArgumentException("Unreadable mask PNG");
                    var reader=readers.next();try {
                        if(!reader.getFormatName().equalsIgnoreCase("png"))throw new IllegalArgumentException("Mask must be PNG");
                        reader.setInput(input,true,true);maskWidth=reader.getWidth(0);maskHeight=reader.getHeight(0);dimensions(maskWidth,maskHeight);
                        long pixels=(long)maskWidth*maskHeight;
                        if(pixels>remainingMaskPixels)throw new IllegalArgumentException("Material masks exceed 64 MiB");
                        remainingMaskPixels-=pixels;
                        var image=reader.read(0);mask=image.getRGB(0,0,maskWidth,maskHeight,null,0,maskWidth);
                    }finally{reader.dispose();}
                }catch(java.io.IOException error){throw new IllegalArgumentException("Unreadable mask PNG",error);}
            }
            if(base==null && mask==null)throw new IllegalArgumentException("Texture needs default or mask");
            if(textures.put(id,new Texture(base,maskWidth,maskHeight,mask,Map.copyOf(palette)))!=null)throw new IllegalArgumentException("Duplicate texture alias: "+id);
        }
        return new Layer(source.origin(),Set.copyOf(packs),source.shaderPackId(),Map.copyOf(textures));
    }
    private static Material material(JsonObject object) {
        var value=object.get("surface");int surface;
        if(value==null)throw new IllegalArgumentException("Material needs surface");
        if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            surface=value.getAsInt();if(value.getAsBigDecimal().compareTo(java.math.BigDecimal.valueOf(surface))!=0)throw new IllegalArgumentException("Surface class must be integer");
        }else surface=switch(value.getAsString().toLowerCase(Locale.ROOT)) {
            case "opaque","matte","stone","wood"->1;case "metal"->2;case "puddle"->3;case "water"->4;
            case "glass"->5;case "ice"->6;case "porous"->7;case "gem","diamond"->8;case "coated","screen","polished"->9;
            default->throw new IllegalArgumentException("Unknown surface class");
        };
        if(surface<1 || surface>9)throw new IllegalArgumentException("Surface class must be 1..9");
        return new Material(channel(object,"roughness"),channel(object,"reflection"),channel(object,"emission"),surface);
    }
    private static int channel(JsonObject object,String name) {
        var value=object.get(name);
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Material needs numeric "+name);
        float number=value.getAsFloat();if(!Float.isFinite(number) || number<0 || number>1)throw new IllegalArgumentException("Material "+name+" must be 0..1");
        return Math.round(number*255);
    }
}
