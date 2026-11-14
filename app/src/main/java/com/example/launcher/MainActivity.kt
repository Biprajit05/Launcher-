package com.example.launcher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.app.Dialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Window
import android.widget.LinearLayout
import android.widget.SeekBar
import kotlin.math.ceil
import android.app.KeyguardManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.PowerManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.RoundedCorner
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var root: View
    private lateinit var home: View
    private lateinit var drawer: View
    private lateinit var homeGrid: HomeGridView
    private lateinit var dock: RecyclerView
    private lateinit var drawerList: RecyclerView
    private lateinit var search: EditText
    private lateinit var homeSearch: View
    private lateinit var pageDots: PageDots
    private lateinit var widgetHost: AppWidgetHost
    private lateinit var widgetManager: AppWidgetManager
    private var pendingWidgetId = -1
    private var pendingProvider: AppWidgetProviderInfo? = null
    private lateinit var rail: AlphabetRail
    private lateinit var railBubble: TextView
    private var shownApps: List<AppInfo> = emptyList()
    private var railGesture = false
    private lateinit var dockAdapter: AppAdapter
    private lateinit var drawerAdapter: AppAdapter

    private var allApps: List<AppInfo> = emptyList()
    private var dockApps: List<AppInfo> = emptyList()

    // Drag from the app drawer to the dock
    private var pendingApp: AppInfo? = null
    private var pendingAnchor: View? = null
    private var pressX = 0f
    private var pressY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var extDragging = false
    private var overlay: ImageView? = null
    private var hoverSlot = -1
    private var focusSearchOnOpen = false

    // Custom icons: gallery picker + change tracking
    private var iconSig = ""
    private var pickTarget: AppInfo? = null
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> onImagePicked(uri) }

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

    // "Expand window from icon" launch animation
    // Unlock animation: home is parked big + invisible while the screen is off, then zooms in on unlock.
    private var unlockPending = false
    private var unlockAnim: ValueAnimator? = null
    private var unlockBlur = -1
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> if (resumedNow) prepareUnlock()
                Intent.ACTION_USER_PRESENT -> playUnlock()
                Intent.ACTION_SCREEN_ON -> if (unlockPending && !keyguardLocked()) playUnlock()
            }
        }
    }
    private val unlockWatch = object : Runnable {
        override fun run() {
            if (!unlockPending) return
            if (resumedNow && !keyguardLocked()) playUnlock() else ui.postDelayed(this, 400)
        }
    }
    private var blurOn = false
    private var lastBlur = -1
    private var blurMaxPx = 0f
    private var drawerLight = true
    private var launchOv: LaunchOverlay? = null
    private var ovAnim: ValueAnimator? = null
    private var ovOpening = false
    private var ovClosing = false
    private var closeWhenOpened = false
    private var resumedNow = false
    private val ui = Handler(Looper.getMainLooper())
    private val launchFailSafe = Runnable {
        // Nothing took over the screen (launch failed / translucent app): give the home screen back.
        if (launchOv != null && !ovClosing && resumedNow) closeLaunchOverlay()
    }

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        iconSig = IconManager.signature(applicationContext)
        root = findViewById(R.id.root)
        home = findViewById(R.id.home)
        drawer = findViewById(R.id.drawer)
        homeGrid = findViewById(R.id.homeGrid)
        dock = findViewById(R.id.dock)
        drawerList = findViewById(R.id.drawerList)
        search = findViewById(R.id.search)
        homeSearch = findViewById(R.id.homeSearch)
        pageDots = findViewById(R.id.pageDots)
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = AppWidgetHost(applicationContext, WIDGET_HOST_ID)
        rail = findViewById(R.id.rail)
        railBubble = findViewById(R.id.railBubble)
        rail.onLetter = { letter, cy, done -> onRailLetter(letter, cy, done) }
        // The A-Z strip only spans the middle ~56% of the drawer height.
        (rail.parent as View).addOnLayoutChangeListener { _, _, t, _, b, _, _, _, _ ->
            val h = ((b - t) * 0.56f).toInt()
            if (h > 0 && rail.layoutParams.height != h) {
                rail.layoutParams.height = h
                rail.requestLayout()
            }
        }
        homeSearch.setOnClickListener { openDrawerForSearch() }

        val menuFromList: (AppInfo, View) -> Unit = { a, v -> showAppMenu(a, v, false) }
        // Drawer: long-press then drag = put on the dock; long-press and release = menu.
        val drawerLongPress: (AppInfo, View) -> Unit = { a, v ->
            if (prefs.dockEnabled) beginDrawerPress(a, v) else showAppMenu(a, v, false)
        }
        dockAdapter = AppAdapter(prefs, { 4 }, true, false, ::launch, menuFromList)
        drawerAdapter = AppAdapter(prefs, { prefs.columns }, false, true, ::launch, drawerLongPress)

        dock.layoutManager = object : GridLayoutManager(this, 4) {
            override fun canScrollVertically() = false
        }
        drawerList.layoutManager = GridLayoutManager(this, prefs.columns)
        dock.adapter = dockAdapter
        drawerList.adapter = drawerAdapter
        drawerList.itemAnimator = null

        homeGrid.onAppClick = { app, v -> launch(app, v) }
        homeGrid.onAppMenu = { app, v -> showAppMenu(app, v, true) }
        homeGrid.onEmptyLongPress = { if (!drawerOpen) showHomeMenu() }
        homeGrid.onFolderClick = { item, v -> openFolder(item, v) }
        homeGrid.onFolderMenu = { item, v -> showFolderMenu(item, v) }
        homeGrid.onWidgetMenu = { item, v -> showWidgetMenu(item, v) }
        homeGrid.onChanged = { items, pages -> prefs.setHomePages(HomeStore.encode(items, pages)) }
        homeGrid.onPageScroll = { count, pos -> pageDots.set(count, pos) }
        homeGrid.widgetFactory = { id -> createWidgetView(id) }
        homeGrid.onWidgetGone = { id ->
            try { widgetHost.deleteAppWidgetId(id) } catch (e: Exception) { /* already gone */ }
        }
        homeGrid.onDragMove = { x, y -> setHover(dockSlotAt(x, y)) }
        homeGrid.onDragEnd = { setHover(-1) }
        homeGrid.onDropOutside = { app, x, y ->
            val slot = dockSlotAt(x, y)
            if (slot >= 0) { assignToDock(app, slot); true } else false
        }

        // Full screen: draw behind the status + navigation bars and pad content instead.
        val sidePad = (20 * resources.displayMetrics.density).toInt()
        val listBottom = (8 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            home.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            drawer.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            drawerList.updatePadding(
                left = sidePad,
                right = sidePad,
                top = listBottom,
                bottom = listBottom
            )
            insets
        }

        search.doAfterTextChanged { applyFilter(it?.toString().orEmpty()) }

        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        })
        reload()
    }

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
    }

    /** Dark status/nav icons only when the light drawer is on screen. */
    private fun updateBars(drawerShown: Boolean) {
        val c = WindowInsetsControllerCompat(window, root)
        val light = drawerShown && drawerLight
        c.isAppearanceLightStatusBars = light
        c.isAppearanceLightNavigationBars = light
    }

    override fun onStart() {
        super.onStart()
        try { widgetHost.startListening() } catch (e: Exception) { /* widgets just won't live-update */ }
    }

    override fun onStop() {
        super.onStop()
        try { widgetHost.stopListening() } catch (e: Exception) { /* ignore */ }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(pkgReceiver)
        unregisterReceiver(screenReceiver)
        ui.removeCallbacksAndMessages(null)
        ovAnim?.cancel()
        executor.shutdown()
    }

    override fun onPause() {
        super.onPause()
        resumedNow = false
        // Paused because the screen turned off (not because an app was opened): park the home screen.
        if (!getSystemService(PowerManager::class.java).isInteractive) prepareUnlock()
    }

    override fun onResume() {
        super.onResume()
        resumedNow = true
        // Coming back from an app: shrink the window back into its icon.
        if (launchOv != null && !ovClosing) {
            if (ovOpening) closeWhenOpened = true else closeLaunchOverlay()
        }
        updateBars(drawerOpen)
        applyDrawerStyle()
        if (unlockPending) {
            if (!prefs.unlockAnim) resetUnlock()
            else if (!keyguardLocked()) playUnlock()
            else { ui.removeCallbacks(unlockWatch); ui.postDelayed(unlockWatch, 400) }
        }
        // Icon pack changed in Settings: reload icons off the UI thread, then rebind.
        val iconsChanged = allApps.isNotEmpty() && IconManager.signature(applicationContext) != iconSig
        if (iconsChanged) refreshIcons()
        if (allApps.isNotEmpty() && !iconsChanged) {
            dockApps = resolveDock(applicationContext, allApps) // pick up changes from Settings
            bind(false)
        }
        if (drawerAnim?.isRunning != true && !unlockPending && unlockAnim?.isRunning != true) {
            val s = if (drawerOpen) 0.94f else 1f
            val a = if (drawerOpen) 0f else 1f
            animateLayer(home, s, a, prefs.speed.toLong())
            animateLayer(drawer, 1f, 1f, prefs.speed.toLong())
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (drawerOpen) animateDrawer(false) // Home button closes the drawer
        else if (homeGrid.currentPage != 0 && !homeGrid.isDragging) homeGrid.goToPage(0)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Never leave the launcher; only close the drawer.
        if (drawerOpen) animateDrawer(false)
    }

    // ---------- Data ----------

    private fun reload() {
        val ctx = applicationContext
        IconManager.invalidate() // icon packs may have been installed / updated / removed
        executor.execute {
            val apps = AppRepository.loadApps(ctx)
            val dockList = resolveDock(ctx, apps)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                IconStyle.clear()
                allApps = apps
                dockApps = dockList
                bind()
            }
        }
    }

    /** Saved dock (if any), padded with the default apps so it always has 4. */
    private fun resolveDock(ctx: Context, apps: List<AppInfo>): List<AppInfo> {
        val defaults = AppRepository.pickDock(ctx, apps)
        val saved = prefs.getDock() ?: return defaults
        val out = ArrayList<AppInfo>()
        for (part in saved.split("|")) {
            val comp = ComponentName.unflattenFromString(part) ?: continue
            val app = apps.firstOrNull { it.component == comp } ?: continue
            if (out.none { it.component == comp }) out.add(app)
        }
        if (out.isEmpty()) return defaults
        for (a in defaults) {
            if (out.size >= 4) break
            if (out.none { it.component == a.component }) out.add(a)
        }
        return out.take(4)
    }

    private fun bind(resetHome: Boolean = true) {
        val cols = prefs.columns
        dock.visibility = if (prefs.dockEnabled) View.VISIBLE else View.GONE
        if (prefs.dockBackground) dock.setBackgroundResource(R.drawable.dock_bg) else dock.background = null
        (drawerList.layoutManager as GridLayoutManager).spanCount = cols
        dockAdapter.submit(dockApps)
        homeGrid.configure(prefs.homeCols, prefs.homeRows, prefs.scale, prefs.shape, prefs.labelScale)
        if (resetHome) {
            val h = loadHome()
            homeGrid.setItems(h.items, h.pages)
        }
        applyFilter(search.text.toString())
    }

    private fun applyFilter(q: String) {
        val t = q.trim()
        shownApps = if (t.isEmpty()) allApps else allApps.filter { it.label.contains(t, true) }
        drawerAdapter.submit(shownApps)
        rail.visibility = if (t.isEmpty()) View.VISIBLE else View.INVISIBLE
    }

    // ---------- Home layout (pages, folders, widgets are saved by HomeStore) ----------

    private fun loadHome(): HomeStore.Decoded {
        val saved = prefs.getHomePages()
        if (saved != null) return HomeStore.decode(saved, allApps)
        val old = prefs.getHomeLayout()
        if (old != null) return HomeStore.Decoded(HomeStore.migrate(old, allApps), 1)
        val cols = prefs.homeCols
        val rows = prefs.homeRows
        val dockPkgs = if (prefs.dockEnabled) dockApps.map { it.packageName }.toSet() else emptySet()
        val first = allApps.filter { it.packageName !in dockPkgs }.take(cols * minOf(rows, 5))
        val items = first.mapIndexed { i, a -> HomeItem(HomeItem.APP, 0, i % cols, i / cols, app = a) }
        return HomeStore.Decoded(items, 1)
    }

    // ---------- Launch ----------

    private fun launch(app: AppInfo, iconView: View) {
        if (launchOv != null && !ovClosing) return // a launch is already running
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (prefs.style == 3) {
            expandLaunch(app, intent, iconView)
            return
        }
        try {
            startActivity(intent, buildOptions(iconView))
            // shrink + dim whichever layer the user tapped in
            animateLayer(if (drawerOpen) drawer else home, 0.92f, 0.5f, prefs.speed.toLong())
        } catch (e: Exception) {
            Toast.makeText(this, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Unlock animation ----------

    private fun keyguardLocked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Screen went off with the home screen showing: park it big and invisible, ready to zoom in. */
    private fun prepareUnlock() {
        if (!prefs.unlockAnim || drawerOpen || launchOv != null || unlockPending) return
        unlockAnim?.cancel()
        unlockPending = true
        home.scaleX = UNLOCK_SCALE
        home.scaleY = UNLOCK_SCALE
        home.alpha = 0f
    }

    private fun resetUnlock() {
        unlockPending = false
        ui.removeCallbacks(unlockWatch)
        unlockAnim?.cancel()
        if (Build.VERSION.SDK_INT >= 31) home.setRenderEffect(null)
        home.setLayerType(View.LAYER_TYPE_NONE, null)
        if (!drawerOpen) { home.scaleX = 1f; home.scaleY = 1f; home.alpha = 1f }
    }

    /** Home zooms from big + blurry down to normal while it fades in. */
    private fun playUnlock() {
        if (!unlockPending) return
        unlockPending = false
        ui.removeCallbacks(unlockWatch)
        if (!prefs.unlockAnim || drawerOpen) { resetUnlock(); return }
        unlockAnim?.cancel()
        unlockBlur = -1
        val blurMax = 26 * resources.displayMetrics.density
        home.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        unlockAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (520 * prefs.factor).toLong()
            interpolator = PathInterpolator(0.1f, 0.85f, 0.2f, 1f)
            addUpdateListener {
                val p = it.animatedValue as Float
                val sc = UNLOCK_SCALE - (UNLOCK_SCALE - 1f) * p
                home.scaleX = sc
                home.scaleY = sc
                home.alpha = (it.animatedFraction / 0.3f).coerceIn(0f, 1f)
                if (Build.VERSION.SDK_INT >= 31) {
                    val r = ((1f - p) * blurMax).toInt()
                    if (r != unlockBlur) {
                        unlockBlur = r
                        home.setRenderEffect(
                            if (r <= 0) null else RenderEffect.createBlurEffect(r.toFloat(), r.toFloat(), Shader.TileMode.CLAMP)
                        )
                    }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    if (Build.VERSION.SDK_INT >= 31) home.setRenderEffect(null)
                    home.setLayerType(View.LAYER_TYPE_NONE, null)
                    home.scaleX = 1f; home.scaleY = 1f; home.alpha = 1f
                }
            })
            start()
        }
    }

    // ---------- Expand-from-icon launch / shrink-back-to-icon return ----------

    private val UNLOCK_SCALE = 1.4f
    private val openInterp = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val closeInterp = PathInterpolator(0.3f, 0f, 0f, 1f)

    /** Icon bounds in root coordinates, including any scale (press effect, drawer scale). */
    private fun rectInRoot(v: View): RectF {
        val a = IntArray(2); v.getLocationOnScreen(a)
        val r = IntArray(2); root.getLocationOnScreen(r)
        var sx = 1f
        var sy = 1f
        var p: View? = v
        while (p != null && p !== root) {
            sx *= p.scaleX; sy *= p.scaleY
            p = p.parent as? View
        }
        val left = (a[0] - r[0]).toFloat()
        val top = (a[1] - r[1]).toFloat()
        return RectF(left, top, left + v.width * sx, top + v.height * sy)
    }

    private fun iconCornerRadius(size: Float): Float = when (prefs.shape) {
        1 -> size / 2f      // circle
        2 -> size * 0.34f   // squircle
        4 -> size * 0.06f   // square
        5 -> size * 0.40f   // teardrop
        else -> size * 0.22f // original / rounded square
    }

    private fun deviceCornerRadius(): Float {
        if (Build.VERSION.SDK_INT >= 31) {
            val c = root.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
            if (c != null && c.radius > 0) return c.radius.toFloat()
        }
        return 28 * resources.displayMetrics.density
    }

    private fun expandLaunch(app: AppInfo, intent: Intent, iconView: View) =
        expandLaunchCore(app, intent, rectInRoot(iconView), (iconView as? ImageView)?.drawable)

    /** Same as a normal launch, but the window grows out of a given rectangle (used by folders). */
    private fun launchFromRect(app: AppInfo, rect: RectF, drawable: Drawable?) {
        if (launchOv != null && !ovClosing) return
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        expandLaunchCore(app, intent, rect, drawable)
    }

    private fun expandLaunchCore(app: AppInfo, intent: Intent, src: RectF, iconDrawable: Drawable?) {
        removeLaunchOverlay() // clear a previous overlay that is still shrinking
        val density = resources.displayMetrics.density
        val w = root.width.toFloat()
        val h = root.height.toFloat()
        if (w <= 0f || h <= 0f) { // not laid out yet: plain launch
            try { startActivity(intent) } catch (e: Exception) { cantOpen(app) }
            return
        }
        val ov = LaunchOverlay(this).apply {
            from.set(src)
            to.set(0f, 0f, w, h)
            fromRadius = iconCornerRadius(src.width())
            toRadius = deviceCornerRadius()
            iconFrom = min(src.width(), src.height())
            iconTo = min(w * 0.26f, 110 * density)
            fillColor = ContextCompat.getColor(this@MainActivity, R.color.splash_bg)
            icon = iconDrawable?.constantState?.newDrawable(resources)?.mutate() ?: iconDrawable ?: app.icon
            progress = 0f
        }
        (root as ViewGroup).addView(ov, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        launchOv = ov
        ovClosing = false
        closeWhenOpened = false
        hideKeyboard()

        // Animation starts on the very next frame; the activity is started right after.
        ovOpening = true
        ovAnim?.cancel()
        ovAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (380 * prefs.factor).toLong()
            interpolator = openInterp
            addUpdateListener { ov.progress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    ovOpening = false
                    if (closeWhenOpened) { closeWhenOpened = false; closeLaunchOverlay() }
                }
            })
            start()
        }

        // The app's own window fades in on top of the overlay once it is nearly full-screen.
        val d = prefs.speed
        val opts = ActivityOptions.makeCustomAnimation(this, anim("expand_in_$d"), anim("expand_hold_$d"))
        try {
            startActivity(intent, opts.toBundle())
            ui.removeCallbacks(launchFailSafe)
            ui.postDelayed(launchFailSafe, 1500)
        } catch (e: Exception) {
            removeLaunchOverlay()
            cantOpen(app)
        }
    }

    private fun cantOpen(app: AppInfo) =
        Toast.makeText(this, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()

    /** Shrink the window back into the icon, then drop the overlay. Taps pass through while it runs. */
    private fun closeLaunchOverlay() {
        val ov = launchOv ?: return
        ui.removeCallbacks(launchFailSafe)
        ovAnim?.cancel()
        ovOpening = false
        ovClosing = true
        ov.isClickable = false
        val startP = ov.progress
        ovAnim = ValueAnimator.ofFloat(startP, 0f).apply {
            duration = (330 * prefs.factor * max(startP, 0.4f)).toLong().coerceAtLeast(120L)
            interpolator = closeInterp
            addUpdateListener { ov.progress = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && launchOv === ov) removeLaunchOverlay()
                }
            })
            start()
        }
    }

    private fun removeLaunchOverlay() {
        ui.removeCallbacks(launchFailSafe)
        ovAnim?.cancel()
        ovAnim = null
        launchOv?.let { (root as ViewGroup).removeView(it) }
        launchOv = null
        ovOpening = false
        ovClosing = false
        closeWhenOpened = false
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

    private fun showAppMenu(app: AppInfo, anchor: View, onHome: Boolean) {
        val target = anchor.findViewById<View>(R.id.icon) ?: anchor
        val main = ArrayList<AppPopup.Item>()
        main.add(AppPopup.Item(R.drawable.ic_pop_image, "Edit icon") { showEditIcon(app) })
        main.add(AppPopup.Item(R.drawable.ic_pop_edit, "Rename") { showRenameDialog(app) })
        if (!onHome) main.add(AppPopup.Item(R.drawable.ic_pop_add, "Add to home") { addToHome(app) })
        val danger = ArrayList<AppPopup.Item>()
        if (onHome) {
            danger.add(AppPopup.Item(R.drawable.ic_pop_remove, "Remove from home", true) { homeGrid.removeApp(app) })
        }
        danger.add(AppPopup.Item(R.drawable.ic_pop_delete, "Uninstall", true) { uninstall(app) })
        AppPopup(this).show(root, target, app.label, listOf(main, danger)) { openAppInfo(app) }
    }

    private fun openAppInfo(app: AppInfo) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null))
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uninstall(app: AppInfo) {
        val flags = try { packageManager.getApplicationInfo(app.packageName, 0).flags } catch (e: Exception) { 0 }
        val pureSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        if (pureSystem) {
            // Android does not allow removing built-in apps; App info has Disable.
            Toast.makeText(this, "${app.systemLabel} is a system app and can't be uninstalled. Opening App info.", Toast.LENGTH_LONG).show()
            openAppInfo(app)
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null)))
        } catch (e: Exception) {
            Toast.makeText(this, "Not available", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- Edit icon / Rename ----------

    private fun showRenameDialog(app: AppInfo) {
        val d = resources.displayMetrics.density
        val input = EditText(this)
        input.setSingleLine()
        input.setText(app.label)
        input.setSelection(input.text.length)
        val box = FrameLayout(this)
        box.setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        box.addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val t = input.text.toString().trim()
                IconManager.setCustomLabel(this, app.component, if (t.isEmpty() || t == app.systemLabel) null else t)
                reload()
            }
            .setNeutralButton("Reset") { _, _ ->
                IconManager.setCustomLabel(this, app.component, null)
                reload()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun showEditIcon(app: AppInfo) {
        val titles = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        titles.add("Choose from gallery")
        actions.add { pickTarget = app; pickImage.launch("image/*") }
        if (IconManager.installedPacks(this).isNotEmpty()) {
            titles.add("Choose from icon pack")
            actions.add { pickFromPack(app) }
        }
        if (IconManager.hasIconOverride(this, app.component)) {
            titles.add("Reset to default icon")
            actions.add {
                IconManager.clearIconOverride(this, app.component)
                refreshIcons()
            }
        }
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(titles.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun pickFromPack(app: AppInfo) {
        val chosen = IconManager.selectedPack(this)
        val usable = chosen != null && IconManager.installedPacks(this).any { it.pkg == chosen }
        val pick = { pkg: String ->
            IconDialogs.pickPackIcon(this, pkg) { name ->
                IconManager.setPackIconOverride(this, app.component, pkg, name)
                refreshIcons()
            }
        }
        if (usable && chosen != null) pick(chosen)
        else IconDialogs.choosePack(this, false) { pkg -> if (pkg != null) pick(pkg) }
    }

    private fun onImagePicked(uri: Uri?) {
        val app = pickTarget
        pickTarget = null
        if (app == null || uri == null) return
        val ctx = applicationContext
        executor.execute {
            val ok = IconManager.saveGalleryIcon(ctx, app.component, uri)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (ok) refreshIcons()
                else Toast.makeText(this, "Couldn't use that image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Icon pack or a custom icon changed: drop cached icons and redraw everything. */
    private fun refreshIcons() {
        val ctx = applicationContext
        iconSig = IconManager.signature(ctx)
        executor.execute {
            IconManager.warm(ctx)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                IconStyle.clear()
                if (allApps.isNotEmpty()) {
                    dockApps = resolveDock(ctx, allApps)
                    bind()
                }
            }
        }
    }

    private fun addToHome(app: AppInfo) {
        if (homeGrid.contains(app)) {
            Toast.makeText(this, "Already on the home screen", Toast.LENGTH_SHORT).show()
        } else if (homeGrid.addApp(app)) {
            if (drawerOpen) animateDrawer(false)
        } else {
            Toast.makeText(this, "Home screen is full", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showHomeMenu() {
        PopupMenu(this, homeGrid, Gravity.CENTER).apply {
            menu.add(0, 2, 0, "Widgets")
            menu.add(0, 1, 1, R.string.settings)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    2 -> showWidgetPicker()
                    else -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
                true
            }
            show()
        }
    }

    // ---------- Dock: drag & drop ----------

    private fun beginDrawerPress(app: AppInfo, anchor: View) {
        pendingApp = app
        pendingAnchor = anchor
        pressX = lastRawX
        pressY = lastRawY
        // Stop the list / icon from scrolling or clicking while we own the touch.
        val c = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
            MotionEvent.ACTION_CANCEL, lastX, lastY, 0)
        super.dispatchTouchEvent(c)
        c.recycle()
        anchor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun startExtDrag(app: AppInfo) {
        extDragging = true
        val iconView = pendingAnchor?.findViewById<ImageView>(R.id.icon)
        val size = (iconView?.width ?: 0).takeIf { it > 0 } ?: (52 * resources.displayMetrics.density).toInt()
        val img = ImageView(this)
        img.setImageDrawable(iconView?.drawable?.constantState?.newDrawable() ?: app.icon)
        img.scaleType = ImageView.ScaleType.FIT_XY
        img.layoutParams = FrameLayout.LayoutParams(size, size)
        img.scaleX = 1.15f
        img.scaleY = 1.15f
        (root as FrameLayout).addView(img)
        overlay = img
        moveExtDrag(lastRawX, lastRawY)
        if (drawerOpen) animateDrawer(false) // reveal the home screen + dock
        hideKeyboard()
    }

    private fun moveExtDrag(rawX: Float, rawY: Float) {
        val img = overlay ?: return
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        img.translationX = rawX - loc[0] - img.layoutParams.width / 2f
        img.translationY = rawY - loc[1] - img.layoutParams.height / 2f
        setHover(dockSlotAt(rawX, rawY))
    }

    private fun endExtDrag() {
        overlay?.let { (root as FrameLayout).removeView(it) }
        overlay = null
        extDragging = false
        setHover(-1)
    }

    /** Which dock slot (0..3) is under this screen point, or -1. */
    private fun dockSlotAt(x: Float, y: Float): Int {
        if (!prefs.dockEnabled || dock.visibility != View.VISIBLE || dock.width == 0) return -1
        val loc = IntArray(2)
        dock.getLocationOnScreen(loc)
        val sc = home.scaleX
        val w = dock.width * sc
        val h = dock.height * sc
        val pad = 24 * resources.displayMetrics.density
        if (x < loc[0] || x > loc[0] + w || y < loc[1] - pad || y > loc[1] + h + pad) return -1
        return ((x - loc[0]) / w * 4).toInt().coerceIn(0, 3)
    }

    private fun setHover(slot: Int) {
        if (slot == hoverSlot) return
        dock.findViewHolderForAdapterPosition(hoverSlot)?.itemView?.animate()
            ?.scaleX(1f)?.scaleY(1f)?.setDuration(120)?.start()
        hoverSlot = slot
        dock.findViewHolderForAdapterPosition(slot)?.itemView?.animate()
            ?.scaleX(1.25f)?.scaleY(1.25f)?.setDuration(120)?.start()
    }

    private fun assignToDock(app: AppInfo, slot: Int) {
        val list = dockApps.toMutableList()
        val existing = list.indexOfFirst { it.component == app.component }
        if (slot >= list.size) {
            if (existing >= 0) list.removeAt(existing)
            list.add(app)
        } else if (existing >= 0) {
            Collections.swap(list, existing, slot)
        } else {
            list[slot] = app
        }
        dockApps = list.take(4)
        prefs.setDock(dockApps.joinToString("|") { it.component.flattenToString() })
        dockAdapter.submit(dockApps)
    }

    private fun firstLetter(label: String): String {
        val c = label.trim().firstOrNull()?.uppercaseChar() ?: return "#"
        return if (c in 'A'..'Z') c.toString() else "#"
    }

    private fun onRailLetter(letter: String, cy: Float, done: Boolean) {
        if (done) {
            railBubble.visibility = View.GONE
            return
        }
        val list = shownApps
        // "#" = apps not starting with a letter (they sort first); a letter with no apps jumps to the next one.
        var pos = list.indexOfFirst { firstLetter(it.label) == letter }
        if (pos < 0 && letter != "#") pos = list.indexOfFirst { firstLetter(it.label) > letter }
        if (pos < 0 && letter != "#") pos = list.size - 1
        if (pos >= 0) {
            drawerList.stopScroll()
            (drawerList.layoutManager as GridLayoutManager).scrollToPositionWithOffset(pos, 0)
        }
        railBubble.text = letter
        val lp = railBubble.layoutParams as FrameLayout.LayoutParams
        lp.topMargin = (rail.top + cy - railBubble.layoutParams.height / 2f).toInt().coerceAtLeast(0)
        railBubble.layoutParams = lp
        railBubble.visibility = View.VISIBLE
    }

    private fun hitRail(ev: MotionEvent): Boolean {
        if (rail.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        rail.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] - 8 && ev.rawX <= loc[0] + rail.width &&
            ev.rawY >= loc[1] && ev.rawY <= loc[1] + rail.height
    }

    private fun openDrawerForSearch() {
        if (drawerOpen) return
        focusSearchOnOpen = true
        animateDrawer(true)
    }

    // ---------- Drawer (drag + animate) ----------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val a = ev.actionMasked
        lastX = ev.x; lastY = ev.y
        lastRawX = ev.rawX; lastRawY = ev.rawY

        // A drawer icon was long-pressed: drag it to the dock, or release for the menu.
        val pa = pendingApp
        if (pa != null) {
            when (a) {
                MotionEvent.ACTION_MOVE -> {
                    if (!extDragging && hypot(ev.rawX - pressX, ev.rawY - pressY) > slop * 1.5f) startExtDrag(pa)
                    if (extDragging) moveExtDrag(ev.rawX, ev.rawY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val anchor = pendingAnchor
                    if (extDragging) {
                        val slot = if (a == MotionEvent.ACTION_UP) dockSlotAt(ev.rawX, ev.rawY) else -1
                        if (slot >= 0) assignToDock(pa, slot)
                        endExtDrag()
                    } else if (a == MotionEvent.ACTION_UP && anchor != null) {
                        showAppMenu(pa, anchor, false)
                    }
                    pendingApp = null; pendingAnchor = null
                    tracking = false; vt?.recycle(); vt = null
                }
            }
            return true
        }

        // Touches on the A-Z rail belong to the rail only.
        if (a == MotionEvent.ACTION_DOWN) railGesture = drawerOpen && hitRail(ev)
        if (railGesture) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) railGesture = false
            return super.dispatchTouchEvent(ev)
        }

        // While an icon is being moved on the home screen, never start the drawer swipe.
        if (homeGrid.isDragging) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                tracking = false; vt?.recycle(); vt = null
            }
            return super.dispatchTouchEvent(ev)
        }

        if (a == MotionEvent.ACTION_DOWN) {
            vt?.recycle(); vt = VelocityTracker.obtain()
            downX = ev.x; downY = ev.y
            tracking = true; dragging = false
            homeGrid.touchOnWidget = false
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
            if (abs(dy) > slop && abs(dy) > abs(dx) && !homeGrid.isPaging) {
                // A swipe that starts on a widget belongs to the widget (so lists inside it can scroll).
                val canOpen = !drawerOpen && dy < 0 && !homeGrid.touchOnWidget
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
        drawer.animate().cancel()
        if (drawer.visibility != View.VISIBLE) {
            // Appears right away, a bit below the middle of the screen, fully transparent.
            drawer.visibility = View.VISIBLE
            setTy(closedTy())
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
        val open = when {
            vy < -250f -> true
            vy > 800f -> false
            else -> drawer.translationY < closedTy() * 0.75f
        }
        animateDrawer(open)
    }

    /** Where the drawer sits when closed: a little below the middle of the screen. */
    private fun closedTy(): Float = root.height * 0.6f

    /**
     * Drawer position, drawer opacity and home scale/alpha are all driven from one value.
     * Closed: drawer at 60% down, alpha 0. Open: drawer at the top, alpha 1, home faded out.
     */
    private fun setTy(ty: Float) {
        val range = closedTy().coerceAtLeast(1f)
        val t = ty.coerceIn(0f, range)
        drawer.translationY = t
        val p = 1f - t / range
        drawer.alpha = p
        if (blurOn && Build.VERSION.SDK_INT >= 31) {
            val r = (p * blurMaxPx).toInt()
            if (r != lastBlur) {
                lastBlur = r
                window.setBackgroundBlurRadius(r)
            }
        }
        val s = 1f - 0.06f * p
        home.scaleX = s; home.scaleY = s
        home.alpha = 1f - p
    }

    private fun systemIsNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Auto mode: light drawer on a light wallpaper, dark drawer on a dark one. */
    private fun wallpaperIsLight(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                val c = WallpaperManager.getInstance(this).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                if (c != null) return (c.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
            } catch (e: Exception) { /* fall through */ }
        }
        return !systemIsNight()
    }

    /** Drawer look from the settings: colour (auto/light/dark), transparency and blur. */
    private fun applyDrawerStyle() {
        blurOn = Build.VERSION.SDK_INT >= 31 && windowManager.isCrossWindowBlurEnabled
        drawerLight = when (prefs.drawerColor) {
            1 -> true
            2 -> false
            else -> wallpaperIsLight()
        }
        // transparency 0 (opaque) .. 100 (transparent) -> tint opacity 96% .. 10%
        var alpha = 0.96f - (0.96f - 0.10f) * (prefs.drawerTransparency / 100f)
        if (!blurOn) alpha = max(alpha, 0.88f) // no blur available: keep it readable
        val base = if (drawerLight) Color.rgb(0xF2, 0xF2, 0xF7) else Color.rgb(0x14, 0x14, 0x16)
        drawer.setBackgroundColor(Color.argb((alpha * 255).toInt(), Color.red(base), Color.green(base), Color.blue(base)))
        blurMaxPx = if (blurOn) prefs.drawerBlur / 100f * 150f * resources.displayMetrics.density else 0f
        if (Build.VERSION.SDK_INT >= 31 && !drawerOpen && lastBlur != 0) {
            lastBlur = 0
            window.setBackgroundBlurRadius(0)
        }

        val text = if (drawerLight) Color.rgb(0x1C, 0x1C, 0x1E) else Color.WHITE
        val hint = if (drawerLight) Color.argb(0xB0, 0x3A, 0x3A, 0x3C) else Color.argb(0xB3, 0xFF, 0xFF, 0xFF)
        drawerAdapter.setTextColor(text)
        rail.setColors(hint, text)
        search.setTextColor(text)
        search.setHintTextColor(hint)
        search.background?.mutate()?.setTint(
            if (drawerLight) Color.argb(0x26, 0, 0, 0) else Color.argb(0x33, 0xFF, 0xFF, 0xFF)
        )
        updateBars(drawerOpen)
    }

    private fun layers(on: Boolean) {
        val t = if (on) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE
        home.setLayerType(t, null)
        drawer.setLayerType(t, null)
    }

    private fun animateDrawer(open: Boolean) {
        drawerAnim?.cancel()
        drawerOpen = open
        updateBars(open)
        val h = closedTy().coerceAtLeast(1f)
        drawer.animate().cancel()
        if (drawer.visibility != View.VISIBLE) {
            drawer.visibility = View.VISIBLE
            setTy(h)
        }
        val from = drawer.translationY
        val to = if (open) 0f else h
        val frac = (abs(to - from) / h).coerceIn(0.3f, 1f)
        drawerAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = ((if (open) 240 else 300) * prefs.factor * frac).toLong()
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
        if (!open) focusSearchOnOpen = false
        if (open && focusSearchOnOpen) {
            focusSearchOnOpen = false
            search.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
        }
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

    // ---------- Folders ----------

    private fun openFolder(item: HomeItem, folderIcon: View) {
        val folder = item.folder ?: return
        val d = resources.displayMetrics.density
        val dm = resources.displayMetrics
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
        val bg = GradientDrawable()
        bg.setColor(Color.argb(0xEE, 0x1E, 0x20, 0x24))
        bg.setCornerRadius(28 * d)
        panel.background = bg

        val title = TextView(this)
        title.text = folder.name
        title.setTextColor(Color.WHITE)
        title.textSize = 18f
        title.gravity = Gravity.CENTER
        title.setPadding(0, (4 * d).toInt(), 0, (12 * d).toInt())
        title.setOnClickListener { dialog.dismiss(); showRenameFolder(item) }
        panel.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val rv = RecyclerView(this)
        rv.layoutManager = GridLayoutManager(this, 3)
        val adapter = AppAdapter(
            prefs, { 3 }, true, true,
            { app, v -> folderLaunch(dialog, app, v, folderIcon) },
            { app, _ -> showFolderAppMenu(dialog, item, app) }
        )
        adapter.submit(folder.apps.toList())
        rv.adapter = adapter
        val rowsNeeded = (folder.apps.size + 2) / 3
        val h = min((rowsNeeded * 104 * d).toInt(), (dm.heightPixels * 0.55f).toInt())
        panel.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))

        dialog.setContentView(panel)
        val win = dialog.window
        if (win != null) {
            win.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            win.setLayout((dm.widthPixels * 0.88f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            win.setDimAmount(0.4f)
        }
        dialog.show()
    }

    private fun folderLaunch(dialog: Dialog, app: AppInfo, tapped: View, folderIcon: View) {
        if (prefs.style == 3) {
            // The app grows out of the icon that was tapped inside the folder.
            val rect = rectInRoot(tapped)
            val drawable = (tapped as? ImageView)?.drawable?.constantState?.newDrawable(resources)?.mutate()
            dialog.dismiss()
            launchFromRect(app, rect, drawable)
        } else {
            dialog.dismiss()
            launch(app, folderIcon)
        }
    }

    private fun showFolderAppMenu(folderDialog: Dialog, item: HomeItem, app: AppInfo) {
        val titles = arrayOf("Remove from folder", "Edit icon", "Rename", "App info", "Uninstall")
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(titles) { _, which ->
                folderDialog.dismiss()
                when (which) {
                    0 -> homeGrid.removeFromFolder(item, app)
                    1 -> showEditIcon(app)
                    2 -> showRenameDialog(app)
                    3 -> openAppInfo(app)
                    4 -> uninstall(app)
                }
            }
            .show()
    }

    private fun showFolderMenu(item: HomeItem, anchor: View) {
        val main = listOf(AppPopup.Item(R.drawable.ic_pop_edit, "Rename") { showRenameFolder(item) })
        val danger = listOf(AppPopup.Item(R.drawable.ic_pop_remove, "Ungroup folder", true) { homeGrid.ungroupFolder(item) })
        AppPopup(this).show(root, anchor, item.folder?.name ?: "Folder", listOf(main, danger), false) { }
    }

    private fun showRenameFolder(item: HomeItem) {
        val folder = item.folder ?: return
        val builder = AlertDialog.Builder(this)
        val ctx = builder.context
        val d = resources.displayMetrics.density
        val input = EditText(ctx)
        input.setSingleLine()
        input.setText(folder.name)
        input.setSelection(input.text.length)
        val box = FrameLayout(ctx)
        box.setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        box.addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val dialog = builder
            .setTitle("Folder name")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val t = input.text.toString().trim()
                folder.name = if (t.isEmpty()) "Folder" else t
                homeGrid.refreshItem(item)
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    // ---------- Widgets ----------

    private fun createWidgetView(id: Int): View? {
        return try {
            val info = widgetManager.getAppWidgetInfo(id) ?: return null
            widgetHost.createView(this, id, info)
        } catch (e: Exception) {
            null
        }
    }

    private fun showWidgetPicker() {
        WidgetPicker.show(this) { info -> startAddWidget(info) }
    }

    @Suppress("DEPRECATION")
    private fun startAddWidget(info: AppWidgetProviderInfo) {
        val id = try {
            widgetHost.allocateAppWidgetId()
        } catch (e: Exception) {
            Toast.makeText(this, "Can't add widgets here", Toast.LENGTH_SHORT).show()
            return
        }
        pendingWidgetId = id
        pendingProvider = info
        val bound = try {
            widgetManager.bindAppWidgetIdIfAllowed(id, info.provider)
        } catch (e: Exception) {
            false
        }
        if (bound) {
            afterWidgetBound(id, info)
            return
        }
        // Android asks the user for permission to show this widget.
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
        try {
            startActivityForResult(intent, REQ_WIDGET_BIND)
        } catch (e: Exception) {
            dropPendingWidget()
            Toast.makeText(this, "Can't add this widget", Toast.LENGTH_SHORT).show()
        }
    }

    private fun afterWidgetBound(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_WIDGET_CONFIGURE, null)
            } catch (e: Exception) {
                placeWidget(id, info) // its setup screen can't open: add it as it is
            }
        } else {
            placeWidget(id, info)
        }
    }

    private fun placeWidget(id: Int, info: AppWidgetProviderInfo) {
        pendingWidgetId = -1
        pendingProvider = null
        val span = spanFor(info)
        if (!homeGrid.addWidget(id, span.first, span.second)) {
            try { widgetHost.deleteAppWidgetId(id) } catch (e: Exception) { /* ignore */ }
            Toast.makeText(this, "No space on the home screen", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dropPendingWidget() {
        val id = pendingWidgetId
        if (id >= 0) {
            try { widgetHost.deleteAppWidgetId(id) } catch (e: Exception) { /* ignore */ }
        }
        pendingWidgetId = -1
        pendingProvider = null
    }

    /** How many cells (columns, rows) a new widget should cover. */
    private fun spanFor(info: AppWidgetProviderInfo): Pair<Int, Int> {
        var sc = ceil(info.minWidth.toFloat() / max(1, homeGrid.cellWidthPx)).toInt()
        var sr = ceil(info.minHeight.toFloat() / max(1, homeGrid.cellHeightPx)).toInt()
        if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            sc = info.targetCellWidth
            sr = info.targetCellHeight
        }
        return Pair(sc.coerceIn(1, prefs.homeCols), sr.coerceIn(1, prefs.homeRows))
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_WIDGET_BIND && requestCode != REQ_WIDGET_CONFIGURE) return
        val id = pendingWidgetId
        val info = pendingProvider
        if (id < 0 || info == null) return
        if (resultCode != RESULT_OK) {
            dropPendingWidget()
            return
        }
        if (requestCode == REQ_WIDGET_BIND) afterWidgetBound(id, info) else placeWidget(id, info)
    }

    private fun showWidgetMenu(item: HomeItem, anchor: View) {
        val info = widgetManager.getAppWidgetInfo(item.widgetId)
        val main = ArrayList<AppPopup.Item>()
        if (info == null || info.resizeMode != AppWidgetProviderInfo.RESIZE_NONE) {
            main.add(AppPopup.Item(R.drawable.ic_pop_edit, "Resize") { showResizeWidget(item) })
        }
        val danger = listOf(AppPopup.Item(R.drawable.ic_pop_remove, "Remove widget", true) { removeWidget(item) })
        val name = if (info != null) info.loadLabel(packageManager) else "Widget"
        AppPopup(this).show(root, anchor, name, listOf(main, danger), false) { }
    }

    private fun removeWidget(item: HomeItem) {
        homeGrid.removeWidget(item)
        try { widgetHost.deleteAppWidgetId(item.widgetId) } catch (e: Exception) { /* ignore */ }
    }

    private fun showResizeWidget(item: HomeItem) {
        val builder = AlertDialog.Builder(this)
        val ctx = builder.context
        val d = resources.displayMetrics.density
        var newW = item.spanC
        var newH = item.spanR
        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding((24 * d).toInt(), (12 * d).toInt(), (24 * d).toInt(), 0)

        fun addRow(name: String, maxCells: Int, start: Int, onChange: (Int) -> Unit) {
            val label = TextView(ctx)
            label.text = "$name: $start"
            label.textSize = 15f
            val bar = SeekBar(ctx)
            bar.max = max(0, maxCells - 1)
            bar.progress = (start - 1).coerceIn(0, max(0, maxCells - 1))
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    label.text = "$name: ${progress + 1}"
                    onChange(progress + 1)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            box.addView(label)
            box.addView(bar)
        }
        addRow("Width (columns)", prefs.homeCols, newW) { newW = it }
        addRow("Height (rows)", prefs.homeRows, newH) { newH = it }

        builder.setTitle("Resize widget")
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                if (!homeGrid.resizeWidget(item, newW, newH)) {
                    Toast.makeText(this, "Not enough space for that size", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private companion object {
        const val WIDGET_HOST_ID = 4242
        const val REQ_WIDGET_BIND = 9001
        const val REQ_WIDGET_CONFIGURE = 9002
    }
}
