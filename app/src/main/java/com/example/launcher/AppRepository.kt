package com.example.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

object AppRepository {

    /** Call from a background thread (loads icons). */
    fun loadApps(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        IconManager.warm(ctx) // parse the selected icon pack here, not on the UI thread
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map {
                val ai = it.activityInfo
                val comp = ComponentName(ai.packageName, ai.name)
                val systemLabel = it.loadLabel(pm).toString()
                AppInfo(
                    IconManager.customLabel(ctx, comp) ?: systemLabel,
                    ai.packageName,
                    comp,
                    it.loadIcon(pm),
                    systemLabel
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Dock = default phone, messages, browser, camera apps; padded with the first apps if missing. */
    fun pickDock(ctx: Context, apps: List<AppInfo>): List<AppInfo> {
        val pm = ctx.packageManager
        val probes = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
        val result = LinkedHashMap<String, AppInfo>()
        for (p in probes) {
            val pkg = pm.resolveActivity(p, 0)?.activityInfo?.packageName ?: continue
            apps.firstOrNull { it.packageName == pkg }?.let { result[pkg] = it }
        }
        for (a in apps) { if (result.size >= 4) break; result.putIfAbsent(a.packageName, a) }
        return result.values.take(4)
    }
}
