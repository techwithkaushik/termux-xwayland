package org.techwithkaushik.termuxxwayland.client;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Termux-side direct AF_UNIX client, launched by app_process under the existing
 * Termux UID. It deliberately never creates an Android Context or calls
 * bindService/Binder, avoiding MIUI hidden-context failures and Binder policy.
 */
public final class BridgeClient {
    private static final int VERSION = 1;
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;
    private static final byte[] MAGIC = new byte[] { 0x54, 0x58, 0x57, 0x42 };
    private static final int HELLO = 1, WELCOME = 2, FRAME = 3, PING = 7, PONG = 8, CLOSE = 9;
    private static final int FRAME_WIDTH = 640, FRAME_HEIGHT = 360;
    private static final int FRAME_BYTES = FRAME_WIDTH * FRAME_HEIGHT * 4;
    private static volatile Process activeCapture;
    private static final Object OUTPUT_LOCK = new Object();

    private BridgeClient() {}

    public static void main(String[] args) {
        if (args.length > 0 && "--help".equals(args[0])) {
            System.out.println("BridgeClient: connects to the authenticated Termux-XWayland abstract Unix socket.");
            return;
        }
        String sessionId = System.getenv("TERMUX_XWAYLAND_SESSION_ID");
        if (sessionId == null || !sessionId.matches("[a-f0-9]{32}")) {
            System.err.println("SOCKET_CLIENT_ERROR: TERMUX_XWAYLAND_SESSION_ID must be 32 lowercase hex characters.");
            System.exit(2);
            return;
        }

        LocalSocket socket = null;
        try {
            socket = connectWithRetry("termux_xwayland_" + sessionId);
            socket.setSoTimeout(10000);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            writeFrame(output, HELLO, sessionId.getBytes(StandardCharsets.US_ASCII));
            Frame welcome = readFrame(input);
            if (welcome.kind != WELCOME || welcome.payload.length != 4) {
                throw new IOException("Invalid direct-socket WELCOME");
            }
            long termuxUid = ((long)(welcome.payload[0] & 0xff) << 24)
                | ((long)(welcome.payload[1] & 0xff) << 16)
                | ((long)(welcome.payload[2] & 0xff) << 8)
                | (long)(welcome.payload[3] & 0xff);
            if (termuxUid != Process.myUid()) {
                throw new SecurityException("Kernel peer UID mismatch: WELCOME=" + termuxUid
                    + " but Termux process UID=" + Process.myUid());
            }
            System.out.println("SESSION_CONNECTED host_uid=" + termuxUid + " transport=abstract-unix-socket");
            System.out.flush();

            Process capture = startFrameCapture();
            activeCapture = capture;
            if (capture != null) startFrameForwarder(capture, output);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Process running = activeCapture;
                if (running != null) running.destroy();
                try { socket.close(); } catch (Exception ignored) {}
            }, "termux-xwayland-socket-cleanup"));

            while (true) {
                writeFrame(output, PING, new byte[0]);
                Frame response = readFrame(input);
                if (response.kind == CLOSE) break;
                if (response.kind != PONG || response.payload.length != 0) {
                    throw new IOException("Unexpected direct-socket heartbeat response");
                }
                System.out.println("SESSION_HEALTHY epoch_seconds=" + (System.currentTimeMillis() / 1000L)
                    + " transport=abstract-unix-socket");
                System.out.flush();
                Thread.sleep(5000L);
            }
        } catch (Exception error) {
            System.err.println("SOCKET_CLIENT_ERROR: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            System.err.flush();
            System.exit(4);
        } finally {
            Process capture = activeCapture;
            if (capture != null) capture.destroy();
            if (socket != null) try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static LocalSocket connectWithRetry(String name) throws Exception {
        IOException last = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            LocalSocket socket = new LocalSocket();
            try {
                socket.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
                System.out.println("SOCKET_CONNECTED name=@" + name);
                System.out.flush();
                return socket;
            } catch (IOException error) {
                last = error;
                try { socket.close(); } catch (IOException ignored) {}
                Thread.sleep(250L);
            }
        }
        throw new IOException("Android direct socket did not become ready within 10 seconds", last);
    }

    private static Process startFrameCapture() {
        String display = System.getenv("DISPLAY");
        if (display == null || display.trim().isEmpty()) {
            System.out.println("FRAME_CAPTURE_UNAVAILABLE reason=DISPLAY_not_set");
            System.out.flush();
            return null;
        }
        String ffmpeg = findExecutable("ffmpeg");
        if (ffmpeg == null) {
            System.out.println("FRAME_CAPTURE_UNAVAILABLE reason=ffmpeg_not_installed");
            System.out.flush();
            return null;
        }
        try {
            Process process = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error",
                "-f", "x11grab", "-framerate", "2", "-video_size", "640x360",
                "-i", display, "-vf", "scale=640:360", "-pix_fmt", "rgba",
                "-f", "rawvideo", "pipe:1")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            System.out.println("FRAME_CAPTURE_STARTED display=" + display + " size=640x360 fps=2");
            System.out.flush();
            return process;
        } catch (IOException error) {
            System.out.println("FRAME_CAPTURE_UNAVAILABLE reason=process_start_failed");
            System.out.flush();
            return null;
        }
    }

    private static String findExecutable(String name) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String directory : path.split(File.pathSeparator)) {
            File candidate = new File(directory, name);
            if (candidate.isFile() && candidate.canExecute()) return candidate.getAbsolutePath();
        }
        return null;
    }

    private static void startFrameForwarder(Process capture, DataOutputStream output) {
        Thread thread = new Thread(() -> {
            byte[] pixels = new byte[FRAME_BYTES];
            byte[] payload = new byte[8 + FRAME_BYTES];
            payload[0] = (byte)(FRAME_WIDTH >>> 8);
            payload[1] = (byte)FRAME_WIDTH;
            payload[2] = (byte)(FRAME_HEIGHT >>> 8);
            payload[3] = (byte)FRAME_HEIGHT;
            payload[4] = 1; // RGBA8888
            long lastLog = 0L;
            try (DataInputStream raw = new DataInputStream(capture.getInputStream())) {
                while (!Thread.currentThread().isInterrupted()) {
                    raw.readFully(pixels);
                    System.arraycopy(pixels, 0, payload, 8, pixels.length);
                    writeFrame(output, FRAME, payload);
                    long now = System.currentTimeMillis();
                    if (lastLog == 0L || now - lastLog >= 5000L) {
                        lastLog = now;
                        System.out.println("FRAME_SENT epoch_seconds=" + (now / 1000L)
                            + " transport=abstract-unix-socket");
                        System.out.flush();
                    }
                }
            } catch (IOException error) {
                System.out.println("FRAME_CAPTURE_STOPPED reason=" + error.getClass().getSimpleName());
                System.out.flush();
            }
        }, "termux-xwayland-frame-forwarder");
        thread.setDaemon(true);
        thread.start();
    }

    private static Frame readFrame(DataInputStream input) throws IOException {
        byte[] header = new byte[12];
        input.readFully(header);
        for (int i = 0; i < MAGIC.length; i++) {
            if (header[i] != MAGIC[i]) throw new IOException("Invalid TXWB magic");
        }
        if ((header[4] & 0xff) != VERSION) throw new IOException("Unsupported TXWB version");
        if (header[6] != 0 || header[7] != 0) throw new IOException("Nonzero TXWB reserved bits");
        int kind = header[5] & 0xff;
        long length = ((long)(header[8] & 0xff) << 24) | ((long)(header[9] & 0xff) << 16)
            | ((long)(header[10] & 0xff) << 8) | (long)(header[11] & 0xff);
        if (length > MAX_PAYLOAD) throw new IOException("TXWB payload exceeds limit");
        byte[] payload = new byte[(int)length];
        input.readFully(payload);
        return new Frame(kind, payload);
    }

    private static void writeFrame(DataOutputStream output, int kind, byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD) throw new IOException("TXWB payload exceeds limit");
        synchronized (OUTPUT_LOCK) {
            output.write(MAGIC);
            output.writeByte(VERSION);
            output.writeByte(kind);
            output.writeShort(0);
            output.writeInt(payload.length);
            output.write(payload);
            output.flush();
        }
    }

    private static final class Frame {
        final int kind;
        final byte[] payload;
        Frame(int kind, byte[] payload) { this.kind = kind; this.payload = payload; }
    }
}
