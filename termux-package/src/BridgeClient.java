package org.techwithkaushik.termuxxwayland.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Handler;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
            context = createTermuxContext();
        } catch (Exception error) {
            System.err.println("BINDER_CLIENT_ERROR: cannot create Termux context");
            Throwable cause = error;
            int depth = 0;
            while (cause != null && depth++ < 12) {
                System.err.println("  cause[" + (depth - 1) + "]: "
                    + cause.getClass().getName() + ": " + cause.getMessage());
                cause = cause.getCause();
            }
            error.printStackTrace(System.err);
            System.err.flush();
            System.exit(2);
            return;
        }

        final Handler mainHandler = new Handler(Looper.getMainLooper());
        final AtomicBoolean finished = new AtomicBoolean(false);
        final String hostPackage = findHostPackage(context);
        if (hostPackage == null) {
            System.err.println("BINDER_CLIENT_ERROR: Termux-XWayland host APK is not installed.");
            System.exit(3);
            return;
        }

        final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                Thread worker = new Thread(() -> {
                    try {
                        runSession(binder);
                    } catch (Exception error) {
                        System.err.println("BINDER_SESSION_ERROR: " + error.getMessage());
                    } finally {
                        if (finished.compareAndSet(false, true)) {
                            mainHandler.post(() -> {
                                try { context.unbindService(this); } catch (Exception ignored) {}
                                Looper.getMainLooper().quit();
                            });
                        }
                    }
                }, "termux-xwayland-binder");
                worker.setDaemon(false);
                worker.start();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                System.err.println("BINDER_SESSION_DISCONNECTED");
                if (finished.compareAndSet(false, true)) Looper.getMainLooper().quit();
            }
        };

        Intent intent = new Intent().setComponent(new ComponentName(hostPackage, SERVICE_CLASS));
        boolean bound;
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception error) {
            bound = false;
            System.err.println("BINDER_CLIENT_ERROR: bindService failed: " + error);
        }
        if (!bound) {
            System.err.println("BINDER_CLIENT_ERROR: Android rejected the Binder service bind.");
            System.exit(4);
            return;
        }
        System.out.println("BINDER_CONNECTING host=" + hostPackage);
        System.out.flush();
        Looper.loop();
    }

    private static Context createTermuxContext() throws Exception {
        /*
         * Do not call ActivityThread.systemMain()/getSystemContext() here.
         * MIUI Android 10 initializes ThemeCompatibilityLoader on that path and
         * can throw when /data/system/theme_config/theme_compatibility.xml is
         * absent. Build an app context directly from Termux's ApplicationInfo.
         */
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        Object activityThread = activityThreadClass.getDeclaredConstructor().newInstance();

        Class<?> appGlobalsClass = Class.forName("android.app.AppGlobals");
        Method getPackageManager = appGlobalsClass.getDeclaredMethod("getPackageManager");
        getPackageManager.setAccessible(true);
        Object packageManagerBinder = getPackageManager.invoke(null);
        if (packageManagerBinder == null) {
            throw new IllegalStateException("Android package manager is unavailable");
        }

        Class<?> iPackageManagerClass = Class.forName("android.content.pm.IPackageManager");
        Method getApplicationInfo = null;
        for (Method method : iPackageManagerClass.getMethods()) {
            if ("getApplicationInfo".equals(method.getName())
                    && method.getParameterTypes().length == 3
                    && method.getParameterTypes()[0] == String.class) {
                getApplicationInfo = method;
                break;
            }
        }
        if (getApplicationInfo == null) {
            throw new NoSuchMethodException("IPackageManager.getApplicationInfo(String, int, int)");
        }
        Object appInfo = getApplicationInfo.invoke(
            packageManagerBinder, TERMUX_PACKAGE, 0, android.os.Process.myUid() / 100000
        );
        if (appInfo == null) {
            throw new PackageManager.NameNotFoundException(TERMUX_PACKAGE);
        }

        Class<?> compatibilityInfoClass = Class.forName("android.content.res.CompatibilityInfo");
        Object compatibilityInfo = compatibilityInfoClass.getField("DEFAULT_COMPATIBILITY_INFO").get(null);
        Method getPackageInfoNoCheck = activityThreadClass.getDeclaredMethod(
            "getPackageInfoNoCheck",
            Class.forName("android.content.pm.ApplicationInfo"),
            compatibilityInfoClass
        );
        getPackageInfoNoCheck.setAccessible(true);
        Object loadedApk = getPackageInfoNoCheck.invoke(activityThread, appInfo, compatibilityInfo);

        Class<?> contextImplClass = Class.forName("android.app.ContextImpl");
        Method createAppContext = contextImplClass.getDeclaredMethod(
            "createAppContext", activityThreadClass, Class.forName("android.app.LoadedApk")
        );
        createAppContext.setAccessible(true);
        Object value = createAppContext.invoke(null, activityThread, loadedApk);
        if (!(value instanceof Context)) {
            throw new IllegalStateException("Android did not create a Termux application context");
        }
        Context context = (Context) value;
        if (!TERMUX_PACKAGE.equals(context.getPackageName())) {
            throw new SecurityException("Android returned an unexpected package context: "
                + context.getPackageName());
        }
        return context;
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

    private static void runSession(IBinder binder) throws Exception {
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        ParcelFileDescriptor descriptor = null;
        try {
            request.writeInterfaceToken(DESCRIPTOR);
            request.writeInt(VERSION);
            if (!binder.transact(OPEN_SESSION, request, response, 0)) {
                throw new IOException("Host Binder service rejected OPEN_SESSION");
            }
            response.readException();
            descriptor = response.readParcelable(BridgeClient.class.getClassLoader());
        } finally {
            request.recycle();
            response.recycle();
        }
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
                        frameCapture = startFrameCapture();
                        activeFrameCapture = frameCapture;
                        if (frameCapture != null) startFrameForwarder(frameCapture, output);
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
            String termuxTmp = System.getenv("TMPDIR");
            File logDirectory = new File(termuxTmp == null
                ? "/data/data/com.termux/files/usr/tmp" : termuxTmp);
            File ffmpegLog = new File(logDirectory, "termux-xwayland-ffmpeg.log");
            Process process = new ProcessBuilder(
                ffmpeg, "-hide_banner", "-loglevel", "error",
                "-f", "x11grab", "-framerate", "2", "-video_size", "640x360",
                "-i", display, "-vf", "scale=640:360", "-pix_fmt", "rgba",
                "-f", "rawvideo", "pipe:1"
            ).redirectError(ProcessBuilder.Redirect.to(ffmpegLog)).start();
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
                    int sampled = 0;
                    int nonBlack = 0;
                    // Sample one RGBA pixel out of every 64 to detect the common
                    // "valid transport, black XWayland root" failure without a
                    // full-frame scan on every frame on low-memory ARM32 devices.
                    for (int offset = 0; offset < pixels.length; offset += 4 * 64) {
                        sampled++;
                        if ((pixels[offset] & 0xff) != 0
                                || (pixels[offset + 1] & 0xff) != 0
                                || (pixels[offset + 2] & 0xff) != 0) {
                            nonBlack++;
                        }
                    }
                    System.arraycopy(pixels, 0, payload, 8, pixels.length);
                    writeFrame(output, FRAME, payload);
                    long now = System.currentTimeMillis();
                    if (lastLog == 0L || now - lastLog >= 5000L) {
                        lastLog = now;
                        int percent = sampled == 0 ? 0 : (nonBlack * 100 / sampled);
                        System.out.println("FRAME_SENT epoch_seconds=" + (now / 1000L)
                            + " transport=binder-fd sampled_nonblack_percent=" + percent);
                        if (percent == 0) {
                            System.out.println("FRAME_CONTENT_SUSPECT_BLACK reason=no_sampled_nonblack_pixels");
                        }
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
