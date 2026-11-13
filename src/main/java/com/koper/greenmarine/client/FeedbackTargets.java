package com.koper.greenmarine.client;

import java.util.*;

/** Publishes all attachments together only after a successful pass; reads never see its write side. */
final class FeedbackTargets<T> {
    private static final class Pair<T> {
        final T first,second;boolean flipped;
        Pair(T first,T second) {this.first=Objects.requireNonNull(first);this.second=Objects.requireNonNull(second);}
    }
    private final Map<String,Pair<T>> targets=new LinkedHashMap<>();
    void define(String name,T first,T second) {
        if(targets.putIfAbsent(name,new Pair<>(first,second))!=null)throw new IllegalArgumentException("Repeated target: "+name);
    }
    private Pair<T> target(String name) {
        var pair=targets.get(name);if(pair==null)throw new IllegalArgumentException("Unknown scene target: "+name);return pair;
    }
    T read(String name) {var p=target(name);return p.flipped?p.second:p.first;}
    T write(String name) {var p=target(name);return p.flipped?p.first:p.second;}
    void commit(Collection<String> names) {
        var unique=new HashSet<String>();
        for(var name:names) {target(name);if(!unique.add(name))throw new IllegalArgumentException("Repeated output: "+name);}
        for(var name:names) {var pair=target(name);pair.flipped=!pair.flipped;}
    }
    void clear() {targets.clear();}
}
