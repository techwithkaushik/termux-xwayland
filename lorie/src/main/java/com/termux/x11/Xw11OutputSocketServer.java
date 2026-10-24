package com.termux.x11;

import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authenticated IPv4-loopback output bridge for Termux/Android IPC. */
final class Xw11OutputSocketServer implements AutoCloseable {
    private static final String TAG = "XW11OutputIPC";
    private final int port;
    private final String expectedHandshake;
    private final long sessionId;
    private final MainActivity activity;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private ServerSocket server;
    private Thread thread;

    Xw11OutputSocketServer(@NonNull MainActivity activity, @NonNull String portText,
                           @NonNull String token, long sessionId) {
        int parsedPort;
        try { parsedPort = Integer.parseInt(portText); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid XW11 loopback port", error); }
        if (parsedPort < 1024 || parsedPort > 65535 ||
            !token.matches("[A-Fa-f0-9]{64}") || sessionId <= 0 ||
            sessionId > 0xffffffffL) {
            throw new IllegalArgumentException("Invalid XW11 output IPC parameters");
        }
        this.port = parsedPort;
        this.expectedHandshake = "XW11AUTH " + sessionId + " " + token;
        this.sessionId = sessionId;
        this.activity = activity;
    }

    synchronized void start() throws IOException {
        if (server != null) return;
        server = new ServerSocket();
        server.setReuseAddress(false);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1);
        thread = new Thread(this::acceptLoop, "XW11-output-ipc");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "Authenticated loopback TCP output socket ready on 127.0.0.1:" + port + " for session " + sessionId);
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
            Socket peer = null;
            ParcelFileDescriptor descriptor = null;
            try {
                peer = server.accept();
                if (!peer.getInetAddress().isLoopbackAddress())
                    throw new IOException("Rejected non-loopback XW11 output peer");
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
                descriptor = ParcelFileDescriptor.fromSocket(peer);
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
                // Keep the peer alive: the descriptor shares its connected socket.
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
