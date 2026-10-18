package com.koper.greenmarine.nativebridge;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Select by the running process ABI, not the Android filesystem outside proot. */
final class NativePlatform {
    private NativePlatform() {}

    static Arena libraryArena(Arena closeableArena) {
        // Rust and its standard library may register thread-exit callbacks. Bionic
        // can call those after dlclose; retain code mappings until process exit.
        return "android-aarch64".equals(current()) ? Arena.global() : closeableArena;
    }

    static String current() {
        String maps = "";
        try { maps = Files.readString(Path.of("/proc/self/maps")); }
        catch (IOException | SecurityException ignored) { /* Use JVM hints if procfs is restricted. */ }
        return select(System.getProperty("os.name", ""), System.getProperty("os.arch", ""), maps,
            System.getProperty("java.runtime.name", "") + " " + System.getProperty("java.vm.name", "")
                + " " + System.getProperty("java.vendor", ""));
    }

    static String select(String os, String arch, String maps, String runtime) {
        os = os.toLowerCase(Locale.ROOT);
        arch = arch.toLowerCase(Locale.ROOT);
        if (!os.contains("linux") && !os.contains("android")) return null;
        boolean glibc = maps.contains("libc.so.6") || maps.contains("ld-linux");
        boolean android = !glibc && (maps.contains("/libc.so") || os.contains("android")
            || runtime.toLowerCase(Locale.ROOT).contains("android"));
        if (arch.equals("aarch64") || arch.equals("arm64")) return android ? "android-aarch64" : "linux-aarch64";
        if (!android && (arch.equals("amd64") || arch.equals("x86_64"))) return "linux-x86_64";
        return null;
    }
}
