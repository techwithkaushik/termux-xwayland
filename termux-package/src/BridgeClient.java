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
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final int MAX_PAYLOAD = 32 * 1024 * 1024;
    private static final byte[] MAGIC = new byte[] { 0x54, 0x58, 0x57, 0x42 };
    private static final int WELCOME = 2;
    private static final int PING = 7;
    private static final int PONG = 8;
    private static final int CLOSE = 9;
    private static final int FRAME = 3;
    private static final int INPUT = 4;
    private static final int RESIZE = 5;
    private static final int CLIPBOARD = 6;
    // Weston/XWayland can run at the phone's native resolution, but transporting
    // a full 1080x2400 RGBA image for every frame creates ~10 MB packets and
    // guarantees stale frames on low-memory/ARM devices. Keep the desktop's
    // actual coordinates separate from a bounded, aspect-preserving stream.
    private static final int DISPLAY_WIDTH = envDimension("TERMUX_XWAYLAND_WIDTH", 640);
    private static final int DISPLAY_HEIGHT = envDimension("TERMUX_XWAYLAND_HEIGHT", 360);
    private static final int[] STREAM_DIMENSIONS = streamDimensions(DISPLAY_WIDTH, DISPLAY_HEIGHT);
    private static final int FRAME_WIDTH = STREAM_DIMENSIONS[0];
    private static final int FRAME_HEIGHT = STREAM_DIMENSIONS[1];
    private static final int FRAME_BYTES = Math.multiplyExact(Math.multiplyExact(FRAME_WIDTH, FRAME_HEIGHT), 4);
    private static int[] streamDimensions(int width, int height) {
        final long maxPixels = 640L * 360L;
        long pixels = (long) width * height;
        if (pixels <= maxPixels) return new int[] { width, height };
        double scale = Math.sqrt((double) maxPixels / (double) pixels);
        int streamWidth = Math.max(1, (int) Math.floor(width * scale));
        int streamHeight = Math.max(1, (int) Math.floor(height * scale));
        while ((long) streamWidth * streamHeight > maxPixels) {
            if ((double) streamWidth / streamHeight > (double) width / height) streamWidth--;
            else streamHeight--;
        }
        return new int[] { streamWidth, streamHeight };
    }

    private static volatile Process activeFrameCapture;
    private static volatile Process activeNativeInput;
    private static BufferedWriter nativeInputWriter;
    private static boolean nativeInputUnavailableLogged;
    // Keep slow xdotool process launches off the Binder protocol/heartbeat loop.
    // A single worker preserves key/button event ordering while the reader can
    // continue answering PINGs and accepting frames during input bursts.
    private static final ExecutorService INPUT_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "termux-xwayland-input");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    // Pointer motion is high-frequency and replaceable. Keep its queue bounded by
    // accumulating relative deltas and submitting only one drain task at a time.
    // Key/button/text events remain ordered on INPUT_EXECUTOR and never get dropped.
    private static final ExecutorService POINTER_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "termux-xwayland-pointer");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    private static final Object RELATIVE_MOTION_LOCK = new Object();
    private static int pendingRelativeX = 0;
    private static int pendingRelativeY = 0;
    private static boolean relativeMotionDrainQueued = false;

    private static int envDimension(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 320 && parsed <= 4096 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

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
                    // app_process prepared the main looper for Android framework
                    // initialization. Android forbids quitting that looper from a
                    // Binder worker; terminate this dedicated client process when its
                    // single Binder session ends instead.
                    System.exit(0);
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

            // One reader owns the FD input stream. Frames, PONG and Android INPUT
            // may arrive independently; treating every response as PONG broke duplex.
            final java.util.concurrent.BlockingQueue<Frame> incoming =
                new java.util.concurrent.LinkedBlockingQueue<>(32);
            final AtomicBoolean readerFailed = new AtomicBoolean(false);
            Thread reader = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Frame frame = readFrame(input);
                        if (!incoming.offer(frame, 2, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new IOException("TXWB inbound queue is full");
                        }
                        if (frame.kind == CLOSE) break;
                    }
                } catch (Exception error) {
                    readerFailed.set(true);
                    System.err.println("BINDER_READER_STOPPED: " + error);
                }
            }, "termux-xwayland-binder-reader");
            reader.setDaemon(true);
            reader.start();

            boolean connected = false;
            try {
                while (!readerFailed.get()) {
                    writeFrame(output, PING, new byte[0]);
                    long deadline = System.currentTimeMillis() + 5000L;
                    boolean gotPong = false;
                    while (System.currentTimeMillis() < deadline && !readerFailed.get()) {
                        Frame message = incoming.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (message == null) continue;
                        if (message.kind == PONG && message.payload.length == 0) {
                            gotPong = true;
                            break;
                        }
                        if (message.kind == INPUT) {
                            final byte[] inputPayload = message.payload;
                            if (!queueRelativeMotion(inputPayload)) {
                                INPUT_EXECUTOR.execute(() -> handleInput(inputPayload));
                            }
                            continue;
                        }
                        if (message.kind == CLOSE) return;
                        if (message.kind == FRAME || message.kind == RESIZE || message.kind == CLIPBOARD) {
                            System.err.println("UNEXPECTED_ANDROID_MESSAGE kind=" + message.kind);
                            continue;
                        }
                        throw new IOException("Unexpected inbound TXWB message kind=" + message.kind);
                    }
                    if (!gotPong) throw new IOException("Timed out waiting for TXWB PONG");
                    if (!connected) {
                        connected = true;
                        System.out.println("SESSION_CONNECTED host_uid=" + hostUid + " transport=binder-fd");
                        System.out.flush();
                        /*
                         * Capture is an optional native child process and may exit when
                         * Weston denies capture, its socket is recreated, or the helper
                         * encounters a transient Wayland failure. Do not leave a healthy
                         * Binder session permanently black after that first failure.
                         * Retry with bounded backoff; diagnostics from each attempt remain
                         * visible in the session log.
                         */
                        Thread captureStarter = new Thread(() -> {
                            long retryDelayMs = 1000L;
                            while (!Thread.currentThread().isInterrupted()) {
                                Process capture = startFrameCapture();
                                if (capture == null) {
                                    sleepBeforeCaptureRetry(retryDelayMs);
                                    retryDelayMs = Math.min(retryDelayMs * 2L, 10000L);
                                    continue;
                                }
                                activeFrameCapture = capture;
                                Thread forwarder = startFrameForwarder(capture, output);
                                try {
                                    while (!Thread.currentThread().isInterrupted()) {
                                        if (capture.waitFor(1000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                            break;
                                        }
                                        if (!forwarder.isAlive()) {
                                            System.err.println("FRAME_CAPTURE_RESTART reason=frame_forwarder_stopped");
                                            capture.destroy();
                                            break;
                                        }
                                    }
                                    if (Thread.currentThread().isInterrupted()) capture.destroy();
                                    int exitCode = capture.isAlive() ? -1 : capture.exitValue();
                                    System.out.println("FRAME_CAPTURE_EXIT exit_code=" + exitCode
                                        + " retry_delay_ms=" + retryDelayMs);
                                    System.out.flush();
                                    forwarder.join(1000L);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    capture.destroy();
                                } finally {
                                    if (activeFrameCapture == capture) activeFrameCapture = null;
                                }
                                if (Thread.currentThread().isInterrupted()) break;
                                sleepBeforeCaptureRetry(retryDelayMs);
                                retryDelayMs = Math.min(retryDelayMs * 2L, 10000L);
                            }
                        }, "termux-xwayland-frame-capture-start");
                        captureStarter.setDaemon(true);
                        captureStarter.start();
                    }
                    System.out.println("SESSION_HEALTHY epoch_seconds=" + (System.currentTimeMillis() / 1000L)
                        + " transport=binder-fd");
                    System.out.flush();
                }
            } finally {
                reader.interrupt();
                Process capture = activeFrameCapture;
                if (capture != null) capture.destroy();
                activeFrameCapture = null;
            }
        }
    }

    /** Decode bounded normalized touch events and route them to the active XWayland display. */
    /**
     * Returns true when a relative-motion packet was consumed by the coalescer.
     * Many tiny xdotool processes were previously started for every finger move;
     * on Android this let a motion backlog form faster than it could be drained.
     */
    private static boolean queueRelativeMotion(byte[] payload) {
        if (payload.length == 0 || payload.length > 128) return false;
        String raw = new String(payload, StandardCharsets.US_ASCII).trim();
        if (!raw.startsWith("REL ")) return false;
        String[] fields = raw.split("\\s+");
        if (fields.length != 3) return false;
        final int dx;
        final int dy;
        try {
            dx = Integer.parseInt(fields[1]);
            dy = Integer.parseInt(fields[2]);
        } catch (NumberFormatException invalid) {
            return false;
        }
        if (Math.abs((long) dx) > 4096L || Math.abs((long) dy) > 4096L) return true;
        synchronized (RELATIVE_MOTION_LOCK) {
            pendingRelativeX = clampMotion((long) pendingRelativeX + dx);
            pendingRelativeY = clampMotion((long) pendingRelativeY + dy);
            if (relativeMotionDrainQueued) return true;
            relativeMotionDrainQueued = true;
        }
        POINTER_EXECUTOR.execute(() -> {
            while (true) {
                final int moveX;
                final int moveY;
                synchronized (RELATIVE_MOTION_LOCK) {
                    moveX = pendingRelativeX;
                    moveY = pendingRelativeY;
                    pendingRelativeX = 0;
                    pendingRelativeY = 0;
                    if (moveX == 0 && moveY == 0) {
                        relativeMotionDrainQueued = false;
                        return;
                    }
                }
                handleInput(("REL " + moveX + " " + moveY).getBytes(StandardCharsets.US_ASCII));
            }
        });
        return true;
    }

    private static int clampMotion(long value) {
        return (int) Math.max(-8192L, Math.min(8192L, value));
    }

    /**
     * Send latency-sensitive pointer packets through one persistent XTest process.
     * Spawning xdotool for every move costs tens of milliseconds on low-end ARM devices.
     * Returns true only when a recognized pointer packet was accepted by the helper.
     */
    private static boolean dispatchNativePointer(String rawEvent) {
        String[] fields = rawEvent.trim().split("\\s+");
        if (fields.length == 0) return false;
        String op = fields[0];
        if (!("REL".equals(op) || "MOVE".equals(op) || "BUTTON".equals(op) || "CLICK".equals(op))) return false;
        final String command;
        try {
            if ("REL".equals(op) && fields.length == 3) {
                int dx = Integer.parseInt(fields[1]), dy = Integer.parseInt(fields[2]);
                if (Math.abs((long) dx) > 4096L || Math.abs((long) dy) > 4096L) return true;
                if (dx == 0 && dy == 0) return true;
                command = "REL " + dx + " " + dy;
            } else if ("MOVE".equals(op) && fields.length == 3) {
                float nx = Float.parseFloat(fields[1]), ny = Float.parseFloat(fields[2]);
                if (!Float.isFinite(nx) || !Float.isFinite(ny) || nx < 0f || nx > 1f || ny < 0f || ny > 1f) return true;
                int x = Math.min(FRAME_WIDTH - 1, Math.round(nx * (DISPLAY_WIDTH - 1)));
                int y = Math.min(FRAME_HEIGHT - 1, Math.round(ny * (DISPLAY_HEIGHT - 1)));
                command = "MOVE " + x + " " + y;
            } else if ("BUTTON".equals(op) && fields.length == 3 &&
                    ("DOWN".equals(fields[2]) || "UP".equals(fields[2]))) {
                int button = Integer.parseInt(fields[1]);
                if (button < 1 || button > 3) return true;
                command = "BUTTON " + button + " " + fields[2];
            } else if ("CLICK".equals(op) && fields.length == 2) {
                int button = Integer.parseInt(fields[1]);
                if (button < 1 || button > 3) return true;
                command = "CLICK " + button;
            } else {
                return false;
            }
        } catch (NumberFormatException invalid) {
            return false;
        }

        synchronized (BridgeClient.class) {
            try {
                Process process = activeNativeInput;
                if (process == null || !process.isAlive() || nativeInputWriter == null) {
                    String helper = findExecutable("termux-xwayland-input");
                    String display = System.getenv("DISPLAY");
                    if (helper == null || display == null || display.trim().isEmpty()) {
                        if (!nativeInputUnavailableLogged) {
                            System.err.println("NATIVE_INPUT_UNAVAILABLE reason=helper_or_DISPLAY_missing; falling back to xdotool");
                            nativeInputUnavailableLogged = true;
                        }
                        return false;
                    }
                    process = new ProcessBuilder(helper, "--display", display,
                            "--width", Integer.toString(DISPLAY_WIDTH),
                            "--height", Integer.toString(DISPLAY_HEIGHT))
                            .redirectError(ProcessBuilder.Redirect.INHERIT).start();
                    activeNativeInput = process;
                    nativeInputWriter = new BufferedWriter(new OutputStreamWriter(
                            process.getOutputStream(), StandardCharsets.US_ASCII), 256);
                    System.out.println("NATIVE_INPUT_STARTED backend=persistent-XTest display=" + display);
                    System.out.flush();
                }
                nativeInputWriter.write(command);
                nativeInputWriter.newLine();
                nativeInputWriter.flush();
                return true;
            } catch (IOException error) {
                System.err.println("NATIVE_INPUT_FAILED reason=" + error.getClass().getSimpleName());
                Process process = activeNativeInput;
                if (process != null) process.destroy();
                activeNativeInput = null;
                nativeInputWriter = null;
                return false;
            }
        }
    }

    private static void stopNativeInput() {
        synchronized (BridgeClient.class) {
            try { if (nativeInputWriter != null) nativeInputWriter.close(); } catch (IOException ignored) {}
            nativeInputWriter = null;
            Process process = activeNativeInput;
            activeNativeInput = null;
            if (process != null) process.destroy();
        }
    }

    private static void handleInput(byte[] payload) {
        if (payload.length == 0 || payload.length > 128) return;
        String rawEvent = new String(payload, StandardCharsets.UTF_8);
        if (dispatchNativePointer(rawEvent)) return;
        String xdotool = findExecutable("xdotool");
        if (xdotool == null) {
            System.err.println("INPUT_UNAVAILABLE reason=xdotool_not_installed");
            return;
        }

        // Preserve leading/trailing spaces in committed IME text.
        if (rawEvent.startsWith("TEXT ")) {
            String text = rawEvent.substring(5);
            if (text.isEmpty() || text.getBytes(StandardCharsets.UTF_8).length > 100
                    || text.codePoints().anyMatch(cp -> Character.isISOControl(cp) && cp != '\n' && cp != '\t')) {
                System.err.println("INPUT_REJECTED reason=invalid_text");
                return;
            }
            String typeText = text.replace("\n", "\r").replace("\t", "\t");
            runInputCommand(new ProcessBuilder(xdotool, "type", "--clearmodifiers", "--delay", "0", "--", typeText));
            return;
        }

        String event = rawEvent.trim();
        String[] fields = event.split("\\s+");

        // Keyboard packets use: KEY <xdotool-key-name> DOWN|UP.
        if (fields.length == 3 && "KEY".equals(fields[0])) {
            if (!fields[2].equals("DOWN") && !fields[2].equals("UP")) {
                System.err.println("INPUT_REJECTED reason=invalid_key_action");
                return;
            }
            if (!fields[1].matches("[A-Za-z0-9_+.-]{1,32}")) {
                System.err.println("INPUT_REJECTED reason=invalid_key_name");
                return;
            }
            runInputCommand(new ProcessBuilder(xdotool, fields[2].equals("DOWN") ? "keydown" : "keyup", fields[1]));
            return;
        }

        // Physical mouse buttons are forwarded separately from pointer motion.
        if (fields.length == 3 && "BUTTON".equals(fields[0])) {
            if (!fields[2].equals("DOWN") && !fields[2].equals("UP")) {
                System.err.println("INPUT_REJECTED reason=invalid_button_action");
                return;
            }
            final int button;
            try {
                button = Integer.parseInt(fields[1]);
            } catch (NumberFormatException invalid) {
                System.err.println("INPUT_REJECTED reason=invalid_button");
                return;
            }
            if (button < 1 || button > 3) {
                System.err.println("INPUT_REJECTED reason=invalid_button");
                return;
            }
            runInputCommand(new ProcessBuilder(xdotool,
                fields[2].equals("DOWN") ? "mousedown" : "mouseup",
                Integer.toString(button)));
            return;
        }

        // Trackpad motion is relative to the current X11 pointer, not the
        // absolute location of the finger on the Android view.
        if (fields.length == 3 && "REL".equals(fields[0])) {
            final int dx;
            final int dy;
            try {
                dx = Integer.parseInt(fields[1]);
                dy = Integer.parseInt(fields[2]);
            } catch (NumberFormatException invalid) {
                System.err.println("INPUT_REJECTED reason=invalid_relative_coordinates");
                return;
            }
            if (Math.abs((long) dx) > 4096L || Math.abs((long) dy) > 4096L) {
                System.err.println("INPUT_REJECTED reason=relative_coordinates_out_of_range");
                return;
            }
            if (dx != 0 || dy != 0) {
                runInputCommand(new ProcessBuilder(xdotool, "mousemove_relative", "--",
                    Integer.toString(dx), Integer.toString(dy)));
            }
            return;
        }

        // A trackpad tap generates a click without holding the button during motion.
        if (fields.length == 2 && "CLICK".equals(fields[0])) {
            final int button;
            try {
                button = Integer.parseInt(fields[1]);
            } catch (NumberFormatException invalid) {
                System.err.println("INPUT_REJECTED reason=invalid_click_button");
                return;
            }
            if (button < 1 || button > 3) {
                System.err.println("INPUT_REJECTED reason=invalid_click_button");
                return;
            }
            runInputCommand(new ProcessBuilder(xdotool, "click", "--delay", "0",
                Integer.toString(button)));
            return;
        }

        // Convert Android wheel axes to X11 wheel-button clicks.
        if (fields.length == 3 && "SCROLL".equals(fields[0])) {
            final float vertical;
            final float horizontal;
            try {
                vertical = Float.parseFloat(fields[1]);
                horizontal = Float.parseFloat(fields[2]);
            } catch (NumberFormatException invalid) {
                System.err.println("INPUT_REJECTED reason=invalid_scroll");
                return;
            }
            if (!Float.isFinite(vertical) || !Float.isFinite(horizontal)
                    || Math.abs(vertical) > 100f || Math.abs(horizontal) > 100f) {
                System.err.println("INPUT_REJECTED reason=scroll_out_of_range");
                return;
            }
            int verticalTicks = Math.min(20, Math.round(Math.abs(vertical)));
            int horizontalTicks = Math.min(20, Math.round(Math.abs(horizontal)));
            if (verticalTicks > 0) {
                runInputCommand(new ProcessBuilder(xdotool, "click", "--repeat",
                    Integer.toString(verticalTicks), "--delay", "0",
                    vertical > 0 ? "4" : "5"));
            }
            if (horizontalTicks > 0) {
                runInputCommand(new ProcessBuilder(xdotool, "click", "--repeat",
                    Integer.toString(horizontalTicks), "--delay", "0",
                    horizontal > 0 ? "7" : "6"));
            }
            return;
        }

        if (fields.length != 3) {
            System.err.println("INPUT_REJECTED reason=invalid_fields");
            return;
        }
        String action = fields[0];
        final float nx;
        final float ny;
        try {
            nx = Float.parseFloat(fields[1]);
            ny = Float.parseFloat(fields[2]);
        } catch (NumberFormatException invalid) {
            System.err.println("INPUT_REJECTED reason=invalid_coordinates");
            return;
        }
        if (!Float.isFinite(nx) || !Float.isFinite(ny) || nx < 0f || nx > 1f || ny < 0f || ny > 1f) {
            System.err.println("INPUT_REJECTED reason=coordinates_out_of_range");
            return;
        }
        int x = Math.min(DISPLAY_WIDTH - 1, Math.round(nx * (DISPLAY_WIDTH - 1)));
        int y = Math.min(DISPLAY_HEIGHT - 1, Math.round(ny * (DISPLAY_HEIGHT - 1)));
        ProcessBuilder command = new ProcessBuilder(xdotool, "mousemove", Integer.toString(x), Integer.toString(y));
        if ("DOWN".equals(action)) {
            command.command().add("mousedown");
            command.command().add("1");
        } else if ("UP".equals(action) || "CANCEL".equals(action)) {
            command.command().add("mouseup");
            command.command().add("1");
        } else if (!"MOVE".equals(action)) {
            System.err.println("INPUT_REJECTED reason=unknown_action");
            return;
        }
        runInputCommand(command);
    }

    private static void runInputCommand(ProcessBuilder command) {
        try {
            Process process = command.redirectErrorStream(true).start();
            if (!process.waitFor(250, java.util.concurrent.TimeUnit.MILLISECONDS)) process.destroyForcibly();
        } catch (Exception error) {
            System.err.println("INPUT_DISPATCH_FAILED: " + error.getClass().getSimpleName());
        }
    }

    /**
     * Start the native Wayland capture helper.
     *
     * Do not fall back to FFmpeg/X11 root capture: rootless XWayland windows are
     * composed by Weston and an X11 root grab can return a black frame. The helper
     * must use an explicitly authorized Weston capture path and emit fixed-size
     * RGBA frames to stdout. Until the helper is installed, fail closed and report
     * a clear diagnostic rather than sending misleading black frames.
     */
    private static Process startFrameCapture() {
        // Native capture connects to a Wayland socket, not the X11 DISPLAY.
        // DISPLAY (often :0) is only for xdotool/XWayland input routing.
        String display = System.getenv("WAYLAND_DISPLAY");
        if (display == null || display.trim().isEmpty()) {
            System.out.println("FRAME_CAPTURE_UNAVAILABLE reason=WAYLAND_DISPLAY_not_set");
            System.out.flush();
            return null;
        }
        String capture = findExecutable("termux-xwayland-capture");
        if (capture == null) {
            System.out.println("FRAME_CAPTURE_UNAVAILABLE reason=native_wayland_capture_helper_not_installed");
            System.out.flush();
            return null;
        }
        try {
            Process process = new ProcessBuilder(
                capture, "--display", display,
                "--width", Integer.toString(FRAME_WIDTH),
                "--height", Integer.toString(FRAME_HEIGHT),
                "--format", "rgba"
            ).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            System.out.println("FRAME_CAPTURE_STARTED epoch_seconds="
                + (System.currentTimeMillis() / 1000L)
                + " backend=native-wayland display=" + display
                + " size=" + FRAME_WIDTH + "x" + FRAME_HEIGHT
                + " display_size=" + DISPLAY_WIDTH + "x" + DISPLAY_HEIGHT + " max_stream_pixels=230400");
            System.out.flush();
            return process;
        } catch (IOException error) {
            System.err.println("FRAME_CAPTURE_START_FAILED backend=native-wayland error="
                + error.getClass().getName() + " message=" + String.valueOf(error.getMessage()));
            System.err.flush();
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

    private static Thread startFrameForwarder(Process capture, DataOutputStream output) {
        Thread thread = new Thread(() -> {
            byte[] pixels = new byte[FRAME_BYTES];
            byte[] payload = new byte[8 + FRAME_BYTES];
            payload[0] = (byte) (FRAME_WIDTH >>> 8);
            payload[1] = (byte) FRAME_WIDTH;
            payload[2] = (byte) (FRAME_HEIGHT >>> 8);
            payload[3] = (byte) FRAME_HEIGHT;
            payload[4] = 1;
            long lastLog = 0L;
            long frameCount = 0L;
            try (DataInputStream raw = new DataInputStream(capture.getInputStream())) {
                while (!Thread.currentThread().isInterrupted()) {
                    long readStarted = System.nanoTime();
                    raw.readFully(pixels);
                    long readFinished = System.nanoTime();
                    frameCount++;
                    System.arraycopy(pixels, 0, payload, 8, pixels.length);
                    if (frameCount == 1L) {
                        System.out.println("FRAME_READ count=1 bytes=" + pixels.length
                            + " elapsed_ms=" + ((readFinished - readStarted) / 1_000_000L));
                        System.out.flush();
                        System.out.println("FRAME_FORWARD_WRITE_BEGIN count=1 payload_bytes=" + payload.length);
                        System.out.flush();
                    }
                    long writeStarted = System.nanoTime();
                    writeFrame(output, FRAME, payload);
                    long writeFinished = System.nanoTime();
                    long now = System.currentTimeMillis();
                    if (frameCount == 1L || lastLog == 0L || now - lastLog >= 5000L) {
                        lastLog = now;
                        System.out.println("FRAME_SENT count=" + frameCount
                            + " epoch_seconds=" + (now / 1000L)
                            + " read_ms=" + ((readFinished - readStarted) / 1_000_000L)
                            + " write_ms=" + ((writeFinished - writeStarted) / 1_000_000L)
                            + " transport=binder-fd");
                        System.out.flush();
                    }
                }
            } catch (IOException error) {
                System.err.println("FRAME_CAPTURE_STOPPED count=" + frameCount
                    + " epoch_seconds=" + (System.currentTimeMillis() / 1000L)
                    + " reason=" + error.getClass().getName()
                    + " message=" + String.valueOf(error.getMessage()));
                error.printStackTrace(System.err);
                System.out.flush();
            }
        }, "termux-xwayland-frame-forwarder");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void sleepBeforeCaptureRetry(long delayMs) {
        try {
            System.out.println("FRAME_CAPTURE_RETRY scheduled_delay_ms=" + delayMs);
            System.out.flush();
            Thread.sleep(delayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
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
