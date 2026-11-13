package com.koper.greenmarine.api.pack;

import com.google.gson.*;
import java.util.*;

/** Ordered, immutable vanilla block-state material rules for captured Iris terrain. */
public final class PackTerrain {
    public static final Set<String> PIPELINES=Set.of("minecraft:pipeline/solid_terrain","minecraft:pipeline/cutout_terrain","minecraft:pipeline/translucent_terrain");
    public static final PackTerrain NONE=new PackTerrain(false,Map.of());
    public record Rule(String block,Map<String,String> properties,int id) {
        public Rule {properties=Map.copyOf(properties);}
        boolean matches(Map<String,String> state) {
            return properties.entrySet().stream().allMatch(e->e.getValue().equals(state.get(e.getKey())));
        }
    }
    private final boolean enabled;
    private final Map<String,Integer> blocks;
    private final Map<String,List<Rule>> byBlock;
    public PackTerrain(boolean enabled,Map<String,Integer> blocks) {
        if(blocks.size()>16384 || !enabled&&!blocks.isEmpty())throw invalid("Invalid terrain mapping count/profile");
        this.enabled=enabled;this.blocks=Collections.unmodifiableMap(new LinkedHashMap<>(blocks));
        var index=new LinkedHashMap<String,List<Rule>>();
        this.blocks.forEach((selector,id)->{
            if(id==null)throw invalid("Null material ID");
            var rule=parseRule(selector,id);index.computeIfAbsent(rule.block(),key->new ArrayList<>()).add(rule);
        });
        var snapshot=new HashMap<String,List<Rule>>();index.forEach((block,rules)->snapshot.put(block,List.copyOf(rules)));
        byBlock=Map.copyOf(snapshot);
    }
    public boolean enabled(){return enabled;}
    public Map<String,Integer> blocks(){return blocks;}
    public int materialId(String block,Map<String,String> properties) {
        for(var rule:byBlock.getOrDefault(block,List.of()))if(rule.matches(properties))return rule.id();
        return -1;
    }
    /** Native selectors have explicit namespaces and colon-separated exact state predicates. */
    public static Rule parseRule(String selector,int id) {
        if(selector==null || selector.length()>1024)throw invalid("Invalid block selector");
        String[] parts=selector.split(":",-1);
        if(parts.length<2)throw invalid("Block selector requires an explicit namespace: "+selector);
        String block=parts[0]+":"+parts[1];ShaderPack.requireId(block);
        var properties=new LinkedHashMap<String,String>();
        for(int i=2;i<parts.length;i++) {
            String[] pair=parts[i].split("=",-1);
            if(pair.length!=2 || !pair[0].matches("[a-z0-9_]+") || !pair[1].matches("[a-z0-9_-]+")
                || properties.putIfAbsent(pair[0],pair[1])!=null)throw invalid("Invalid block-state predicate: "+selector);
        }
        return new Rule(block,properties,id);
    }
    public static PackTerrain parse(JsonObject object) {
        if(object==null)return NONE;
        for(String key:object.keySet())if(!Set.of("profile","blocks").contains(key))throw invalid("Unknown terrain key: "+key);
        var profile=object.get("profile");
        if(profile==null || !profile.isJsonPrimitive() || !profile.getAsJsonPrimitive().isString()
            || !profile.getAsString().equals("iris_separate_ao"))throw invalid("Unknown terrain profile");
        var blocks=new LinkedHashMap<String,Integer>();
        if(object.has("blocks")) {
            if(!object.get("blocks").isJsonObject())throw invalid("blocks must be an object");
            for(var entry:object.getAsJsonObject("blocks").entrySet())try {
                var value=entry.getValue();
                if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())throw invalid("Material IDs must be integers");
                blocks.put(entry.getKey(),value.getAsBigDecimal().intValueExact());
            }catch(ArithmeticException failure){throw invalid("Material ID outside signed 32-bit integer range");}
        }
        return new PackTerrain(true,blocks);
    }
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException("Pack terrain: "+message);}
}
