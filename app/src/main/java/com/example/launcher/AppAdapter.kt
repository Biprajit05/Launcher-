package com.example.launcher

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val prefs: Prefs,
    private val columns: () -> Int,
    private val onWallpaper: Boolean,
    private val showLabels: Boolean,
    private val onClick: (AppInfo, View) -> Unit,
    private val onLongClick: (AppInfo, View) -> Unit,
    /** Dock only: every cell has exactly this width (px), so the row can be centred. */
    private val cellWidth: (() -> Int)? = null
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private var items: List<AppInfo> = emptyList()
    private var recycler: RecyclerView? = null
    private var lastWidth = -1

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        recycler = recyclerView
        // Re-size the icons once the list knows its real width, so big icons never get cut off.
        if (cellWidth == null) recyclerView.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val w = r - l
            if (w != lastWidth) {
                lastWidth = w
                if (items.isNotEmpty()) recyclerView.post { notifyDataSetChanged() }
            }
        }
    }

    /** Widest icon that fits one column of this list (0 padding is already excluded). */
    private fun maxIconPx(): Int {
        cellWidth?.let { return it() - (4f * (recycler?.resources?.displayMetrics?.density ?: 3f)).toInt() }
        val rv = recycler ?: return Int.MAX_VALUE
        val w = rv.width - rv.paddingLeft - rv.paddingRight
        if (w <= 0) return Int.MAX_VALUE
        val colW = w / columns().coerceAtLeast(1)
        return (colW - 6 * rv.resources.displayMetrics.density).toInt()
    }
    private var textColor: Int? = null

    /** Label colour for the app drawer (light/dark drawer background). */
    @SuppressLint("NotifyDataSetChanged")
    fun setTextColor(c: Int) {
        if (textColor == c) return
        textColor = c
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<AppInfo>) {
        items = list
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val label: TextView = v.findViewById(R.id.label)

        init {
            if (onWallpaper) {
                label.setTextColor(Color.WHITE)
                label.setShadowLayer(4f, 0f, 1f, Color.BLACK)
            }
            if (!showLabels) label.visibility = View.GONE
            // Press effect: 0.88 on down, 1.0 on release
            v.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).withLayer().start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f).setDuration(120).withLayer().start()
                }
                false
            }
            v.setOnClickListener { pos()?.let { if (items[it].packageName != AppInfo.GAP_PKG) onClick(items[it], icon) } }
            v.setOnLongClickListener { pos()?.let { if (items[it].packageName != AppInfo.GAP_PKG) onLongClick(items[it], v) }; true }
        }

        private fun pos() = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        val cw = cellWidth
        if (cw != null) {
            val pv = v.paddingTop
            v.setPadding(0, pv, 0, pv)
            v.layoutParams = RecyclerView.LayoutParams(cw(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return VH(v)
    }

    /** Like [submit] but items slide to their new places (used by the dock). */
    fun submitAnimated(list: List<AppInfo>) {
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].component == list[n].component
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == list[n]
        })
        items = list
        diff.dispatchUpdatesTo(this)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        if (app.packageName == AppInfo.GAP_PKG) {
            holder.icon.setImageDrawable(null)
            holder.label.text = ""
            return
        }
        holder.itemView.animate().cancel()
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
        IconStyle.bindIcon(holder.icon, holder.label, app, prefs.shape, prefs.scale, columns(), prefs.labelScale, maxIconPx())
        if (!onWallpaper) textColor?.let { holder.label.setTextColor(it) }
    }

    override fun getItemCount() = items.size
}
