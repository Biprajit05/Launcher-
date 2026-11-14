package com.example.launcher

import android.content.ComponentName
import android.graphics.drawable.Drawable

data class AppInfo(
    /** Name shown to the user (the custom name if one was set). */
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val icon: Drawable,
    /** The app's real name, without any rename. */
    val systemLabel: String = label
)
