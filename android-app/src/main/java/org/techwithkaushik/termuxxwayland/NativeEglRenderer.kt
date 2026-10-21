package org.techwithkaushik.termuxxwayland

import android.view.Surface

/**
 * Owns an EGL/GLES renderer bound directly to the Android Surface.
 * Calls must be made from the same rendering thread between initialize/release.
 */
internal object NativeEglRenderer {
    init {
        System.loadLibrary("termuxxwayland_renderer")
    }

    external fun nativeInitialize(surface: Surface): Long
    external fun nativeRender(handle: Long, pixels: ByteArray, width: Int, height: Int): Boolean
    external fun nativeRelease(handle: Long)
}
