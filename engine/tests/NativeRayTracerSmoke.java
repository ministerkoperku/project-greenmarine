package com.koper.greenmarine.nativebridge;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Run with Java 25 against the freshly built native library; no Vulkan host is attached. */
public final class NativeRayTracerSmoke {
    public static void main(String[] args) throws InterruptedException {
        try (var arena = Arena.ofConfined()) {
            var symbols = SymbolLookup.libraryLookup(Path.of(args[0]), NativePlatform.libraryArena(arena));
            var current = new NativeRayTracer(symbols);
            check(current.available(), "legacy RT ABI remains available");
            check(current.actorsAvailable(), "actor extension available independently");
            check(current.extendedActorsAvailable(),"extended actor extension available independently");
            check(current.recordSurfaceActorsExtended(1,ByteBuffer.allocateDirect(320),1,1,1,2,3,4,5,ByteBuffer.allocateDirect(18448),0)==-2,
                "empty extended actors preserve missing-host handling");
            SymbolLookup noExtended=name->name.equals("greenmarine_surface_rt_actors_extended_api_version") || name.equals("greenmarine_surface_rt_record_actors_extended")?Optional.empty():symbols.find(name);
            var noExtendedLibrary=new NativeRayTracer(noExtended);
            check(noExtendedLibrary.actorsAvailable() && !noExtendedLibrary.extendedActorsAvailable(),"extended absence preserves v1 actor availability");
            check(noExtendedLibrary.recordSurfaceActorsExtended(1,ByteBuffer.allocateDirect(320),1,1,1,2,3,4,5,ByteBuffer.allocateDirect(18448),0)==-2,
                "missing extended API rejects payload without truncation");
            boolean extendedRejected=false;
            try {current.recordSurfaceActorsExtended(1,ByteBuffer.allocateDirect(320),1,1,1,2,3,4,5,ByteBuffer.allocateDirect(4624),0);}
            catch(IllegalArgumentException expected) {extendedRejected=true;}
            check(extendedRejected,"extended API requires exact18448 byte payload");
            var extendedPayload=ByteBuffer.allocateDirect(18448).order(java.nio.ByteOrder.nativeOrder());
            extendedPayload.putFloat(0,65);extendedRejected=false;
            try {current.recordSurfaceActorsExtended(1,ByteBuffer.allocateDirect(320),1,1,1,2,3,4,5,extendedPayload,1);}
            catch(IllegalArgumentException expected) {extendedRejected=true;}
            check(extendedRejected,"extended actor count65 is rejected before FFM");
            extendedPayload.putFloat(0,64);
            for(int box=0;box<64;box++) {
                int offset=16+box*288;
                for(int axis=0;axis<4;axis++)extendedPayload.putFloat(offset+axis*20,1);
                for(int axis=0;axis<3;axis++)extendedPayload.putFloat(offset+80+axis*4,1);
            }
            check(current.recordSurfaceActorsExtended(1,ByteBuffer.allocateDirect(320),1,1,1,2,3,4,5,extendedPayload,1)==-2,
                "valid64box payload crosses FFM to missing-host handling");


            check(current.recordSurfaceActors(1,ByteBuffer.allocateDirect(320),1,1,1,2,3,4,5,ByteBuffer.allocateDirect(4624),0)==-2,
                "empty actors preserve no-host surface dispatch semantics");
            boolean invalidActors=false;
            try {current.recordSurfaceActors(1,ByteBuffer.allocateDirect(320),1,1,1,2,3,4,5,ByteBuffer.allocate(4624),0);}
            catch(IllegalArgumentException expected) {invalidActors=true;}
            check(invalidActors,"actor payload must use direct memory");
            invalidActors=false;
            var invalidPayload=ByteBuffer.allocateDirect(4624).order(java.nio.ByteOrder.nativeOrder());
            invalidPayload.putFloat(0,17);
            try {current.recordSurfaceActors(1,ByteBuffer.allocateDirect(320),1,1,1,2,3,4,5,invalidPayload,1);}
            catch(IllegalArgumentException expected) {invalidActors=true;}
            check(invalidActors,"actor count is bounded before FFM");
            invalidActors=false;invalidPayload.putFloat(0,0).putFloat(100,Float.NaN);
            try {current.recordSurfaceActors(1,ByteBuffer.allocateDirect(320),1,1,1,2,3,4,5,invalidPayload,0);}
            catch(IllegalArgumentException expected) {invalidActors=true;}
            check(invalidActors,"actor payload must be finite before FFM");
            SymbolLookup noActors=name->name.equals("greenmarine_surface_rt_actors_api_version") || name.equals("greenmarine_surface_rt_record_actors")?Optional.empty():symbols.find(name);
            var noActorLibrary=new NativeRayTracer(noActors);
            check(noActorLibrary.surfaceAvailable() && !noActorLibrary.actorsAvailable(), "actor absence preserves surface ABI");
            check(noActorLibrary.recordSurfaceActors(1,ByteBuffer.allocateDirect(320),1,1,1,2,3,4,5,ByteBuffer.allocateDirect(4624),0)==-2,
                "legacy actor call falls back to surface dispatch");
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
        }
        workerMayExitAfterLibraryUnload(Path.of(args[0]));
        System.out.println("NativeRayTracer smoke: 36 checks plus library lifetime and worker teardown passed");
    }
    private static void workerMayExitAfterLibraryUnload(Path library) throws InterruptedException {
        var ready = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread worker;
        try (var arena = Arena.ofShared()) {
            var current = new NativeRayTracer(SymbolLookup.libraryLookup(library, NativePlatform.libraryArena(arena)));
            worker = new Thread(() -> {
                try {
                    check(current.prepareScene(ByteBuffer.allocateDirect(96), 1) == 0, "worker rejects unattached host");
                    check(current.prepareError().contains("No attached host"), "worker initializes its native error TLS");
                } catch (Throwable error) { failure.set(error); }
                finally { ready.countDown(); }
                try { finish.await(); }
                catch (InterruptedException error) { failure.set(error); Thread.currentThread().interrupt(); }
            });
            worker.start();
            ready.await();
        } finally { finish.countDown(); }
        worker.join();
        if (failure.get() != null) throw new AssertionError("Worker native smoke failed", failure.get());
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
