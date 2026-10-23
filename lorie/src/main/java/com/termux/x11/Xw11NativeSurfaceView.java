package com.termux.x11;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

/**
 * Isolated Surface lifecycle bridge for the experimental native XW11 presenter.
 *
 * This view is deliberately not inserted into the existing LorieActivity layout:
 * until a Wayland frame producer is connected, it would only display a blank
 * surface. It must never share LorieView's Surface with the legacy EGL renderer.
 */
final class Xw11NativeSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    private static final String TAG = "XW11Surface";
    private static long nextGeneration = 1;
    private long generation;

    Xw11NativeSurfaceView(Context context) {
        super(context);
        getHolder().addCallback(this);
    }

    Xw11NativeSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        getHolder().addCallback(this);
    }

    private static synchronized long allocateGeneration() {
        long value = nextGeneration++;
        if (value == 0) {
            value = nextGeneration++;
        }
        if (nextGeneration == 0) {
            nextGeneration = 1;
        }
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
        if (result != 0) {
            Log.e(TAG, "Native Surface attach failed: " + result);
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        final long current = generation;
        generation = 0;
        if (current != 0) {
            nativeDetachSurface(current);
        }
    }

    private static native int nativeAttachSurface(Surface surface, long generation);
    private static native void nativeDetachSurface(long generation);
}
