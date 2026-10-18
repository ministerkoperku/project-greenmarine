package com.koper.greenmarine.nativebridge;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativePlatformTest {
    @Test void linuxArmUsesItsOwnLibraryInsteadOfDesktopX86() {
        assertEquals("linux-aarch64", NativePlatform.select("Linux", "aarch64", "/usr/lib/libc.so.6", "OpenJDK"));
        assertEquals("linux-aarch64", NativePlatform.select("Linux", "arm64", "", "OpenJDK"));
        assertEquals("linux-x86_64", NativePlatform.select("Linux", "amd64", "", "OpenJDK"));
        assertEquals("linux-x86_64", NativePlatform.select("Linux", "x86_64", "", "OpenJDK"));
    }

    @Test void androidUsesBionicEvenWhenJvmReportsLinux() {
        assertEquals("android-aarch64", NativePlatform.select("Linux", "aarch64", "/apex/com.android.runtime/lib64/bionic/libc.so", "OpenJDK"));
        assertEquals("android-aarch64", NativePlatform.select("Android", "arm64", "", "OpenJDK"));
        assertEquals("android-aarch64", NativePlatform.select("Linux", "aarch64", "", "Android Runtime"));
    }

    @Test void prootGlibcTakesPrecedenceOverAndroidHints() {
        assertEquals("linux-aarch64", NativePlatform.select("Linux", "aarch64",
            "/data/data/com.termux/files/usr/var/lib/proot/usr/lib/libc.so.6\n/apex/shared-object.so", "Android OpenJDK"));
    }

    @Test void unsupportedPlatformsNeverTryAnotherAbi() {
        assertNull(NativePlatform.select("Windows 11", "aarch64", "", "OpenJDK"));
        assertNull(NativePlatform.select("Linux", "armv7l", "", "OpenJDK"));
        assertNull(NativePlatform.select("Android", "x86_64", "", "OpenJDK"));
        assertNull(NativePlatform.select("Mac OS X", "arm64", "", "OpenJDK"));
    }
}
