package org.techwithkaushik.termuxxwayland

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * SurfaceView renderer for the streamed X11 desktop.
 *
 * Frames are rendered on a dedicated thread only when a new frame arrives or
 * the surface is recreated. This avoids the old View/Canvas UI-thread bottleneck
 * without a busy loop that burns CPU on low-memory Android devices.
 */
internal class DesktopSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private val frameLock = Object()
    @Volatile private var surfaceReady = false
    @Volatile private var renderThread: Thread? = null
    private data class PixelFrame(val pixels: ByteArray, val width: Int, val height: Int)
    private var latestFrame: PixelFrame? = null
    private var frameVersion = 0L
    private var renderedVersion = -1L
    private var firstFrameDrawnLogged = false
    var inputEventListener: ((ByteArray) -> Unit)? = null
    var cursorPositionListener: ((Float, Float) -> Unit)? = null
    private var lastMoveAt = 0L
    private var pointerCaptured = false

    init {
        // Match Termux:X11's transparent view background. Configure alpha before
        // the Surface is created so EGL can composite letterbox areas correctly.
        setBackgroundColor(Color.TRANSPARENT)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        isFocusable = true
        isFocusableInTouchMode = true
        holder.addCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        synchronized(frameLock) { renderedVersion = -1L }
        startRenderThread()
        Log.i(TAG, "event=surface_created width=$width height=$height")
        synchronized(frameLock) { frameLock.notifyAll() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(frameLock) {
            // Re-present the most recent frame after a size/format change.
            renderedVersion = -1L
            frameLock.notifyAll()
        }
        Log.i(TAG, "event=surface_changed width=$width height=$height format=$format valid=${holder.surface.isValid}")
        // surfaceChanged can arrive after a fast EGL initialization failure. Make
        // sure a dead renderer is restarted instead of leaving a permanently black view.
        if (holder.surface.isValid && renderThread?.isAlive != true) startRenderThread()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        val thread = renderThread
        renderThread = null
        synchronized(frameLock) { frameLock.notifyAll() }
        if (thread != null && thread !== Thread.currentThread()) {
            thread.interrupt()
            try {
                thread.join(1000L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        Log.i(TAG, "event=surface_destroyed")
    }

    fun setFrame(pixels: ByteArray, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return
        synchronized(frameLock) {
            // Keep only the newest decoded frame; avoid allocating/copying a Bitmap per frame.
            latestFrame = PixelFrame(pixels, width, height)
            frameVersion++
            frameLock.notifyAll()
        }
    }

    fun clearFrame() {
        synchronized(frameLock) {
            latestFrame = null
            frameVersion++
            frameLock.notifyAll()
        }
    }

    private fun startRenderThread() {
        if (renderThread?.isAlive == true) return
        val thread = Thread({
            var nativeHandle = 0L
            var lastLoggedErrorAt = 0L
            try {
                // Some Android devices deliver surfaceCreated before the window is
                // fully EGL-ready. Retry initialization while this same surface lives
                // instead of exiting permanently on the first transient failure.
                while (surfaceReady && !Thread.currentThread().isInterrupted &&
                    nativeHandle == 0L) {
                    if (!holder.surface.isValid) {
                        try { Thread.sleep(100L) } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                        continue
                    }
                    nativeHandle = NativeEglRenderer.nativeInitialize(holder.surface)
                    if (nativeHandle == 0L && surfaceReady &&
                        !Thread.currentThread().isInterrupted) {
                        Log.e(TAG, "event=native_egl_init_failed retry_ms=500")
                        try { Thread.sleep(500L) } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
                if (nativeHandle == 0L) return@Thread
                Log.i(TAG, "event=native_egl_initialized renderer=EGL_GLES2")
                while (surfaceReady && !Thread.currentThread().isInterrupted) {
                    val snapshot = synchronized(frameLock) {
                        while (surfaceReady && frameVersion == renderedVersion &&
                            !Thread.currentThread().isInterrupted) {
                            try {
                                frameLock.wait()
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                                break
                            }
                        }
                        if (!surfaceReady || Thread.currentThread().isInterrupted) null
                        else Pair(latestFrame, frameVersion)
                    } ?: break

                    val frame = snapshot.first
                    val version = snapshot.second
                    if (frame == null || !holder.surface.isValid) {
                        synchronized(frameLock) { renderedVersion = version }
                        continue
                    }

                    try {
                        // Do not hold frameLock while JNI/GPU work runs: the incoming
                        // frame callback must be able to replace stale frames immediately.
                        if (surfaceReady && nativeHandle != 0L &&
                            NativeEglRenderer.nativeRender(nativeHandle, frame.pixels, frame.width, frame.height)) {
                            synchronized(frameLock) { renderedVersion = version }
                            if (!firstFrameDrawnLogged) {
                                firstFrameDrawnLogged = true
                                Log.i(TAG, "event=first_frame_drawn renderer=EGL_GLES2 pixels=${frame.width}x${frame.height}")
                            }
                        } else {
                            Log.w(TAG, "event=native_egl_render_failed")
                        }
                    } catch (e: Exception) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastLoggedErrorAt > 5000L) {
                            Log.w(TAG, "event=native_egl_render_exception message=${e.message}", e)
                            lastLoggedErrorAt = now
                        }
                    }
                }
            } finally {
                if (nativeHandle != 0L) {
                    runCatching { NativeEglRenderer.nativeRelease(nativeHandle) }
                        .onFailure { Log.w(TAG, "event=native_egl_release_failed", it) }
                }
                // Clear only our own thread reference. A new Surface may already
                // have installed a replacement renderer thread.
                if (renderThread === Thread.currentThread()) renderThread = null
                Log.i(TAG, "event=native_egl_thread_stopped surfaceReady=${surfaceReady}")
                // Covers the race where surfaceCreated arrives while the previous
                // thread is still exiting and startRenderThread therefore cannot start.
                if (surfaceReady && !Thread.currentThread().isInterrupted) {
                    post {
                        if (surfaceReady && holder.surface.isValid && renderThread?.isAlive != true) {
                            startRenderThread()
                        }
                    }
                }
            }
        }, "XWaylandNativeEglRenderer").apply {
            priority = Thread.NORM_PRIORITY
        }
        // Publish the reference before starting: a very fast thread exit must be
        // able to clear its own reference in finally without racing this assignment.
        renderThread = thread
        thread.start()
    }


    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString().orEmpty()
                if (value.isEmpty()) return true
                // Keep the bridge packet bounded and reject control characters.
                val safe = value.filter { it == '\n' || it == '\t' || it >= ' ' && it != '\u007f' }
                if (safe.isNotEmpty() && safe.toByteArray(Charsets.UTF_8).size <= 100) {
                    inputEventListener?.invoke(("TEXT " + safe).toByteArray(Charsets.UTF_8))
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceIn(0, 20)) {
                    inputEventListener?.invoke("KEY BackSpace DOWN".toByteArray(Charsets.US_ASCII))
                    inputEventListener?.invoke("KEY BackSpace UP".toByteArray(Charsets.US_ASCII))
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                val name = xdotoolKeyName(event.keyCode) ?: return false
                inputEventListener?.invoke(
                    "KEY $name ${if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"}"
                        .toByteArray(Charsets.US_ASCII)
                )
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val name = xdotoolKeyName(keyCode) ?: return super.onKeyDown(keyCode, event)
        inputEventListener?.invoke("KEY $name DOWN".toByteArray(Charsets.US_ASCII))
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val name = xdotoolKeyName(keyCode) ?: return super.onKeyUp(keyCode, event)
        inputEventListener?.invoke("KEY $name UP".toByteArray(Charsets.US_ASCII))
        return true
    }

    private fun xdotoolKeyName(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER -> "Return"
        KeyEvent.KEYCODE_DEL -> "BackSpace"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_ESCAPE -> "Escape"
        KeyEvent.KEYCODE_SPACE -> "space"
        KeyEvent.KEYCODE_DPAD_UP -> "Up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
        KeyEvent.KEYCODE_HOME -> "Home"
        KeyEvent.KEYCODE_MOVE_END -> "End"
        KeyEvent.KEYCODE_PAGE_UP -> "Page_Up"
        KeyEvent.KEYCODE_PAGE_DOWN -> "Page_Down"
        KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "Shift_L"
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> "Control_L"
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> "Alt_L"
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> "Super_L"
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('a' + keyCode - KeyEvent.KEYCODE_A).toString()
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + keyCode - KeyEvent.KEYCODE_0).toString()
        else -> null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return true
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> return true
        }
        val bounds = contentBounds()
        val insideContent = event.x >= bounds.left && event.x <= bounds.right &&
            event.y >= bounds.top && event.y <= bounds.bottom
        if (action == "DOWN") {
            if (!insideContent) return true
            pointerCaptured = true
            requestFocus()
        } else if (!pointerCaptured) {
            return true
        }

        val now = android.os.SystemClock.uptimeMillis()
        if (action == "MOVE" && now - lastMoveAt < 33L) return true
        if (action == "MOVE") lastMoveAt = now
        cursorPositionListener?.invoke(event.x, event.y)
        val normalizedX = ((event.x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
        val normalizedY = ((event.y - bounds.top) / bounds.height()).coerceIn(0f, 1f)
        synchronized(frameLock) {
            frameVersion++
            frameLock.notifyAll()
        }
        runCatching {
            inputEventListener?.invoke("$action $normalizedX $normalizedY".toByteArray(Charsets.US_ASCII))
        }
        if (action == "UP" || action == "CANCEL") pointerCaptured = false
        return true
    }

    private fun contentBounds(): android.graphics.RectF {
        val frameWidth: Int
        val frameHeight: Int
        synchronized(frameLock) {
            val frame = latestFrame
            frameWidth = frame?.width ?: 640
            frameHeight = frame?.height ?: 360
        }
        val scale = kotlin.math.min(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
        val drawWidth = frameWidth * scale
        val drawHeight = frameHeight * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        return android.graphics.RectF(left, top, left + drawWidth, top + drawHeight)
    }

    private companion object {
        const val TAG = "TermuxXWayland"
    }
}
