package com.koper.greenmarine.api.pack;

import com.google.gson.*;
import com.koper.greenmarine.api.render.UniformExpressions;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

/** Immutable CPU configuration for native pack constants and custom expressions. No client/GPU
 * dependencies. Constants retain precise numbers until the consuming expression/layout validates
 * its type. A compiled program is shared; mutable evaluation State belongs to the renderer. */
public final class PackInputs {
    public static final int MAX_CONSTANTS=256,MAX_COMPONENTS=65_536,MAX_ARRAY_LENGTH=16_384;
    public static final PackInputs NONE=new PackInputs(Map.of(),List.of(),0);
    private static final Set<String> KEYS=Set.of("constants","expressions","sunPathRotation");
    private static final Set<String> EXPRESSION_KEYS=Set.of("kind","type","name","expression");
    private final Map<String,Object> constants;
    private final List<UniformExpressions.Declaration> expressions;
    private final float sunPathRotation;
    private final UniformExpressions.Program program;

    public PackInputs(Map<String,?> constants,List<UniformExpressions.Declaration> expressions,float sunPathRotation) {
        if(constants==null||constants.size()>MAX_CONSTANTS)throw invalid("Expected at most "+MAX_CONSTANTS+" constants");
        if(expressions==null||expressions.size()>UniformExpressions.Limits.DEFAULT.declarations())throw invalid("Invalid expression count");
        if(!Float.isFinite(sunPathRotation))throw invalid("sunPathRotation must be finite");
        var snapshot=new LinkedHashMap<String,Object>();int components=0;
        for(var entry:constants.entrySet()) {
            String name=entry.getKey();
            if(name==null||!name.matches("[A-Za-z_][A-Za-z_0-9]*")||name.startsWith("gl_")||Set.of("true","false","pi").contains(name))
                throw invalid("Invalid constant name: "+name);
            Object raw=entry.getValue();
            components+=raw!=null&&raw.getClass().isArray()?Array.getLength(raw):raw instanceof List<?> list?list.size():1;
            if(components>MAX_COMPONENTS)throw invalid("Constant component budget exceeded");
            snapshot.put(name,constant(raw,name));
        }
        var declarations=new ArrayList<UniformExpressions.Declaration>();
        for(var declaration:expressions) {
            if(declaration==null)throw invalid("Null expression declaration");
            if(snapshot.containsKey(declaration.name()))throw invalid("Constant/expression name collision: "+declaration.name());
            declarations.add(declaration);
        }
        this.constants=Collections.unmodifiableMap(snapshot);this.expressions=List.copyOf(declarations);
        this.sunPathRotation=sunPathRotation;program=UniformExpressions.compile(this.expressions);
    }

    /** Defensive arrays and immutable maps/lists. Calling this getter cannot expose model state. */
    public Map<String,Object> constants() {
        var copy=new LinkedHashMap<String,Object>();
        constants.forEach((name,value)->copy.put(name,value.getClass().isArray()?cloneArray(value):value));
        return Collections.unmodifiableMap(copy);
    }
    public List<UniformExpressions.Declaration> expressions(){return expressions;}
    public float sunPathRotation(){return sunPathRotation;}
    public UniformExpressions.Program program(){return program;}

