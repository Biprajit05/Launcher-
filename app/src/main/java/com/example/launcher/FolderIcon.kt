package com.example.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.min

/** Draws a folder: a translucent tile with a mini preview of the apps inside. */
object FolderIcon {

    fun bind(
        icon: ImageView,
        label: TextView,
        folder: FolderData,
        shape: Int,
        scale: Float,
        columns: Int,
        labelScale: Float,
        maxIconPx: Int
    ) {
        val dm = icon.resources.displayMetrics
        val d = dm.density
        val cap = if (maxIconPx != Int.MAX_VALUE) maxIconPx else dm.widthPixels / columns - (16f * d).toInt()
        val want = (52f * d * scale).toInt()
        val px = min(want, cap).coerceAtLeast((24f * d).toInt())
        val lp = icon.layoutParams
        if (lp.width != px || lp.height != px) {
            lp.width = px
            lp.height = px
            icon.layoutParams = lp
        }
        icon.setImageDrawable(BitmapDrawable(icon.resources, render(icon.context, folder, px, shape)))
        label.text = folder.name
        label.textSize = 12f * labelScale
    }

    fun render(ctx: Context, folder: FolderData, size: Int, shape: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.argb(0x73, 0xFF, 0xFF, 0xFF)
        val frac = when (shape) {
            1 -> 0.5f
            2 -> 0.34f
            4 -> 0.06f
            5 -> 0.40f
            else -> 0.22f
        }
        val radius = size * frac
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)

        val apps = folder.apps
        val n = if (apps.size > 4) 3 else 2
        val pad = size * 0.14f
        val gap = size * 0.05f
        val cell = (size - 2f * pad - gap * (n - 1)) / n
        val count = min(apps.size, n * n)
        for (k in 0 until count) {
            val rr = k / n
            val cc = k % n
            val x = pad + cc * (cell + gap)
            val y = pad + rr * (cell + gap)
            val dr = IconStyle.styledDrawable(ctx, apps[k], shape)
            dr.setBounds(x.toInt(), y.toInt(), (x + cell).toInt(), (y + cell).toInt())
            dr.draw(c)
        }
        return bmp
    }
}
