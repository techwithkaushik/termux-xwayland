package org.techwithkaushik.termuxxwayland

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.min

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
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    @Volatile private var surfaceReady = false
    @Volatile private var renderThread: Thread? = null
    private var latestFrame: Bitmap? = null
    private var frameVersion = 0L
    private var renderedVersion = -1L
    private var firstFrameDrawnLogged = false
    var inputEventListener: ((ByteArray) -> Unit)? = null
    private var lastMoveAt = 0L
    private var pointerCaptured = false
    @Volatile private var cursorX = 0.5f
    @Volatile private var cursorY = 0.5f
    @Volatile private var desktopFrameAvailable = false
    @Volatile private var cursorVisible = true

    init {
        setBackgroundColor(Color.BLACK)
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
            renderedVersion = -1L
            frameLock.notifyAll()
        }
        Log.i(TAG, "event=surface_changed width=$width height=$height format=$format")
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

    fun setFrame(frame: Bitmap) {
        synchronized(frameLock) {
            val previous = latestFrame
            latestFrame = frame
            frameVersion++
            desktopFrameAvailable = true
            if (previous != null && previous !== frame && !previous.isRecycled) {
                previous.recycle()
            }
            frameLock.notifyAll()
        }
    }

    fun clearFrame() {
        synchronized(frameLock) {
            latestFrame?.let { if (!it.isRecycled) it.recycle() }
            latestFrame = null
            frameVersion++
            frameLock.notifyAll()
        }
    }

    private fun startRenderThread() {
        if (renderThread?.isAlive == true) return
        renderThread = Thread({
            var lastLoggedErrorAt = 0L
            while (surfaceReady && !Thread.currentThread().isInterrupted) {
                val frame: Bitmap?
                val version: Long
                synchronized(frameLock) {
                    while (surfaceReady && frameVersion == renderedVersion &&
                        !Thread.currentThread().isInterrupted) {
                        try {
                            frameLock.wait()
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                    if (!surfaceReady || Thread.currentThread().isInterrupted) break
                    frame = latestFrame
                    version = frameVersion
                }

                if (frame == null || frame.isRecycled || !holder.surface.isValid) {
                    synchronized(frameLock) { renderedVersion = version }
                    continue
                }

                var canvas: Canvas? = null
                try {
                    canvas = holder.lockCanvas()
                    if (canvas != null) {
                        synchronized(frameLock) {
                            if (surfaceReady && !frame.isRecycled) {
                                drawFrame(canvas, frame)
                                renderedVersion = version
                                if (!firstFrameDrawnLogged) {
                                    firstFrameDrawnLogged = true
                                    Log.i(TAG, "event=first_frame_drawn renderer=SurfaceView bitmap=${frame.width}x${frame.height} canvas=${canvas.width}x${canvas.height}")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastLoggedErrorAt > 5000L) {
                        Log.w(TAG, "event=surface_render_failed message=${e.message}", e)
                        lastLoggedErrorAt = now
                    }
                } finally {
                    if (canvas != null) {
                        try {
                            holder.unlockCanvasAndPost(canvas)
                        } catch (e: Exception) {
                            Log.w(TAG, "event=surface_post_failed message=${e.message}")
                        }
                    }
                }
            }
        }, "XWaylandSurfaceRenderer").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    private fun drawFrame(canvas: Canvas, bitmap: Bitmap) {
        canvas.drawColor(Color.BLACK)
        if (width <= 0 || height <= 0) return
        val scale = min(canvas.width.toFloat() / bitmap.width, canvas.height.toFloat() / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (canvas.width - drawWidth) / 2f
        val top = (canvas.height - drawHeight) / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawWidth, top + drawHeight), paint)
        if (cursorVisible) {
            val cursorLeft = left + cursorX * drawWidth
            val cursorTop = top + cursorY * drawHeight
            drawCursor(canvas, cursorLeft, cursorTop, min(canvas.width, canvas.height) / 42f)
        }
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
        } else if (!pointerCaptured) {
            return true
        }

        val now = android.os.SystemClock.uptimeMillis()
        if (action == "MOVE" && now - lastMoveAt < 16L) return true
        if (action == "MOVE") lastMoveAt = now
        val normalizedX = ((event.x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
        val normalizedY = ((event.y - bounds.top) / bounds.height()).coerceIn(0f, 1f)
        cursorX = normalizedX
        cursorY = normalizedY
        cursorVisible = true
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

    private fun drawCursor(canvas: Canvas, x: Float, y: Float, scale: Float) {
        // Startup marker until the first real desktop frame arrives; then show a desktop arrow.
        if (!desktopFrameAvailable) {
            val arm = 10f * scale
            cursorPaint.style = Paint.Style.STROKE
            cursorPaint.strokeWidth = 3f * scale
            canvas.drawLine(x - arm, y - arm, x + arm, y + arm, cursorPaint)
            canvas.drawLine(x + arm, y - arm, x - arm, y + arm, cursorPaint)
            cursorPaint.style = Paint.Style.FILL
            return
        }
        val path = android.graphics.Path().apply {
            moveTo(x, y)
            lineTo(x, y + 20f * scale)
            lineTo(x + 5f * scale, y + 15f * scale)
            lineTo(x + 9f * scale, y + 25f * scale)
            lineTo(x + 13f * scale, y + 23f * scale)
            lineTo(x + 9f * scale, y + 13f * scale)
            lineTo(x + 16f * scale, y + 13f * scale)
            close()
        }
        canvas.drawPath(path, cursorPaint)
    }

    private fun contentBounds(): RectF {
        val frameWidth: Int
        val frameHeight: Int
        synchronized(frameLock) {
            val frame = latestFrame
            frameWidth = if (frame != null && !frame.isRecycled) frame.width else 640
            frameHeight = if (frame != null && !frame.isRecycled) frame.height else 360
        }
        val scale = min(width.toFloat() / frameWidth, height.toFloat() / frameHeight)
        val drawWidth = frameWidth * scale
        val drawHeight = frameHeight * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        return RectF(left, top, left + drawWidth, top + drawHeight)
    }

    private companion object {
        const val TAG = "TermuxXWayland"
    }
}
