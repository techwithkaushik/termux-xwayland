package org.techwithkaushik.termuxxwayland.client;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * Pure socket client launched by app_process in the existing Termux UID.
 * It does not construct an Android Context or call ActivityManager/Binder.
 */
public final class BridgeClient {
    private static final String TAG = "TermuxXWaylandClient";
    private static final String SOCKET_NAME = "termux-xwayland-control.sock";
    private static final byte[] MAGIC = new byte[] { 0x54, 0x58, 0x57, 0x42 };
    private static final int VERSION = 1;
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;
    private static final int WELCOME = 2, FRAME = 3, PING = 7, PONG = 8, CLOSE = 9;
    private static final int WIDTH = 640, HEIGHT = 360, FRAME_BYTES = WIDTH * HEIGHT * 4;
    private static volatile Process activeCapture;

    private BridgeClient() {}

    public static void main(String[] args) {
        if (args.length > 0 && "--help".equals(args[0])) {
            System.out.println("BridgeClient: connects directly to the abstract Unix socket.");
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process capture = activeCapture;
            if (capture != null) capture.destroy();
        }, "txw-capture-cleanup"));

        try (LocalSocket socket = connect();
             DataInputStream input = new DataInputStream(socket.getInputStream());
             DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
            socket.setSoTimeout(10_000);
            Message welcome = readMessage(input);
            if (welcome.kind != WELCOME) throw new IOException("Expected TXWB WELCOME");
            Log.i(TAG, "Connected to abstract socket; TXWB session started");
            System.out.println("SESSION_CONNECTED transport=abstract-unix");
            System.out.flush();

            Process capture = startFrameCapture();
            activeCapture = capture;
            if (capture != null) startFrameForwarder(capture, output);

            while (true) {
                writeMessage(output, PING, new byte[0]);
                Message response = readMessage(input);
                if (response.kind == CLOSE) break;
                if (response.kind != PONG || response.payload.length != 0) {
                    throw new IOException("Unexpected response to TXWB PING");
                }
                Thread.sleep(2000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            Log.e(TAG, "Abstract socket session failed", e);
            System.err.println("SOCKET_CLIENT_ERROR: " + e.getMessage());
            System.err.flush();
            System.exit(1);
        } finally {
            Process capture = activeCapture;
            if (capture != null) capture.destroy();
            activeCapture = null;
        }
    }

    private static LocalSocket connect() throws IOException {
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(
                SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            return socket;
        } catch (IOException e) {
            try { socket.close(); } catch (IOException ignored) {}
            throw new IOException("Cannot connect to abstract socket @" + SOCKET_NAME, e);
        }
    }

    private static Message readMessage(DataInputStream in) throws IOException {
        byte[] header = new byte[12];
        in.readFully(header);
        for (int i = 0; i < MAGIC.length; i++) {
            if (header[i] != MAGIC[i]) throw new IOException("Invalid TXWB magic");
        }
        if ((header[4] & 0xff) != VERSION) throw new IOException("Unsupported TXWB version");
        if (header[6] != 0 || header[7] != 0) throw new IOException("Reserved bits must be zero");
        int kind = header[5] & 0xff;
        long size = ((long)(header[8] & 0xff) << 24)
            | ((long)(header[9] & 0xff) << 16)
            | ((long)(header[10] & 0xff) << 8)
            | (long)(header[11] & 0xff);
        if (size > MAX_PAYLOAD) throw new IOException("TXWB payload exceeds limit");
        byte[] payload = new byte[(int) size];
        in.readFully(payload);
        return new Message(kind, payload);
    }

    private static void writeMessage(DataOutputStream out, int kind, byte[] payload)
            throws IOException {
        if (payload.length > MAX_PAYLOAD) throw new IOException("TXWB payload exceeds limit");
        synchronized (out) {
            out.write(MAGIC);
            out.writeByte(VERSION);
            out.writeByte(kind);
            out.writeShort(0);
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
        }
    }

    private static Process startFrameCapture() {
        String display = System.getenv("DISPLAY");
        String ffmpeg = findExecutable("ffmpeg");
        if (display == null || display.isEmpty() || ffmpeg == null) {
            System.out.println("FRAME_CAPTURE_UNAVAILABLE reason=DISPLAY_or_ffmpeg_missing");
            return null;
        }
        try {
            return new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error",
                "-f", "x11grab", "-framerate", "2", "-video_size", "640x360",
                "-i", display, "-vf", "scale=640:360", "-pix_fmt", "rgba",
                "-f", "rawvideo", "pipe:1")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            Log.w(TAG, "Could not start optional X11 frame capture", e);
            return null;
        }
    }

    private static String findExecutable(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(File.pathSeparator)) {
            File file = new File(dir, name);
            if (file.isFile() && file.canExecute()) return file.getAbsolutePath();
        }
        return null;
    }

    private static void startFrameForwarder(Process capture, DataOutputStream output) {
        Thread worker = new Thread(() -> {
            byte[] pixels = new byte[FRAME_BYTES];
            byte[] payload = new byte[8 + FRAME_BYTES];
            payload[0] = (byte)(WIDTH >>> 8); payload[1] = (byte)WIDTH;
            payload[2] = (byte)(HEIGHT >>> 8); payload[3] = (byte)HEIGHT;
            payload[4] = 1;
            try (DataInputStream raw = new DataInputStream(capture.getInputStream())) {
                while (!Thread.currentThread().isInterrupted()) {
                    raw.readFully(pixels);
                    System.arraycopy(pixels, 0, payload, 8, pixels.length);
                    writeMessage(output, FRAME, payload);
                }
            } catch (IOException e) {
                Log.i(TAG, "Frame forwarding stopped: " + e.getMessage());
            }
        }, "txw-frame-forwarder");
        worker.setDaemon(true);
        worker.start();
    }

    private static final class Message {
        final int kind;
        final byte[] payload;
        Message(int kind, byte[] payload) { this.kind = kind; this.payload = payload; }
    }
}
