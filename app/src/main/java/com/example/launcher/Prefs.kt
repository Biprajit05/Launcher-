package com.example.launcher

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)

    /** 0 = scale from icon (system), 1 = fade, 2 = slide up, 3 = expand window from icon (default) */
    var style: Int
        get() = sp.getInt("style", 3)
        set(v) = sp.edit().putInt("style", v).apply()

    /** 250 / 450 / 800 ms */
    var speed: Int
        get() = sp.getInt("speed", 450)
        set(v) = sp.edit().putInt("speed", v).apply()

    /** 4 or 5 */
    var columns: Int
        get() = sp.getInt("columns", 4)
        set(v) = sp.edit().putInt("columns", v).apply()

    /** 0 original, 1 circle, 2 squircle, 3 rounded square, 4 square, 5 teardrop */
    var shape: Int
        get() = sp.getInt("shape", 0)
        set(v) = sp.edit().putInt("shape", v).apply()

    /** Icon size in percent, 60..140 */
    var iconPercent: Int
        get() = sp.getInt("icon_percent", 100)
        set(v) = sp.edit().putInt("icon_percent", v).apply()

    val scale: Float get() = iconPercent / 100f

    /** App label size in percent, 50..150 (home screen + app drawer). */
    var labelPercent: Int
        get() = sp.getInt("label_percent", 100)
        set(v) = sp.edit().putInt("label_percent", v).apply()

    val labelScale: Float get() = labelPercent / 100f

    /** Home screen grid: 0 = 4x6, 1 = 4x7, 2 = 5x6 (columns x rows). */
    var homeGrid: Int
        get() = sp.getInt("home_grid", 0)
        set(v) = sp.edit().putInt("home_grid", v).apply()

    val homeCols: Int get() = if (homeGrid == 2) 5 else 4
    val homeRows: Int get() = if (homeGrid == 1) 7 else 6

    /** 1.0 at "Normal" */
    val factor: Float get() = speed / 450f

    /** Show the dock at the bottom of the home screen. */
    var dockEnabled: Boolean
        get() = sp.getBoolean("dock_enabled", true)
        set(v) = sp.edit().putBoolean("dock_enabled", v).apply()

    /** App drawer background colour: 0 = auto (follows the wallpaper), 1 = light, 2 = dark. */
    var drawerColor: Int
        get() = sp.getInt("drawer_color", 0)
        set(v) = sp.edit().putInt("drawer_color", v).apply()

    /** 0 = opaque .. 100 = transparent. */
    var drawerTransparency: Int
        get() = sp.getInt("drawer_transparency", DEFAULT_TRANSPARENCY)
        set(v) = sp.edit().putInt("drawer_transparency", v).apply()

    /** 0 = clear .. 100 = blurry. */
    var drawerBlur: Int
        get() = sp.getInt("drawer_blur", DEFAULT_BLUR)
        set(v) = sp.edit().putInt("drawer_blur", v).apply()

    companion object {
        const val DEFAULT_TRANSPARENCY = 85
        const val DEFAULT_BLUR = 55
    }

    /** Draw the translucent panel behind the dock icons. */
    var dockBackground: Boolean
        get() = sp.getBoolean("dock_background", true)
        set(v) = sp.edit().putBoolean("dock_background", v).apply()

    /** Saved dock apps as "component|component|...". null = default dock. */
    fun getDock(): String? = sp.getString("dock_apps", null)

    fun setDock(value: String?) {
        val e = sp.edit()
        if (value == null) e.remove("dock_apps") else e.putString("dock_apps", value)
        e.apply()
    }

    /** Saved home screen layout. null = never customised (use the default). */
    fun getHomeLayout(): String? = sp.getString("home_layout", null)

    fun setHomeLayout(value: String?) {
        val e = sp.edit()
        if (value == null) e.remove("home_layout") else e.putString("home_layout", value)
        e.apply()
    }
}
