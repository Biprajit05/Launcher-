package com.example.launcher

import android.content.ComponentName
import org.json.JSONArray
import org.json.JSONObject

/** Apps grouped into a folder on the home screen. */
class FolderData(var name: String, val apps: MutableList<AppInfo> = ArrayList())

/**
 * One thing on the home screen: an app, a folder or a widget.
 * It lives on [page] at cell ([col], [row]) and covers [spanC] x [spanR] cells.
 */
class HomeItem(
    val kind: Int,
    var page: Int,
    var col: Int,
    var row: Int,
    var spanC: Int = 1,
    var spanR: Int = 1,
    val app: AppInfo? = null,
    val folder: FolderData? = null,
    val widgetId: Int = -1
) {
    companion object {
        const val APP = 0
        const val FOLDER = 1
        const val WIDGET = 2
    }
}

/** Saves / loads the home screen (all pages) as JSON. */
object HomeStore {

    class Decoded(val items: List<HomeItem>, val pages: Int)

    fun encode(items: List<HomeItem>, pages: Int): String {
        val arr = JSONArray()
        for (m in items) {
            val o = JSONObject()
            o.put("p", m.page)
            o.put("x", m.col)
            o.put("y", m.row)
            when (m.kind) {
                HomeItem.APP -> {
                    val app = m.app ?: continue
                    o.put("t", "a")
                    o.put("c", app.component.flattenToString())
                }
                HomeItem.FOLDER -> {
                    val f = m.folder ?: continue
                    o.put("t", "f")
                    o.put("n", f.name)
                    val a = JSONArray()
                    for (app in f.apps) a.put(app.component.flattenToString())
                    o.put("a", a)
                }
                else -> {
                    o.put("t", "w")
                    o.put("id", m.widgetId)
                    o.put("w", m.spanC)
                    o.put("h", m.spanR)
                }
            }
            arr.put(o)
        }
        val root = JSONObject()
        root.put("n", pages)
        root.put("i", arr)
        return root.toString()
    }

    fun decode(s: String, apps: List<AppInfo>): Decoded {
        val byComp = HashMap<ComponentName, AppInfo>()
        for (a in apps) byComp[a.component] = a
        val out = ArrayList<HomeItem>()
        var pages = 1
        try {
            val root = JSONObject(s)
            pages = root.optInt("n", 1).coerceAtLeast(1)
            val arr = root.optJSONArray("i") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = o.optInt("p", 0)
                val x = o.optInt("x", 0)
                val y = o.optInt("y", 0)
                when (o.optString("t")) {
                    "a" -> {
                        val comp = ComponentName.unflattenFromString(o.optString("c")) ?: continue
                        val app = byComp[comp] ?: continue
                        out.add(HomeItem(HomeItem.APP, p, x, y, app = app))
                    }
                    "f" -> {
                        val list = ArrayList<AppInfo>()
                        val a = o.optJSONArray("a") ?: JSONArray()
                        for (k in 0 until a.length()) {
                            val comp = ComponentName.unflattenFromString(a.optString(k)) ?: continue
                            val app = byComp[comp] ?: continue
                            if (list.none { it.component == comp }) list.add(app)
                        }
                        if (list.size >= 2) {
                            out.add(HomeItem(HomeItem.FOLDER, p, x, y, folder = FolderData(o.optString("n", "Folder"), list)))
                        } else if (list.size == 1) {
                            out.add(HomeItem(HomeItem.APP, p, x, y, app = list[0]))
                        }
                    }
                    "w" -> {
                        val id = o.optInt("id", -1)
                        if (id >= 0) {
                            out.add(HomeItem(HomeItem.WIDGET, p, x, y, o.optInt("w", 1), o.optInt("h", 1), widgetId = id))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // corrupt data: fall through with whatever was read
        }
        return Decoded(out, pages)
    }

    /** Old single-page format: "component,col,row|component,col,row". */
    fun migrate(old: String, apps: List<AppInfo>): List<HomeItem> {
        val out = ArrayList<HomeItem>()
        if (old.isEmpty()) return out
        for (part in old.split("|")) {
            val f = part.split(",")
            if (f.size != 3) continue
            val comp = ComponentName.unflattenFromString(f[0]) ?: continue
            val app = apps.firstOrNull { it.component == comp } ?: continue
            val c = f[1].toIntOrNull() ?: continue
            val r = f[2].toIntOrNull() ?: continue
            out.add(HomeItem(HomeItem.APP, 0, c, r, app = app))
        }
        return out
    }
}
