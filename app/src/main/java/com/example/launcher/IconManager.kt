package com.example.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * Everything about custom icons and names:
 *  - third-party icon packs (ADW / Nova / Apex / Go / Lawnchair style, via appfilter.xml)
 *  - per-app icon override: an image from the gallery, or one icon picked from a pack
 *  - per-app custom name
 */
object IconManager {

    class PackInfo(val pkg: String, val label: String, val icon: Drawable)

    /** Intent actions icon packs use to announce themselves. Keep in sync with <queries> in the manifest. */
    private val PACK_ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.anddoes.launcher.THEME",
        "org.adw.launcher.icons.ACTION_PICK_ICON",
        "com.gau.go.launcherex.theme",
        "ch.deletescape.lawnchair.ICONPACK"
    )

    private class Pack(
        val pkg: String,
        val res: Resources,
        val map: HashMap<String, String>,   // flattened component -> drawable name
        val names: List<String>,            // every icon in the pack (for the picker)
        val backs: List<String>,            // <iconback> images (for apps the pack does not theme)
        val upon: String?,                  // <iconupon>
        val mask: String?,                  // <iconmask>
        val scale: Float                    // <scale factor>
    )

    private val packs = HashMap<String, Pack?>()
    private val fallbacks = LruCache<String, Bitmap>(64)
    private val gallery = LruCache<String, Bitmap>(64)

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("launcher_custom", Context.MODE_PRIVATE)

    // ---------- Change tracking ----------

    private fun bump(ctx: Context) {
        val s = sp(ctx)
        s.edit().putInt("rev", s.getInt("rev", 0) + 1).apply()
    }

    /** Changes whenever the icon pack or any custom icon changes. */
    fun signature(ctx: Context): String = "${selectedPack(ctx)}|${sp(ctx).getInt("rev", 0)}"

    @Synchronized
    fun invalidate() {
        packs.clear()
        fallbacks.evictAll()
        gallery.evictAll()
    }

    // ---------- Icon packs ----------

    fun installedPacks(ctx: Context): List<PackInfo> {
        val pm = ctx.packageManager
        val seen = LinkedHashMap<String, PackInfo>()
        for (action in PACK_ACTIONS) {
            val list = try { pm.queryIntentActivities(Intent(action), 0) } catch (e: Exception) { emptyList() }
            for (ri in list) {
                val app = ri.activityInfo.applicationInfo
                val pkg = app.packageName
                if (pkg == ctx.packageName || seen.containsKey(pkg)) continue
                seen[pkg] = PackInfo(pkg, app.loadLabel(pm).toString(), app.loadIcon(pm))
            }
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    fun selectedPack(ctx: Context): String? = sp(ctx).getString("pack", null)

    fun setSelectedPack(ctx: Context, pkg: String?) {
        val e = sp(ctx).edit()
        if (pkg == null) e.remove("pack") else e.putString("pack", pkg)
        e.apply()
        bump(ctx)
    }

    fun selectedPackLabel(ctx: Context): String {
        val pkg = selectedPack(ctx) ?: return "Default (system icons)"
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            "Default (icon pack not installed)"
        }
    }

    /** Parses the selected pack now (call off the UI thread) so binding icons later is instant. */
    fun warm(ctx: Context) {
        selectedPack(ctx)?.let { pack(ctx, it) }
    }

    fun packNames(ctx: Context, pkg: String): List<String> = pack(ctx, pkg)?.names ?: emptyList()

    fun packDrawable(ctx: Context, pkg: String, name: String): Drawable? =
        pack(ctx, pkg)?.let { load(it, name) }

    @Synchronized
    private fun pack(ctx: Context, pkg: String): Pack? {
        if (packs.containsKey(pkg)) return packs[pkg]
        val p = try { parsePack(ctx, pkg) } catch (e: Exception) { null }
        packs[pkg] = p
        return p
    }

    private fun parsePack(ctx: Context, pkg: String): Pack? {
        val res = ctx.packageManager.getResourcesForApplication(pkg)
        val map = HashMap<String, String>()
        val fromFilter = LinkedHashSet<String>()
        val backs = ArrayList<String>()
        var upon: String? = null
        var mask: String? = null
        var scale = 1f

        val filter = openParser(res, pkg, "appfilter") ?: return null
        try {
            var ev = filter.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    val tag = filter.name
                    if (tag == "item") {
                        val comp = attr(filter, res, "component")
                        val drawable = attr(filter, res, "drawable")
                        if (comp != null && drawable != null && comp.startsWith("ComponentInfo{")) {
                            val cn = ComponentName.unflattenFromString(comp.removePrefix("ComponentInfo{").removeSuffix("}"))
                            if (cn != null) {
                                map[cn.flattenToString()] = drawable
                                fromFilter.add(drawable)
                            }
                        }
                    } else if (tag == "iconback") {
                        for (i in 0 until filter.attributeCount) {
                            if (filter.getAttributeName(i).startsWith("img")) {
                                val v = attrValue(filter, res, i)
                                if (v != null) backs.add(v)
                            }
                        }
                    } else if (tag == "iconupon") {
                        upon = attr(filter, res, "img1")
                    } else if (tag == "iconmask") {
                        mask = attr(filter, res, "img1")
                    } else if (tag == "scale") {
                        val f = attr(filter, res, "factor")?.toFloatOrNull()
                        if (f != null && f > 0f) scale = f
                    }
                }
                ev = filter.next()
            }
        } finally {
            (filter as? XmlResourceParser)?.close()
        }

        // Many packs also ship a full icon list (drawable.xml); use it for the picker when present.
        val all = LinkedHashSet<String>()
        val list = openParser(res, pkg, "drawable")
        if (list != null) {
            try {
                var ev = list.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && list.name == "item") {
                        val d = attr(list, res, "drawable")
                        if (d != null) all.add(d)
                    }
                    ev = list.next()
                }
            } catch (e: Exception) { /* optional file */ } finally {
                (list as? XmlResourceParser)?.close()
            }
        }
        all.addAll(fromFilter)

        return Pack(pkg, res, map, all.toList(), backs, upon, mask, scale)
    }

    /** res/xml/<name>.xml first, then assets/<name>.xml. */
    private fun openParser(res: Resources, pkg: String, name: String): XmlPullParser? {
        val id = res.getIdentifier(name, "xml", pkg)
        if (id != 0) return try { res.getXml(id) } catch (e: Exception) { null }
        return try {
            val bytes = res.assets.open("$name.xml").use { it.readBytes() }
            val p = Xml.newPullParser()
            p.setInput(ByteArrayInputStream(bytes), null)
            p
        } catch (e: Exception) {
            null
        }
    }

    private fun attrValue(p: XmlPullParser, res: Resources, i: Int): String? {
        val v = p.getAttributeValue(i) ?: return null
        if (!v.startsWith("@")) return v
        val rest = v.substring(1)
        val id = rest.toIntOrNull()
        if (id != null) {
            return try { res.getResourceEntryName(id) } catch (e: Exception) { null }
        }
        return rest.substringAfterLast('/')
    }

    private fun attr(p: XmlPullParser, res: Resources, name: String): String? {
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i) == name) return attrValue(p, res, i)
        }
        return null
    }

    private fun load(p: Pack, name: String): Drawable? {
        var id = p.res.getIdentifier(name, "drawable", p.pkg)
        if (id == 0) id = p.res.getIdentifier(name, "mipmap", p.pkg)
        if (id == 0) return null
        return try { p.res.getDrawable(id, null) } catch (e: Exception) { null }
    }

    /**
     * The icon-pack icon for this app, or null to use the normal icon.
     * Order: icon picked for this app from a pack -> selected pack's own icon -> pack background
     * composed behind the original icon (only if the pack ships <iconback> / <iconupon> / <iconmask>).
     */
    fun packIcon(ctx: Context, app: AppInfo): Drawable? {
        val flat = app.component.flattenToString()
        val ov = sp(ctx).getString("icon:$flat", null)
        if (ov != null && ov.startsWith("p:")) {
            val spec = ov.substring(2)
            val pkg = spec.substringBefore('/')
            val name = spec.substringAfter('/')
            val p = pack(ctx, pkg)
            if (p != null) load(p, name)?.let { return it }
        }
        val sel = selectedPack(ctx) ?: return null
        val p = pack(ctx, sel) ?: return null
        val name = p.map[flat]
        if (name != null) load(p, name)?.let { return it }
        return composeFallback(ctx, p, app)
    }

    private fun composeFallback(ctx: Context, p: Pack, app: AppInfo): Drawable? {
        if (p.backs.isEmpty() && p.upon == null && p.mask == null) return null
        val flat = app.component.flattenToString()
        val key = p.pkg + "#" + flat
        val hit = fallbacks.get(key)
        if (hit != null) return BitmapDrawable(ctx.resources, hit)

        val px = (64 * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(96)
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)

        if (p.backs.isNotEmpty()) {
            val back = p.backs[(flat.hashCode() and 0x7fffffff) % p.backs.size]
            load(p, back)?.let { it.setBounds(0, 0, px, px); it.draw(c) }
        }

        val inner = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val ic = Canvas(inner)
        val s = (px * p.scale).toInt().coerceIn(1, px)
        val off = (px - s) / 2
        val src = app.icon.constantState?.newDrawable()?.mutate() ?: app.icon
        src.setBounds(off, off, off + s, off + s)
        src.draw(ic)
        val maskName = p.mask
        if (maskName != null) {
            val md = load(p, maskName)
            if (md != null) {
                val layer = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
                md.setBounds(0, 0, px, px)
                md.draw(Canvas(layer))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
                ic.drawBitmap(layer, 0f, 0f, paint)
                layer.recycle()
            }
        }
        c.drawBitmap(inner, 0f, 0f, null)
        inner.recycle()

        val uponName = p.upon
        if (uponName != null) load(p, uponName)?.let { it.setBounds(0, 0, px, px); it.draw(c) }

        fallbacks.put(key, out)
        return BitmapDrawable(ctx.resources, out)
    }

    // ---------- Per-app icon (gallery / pack pick) ----------

    fun hasIconOverride(ctx: Context, comp: ComponentName): Boolean =
        sp(ctx).contains("icon:" + comp.flattenToString())

    fun setPackIconOverride(ctx: Context, comp: ComponentName, pkg: String, name: String) {
        val flat = comp.flattenToString()
        deleteGalleryFile(ctx, flat)
        gallery.remove(flat)
        sp(ctx).edit().putString("icon:$flat", "p:$pkg/$name").apply()
        bump(ctx)
    }

    fun clearIconOverride(ctx: Context, comp: ComponentName) {
        val flat = comp.flattenToString()
        deleteGalleryFile(ctx, flat)
        gallery.remove(flat)
        sp(ctx).edit().remove("icon:$flat").apply()
        bump(ctx)
    }

    /** The picked gallery image for this app, or null. */
    fun galleryIcon(ctx: Context, comp: ComponentName): Bitmap? {
        val flat = comp.flattenToString()
        if (sp(ctx).getString("icon:$flat", null) != "g") return null
        val cached = gallery.get(flat)
        if (cached != null) return cached
        val f = iconFile(ctx, flat)
        if (!f.exists()) return null
        val b = BitmapFactory.decodeFile(f.path) ?: return null
        gallery.put(flat, b)
        return b
    }

    /** Reads the picked image, crops the centre square and stores it as this app's icon. Call off the UI thread. */
    fun saveGalleryIcon(ctx: Context, comp: ComponentName, uri: Uri): Boolean {
        return try {
            var src = decodeSampled(ctx, uri, 600) ?: return false
            val rot = exifRotation(ctx, uri)
            if (rot != 0) {
                src = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            }
            val side = min(src.width, src.height)
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val out = Bitmap.createScaledBitmap(square, 256, 256, true)
            val flat = comp.flattenToString()
            FileOutputStream(iconFile(ctx, flat)).use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            gallery.remove(flat)
            sp(ctx).edit().putString("icon:$flat", "g").apply()
            bump(ctx)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun decodeSampled(ctx: Context, uri: Uri, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun exifRotation(ctx: Context, uri: Uri): Int = try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    } catch (e: Exception) {
        0
    }

    private fun iconFile(ctx: Context, flat: String): File {
        val dir = File(ctx.filesDir, "icons")
        dir.mkdirs()
        return File(dir, flat.replace(Regex("[^A-Za-z0-9._]"), "_") + ".png")
    }

    private fun deleteGalleryFile(ctx: Context, flat: String) {
        try { iconFile(ctx, flat).delete() } catch (e: Exception) { /* ignore */ }
    }

    // ---------- Custom names ----------

    fun customLabel(ctx: Context, comp: ComponentName): String? =
        sp(ctx).getString("label:" + comp.flattenToString(), null)

    fun setCustomLabel(ctx: Context, comp: ComponentName, label: String?) {
        val e = sp(ctx).edit()
        val key = "label:" + comp.flattenToString()
        if (label.isNullOrBlank()) e.remove(key) else e.putString(key, label.trim())
        e.apply()
    }

    // ---------- Reset ----------

    /** Removes every custom icon and name (the selected icon pack stays). */
    fun resetAll(ctx: Context) {
        val s = sp(ctx)
        val e = s.edit()
        for (k in s.all.keys) if (k.startsWith("icon:") || k.startsWith("label:")) e.remove(k)
        e.apply()
        File(ctx.filesDir, "icons").listFiles()?.forEach { it.delete() }
        gallery.evictAll()
        bump(ctx)
    }
}
