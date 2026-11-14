package com.example.launcher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import kotlin.math.min

/**
 * Dark rounded long-press menu with an arrow that points at the icon:
 * app name + (i) button on top, then rows grouped by dividers.
 */
class AppPopup(private val ctx: Context) {

    class Item(
        val icon: Int,
        val title: String,
        val danger: Boolean = false,
        val onClick: () -> Unit
    )

    /**
     * @param parent a view attached to the window (used as the popup's window token)
     * @param anchor the icon the menu belongs to
     * @param groups rows; a divider is drawn between groups
     * @param onInfo called when the (i) button is tapped
     */
    fun show(parent: View, anchor: View, title: String, groups: List<List<Item>>, onInfo: () -> Unit) {
        val dm = ctx.resources.displayMetrics
        val d = dm.density
        fun dp(v: Float): Int = (v * d).toInt()

        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val panelW = min(dp(232f), screenW - dp(24f))
        val arrowW = dp(22f)
        val arrowH = dp(10f)
        var window: PopupWindow? = null

        // ----- panel -----
        val panel = LinearLayout(ctx)
        panel.orientation = LinearLayout.VERTICAL
        panel.background = GradientDrawable().apply {
            setColor(PANEL)
            setCornerRadius(dp(26f).toFloat())
        }
        panel.clipToOutline = true
        panel.setPadding(0, dp(6f), 0, dp(8f))

        // header: app name + info button
        val header = LinearLayout(ctx)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.setPadding(dp(22f), dp(4f), dp(14f), dp(2f))
        val name = TextView(ctx)
        name.text = title
        name.setTextColor(Color.argb(0xCC, 0xFF, 0xFF, 0xFF))
        name.textSize = 15f
        name.maxLines = 1
        name.ellipsize = TextUtils.TruncateAt.END
        header.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val info = ImageView(ctx)
        info.setImageResource(R.drawable.ic_pop_info)
        info.setColorFilter(Color.WHITE)
        info.setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        info.background = GradientDrawable().apply {
            setShape(GradientDrawable.OVAL)
            setColor(Color.argb(0x33, 0xFF, 0xFF, 0xFF))
        }
        info.contentDescription = "App info"
        info.setOnClickListener { window?.dismiss(); onInfo() }
        header.addView(info, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // rows
        var groupIndex = 0
        for (items in groups) {
            if (items.isEmpty()) continue
            if (groupIndex > 0) {
                val line = View(ctx)
                line.setBackgroundColor(Color.argb(0x2E, 0xFF, 0xFF, 0xFF))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(1f)))
                lp.setMargins(dp(8f), dp(6f), dp(8f), dp(6f))
                panel.addView(line, lp)
            }
            groupIndex++
            for (item in items) {
                val tint = if (item.danger) DANGER else Color.WHITE
                val row = LinearLayout(ctx)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(dp(22f), 0, dp(18f), 0)
                row.background = RippleDrawable(
                    ColorStateList.valueOf(Color.argb(0x40, 0xFF, 0xFF, 0xFF)), null, ColorDrawable(Color.WHITE)
                )
                val icon = ImageView(ctx)
                icon.setImageResource(item.icon)
                icon.setColorFilter(tint)
                row.addView(icon, LinearLayout.LayoutParams(dp(26f), dp(26f)))
                val text = TextView(ctx)
                text.text = item.title
                text.setTextColor(tint)
                text.textSize = 17f
                text.maxLines = 1
                text.ellipsize = TextUtils.TruncateAt.END
                val tlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                tlp.marginStart = dp(18f)
                row.addView(text, tlp)
                row.setOnClickListener { window?.dismiss(); item.onClick() }
                panel.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52f)))
            }
        }

        panel.measure(
            View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val totalH = panel.measuredHeight + arrowH

        // ----- placement: above the icon if it fits, otherwise below -----
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val cx = loc[0] + anchor.width / 2
        val iconTop = loc[1]
        val iconBottom = loc[1] + anchor.height
        val gap = dp(4f)
        val above = iconTop - totalH - gap >= dp(32f)
        val y = if (above) iconTop - totalH - gap
        else (iconBottom + gap).coerceAtMost(screenH - totalH - dp(8f)).coerceAtLeast(dp(8f))
        val x = (cx - panelW / 2).coerceIn(dp(12f), maxOf(dp(12f), screenW - panelW - dp(12f)))
        val arrowCx = (cx - x).coerceIn(dp(30f), maxOf(dp(30f), panelW - dp(30f)))

        // ----- arrow -----
        val arrow = object : View(ctx) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL }
            private val path = Path()
            override fun onDraw(canvas: Canvas) {
                val w = width.toFloat()
                val h = height.toFloat()
                path.reset()
                if (above) { // popup sits above the icon: arrow points down
                    path.moveTo(0f, 0f); path.lineTo(w, 0f); path.lineTo(w / 2f, h)
                } else {     // popup sits below the icon: arrow points up
                    path.moveTo(0f, h); path.lineTo(w, h); path.lineTo(w / 2f, 0f)
                }
                path.close()
                canvas.drawPath(path, paint)
            }
        }
        val arrowLp = LinearLayout.LayoutParams(arrowW, arrowH)
        arrowLp.leftMargin = arrowCx - arrowW / 2
        val panelLp = LinearLayout.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT)

        val container = LinearLayout(ctx)
        container.orientation = LinearLayout.VERTICAL
        if (above) {
            container.addView(panel, panelLp)
            container.addView(arrow, arrowLp)
        } else {
            container.addView(arrow, arrowLp)
            container.addView(panel, panelLp)
        }

        val pw = PopupWindow(container, panelW, totalH, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        window = pw

        container.pivotX = arrowCx.toFloat()
        container.pivotY = if (above) totalH.toFloat() else 0f
        container.scaleX = 0.85f
        container.scaleY = 0.85f
        container.alpha = 0f
        pw.showAtLocation(parent, Gravity.TOP or Gravity.START, x, y)
        container.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(140).setInterpolator(DecelerateInterpolator()).start()
    }

    private companion object {
        val PANEL: Int = Color.argb(0xF5, 0x0C, 0x10, 0x13)
        val DANGER: Int = Color.rgb(0xF2, 0x8B, 0x82)
    }
}
