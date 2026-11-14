package com.example.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The home screen workspace: several pages side by side, each a grid of cells.
 * A cell holds an app, a folder, or (spanning several cells) a widget.
 *
 * Swipe sideways = change page. Long-press = pick up (drag to move, or release
 * without moving for the menu). Drop an app on another app = make a folder.
 * Hold a dragged item at the left/right edge = move to the next page.
 */
class HomeGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    private class Entry(val item: HomeItem, val view: View) {
        var wdp = -1
        var hdp = -1
    }

    private class Target(val page: Int, val col: Int, val row: Int, val other: Entry?, val near: Boolean)

    var onAppClick: ((AppInfo, View) -> Unit)? = null
    var onAppMenu: ((AppInfo, View) -> Unit)? = null
    var onFolderClick: ((HomeItem, View) -> Unit)? = null
    var onFolderMenu: ((HomeItem, View) -> Unit)? = null
    var onWidgetMenu: ((HomeItem, View) -> Unit)? = null
    var onEmptyLongPress: (() -> Unit)? = null
    var onChanged: ((List<HomeItem>, Int) -> Unit)? = null

    /** Finger moved while an app is being dragged (screen coordinates). */
    var onDragMove: ((Float, Float) -> Unit)? = null

    /** App dropped. Return true if something outside the grid (the dock) took it. */
    var onDropOutside: ((AppInfo, Float, Float) -> Boolean)? = null

    /** Drag finished (dropped or cancelled). */
    var onDragEnd: (() -> Unit)? = null

    /** (page count, scroll position in pages, e.g. 0.5 = half way to page 2) */
    var onPageScroll: ((Int, Float) -> Unit)? = null

    /** Creates the view of a widget (null if the widget no longer exists). */
    var widgetFactory: ((Int) -> View?)? = null
    var onWidgetGone: ((Int) -> Unit)? = null

    /** True while the current touch started on a widget (the widget then owns vertical swipes). */
    var touchOnWidget = false

    private val entries = ArrayList<Entry>()
    private var cols = 4
    private var rows = 6
    private var cellW = 1
    private var cellH = 1
    private var scale = 1f
    private var labelScale = 1f
    private var shape = 0
    private var iconCap = Int.MAX_VALUE

    var pageCount = 1
        private set
    var currentPage = 0
        private set
    private var pageAnim: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastDownTime = -1L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var rawX = 0f
    private var rawY = 0f
    private var touched: Entry? = null
    private var dragEntry: Entry? = null
    private var dragFar = false
    private var moved = false
    private var longFired = false
    private var grabDX = 0f
    private var grabDY = 0f
    private var paging = false
    private var pageStartScroll = 0
    private var pageDownX = 0f
    private var vt: VelocityTracker? = null
    private var hover: Entry? = null
    private var edgeDir = 0
    private val longPress = Runnable { onLongPressFired() }
    private val edgeRun = Runnable { onEdgeDwell() }

    val isDragging: Boolean get() = dragEntry != null
    val isPaging: Boolean get() = paging
    val cellWidthPx: Int get() = cellW
    val cellHeightPx: Int get() = cellH

    init {
        clipToPadding = false
    }

    // ---------- Public API ----------

    fun configure(columns: Int, rowCount: Int, iconScale: Float, iconShape: Int, labelSize: Float) {
        val c = max(1, columns)
        val r = max(1, rowCount)
        val changed = c != cols || r != rows || iconScale != scale || iconShape != shape || labelSize != labelScale
        cols = c
        rows = r
        scale = iconScale
        labelScale = labelSize
        shape = iconShape
        if (changed) for (e in entries) bindEntry(e)
        requestLayout()
    }

    fun setItems(items: List<HomeItem>, pages: Int) {
        if (dragEntry != null) return
        // Keep the live widget views so they don't flicker when the list is rebuilt.
        val reuse = HashMap<Int, View>()
        for (e in entries) {
            if (e.item.kind == HomeItem.WIDGET) reuse[e.item.widgetId] = e.view
        }
        removeAllViews()
        entries.clear()
        var gone = false
        for (m in items) {
            val e = makeEntry(m, reuse[m.widgetId])
            if (e == null) {
                if (m.kind == HomeItem.WIDGET) onWidgetGone?.invoke(m.widgetId)
                gone = true
            } else {
                entries.add(e)
            }
        }
        pageCount = max(1, pages)
        currentPage = currentPage.coerceIn(0, pageCount - 1)
        requestLayout()
        notifyPageScroll()
        if (gone) post { notifyChanged() }
    }

    /** True if the app is anywhere on the home screen, also inside a folder. */
    fun contains(app: AppInfo): Boolean = entries.any {
        val m = it.item
        m.app?.component == app.component || m.folder?.apps?.any { a -> a.component == app.component } == true
    }

    /** Puts the app in the first free cell (current page first). Returns false if everything is full. */
    fun addApp(app: AppInfo): Boolean = placeNew(HomeItem(HomeItem.APP, 0, 0, 0, app = app), true)

    fun addWidget(widgetId: Int, spanC: Int, spanR: Int): Boolean {
        val m = HomeItem(HomeItem.WIDGET, 0, 0, 0, spanC.coerceIn(1, cols), spanR.coerceIn(1, rows), widgetId = widgetId)
        return placeNew(m, true)
    }

    fun removeApp(app: AppInfo) {
        val e = entries.firstOrNull { it.item.kind == HomeItem.APP && it.item.app?.component == app.component } ?: return
        entries.remove(e)
        removeView(e.view)
        afterStructureChange()
    }

    fun removeWidget(item: HomeItem) {
        val e = entries.firstOrNull { it.item === item } ?: return
        entries.remove(e)
        removeView(e.view)
        afterStructureChange()
    }

    /** Folder was renamed / changed: redraw it and save. */
    fun refreshItem(item: HomeItem) {
        val e = entries.firstOrNull { it.item === item } ?: return
        bindEntry(e)
        notifyChanged()
    }

    fun removeFromFolder(item: HomeItem, app: AppInfo) {
        val f = item.folder ?: return
        val e = entries.firstOrNull { it.item === item } ?: return
        f.apps.removeAll { it.component == app.component }
        collapseFolder(e)
        placeNew(HomeItem(HomeItem.APP, 0, 0, 0, app = app), false)
        afterStructureChange()
    }

    fun ungroupFolder(item: HomeItem) {
        val f = item.folder ?: return
        val e = entries.firstOrNull { it.item === item } ?: return
        entries.remove(e)
        removeView(e.view)
        var first = true
        for (a in f.apps.toList()) {
            if (first) {
                first = false
                val m = HomeItem(HomeItem.APP, item.page, item.col, item.row, app = a)
                makeEntry(m)?.let { entries.add(it) }
            } else {
                placeNew(HomeItem(HomeItem.APP, 0, 0, 0, app = a), false)
            }
        }
        afterStructureChange()
    }

    /** Resizes a widget; false if there is no room for the new size. */
    fun resizeWidget(item: HomeItem, spanC: Int, spanR: Int): Boolean {
        val e = entries.firstOrNull { it.item === item } ?: return false
        val sc = spanC.coerceIn(1, cols)
        val sr = spanR.coerceIn(1, rows)
        val g = occupancy(item.page, e)
        if (regionFree(g, item.col, item.row, sc, sr)) {
            item.spanC = sc
            item.spanR = sr
        } else {
            val spot = findFree(item.page, sc, sr, e) ?: return false
            item.col = spot.first
            item.row = spot.second
            item.spanC = sc
            item.spanR = sr
        }
        e.wdp = -1
        requestLayout()
        notifyChanged()
        return true
    }

    fun goToPage(p: Int, animate: Boolean = true) {
        val t = p.coerceIn(0, max(0, pageCount - 1))
        pageAnim?.cancel()
        currentPage = t
        if (width <= 0) return
        val target = t * width
        if (!animate || scrollX == target) {
            scrollTo(target, 0)
            return
        }
        val anim = ValueAnimator.ofInt(scrollX, target)
        anim.duration = 260L
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener {
            scrollTo(it.animatedValue as Int, 0)
            val d = dragEntry
            if (d != null) follow(d, lastX, lastY)
        }
        pageAnim = anim
        anim.start()
    }

    // ---------- Placement helpers ----------

    private fun occupancy(page: Int, except: Entry?): BooleanArray {
        val g = BooleanArray(cols * rows)
        for (e in entries) {
            if (e === except) continue
            val m = e.item
            if (m.page != page) continue
            for (r in max(0, m.row) until min(rows, m.row + m.spanR)) {
                for (c in max(0, m.col) until min(cols, m.col + m.spanC)) {
                    g[r * cols + c] = true
                }
            }
        }
        return g
    }

    private fun regionFree(g: BooleanArray, c: Int, r: Int, sc: Int, sr: Int): Boolean {
        if (c < 0 || r < 0 || c + sc > cols || r + sr > rows) return false
        for (rr in r until r + sr) {
            for (cc in c until c + sc) {
                if (g[rr * cols + cc]) return false
            }
        }
        return true
    }

    private fun mark(g: BooleanArray, c: Int, r: Int, sc: Int, sr: Int) {
        for (rr in r until min(rows, r + sr)) {
            for (cc in c until min(cols, c + sc)) {
                g[rr * cols + cc] = true
            }
        }
    }

    private fun findFree(page: Int, sc: Int, sr: Int, except: Entry?): Pair<Int, Int>? {
        val g = occupancy(page, except)
        for (r in 0..rows - sr) {
            for (c in 0..cols - sc) {
                if (regionFree(g, c, r, sc, sr)) return Pair(c, r)
            }
        }
        return null
    }

    private fun placeNew(m: HomeItem, persist: Boolean): Boolean {
        val order = ArrayList<Int>()
        order.add(currentPage)
        for (p in 0 until pageCount) if (p != currentPage) order.add(p)
        var placed = false
        for (p in order) {
            val spot = findFree(p, m.spanC, m.spanR, null)
            if (spot != null) {
                m.page = p
                m.col = spot.first
                m.row = spot.second
                placed = true
                break
            }
        }
        if (!placed) {
            if (pageCount >= MAX_PAGES) return false
            pageCount++
            m.page = pageCount - 1
            m.col = 0
            m.row = 0
            notifyPageScroll()
        }
        val e = makeEntry(m) ?: return false
        entries.add(e)
        requestLayout()
        if (persist) notifyChanged()
        return true
    }

    /** A folder with fewer than two apps stops being a folder. */
    private fun collapseFolder(e: Entry) {
        val f = e.item.folder ?: return
        if (f.apps.size >= 2) {
            bindEntry(e)
            return
        }
        entries.remove(e)
        removeView(e.view)
        if (f.apps.size == 1) {
            val m = HomeItem(HomeItem.APP, e.item.page, e.item.col, e.item.row, app = f.apps[0])
            makeEntry(m)?.let { entries.add(it) }
        }
    }

    private fun afterStructureChange() {
        requestLayout()
        pruneEmptyPages()
        notifyChanged()
    }

    /** Removes pages that have nothing on them (the first page always stays). */
    private fun pruneEmptyPages() {
        var p = 0
        while (p < pageCount && pageCount > 1) {
            if (entries.none { it.item.page == p }) {
                for (e in entries) {
                    if (e.item.page > p) e.item.page -= 1
                }
                pageCount--
                if (currentPage > p) currentPage--
            } else {
                p++
            }
        }
        currentPage = currentPage.coerceIn(0, max(0, pageCount - 1))
        notifyPageScroll()
        post { settleIfNeeded() }
    }

    private fun notifyChanged() {
        onChanged?.invoke(entries.map { it.item }, pageCount)
    }

    private fun notifyPageScroll() {
        if (width > 0) onPageScroll?.invoke(pageCount, scrollX / width.toFloat())
        else onPageScroll?.invoke(pageCount, currentPage.toFloat())
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        notifyPageScroll()
    }

    // ---------- Views ----------

    private fun makeEntry(m: HomeItem, reuse: View? = null): Entry? {
        if (m.kind == HomeItem.WIDGET) {
            val v = reuse ?: widgetFactory?.invoke(m.widgetId) ?: return null
            val e = Entry(m, v)
            addView(v)
            return e
        }
        val v = LayoutInflater.from(context).inflate(R.layout.item_app, this, false)
        val label = v.findViewById<TextView>(R.id.label)
        label.setTextColor(Color.WHITE)
        label.setShadowLayer(4f, 0f, 1f, Color.BLACK)
        // Tight cell: icon + label centred, so big icons use the whole cell instead of being capped.
        val vp = (2 * density).toInt()
        v.setPadding(v.paddingLeft, vp, v.paddingRight, vp)
        (v as? LinearLayout)?.gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        val e = Entry(m, v)
        bindEntry(e)
        addView(v)
        return e
    }

    private fun bindEntry(e: Entry) {
        val m = e.item
        if (m.kind == HomeItem.WIDGET) return
        val icon = e.view.findViewById<ImageView>(R.id.icon)
        val label = e.view.findViewById<TextView>(R.id.label)
        val folder = m.folder
        val app = m.app
        if (m.kind == HomeItem.FOLDER && folder != null) {
            FolderIcon.bind(icon, label, folder, shape, scale, cols, labelScale, iconCap)
        } else if (app != null) {
            IconStyle.bindIcon(icon, label, app, shape, scale, cols, labelScale, iconCap)
        }
    }

    // ---------- Layout ----------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        cellW = max(1, (w - paddingLeft - paddingRight) / cols)
        // Rows are fixed by the setting; the cells share the available height.
        cellH = max(1, (h - paddingTop - paddingBottom) / rows)
        // Icon must fit the cell: cell padding + gap + one label line.
        val labelPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f * labelScale, resources.displayMetrics)
        val capH = cellH - 8f * density - labelPx * 1.3f
        val capW = cellW - 6f * density
        val cap = min(capH, capW).toInt().coerceAtLeast((24f * density).toInt())
        normalize()
        if (cap != iconCap) {
            iconCap = cap
            for (e in entries) bindEntry(e)
        }
        for (e in entries) {
            val m = e.item
            e.view.measure(
                MeasureSpec.makeMeasureSpec(cellW * m.spanC, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cellH * m.spanR, MeasureSpec.EXACTLY)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (e in entries) {
            val m = e.item
            val x = m.page * w + paddingLeft + m.col * cellW
            val y = paddingTop + m.row * cellH
            val vw = cellW * m.spanC
            val vh = cellH * m.spanR
            e.view.layout(x, y, x + vw, y + vh)
            if (m.kind == HomeItem.WIDGET) pushWidgetSize(e, vw, vh)
        }
        if (pageAnim?.isRunning != true && !paging && w > 0) {
            val target = currentPage.coerceIn(0, max(0, pageCount - 1)) * w
            if (scrollX != target) scrollTo(target, 0)
        }
    }

    private fun pushWidgetSize(e: Entry, wPx: Int, hPx: Int) {
        val v = e.view as? AppWidgetHostView ?: return
        val wdp = (wPx / density).toInt()
        val hdp = (hPx / density).toInt()
        if (wdp != e.wdp || hdp != e.hdp) {
            e.wdp = wdp
            e.hdp = hdp
            try {
                v.updateAppWidgetSize(null, wdp, hdp, wdp, hdp)
            } catch (ex: Exception) {
                // some widgets reject size updates; they still draw
            }
        }
    }

    /** Makes sure everything sits on a valid, free cell; what doesn't fit moves to another page. */
    private fun normalize() {
        if (entries.isEmpty()) return
        var changed = false
        val grids = ArrayList<BooleanArray>()
        fun gridFor(p: Int): BooleanArray {
            while (grids.size <= p) grids.add(BooleanArray(cols * rows))
            return grids[p]
        }
        val pending = ArrayList<Entry>()
        val ordered = entries.sortedBy { if (it.item.kind == HomeItem.WIDGET) 0 else 1 }
        for (e in ordered) {
            val m = e.item
            val sc = m.spanC.coerceIn(1, cols)
            val sr = m.spanR.coerceIn(1, rows)
            if (sc != m.spanC || sr != m.spanR) {
                m.spanC = sc
                m.spanR = sr
                changed = true
            }
            if (m.page in 0 until MAX_PAGES && regionFree(gridFor(m.page), m.col, m.row, sc, sr)) {
                mark(gridFor(m.page), m.col, m.row, sc, sr)
            } else {
                pending.add(e)
            }
        }
        for (e in pending) {
            val m = e.item
            var placed = false
            var p = 0
            while (!placed) {
                val g = gridFor(p)
                for (r in 0..rows - m.spanR) {
                    if (placed) break
                    for (c in 0..cols - m.spanC) {
                        if (regionFree(g, c, r, m.spanC, m.spanR)) {
                            m.page = p
                            m.col = c
                            m.row = r
                            mark(g, c, r, m.spanC, m.spanR)
                            placed = true
                            break
                        }
                    }
                }
                p++
            }
            changed = true
        }
        if (grids.size > pageCount) pageCount = grids.size
        if (changed) post { notifyChanged() }
    }

    // ---------- Touch ----------

    private fun entryAtContent(cx: Float, cy: Float): Entry? {
        for (i in entries.indices.reversed()) {
            val v = entries[i].view
            if (cx >= v.left && cx < v.right && cy >= v.top && cy < v.bottom) return entries[i]
        }
        return null
    }

    private fun beginTouch(ev: MotionEvent) {
        if (ev.downTime == lastDownTime) return
        lastDownTime = ev.downTime
        downX = ev.x
        downY = ev.y
        lastX = ev.x
        lastY = ev.y
        moved = false
        longFired = false
        dragFar = false
        paging = false
        vt?.recycle()
        vt = VelocityTracker.obtain()
        vt?.addMovement(ev)
        val e = entryAtContent(ev.x + scrollX, ev.y)
        touched = e
        touchOnWidget = e != null && e.item.kind == HomeItem.WIDGET
        if (e != null && e.item.kind != HomeItem.WIDGET) press(e.view, true)
        removeCallbacks(longPress)
        postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun endTouch() {
        vt?.recycle()
        vt = null
        touched = null
    }

    private fun releasePress() {
        val t = touched
        if (t != null && t.item.kind != HomeItem.WIDGET) press(t.view, false)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginTouch(ev)
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                if (dragEntry != null) return true
                if (!paging && !moved) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > slop || abs(dy) > slop) {
                        moved = true
                        removeCallbacks(longPress)
                        releasePress()
                        if (abs(dx) > abs(dy)) {
                            startPaging(ev)
                            return true
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragEntry != null) {
                    onTouchEvent(ev)
                    return true
                }
                // A widget owned this touch and it ended without us: clean up.
                removeCallbacks(longPress)
                releasePress()
                settleIfNeeded()
                endTouch()
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        rawX = ev.rawX
        rawY = ev.rawY
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginTouch(ev)
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val d = dragEntry
                if (d != null) {
                    follow(d, ev.x, ev.y)
                } else if (paging) {
                    val maxScroll = max(0, (pageCount - 1) * width)
                    val nx = (pageStartScroll - (ev.x - pageDownX)).toInt().coerceIn(0, maxScroll)
                    scrollTo(nx, 0)
                } else if (!moved) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dx) > slop || abs(dy) > slop) {
                        moved = true
                        removeCallbacks(longPress)
                        releasePress()
                        if (abs(dx) > abs(dy)) startPaging(ev)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                vt?.addMovement(ev)
                removeCallbacks(longPress)
                val d = dragEntry
                if (d != null) {
                    finishDrag(d, true)
                } else if (paging) {
                    endPaging()
                } else {
                    val t = touched
                    releasePress()
                    if (t != null && !moved && !longFired) handleTap(t)
                    settleIfNeeded()
                }
                endTouch()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                val d = dragEntry
                if (d != null) {
                    finishDrag(d, false)
                } else if (paging) {
                    endPaging()
                } else {
                    releasePress()
                    settleIfNeeded()
                }
                endTouch()
            }
        }
        return true
    }

    private fun handleTap(t: Entry) {
        val icon = t.view.findViewById<View>(R.id.icon) ?: t.view
        val app = t.item.app
        when (t.item.kind) {
            HomeItem.APP -> if (app != null) onAppClick?.invoke(app, icon)
            HomeItem.FOLDER -> onFolderClick?.invoke(t.item, icon)
            else -> {}
        }
    }

    private fun press(v: View, down: Boolean) {
        val s = if (down) 0.88f else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(if (down) 90L else 120L).start()
    }

    // ---------- Paging ----------

    private fun startPaging(ev: MotionEvent) {
        paging = true
        pageAnim?.cancel()
        pageStartScroll = scrollX
        pageDownX = ev.x
        removeCallbacks(longPress)
        releasePress()
    }

    private fun endPaging() {
        paging = false
        val v = vt
        v?.computeCurrentVelocity(1000)
        val vx = v?.xVelocity ?: 0f
        val w = max(1, width)
        val base = (pageStartScroll / w.toFloat()).roundToInt()
        val target = when {
            vx < -700f -> base + 1
            vx > 700f -> base - 1
            else -> (scrollX / w.toFloat()).roundToInt()
        }
        goToPage(target)
    }

    /** If the pages are stuck half way between two (e.g. an animation was stopped), glide to the nearest. */
    private fun settleIfNeeded() {
        if (width <= 0 || dragEntry != null || paging) return
        val nearest = (scrollX / width.toFloat()).roundToInt().coerceIn(0, max(0, pageCount - 1))
        if (scrollX != nearest * width && pageAnim?.isRunning != true) goToPage(nearest)
    }

    // ---------- Drag ----------

    private fun onLongPressFired() {
        longFired = true
        val e = touched
        if (e == null) {
            if (!moved && !paging) onEmptyLongPress?.invoke()
            return
        }
        if (moved || paging) return
        dragEntry = e
        dragFar = false
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        e.view.bringToFront()
        // Let the dragged item draw outside the grid and above the dock / search bar.
        clipChildren = false
        (parent as? ViewGroup)?.clipChildren = false
        translationZ = 24f
        val s = if (e.item.kind == HomeItem.WIDGET) 1.04f else 1.15f
        e.view.animate().scaleX(s).scaleY(s).setDuration(120L).start()
        grabDX = (downX + scrollX) - e.view.left
        grabDY = downY - e.view.top
    }

    private fun follow(e: Entry, x: Float, y: Float) {
        lastX = x
        lastY = y
        if (!dragFar && (abs(x - downX) > slop || abs(y - downY) > slop)) dragFar = true
        e.view.translationX = (x + scrollX - grabDX) - e.view.left
        e.view.translationY = (y - grabDY) - e.view.top
        val app = e.item.app
        if (e.item.kind == HomeItem.APP && app != null) onDragMove?.invoke(rawX, rawY)
        updateHover(e)
        updateEdge(x)
    }

    private fun updateEdge(x: Float) {
        val edge = 32f * density
        val dir = when {
            x < edge -> -1
            x > width - edge -> 1
            else -> 0
        }
        if (dir == edgeDir) return
        edgeDir = dir
        removeCallbacks(edgeRun)
        if (dir != 0) postDelayed(edgeRun, 550L)
    }

    private fun onEdgeDwell() {
        val d = dragEntry ?: return
        if (edgeDir == 0) return
        val next = currentPage + edgeDir
        if (next >= 0 && next < pageCount) {
            goToPage(next)
        } else if (edgeDir > 0 && pageCount < MAX_PAGES && entries.any { it !== d && it.item.page == currentPage }) {
            pageCount++
            notifyPageScroll()
            goToPage(pageCount - 1)
        } else {
            return
        }
        postDelayed(edgeRun, 750L)
    }

    private fun entryCovering(page: Int, col: Int, row: Int, except: Entry?): Entry? =
        entries.firstOrNull {
            val m = it.item
            it !== except && m.page == page &&
                col >= m.col && col < m.col + m.spanC &&
                row >= m.row && row < m.row + m.spanR
        }

    /** Where the dragged item would land if it were dropped now. */
    private fun targetFor(e: Entry): Target {
        val v = e.view
        val m = e.item
        val vx = v.left + v.translationX
        val vy = v.top + v.translationY
        val cx = vx + v.width / 2f
        val cy = vy + v.height / 2f
        val tp = floor(cx / max(1, width)).toInt().coerceIn(0, max(0, pageCount - 1))
        val localX = vx - tp * width - paddingLeft
        val localY = vy - paddingTop
        val tc = floor((localX + cellW / 2f) / cellW).toInt().coerceIn(0, max(0, cols - m.spanC))
        val tr = floor((localY + cellH / 2f) / cellH).toInt().coerceIn(0, max(0, rows - m.spanR))
        val other = entryCovering(tp, tc, tr, e)
        var near = false
        if (other != null) {
            val ox = tp * width + paddingLeft + other.item.col * cellW + cellW / 2f
            val oy = paddingTop + other.item.row * cellH + cellH / 2f
            near = hypot(ox - cx, oy - cy) < min(cellW, cellH) * 0.32f
        }
        return Target(tp, tc, tr, other, near)
    }

    private fun mergeable(dragged: Entry, other: Entry): Boolean =
        dragged.item.kind == HomeItem.APP &&
            (other.item.kind == HomeItem.APP || other.item.kind == HomeItem.FOLDER)

    private fun updateHover(e: Entry) {
        val t = targetFor(e)
        val o = t.other
        val h = if (o != null && t.near && mergeable(e, o)) o else null
        if (h === hover) return
        hover?.view?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120L)?.start()
        hover = h
        h?.view?.animate()?.scaleX(1.18f)?.scaleY(1.18f)?.setDuration(120L)?.start()
    }

    private fun clearHover() {
        hover?.view?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(120L)?.start()
        hover = null
    }

    private fun snapBack(e: Entry) {
        e.view.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(160L).start()
        post { settleIfNeeded() }
    }

    /** The item now belongs to a new cell: keep it where the finger left it, then glide in. */
    private fun glideInto(e: Entry, fromX: Float, fromY: Float) {
        val m = e.item
        val nl = m.page * width + paddingLeft + m.col * cellW
        val nt = paddingTop + m.row * cellH
        e.view.translationX = fromX - nl
        e.view.translationY = fromY - nt
        e.view.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(160L).start()
    }

    private fun finishDrag(e: Entry, drop: Boolean) {
        dragEntry = null
        removeCallbacks(edgeRun)
        edgeDir = 0
        translationZ = 0f
        clearHover()
        onDragEnd?.invoke()
        val view = e.view
        val m = e.item
        val icon = view.findViewById<View>(R.id.icon) ?: view

        if (!drop || !dragFar) {
            // Not moved (or cancelled): go back; a plain long-press shows the menu.
            view.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(120L).start()
            if (drop) {
                val app = m.app
                when (m.kind) {
                    HomeItem.APP -> if (app != null) onAppMenu?.invoke(app, icon)
                    HomeItem.FOLDER -> onFolderMenu?.invoke(m, icon)
                    else -> onWidgetMenu?.invoke(m, view)
                }
            }
            post { settleIfNeeded() }
            return
        }

        // Dropped on the dock? Then the dock took it: the app just glides back home.
        val dropApp = m.app
        if (m.kind == HomeItem.APP && dropApp != null && onDropOutside?.invoke(dropApp, rawX, rawY) == true) {
            snapBack(e)
            return
        }

        val t = targetFor(e)
        val other = t.other
        val vx = view.left + view.translationX
        val vy = view.top + view.translationY
        val oldPage = m.page
        val oldCol = m.col
        val oldRow = m.row

        if (m.kind == HomeItem.WIDGET) {
            val g = occupancy(t.page, e)
            if (regionFree(g, t.col, t.row, m.spanC, m.spanR)) {
                m.page = t.page
                m.col = t.col
                m.row = t.row
                glideInto(e, vx, vy)
                afterStructureChange()
            } else {
                snapBack(e)
            }
            return
        }

        if (other == null) {
            m.page = t.page
            m.col = t.col
            m.row = t.row
            glideInto(e, vx, vy)
            afterStructureChange()
            return
        }

        val otherItem = other.item
        if (otherItem.kind == HomeItem.WIDGET) {
            snapBack(e)
            return
        }

        val draggedApp = m.app
        val otherApp = otherItem.app
        if (m.kind == HomeItem.APP && draggedApp != null && t.near && otherItem.kind == HomeItem.APP && otherApp != null) {
            // App on app: make a folder where the target was.
            val folder = FolderData("Folder", arrayListOf(otherApp, draggedApp))
            val fm = HomeItem(HomeItem.FOLDER, otherItem.page, otherItem.col, otherItem.row, folder = folder)
            entries.remove(e)
            removeView(view)
            entries.remove(other)
            removeView(other.view)
            val fe = makeEntry(fm)
            if (fe != null) {
                entries.add(fe)
                fe.view.alpha = 0f
                fe.view.animate().alpha(1f).setDuration(180L).start()
            }
            afterStructureChange()
            return
        }
        val targetFolder = otherItem.folder
        if (m.kind == HomeItem.APP && draggedApp != null && t.near && otherItem.kind == HomeItem.FOLDER && targetFolder != null) {
            // App on folder: it goes inside.
            if (targetFolder.apps.none { it.component == draggedApp.component }) targetFolder.apps.add(draggedApp)
            entries.remove(e)
            removeView(view)
            bindEntry(other)
            afterStructureChange()
            return
        }

        // Otherwise swap the two.
        val ox = other.view.left.toFloat()
        val oy = other.view.top.toFloat()
        m.page = t.page
        m.col = t.col
        m.row = t.row
        otherItem.page = oldPage
        otherItem.col = oldCol
        otherItem.row = oldRow
        glideInto(e, vx, vy)
        glideInto(other, ox, oy)
        afterStructureChange()
    }

    companion object {
        const val MAX_PAGES = 9
    }
}
