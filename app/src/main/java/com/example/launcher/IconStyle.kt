package com.example.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
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
    fun bindIcon(
        icon: ImageView,
        label: TextView,
        app: AppInfo,
        shape: Int,
        scale: Float,
        columns: Int,
        labelScale: Float = 1f,
        maxIconPx: Int = Int.MAX_VALUE
    ) {
        val dm = icon.resources.displayMetrics
        val d = dm.density
        val cap = min(dm.widthPixels / columns - (16f * d).toInt(), maxIconPx)
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
        label.textSize = 12f * labelScale
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

        val trimmed = trimBorder(content)

        // Cut the content out with an anti-aliased shape.
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        oc.drawPath(shapePath(shape, size.toFloat()), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        oc.drawBitmap(trimmed, 0f, 0f, paint)
        trimmed.recycle()
        return out
    }

    private fun drawLegacy(c: Canvas, icon: Drawable, size: Int) {
        val probe = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, size, size)
        icon.draw(Canvas(probe))
        // Bounding box of the visible (opaque) part of the icon.
        var l = size; var t = size; var r = -1; var b = -1
        for (y in 0 until size) for (x in 0 until size) {
            if (Color.alpha(probe.getPixel(x, y)) > 128) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        if (r < l || b < t) {
            c.drawBitmap(probe, 0f, 0f, null)
            probe.recycle()
            return
        }
        // Fill the whole shape with the icon's own edge colour, then draw the
        // visible part scaled up so it covers the shape (crop, no padding).
        val edge = probe.getPixel((l + 3).coerceAtMost(r), (t + b) / 2)
        c.drawColor(if (Color.alpha(edge) > 200) edge else Color.WHITE)
        val side = min(r - l + 1, b - t + 1)
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        val src = Rect(cx - side / 2, cy - side / 2, cx - side / 2 + side, cy - side / 2 + side)
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        c.drawBitmap(probe, src, Rect(0, 0, size, size), p)
        probe.recycle()
    }

    /**
     * If the icon is a picture sitting on a plain border (e.g. a white tile with
     * padding), cut the border off so the picture fills the whole shape.
     */
    private fun trimBorder(src: Bitmap): Bitmap {
        val n = src.width
        val m = 2
        val corners = intArrayOf(
            src.getPixel(m, m), src.getPixel(n - 1 - m, m),
            src.getPixel(m, n - 1 - m), src.getPixel(n - 1 - m, n - 1 - m)
        )
        val bg = corners[0]
        if (corners.any { Color.alpha(it) < 200 || diff(it, bg) > 10 }) return src

        var l = n; var t = n; var r = -1; var b = -1
        var content = 0
        val px = IntArray(n * n)
        src.getPixels(px, 0, n, 0, 0, n, n)
        for (y in 0 until n) for (x in 0 until n) {
            if (diff(px[y * n + x], bg) > 10) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        if (r < l || b < t) return src
        val w = r - l + 1
        val h = b - t + 1
        // Only zoom when it is a real picture block (not a small / round logo).
        if (w < n * 0.45f || h < n * 0.45f || (w > n * 0.96f && h > n * 0.96f)) return src
        for (y in t..b) for (x in l..r) if (diff(px[y * n + x], bg) > 10) content++
        if (content < 0.88f * w * h) return src

        val side = min(w, h) - 2
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        val crop = Rect(cx - side / 2, cy - side / 2, cx - side / 2 + side, cy - side / 2 + side)
        val out = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, crop, Rect(0, 0, n, n), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        src.recycle()
        return out
    }

    private fun diff(a: Int, b: Int): Int = max(
        abs(Color.red(a) - Color.red(b)),
        max(abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))
    )

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
