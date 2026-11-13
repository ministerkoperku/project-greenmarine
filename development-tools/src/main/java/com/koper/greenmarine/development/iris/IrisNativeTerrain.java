package com.koper.greenmarine.development.iris;

import com.google.gson.*;
import com.koper.greenmarine.api.pack.PackTerrain;
import java.util.*;

/** Generic host terrain routing and option-selected material rules. No pack-name special cases. */
public final class IrisNativeTerrain {
    public static final Map<String,String> REQUESTS;
    static {
        var requests=new LinkedHashMap<String,String>();
        requests.put("minecraft:pipeline/solid_terrain","gbuffers_terrain_solid");
        requests.put("minecraft:pipeline/cutout_terrain","gbuffers_terrain_cutout");
        requests.put("minecraft:pipeline/translucent_terrain","gbuffers_water");
        REQUESTS=Collections.unmodifiableMap(requests);
    }
    private IrisNativeTerrain() {}
    public static JsonObject capture(IrisSources sources,IrisPipelinePlan plan,List<String> diagnostics) {
        if(sources.text("block.properties").isEmpty() && plan.worlds().stream().flatMap(w->w.programs().stream()).filter(IrisPipelinePlan.Program::enabled)
            .flatMap(p->p.stages().values().stream()).anyMatch(s->s.kind()==IrisStage.VERTEX && java.util.regex.Pattern.compile("\\bmc_Entity\\b").matcher(s.preprocessedText()).find()))
            diagnostics.add("LEGACY_MATERIAL_DEFAULTS: missing block.properties requires the legacy Iris material map; an intentionally empty file is distinct");
        var selected=new LinkedHashMap<String,String>();
        for(String line:new MiniPreprocessor(plan.optionMacros()).process(sources.text("block.properties").orElse(""))) {
            String text=line.strip();if(text.isEmpty() || text.startsWith("#") || text.startsWith("!"))continue;
            int eq=text.indexOf('=');
            if(eq<0){diagnostics.add("BLOCK_MAPPING: invalid property: "+text);continue;}
            selected.put(text.substring(0,eq).strip(),text.substring(eq+1).strip());
        }
        var mappings=new JsonObject();
        for(var entry:selected.entrySet()) {
            if(!entry.getKey().matches("block\\.-?[0-9]+")) {diagnostics.add("BLOCK_MAPPING: unsupported directive "+entry.getKey());continue;}
            int id;
            try{id=Integer.parseInt(entry.getKey().substring(6));}
            catch(NumberFormatException invalid){diagnostics.add("BLOCK_MAPPING: invalid ID "+entry.getKey());continue;}
            for(String selector:entry.getValue().split("\\s+")) {
                if(selector.isBlank())continue;
                String[] parts=selector.split(":",-1);
                if(parts.length==1 || parts[1].contains("="))selector="minecraft:"+selector;
                try {
                    PackTerrain.parseRule(selector,id);
                    if(!mappings.has(selector))mappings.addProperty(selector,id);
                }catch(IllegalArgumentException invalid){diagnostics.add("BLOCK_MAPPING: "+invalid.getMessage());}
            }
        }
        var result=new JsonObject();result.addProperty("profile","iris_separate_ao");result.add("blocks",mappings);return result;
    }
}
