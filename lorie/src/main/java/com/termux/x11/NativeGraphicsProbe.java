package com.termux.x11;

import android.view.Surface;

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

    /** Tests EGL window-surface creation and swap on the supplied Android Surface. */
    public static native String probeWindowSurface(Surface surface);
}
