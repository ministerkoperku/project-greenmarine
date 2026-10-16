package com.koper.greenmarine.nativebridge;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Optional;

/** Run with Java 25 against the freshly built native library; no Vulkan host is attached. */
public final class NativeRayTracerSmoke {
    public static void main(String[] args) {
        try (var arena = Arena.ofConfined()) {
            var symbols = SymbolLookup.libraryLookup(Path.of(args[0]), arena);
            var current = new NativeRayTracer(symbols);
            check(current.available(), "legacy RT ABI remains available");
            check(current.surfaceAvailable(), "surface RT ABI available independently");
            check(current.initializeSurface(false) == -20, "Off rejects before host access");
            check(current.initializeSurface(true) == -2, "missing host cannot initialize surface state");
            check(current.recordSurface(1, ByteBuffer.allocateDirect(320), 1, 1, 1, 2, 3, 4, 5) == -2,
                "missing host cannot dispatch borrowed handles");
            boolean rejected = false;
            try { current.recordSurface(1, ByteBuffer.allocateDirect(288), 1, 1, 1, 2, 3, 4, 5); }
            catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "surface calls reject legacy MarineFrame288");
            SymbolLookup legacy = name -> name.startsWith("greenmarine_surface_") ? Optional.empty() : symbols.find(name);
            var oldLibrary = new NativeRayTracer(legacy);
            check(oldLibrary.available(), "missing optional symbols preserve legacy RT availability");
            check(!oldLibrary.surfaceAvailable(), "missing optional symbols disable only surface RT");
            check(oldLibrary.initializeSurface(true) == -2, "optional absence is reported safely");
            check(current.asyncAvailable(), "async ABI is independently available");
            check(current.beginSubmission(0)==-1, "async rejects zero serial");
            check(current.beginSubmission(1)==-2, "async lease needs attached host");
            check(current.prepareScene(ByteBuffer.allocateDirect(96),1)==0, "unattached preparation returns no token");
            check(current.prepareStatus()==-2, "prepare status retains no-host result");
            check(current.prepareError().contains("No attached host"), "prepare error crosses FFM on the preparing thread");
            check(current.publishScene(0)==-1, "publish rejects zero token");
            check(current.discardPreparedScene(0)==-1, "discard rejects zero token");
            check(current.asyncStats().length==14, "async stats layout is stable");
            SymbolLookup withoutAsync = name -> name.equals("greenmarine_rt_async_api_version") ? Optional.empty() : symbols.find(name);
            var surfaceLibrary=new NativeRayTracer(withoutAsync);
            check(surfaceLibrary.available(), "missing async symbols preserve base RT");
            check(surfaceLibrary.surfaceAvailable(), "missing async symbols preserve surface RT");
            check(!surfaceLibrary.asyncAvailable(), "missing async symbols disable only async extension");
            check(surfaceLibrary.beginSubmission(1)==-2, "missing async extension is safe");
            System.out.println("NativeRayTracer smoke: 22 checks passed");
        }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
