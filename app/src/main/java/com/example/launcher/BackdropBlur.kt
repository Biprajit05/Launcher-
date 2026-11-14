package com.example.launcher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Blurs the wallpaper once (small bitmap) so frosted-glass shapes (folders, search pill)
 * can show the part of it that sits behind them. One percentage (0..100) controls all of it.
 */
object BackdropBlur {

    /** The blurred bitmap is this many times smaller than the screen. */
    const val SCALE = 8

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var source: Bitmap? = null
    private var blurred: Bitmap? = null
    private var blurredPercent = -1
    private var loading = false
    private var loadedWallpaperId = -2

    /** Current blur strength 0..100 (set from Settings). */
    var percent = 50
        private set

    /** Called on the main thread whenever the blurred bitmap changed. */
    var onChanged: (() -> Unit)? = null

    /** The blurred wallpaper, or null if the wallpaper can't be read (then a plain frosted tint is drawn). */
    fun bitmap(): Bitmap? = blurred ?: source

    val hasWallpaper: Boolean get() = source != null

    /** Settings multiplier for the blur of the open folder: 0 = off, 1 = default (at 50%), 2 = double. */
    fun factor(): Float = percent / 50f

    private fun wallpaperId(ctx: Context): Int = try {
        WallpaperManager.getInstance(ctx).getWallpaperId(WallpaperManager.FLAG_SYSTEM)
    } catch (e: Throwable) {
        -1
    }

    /** (Re)loads the wallpaper if needed and applies [newPercent]. Safe to call often. */
    fun update(ctx: Context, newPercent: Int) {
        val app = ctx.applicationContext
        val p = newPercent.coerceIn(0, 100)
        val wid = wallpaperId(app)
        val needLoad = source == null || wid != loadedWallpaperId
        if (!needLoad && p == blurredPercent && p == percent) return
        percent = p
        if (loading) return
        loading = true
        worker.execute {
            try {
                var src = source
                if (needLoad) {
                    src = loadWallpaper(app)
                    loadedWallpaperId = wid
                }
                val want = percent
                val out = if (src == null) null else blur(src, want)
                main.post {
                    source = src
                    blurred = out
                    blurredPercent = want
                    loading = false
                    onChanged?.invoke()
                    // The slider moved while we were working: run once more.
                    if (want != percent) update(app, percent)
                }
            } catch (e: Throwable) {
                main.post { loading = false }
            }
        }
    }

    private fun loadWallpaper(ctx: Context): Bitmap? {
        val dm = ctx.resources.displayMetrics
        val d = try {
            WallpaperManager.getInstance(ctx).drawable
        } catch (e: Throwable) {
            null
        } ?: return null
        val bw = d.intrinsicWidth
        val bh = d.intrinsicHeight
        if (bw <= 0 || bh <= 0) return null
        val tw = max(1, dm.widthPixels / SCALE)
        val th = max(1, dm.heightPixels / SCALE)
        val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Same crop the system uses for a still wallpaper: fill the screen, centred.
        val s = max(tw / bw.toFloat(), th / bh.toFloat())
        val dw = (bw * s).roundToInt()
        val dh = (bh * s).roundToInt()
        val l = (tw - dw) / 2
        val t = (th - dh) / 2
        d.setBounds(l, t, l + dw, t + dh)
        d.draw(c)
        return bmp
    }

    private fun blur(src: Bitmap, percent: Int): Bitmap {
        if (percent <= 0) return src
        val r = (1 + percent * 0.07f).roundToInt()
        val w = src.width
        val h = src.height
        var a = IntArray(w * h)
        var b = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        repeat(3) {
            pass(a, b, w, h, r, true)
            pass(b, a, w, h, r, false)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    /** One box-blur pass over every row (horizontal) or column (vertical). */
    private fun pass(inp: IntArray, out: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val step = if (horizontal) 1 else w
        val div = 2 * r + 1
        for (l in 0 until lines) {
            val base = if (horizontal) l * w else l
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = inp[base + i.coerceIn(0, len - 1) * step]
                sa += p ushr 24
                sr += (p shr 16) and 255
                sg += (p shr 8) and 255
                sb += p and 255
            }
            for (i in 0 until len) {
                out[base + i * step] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val add = inp[base + min(len - 1, i + r + 1) * step]
                val sub = inp[base + max(0, i - r) * step]
                sa += (add ushr 24) - (sub ushr 24)
                sr += ((add shr 16) and 255) - ((sub shr 16) and 255)
                sg += ((add shr 8) and 255) - ((sub shr 8) and 255)
                sb += (add and 255) - (sub and 255)
            }
        }
    }
}

/**
 * Frosted glass: the blurred wallpaper that lies behind [view], clipped to a rounded shape,
 * plus a light tint. [radiusFrac] is the corner radius as a fraction of the shorter side
 * (0.5 = pill / circle).
 */
class FrostDrawable(
    private val view: View,
    private val radiusFrac: Float,
    private val tint: Int,
    private val tintNoWallpaper: Int
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rectF = RectF()
    private val src = Rect()
    private val loc = IntArray(2)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val rad = min(b.width(), b.height()) * radiusFrac
        rectF.set(b)
        path.reset()
        path.addRoundRect(rectF, rad, rad, Path.Direction.CW)
        val bmp = BackdropBlur.bitmap()
        val save = canvas.save()
        canvas.clipPath(path)
        if (bmp != null && BackdropBlur.percent >= 0) {
            view.getLocationOnScreen(loc)
            val s = BackdropBlur.SCALE.toFloat()
            src.set(
                ((loc[0] + b.left) / s).toInt(),
                ((loc[1] + b.top) / s).toInt(),
                ((loc[0] + b.right) / s).roundToInt(),
                ((loc[1] + b.bottom) / s).roundToInt()
            )
            if (src.width() < 1) src.right = src.left + 1
            if (src.height() < 1) src.bottom = src.top + 1
            canvas.drawBitmap(bmp, src, b, paint)
            tintPaint.color = tint
        } else {
            tintPaint.color = tintNoWallpaper
        }
        canvas.drawPaint(tintPaint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
