package com.example.launcher

import android.app.Activity
import android.app.Dialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Full-screen widget picker on a blurred background: one card per app (with a count),
 * tap a card to see that app's widgets, tap a widget to add it.
 */
object WidgetPicker {

    private class Group(val pkg: String, val app: String, val items: List<AppWidgetProviderInfo>)

    private class GroupVH(
        val box: LinearLayout,
        val img: ImageView,
        val name: TextView,
        val count: TextView
    ) : RecyclerView.ViewHolder(box)

    private class ItemVH(
        val box: LinearLayout,
        val img: ImageView,
        val name: TextView,
        val size: TextView
    ) : RecyclerView.ViewHolder(box)

    private fun widgetLabel(pm: PackageManager, info: AppWidgetProviderInfo): String =
        try { info.loadLabel(pm) } catch (e: Exception) { info.provider.shortClassName }

    private fun appName(pm: PackageManager, info: AppWidgetProviderInfo): String =
        try {
            pm.getApplicationInfo(info.provider.packageName, 0).loadLabel(pm).toString()
        } catch (e: Exception) {
            info.provider.packageName
        }

    private fun hasPreview(activity: Activity, info: AppWidgetProviderInfo): Boolean =
        try { info.loadPreviewImage(activity, 0) != null } catch (e: Exception) { false }

    private fun preview(activity: Activity, info: AppWidgetProviderInfo): Drawable? =
        try {
            info.loadPreviewImage(activity, 0)
                ?: info.loadIcon(activity, activity.resources.displayMetrics.densityDpi)
        } catch (e: Exception) {
            null
        }

    private fun round(color: Int, radiusPx: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
        }

    fun show(activity: Activity, onPick: (AppWidgetProviderInfo) -> Unit) {
        val pm = activity.packageManager
        val providers: List<AppWidgetProviderInfo> = try {
            AppWidgetManager.getInstance(activity).installedProviders
        } catch (e: Exception) {
            emptyList()
        }
        if (providers.isEmpty()) {
            Toast.makeText(activity, "No widgets available", Toast.LENGTH_SHORT).show()
            return
        }
        val groups: List<Group> = providers
            .groupBy { it.provider.packageName }
            .map { (pkg, list) ->
                Group(pkg, appName(pm, list[0]), list.sortedBy { widgetLabel(pm, it).lowercase() })
            }
            .sortedBy { it.app.lowercase() }

        val dm = activity.resources.displayMetrics
        val d = dm.density
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT

        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(mp, mp)
            WindowCompat.setDecorFitsSystemWindows(w, false)
            w.statusBarColor = Color.TRANSPARENT
            w.navigationBarColor = Color.TRANSPARENT
        }

