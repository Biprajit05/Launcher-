package com.example.launcher

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/** One app on the home screen, with its cell. */
class HomeItem(val app: AppInfo, var col: Int, var row: Int)

/**
 * Home screen grid. Tap = open, long-press = pick up (drag to move, or release
 * without moving for the menu), long-press on empty space = settings.
 */
class HomeGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    private class Entry(val item: HomeItem, val view: View) {
        var visible = true
    }

    var onAppClick: ((AppInfo, View) -> Unit)? = null
    var onAppMenu: ((AppInfo, View) -> Unit)? = null
    var onEmptyLongPress: (() -> Unit)? = null
    var onChanged: ((List<HomeItem>) -> Unit)? = null

    /** Finger moved while an icon is being dragged (screen coordinates). */
    var onDragMove: ((Float, Float) -> Unit)? = null

    /** Icon dropped. Return true if something outside the grid (the dock) took it. */
    var onDropOutside: ((AppInfo, Float, Float) -> Boolean)? = null

    /** Drag finished (dropped or cancelled). */
    var onDragEnd: (() -> Unit)? = null

    private val entries = ArrayList<Entry>()
    private var cols = 4
    private var rows = 6
    private var cellW = 1
    private var cellH = 1
    private var scale = 1f
    private var labelScale = 1f
    private var shape = 0
    private var iconCap = Int.MAX_VALUE

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var touched: Entry? = null
    private var dragEntry: Entry? = null
    private var dragFar = false
    private var moved = false
    private var longFired = false
    private var grabDX = 0f
    private var grabDY = 0f
    private var rawX = 0f
    private var rawY = 0f
    private val longPress = Runnable { onLongPressFired() }

    val isDragging: Boolean get() = dragEntry != null

    // ---------- Public API ----------

    fun configure(columns: Int, rowCount: Int, iconScale: Float, iconShape: Int, labelSize: Float) {
        cols = max(1, columns)
        rows = max(1, rowCount)
        scale = iconScale
        labelScale = labelSize
        shape = iconShape
        requestLayout()
    }

    fun setItems(items: List<HomeItem>) {
        if (dragEntry != null) return
        removeAllViews()
        entries.clear()
        for (m in items) entries.add(makeEntry(m))
        requestLayout()
    }

    fun contains(app: AppInfo): Boolean = entries.any { it.item.app.component == app.component }

    /** Puts the app in the first free cell. Returns false if the screen is full. */
    fun addApp(app: AppInfo): Boolean {
        val occupied = HashSet<Int>()
        for (e in entries) {
            if (e.visible) occupied.add(e.item.row * cols + e.item.col)
        }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!occupied.contains(r * cols + c)) {
                    entries.add(makeEntry(HomeItem(app, c, r)))
                    requestLayout()
                    notifyChanged()
                    return true
                }
            }
        }
        return false
    }

    fun removeApp(app: AppInfo) {
        val e = entries.firstOrNull { it.item.app.component == app.component } ?: return
        entries.remove(e)
        removeView(e.view)
        requestLayout()
        notifyChanged()
    }

    private fun notifyChanged() {
        onChanged?.invoke(entries.map { it.item })
    }

    // ---------- Views ----------

    private fun makeEntry(m: HomeItem): Entry {
        val v = LayoutInflater.from(context).inflate(R.layout.item_app, this, false)
        val label = v.findViewById<TextView>(R.id.label)
        label.setTextColor(Color.WHITE)
        label.setShadowLayer(4f, 0f, 1f, Color.BLACK)
        val e = Entry(m, v)
        bindEntry(e)
        addView(v)
        return e
    }

    private fun bindEntry(e: Entry) {
        val icon = e.view.findViewById<ImageView>(R.id.icon)
        val label = e.view.findViewById<TextView>(R.id.label)
        IconStyle.bindIcon(icon, label, e.item.app, shape, scale, cols, labelScale, iconCap)
    }

    // ---------- Layout ----------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val d = resources.displayMetrics.density
        cellW = max(1, (w - paddingLeft - paddingRight) / cols)
        // Rows are fixed by the setting; the cells share the available height.
        cellH = max(1, (h - paddingTop - paddingBottom) / rows)
        // Icon must fit the cell: cell padding (16dp) + gap (4dp) + one label line.
        val labelPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f * labelScale, resources.displayMetrics)
        val cap = (cellH - 20f * d - labelPx * 1.3f).toInt().coerceAtLeast((24f * d).toInt())
        if (cap != iconCap) {
            iconCap = cap
            for (e in entries) bindEntry(e)
        }
        val cw = MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY)
        for (e in entries) e.view.measure(cw, ch)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        normalize()
        for (e in entries) {
            if (e.visible) {
                val x = cellLeft(e.item.col)
                val y = cellTop(e.item.row)
                e.view.layout(x, y, x + cellW, y + cellH)
            } else {
                e.view.layout(0, 0, 0, 0)
            }
        }
    }

    /** Makes sure every app sits on a valid, unique cell. */
    private fun normalize() {
        val occupied = HashSet<Int>()
        val pending = ArrayList<Entry>()
        for (e in entries) {
            val m = e.item
            if (m.col in 0 until cols && m.row in 0 until rows && occupied.add(m.row * cols + m.col)) {
                e.visible = true
            } else {
                pending.add(e)
            }
        }
        for (e in pending) {
            var placed = false
            for (r in 0 until rows) {
                if (placed) break
                for (c in 0 until cols) {
                    if (occupied.add(r * cols + c)) {
                        e.item.col = c
                        e.item.row = r
                        e.visible = true
                        placed = true
                        break
                    }
                }
            }
            if (!placed) e.visible = false
        }
    }

    private fun cellLeft(col: Int) = paddingLeft + col * cellW
    private fun cellTop(row: Int) = paddingTop + row * cellH

    private fun hitCol(x: Float): Int = if (x < paddingLeft) -1 else ((x - paddingLeft) / cellW).toInt()
    private fun hitRow(y: Float): Int = if (y < paddingTop) -1 else ((y - paddingTop) / cellH).toInt()

    private fun entryAt(col: Int, row: Int, except: Entry?): Entry? =
        entries.firstOrNull { it !== except && it.visible && it.item.col == col && it.item.row == row }

    // ---------- Touch ----------

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        rawX = ev.rawX
        rawY = ev.rawY
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                moved = false
                longFired = false
                dragFar = false
                val e = entryAt(hitCol(ev.x), hitRow(ev.y), null)
                touched = e
                if (e != null) press(e.view, true)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val d = dragEntry
                if (d != null) {
                    follow(d, ev.x, ev.y)
                } else if (!moved && (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop)) {
                    moved = true
                    removeCallbacks(longPress)
                    val t = touched
                    if (t != null) press(t.view, false)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val d = dragEntry
                if (d != null) {
                    finishDrag(d, true)
                } else {
                    val t = touched
                    if (t != null) {
                        press(t.view, false)
                        if (!moved && !longFired) {
                            onAppClick?.invoke(t.item.app, t.view.findViewById<View>(R.id.icon))
                        }
                    }
                }
                touched = null
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                val d = dragEntry
                if (d != null) {
                    finishDrag(d, false)
                } else {
                    val t = touched
                    if (t != null) press(t.view, false)
                }
                touched = null
            }
        }
        return true
    }

    private fun press(v: View, down: Boolean) {
        val s = if (down) 0.88f else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(if (down) 90L else 120L).start()
    }

    private fun onLongPressFired() {
        longFired = true
        val e = touched
        if (e == null) {
            onEmptyLongPress?.invoke()
            return
        }
        dragEntry = e
        dragFar = false
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        e.view.bringToFront()
        // Let the dragged icon draw outside the grid and above the dock / search bar.
        clipChildren = false
        (parent as? ViewGroup)?.clipChildren = false
        translationZ = 24f
        e.view.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120L).start()
        grabDX = downX - e.view.left
        grabDY = downY - e.view.top
    }

    private fun follow(e: Entry, x: Float, y: Float) {
        if (!dragFar && (abs(x - downX) > slop || abs(y - downY) > slop)) dragFar = true
        e.view.translationX = (x - grabDX) - e.view.left
        e.view.translationY = (y - grabDY) - e.view.top
        onDragMove?.invoke(rawX, rawY)
    }

    private fun finishDrag(e: Entry, drop: Boolean) {
        dragEntry = null
        val view = e.view
        translationZ = 0f
        onDragEnd?.invoke()

        if (!drop || !dragFar) {
            // Not moved (or cancelled): go back; a plain long-press shows the menu.
            view.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(120L).start()
            if (drop) onAppMenu?.invoke(e.item.app, view.findViewById<View>(R.id.icon))
            return
        }

        // Dropped on the dock? Then the dock took it: the icon just glides back home.
        if (onDropOutside?.invoke(e.item.app, rawX, rawY) == true) {
            view.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(160L).start()
            return
        }

        val oldCol = e.item.col
        val oldRow = e.item.row
        val vx = view.left + view.translationX
        val vy = view.top + view.translationY
        val cx = vx + cellW / 2f
        val cy = vy + cellH / 2f
        val tc = ((cx - paddingLeft) / cellW).toInt().coerceIn(0, cols - 1)
        val tr = ((cy - paddingTop) / cellH).toInt().coerceIn(0, rows - 1)

        val other = entryAt(tc, tr, e)
        e.item.col = tc
        e.item.row = tr
        if (other != null) {
            other.item.col = oldCol
            other.item.row = oldRow
            other.view.translationX = (cellLeft(tc) - cellLeft(oldCol)).toFloat()
            other.view.translationY = (cellTop(tr) - cellTop(oldRow)).toFloat()
            other.view.animate().translationX(0f).translationY(0f).setDuration(160L).start()
        }

        // The view will be laid out in its new cell; keep it visually where it is, then glide in.
        view.translationX = vx - cellLeft(tc)
        view.translationY = vy - cellTop(tr)
        view.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(160L).start()

        requestLayout()
        notifyChanged()
    }
}
