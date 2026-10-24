package com.termux.x11;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authenticated abstract AF_UNIX output bridge shared by Termux and Android. */
final class Xw11OutputSocketServer implements AutoCloseable {
    private static final String TAG = "XW11OutputIPC";
    private final String socketName;
    private final String expectedHandshake;
    private final long sessionId;
    private final MainActivity activity;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private LocalServerSocket server;
    private Thread thread;

    Xw11OutputSocketServer(@NonNull MainActivity activity, @NonNull String socketName,
                           @NonNull String token, long sessionId) {
        if (!socketName.matches("[A-Za-z0-9_-]{1,80}") ||
            !token.matches("[A-Fa-f0-9]{64}") || sessionId <= 0 ||
            sessionId > 0xffffffffL) {
            throw new IllegalArgumentException("Invalid XW11 output IPC parameters");
        }
        this.socketName = socketName;
        this.expectedHandshake = "XW11AUTH " + sessionId + " " + token;
        this.sessionId = sessionId;
        this.activity = activity;
    }

    synchronized void start() throws IOException {
        if (server != null) return;
        server = new LocalServerSocket(socketName); // Linux abstract AF_UNIX namespace
        thread = new Thread(this::acceptLoop, "XW11-output-ipc");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "Authenticated abstract AF_UNIX output IPC ready for session " + sessionId);
    }

    private static String readHandshake(InputStream input) throws IOException {
        byte[] bytes = new byte[160];
        int length = 0;
        while (length < bytes.length) {
            int value = input.read();
            if (value < 0) throw new IOException("Peer closed before authentication");
            if (value == '\n')
                return new String(bytes, 0, length, StandardCharsets.US_ASCII);
            if (value == '\r' || value < 0x20 || value > 0x7e)
                throw new IOException("Invalid authentication line");
            bytes[length++] = (byte) value;
        }
        throw new IOException("Authentication line too long");
    }

    private void acceptLoop() {
        while (!closed.get()) {
            LocalSocket peer = null;
            ParcelFileDescriptor descriptor = null;
            try {
                peer = server.accept();
                peer.setSoTimeout(3000);
                String handshake = readHandshake(peer.getInputStream());
                if (!expectedHandshake.equals(handshake)) {
                    Log.w(TAG, "Rejected unauthenticated XW11 output peer");
                    continue;
                }
                activity.runOnUiThread(activity::prepareXw11OutputSurface);
                long generation = 0;
                long deadline = android.os.SystemClock.uptimeMillis() + 5000;
                while (!closed.get() && android.os.SystemClock.uptimeMillis() < deadline) {
                    generation = activity.outputSurfaceGenerationForIpc();
                    if (generation > 0) break;
                    Thread.sleep(20);
                }
                if (generation <= 0)
                    throw new IOException("Android output Surface did not become ready");
                OutputStream output = peer.getOutputStream();
                output.write(("XW11READY " + generation + "\\n").getBytes(StandardCharsets.US_ASCII));
                output.flush();
                peer.setSoTimeout(0);
                // Duplicate the connected AF_UNIX fd before handing ownership to
                // the native receiver. LocalSocket itself can then be closed safely.
                descriptor = ParcelFileDescriptor.dup(peer.getFileDescriptor());
                ParcelFileDescriptor accepted = descriptor;
                descriptor = null;
                final long readyGeneration = generation;
                activity.runOnUiThread(() -> {
                    try {
                        if (closed.get() || !activity.attachXw11OutputTransport(accepted, sessionId)) {
                            Log.e(TAG, "Authenticated output IPC could not attach to Surface");
                        } else {
                            Log.i(TAG, "Output IPC attached; generation=" + readyGeneration);
                        }
                    } catch (RuntimeException error) {
                        Log.e(TAG, "Output IPC attach failed", error);
                    } finally {
                        // attachOutputTransport duplicates the fd into its retained handle.
                        // Always close this accepted descriptor to avoid leaking one per session.
                        try { accepted.close(); } catch (IOException ignored) {}
                    }
                });
                peer.close();
                peer = null;
                break;
            } catch (IOException error) {
                if (!closed.get()) Log.e(TAG, "XW11 output IPC accept failed", error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                if (!closed.get()) Log.e(TAG, "Interrupted while waiting for output Surface", error);
            } finally {
                if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) {}
                if (peer != null) try { peer.close(); } catch (IOException ignored) {}
            }
        }
    }

    @Override public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (server != null) {
            try { server.close(); } catch (IOException ignored) {}
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }
}
