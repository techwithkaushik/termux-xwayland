package com.termux.x11;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Authenticated abstract AF_UNIX output bridge. TCP is intentionally not used
 * by this transport; the future X-server-argument TCP path must be validated
 * independently before it is enabled.
 */
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
        if (!socketName.matches("[A-Za-z0-9_.-]{1,80}") ||
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
        server = new LocalServerSocket(new LocalSocketAddress(
                socketName, LocalSocketAddress.Namespace.ABSTRACT));
        thread = new Thread(this::acceptLoop, "XW11-output-ipc");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "Authenticated abstract AF_UNIX output socket ready for session " + sessionId);
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
                output.write(("XW11READY " + generation + "\n").getBytes(StandardCharsets.US_ASCII));
                output.flush();
                peer.setSoTimeout(0);
                descriptor = ParcelFileDescriptor.dup(peer.getFileDescriptor());
                ParcelFileDescriptor accepted = descriptor;
                descriptor = null;
                final long readyGeneration = generation;
                activity.runOnUiThread(() -> {
                    try {
                        if (closed.get() || !activity.attachXw11OutputTransport(accepted, sessionId)) {
                            Log.e(TAG, "Authenticated output socket could not attach to Surface");
                            try { accepted.close(); } catch (IOException ignored) {}
                        } else {
                            Log.i(TAG, "Output transport attached; generation=" + readyGeneration);
                        }
                    } catch (RuntimeException error) {
                        Log.e(TAG, "Output transport attach failed", error);
                        try { accepted.close(); } catch (IOException ignored) {}
                    }
                });
                // Keep peer alive: the duplicated descriptor shares its socket.
                peer = null;
                break;
            } catch (IOException error) {
                if (!closed.get()) Log.e(TAG, "XW11 output socket accept failed", error);
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