        val root = FrameLayout(activity)
        var blurred = false
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                if (activity.windowManager.isCrossWindowBlurEnabled) {
                    dialog.window?.setBackgroundBlurRadius((60 * d).toInt())
                    blurred = true
                }
            } catch (e: Exception) { /* not supported */ }
        }
        root.setBackgroundColor(if (blurred) Color.argb(120, 10, 24, 34) else Color.argb(235, 14, 22, 30))

        val page = LinearLayout(activity)
        page.orientation = LinearLayout.VERTICAL
        root.addView(page, FrameLayout.LayoutParams(mp, mp))
        @Suppress("DEPRECATION")
        root.setOnApplyWindowInsetsListener { _, insets ->
            page.setPadding(
                (20 * d).toInt(),
                insets.systemWindowInsetTop + (24 * d).toInt(),
                (20 * d).toInt(),
                insets.systemWindowInsetBottom
            )
            insets
        }

        val title = TextView(activity)
        title.setTextColor(Color.WHITE)
        title.textSize = 34f
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.text = "Widgets"
        title.setPadding((4 * d).toInt(), 0, 0, (14 * d).toInt())
        page.addView(title, LinearLayout.LayoutParams(mp, wc))

        val search = EditText(activity)
        search.hint = "Search widgets"
        search.setHintTextColor(Color.argb(190, 255, 255, 255))
        search.setTextColor(Color.WHITE)
        search.textSize = 17f
        search.isSingleLine = true
        search.background = round(Color.argb(70, 255, 255, 255), 28f * d)
        search.setPadding((20 * d).toInt(), 0, (20 * d).toInt(), 0)
        val sIcon = activity.getDrawable(R.drawable.ic_search)?.mutate()
        sIcon?.setTint(Color.argb(220, 255, 255, 255))
        search.setCompoundDrawablesRelativeWithIntrinsicBounds(sIcon, null, null, null)
        search.compoundDrawablePadding = (12 * d).toInt()
        page.addView(search, LinearLayout.LayoutParams(mp, (52 * d).toInt()))

        val list = RecyclerView(activity)
        list.overScrollMode = View.OVER_SCROLL_NEVER
        list.clipToPadding = false
        list.setPadding(0, (16 * d).toInt(), 0, (24 * d).toInt())
        page.addView(list, LinearLayout.LayoutParams(mp, 0, 1f))

        var openGroup: Group? = null
        var query = ""

        fun groupMatches(g: Group): Boolean =
            query.isEmpty() || g.app.contains(query, true) || g.items.any { widgetLabel(pm, it).contains(query, true) }

        lateinit var showGroups: () -> Unit
        lateinit var showDetail: (Group) -> Unit

        // ---------- app cards (2 per row) ----------
        val tileH = ((dm.widthPixels - 40 * d - 12 * d) / 2f * 0.78f).toInt()

        val groupAdapter = object : RecyclerView.Adapter<GroupVH>() {
            var shown: List<Group> = groups

            @Suppress("NotifyDataSetChanged")
            fun refresh() {
                shown = groups.filter { groupMatches(it) }
                notifyDataSetChanged()
            }

            override fun getItemCount() = shown.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupVH {
                val box = LinearLayout(activity)
                box.orientation = LinearLayout.VERTICAL
                box.setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (14 * d).toInt())

                val plate = FrameLayout(activity)
                plate.background = round(Color.argb(60, 255, 255, 255), 30f * d)
                plate.setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
                val tile = FrameLayout(activity)
                tile.background = round(Color.rgb(0x22, 0x22, 0x22), 24f * d)
                val img = ImageView(activity)
                img.scaleType = ImageView.ScaleType.FIT_CENTER
                img.setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
                tile.addView(img, FrameLayout.LayoutParams(mp, mp))
                plate.addView(tile, FrameLayout.LayoutParams(mp, tileH))
                box.addView(plate, LinearLayout.LayoutParams(mp, wc))

                val row = LinearLayout(activity)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER
                row.setPadding(0, (10 * d).toInt(), 0, 0)
                val name = TextView(activity)
                name.setTextColor(Color.WHITE)
                name.textSize = 18f
                name.maxLines = 1
                name.ellipsize = TextUtils.TruncateAt.END
                val count = TextView(activity)
                count.setTextColor(Color.WHITE)
                count.textSize = 13f
                count.gravity = Gravity.CENTER
                count.background = round(Color.argb(70, 255, 255, 255), 12f * d)
                count.setPadding((9 * d).toInt(), (2 * d).toInt(), (9 * d).toInt(), (2 * d).toInt())
                row.addView(name, LinearLayout.LayoutParams(wc, wc))
                val clp = LinearLayout.LayoutParams(wc, wc)
                clp.marginStart = (8 * d).toInt()
                row.addView(count, clp)
                box.addView(row, LinearLayout.LayoutParams(mp, wc))
                box.layoutParams = RecyclerView.LayoutParams(mp, wc)
                return GroupVH(box, img, name, count)
            }

            override fun onBindViewHolder(h: GroupVH, position: Int) {
                val g = shown[position]
                // The first widget with a real preview image represents the app.
                val first = g.items.firstOrNull { hasPreview(activity, it) } ?: g.items[0]
                h.img.setImageDrawable(preview(activity, first))
                h.name.text = g.app
                h.count.text = g.items.size.toString()
                h.box.setOnClickListener { showDetail(g) }
            }
        }

        // ---------- the widgets of one app ----------
        val itemAdapter = object : RecyclerView.Adapter<ItemVH>() {
            var shown: List<AppWidgetProviderInfo> = emptyList()

            @Suppress("NotifyDataSetChanged")
            fun set(g: Group) {
                shown = g.items.filter {
                    query.isEmpty() || g.app.contains(query, true) || widgetLabel(pm, it).contains(query, true)
                }
                notifyDataSetChanged()
            }

            override fun getItemCount() = shown.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemVH {
                val box = LinearLayout(activity)
                box.orientation = LinearLayout.VERTICAL
                box.setPadding(0, 0, 0, (18 * d).toInt())
                val plate = FrameLayout(activity)
                plate.background = round(Color.argb(60, 255, 255, 255), 30f * d)
                plate.setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
                val tile = FrameLayout(activity)
                tile.background = round(Color.rgb(0x22, 0x22, 0x22), 24f * d)
                val img = ImageView(activity)
                img.scaleType = ImageView.ScaleType.FIT_CENTER
                img.setPadding((14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt(), (14 * d).toInt())
                tile.addView(img, FrameLayout.LayoutParams(mp, (190 * d).toInt()))
                plate.addView(tile, FrameLayout.LayoutParams(mp, wc))
                box.addView(plate, LinearLayout.LayoutParams(mp, wc))
                val name = TextView(activity)
                name.setTextColor(Color.WHITE)
                name.textSize = 17f
                name.gravity = Gravity.CENTER
                name.setPadding(0, (10 * d).toInt(), 0, 0)
                val size = TextView(activity)
                size.setTextColor(Color.argb(190, 255, 255, 255))
                size.textSize = 13f
                size.gravity = Gravity.CENTER
                box.addView(name, LinearLayout.LayoutParams(mp, wc))
                box.addView(size, LinearLayout.LayoutParams(mp, wc))
                box.layoutParams = RecyclerView.LayoutParams(mp, wc)
                return ItemVH(box, img, name, size)
            }

            override fun onBindViewHolder(h: ItemVH, position: Int) {
                val info = shown[position]
                h.img.setImageDrawable(preview(activity, info))
                h.name.text = widgetLabel(pm, info)
                val cw = ((info.minWidth / d + 30f) / 70f).toInt().coerceAtLeast(1)
                val ch = ((info.minHeight / d + 30f) / 70f).toInt().coerceAtLeast(1)
                h.size.text = "$cw \u00D7 $ch"
                h.box.setOnClickListener {
                    dialog.dismiss()
                    onPick(info)
                }
            }
        }

        showGroups = {
            openGroup = null
            title.text = "Widgets"
            title.setOnClickListener(null)
            title.isClickable = false
            list.layoutManager = GridLayoutManager(activity, 2)
            list.adapter = groupAdapter
            groupAdapter.refresh()
        }
        showDetail = { g ->
            openGroup = g
            title.text = "\u2039  " + g.app
            title.setOnClickListener { showGroups() }
            list.layoutManager = LinearLayoutManager(activity)
            list.adapter = itemAdapter
            itemAdapter.set(g)
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                val g = openGroup
                if (g != null) itemAdapter.set(g) else groupAdapter.refresh()
            }
        })

        dialog.setOnKeyListener { _, keyCode, ev ->
            if (keyCode == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP && openGroup != null) {
                showGroups()
                true
            } else {
                false
            }
        }

        showGroups()
        dialog.setContentView(root)
        dialog.show()
    }
}
