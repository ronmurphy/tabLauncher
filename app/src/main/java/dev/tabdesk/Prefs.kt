package dev.tabdesk

import dev.tabdesk.icons.*
import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private lateinit var sp: SharedPreferences
    private var renameCache: MutableMap<String, String> = HashMap()
    private var hiddenCache: MutableSet<String> = HashSet()
    private var iconCache: MutableMap<String, String> = HashMap()

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("tabdesk", Context.MODE_PRIVATE)
        loadCaches()
    }

    private fun loadCaches() {
        val o = JSONObject(sp.getString("renames", "{}")!!)
        renameCache = o.keys().asSequence().associateWith { o.getString(it) }.toMutableMap()
        hiddenCache = sp.getStringSet("hidden", emptySet())!!.toHashSet()
        val ic = JSONObject(sp.getString("icons", "{}")!!)
        iconCache = ic.keys().asSequence().associateWith { ic.getString(it) }.toMutableMap()
    }

    /** 0 = follow system, 1 = light, 2 = dark */
    var themeMode: Int
        get() = sp.getInt("theme", 0)
        set(v) = sp.edit().putInt("theme", v).apply()

    /** 0 = wallpaper colour */
    var accent: Int
        get() = sp.getInt("accent", 0)
        set(v) = sp.edit().putInt("accent", v).apply()

    /** An index into Icons.STYLES (Icons.ORIGINAL, THEMED, … BADGE). */
    var iconStyle: Int
        get() = sp.getInt("iconStyle", 0)
        set(v) = sp.edit().putInt("iconStyle", v).apply()
    /** IconEffects.ALL id applied to every icon last ("none" for no effect). */
    var iconEffect: String
        get() = sp.getString("iconEffect", null)?.takeIf { IconEffects.isEffect(it) } ?: IconEffects.NONE
        set(v) = sp.edit().putString("iconEffect", v).apply()
    /** Saved icon themes (IconThemes), as a JSON array. */
    var iconThemes: String
        get() = sp.getString("iconThemes", "[]")!!
        set(v) = sp.edit().putString("iconThemes", v).apply()
    /** Which set of starter icon themes has been added (IconThemes.STARTER_VERSION). */
    var iconThemesSeeded: Int
        get() = sp.getInt("iconThemesSeed", 0)
        set(v) = sp.edit().putInt("iconThemesSeed", v).apply()
    /** Package of the icon pack in use, or null. */
    var iconPack: String?
        get() = sp.getString("iconPack", null)
        set(v) = sp.edit().putString("iconPack", v).apply()
    /** IconColours.ACCENT, APP, AUTO or a named set ("sakura"…). */
    var iconColours: String
        get() = sp.getString("iconColours", null)?.takeIf { IconColours.isColours(it) } ?: IconColours.ACCENT
        set(v) = sp.edit().putString("iconColours", v).apply()

    var dots: Boolean
        get() = sp.getBoolean("dots", true)
        set(v) = sp.edit().putBoolean("dots", v).apply()

    // ---- layout
    var iconDp: Int
        get() = sp.getInt("iconDp", 52)
        set(v) = sp.edit().putInt("iconDp", v).apply()
    /** Target desktop cell width; smaller = denser grid. */
    var cellDp: Int
        get() = sp.getInt("cellDp", 96)
        set(v) = sp.edit().putInt("cellDp", v).apply()
    var labels: Boolean
        get() = sp.getBoolean("labels", true)
        set(v) = sp.edit().putBoolean("labels", v).apply()
    var taskbarDp: Int
        get() = sp.getInt("taskbarDp", 56)
        set(v) = sp.edit().putInt("taskbarDp", v).apply()
    var hideStatus: Boolean
        get() = sp.getBoolean("hideStatus", true)
        set(v) = sp.edit().putBoolean("hideStatus", v).apply()
    var doubleTapLock: Boolean
        get() = sp.getBoolean("doubleTapLock", true)
        set(v) = sp.edit().putBoolean("doubleTapLock", v).apply()
    var recents: Boolean
        get() = sp.getBoolean("recents", true)
        set(v) = sp.edit().putBoolean("recents", v).apply()
    /** Taskbar recents the user removed: package → when. An app comes back once it's opened again. */
    var removedRecents: Map<String, Long>
        get() = JSONObject(sp.getString("removedRecents", "{}")!!).let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }
        set(v) = sp.edit().putString("removedRecents", JSONObject(v as Map<*, *>).toString()).apply()
    /** App keys that always open in a floating window. */
    var floatApps: Set<String>
        get() = JSONArray(sp.getString("floatApps", "[]")).let { a -> List(a.length()) { a.getString(it) }.toSet() }
        set(v) = sp.edit().putString("floatApps", JSONArray(v).toString()).apply()
    /** App keys removed from Start's "Recently added" list. */
    var removedAdded: Set<String>
        get() = JSONArray(sp.getString("removedAdded", "[]")).let { a -> List(a.length()) { a.getString(it) }.toSet() }
        set(v) = sp.edit().putString("removedAdded", JSONArray(v).toString()).apply()

    /** Google Fonts family for TabDesk's text, or null for the system font. */
    var font: String?
        get() = sp.getString("font", null)
        set(v) = sp.edit().putString("font", v).apply()
    /** The user's own screen timeout, restored when Keep awake is turned off. */
    var screenTimeout: Int
        get() = sp.getInt("screenTimeout", 120_000)
        set(v) = sp.edit().putInt("screenTimeout", v).apply()
    var tileAdded: Boolean
        get() = sp.getBoolean("tileAdded", false)
        set(v) = sp.edit().putBoolean("tileAdded", v).apply()

    /** Where the last backup was saved; the file picker starts there next time. */
    var lastBackup: String?
        get() = sp.getString("lastBackup", null)
        set(v) = sp.edit().putString("lastBackup", v).apply()

    /** Check GitHub once a day for a newer TabDesk. */
    var autoUpdates: Boolean
        get() = sp.getBoolean("autoUpdates", true)
        set(v) = sp.edit().putBoolean("autoUpdates", v).apply()
    /** When TabDesk last asked GitHub for its newest version. */
    var updateChecked: Long
        get() = sp.getLong("updateChecked", 0)
        set(v) = sp.edit().putLong("updateChecked", v).apply()
    /** The newest version the user has been told about, so each is only announced once. */
    var updateNotified: String?
        get() = sp.getString("updateNotified", null)
        set(v) = sp.edit().putString("updateNotified", v).apply()
    /** The version being installed, to say so once TabDesk restarts as it. Written at once: installing ends TabDesk. */
    var updatingTo: String?
        get() = sp.getString("updatingTo", null)
        set(v) { sp.edit().putString("updatingTo", v).commit() }

    // ---- backup: everything in Customize plus pins, renames, hidden apps (the desktop is saved separately)
    private val backupInts = listOf("theme", "accent", "iconDp", "cellDp", "taskbarDp", "iconStyle")
    private val backupBools = listOf("dots", "labels", "hideStatus", "doubleTapLock", "recents", "autoUpdates")
    private val backupLists = listOf("pins", "floatApps", "removedAdded")

    fun exportSettings(): JSONObject = JSONObject().apply {
        backupInts.forEach { if (sp.contains(it)) put(it, sp.getInt(it, 0)) }
        backupBools.forEach { if (sp.contains(it)) put(it, sp.getBoolean(it, false)) }
        backupLists.forEach { k -> sp.getString(k, null)?.let { put(k, JSONArray(it)) } }
        put("renames", JSONObject(renameCache as Map<*, *>))
        put("hidden", JSONArray(hiddenCache.toList()))
        put("icons", JSONObject(iconCache as Map<*, *>))
        font?.let { put("font", it) }
        put("iconColours", iconColours)
        iconPack?.let { put("iconPack", it) }
        put("iconEffect", iconEffect)
        put("iconThemes", JSONArray(iconThemes))
    }

    /** Replace those settings with a backup's. Values are range-checked; anything unknown is ignored. */
    fun importSettings(o: JSONObject) {
        fun strings(a: JSONArray) = List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() }
        val e = sp.edit()
        (backupInts + backupBools + backupLists + listOf("renames", "hidden", "font", "icons", "iconColours", "iconPack", "iconEffect")).forEach { e.remove(it) }
        fun int(k: String, ok: (Int) -> Boolean) { if (o.has(k)) o.optInt(k).takeIf(ok)?.let { e.putInt(k, it) } }
        int("theme") { it in 0..2 }
        int("accent") { true }
        int("iconDp") { it in 40..72 }
        int("cellDp") { it in 72..140 }
        int("taskbarDp") { it in listOf(48, 56, 64) }
        int("iconStyle") { it in 0 until Icons.STYLES.size }
        backupBools.forEach { k -> if (o.has(k)) e.putBoolean(k, o.optBoolean(k)) }
        backupLists.forEach { k -> o.optJSONArray(k)?.let { e.putString(k, JSONArray(strings(it)).toString()) } }
        o.optJSONObject("renames")?.let { r ->
            e.putString("renames", JSONObject(r.keys().asSequence().associateWith { r.optString(it) }.filterValues { it.isNotBlank() } as Map<*, *>).toString())
        }
        o.optJSONArray("hidden")?.let { e.putStringSet("hidden", strings(it).toHashSet()) }
        o.optJSONObject("icons")?.let { r ->
            val ok = r.keys().asSequence().associateWith { r.optString(it) }
                .filterValues { validIcon(it) }
            e.putString("icons", JSONObject(ok as Map<*, *>).toString())
        }
        o.optString("font").takeIf { it.isNotBlank() && it.length < 100 }?.let { e.putString("font", it) }
        o.optString("iconColours").takeIf { IconColours.isColours(it) }?.let { e.putString("iconColours", it) }
        // A pack that isn't installed here is kept: icons fall back to the style until it is.
        o.optString("iconPack").takeIf { it.matches(Regex("[A-Za-z0-9_.]{1,200}")) }?.let { e.putString("iconPack", it) }
        o.optString("iconEffect").takeIf { IconEffects.isEffect(it) }?.let { e.putString("iconEffect", it) }
        // Saved icon themes are added to the ones here (same name: the backup's wins), not replaced.
        o.optJSONArray("iconThemes")?.let { a -> IconThemes.merge(sp.getString("iconThemes", "[]")!!, a)?.let { e.putString("iconThemes", it) } }
        e.putBoolean("seeded", true)
        e.commit()
        loadCaches()
    }

    var seeded: Boolean
        get() = sp.getBoolean("seeded", false)
        set(v) = sp.edit().putBoolean("seeded", v).apply()

    var pins: List<String>
        get() = JSONArray(sp.getString("pins", "[]")).let { a -> List(a.length()) { a.getString(it) } }
        set(v) = sp.edit().putString("pins", JSONArray(v).toString()).apply()

    fun renameOf(key: String): String? = renameCache[key]
    fun setRename(key: String, name: String?) {
        if (name.isNullOrBlank()) renameCache.remove(key) else renameCache[key] = name.trim()
        sp.edit().putString("renames", JSONObject(renameCache as Map<*, *>).toString()).apply()
    }

    /** Icon picked for an app: Icons.OWN, "sym:<name>", or null for automatic. */
    fun iconOf(key: String): String? = iconCache[key]
    fun setIcon(key: String, v: String?) {
        if (v == null) iconCache.remove(key) else iconCache[key] = v
        sp.edit().putString("icons", JSONObject(iconCache as Map<*, *>).toString()).apply()
    }
    fun hasIconOverrides() = iconCache.isNotEmpty()
    fun allIcons(): Map<String, String> = HashMap(iconCache)
    /** Replace every picked icon at once (applying an icon theme). */
    fun setIcons(m: Map<String, String>) {
        iconCache = HashMap(m)
        sp.edit().putString("icons", JSONObject(iconCache as Map<*, *>).toString()).apply()
    }
    /** Is [v] something Prefs.setIcon accepts (from a file)? */
    fun validIcon(v: String) = v == Icons.OWN || (v.startsWith(Icons.SYM) && v.length < 80) || (v.startsWith(Icons.PACK) && v.length < 300)

    fun isHidden(key: String) = key in hiddenCache
    fun setHidden(key: String, hidden: Boolean) {
        if (hidden) hiddenCache += key else hiddenCache -= key
        sp.edit().putStringSet("hidden", HashSet(hiddenCache)).apply()
    }

    /** Bumped whenever something outside HomeActivity (PinActivity) edits the desktop. */
    var desktopRev: Int
        get() = sp.getInt("desktopRev", 0)
        set(v) = sp.edit().putInt("desktopRev", v).apply()

    fun loadDesktop(): MutableList<DesktopItem> {
        val a = JSONArray(sp.getString("desktop", "[]"))
        return MutableList(a.length()) { DesktopItem.fromJson(a.getJSONObject(it)) }
    }

    fun saveDesktop(items: List<DesktopItem>) {
        val a = JSONArray(); items.forEach { a.put(it.toJson()) }
        sp.edit().putString("desktop", a.toString()).apply()
    }

    // A widget mid-way through the bind / configure flow.
    var pendingWidget: Int
        get() = sp.getInt("pendingWidget", -1)
        set(v) = sp.edit().putInt("pendingWidget", v).apply()
    var pendingCell: IntArray?
        get() = sp.getString("pendingCell", null)?.split(',')?.map { it.toInt() }?.toIntArray()
        set(v) = sp.edit().putString("pendingCell", v?.joinToString(",")).apply()
}
