package com.termux.x11;

/**
 * Diagnostic-only EGL probe. This reports capabilities from the actual
 * Termux-XW11 process; it does not claim that Weston buffers can be imported.
 */
public final class NativeGraphicsProbe {
    static {
        System.loadLibrary("Xlorie");
    }

    private NativeGraphicsProbe() {}

    public static native String probeEglCapabilities();
}
