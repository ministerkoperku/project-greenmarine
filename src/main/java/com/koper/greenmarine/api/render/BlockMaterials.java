package com.koper.greenmarine.api.render;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Function;

/** Small builtin material library; unknown blocks remain rough, opaque and nonmetallic. */
public final class BlockMaterials {
    public static final int MAX_PROFILES=32, FRAME_BYTES=1040;
    public static final int MAX_ADDITIONAL_PROFILES=8, EXTENSION_FRAME_BYTES=272;
    public record Surface(float roughness,float reflectance,float sunlightTransmission,int surfaceClass) {
        public Surface {
            if(!Float.isFinite(roughness)||!Float.isFinite(reflectance)||!Float.isFinite(sunlightTransmission)
                ||roughness<0||roughness>1||reflectance<0||reflectance>1||sunlightTransmission<0||sunlightTransmission>1
                ||surfaceClass<1||surfaceClass>9) throw new IllegalArgumentException("Invalid block material");
        }
    }
    private static final Surface OPAQUE=new Surface(.96f,0,0,1);
    private static final Map<String,Surface> BLOCKS;
    private static final Map<String,Surface> ATLAS;
    private static final Map<String,Surface> ADDITIONAL_ATLAS;
    static {
        var blocks=new LinkedHashMap<String,Surface>();var atlas=new LinkedHashMap<String,Surface>();
        var glass=new Surface(.08f,.25f,.98f,5);
        add(blocks,atlas,"glass",glass);blocks.put("minecraft:glass_pane",glass);
        add(blocks,atlas,"tinted_glass",new Surface(.12f,.2f,0,5));
        for(String color:List.of("white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black")) {
            var stained=new Surface(.12f,.22f,.85f,5);
            add(blocks,atlas,color+"_stained_glass",stained);blocks.put("minecraft:"+color+"_stained_glass_pane",stained);
        }
        add(blocks,atlas,"ice",new Surface(.32f,.28f,.65f,6));
        add(blocks,atlas,"packed_ice",new Surface(.52f,.15f,0,6));
        add(blocks,atlas,"blue_ice",new Surface(.42f,.2f,0,6));
        add(blocks,atlas,"iron_block",new Surface(.4f,.7f,0,2));
        add(blocks,atlas,"gold_block",new Surface(.23f,.85f,0,2));
        add(blocks,atlas,"copper_block",new Surface(.48f,.65f,0,2));
        var porous=new Surface(.99f,0,0,7);
        for(String name:List.of("sand","red_sand","gravel","dirt","coarse_dirt","sandstone")) add(blocks,atlas,name,porous);
        atlas.put("minecraft:block/sandstone_top",porous);atlas.put("minecraft:block/sandstone_bottom",porous);
        if(atlas.size()>MAX_PROFILES)throw new IllegalStateException("Builtin material limit exceeded");
        var additional=new LinkedHashMap<String,Surface>();
        add(blocks,additional,"diamond_block",new Surface(.22f,.35f,0,8));
        add(blocks,additional,"netherite_block",new Surface(.65f,.55f,0,2));
        add(blocks,additional,"exposed_copper",new Surface(.60f,.35f,0,9));
        add(blocks,additional,"weathered_copper",new Surface(.76f,.22f,0,9));
        add(blocks,additional,"oxidized_copper",new Surface(.90f,.12f,0,9));
        // Vanilla waxed full cubes use the same model and atlas texture as unwaxed cubes.
        for(String name:List.of("copper_block","exposed_copper","weathered_copper","oxidized_copper"))
            blocks.put("minecraft:waxed_"+name,blocks.get("minecraft:"+name));
        if(additional.size()>MAX_ADDITIONAL_PROFILES)throw new IllegalStateException("Additional material limit exceeded");
        BLOCKS=Map.copyOf(blocks);ATLAS=Collections.unmodifiableMap(atlas);
        ADDITIONAL_ATLAS=Collections.unmodifiableMap(additional);
    }
    private BlockMaterials() {}
    private static void add(Map<String,Surface> blocks,Map<String,Surface> atlas,String name,Surface material) {
        blocks.put("minecraft:"+name,material);atlas.put("minecraft:block/"+name,material);
    }
    public static Surface forBlock(String id) {return BLOCKS.getOrDefault(id,OPAQUE);}
    public static Map<String,Surface> atlasProfiles() {return ATLAS;}
    public static Map<String,Surface> additionalAtlasProfiles() {return ADDITIONAL_ATLAS;}
    /** Rectangles and properties each occupy 32 vec4 slots, followed by one count vec4. */
    public static void writeAtlas(ByteBuffer output,Function<String,float[]> rectangles) {
        if(output.capacity()<FRAME_BYTES)throw new IllegalArgumentException("Material frame requires 1040 bytes");
        for(int offset=0;offset<FRAME_BYTES;offset+=4)output.putFloat(offset,0);
        int index=0;
        for(var entry:ATLAS.entrySet()) {
            var rect=rectangles.apply(entry.getKey());if(rect==null)continue;
            if(rect.length!=4)throw new IllegalArgumentException("Expected atlas UV rectangle");
            for(float value:rect)if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite atlas rectangle");
            if(rect[0]>=rect[2]||rect[1]>=rect[3])continue;
            for(int i=0;i<4;i++)output.putFloat(index*16+i*4,rect[i]);
            int offset=512+index*16;var profile=entry.getValue();
            output.putFloat(offset,profile.roughness()).putFloat(offset+4,profile.reflectance())
                .putFloat(offset+8,profile.sunlightTransmission()).putFloat(offset+12,profile.surfaceClass());index++;
        }
        output.putFloat(1024,index);
    }
    /** Independent std140 extension: 8 rectangles, 8 property vectors, then one count vec4. */
    public static void writeAtlasExtension(ByteBuffer output,Function<String,float[]> rectangles) {
        if(output.capacity()<EXTENSION_FRAME_BYTES)throw new IllegalArgumentException("Material extension requires 272 bytes");
        for(int offset=0;offset<EXTENSION_FRAME_BYTES;offset+=4)output.putFloat(offset,0);
        int index=0;
        for(var entry:ADDITIONAL_ATLAS.entrySet()) {
            var rect=rectangles.apply(entry.getKey());if(rect==null)continue;
            if(rect.length!=4)throw new IllegalArgumentException("Expected atlas UV rectangle");
            for(float value:rect)if(!Float.isFinite(value))throw new IllegalArgumentException("Non-finite atlas rectangle");
            if(rect[0]>=rect[2]||rect[1]>=rect[3])continue;
            for(int i=0;i<4;i++)output.putFloat(index*16+i*4,rect[i]);
            int offset=128+index*16;var profile=entry.getValue();
            output.putFloat(offset,profile.roughness()).putFloat(offset+4,profile.reflectance())
                .putFloat(offset+8,profile.sunlightTransmission()).putFloat(offset+12,profile.surfaceClass());index++;
        }
        output.putFloat(256,index);
    }

}
