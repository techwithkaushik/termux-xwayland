package com.termux.x11;

import android.content.Context;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
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
    private static final String TAG = "XW11Surface";
    private static final long NS_PER_MS = 1_000_000L;
    private static final int INPUT_TOUCH = 3;
    private static final int INPUT_KEY = 4;
    private static final int INPUT_FOCUS = 5;
    private static final int MOVE = 1;
    private static final int DOWN = 2;
    private static final int UP = 3;
    private static final int CANCEL = 4;

    private static long nextGeneration = 1;
    private long generation;

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

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        final long current = generation;
        final Surface surface = holder.getSurface();
        if (current == 0 || surface == null || !surface.isValid()) {
            Log.w(TAG, "Ignoring invalid Surface lifecycle update");
            return;
        }
        int result = nativeAttachSurface(surface, current);
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
            int result = nativeAttachInputTransport(connectedSocket.getFd(), sessionId, current);
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
        boolean submitted = false;

        if (masked == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                submitted |= submitTouch(event, i, MOVE);
            }
        } else if (masked == MotionEvent.ACTION_DOWN ||
                   masked == MotionEvent.ACTION_POINTER_DOWN) {
            submitted = submitTouch(event, actionIndex, DOWN);
            requestFocus();
        } else if (masked == MotionEvent.ACTION_UP ||
                   masked == MotionEvent.ACTION_POINTER_UP) {
            submitted = submitTouch(event, actionIndex, UP);
        } else if (masked == MotionEvent.ACTION_CANCEL) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                submitted |= submitTouch(event, i, CANCEL);
            }
        }
        return submitted || super.onTouchEvent(event);
    }

    private boolean submitTouch(MotionEvent event, int index, int action) {
        final long current = generation;
        if (current == 0 || index < 0 || index >= event.getPointerCount()) return false;
        return nativeSubmitInputEvent(current, event.getEventTime() * NS_PER_MS,
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
        final int scanCode = event.getScanCode();
        if (current == 0 || scanCode <= 0) return false;
        return nativeSubmitInputEvent(current, event.getEventTime() * NS_PER_MS,
                INPUT_KEY, action, scanCode, 0, 0, event.getMetaState(),
                event.getDeviceId());
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction,
                                  android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        final long current = generation;
        if (current != 0) {
            nativeSubmitInputEvent(current, SystemClock.uptimeMillis() * NS_PER_MS,
                    INPUT_FOCUS, gainFocus ? DOWN : UP, 0, 0, 0, 0, 0);
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        final long current = generation;
        generation = 0;
        if (current != 0) {
            nativeDetachInputTransport(current);
            nativeDetachSurface(current);
        }
    }

    private static native int nativeAttachSurface(Surface surface, long generation);
    private static native void nativeDetachSurface(long generation);
    private static native int nativeAttachInputTransport(int fd, long sessionId, long generation);
    private static native boolean nativeSubmitInputEvent(long generation, long timeNs,
            int type, int action, int code, int x, int y, int modifiers, int deviceId);
    private static native void nativeDetachInputTransport(long generation);
}
