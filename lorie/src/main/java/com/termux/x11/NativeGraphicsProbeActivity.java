package com.termux.x11;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;

/**
 * Isolated, opt-in EGL diagnostic. Runs in its own Activity so invoking the
 * probe never relies on MainActivity.onCreate() being called again.
 */
public final class NativeGraphicsProbeActivity extends Activity {
    private static final String TAG = "XW11-EGL-PROBE";
    private SurfaceView surfaceView;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);
        status = new TextView(this);
        status.setText("Running EGL capability and Android Surface checks…");
        layout.addView(status, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        surfaceView = new SurfaceView(this);
        layout.addView(surfaceView, new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        setContentView(layout);

        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                runProbe(holder);
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format,
                                       int width, int height) {
                // Surface creation triggers the probe; avoid repeating it on resize.
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                // The native probe releases its EGL objects before returning.
            }
        });
    }

    private void runProbe(@NonNull SurfaceHolder holder) {
        final String report;
        try {
            String capabilities = NativeGraphicsProbe.probeEglCapabilities();
            String window = NativeGraphicsProbe.probeWindowSurface(holder.getSurface());
            report = capabilities + "\n" + window;
            Log.i(TAG, report);
            status.setText(report + "\n\nProbe complete. You may return to the desktop.");
        } catch (Throwable error) {
            Log.e(TAG, "Native EGL probe failed", error);
            status.setText("Probe failed: " + error + "\nSee logcat tag " + TAG);
        }
    }
}
