package org.techwithkaushik.termuxxwayland

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Canvas-backed desktop frame renderer.
 *
 * Use the Activity's normal View rendering path rather than a separate
 * SurfaceView/BufferQueue layer. This avoids SurfaceView z-order/composition
 * problems on older Android/MIUI builds while keeping the incoming RGBA frame
 * format and pointer coordinate mapping unchanged.
 */
internal class DesktopSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val frameLock = Any()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var latestFrame: Bitmap? = null
    private var firstFrameDrawnLogged = false
    var inputEventListener: ((ByteArray) -> Unit)? = null
    private var lastMoveAt = 0L
    private var pointerCaptured = false

    init {
        setBackgroundColor(Color.rgb(20, 29, 43))
        isFocusable = true
        isFocusableInTouchMode = true
        setWillNotDraw(false)
    }

    fun setFrame(frame: Bitmap) {
        synchronized(frameLock) {
            val previous = latestFrame
            latestFrame = frame
            if (previous != null && previous !== frame && !previous.isRecycled) {
                previous.recycle()
            }
        }
        postInvalidateOnAnimation()
    }

    fun clearFrame() {
        synchronized(frameLock) {
            latestFrame?.let { if (!it.isRecycled) it.recycle() }
            latestFrame = null
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(20, 29, 43))
        synchronized(frameLock) {
            val bitmap = latestFrame
            if (bitmap != null && !bitmap.isRecycled && width > 0 && height > 0) {
                val scale = min(
                    width.toFloat() / bitmap.width,
                    height.toFloat() / bitmap.height
                )
                val drawWidth = bitmap.width * scale
                val drawHeight = bitmap.height * scale
                val left = (width - drawWidth) / 2f
                val top = (height - drawHeight) / 2f
                canvas.drawBitmap(
                    bitmap,
                    null,
                    RectF(left, top, left + drawWidth, top + drawHeight),
                    paint
                )
                if (!firstFrameDrawnLogged) {
                    firstFrameDrawnLogged = true
                    android.util.Log.i(
                        TAG,
                        "event=first_frame_drawn renderer=View bitmap=${bitmap.width}x${bitmap.height} canvas=${canvas.width}x${canvas.height}"
                    )
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        android.util.Log.i(TAG, "event=view_size_changed width=$w height=$h")
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

        val normalizedX = ((event.x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
        val normalizedY = ((event.y - bounds.top) / bounds.height()).coerceIn(0f, 1f)
        val payload = "$action $normalizedX $normalizedY".toByteArray(Charsets.US_ASCII)
        runCatching { inputEventListener?.invoke(payload) }
        if (action == "UP" || action == "CANCEL") pointerCaptured = false
        return true
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
