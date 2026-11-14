package com.example.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/** Small dots under the home screen: one per page, the current one is bigger and brighter. */
class PageDots @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var count = 1
    private var pos = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    fun set(count: Int, pos: Float) {
        if (this.count == count && abs(this.pos - pos) < 0.001f) return
        this.count = count
        this.pos = pos
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (count <= 1) return
        val r = 2.3f * density
        val gap = 15f * density
        val startX = width / 2f - (count - 1) * gap / 2f
        val cy = height / 2f
        for (i in 0 until count) {
            val near = 1f - min(1f, abs(pos - i))
            paint.color = Color.WHITE
            paint.alpha = (100 + 155 * near).toInt()
            canvas.drawCircle(startX + i * gap, cy, r + near * 0.5f * density, paint)
        }
    }
}
