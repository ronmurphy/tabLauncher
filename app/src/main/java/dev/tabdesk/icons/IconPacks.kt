package dev.tabdesk.icons

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.LruCache
import android.util.Xml
import dev.tabdesk.*
import java.io.StringReader
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.abs
import org.xmlpull.v1.XmlPullParser

/**
 * Third-party icon packs in the common ADW / Nova format: an app that lists which of its drawables
 * stands for which app (appfilter.xml), optionally with layers for theming apps it doesn't cover
 * (iconback, iconmask, iconupon, scale) and a list of all its icons (drawable.xml).
 */
object IconPacks {
    /** How packs announce themselves; most use several of these. */
    private val ACTIONS = listOf(
        "org.adw.launcher.THEMES", "com.teslacoilsw.launcher.THEME", "com.gau.go.launcherex.theme",
        "com.anddoes.launcher.THEME", "com.fede.launcher.THEME_ICONPACK", "com.dlto.atom.launcher.THEME",
        "org.adw.launcher.icons.ACTION_PICK_ICON",
    )
    private val CATEGORIES = listOf("com.novalauncher.THEME", "com.teslacoilsw.launcher.THEME", "com.anddoes.launcher.THEME")

    class Pack(val pkg: String, val label: String)

    @Volatile private var installedCache: List<Pack>? = null

    /** Forget what's installed and loaded (called when apps change). */
    fun invalidate() {
        installedCache = null
        synchronized(this) { loaded.clear() }
        thumbs.evictAll()
        missing.clear()
    }

    fun installed(ctx: Context): List<Pack> {
        installedCache?.let { return it }
        val pm = ctx.packageManager
        val pkgs = LinkedHashSet<String>()
        val intents = ACTIONS.map { Intent(it) } + CATEGORIES.map { Intent(Intent.ACTION_MAIN).addCategory(it) }
        for (i in intents) try { pm.queryIntentActivities(i, 0).forEach { pkgs += it.activityInfo.packageName } } catch (e: Exception) { }
        pkgs.remove(ctx.packageName)
        val list = pkgs.mapNotNull { p ->
            try { Pack(p, pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString()) } catch (e: Exception) { null }
        }.sortedBy { it.label.lowercase() }
        installedCache = list
        return list
    }

    // ------------------------------------------------------------ reading a pack

    private class Loaded(
        val pkg: String, val res: Resources,
        val byComponent: Map<String, String>, val byPackage: Map<String, String>,
        val calendars: Map<String, String>, val calendarsByPackage: Map<String, String>,
        val backs: List<Int>, val mask: Int, val upon: Int, val scale: Float,
    ) {
        val themesOthers get() = backs.isNotEmpty() || mask != 0 || upon != 0
    }

    private val loaded = HashMap<String, Loaded?>()

    @Synchronized private fun load(ctx: Context, pkg: String): Loaded? {
        if (loaded.containsKey(pkg)) return loaded[pkg]
        val l = try { parse(ctx, pkg) } catch (e: Exception) { null }
        if (loaded.size >= 3) loaded.clear()
        loaded[pkg] = l
        return l
    }

    /** res/xml/<name>.xml, or assets/<name>.xml (packs use either). */
    private fun parser(ctx: Context, pkg: String, res: Resources, name: String): XmlPullParser? {
        val id = res.getIdentifier(name, "xml", pkg)
        if (id != 0) return try { res.getXml(id) } catch (e: Exception) { null }
        return try {
            val text = ctx.createPackageContext(pkg, 0).assets.open("$name.xml").bufferedReader().use { it.readText() }
            Xml.newPullParser().apply { setInput(StringReader(text)) }
        } catch (e: Exception) { null }
    }

    /** "ComponentInfo{pkg/.Cls}" → "pkg/pkg.Cls", the same form as ComponentName.flattenToString(). */
    private fun key(component: String?): String? {
        val inner = component?.substringAfter('{', "")?.substringBefore('}')?.trim()
        if (inner.isNullOrEmpty()) return null
        return ComponentName.unflattenFromString(inner)?.flattenToString()
    }

    private fun resId(res: Resources, pkg: String, name: String?): Int {
        if (name.isNullOrBlank()) return 0
        val d = res.getIdentifier(name, "drawable", pkg)
        return if (d != 0) d else res.getIdentifier(name, "mipmap", pkg)
    }

