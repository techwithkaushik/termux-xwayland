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
    var inputEventListener: ((ByteArray) -> Unit)? = null
    private var lastMoveAt = 0L

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
        val now = android.os.SystemClock.uptimeMillis()
        if (event.actionMasked == android.view.MotionEvent.ACTION_MOVE && now - lastMoveAt < 32L) return true
        if (event.actionMasked == android.view.MotionEvent.ACTION_MOVE) lastMoveAt = now
        val normalizedX = (event.x / width.toFloat()).coerceIn(0f, 1f)
        val normalizedY = (event.y / height.toFloat()).coerceIn(0f, 1f)
        val action = when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> "DOWN"
            android.view.MotionEvent.ACTION_MOVE -> "MOVE"
            android.view.MotionEvent.ACTION_UP -> "UP"
            android.view.MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> return true
        }
        val payload = "$action ${normalizedX} ${normalizedY}".toByteArray(Charsets.US_ASCII)
        runCatching { inputEventListener?.invoke(payload) }
        return true
    }

    private fun renderLatestFrame() {
        if (!surfaceReady || width <= 0 || height <= 0) return
        val canvas = try {
            holder.lockCanvas()
        } catch (_: Exception) {
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
                }
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }
}
