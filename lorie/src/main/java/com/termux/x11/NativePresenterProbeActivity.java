package com.termux.x11;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;

/**
 * Opt-in diagnostic for the experimental CPU-copy ANativeWindow presenter.
 * This does not launch Weston or alter the legacy Lorie display Surface.
 */
public final class NativePresenterProbeActivity extends Activity
        implements SurfaceHolder.Callback {
    private static final String TAG = "XW11-PRESENTER-PROBE";
    private static final int MAX_TEST_PIXELS = 4 * 1024 * 1024;

    private Xw11NativeSurfaceView surfaceView;
    private TextView status;
    private boolean finished;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);

        status = new TextView(this);
        status.setText("Waiting for native Surface presenter…");
        layout.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        surfaceView = new Xw11NativeSurfaceView(this);
        surfaceView.setBackgroundColor(Color.rgb(12, 24, 40));
        surfaceView.getHolder().addCallback(this);
        layout.addView(surfaceView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(layout);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        finished = false;
        status.setText("Surface created; waiting for dimensions…");
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (finished || width <= 0 || height <= 0) return;
        final long generation = surfaceView.generationForDiagnostics();
        final long pixelCount = (long) width * height;
        if (generation <= 0 || pixelCount > MAX_TEST_PIXELS) {
            fail("Unsafe test dimensions/generation: " + width + "x" + height);
            return;
        }

        final int stride = width * 4;
        final ByteBuffer pixels;
        try {
            pixels = ByteBuffer.allocateDirect(Math.multiplyExact(stride, height));
        } catch (RuntimeException error) {
            fail("Frame allocation failed: " + error);
            return;
        }

        // A moving-free color-bar pattern makes orientation/stride errors obvious.
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int band = (int) ((long) x * 4 / width);
                int r = band == 0 || band == 3 ? 220 : 24;
                int g = band == 1 || band == 3 ? 200 : 36;
                int b = band == 2 ? 240 : 64;
                pixels.put((byte) r);
                pixels.put((byte) g);
                pixels.put((byte) b);
                pixels.put((byte) 255);
            }
        }
        pixels.flip();

        boolean passed = surfaceView.presentRgbaFrame(pixels, width, height, stride, generation);
        finished = true;
        if (passed) {
            String message = "PASS: native ANativeWindow CPU-copy frame posted\n"
                    + "Dimensions: " + width + " × " + height
                    + "\nGeneration: " + generation
                    + "\nThis is not a Weston frame or zero-copy test.";
            status.setText(message);
            Log.i(TAG, message.replace('\n', ' '));
        } else {
            fail("Native presenter rejected the test frame; inspect XW11Surface logs.");
        }
    }

    private void fail(String message) {
        finished = true;
        status.setText("FAIL: " + message);
        Log.e(TAG, message);
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        finished = true;
    }
}
