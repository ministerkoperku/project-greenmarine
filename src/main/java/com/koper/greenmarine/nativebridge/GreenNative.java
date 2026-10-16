package com.koper.greenmarine.nativebridge;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import static java.lang.foreign.ValueLayout.*;

public final class GreenNative implements AutoCloseable {
    private Arena arena;
    private SymbolLookup symbols;
    private MethodHandle attach, sample, detach;
    private String status;
    private Path extracted;

    private GreenNative(String status) { this.status = status; }

    public static void checkAbi(int version) {
        if (version != 1) throw new IllegalStateException("Unsupported GreenMarine native ABI: " + version);
    }

    public static GreenNative open(Path path) {
        var engine = new GreenNative("Native library unavailable");
        Arena scope = Arena.ofShared();
        try {
            var symbols = SymbolLookup.libraryLookup(path.toAbsolutePath(), scope);
            var linker = Linker.nativeLinker();
            var abi = linker.downcallHandle(symbols.find("greenmarine_abi_version").orElseThrow(), FunctionDescriptor.of(JAVA_INT));
            checkAbi((int) abi.invokeExact());
            engine.attach = linker.downcallHandle(symbols.find("greenmarine_attach").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT));
            engine.sample = linker.downcallHandle(symbols.find("greenmarine_sample").orElseThrow(),
                FunctionDescriptor.of(JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
            engine.detach = linker.downcallHandle(symbols.find("greenmarine_detach").orElseThrow(), FunctionDescriptor.ofVoid());
            engine.arena = scope;
            engine.symbols = symbols;
            engine.status = "Native ABI 1 ready";
        } catch (Throwable error) {
            scope.close();
            engine.status = "Native library unavailable: " + error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        return engine;
    }

    public static GreenNative openBundled() {
        if (Boolean.getBoolean("greenmarine.native.disable")) return new GreenNative("Native library disabled by launch option");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
            return new GreenNative("No bundled native library for " + os + "/" + arch);
        }
        Path directory = null;
        try (var stream = GreenNative.class.getResourceAsStream("/native/linux-x86_64/libgreenmarine_engine.so")) {
            if (stream == null) return new GreenNative("Bundled native library missing");
            directory = Files.createTempDirectory("greenmarine-native-");
            Path library = directory.resolve("libgreenmarine_engine.so");
            Files.copy(stream, library);
            var engine = open(library);
            engine.extracted = library;
            if (!engine.available()) engine.cleanExtracted();
            return engine;
        } catch (IOException error) {
            if (directory != null) {
                try { Files.deleteIfExists(directory.resolve("libgreenmarine_engine.so")); Files.deleteIfExists(directory); }
                catch (IOException ignored) { directory.toFile().deleteOnExit(); }
            }
            return new GreenNative("Native extraction failed: " + error.getMessage());
        }
    }

    public NativeRayTracer rayTracer() { return new NativeRayTracer(available()?symbols:null); }

    public boolean available() { return arena != null && arena.scope().isAlive(); }
    public String status() { return status; }

    public int attach(long instance, long physical, long device, long queue, int family) {
        if (!available()) return -2;
        try { return (int) attach.invokeExact(instance, physical, device, queue, family); }
        catch (Throwable error) { status = "Native attachment failed: " + error; return -3; }
    }

    public float sample(float time, float intensity) {
        if (!available()) return 0;
        try { return (float) sample.invokeExact(time, intensity); }
        catch (Throwable error) { status = "Native sampling failed: " + error; return 0; }
    }

    public void detach() {
        if (!available()) return;
        try { detach.invokeExact(); }
        catch (Throwable error) { status = "Native detach failed: " + error; }
    }

    @Override public void close() {
        detach();
        if (arena != null) { arena.close(); arena = null; }
        cleanExtracted();
        status = "Native library closed";
    }

    private void cleanExtracted() {
        if (extracted == null) return;
        try { Files.deleteIfExists(extracted); Files.deleteIfExists(extracted.getParent()); }
        catch (IOException ignored) { extracted.toFile().deleteOnExit(); extracted.getParent().toFile().deleteOnExit(); }
        extracted = null;
    }
}
