package com.example.launcher

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val prefs: Prefs,
    private val columns: () -> Int,
    private val onWallpaper: Boolean,
    private val showLabels: Boolean,
    private val onClick: (AppInfo, View) -> Unit,
    private val onLongClick: (AppInfo, View) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private var items: List<AppInfo> = emptyList()

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
            v.setOnClickListener { pos()?.let { onClick(items[it], icon) } }
            v.setOnLongClickListener { pos()?.let { onLongClick(items[it], v) }; true }
        }

        private fun pos() = bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = items[position]
        holder.itemView.animate().cancel()
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
        IconStyle.bindIcon(holder.icon, holder.label, app, prefs.shape, prefs.scale, columns())
    }

    override fun getItemCount() = items.size
}
