package org.techwithkaushik.termuxxwayland.client;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs inside the existing Termux UID via app_process. No companion APK,
 * RUN_COMMAND permission, root, TCP listener, or SELinux change is required.
 */
public final class BridgeClient {
    private static final String TAG = "TermuxXWaylandClient";
    private static final String TERMUX_PACKAGE = "com.termux";
    private static final String[] HOST_PACKAGES = {
        "org.techwithkaushik.termuxxwayland",
        "org.techwithkaushik.termuxxwayland.debug"
    };
    private static final String SERVICE_CLASS =
        "org.techwithkaushik.termuxxwayland.BridgeSessionService";
    private static final String DESCRIPTOR =
        "org.techwithkaushik.termuxxwayland.IBridgeSession";
    private static final int OPEN_SESSION = IBinder.FIRST_CALL_TRANSACTION;
    private static final int VERSION = 1;
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;
    private static final byte[] MAGIC = new byte[] { 0x54, 0x58, 0x57, 0x42 };
    private static final int WELCOME = 2;
    private static final int PING = 7;
    private static final int PONG = 8;
    private static final int CLOSE = 9;
    private static final int FRAME = 3;
    private static final int FRAME_WIDTH = 640;
    private static final int FRAME_HEIGHT = 360;
    private static final int FRAME_BYTES = FRAME_WIDTH * FRAME_HEIGHT * 4;
    private static volatile Process activeFrameCapture;
    private static final Object LOG_LOCK = new Object();
    private static final String LOG_PATH = System.getenv("TERMUX_XWAYLAND_LOG");

    private BridgeClient() {}

