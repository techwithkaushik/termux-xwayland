package org.techwithkaushik.termuxxwayland

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.min

/**
 * Surface-backed desktop frame renderer.
 *
 * Frames are copied to the Surface on a worker thread instead of allocating an
 * ImageView bitmap update for every packet. The current transport may still feed
 * this view encoded RGBA frames; native compositor capture is a separate stage.
 */
internal class DesktopSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private val frameLock = Any()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var latestFrame: Bitmap? = null
    @Volatile private var surfaceReady = false
    @Volatile private var firstFrameDrawnLogged = false
    @Volatile private var renderFailureLogged = false
    var inputEventListener: ((ByteArray) -> Unit)? = null
    private var lastMoveAt = 0L
    private var pointerCaptured = false

    init {
        setBackgroundColor(Color.rgb(20, 29, 43))
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setFrame(frame: Bitmap) {
        synchronized(frameLock) {
            val previous = latestFrame
            latestFrame = frame
            if (previous != null && previous !== frame && !previous.isRecycled) {
                previous.recycle()
            }
        }
        renderLatestFrame()
    }

    fun clearFrame() {
        synchronized(frameLock) {
            latestFrame?.let { if (!it.isRecycled) it.recycle() }
            latestFrame = null
        }
        if (surfaceReady) {
            runCatching {
                holder.lockCanvas()?.let { canvas ->
                    try {
                        canvas.drawColor(Color.rgb(20, 29, 43))
                    } finally {
                        holder.unlockCanvasAndPost(canvas)
                    }
                }
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        android.util.Log.i(TAG, "event=surface_created width=" + width + " height=" + height)
        renderLatestFrame()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderLatestFrame()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return true

        val action = when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> "DOWN"
            android.view.MotionEvent.ACTION_MOVE -> "MOVE"
            android.view.MotionEvent.ACTION_UP -> "UP"
            android.view.MotionEvent.ACTION_CANCEL -> "CANCEL"
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
        if (action == "MOVE" && now - lastMoveAt < 32L) return true
        if (action == "MOVE") lastMoveAt = now

        // The image is letterboxed to preserve its aspect ratio. Map touches
        // against the displayed image rectangle, not the full SurfaceView.
        val normalizedX = ((event.x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
        val normalizedY = ((event.y - bounds.top) / bounds.height()).coerceIn(0f, 1f)
        val payload = "$action ${normalizedX} ${normalizedY}".toByteArray(Charsets.US_ASCII)
        runCatching { inputEventListener?.invoke(payload) }
        if (action == "UP" || action == "CANCEL") pointerCaptured = false
        return true
    }

    private fun contentBounds(): android.graphics.RectF {
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
        return android.graphics.RectF(left, top, left + drawWidth, top + drawHeight)
    }

    private fun renderLatestFrame() {
        if (!surfaceReady || width <= 0 || height <= 0) return
        val canvas = try {
            holder.lockCanvas()
        } catch (error: Exception) {
            if (!renderFailureLogged) {
                renderFailureLogged = true
                android.util.Log.w(TAG, "event=surface_lock_failed width=" + width + " height=" + height, error)
            }
            null
        } ?: return

        try {
            canvas.drawColor(Color.rgb(20, 29, 43))
            synchronized(frameLock) {
                val bitmap = latestFrame
                if (bitmap != null && !bitmap.isRecycled) {
                    val scale = min(
                        canvas.width.toFloat() / bitmap.width,
                        canvas.height.toFloat() / bitmap.height
                    )
                    val drawWidth = bitmap.width * scale
                    val drawHeight = bitmap.height * scale
                    val left = (canvas.width - drawWidth) / 2f
                    val top = (canvas.height - drawHeight) / 2f
                    canvas.drawBitmap(
                        bitmap,
                        null,
                        android.graphics.RectF(left, top, left + drawWidth, top + drawHeight),
                        paint
                    )
                    if (!firstFrameDrawnLogged) {
                        firstFrameDrawnLogged = true
                        android.util.Log.i(TAG, "event=first_frame_drawn bitmap=" + bitmap.width + "x" + bitmap.height + " canvas=" + canvas.width + "x" + canvas.height)
                    }
                }
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private companion object {
        const val TAG = "TermuxXWayland"
    }
}
