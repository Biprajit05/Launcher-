package com.example.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.min

/** Applies the chosen icon shape + size to an icon / label pair. */
object IconStyle {

    private const val RENDER_DP = 64

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun clear() {
        cache.evictAll()
    }

    /**
     * shape: 0 original, 1 circle, 2 squircle, 3 rounded square, 4 square, 5 teardrop
     * scale: 1.0 = normal size
     * columns: used so big icons never overlap their neighbours
     */
    fun bindIcon(icon: ImageView, label: TextView, app: AppInfo, shape: Int, scale: Float, columns: Int) {
        val dm = icon.resources.displayMetrics
        val d = dm.density
        val cap = dm.widthPixels / columns - (16f * d).toInt()
        val want = (52f * d * scale).toInt()
        val px = min(want, cap).coerceAtLeast((24f * d).toInt())
        val lp = icon.layoutParams
        if (lp.width != px || lp.height != px) {
            lp.width = px
            lp.height = px
            icon.layoutParams = lp
        }
        icon.setImageDrawable(drawableFor(icon.context, app, shape))
        label.text = app.label
        label.textSize = 12f * scale.coerceIn(0.8f, 1.25f)
    }

    private fun drawableFor(ctx: Context, app: AppInfo, shape: Int): Drawable {
        if (shape == 0) return app.icon.constantState?.newDrawable() ?: app.icon
        val key = app.component.flattenToShortString() + "#" + shape
        val cached: Bitmap? = cache.get(key)
        val bmp: Bitmap = cached ?: render(ctx, app.icon, shape).also { cache.put(key, it) }
        return BitmapDrawable(ctx.resources, bmp)
    }

    private fun render(ctx: Context, src: Drawable, shape: Int): Bitmap {
        val size = (RENDER_DP * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        val content = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cc = Canvas(content)
        val icon: Drawable = src.constantState?.newDrawable()?.mutate() ?: src

        if (icon is AdaptiveIconDrawable) {
            // Adaptive layers are 108dp; the visible window is the central 72dp.
            val inset = size / 4
            val bg: Drawable? = icon.background
            val fg: Drawable? = icon.foreground
            if (bg != null) {
                bg.setBounds(-inset, -inset, size + inset, size + inset)
                bg.draw(cc)
            }
            if (fg != null) {
                fg.setBounds(-inset, -inset, size + inset, size + inset)
                fg.draw(cc)
            }
        } else {
            drawLegacy(cc, icon, size)
        }

        // Cut the content out with an anti-aliased shape.
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        oc.drawPath(shapePath(shape, size.toFloat()), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        oc.drawBitmap(content, 0f, 0f, paint)
        content.recycle()
        return out
    }

    private fun drawLegacy(c: Canvas, icon: Drawable, size: Int) {
        val probe = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(probe))
        val m = 3
        val full = Color.alpha(probe.getPixel(m, m)) > 240 &&
            Color.alpha(probe.getPixel(size - 1 - m, m)) > 240 &&
            Color.alpha(probe.getPixel(m, size - 1 - m)) > 240 &&
            Color.alpha(probe.getPixel(size - 1 - m, size - 1 - m)) > 240
        if (full) {
            // Square icon: let it fill the whole shape.
            c.drawBitmap(probe, 0f, 0f, null)
        } else {
            // Icon with its own outline: put it on a white tile.
            c.drawColor(Color.WHITE)
            val pad = (size * 0.14f).toInt()
            icon.setBounds(pad, pad, size - pad, size - pad)
            icon.draw(c)
        }
        probe.recycle()
    }

    private fun shapePath(shape: Int, s: Float): Path {
        val p = Path()
        if (shape == 1) {
            p.addCircle(s / 2f, s / 2f, s / 2f, Path.Direction.CW)
        } else if (shape == 2) {
            // Squircle (superellipse)
            val a = s / 2.0
            val steps = 120
            for (i in 0..steps) {
                val t = 2.0 * Math.PI * i / steps
                val ct = Math.cos(t)
                val st = Math.sin(t)
                val x = a + a * Math.signum(ct) * Math.pow(Math.abs(ct), 0.5)
                val y = a + a * Math.signum(st) * Math.pow(Math.abs(st), 0.5)
                if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
            }
            p.close()
        } else if (shape == 4) {
            p.addRoundRect(RectF(0f, 0f, s, s), s * 0.06f, s * 0.06f, Path.Direction.CW)
        } else if (shape == 5) {
            // Teardrop: round everywhere except the top-right corner
            val r = s / 2f
            val k = s * 0.08f
            val radii = floatArrayOf(r, r, k, k, r, r, r, r)
            p.addRoundRect(RectF(0f, 0f, s, s), radii, Path.Direction.CW)
        } else {
            // 3 = rounded square
            p.addRoundRect(RectF(0f, 0f, s, s), s * 0.24f, s * 0.24f, Path.Direction.CW)
        }
        return p
    }
}
