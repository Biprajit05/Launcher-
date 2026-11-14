package com.example.launcher

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/** The two icon-pack dialogs shared by the settings screen and the "Edit icon" menu. */
object IconDialogs {

    /**
     * Lists the installed icon packs. [onChosen] gets the package name, or null for "Default"
     * (only offered when [includeDefault] is true).
     */
    fun choosePack(ctx: Context, includeDefault: Boolean, onChosen: (String?) -> Unit) {
        val packs = IconManager.installedPacks(ctx)
        if (packs.isEmpty()) {
            AlertDialog.Builder(ctx)
                .setTitle("No icon packs found")
                .setMessage(
                    "Install an icon pack from Google Play (search for \"icon pack\"; ADW, Nova and Apex " +
                        "compatible packs work), then open this screen again."
                )
                .setPositiveButton("OK", null)
                .apply { if (includeDefault) setNegativeButton("Use default icons") { _, _ -> onChosen(null) } }
                .show()
            return
        }
        val rows = ArrayList<IconManager.PackInfo?>()
        if (includeDefault) rows.add(null)
        rows.addAll(packs)
        val selected = IconManager.selectedPack(ctx)
        AlertDialog.Builder(ctx)
            .setTitle("Icon pack")
            .setAdapter(PackAdapter(rows, selected)) { _, which -> onChosen(rows[which]?.pkg) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A grid with every icon of the pack; [onPicked] gets the drawable name. */
    fun pickPackIcon(ctx: Context, pkg: String, onPicked: (String) -> Unit) {
        val names = IconManager.packNames(ctx, pkg)
        if (names.isEmpty()) {
            Toast.makeText(ctx, "This icon pack has no icon list", Toast.LENGTH_SHORT).show()
            return
        }
        val d = ctx.resources.displayMetrics.density
        val grid = GridView(ctx)
        grid.numColumns = 4
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.verticalSpacing = (4 * d).toInt()
        grid.adapter = IconGridAdapter(ctx, pkg, names)
        val box = FrameLayout(ctx)
        box.minimumHeight = (320 * d).toInt()
        box.setPadding((12 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), 0)
        box.addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Pick an icon")
            .setView(box)
            .setNegativeButton("Cancel", null)
            .create()
        grid.setOnItemClickListener { _, _, position, _ ->
            dialog.dismiss()
            onPicked(names[position])
        }
        dialog.show()
    }

    private class PackAdapter(
        private val rows: List<IconManager.PackInfo?>,
        private val selected: String?
    ) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any? = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val c = parent.context
            val d = c.resources.displayMetrics.density
            val row: LinearLayout
            if (convertView is LinearLayout) {
                row = convertView
            } else {
                row = LinearLayout(c)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = android.view.Gravity.CENTER_VERTICAL
                row.setPadding((20 * d).toInt(), (10 * d).toInt(), (20 * d).toInt(), (10 * d).toInt())
                row.addView(ImageView(c), LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
                val t = TextView(c)
                t.textSize = 16f
                t.setPadding((16 * d).toInt(), 0, 0, 0)
                row.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val info = rows[position]
            val icon = row.getChildAt(0) as ImageView
            val text = row.getChildAt(1) as TextView
            if (info == null) {
                icon.visibility = View.INVISIBLE
                text.text = (if (selected == null) "✓  " else "") + "Default (system icons)"
            } else {
                icon.visibility = View.VISIBLE
                icon.setImageDrawable(info.icon)
                text.text = (if (info.pkg == selected) "✓  " else "") + info.label
            }
            return row
        }
    }

    private class IconGridAdapter(
        private val ctx: Context,
        private val pkg: String,
        private val names: List<String>
    ) : BaseAdapter() {
        override fun getCount() = names.size
        override fun getItem(position: Int): Any = names[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val d = ctx.resources.displayMetrics.density
            val v = (convertView as? ImageView) ?: ImageView(ctx).also {
                it.scaleType = ImageView.ScaleType.FIT_CENTER
                it.setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt())
                it.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * d).toInt())
            }
            v.setImageDrawable(IconManager.packDrawable(ctx, pkg, names[position]))
            return v
        }
    }
}
