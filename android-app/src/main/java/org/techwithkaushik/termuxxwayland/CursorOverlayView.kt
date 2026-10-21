package org.techwithkaushik.termuxxwayland

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max

/**
 * Temporary Android-side pointer shown while Weston has not delivered a real
 * framebuffer yet. It is an overlay sibling above SurfaceView because EGL owns
 * the SurfaceView's pixels and would erase a Canvas cursor drawn into it.
 */
internal class CursorOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private var cursorX = 0f
    private var cursorY = 0f
    private var positioned = false
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.FILL
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = max(1f, 1.6f * density)
        strokeJoin = Paint.Join.ROUND
    }
    private val arrow = Path()

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setCursorPosition(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, width.coerceAtLeast(1).toFloat())
        cursorY = y.coerceIn(0f, height.coerceAtLeast(1).toFloat())
        positioned = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!positioned) {
            cursorX = w / 2f
            cursorY = h / 2f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        if (!positioned) {
            cursorX = width / 2f
            cursorY = height / 2f
        }
        val x = cursorX
        val y = cursorY
        val unit = density
        arrow.reset()
        arrow.moveTo(x, y)
        arrow.lineTo(x, y + 25f * unit)
        arrow.lineTo(x + 6f * unit, y + 19f * unit)
        arrow.lineTo(x + 11f * unit, y + 30f * unit)
        arrow.lineTo(x + 16f * unit, y + 27f * unit)
        arrow.lineTo(x + 11f * unit, y + 16f * unit)
        arrow.lineTo(x + 21f * unit, y + 16f * unit)
        arrow.close()
        canvas.drawPath(arrow, outline)
        canvas.drawPath(arrow, fill)
    }
}
