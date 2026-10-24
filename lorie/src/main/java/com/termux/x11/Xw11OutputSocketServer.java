package com.termux.x11;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Authenticated, one-session output socket bridge from the Termux-side capture
 * process to MainActivity. The abstract socket is not trusted by name alone:
 * every peer must prove the per-launch random token before its FD is handed to
 * the native frame receiver.
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
        this.activity = activity;
        this.socketName = socketName;
        this.expectedHandshake = "XW11AUTH " + sessionId + " " + token;
        this.sessionId = sessionId;
    }

    synchronized void start() throws IOException {
        if (server != null) return;
        server = new LocalServerSocket(socketName);
        thread = new Thread(this::acceptLoop, "XW11-output-ipc");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "Authenticated output socket ready for session " + sessionId);
    }

    private void acceptLoop() {
        while (!closed.get()) {
            LocalSocket peer = null;
            ParcelFileDescriptor descriptor = null;
            try {
                peer = server.accept();
                peer.setSoTimeout(3000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                    peer.getInputStream(), StandardCharsets.US_ASCII));
                String handshake = reader.readLine();
                if (!expectedHandshake.equals(handshake)) {
                    Log.w(TAG, "Rejected unauthenticated XW11 output peer");
                    continue;
                }
                // Consume the authentication line before handing the same stream
                // to native; no frame bytes are read by Java beyond the newline.
                peer.setSoTimeout(0);
                descriptor = ParcelFileDescriptor.dup(peer.getFileDescriptor());
                ParcelFileDescriptor accepted = descriptor;
                descriptor = null;
                activity.runOnUiThread(() -> {
                    try {
                        if (closed.get() || !activity.attachXw11OutputTransport(accepted, sessionId)) {
                            Log.e(TAG, "Authenticated output socket could not attach to Surface");
                            try { accepted.close(); } catch (IOException ignored) {}
                        } else {
                            Log.i(TAG, "Output transport attached; awaiting first frame");
                        }
                    } catch (RuntimeException error) {
                        Log.e(TAG, "Output transport attach failed", error);
                        try { accepted.close(); } catch (IOException ignored) {}
                    }
                });
                // The duplicate FD is now owned by the activity/view. Close this
                // accepted socket wrapper; the duplicated descriptor stays alive.
                break;
            } catch (IOException error) {
                if (!closed.get()) Log.e(TAG, "XW11 output socket accept failed", error);
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
            server = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }
}
