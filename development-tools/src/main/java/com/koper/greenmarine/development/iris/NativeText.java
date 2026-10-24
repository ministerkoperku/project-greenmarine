package com.koper.greenmarine.development.iris;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** LWJGL's CharSequence overloads copy onto a 64 KiB thread stack; expanded Iris programs are far bigger. */
final class NativeText {
    interface Call { long apply(ByteBuffer source, ByteBuffer file, ByteBuffer entry); }
    private NativeText() {}
    static long with(String source, String file, Call call) {
        ByteBuffer text = MemoryUtil.memUTF8(source, false), name = MemoryUtil.memUTF8(file), entry = MemoryUtil.memUTF8("main");
        try { return call.apply(text, name, entry); }
        finally { MemoryUtil.memFree(text); MemoryUtil.memFree(name); MemoryUtil.memFree(entry); }
    }
}
