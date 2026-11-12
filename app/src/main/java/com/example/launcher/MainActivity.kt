package com.example.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var root: View
    private lateinit var home: View
    private lateinit var drawer: View
    private lateinit var homeGrid: RecyclerView
    private lateinit var dock: RecyclerView
    private lateinit var drawerList: RecyclerView
    private lateinit var search: EditText
    private lateinit var homeAdapter: AppAdapter
    private lateinit var dockAdapter: AppAdapter
    private lateinit var drawerAdapter: AppAdapter

    private var allApps: List<AppInfo> = emptyList()
    private var dockApps: List<AppInfo> = emptyList()

    private var drawerOpen = false
    private var drawerAnim: ValueAnimator? = null
    private var tracking = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var dragStartY = 0f
    private var baseTy = 0f
    private var vt: VelocityTracker? = null
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val executor = Executors.newSingleThreadExecutor()

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        root = findViewById(R.id.root)
        home = findViewById(R.id.home)
        drawer = findViewById(R.id.drawer)
        homeGrid = findViewById(R.id.homeGrid)
        dock = findViewById(R.id.dock)
        drawerList = findViewById(R.id.drawerList)
        search = findViewById(R.id.search)

        homeAdapter = AppAdapter(true, ::launch, ::showAppMenu)
        dockAdapter = AppAdapter(true, ::launch, ::showAppMenu)
        drawerAdapter = AppAdapter(false, ::launch, ::showAppMenu)

        homeGrid.layoutManager = object : GridLayoutManager(this, prefs.columns) {
            override fun canScrollVertically() = false
        }
        dock.layoutManager = object : GridLayoutManager(this, 4) {
            override fun canScrollVertically() = false
        }
        drawerList.layoutManager = GridLayoutManager(this, prefs.columns)
        homeGrid.adapter = homeAdapter
        dock.adapter = dockAdapter
        drawerList.adapter = drawerAdapter
        homeGrid.itemAnimator = null
        drawerList.itemAnimator = null

        search.doAfterTextChanged { applyFilter(it?.toString().orEmpty()) }

        // Long-press on empty home space -> Settings
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (!drawerOpen && homeGrid.findChildViewUnder(e.x, e.y) == null) showHomeMenu()
            }
        })
        @Suppress("ClickableViewAccessibility")
        homeGrid.setOnTouchListener { _, e -> detector.onTouchEvent(e); false }

        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        })
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(pkgReceiver)
        executor.shutdown()
    }

    override fun onResume() {
        super.onResume()
        if (allApps.isNotEmpty()) bind() // pick up column changes from Settings
        if (drawerAnim?.isRunning != true) {
            val s = if (drawerOpen) 0.94f else 1f
            val a = if (drawerOpen) 0.4f else 1f
            animateLayer(home, s, a, prefs.speed.toLong())
            animateLayer(drawer, 1f, 1f, prefs.speed.toLong())
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (drawerOpen) animateDrawer(false) // Home button closes the drawer
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Never leave the launcher; only close the drawer.
        if (drawerOpen) animateDrawer(false)
    }

    // ---------- Data ----------

    private fun reload() {
        val ctx = applicationContext
        executor.execute {
            val apps = AppRepository.loadApps(ctx)
            val dockList = AppRepository.pickDock(ctx, apps)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                allApps = apps
                dockApps = dockList
                bind()
            }
        }
    }

    private fun bind() {
        val cols = prefs.columns
        (homeGrid.layoutManager as GridLayoutManager).spanCount = cols
        (drawerList.layoutManager as GridLayoutManager).spanCount = cols
        dockAdapter.submit(dockApps)
        val dockPkgs = dockApps.map { it.packageName }.toSet()
        homeAdapter.submit(allApps.filter { it.packageName !in dockPkgs }.take(cols * 5))
        applyFilter(search.text.toString())
    }

    private fun applyFilter(q: String) {
        val t = q.trim()
        drawerAdapter.submit(if (t.isEmpty()) allApps else allApps.filter { it.label.contains(t, true) })
    }

    // ---------- Launch ----------

    private fun launch(app: AppInfo, iconView: View) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent, buildOptions(iconView))
            // shrink + dim whichever layer the user tapped in
            animateLayer(if (drawerOpen) drawer else home, 0.92f, 0.5f, prefs.speed.toLong())
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildOptions(iconView: View): Bundle {
        val d = prefs.speed // 250 / 450 / 800 -> matching anim resources
        val opts = when (prefs.style) {
            1 -> ActivityOptions.makeCustomAnimation(this, anim("fade_in_$d"), anim("fade_out_$d"))
            2 -> ActivityOptions.makeCustomAnimation(this, anim("slide_up_$d"), anim("hold_$d"))
            // Expands from the exact icon bounds (its duration is set by the system).
            else -> ActivityOptions.makeScaleUpAnimation(iconView, 0, 0, iconView.width, iconView.height)
        }
        return opts.toBundle()
    }

    private fun anim(name: String) = resources.getIdentifier(name, "anim", packageName)

    private fun animateLayer(v: View, scale: Float, alpha: Float, duration: Long) {
        v.animate().cancel()
        v.animate().scaleX(scale).scaleY(scale).alpha(alpha)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .withLayer() // hardware layer only while animating
            .start()
    }

    // ---------- Menus ----------

    private fun showAppMenu(app: AppInfo, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.app_info)
            menu.add(0, 2, 1, R.string.uninstall)
            setOnMenuItemClickListener {
                val uri = Uri.fromParts("package", app.packageName, null)
                try {
                    startActivity(
                        if (it.itemId == 1) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
                        else Intent(Intent.ACTION_DELETE, uri)
                    )
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Not available", Toast.LENGTH_SHORT).show()
                }
                true
            }
            show()
        }
    }

    private fun showHomeMenu() {
        PopupMenu(this, homeGrid, Gravity.CENTER).apply {
            menu.add(0, 1, 0, R.string.settings)
            setOnMenuItemClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java)); true
            }
            show()
        }
    }

    // ---------- Drawer (drag + animate) ----------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val a = ev.actionMasked
        if (a == MotionEvent.ACTION_DOWN) {
            vt?.recycle(); vt = VelocityTracker.obtain()
            downX = ev.x; downY = ev.y
            tracking = true; dragging = false
        }
        vt?.addMovement(ev)

        if (dragging) {
            when (a) {
                MotionEvent.ACTION_MOVE -> setTy(baseTy + ev.y - dragStartY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
            }
            return true
        }

        if (tracking && a == MotionEvent.ACTION_MOVE) {
            val dx = ev.x - downX
            val dy = ev.y - downY
            if (abs(dy) > slop && abs(dy) > abs(dx)) {
                val canOpen = !drawerOpen && dy < 0
                val canClose = drawerOpen && dy > 0 && !drawerList.canScrollVertically(-1)
                if (canOpen || canClose) {
                    startDrag(ev)
                    return true
                }
            }
        }

        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            tracking = false; vt?.recycle(); vt = null
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun startDrag(ev: MotionEvent) {
        drawerAnim?.cancel()
        home.animate().cancel()
        if (drawer.visibility != View.VISIBLE) {
            drawer.visibility = View.VISIBLE
            drawer.translationY = root.height.toFloat()
        }
        baseTy = drawer.translationY
        dragStartY = ev.y
        dragging = true
        layers(true)
        // cancel whatever child (icon / list) currently owns the touch
        val c = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(c)
        c.recycle()
        hideKeyboard()
    }

    private fun endDrag() {
        vt?.computeCurrentVelocity(1000)
        val vy = vt?.yVelocity ?: 0f
        vt?.recycle(); vt = null
        tracking = false; dragging = false
        val h = root.height.toFloat()
        val open = when {
            vy < -800f -> true
            vy > 800f -> false
            else -> drawer.translationY < h / 2f
        }
        animateDrawer(open)
    }

    /** Drawer position and home scale/alpha (1 -> 0.94 / 0.4) are driven from one value. */
    private fun setTy(ty: Float) {
        val h = root.height.toFloat().coerceAtLeast(1f)
        val t = ty.coerceIn(0f, h)
        drawer.translationY = t
        val p = 1f - t / h
        val s = 1f - 0.06f * p
        home.scaleX = s; home.scaleY = s
        home.alpha = 1f - 0.6f * p
    }

    private fun layers(on: Boolean) {
        val t = if (on) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        home.setLayerType(t, null)
        drawer.setLayerType(t, null)
    }

    private fun animateDrawer(open: Boolean) {
        drawerAnim?.cancel()
        drawerOpen = open
        val h = root.height.toFloat()
        if (drawer.visibility != View.VISIBLE) {
            drawer.visibility = View.VISIBLE
            drawer.translationY = h
        }
        val from = drawer.translationY
        val to = if (open) 0f else h
        val frac = (abs(to - from) / h).coerceIn(0.3f, 1f)
        drawerAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = (400 * prefs.factor * frac).toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener { setTy(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationStart(animation: Animator) = layers(true)
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) { if (!cancelled) settle(open) }
            })
            start()
        }
    }

    private fun settle(open: Boolean) {
        layers(false)
        if (!open) {
            drawer.visibility = View.INVISIBLE
            search.setText("")
            hideKeyboard()
        }
    }

    private fun hideKeyboard() {
        search.clearFocus()
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
    }
}
