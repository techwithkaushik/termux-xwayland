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
    private data class PixelFrame(val pixels: ByteArray, val width: Int, val height: Int, val pixelOffset: Int)
    private var latestFrame: PixelFrame? = null
    private var renderCursorX = 0.5f
    private var renderCursorY = 0.5f
    private var frameVersion = 0L
    private var renderedVersion = -1L
    private var firstFrameDrawnLogged = false
    var inputEventListener: ((ByteArray) -> Unit)? = null
    var cursorPositionListener: ((Float, Float) -> Unit)? = null
    private var lastMoveAt = 0L
    private var pointerCaptured = false
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var touchMoved = false
    private var lastCentroidX = 0f
    private var lastCentroidY = 0f
    private var trackpadCursorX = -1f
    private var trackpadCursorY = -1f
    private var lastTapAt = 0L
    private var tapCount = 0
    private val pointerPrefs by lazy {
        context.getSharedPreferences("termux_xwayland_preferences", Context.MODE_PRIVATE)
    }
    private val touchMode: Int
        get() = pointerPrefs.getInt("touch_mode", 0).coerceIn(0, 2)
    private val pointerSpeed: Float
        get() = pointerPrefs.getInt("pointer_speed", 100).coerceIn(1, 300) / 100f

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

    fun setFrame(pixels: ByteArray, width: Int, height: Int, pixelOffset: Int = 0) {
        if (width <= 0 || height <= 0 || pixelOffset < 0 || pixelOffset.toLong() + width.toLong() * height * 4L > pixels.size) return
        synchronized(frameLock) {
            // Keep only the newest decoded frame; avoid allocating/copying a Bitmap per frame.
            latestFrame = PixelFrame(pixels, width, height, pixelOffset)
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

    /** Re-render the newest frame after returning from Preferences. */
    fun refreshDisplayPreferences() {
        synchronized(frameLock) {
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
                        else Triple(latestFrame, frameVersion, Pair(renderCursorX, renderCursorY))
                    } ?: break

                    val frame = snapshot.first
                    val version = snapshot.second
                    val cursor = snapshot.third
                    if (frame == null || !holder.surface.isValid) {
                        synchronized(frameLock) { renderedVersion = version }
                        continue
                    }

                    try {
                        // Do not hold frameLock while JNI/GPU work runs: the incoming
                        // frame callback must be able to replace stale frames immediately.
                        if (surfaceReady && nativeHandle != 0L &&
                            NativeEglRenderer.nativeRender(
                                nativeHandle,
                                frame.pixels,
                                frame.pixelOffset,
                                frame.width,
                                frame.height,
                                cursor.first,
                                cursor.second,
                                true,
                                pointerPrefs.getInt("resolution_mode", 0).coerceIn(0, 3),
                                pointerPrefs.getInt("display_scale", 100).coerceIn(30, 300),
                                pointerPrefs.getInt("display_filter", 0).coerceIn(0, 1),
                                pointerPrefs.getBoolean("display_stretch", false)
                            )) {
                            synchronized(frameLock) { renderedVersion = version }
                            if (!firstFrameDrawnLogged) {
                                firstFrameDrawnLogged = true
                                Log.i(TAG, "event=first_frame_drawn renderer=EGL_GLES2 pixels=${frame.width}x${frame.height}")
                            }
                        } else {
                            // A failed GPU/surface operation must consume this frame version.
                            // Otherwise the render loop immediately retries the same frame
                            // without waiting for a new one, pegging a CPU core and worsening
                            // EGL_BAD_SURFACE/context-loss crashes.
                            synchronized(frameLock) {
                                renderedVersion = maxOf(renderedVersion, version)
                            }
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastLoggedErrorAt > 5000L) {
                                Log.w(TAG, "event=native_egl_render_failed frame=${frame.width}x${frame.height}")
                                lastLoggedErrorAt = now
                            }
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
KeyEvent.KEYCODE_F1 -> "F1"
KeyEvent.KEYCODE_F2 -> "F2"
KeyEvent.KEYCODE_F3 -> "F3"
KeyEvent.KEYCODE_F4 -> "F4"
KeyEvent.KEYCODE_F5 -> "F5"
KeyEvent.KEYCODE_F6 -> "F6"
KeyEvent.KEYCODE_F7 -> "F7"
KeyEvent.KEYCODE_F8 -> "F8"
KeyEvent.KEYCODE_F9 -> "F9"
KeyEvent.KEYCODE_F10 -> "F10"
KeyEvent.KEYCODE_F11 -> "F11"
KeyEvent.KEYCODE_F12 -> "F12"
KeyEvent.KEYCODE_COMMA -> "comma"
KeyEvent.KEYCODE_PERIOD -> "period"
KeyEvent.KEYCODE_MINUS -> "minus"
KeyEvent.KEYCODE_EQUALS -> "equal"
KeyEvent.KEYCODE_SLASH -> "slash"
KeyEvent.KEYCODE_BACKSLASH -> "backslash"
KeyEvent.KEYCODE_SEMICOLON -> "semicolon"
KeyEvent.KEYCODE_APOSTROPHE -> "apostrophe"
KeyEvent.KEYCODE_LEFT_BRACKET -> "bracketleft"
KeyEvent.KEYCODE_RIGHT_BRACKET -> "bracketright"
KeyEvent.KEYCODE_GRAVE -> "grave"
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "Shift_L"
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> "Control_L"
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> "Alt_L"
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> "Super_L"
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('a' + keyCode - KeyEvent.KEYCODE_A).toString()
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0' + keyCode - KeyEvent.KEYCODE_0).toString()
        else -> null
    }

    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                activePointerId = event.getPointerId(index)
                pointerCaptured = true
                touchStartX = x
                touchStartY = y
                lastTouchX = x
                lastTouchY = y
                lastCentroidX = x
                lastCentroidY = y
                touchMoved = false
                when (touchMode) {
                    0 -> {
                        // The remote cursor plane is absent from some headless captures.
                        // Keep a crisp local cursor synchronized with relative pointer deltas.
                        val bounds = contentBounds()
                        if (trackpadCursorX < 0f || trackpadCursorY < 0f) {
                            trackpadCursorX = bounds.centerX()
                            trackpadCursorY = bounds.centerY()
                        }
                        updateRenderCursor(trackpadCursorX, trackpadCursorY)
                        cursorPositionListener?.invoke(trackpadCursorX, trackpadCursorY)
                    } // Trackpad: relative motion and tap-to-click.
                    1 -> sendPointer("MOVE", x, y) // Simulated touchscreen: absolute pointer, tap-to-click.
                    else -> sendPointer("DOWN", x, y) // Direct touch: hold left button while in contact.
                }
                requestFocus()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Three-finger gesture exposes Android IME, following Termux:X11-style desktop input behavior.
                if (event.pointerCount >= 3) {
                    requestFocus()
                    post {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                        imm.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                // Keep the original finger as the primary pointer; a second finger must not release the mouse button.
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pointerCaptured) return true
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                val x = event.getX(index)
                val y = event.getY(index)
                val now = android.os.SystemClock.uptimeMillis()
                if (kotlin.math.abs(x - touchStartX) > 7f || kotlin.math.abs(y - touchStartY) > 7f) touchMoved = true
                // Match Termux:X11's trackpad semantics: one finger moves the pointer
                // relatively, while two fingers scroll instead of moving the pointer.
                if (touchMode == 0 && event.pointerCount >= 2) {
                    var cx = 0f
                    var cy = 0f
                    for (i in 0 until event.pointerCount) {
                        cx += event.getX(i)
                        cy += event.getY(i)
                    }
                    cx /= event.pointerCount
                    cy /= event.pointerCount
                    val scrollX = (cx - lastCentroidX) / resources.displayMetrics.density
                    val scrollY = (cy - lastCentroidY) / resources.displayMetrics.density
                    if (now - lastMoveAt >= 16L && (kotlin.math.abs(scrollX) >= 1f || kotlin.math.abs(scrollY) >= 1f)) {
                        sendInput("SCROLL ${-scrollY / 3f} ${-scrollX / 3f}")
                        lastMoveAt = now
                    }
                    lastCentroidX = cx
                    lastCentroidY = cy
                    return true
                }
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                // Do not impose a 16 ms software throttle on pointer motion.
                // The Termux-side native XTest helper coalesces/flushes events
                // without launching a new xdotool process for every move.
                lastMoveAt = now
                when (touchMode) {
                    0 -> {
                        if (touchMoved && (dx != 0f || dy != 0f)) {
                            val bounds = contentBounds()
                            val scaleX = if (bounds.width() > 0f) (frameWidthForInput() / bounds.width()) else 1f
                            val scaleY = if (bounds.height() > 0f) (frameHeightForInput() / bounds.height()) else 1f
                            val desktopDx = kotlin.math.round(dx * scaleX * pointerSpeed).toInt()
                            val desktopDy = kotlin.math.round(dy * scaleY * pointerSpeed).toInt()
                            sendInput("REL $desktopDx $desktopDy")
                            trackpadCursorX = (trackpadCursorX + desktopDx * bounds.width() / frameWidthForInput())
                                .coerceIn(bounds.left, bounds.right)
                            trackpadCursorY = (trackpadCursorY + desktopDy * bounds.height() / frameHeightForInput())
                                .coerceIn(bounds.top, bounds.bottom)
                            updateRenderCursor(trackpadCursorX, trackpadCursorY)
                            cursorPositionListener?.invoke(trackpadCursorX, trackpadCursorY)
                        }
                    }
                    else -> sendPointer("MOVE", x, y)
                }
                lastTouchX = x
                lastTouchY = y
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    if (touchMode == 2) sendPointer("UP", event.getX(event.actionIndex), event.getY(event.actionIndex), allowOutsideCapture = true)
                    pointerCaptured = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (pointerCaptured) {
                    val index = event.findPointerIndex(activePointerId).let {
                        if (it >= 0) it else event.actionIndex
                    }
                    val x = event.getX(index)
                    val y = event.getY(index)
                    when (touchMode) {
                        0 -> if (!touchMoved) {
                            val now = android.os.SystemClock.uptimeMillis()
                            tapCount = if (now - lastTapAt <= android.view.ViewConfiguration.getDoubleTapTimeout()) tapCount + 1 else 1
                            lastTapAt = now
                            sendInput("CLICK ${if (tapCount >= 2) 1 else 1}")
                            if (tapCount >= 2) tapCount = 0
                        }
                        1 -> if (!touchMoved) {
                            sendPointer("MOVE", x, y, allowOutsideCapture = true)
                            sendInput("CLICK 1")
                        }
                        else -> sendPointer("UP", x, y, allowOutsideCapture = true)
                    }
                }
                pointerCaptured = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pointerCaptured && touchMode == 2) {
                    sendPointer("CANCEL", event.x, event.y, allowOutsideCapture = true)
                }
                pointerCaptured = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                return true
            }
            MotionEvent.ACTION_HOVER_MOVE -> {
                sendPointer("MOVE", event.x, event.y, allowOutsideCapture = true)
                return true
            }
            MotionEvent.ACTION_SCROLL -> {
                val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val horizontal = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (vertical != 0f || horizontal != 0f) {
                    sendInput("SCROLL $vertical $horizontal")
                }
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                val down = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
                val state = event.buttonState
                val button = when (event.actionButton) {
                    MotionEvent.BUTTON_SECONDARY -> 3
                    MotionEvent.BUTTON_TERTIARY -> 2
                    MotionEvent.BUTTON_PRIMARY -> 1
                    else -> when {
                        state and MotionEvent.BUTTON_SECONDARY != 0 -> 3
                        state and MotionEvent.BUTTON_TERTIARY != 0 -> 2
                        else -> 1
                    }
                }
                sendInput("BUTTON $button ${if (down) "DOWN" else "UP"}")
                return true
            }
            else -> return true
        }
    }

    private fun sendPointer(
        action: String,
        x: Float,
        y: Float,
        allowOutsideCapture: Boolean = false
    ): Boolean {
        val bounds = contentBounds()
        val insideContent = x >= bounds.left && x <= bounds.right &&
            y >= bounds.top && y <= bounds.bottom
        if (!insideContent && !allowOutsideCapture) return false
        if (bounds.width() <= 0f || bounds.height() <= 0f) return false

        updateRenderCursor(x, y)
        cursorPositionListener?.invoke(x, y)
        val normalizedX = ((x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
        val normalizedY = ((y - bounds.top) / bounds.height()).coerceIn(0f, 1f)
        sendInput("$action $normalizedX $normalizedY")
        return true
    }

    private fun updateRenderCursor(x: Float, y: Float) {
        val bounds = contentBounds()
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        synchronized(frameLock) {
            renderCursorX = ((x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
            renderCursorY = ((y - bounds.top) / bounds.height()).coerceIn(0f, 1f)
            frameVersion++
            frameLock.notifyAll()
        }
    }

    private fun sendInput(value: String) {
        runCatching {
            inputEventListener?.invoke(value.toByteArray(Charsets.US_ASCII))
        }
    }

    // Input coordinates target the full-size XWayland output, not the
    // bandwidth-capped preview frame. Using latestFrame.width here makes
    // touch motion progressively sluggish whenever the stream is downscaled.
    private fun frameWidthForInput(): Float = resources.displayMetrics.widthPixels.toFloat()

    private fun frameHeightForInput(): Float = resources.displayMetrics.heightPixels.toFloat()

    private fun contentBounds(): android.graphics.RectF {
        val frameWidth: Int
        val frameHeight: Int
        synchronized(frameLock) {
            val frame = latestFrame
            frameWidth = frame?.width ?: 640
            frameHeight = frame?.height ?: 360
        }

        // Keep input hit-testing in the same coordinate space as native_egl_renderer.cpp.
        // Previously touch input always assumed "Fit", even when the renderer was
        // stretched, zoomed, or pixel-perfect. That made clicks drift away from the
        // visible target whenever a non-default output mode was selected.
        val prefs = pointerPrefs
        val mode = prefs.getInt("resolution_mode", 0).coerceIn(0, 3)
        val stretch = prefs.getBoolean("display_stretch", false)
        val fitScale = kotlin.math.min(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
        val scale = when {
            mode == 2 || stretch -> kotlin.math.min(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
                .let { base -> if (mode == 2 || stretch) base else base }
            mode == 3 -> kotlin.math.min(
                1f,
                kotlin.math.min(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
            )
            mode == 1 -> fitScale * prefs.getInt("display_scale", 100).coerceIn(30, 300) / 100f
            else -> fitScale
        }

        if (mode == 2 || stretch) {
            return android.graphics.RectF(0f, 0f, width.toFloat(), height.toFloat())
        }
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
