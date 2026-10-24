package com.koper.greenmarine.api.render;

import java.util.Set;

/** Conservative texture-family defaults; null means retain the ordinary opaque fallback. */
public final class InferredMaterials {
    private static final BlockMaterials.Surface METAL=new BlockMaterials.Surface(.44f,.7f,0,2);
    private static final BlockMaterials.Surface WOOD=new BlockMaterials.Surface(.86f,.04f,0,1);
    private static final BlockMaterials.Surface STONE=new BlockMaterials.Surface(.91f,.04f,0,1);
    private static final BlockMaterials.Surface MATTE=new BlockMaterials.Surface(.97f,.02f,0,1);
    private static final BlockMaterials.Surface POLISHED=new BlockMaterials.Surface(.48f,.18f,0,9);
    private static final Set<String> STONES=Set.of("stone","cobblestone","mossy_cobblestone","granite","diorite","andesite","deepslate","tuff","calcite","basalt","blackstone","end_stone","netherrack","obsidian","bedrock","bricks","terracotta","quartz_block");
    private InferredMaterials() {}

    public static BlockMaterials.Surface forTexture(String id) {
        var builtin=BlockMaterials.atlasProfiles().get(id);
        if(builtin==null)builtin=BlockMaterials.additionalAtlasProfiles().get(id);
        if(builtin!=null)return builtin;
        int path=id.indexOf(":block/");
        if(path<1)return null;
        String name=id.substring(path+7);
        // Match material-bearing shapes, never a substring such as iron in ironwood or an ore vein.
        if(name.contains("ore") && (name.endsWith("_ore") || name.startsWith("raw_")))return STONE;
        if(name.startsWith("raw_") && name.endsWith("_block"))return STONE;
        if(name.equals("glass") || name.endsWith("_glass") || name.contains("glass_pane"))
            return new BlockMaterials.Surface(.14f,.22f,name.contains("tinted")?0:.85f,5);
        String copper=name.startsWith("waxed_")?name.substring(6):name;
        if(copper.matches("(?:(?:exposed|weathered|oxidized)_)?(?:(?:cut|chiseled)_)?copper(?:_(?:block|bars|chain|grate|bulb|door|trapdoor|top|side|bottom|lit|powered))*")) {
            if(copper.startsWith("oxidized_"))return BlockMaterials.forBlock("minecraft:oxidized_copper");
            if(copper.startsWith("weathered_"))return BlockMaterials.forBlock("minecraft:weathered_copper");
            if(copper.startsWith("exposed_"))return BlockMaterials.forBlock("minecraft:exposed_copper");
            return BlockMaterials.forBlock("minecraft:copper_block");
        }
        if(name.matches("(?:iron|gold|netherite)_(?:block|bars|chain|door(?:_top|_bottom)?|trapdoor)")
            || name.matches("(?:(?:chipped|damaged)_)?anvil(?:_top)?") || name.equals("chain") || name.equals("heavy_core"))return METAL;
        if(name.startsWith("polished_") || name.equals("smooth_quartz") || name.startsWith("quartz_block_") || name.startsWith("glazed_terracotta") || name.endsWith("_glazed_terracotta"))return POLISHED;
        if(name.matches(".+_(?:planks|log(?:_top)?|wood|stem(?:_top)?|hyphae)") || name.matches("(?:stripped_)?bamboo_block(?:_top)?"))return WOOD;
        if(name.endsWith("_wool") || name.endsWith("_concrete") || name.endsWith("_concrete_powder"))return MATTE;
        if(STONES.contains(name) || name.endsWith("_bricks") || name.endsWith("_tiles") || name.startsWith("deepslate_") || name.startsWith("sandstone_") || name.endsWith("_terracotta"))return STONE;
        return null;
    }
}