    public static void main(String[] args) {
        if (args.length > 0 && "--help".equals(args[0])) {
            System.out.println("BridgeClient: opens a Termux-authorized Binder session and maintains TXWB PING/PONG.");
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Process capture = activeFrameCapture;
            if (capture != null) capture.destroy();
        }, "termux-xwayland-capture-cleanup"));
        // ActivityThread/ContextImpl may construct Handlers during initialization.
        // Prepare the main looper before constructing any Android app context.
        try {
            if (Looper.myLooper() == null) Looper.prepareMainLooper();
        } catch (RuntimeException error) {
            System.err.println("BINDER_CLIENT_ERROR: cannot prepare Android looper: " + error);
            System.exit(2);
            return;
        }

        final Context context;
        try {
            context = createSystemContext();
        } catch (Exception error) {
            System.err.println("BINDER_CLIENT_ERROR: cannot create system context: " + error);
            error.printStackTrace(System.err);
            System.exit(2);
            return;
        }

        final String hostPackage = findHostPackage(context);
        if (hostPackage == null) {
            System.err.println("BINDER_CLIENT_ERROR: Termux-XWayland host APK is not installed.");
            System.exit(3);
            return;
        }
        final int hostUid;
        try {
            hostUid = context.getPackageManager().getApplicationInfo(hostPackage, 0).uid;
        } catch (PackageManager.NameNotFoundException error) {
            System.err.println("BINDER_CLIENT_ERROR: host package disappeared: " + hostPackage);
            System.exit(3);
            return;
        }

        ClientEndpoint endpoint = new ClientEndpoint(hostUid);
        android.os.Bundle bundle = new android.os.Bundle();
        bundle.putBinder(EXTRA_CLIENT_BINDER, endpoint);
        Intent intent = new Intent(ACTION_OPEN_SESSION)
            .setPackage(hostPackage)
            .putExtra(EXTRA_BUNDLE, bundle);
        sendSessionBroadcast(context, intent);
        System.out.println("BINDER_CONNECTING host=" + hostPackage + " transport=upstream-broadcast-binder");
        System.out.flush();
        Looper.loop();
    }

    private static void sendSessionBroadcast(Context context, Intent intent) {
        try {
            context.sendBroadcast(intent);
            return;
        } catch (RuntimeException firstError) {
            System.err.println("BINDER_BROADCAST_CONTEXT_FALLBACK: " + firstError.getClass().getSimpleName());
        }

        /*
         * Termux:X11 uses the ActivityManager broadcast path as a fallback when
         * a system Context's package attribution is rejected. Invoke the public
         * Binder interface reflectively so this client remains SDK-compilable.
         */
        try {
            Class<?> activityManager = Class.forName("android.app.ActivityManager");
            Object manager = activityManager.getMethod("getService").invoke(null);
            Method broadcast = null;
            for (Method method : manager.getClass().getMethods()) {
                if ("broadcastIntent".equals(method.getName())
                        && method.getParameterTypes().length == 13) {
                    broadcast = method;
                    break;
                }
            }
            if (broadcast == null) throw new NoSuchMethodException("IActivityManager.broadcastIntent");
            Object[] args = new Object[] {
                null, intent, null, null, 0, null, null, null, -1, null, false, false, 0
            };
            Object result = broadcast.invoke(manager, args);
            if (result instanceof Integer && ((Integer) result) < 0) {
                throw new IOException("ActivityManager rejected the session broadcast: " + result);
            }
            return;
        } catch (Exception fallbackError) {
            System.err.println("BINDER_CLIENT_ERROR: session broadcast failed: " + fallbackError);
            fallbackError.printStackTrace(System.err);
            System.exit(4);
        }
    }

    private static Context createSystemContext() throws Exception {
        /*
         * Follow Termux:X11's app_process bootstrap: allocate ActivityThread without
         * running its constructor and obtain the platform context. This context is
         * used only to send a package-targeted broadcast carrying our Binder endpoint;
         * we never bind an Android service from the synthetic app_process caller.
         */
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Object activityThread = unsafeClass.getMethod("allocateInstance", Class.class)
            .invoke(unsafe, Class.forName("android.app.ActivityThread"));
        Method getSystemContext = activityThread.getClass().getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        PrintStream originalErr = System.err;
        Object value;
        try {
            // MIUI 12 can print a harmless missing theme-compatibility XML trace.
            System.setErr(new PrintStream(new OutputStream() {
                @Override public void write(int ignored) {}
            }));
            value = getSystemContext.invoke(activityThread);
        } finally {
            System.setErr(originalErr);
        }
        if (!(value instanceof Context)) {
            throw new IllegalStateException("Android did not provide a system context");
        }
        return (Context) value;
    }

    private static String findHostPackage(Context context) {
        PackageManager packageManager = context.getPackageManager();
        for (String packageName : HOST_PACKAGES) {
            try {
                packageManager.getPackageInfo(packageName, 0);
                return packageName;
            } catch (PackageManager.NameNotFoundException ignored) {
                // Try the other supported application ID.
            }
        }
        return null;
    }

    private static final String ACTION_OPEN_SESSION =
        "org.techwithkaushik.termuxxwayland.action.OPEN_TERMUX_SESSION";
    private static final String EXTRA_BUNDLE = "termuxxwayland.session.bundle";
    private static final String EXTRA_CLIENT_BINDER = "termuxxwayland.session.client_binder";

    /**
     * The host calls into this Binder after receiving it in a package-targeted
     * broadcast. This reverses the old broken flow: app_process never calls
     * Context.bindService(), so ActivityManager does not need to register it as
     * an Android application process.
     */
    private static final class ClientEndpoint extends Binder {
        private final int expectedHostUid;
        private final AtomicBoolean opened = new AtomicBoolean(false);

        ClientEndpoint(int expectedHostUid) {
            this.expectedHostUid = expectedHostUid;
            attachInterface(null, DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws android.os.RemoteException {
            if (code != OPEN_SESSION) return super.onTransact(code, data, reply, flags);
            data.enforceInterface(DESCRIPTOR);
            if (Binder.getCallingUid() != expectedHostUid) {
                throw new SecurityException("Only the installed Termux-XWayland host may open this session");
            }
            if (data.readInt() != VERSION) {
                reply.writeException(new IllegalArgumentException("Unsupported bridge protocol version"));
                return true;
            }
            if (!opened.compareAndSet(false, true)) {
                reply.writeException(new IllegalStateException("A session is already open"));
                return true;
            }
            ParcelFileDescriptor[] pair;
            try {
                pair = ParcelFileDescriptor.createSocketPair();
            } catch (IOException error) {
                opened.set(false);
                reply.writeException(error);
                return true;
            }
            try {
                reply.writeNoException();
                reply.writeParcelable(pair[1], 0);
            } catch (RuntimeException error) {
                opened.set(false);
                try { pair[0].close(); } catch (IOException ignored) {}
                throw error;
            } finally {
                try { pair[1].close(); } catch (IOException ignored) {}
            }
            Thread worker = new Thread(() -> {
                try {
                    runSession(pair[0]);
                } catch (Exception error) {
                    System.err.println("BINDER_SESSION_ERROR: " + error);
                    System.err.flush();
                } finally {
                    opened.set(false);
                    Looper.getMainLooper().quit();
                }
            }, "termux-xwayland-binder-session");
            worker.setDaemon(false);
            worker.start();
            return true;
        }
    }

    private static void runSession(ParcelFileDescriptor descriptor) throws Exception {
        if (descriptor == null) throw new IOException("Host returned no session FD");

        try (ParcelFileDescriptor ownedDescriptor = descriptor) {
            DataInputStream input = new DataInputStream(new FileInputStream(ownedDescriptor.getFileDescriptor()));
            DataOutputStream output = new DataOutputStream(new FileOutputStream(ownedDescriptor.getFileDescriptor()));
            Frame welcome = readFrame(input);
            if (welcome.kind != WELCOME || welcome.payload.length != 4) {
                throw new IOException("Invalid TXWB WELCOME frame");
            }
            long hostUid = ((long) (welcome.payload[0] & 0xff) << 24)
                | ((long) (welcome.payload[1] & 0xff) << 16)
                | ((long) (welcome.payload[2] & 0xff) << 8)
                | (long) (welcome.payload[3] & 0xff);
            System.out.println("SESSION_WELCOME host_uid=" + hostUid + " transport=binder-fd");
            System.out.flush();
            boolean connected = false;
            Process frameCapture = null;
            try {
                while (true) {
                    writeFrame(output, PING, new byte[0]);
                    Frame pong = readFrame(input);
                    if (pong.kind != PONG || pong.payload.length != 0) {
                        if (pong.kind == CLOSE) break;
                        throw new IOException("Unexpected response to TXWB PING");
                    }
                    if (!connected) {
                        connected = true;
                        System.out.println("SESSION_CONNECTED host_uid=" + hostUid + " transport=binder-fd");
                        System.out.flush();
                        // Frame capture must never block the PING/PONG heartbeat loop.
                        Thread captureStarter = new Thread(() -> {
                            Process capture = startFrameCapture();
                            activeFrameCapture = capture;
                            if (capture != null) startFrameForwarder(capture, output);
                        }, "termux-xwayland-frame-capture-start");
                        captureStarter.setDaemon(true);
                        captureStarter.start();
                    }
                    System.out.println("SESSION_HEALTHY epoch_seconds=" + (System.currentTimeMillis() / 1000L)
                        + " transport=binder-fd");
                    System.out.flush();
                    Thread.sleep(5000L);
                }
            } finally {
                if (frameCapture != null) frameCapture.destroy();
                activeFrameCapture = null;
            }
        }
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
            Process process = new ProcessBuilder(
                ffmpeg, "-hide_banner", "-loglevel", "error",
                "-f", "x11grab", "-framerate", "2", "-video_size", "640x360",
                "-i", display, "-vf", "scale=640:360", "-pix_fmt", "rgba",
                "-f", "rawvideo", "pipe:1"
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start();
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
            payload[0] = (byte) (FRAME_WIDTH >>> 8);
            payload[1] = (byte) FRAME_WIDTH;
            payload[2] = (byte) (FRAME_HEIGHT >>> 8);
            payload[3] = (byte) FRAME_HEIGHT;
            payload[4] = 1;
            long lastLog = 0L;
            try (DataInputStream raw = new DataInputStream(capture.getInputStream())) {
                while (!Thread.currentThread().isInterrupted()) {
                    raw.readFully(pixels);
                    System.arraycopy(pixels, 0, payload, 8, pixels.length);
                    writeFrame(output, FRAME, payload);
                    long now = System.currentTimeMillis();
                    if (lastLog == 0L || now - lastLog >= 5000L) {
                        lastLog = now;
                        System.out.println("FRAME_SENT epoch_seconds=" + (now / 1000L) + " transport=binder-fd");
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

    private static void log(String level, String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date());
        String line = timestamp + " " + level + " [" + Thread.currentThread().getName() + "] " + message;
        synchronized (LOG_LOCK) {
            System.out.println(line);
            System.out.flush();
            if (LOG_PATH != null && !LOG_PATH.isEmpty()) {
                try (FileOutputStream file = new FileOutputStream(LOG_PATH, true)) {
                    file.write((line + "\n").getBytes("UTF-8"));
                } catch (IOException error) {
                    System.err.println("LOGGER_WRITE_FAILED path=" + LOG_PATH + " error=" + error);
                }
            }
        }
    }

    private static void logError(String message, Throwable error) {
        log("ERROR", message + " type=" + error.getClass().getName() + " message=" + error.getMessage());
        java.io.StringWriter buffer = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(buffer));
        for (String line : buffer.toString().split("\\r?\\n")) log("ERROR", line);
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
        if (kind < 1 || kind > 9) throw new IOException("Unknown TXWB message kind");
        long length = ((long) (header[8] & 0xff) << 24)
            | ((long) (header[9] & 0xff) << 16)
            | ((long) (header[10] & 0xff) << 8)
            | (long) (header[11] & 0xff);
        if (length > MAX_PAYLOAD) throw new IOException("TXWB payload exceeds limit");
        byte[] payload = new byte[(int) length];
        input.readFully(payload);
        return new Frame(kind, payload);
    }

    private static void writeFrame(DataOutputStream output, int kind, byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD) throw new IOException("TXWB payload exceeds limit");
        synchronized (output) {
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
        Frame(int kind, byte[] payload) {
            this.kind = kind;
            this.payload = payload;
        }
    }
}
