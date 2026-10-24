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

        // Surface locking/copying and frame preparation must not block Android's UI thread.
        finished = true;
        status.setText("Submitting bounded synthetic frame off the UI thread…");
        final int frameWidth = width;
        final int frameHeight = height;
        Thread worker = new Thread(() -> {
            final int stride = frameWidth * 4;
            final ByteBuffer pixels;
            try {
                pixels = ByteBuffer.allocateDirect(Math.multiplyExact(stride, frameHeight));
            } catch (RuntimeException error) {
                postResult("FAIL: frame allocation failed: " + error, false);
                return;
            }

            for (int y = 0; y < frameHeight; y++) {
                for (int x = 0; x < frameWidth; x++) {
                    int band = (int) ((long) x * 4 / frameWidth);
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

            boolean passed = surfaceView.presentRgbaFrame(
                    pixels, frameWidth, frameHeight, stride, generation);
            if (passed) {
                String message = "PASS: native ANativeWindow CPU-copy frame posted"
                        + " Dimensions: " + frameWidth + " × " + frameHeight
                        + " Generation: " + generation
                        + " This is not a Weston frame or zero-copy test.";
                postResult(message, true);
            } else {
                postResult("FAIL: native presenter rejected the frame; inspect XW11Surface logs.", false);
            }
        }, "XW11-Presenter-Probe");
        worker.start();
    }

    private void postResult(String message, boolean passed) {
        runOnUiThread(() -> {
            status.setText(message);
            if (passed) Log.i(TAG, message);
            else Log.e(TAG, message);
        });
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
