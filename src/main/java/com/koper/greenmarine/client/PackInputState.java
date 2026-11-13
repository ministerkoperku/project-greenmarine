package com.koper.greenmarine.client;

import com.koper.greenmarine.api.pack.PackInputs;
import com.koper.greenmarine.api.render.UniformExpressions;
import com.koper.greenmarine.client.iris.IrisFrameInputs;
import java.lang.reflect.Array;
import java.util.*;

/** One pack/world lifetime's selected custom expressions and frame results. */
public final class PackInputState {
    private PackInputs configuration;
    private Object world,pack;
    private final UniformExpressions.State smoothing=new UniformExpressions.State();
    private final Map<String,UniformExpressions.Declaration> declarations=new LinkedHashMap<>();
    private UniformExpressions.Program selected;
    private Set<String> selectedOutputs=Set.of(),frameSources=Set.of();
    private IrisFrameInputs.Snapshot frame;
    private Map<String,Object> frameValues=Map.of();

    /** Prepare the union of sources used by the frame's selected passes exactly once.
     * Repeating the same snapshot/source set returns a defensive cached result; changing the
     * set after evaluation is an error. Unused expression branches/program outputs never ask
     * for optional host integrations. Lifetime changes clear smoothing and cached values.
     */
    public Map<String,Object> resolve(PackInputs inputs,Object world,Object pack,IrisFrameInputs.Snapshot snapshot,Set<String> sources) {
        Objects.requireNonNull(inputs);Objects.requireNonNull(world);Objects.requireNonNull(pack);Objects.requireNonNull(snapshot);
        sources=Set.copyOf(sources);
        if(configuration!=inputs || this.world!=world || this.pack!=pack) {
            reset();configuration=inputs;this.world=world;this.pack=pack;
            for(var declaration:inputs.expressions())declarations.put(declaration.name(),declaration);
        }
        if(frame==snapshot) {
            if(!frameSources.equals(sources))throw new IllegalStateException("Pack frame sources changed after evaluation; prepare their union once");
            return copy(frameValues);
        }
        var constants=inputs.constants();var outputs=new HashSet<String>();var hostReferences=new HashSet<String>();
        var direct=new LinkedHashMap<String,Object>();
        for(String source:sources) {
            String root=root(source);
            if(declarations.containsKey(root)) {
                validateExpressionReference(declarations.get(root),source);outputs.add(root);
            } else if(constants.containsKey(root))direct.put(source,value(constants,source));
            else hostReferences.add(source);
        }
        if(selected==null || !selectedOutputs.equals(outputs)) {
            selected=inputs.program().select(outputs);selectedOutputs=Set.copyOf(outputs);
        }
        for(String reference:selected.requiredInputs())if(!constants.containsKey(root(reference)))hostReferences.add(reference);
        // Validate constants' dotted references and all host references before advancing smoothing.
        for(String reference:selected.requiredInputs())if(constants.containsKey(root(reference)))value(constants,reference);
        var environment=new LinkedHashMap<String,Object>(snapshot.resolve(hostReferences));environment.putAll(constants);
        var evaluated=outputs.isEmpty()?Map.<String,Object>of():selected.evaluate(environment,smoothing,snapshot.expressionDeltaSeconds()).values();
        var result=new LinkedHashMap<String,Object>();
        for(String source:sources) {
            if(direct.containsKey(source))result.put(source,direct.get(source));
            else if(declarations.containsKey(root(source)))result.put(source,value(evaluated,source));
            else result.put(source,value(environment,source));
        }
        frame=snapshot;frameSources=sources;frameValues=copy(result);
        return copy(frameValues);
    }
    public void reset() {
        configuration=null;world=pack=null;declarations.clear();selected=null;selectedOutputs=Set.of();
        frame=null;frameSources=Set.of();frameValues=Map.of();smoothing.reset();
    }
    private static void validateExpressionReference(UniformExpressions.Declaration declaration,String reference) {
        Object shape=switch(declaration.type()) {
            case BOOL->false;case INT->0;case FLOAT->0f;
            case VEC2->new float[2];case VEC3->new float[3];case VEC4->new float[4];
        };
        value(Map.of(declaration.name(),shape),reference);
    }
    private static String root(String reference) {
        if(reference==null || !reference.matches("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)*"))
            throw new IllegalArgumentException("Invalid pack input source: "+reference);
        int dot=reference.indexOf('.');return dot<0?reference:reference.substring(0,dot);
    }
    private static Object value(Map<String,?> values,String reference) {
        String root=root(reference);Object current=values.get(root);
        if(current==null)throw new IllegalArgumentException("Missing pack input source: "+reference);
        if(reference.equals(root))return current;
        String[] components=reference.substring(root.length()+1).split("\\.");
        int length=current.getClass().isArray()?Array.getLength(current):current instanceof List<?> list?list.size():0;
        if(length==16 && components.length==2) {
            int at=component(components[0])*4+component(components[1]);
            return current instanceof List<?> list?list.get(at):Array.get(current,at);
        }
        for(String part:components) {
            int index=component(part);
            if(current!=null && current.getClass().isArray() && index<Array.getLength(current))current=Array.get(current,index);
            else if(current instanceof List<?> list && index<list.size())current=list.get(index);
            else throw new IllegalArgumentException("Component outside pack input source: "+reference);
        }
        return current;
    }
    private static int component(String name) {
        if(name.length()!=1)throw new IllegalArgumentException("Invalid pack input component: "+name);
        return switch(name.charAt(0)) {case '0','x','r','s'->0;case '1','y','g','t'->1;case '2','z','b','p'->2;case '3','w','a','q'->3;
            default->throw new IllegalArgumentException("Invalid pack input component: "+name);};
    }
    private static Map<String,Object> copy(Map<String,Object> source) {
        var result=new LinkedHashMap<String,Object>();
        source.forEach((key,value)->{
            if(value.getClass().isArray()) {
                int length=Array.getLength(value);var cloned=Array.newInstance(value.getClass().componentType(),length);
                System.arraycopy(value,0,cloned,0,length);result.put(key,cloned);
            } else if(value instanceof List<?> list)result.put(key,List.copyOf(list));
            else result.put(key,value);
        });
        return Collections.unmodifiableMap(result);
    }
}