    private fun parse(ctx: Context, pkg: String): Loaded? {
        val res = ctx.packageManager.getResourcesForApplication(pkg)
        val p = parser(ctx, pkg, res, "appfilter") ?: return null
        val byComponent = HashMap<String, String>(); val byPackage = HashMap<String, String>()
        val calendars = HashMap<String, String>(); val calendarsByPackage = HashMap<String, String>()
        val backs = ArrayList<Int>(); var mask = 0; var upon = 0; var scale = 1f
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) when (p.name) {
                "item" -> {
                    val k = key(p.getAttributeValue(null, "component"))
                    val d = p.getAttributeValue(null, "drawable")
                    if (k != null && !d.isNullOrBlank()) {
                        byComponent.putIfAbsent(k, d)
                        byPackage.putIfAbsent(k.substringBefore('/'), d)
                    }
                }
                "calendar" -> {
                    val k = key(p.getAttributeValue(null, "component"))
                    val prefix = p.getAttributeValue(null, "prefix")
                    if (k != null && !prefix.isNullOrBlank()) {
                        calendars.putIfAbsent(k, prefix)
                        calendarsByPackage.putIfAbsent(k.substringBefore('/'), prefix)
                    }
                }
                "iconback" -> for (i in 0 until p.attributeCount)
                    if (p.getAttributeName(i).startsWith("img")) resId(res, pkg, p.getAttributeValue(i)).takeIf { it != 0 }?.let { backs += it }
                "iconmask" -> mask = resId(res, pkg, p.getAttributeValue(null, "img1"))
                "iconupon" -> upon = resId(res, pkg, p.getAttributeValue(null, "img1"))
                "scale" -> scale = p.getAttributeValue(null, "factor")?.toFloatOrNull()?.coerceIn(0.3f, 1.5f) ?: 1f
            }
            ev = p.next()
        }
        return Loaded(pkg, res, byComponent, byPackage, calendars, calendarsByPackage, backs, mask, upon, scale)
    }

    private fun drawable(l: Loaded, id: Int): Drawable? =
        try { l.res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXHIGH, null) } catch (e: Exception) { null }

    // ------------------------------------------------------------ icons

    /** The drawable name [pack] uses for an app, or null if it doesn't have one. Calendars get today's date. */
    fun nameFor(ctx: Context, pack: String, component: ComponentName): String? {
        val l = load(ctx, pack) ?: return null
        val k = component.flattenToString()
        (l.calendars[k] ?: l.calendarsByPackage[component.packageName])?.let { return it + Calendar.getInstance().get(Calendar.DAY_OF_MONTH) }
        return l.byComponent[k] ?: l.byPackage[component.packageName]
    }

    /** True if [pack] has its own icon for this app. */
    fun covers(ctx: Context, pack: String, component: ComponentName): Boolean = nameFor(ctx, pack, component) != null

    /** [pack]'s icon for an app, drawn at [size]; null if it doesn't have one. */
    fun iconFor(ctx: Context, pack: String, component: ComponentName, size: Int): Bitmap? =
        nameFor(ctx, pack, component)?.let { named(ctx, pack, it, size) }

    /** A pack icon by its drawable name, drawn at [size]. */
    fun named(ctx: Context, pack: String, name: String, size: Int): Bitmap? {
        val l = load(ctx, pack) ?: return null
        val id = resId(l.res, pack, name).takeIf { it != 0 } ?: return null
        return drawable(l, id)?.let { Apps.render(it, size) }
    }

    /**
     * An app the pack doesn't cover, themed the pack's way: its background, the app's own icon scaled
     * down and cut by the pack's mask, then its overlay. Null if the pack doesn't theme other apps.
     */
    fun themeOther(ctx: Context, pack: String, own: Bitmap, seed: Int, size: Int): Bitmap? {
        val l = load(ctx, pack) ?: return null
        if (!l.themesOthers) return null
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val full = Rect(0, 0, size, size)
        fun layer(id: Int): Bitmap? = drawable(l, id)?.let { d ->
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b -> d.bounds = full; d.draw(Canvas(b)) }
        }
        val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (l.backs.isNotEmpty()) layer(l.backs[abs(seed) % l.backs.size])?.let { c.drawBitmap(it, 0f, 0f, p) }
        val icon = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val ic = Canvas(icon)
        val s = size * l.scale; val o = (size - s) / 2f
        ic.drawBitmap(own, null, RectF(o, o, o + s, o + s), p)
        if (l.mask != 0) layer(l.mask)?.let {
            ic.drawBitmap(it, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        }
        c.drawBitmap(icon, 0f, 0f, p)
        if (l.upon != 0) layer(l.upon)?.let { c.drawBitmap(it, 0f, 0f, p) }
        return out
    }

    // ------------------------------------------------------------ browsing a pack (Change icon)

    /** Every icon in a pack, by drawable name: its drawable.xml if it has one, else everything in its appfilter. */
    fun allNames(ctx: Context, pack: String): List<String> {
        val l = load(ctx, pack) ?: return emptyList()
        val names = LinkedHashSet<String>()
        parser(ctx, pack, l.res, "drawable")?.let { p ->
            try {
                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && p.name == "item") p.getAttributeValue(null, "drawable")?.takeIf { it.isNotBlank() }?.let { names += it }
                    ev = p.next()
                }
            } catch (e: Exception) { }
        }
        if (names.isEmpty()) { names += l.byComponent.values.sorted() }
        return names.toList()
    }

    private val thumbs = LruCache<String, Bitmap>(240)
    private val thumbExec = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private val missing = java.util.Collections.synchronizedSet(HashSet<String>())

    fun thumb(pack: String, name: String, onLight: Boolean): Bitmap? = thumbs.get("$onLight/$pack/$name")

    /** Draw a pack icon for the picker in the background; [done] gets it on the main thread. */
    fun requestThumb(ctx: Context, pack: String, name: String, size: Int, onLight: Boolean, done: (Bitmap?) -> Unit) {
        val app = ctx.applicationContext
        val k = "$onLight/$pack/$name"
        if (k in missing) return
        thumbExec.execute {
            val b = thumbs.get(k) ?: named(app, pack, name, size)?.let { Icons.contrasting(it, onLight) }
            if (b != null) thumbs.put(k, b) else missing += k
            main.post { done(b) }
        }
    }
}
