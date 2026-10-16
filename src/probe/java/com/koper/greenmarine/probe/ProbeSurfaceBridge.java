package com.koper.greenmarine.probe;

import com.koper.greenmarine.client.GreenMarineClient;
import com.koper.greenmarine.client.rt.RayWorld;
import com.koper.greenmarine.nativebridge.*;
import java.lang.foreign.SymbolLookup;
import java.util.Optional;

/** Exercises an older ABI1 library without modifying packaged or installed binaries. */
final class ProbeSurfaceBridge {
    private static NativeRayTracer original;
    static void hideOptionalSymbols() throws Exception {
        var symbolsField=GreenNative.class.getDeclaredField("symbols");symbolsField.setAccessible(true);
        var symbols=(SymbolLookup)symbolsField.get(GreenMarineClient.engine());
        var factory=NativeRayTracer.class.getDeclaredConstructor(SymbolLookup.class);factory.setAccessible(true);
        var legacy=factory.newInstance((SymbolLookup)name->name.startsWith("greenmarine_surface_")?Optional.empty():symbols.find(name));
        if(!legacy.available() || legacy.surfaceAvailable())throw new IllegalStateException("Legacy bridge fixture was not isolated");
        var bridge=RayWorld.class.getDeclaredField("nativeRt");bridge.setAccessible(true);
        original=(NativeRayTracer)bridge.get(null);bridge.set(null,legacy);
        var initialized=RayWorld.class.getDeclaredField("surfaceInitialized");initialized.setAccessible(true);initialized.setBoolean(null,false);
    }
    static void restore() throws Exception {
        if(original==null)return;
        var bridge=RayWorld.class.getDeclaredField("nativeRt");bridge.setAccessible(true);bridge.set(null,original);original=null;
        var initialized=RayWorld.class.getDeclaredField("surfaceInitialized");initialized.setAccessible(true);initialized.setBoolean(null,false);
    }
}
