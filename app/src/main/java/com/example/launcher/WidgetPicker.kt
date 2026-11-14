package com.example.launcher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** A list of every widget installed on the phone; tap one to add it. */
object WidgetPicker {

    private fun widgetLabel(pm: PackageManager, info: AppWidgetProviderInfo): String =
        try { info.loadLabel(pm) } catch (e: Exception) { info.provider.shortClassName }

    private fun appName(pm: PackageManager, info: AppWidgetProviderInfo): String =
        try {
            info.providerInfo.applicationInfo.loadLabel(pm).toString()
        } catch (e: Exception) {
            info.provider.packageName
        }

    private fun widgetIcon(activity: Activity, info: AppWidgetProviderInfo): Drawable? =
        try { info.loadIcon(activity, activity.resources.displayMetrics.densityDpi) } catch (e: Exception) { null }

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
        val list = providers.sortedWith(
            compareBy<AppWidgetProviderInfo>({ appName(pm, it).lowercase() }, { widgetLabel(pm, it).lowercase() })
        )

        val builder = AlertDialog.Builder(activity)
        val ctx = builder.context
        val d = ctx.resources.displayMetrics.density
        val tv = TypedValue()
        ctx.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
        val primary = if (tv.resourceId != 0) ctx.getColor(tv.resourceId) else tv.data
        val secondary = Color.argb(0xB0, Color.red(primary), Color.green(primary), Color.blue(primary))

        val adapter = object : BaseAdapter() {
            override fun getCount() = list.size
            override fun getItem(position: Int): Any = list[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val info = list[position]
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding((20 * d).toInt(), (10 * d).toInt(), (20 * d).toInt(), (10 * d).toInt())

                val iv = ImageView(ctx)
                iv.setImageDrawable(widgetIcon(activity, info))
                row.addView(iv, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))

                val col = LinearLayout(ctx)
                col.orientation = LinearLayout.VERTICAL
                val t1 = TextView(ctx)
                t1.text = widgetLabel(pm, info)
                t1.textSize = 16f
                t1.setTextColor(primary)
                t1.maxLines = 1
                val t2 = TextView(ctx)
                val cw = ((info.minWidth / d + 30f) / 70f).toInt().coerceAtLeast(1)
                val ch = ((info.minHeight / d + 30f) / 70f).toInt().coerceAtLeast(1)
                t2.text = appName(pm, info) + "  \u00B7  " + cw + " \u00D7 " + ch
                t2.textSize = 12f
                t2.setTextColor(secondary)
                t2.maxLines = 1
                col.addView(t1)
                col.addView(t2)
                val clp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                clp.marginStart = (16 * d).toInt()
                row.addView(col, clp)
                return row
            }
        }

        builder.setTitle("Widgets")
            .setAdapter(adapter) { _, which -> onPick(list[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
