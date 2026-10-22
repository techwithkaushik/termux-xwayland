package org.techwithkaushik.termuxxwayland

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import android.view.View
import kotlin.math.max

/**
 * Android-side cursor overlay. Weston headless output capture does not reliably
 * include the X11 cursor plane, so keep the pointer visible independently of
 * the RGBA frame stream.
 */
internal class DesktopCursorOverlay(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
    }
    @Volatile private var pointerX = -1f
    @Volatile private var pointerY = -1f
    @Volatile private var pointerVisible = false
    private val density = resources.displayMetrics.density

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun updatePointer(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        pointerX = x.coerceIn(0f, max(0f, width.toFloat() - 1f))
        pointerY = y.coerceIn(0f, max(0f, height.toFloat() - 1f))
        pointerVisible = true
        postInvalidateOnAnimation()
    }

    fun resetPointer() {
        pointerVisible = false
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!pointerVisible) return
        val x = pointerX
        val y = pointerY
        // High-contrast native cursor shape; its small fixed size stays crisp
        // even when the remote desktop stream is scaled to fit the phone.
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y + 21f * density)
            lineTo(x + 5.5f * density, y + 16f * density)
            lineTo(x + 10f * density, y + 26f * density)
            lineTo(x + 14f * density, y + 24f * density)
            lineTo(x + 9.5f * density, y + 14f * density)
            lineTo(x + 17f * density, y + 14f * density)
            close()
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, outline)
    }
}