    /** Missing optional configuration is NONE. Explicit fields require their actual JSON types;
     * strings are never converted to numbers/booleans and unknown keys are errors. */
    public static PackInputs parse(JsonObject object) {
        if(object==null)return NONE;
        keys(object,KEYS,"uniformInputs");
        var constants=new LinkedHashMap<String,Object>();
        if(object.has("constants")) {
            var node=object.get("constants");
            if(!node.isJsonObject())throw invalid("constants must be an object");
            if(node.getAsJsonObject().size()>MAX_CONSTANTS)throw invalid("Constant count budget exceeded");
            int components=0;
            for(var entry:node.getAsJsonObject().entrySet()) {
                var value=entry.getValue();components+=value.isJsonArray()?value.getAsJsonArray().size():1;
                if(components>MAX_COMPONENTS)throw invalid("Constant component budget exceeded");
                constants.put(entry.getKey(),jsonConstant(value,entry.getKey()));
            }
        }
        var expressions=new ArrayList<UniformExpressions.Declaration>();
        if(object.has("expressions")) {
            var node=object.get("expressions");
            if(!node.isJsonArray())throw invalid("expressions must be an array");
            if(node.getAsJsonArray().size()>UniformExpressions.Limits.DEFAULT.declarations())throw invalid("Expression count budget exceeded");
            for(var element:node.getAsJsonArray()) {
                if(!element.isJsonObject())throw invalid("Expression declaration must be an object");
                var declaration=element.getAsJsonObject();keys(declaration,EXPRESSION_KEYS,"expression");
                var kind=switch(string(declaration,"kind")){case "variable"->UniformExpressions.Kind.VARIABLE;case "uniform"->UniformExpressions.Kind.UNIFORM;default->throw invalid("Unknown expression kind");};
                var type=switch(string(declaration,"type")) {
                    case "bool"->UniformExpressions.Type.BOOL;case "int"->UniformExpressions.Type.INT;case "float"->UniformExpressions.Type.FLOAT;
                    case "vec2"->UniformExpressions.Type.VEC2;case "vec3"->UniformExpressions.Type.VEC3;case "vec4"->UniformExpressions.Type.VEC4;
                    default->throw invalid("Unknown expression type");
                };
                expressions.add(new UniformExpressions.Declaration(kind,type,string(declaration,"name"),string(declaration,"expression")));
            }
        }
        float rotation=0;
        if(object.has("sunPathRotation")) {
            var node=object.get("sunPathRotation");
            if(!node.isJsonPrimitive()||!node.getAsJsonPrimitive().isNumber())throw invalid("sunPathRotation must be a number");
            rotation=((Number)jsonScalar(node,"sunPathRotation")).floatValue();
        }
        return new PackInputs(constants,expressions,rotation);
    }

    private static Object constant(Object value,String name) {
        if(value==null)throw invalid("Null constant: "+name);
        if(value.getClass().isArray()) {
            Class<?> component=value.getClass().getComponentType();int length=Array.getLength(value);
            if(!component.isPrimitive()||component==char.class)throw invalid("Constant arrays must be flat numeric/boolean primitive arrays: "+name);
            length(length,name);
            for(int i=0;i<length;i++)scalar(Array.get(value,i),name);
            return cloneArray(value);
        }
        if(value instanceof List<?> list) {
            length(list.size(),name);var snapshot=new ArrayList<Object>(list.size());
            for(Object component:list)snapshot.add(scalar(component,name));
            return List.copyOf(snapshot);
        }
        return scalar(value,name);
    }
    private static Object scalar(Object value,String name) {
        if(value instanceof Boolean)return value;
        if(!(value instanceof Number number))throw invalid("Expected numeric/boolean constant component: "+name);
        Class<?> type=number.getClass();
        if(type!=Byte.class&&type!=Short.class&&type!=Integer.class&&type!=Long.class&&type!=Float.class&&type!=Double.class
            &&type!=BigInteger.class&&type!=BigDecimal.class)throw invalid("Unsupported or mutable Number type for constant: "+name);
        if(!Double.isFinite(number.doubleValue()))throw invalid("Nonfinite or out-of-range constant: "+name);
        return value;
    }
    private static Object jsonConstant(JsonElement value,String name) {
        if(value.isJsonArray()) {
            var array=value.getAsJsonArray();length(array.size(),name);var result=new ArrayList<Object>(array.size());
            for(var component:array)result.add(jsonScalar(component,name));
            return List.copyOf(result);
        }
        return jsonScalar(value,name);
    }
    private static Object jsonScalar(JsonElement value,String name) {
        if(!value.isJsonPrimitive())throw invalid("Expected numeric/boolean JSON constant component: "+name);
        var primitive=value.getAsJsonPrimitive();
        if(primitive.isBoolean())return primitive.getAsBoolean();
        if(!primitive.isNumber())throw invalid("Expected numeric/boolean JSON constant component: "+name);
        try{return scalar(new BigDecimal(primitive.getAsString()),name);}
        catch(NumberFormatException invalid){throw invalid("Invalid numeric constant: "+name);}
    }
    private static Object cloneArray(Object source){int size=Array.getLength(source);Object result=Array.newInstance(source.getClass().getComponentType(),size);System.arraycopy(source,0,result,0,size);return result;}
    private static void length(int size,String name){if(size<1||size>MAX_ARRAY_LENGTH)throw invalid("Constant array length must be 1.."+MAX_ARRAY_LENGTH+": "+name);}
    private static String string(JsonObject object,String name) {
        var value=object.get(name);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw invalid("Expression "+name+" must be a string");
        return value.getAsString();
    }
    private static void keys(JsonObject object,Set<String> allowed,String label){for(String name:object.keySet())if(!allowed.contains(name))throw invalid("Unknown "+label+" key: "+name);}
    private static IllegalArgumentException invalid(String message){return new IllegalArgumentException("Pack inputs: "+message);}
}
