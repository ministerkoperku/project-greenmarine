package com.koper.greenmarine.nativebridge;

import java.nio.ByteBuffer;

/** Run with only the distribution JAR and these test classes on the classpath. */
public final class BundledNativeSmoke {
    public static void main(String[] args) {
        GreenNative engine = GreenNative.openBundled();
        if (Boolean.getBoolean("greenmarine.native.disable")) {
            try (engine) {
                if (engine.available()) throw new AssertionError("Native disable ignored");
                if (com.koper.greenmarine.client.PulseSampler.sample(engine, 0, 2) != 1.5f)
                    throw new AssertionError("Java pulse fallback");
            }
            System.out.println("BundledNative smoke: disabled native and Java fallback passed");
            return;
        }
        NativeRayTracer rays;
        try (engine) {
            if (!engine.available()) throw new AssertionError(engine.status());
            if (engine.sample(0, 2) != 1.5f) throw new AssertionError("Bundled sample ABI");
            rays = engine.rayTracer();
            if (!rays.asyncAvailable()) throw new AssertionError("Async ABI missing");
            if (rays.prepareScene(ByteBuffer.allocateDirect(96), 1) != 0
                || !rays.prepareError().contains("No attached host")) throw new AssertionError("Error ABI");
            System.out.println(engine.status());
        }
        if (engine.available()) throw new AssertionError("Closed engine remains available");
        if (rays.initialize(true) != -3) throw new AssertionError("Retained tracer called a closed engine");
        System.out.println("BundledNative smoke: packaged library, FFM calls and close passed");
    }
}
