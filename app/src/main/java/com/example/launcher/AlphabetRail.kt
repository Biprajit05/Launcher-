package com.example.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/** Thin vertical A-Z strip. Touch / drag on it reports the letter under the finger. */
class AlphabetRail @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val letters = listOf("#") + ('A'..'Z').map { it.toString() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
        color = ctx.getColor(R.color.hint)
    }
    private var active = -1
    private var normalColor = ctx.getColor(R.color.hint)
    private var activeColor = ctx.getColor(R.color.label)

    fun setColors(normal: Int, activeC: Int) {
        normalColor = normal
        activeColor = activeC
        invalidate()
    }

    /** Called with the letter ("#" or "A".."Z") and its centre Y in this view; done = finger lifted. */
    var onLetter: ((letter: String, centerY: Float, done: Boolean) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val step = h / letters.size
        paint.textSize = (step * 0.62f).coerceAtMost(9f * resources.displayMetrics.scaledDensity)
        for (i in letters.indices) {
            paint.color = if (i == active) activeColor else normalColor
            paint.isFakeBoldText = i == active
            val y = step * i + step / 2f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(letters[i], width / 2f, y, paint)
        }
    }

    private fun ctx() = context

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                report(e.y, false)
            }
            MotionEvent.ACTION_MOVE -> report(e.y, false)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val i = indexAt(e.y)
                active = -1
                invalidate()
                onLetter?.invoke(letters[i], centerOf(i), true)
            }
        }
        return true
    }

    private fun indexAt(y: Float): Int {
        val step = height.toFloat() / letters.size
        return (y / step).toInt().coerceIn(0, letters.size - 1)
    }

    private fun centerOf(i: Int): Float = height.toFloat() / letters.size * (i + 0.5f)

    private fun report(y: Float, done: Boolean) {
        val i = indexAt(y)
        if (i != active) {
            active = i
            invalidate()
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
        onLetter?.invoke(letters[i], centerOf(i), done)
    }
}
