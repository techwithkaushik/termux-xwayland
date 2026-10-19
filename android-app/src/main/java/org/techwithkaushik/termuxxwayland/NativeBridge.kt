package org.techwithkaushik.termuxxwayland

/**
 * JNI entry point for the optional Rust socket diagnostic reader.
 *
 * Native code duplicates the descriptor, so the caller retains ownership
 * of the original LocalSocket/FileDescriptor.
 */
object NativeBridge {
    init {
        System.loadLibrary("termux_xwayland_host")
    }

    /**
     * Reads an already-connected Unix stream socket until peer close,
     * a read error, or the native read timeout.
     *
     * This blocks. Always call it from a worker thread, never the UI thread.
     * Returns 0 on orderly close and -1 on error/timeout.
     */
    external fun nativeReadSocket(fileDescriptor: java.io.FileDescriptor): Int
}
