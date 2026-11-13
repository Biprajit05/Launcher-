package com.example.launcher

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)

    /** 0 = scale from icon, 1 = fade, 2 = slide up */
    var style: Int
        get() = sp.getInt("style", 0)
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

    /** 1.0 at "Normal" */
    val factor: Float get() = speed / 450f

    /** Saved home screen layout. null = never customised (use the default). */
    fun getHomeLayout(): String? = sp.getString("home_layout", null)

    fun setHomeLayout(value: String?) {
        val e = sp.edit()
        if (value == null) e.remove("home_layout") else e.putString("home_layout", value)
        e.apply()
    }
}
