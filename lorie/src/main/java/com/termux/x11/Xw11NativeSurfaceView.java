package com.termux.x11;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import android.os.ParcelFileDescriptor;

/**
 * Experimental XW11 Surface/input bridge.
 *
 * The view is intentionally not placed in the legacy Lorie layout: a compositor
 * frame producer and a trusted input-socket connector must be configured first.
 * It must never share LorieView's Surface with the legacy EGL renderer.
 */
final class Xw11NativeSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    static {
        System.loadLibrary("Xlorie");
    }

    private static final String TAG = "XW11Surface";
    private static final long NS_PER_MS = 1_000_000L;
    private static final int INPUT_POINTER_MOTION = 1;
    private static final int INPUT_POINTER_BUTTON = 2;
    private static final int INPUT_TOUCH = 3;
    private static final int INPUT_KEY = 4;
    private static final int INPUT_FOCUS = 5;
    private static final int MOVE = 1;
    private static final int DOWN = 2;
    private static final int UP = 3;
    private static final int CANCEL = 4;

    // Registration is optional during development. A mismatched/stale native library must
    // degrade this experimental view gracefully instead of crashing Activity lifecycle.
    private static volatile boolean nativeBridgeAvailable = true;
    private static boolean nativeBridgeFailureLogged;
    private static long nextGeneration = 1;
    private long generation;

    private static synchronized void markNativeBridgeUnavailable(UnsatisfiedLinkError error) {
        nativeBridgeAvailable = false;
        if (!nativeBridgeFailureLogged) {
            nativeBridgeFailureLogged = true;
            Log.e(TAG, "XW11 native bridge unavailable; experimental input/presentation disabled", error);
        }
    }

    private static int attachSurfaceSafely(Surface surface, long current) {
        if (!nativeBridgeAvailable) return -1;
        try {
            return nativeAttachSurface(surface, current);
        } catch (UnsatisfiedLinkError error) {
            markNativeBridgeUnavailable(error);
            return -1;
        }
    }

    private static void detachSurfaceSafely(long current) {
        if (!nativeBridgeAvailable) return;
        try {
            nativeDetachSurface(current);
        } catch (UnsatisfiedLinkError error) {
            markNativeBridgeUnavailable(error);
        }
    }

    private static void detachInputSafely(long current) {
        if (!nativeBridgeAvailable) return;
        try {
            nativeDetachInputTransport(current);
        } catch (UnsatisfiedLinkError error) {
            markNativeBridgeUnavailable(error);
        }
    }

    private static int attachInputSafely(int fd, long sessionId, long current) {
        if (!nativeBridgeAvailable) return -1;
        try {
            return nativeAttachInputTransport(fd, sessionId, current);
        } catch (UnsatisfiedLinkError error) {
            markNativeBridgeUnavailable(error);
            return -1;
        }
    }

    private static boolean submitInputSafely(long current, long timeNs, int type, int action,
            int code, int x, int y, int modifiers, int deviceId) {
        if (!nativeBridgeAvailable) return false;
        try {
            return nativeSubmitInputEvent(current, timeNs, type, action, code, x, y,
                    modifiers, deviceId);
        } catch (UnsatisfiedLinkError error) {
            markNativeBridgeUnavailable(error);
            return false;
        }
    }

    private static boolean presentFrameSafely(java.nio.ByteBuffer pixels, int offset, int length,
            int width, int height, int strideBytes, long current) {
        if (!nativeBridgeAvailable) return false;
        try {
            return nativePresentRgbaFrame(pixels, offset, length, width, height,
                    strideBytes, current) == 0;
        } catch (UnsatisfiedLinkError error) {
            markNativeBridgeUnavailable(error);
            return false;
        }
    }

    Xw11NativeSurfaceView(Context context) {
        super(context);
        initialize();
    }

    Xw11NativeSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initialize();
    }

    private void initialize() {
        getHolder().addCallback(this);
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    private static synchronized long allocateGeneration() {
        long value = nextGeneration++;
        if (value == 0) value = nextGeneration++;
        if (nextGeneration == 0) nextGeneration = 1;
        return value;
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        generation = allocateGeneration();
    }

    /** Package-private generation access for the isolated presenter diagnostic only. */
    long generationForDiagnostics() {
        return generation;
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        final long current = generation;
        final Surface surface = holder.getSurface();
        if (current == 0 || surface == null || !surface.isValid()) {
            Log.w(TAG, "Ignoring invalid Surface lifecycle update");
            return;
        }
        int result = attachSurfaceSafely(surface, current);
        if (result != 0) Log.e(TAG, "Native Surface attach failed: " + result);
    }

    /**
     * Attach a connected, authenticated transport socket supplied by the session
     * launcher. Native duplicates the fd; the caller may close its descriptor
     * after this method returns. A session id of zero is never accepted.
     */
    boolean attachInputTransport(@NonNull ParcelFileDescriptor connectedSocket, long sessionId) {
        final long current = generation;
        if (current == 0 || sessionId <= 0) return false;
        try {
            int result = attachInputSafely(connectedSocket.getFd(), sessionId, current);
            if (result != 0) Log.e(TAG, "Native input transport attach failed: " + result);
            return result == 0;
        } catch (Exception e) {
            Log.e(TAG, "Unable to obtain input transport descriptor", e);
            return false;
        }
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        final long current = generation;
        if (current == 0) return false;
        final int masked = event.getActionMasked();
        final int actionIndex = event.getActionIndex();
        if ((event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            return submitMouse(event, masked) || super.onTouchEvent(event);
        }

        boolean submitted = false;
        if (masked == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) submitted |= submitTouch(event, i, MOVE);
        } else if (masked == MotionEvent.ACTION_DOWN ||
                   masked == MotionEvent.ACTION_POINTER_DOWN) {
            submitted = submitTouch(event, actionIndex, DOWN);
            requestFocus();
        } else if (masked == MotionEvent.ACTION_UP ||
                   masked == MotionEvent.ACTION_POINTER_UP) {
            submitted = submitTouch(event, actionIndex, UP);
        } else if (masked == MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < event.getPointerCount(); i++) submitted |= submitTouch(event, i, CANCEL);
        }
        return submitted || super.onTouchEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(@NonNull MotionEvent event) {
        // Mouse hover and button transitions are delivered through generic motion,
        // not reliably through onTouchEvent. Keep all input on the same native path.
        if ((event.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
            final int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_HOVER_MOVE ||
                    action == MotionEvent.ACTION_MOVE ||
                    action == MotionEvent.ACTION_BUTTON_PRESS ||
                    action == MotionEvent.ACTION_BUTTON_RELEASE) {
                return submitMouse(event, action) || super.onGenericMotionEvent(event);
            }
        }
        return super.onGenericMotionEvent(event);
    }

    private boolean submitMouse(MotionEvent event, int masked) {
        final long current = generation;
        if (current == 0) return false;
        final long timeNs = event.getEventTime() * NS_PER_MS;
        final int x = Math.round(event.getX());
        final int y = Math.round(event.getY());
        if (masked == MotionEvent.ACTION_MOVE || masked == MotionEvent.ACTION_HOVER_MOVE) {
            return submitInputSafely(current, timeNs, INPUT_POINTER_MOTION, MOVE,
                    0, x, y, event.getMetaState(), event.getDeviceId());
        }
        final boolean pressed = masked == MotionEvent.ACTION_DOWN ||
                masked == MotionEvent.ACTION_BUTTON_PRESS;
        final boolean released = masked == MotionEvent.ACTION_UP ||
                masked == MotionEvent.ACTION_BUTTON_RELEASE;
        if (!pressed && !released) return false;
        final int button = linuxButtonCode(event.getActionButton());
        if (button == 0) return false;
        return submitInputSafely(current, timeNs, INPUT_POINTER_BUTTON,
                pressed ? DOWN : UP, button, x, y, event.getMetaState(), event.getDeviceId());
    }

    private static int linuxButtonCode(int androidButton) {
        switch (androidButton) {
            case MotionEvent.BUTTON_PRIMARY: return 0x110;   // BTN_LEFT
            case MotionEvent.BUTTON_SECONDARY: return 0x111; // BTN_RIGHT
            case MotionEvent.BUTTON_TERTIARY: return 0x112;  // BTN_MIDDLE
            case MotionEvent.BUTTON_BACK: return 0x116;      // BTN_BACK
            case MotionEvent.BUTTON_FORWARD: return 0x115;   // BTN_FORWARD
            default: return 0;
        }
    }

    private boolean submitTouch(MotionEvent event, int index, int action) {
        final long current = generation;
        if (current == 0 || index < 0 || index >= event.getPointerCount()) return false;
        return submitInputSafely(current, event.getEventTime() * NS_PER_MS,
                INPUT_TOUCH, action, event.getPointerId(index),
                Math.round(event.getX(index)), Math.round(event.getY(index)),
                event.getMetaState(), event.getDeviceId());
    }

    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        return submitKey(event, DOWN) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, @NonNull KeyEvent event) {
        return submitKey(event, UP) || super.onKeyUp(keyCode, event);
    }

    private boolean submitKey(KeyEvent event, int action) {
        final long current = generation;
        // Android's scan code is the kernel input code for hardware keyboards.
        // Do not mislabel Android KeyEvent keyCode values as Linux evdev codes.
        final int scanCode = event.getScanCode() > 0
                ? event.getScanCode() : linuxKeyCode(event.getKeyCode());
        if (current == 0 || scanCode <= 0) return false;
        return nativeSubmitInputEvent(current, event.getEventTime() * NS_PER_MS,
                INPUT_KEY, action, scanCode, 0, 0, event.getMetaState(),
                event.getDeviceId());
    }

    // Linux evdev key codes for common Android soft-keyboard and navigation keys.
    // Hardware keyboard scan codes remain preferred whenever Android supplies them.
    private static int linuxKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_A: return 30;
            case KeyEvent.KEYCODE_B: return 48;
            case KeyEvent.KEYCODE_C: return 46;
            case KeyEvent.KEYCODE_D: return 32;
            case KeyEvent.KEYCODE_E: return 18;
            case KeyEvent.KEYCODE_F: return 33;
            case KeyEvent.KEYCODE_G: return 34;
            case KeyEvent.KEYCODE_H: return 35;
            case KeyEvent.KEYCODE_I: return 23;
            case KeyEvent.KEYCODE_J: return 36;
            case KeyEvent.KEYCODE_K: return 37;
            case KeyEvent.KEYCODE_L: return 38;
            case KeyEvent.KEYCODE_M: return 50;
            case KeyEvent.KEYCODE_N: return 49;
            case KeyEvent.KEYCODE_O: return 24;
            case KeyEvent.KEYCODE_P: return 25;
            case KeyEvent.KEYCODE_Q: return 16;
            case KeyEvent.KEYCODE_R: return 19;
            case KeyEvent.KEYCODE_S: return 31;
            case KeyEvent.KEYCODE_T: return 20;
            case KeyEvent.KEYCODE_U: return 22;
            case KeyEvent.KEYCODE_V: return 47;
            case KeyEvent.KEYCODE_W: return 17;
            case KeyEvent.KEYCODE_X: return 45;
            case KeyEvent.KEYCODE_Y: return 21;
            case KeyEvent.KEYCODE_Z: return 44;
            case KeyEvent.KEYCODE_0: return 11;
            case KeyEvent.KEYCODE_1: return 2;
            case KeyEvent.KEYCODE_2: return 3;
            case KeyEvent.KEYCODE_3: return 4;
            case KeyEvent.KEYCODE_4: return 5;
            case KeyEvent.KEYCODE_5: return 6;
            case KeyEvent.KEYCODE_6: return 7;
            case KeyEvent.KEYCODE_7: return 8;
            case KeyEvent.KEYCODE_8: return 9;
            case KeyEvent.KEYCODE_9: return 10;
            case KeyEvent.KEYCODE_ENTER: return 28;
            case KeyEvent.KEYCODE_DEL: return 14;
            case KeyEvent.KEYCODE_TAB: return 15;
            case KeyEvent.KEYCODE_SPACE: return 57;
            case KeyEvent.KEYCODE_ESCAPE: return 1;
            case KeyEvent.KEYCODE_DPAD_UP: return 103;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 108;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 105;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 106;
            case KeyEvent.KEYCODE_HOME: return 102;
            case KeyEvent.KEYCODE_MOVE_END: return 107;
            case KeyEvent.KEYCODE_PAGE_UP: return 104;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 109;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 111;
            case KeyEvent.KEYCODE_SHIFT_LEFT: return 42;
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return 54;
            case KeyEvent.KEYCODE_CTRL_LEFT: return 29;
            case KeyEvent.KEYCODE_CTRL_RIGHT: return 97;
            case KeyEvent.KEYCODE_ALT_LEFT: return 56;
            case KeyEvent.KEYCODE_ALT_RIGHT: return 100;
            case KeyEvent.KEYCODE_CAPS_LOCK: return 58;
            default: return 0;
        }
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction,
                                  android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        final long current = generation;
        if (current != 0) {
            submitInputSafely(current, SystemClock.uptimeMillis() * NS_PER_MS,
                    INPUT_FOCUS, gainFocus ? DOWN : UP, 0, 0, 0, 0, 0);
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        final long current = generation;
        generation = 0;
        if (current != 0) {
            // Teardown must always complete even if RegisterNatives failed for this APK.
            detachInputSafely(current);
            detachSurfaceSafely(current);
        }
    }

    /**
     * Submit a compositor-produced RGBA8888 frame to this view's own Surface.
     * The direct buffer is borrowed only for the duration of the native call;
     * the native presenter copies its rows before returning.
     */
    boolean presentRgbaFrame(@NonNull java.nio.ByteBuffer pixels, int width, int height,
                             int strideBytes, long frameGeneration) {
        if (!pixels.isDirect() || frameGeneration == 0 || frameGeneration != generation)
            return false;
        return presentFrameSafely(pixels, pixels.position(), pixels.remaining(),
                width, height, strideBytes, frameGeneration);
    }

    private static native int nativePresentRgbaFrame(java.nio.ByteBuffer pixels,
            int offset, int length, int width, int height, int strideBytes, long generation);
    private static native int nativeAttachSurface(Surface surface, long generation);
    private static native void nativeDetachSurface(long generation);
    private static native int nativeAttachInputTransport(int fd, long sessionId, long generation);
    private static native boolean nativeSubmitInputEvent(long generation, long timeNs,
            int type, int action, int code, int x, int y, int modifiers, int deviceId);
    private static native void nativeDetachInputTransport(long generation);
}
