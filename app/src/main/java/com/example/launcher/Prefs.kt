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

    /** 1.0 at "Normal" */
    val factor: Float get() = speed / 450f
}
