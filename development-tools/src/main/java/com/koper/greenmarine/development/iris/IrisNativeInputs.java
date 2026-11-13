package com.koper.greenmarine.development.iris;

import com.google.gson.*;
import com.koper.greenmarine.api.pack.PackInputs;
import java.math.*;
import java.util.*;
import java.util.regex.Pattern;

/** Explicit native uniform configuration; emitting it does not establish whole-pack runtime support. */
public final class IrisNativeInputs {
    private static final Pattern DECIMAL=Pattern.compile("([+-]?)\\s*((?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)[fFlLuU]*");
    private IrisNativeInputs() {}
    public static JsonObject export(IrisPipelinePlan plan) {
        var document=new JsonObject();document.addProperty("schema",1);document.addProperty("kind","greenmarine.iris.uniform-inputs");
        document.addProperty("runtimeReady",false);var worlds=new JsonArray();document.add("worlds",worlds);
        var declarations=new JsonArray();
        for(var property:plan.properties().entrySet()) {
            if(!property.getKey().startsWith("uniform.") && !property.getKey().startsWith("variable."))continue;
            String[] parts=property.getKey().split("\\.",3);var declaration=new JsonObject();
            declaration.addProperty("kind",parts[0]);declaration.addProperty("type",parts.length>1?parts[1]:"");
            declaration.addProperty("name",parts.length>2?parts[2]:"");declaration.addProperty("expression",property.getValue());declarations.add(declaration);
        }
        for(var world:plan.worlds()) {
            var entry=new JsonObject();entry.addProperty("folder",world.folder());worlds.add(entry);
            var diagnostics=new JsonArray();entry.add("diagnostics",diagnostics);entry.add("rawExpressions",declarations.deepCopy());
            var unresolved=new JsonObject();entry.add("unresolvedOptionMacros",unresolved);
            var config=new JsonObject();var constants=new JsonObject();config.add("constants",constants);config.add("expressions",declarations.deepCopy());
            try {
                config.addProperty("sunPathRotation",sunPath(world));
                var initial=PackInputs.parse(config);
                for(String reference:initial.program().requiredInputs()) {
                    String name=root(reference);String macro=plan.optionMacros().get(name);
                    if(macro==null || constants.has(name) || unresolved.has(name))continue;
                    try {constants.add(name,literal(macro));}
                    catch(IllegalArgumentException unsupported) {
                        unresolved.addProperty(name,macro);diagnostics.add("Unsupported option constant "+name+": "+macro);
                    }
                }
                var parsed=PackInputs.parse(config);var hosts=new JsonArray();
                for(String reference:new TreeSet<>(parsed.program().requiredInputs()))if(!constants.has(root(reference)))hosts.add(reference);
                entry.addProperty("configurationValid",true);entry.add("uniformInputs",config);entry.add("requiredHostReferences",hosts);
            }catch(IllegalArgumentException invalid) {
                entry.addProperty("configurationValid",false);entry.add("uniformInputs",JsonNull.INSTANCE);
                entry.add("requiredHostReferences",new JsonArray());diagnostics.add(invalid.getMessage());
            }
            entry.addProperty("optionConstantsComplete",unresolved.isEmpty());
        }
        return document;
    }
    private static float sunPath(IrisPipelinePlan.World world) {
        BigDecimal selected=null;
        for(var program:world.programs())if(program.enabled()) {
            var stages=new ArrayList<IrisPipelinePlan.Stage>(program.stages().values());stages.addAll(program.computes());
            for(var stage:stages) {
                var constant=stage.constants().get("sunPathRotation");if(constant==null)continue;
                JsonPrimitive value=literal(constant.expression());
                if(!value.isNumber())throw new IllegalArgumentException("sunPathRotation must be numeric in "+stage.path());
                var next=value.getAsBigDecimal();
                if(selected!=null && selected.compareTo(next)!=0)throw new IllegalArgumentException("Conflicting sunPathRotation directives in "+world.folder());
                selected=next;
            }
        }
        float result=selected==null?0:selected.floatValue();
        if(!Float.isFinite(result))throw new IllegalArgumentException("sunPathRotation is outside float range");
        return result;
    }
    /** Resolve exact literals only. Symbolic option expressions remain explicit requirements. */
    private static JsonPrimitive literal(String source) {
        String text=source.strip();
        if(text.equals("true") || text.equals("false"))return new JsonPrimitive(Boolean.parseBoolean(text));
        var decimal=DECIMAL.matcher(text);
        if(decimal.matches())return new JsonPrimitive(new BigDecimal(decimal.group(1)+decimal.group(2)));
        var hex=Pattern.compile("([+-]?)0[xX]([0-9a-fA-F]+)[uUlL]*").matcher(text);
        if(hex.matches())return new JsonPrimitive(new BigDecimal(new BigInteger(hex.group(1)+hex.group(2),16)));
        throw new IllegalArgumentException("Unsupported constant expression: "+source);
    }
    private static String root(String reference) {int dot=reference.indexOf('.');return dot<0?reference:reference.substring(0,dot);}
}
