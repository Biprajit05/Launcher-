package com.example.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View

/**
 * Full-screen overlay that draws the "window" growing out of an icon (and shrinking back).
 * progress 0 = exactly the icon's bounds, 1 = whole screen. One draw call per frame, no layout.
 */
class LaunchOverlay(context: Context) : View(context) {

    val from = RectF()          // icon bounds (root coordinates)
    val to = RectF()            // full screen
    var fromRadius = 0f         // icon corner radius
    var toRadius = 0f           // device corner radius
    var iconFrom = 0f           // icon size at progress 0
    var iconTo = 0f             // splash icon size at progress 1
    var icon: Drawable? = null
    var fillColor = Color.WHITE

    var progress = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        isClickable = true // swallow taps while the launch animation runs
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun onDraw(canvas: Canvas) {
        val p = progress
        r.set(
            lerp(from.left, to.left, p), lerp(from.top, to.top, p),
            lerp(from.right, to.right, p), lerp(from.bottom, to.bottom, p)
        )
        val rad = lerp(fromRadius, toRadius, p)

        // The window background fades in over the first quarter, so at progress 0 only the icon shows.
        val fillA = (p / 0.25f).coerceIn(0f, 1f)
        paint.color = fillColor
        paint.alpha = (Color.alpha(fillColor) * fillA).toInt()
        canvas.drawRoundRect(r, rad, rad, paint)

        icon?.let {
            val size = lerp(iconFrom, iconTo, p)
            val sc = size / BASE
            canvas.save()
            canvas.translate(r.centerX() - size / 2f, r.centerY() - size / 2f)
            canvas.scale(sc, sc)
            it.setBounds(0, 0, BASE.toInt(), BASE.toInt())
            it.draw(canvas)
            canvas.restore()
        }
    }

    private companion object {
        const val BASE = 256f
    }
}
