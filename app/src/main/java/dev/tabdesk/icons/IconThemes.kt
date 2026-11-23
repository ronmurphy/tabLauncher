package dev.tabdesk.icons

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import dev.tabdesk.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saved icon themes: a name plus the recipe (style, colours, icon pack, effect and the icons picked
 * for single apps). Saving the recipe rather than the drawn icons keeps a theme tiny and lets it cover
 * apps installed later, and an exported theme draws someone else's apps the same way on their tablet.
 */
object IconThemes {
    const val REQ_EXPORT = 41
    const val REQ_IMPORT = 42
    private const val MIME = "application/json"
    private val STYLE_IDS = listOf("original", "themed", "watercolour", "inked", "minimal", "outline", "papercut", "longshadow", "badge")

    data class Theme(
        val name: String, val style: Int, val colours: String, val pack: String?, val effect: String,
        val icons: Map<String, String>,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name); put("style", STYLE_IDS[style]); put("colours", colours)
            pack?.let { put("pack", it) }
            put("effect", effect)
            put("icons", JSONObject(icons as Map<*, *>))
        }

        /** What it's made of, e.g. "Watercolour · Sakura · 8-bit". */
        fun describe(ctx: android.content.Context? = null): String {
            val parts = ArrayList<String>()
            parts += Icons.STYLES[style]
            if (style != Icons.ORIGINAL) IconColours.CHOICES.firstOrNull { it.first == colours }?.let { parts += it.second }
            pack?.let { p -> parts += ctx?.let { c -> IconPacks.installed(c).firstOrNull { it.pkg == p }?.label } ?: "icon pack" }
            if (effect != IconEffects.NONE) IconEffects.ALL.firstOrNull { it.first == effect }?.let { parts += it.second }
            return parts.joinToString(" · ")
        }

        /** Looks the same as [o] (the names may differ). */
        fun sameLook(o: Theme): Boolean {
            if (style != o.style || pack != o.pack || effect != o.effect || icons != o.icons) return false
            // Colours only show in Original for symbols picked for single apps.
            val coloursShow = style != Icons.ORIGINAL || icons.values.any { it.startsWith(Icons.SYM) }
            return !coloursShow || colours == o.colours
        }
    }

    fun fromJson(o: JSONObject?): Theme? {
        o ?: return null
        val name = o.optString("name").trim().take(60).takeIf { it.isNotBlank() } ?: return null
        val style = STYLE_IDS.indexOf(o.optString("style")).takeIf { it >= 0 } ?: return null
        val colours = o.optString("colours").takeIf { IconColours.isColours(it) } ?: IconColours.ACCENT
        val pack = o.optString("pack").takeIf { it.matches(Regex("[A-Za-z0-9_.]{1,200}")) }
        val effect = o.optString("effect").takeIf { IconEffects.isEffect(it) } ?: IconEffects.NONE
        val icons = o.optJSONObject("icons")?.let { r ->
            r.keys().asSequence().filter { it.length < 300 }.associateWith { r.optString(it) }.filterValues { Prefs.validIcon(it) }
        } ?: emptyMap()
        return Theme(name, style, colours, pack, effect, icons)
    }

    private fun parse(json: String): List<Theme> = try {
        val a = JSONArray(json); (0 until a.length()).mapNotNull { fromJson(a.optJSONObject(it)) }
    } catch (e: Exception) { emptyList() }

    private fun store(list: List<Theme>) { Prefs.iconThemes = JSONArray(list.map { it.toJson() }).toString() }

    /** Bumped when starters are added, so new ones reach people who already have the earlier set. */
    private const val STARTER_VERSION = 5

    /** A few to start from, showing off combinations; added once, and deletable like any other. */
    private val STARTERS = listOf(
        Theme("Original", Icons.ORIGINAL, IconColours.ACCENT, null, IconEffects.NONE, emptyMap()),
        Theme("Ink wash", Icons.WATERCOLOUR, "black", null, IconEffects.NONE, emptyMap()),
        Theme("Cherry blossom", Icons.WATERCOLOUR, "sakura", null, IconEffects.NONE, emptyMap()),
        Theme("Woodblock", Icons.INKED, "ukiyoe", null, IconEffects.NONE, emptyMap()),
        Theme("Retro arcade", Icons.ORIGINAL, IconColours.ACCENT, null, "8bit", emptyMap()),
        Theme("Green screen", Icons.MINIMAL, "terminal", null, "crt", emptyMap()),
        Theme("Summer stickers", Icons.THEMED, "summer", null, "sticker", emptyMap()),
        Theme("Frosted glass", Icons.ORIGINAL, IconColours.ACCENT, null, "glass", emptyMap()),
        Theme("Neon sign", Icons.ORIGINAL, IconColours.ACCENT, null, "neon", emptyMap()),
        Theme("Neon glass", Icons.ORIGINAL, IconColours.ACCENT, null, "neonglass", emptyMap()),
        Theme("Synthwave", Icons.THEMED, "synthwave", null, IconEffects.NONE, emptyMap()),
        Theme("Sketchbook", Icons.ORIGINAL, IconColours.ACCENT, null, "pencil", emptyMap()),
        Theme("Line art", Icons.OUTLINE, IconColours.ACCENT, null, IconEffects.NONE, emptyMap()),
        Theme("Paper craft", Icons.PAPERCUT, "summer", null, IconEffects.NONE, emptyMap()),
        Theme("Flat design", Icons.LONGSHADOW, IconColours.APP, null, IconEffects.NONE, emptyMap()),
        Theme("Badges", Icons.BADGE, IconColours.APP, null, IconEffects.NONE, emptyMap()),
    )

    fun all(): List<Theme> {
        if (Prefs.iconThemesSeeded < STARTER_VERSION) {
            val have = parse(Prefs.iconThemes)
            store(STARTERS.filter { s -> have.none { it.name.equals(s.name, true) } } + have)
            Prefs.iconThemesSeeded = STARTER_VERSION
        }
        return parse(Prefs.iconThemes)
    }

    /** What the icons look like now, as a theme. */
    fun current(name: String = ""): Theme =
        Theme(name, Prefs.iconStyle, Prefs.iconColours, Prefs.iconPack, Prefs.iconEffect, Prefs.allIcons())

    /** The saved theme that looks like the current icons, if any. */
    fun active(): Theme? = current().let { now -> all().firstOrNull { it.sameLook(now) } }

    /** [name], or "name 2", "name 3"… if a theme already has it (other than [except]). */
    fun freeName(name: String, except: String? = null): String {
        val taken = all().map { it.name.lowercase() }.filter { it != except?.lowercase() }.toSet()
        if (name.lowercase() !in taken) return name
        var i = 2
        while ("$name $i".lowercase() in taken) i++
        return "$name $i"
    }

    fun saveCurrent(name: String) { store(all() + current(freeName(name))) }

    fun rename(old: String, new: String) {
        val n = freeName(new.trim().take(60), except = old)
        store(all().map { if (it.name == old) it.copy(name = n) else it })
    }

    fun delete(name: String) { store(all().filter { it.name != name }) }

    /** The theme switched to and what the icons were before, for Undo. */
    var undo: Pair<String, Theme>? = null; private set

    fun apply(act: HomeActivity, t: Theme) {
        undo = t.name to current()
        set(act, t)
    }

    fun undoApply(act: HomeActivity) {
        val prev = undo?.second ?: return
        undo = null
        set(act, prev)
    }

    private fun set(act: HomeActivity, t: Theme) {
        val picksChanged = Prefs.allIcons() != t.icons
        Prefs.iconStyle = t.style; Prefs.iconColours = t.colours; Prefs.iconPack = t.pack; Prefs.iconEffect = t.effect
        Prefs.setIcons(t.icons)
        act.applyThemeChange()
        // The style may be unchanged while the picked icons aren't.
        if (picksChanged) Apps.load()
    }

    /** A backup's themes added to [existing] (a backup's theme replaces one with the same name). */
    fun merge(existing: String, incoming: JSONArray): String? {
        val add = (0 until incoming.length()).mapNotNull { fromJson(incoming.optJSONObject(it)) }
        if (add.isEmpty()) return null
        val keep = parse(existing).filter { e -> add.none { it.name.equals(e.name, true) } }
        return JSONArray((keep + add).map { it.toJson() }).toString()
    }

    // ------------------------------------------------------------ files

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var exporting: Theme? = null

    fun export(act: HomeActivity, t: Theme) {
        exporting = t
        val safe = t.name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim()
        act.safeStartForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(MIME)
            .putExtra(Intent.EXTRA_TITLE, "$safe (TabDesk icons).json"), REQ_EXPORT)
    }

    fun import(act: HomeActivity) {
        act.safeStartForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(MIME, "text/plain", "application/octet-stream")), REQ_IMPORT)
    }

    fun onResult(act: HomeActivity, req: Int, res: Int, data: Intent?): Boolean {
        if (req != REQ_EXPORT && req != REQ_IMPORT) return false
        val uri = data?.data
        if (res != Activity.RESULT_OK || uri == null) return true
        if (req == REQ_EXPORT) exporting?.let { write(act, uri, it) } else read(act, uri)
        return true
    }

    private fun write(act: HomeActivity, uri: Uri, t: Theme) {
        val bytes = JSONObject().apply {
            put("app", "TabDesk"); put("kind", "iconTheme"); put("format", 1); put("theme", t.toJson())
        }.toString(1).toByteArray()
        val app = act.applicationContext
        exec.execute {
            val ok = listOf("wt", "w").any { mode ->
                try { app.contentResolver.openOutputStream(uri, mode)!!.use { it.write(bytes) }; true } catch (e: Exception) { false }
            }
            main.post { act.toast(if (ok) "Saved “${t.name}”" else "Couldn't save the theme") }
        }
    }

    private fun read(act: HomeActivity, uri: Uri) {
        val app = act.applicationContext
        exec.execute {
            val t = try {
                val out = ByteArrayOutputStream()
                app.contentResolver.openInputStream(uri)!!.use { s ->
                    val buf = ByteArray(16 shl 10)
                    while (true) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n); require(out.size() <= 1 shl 20) }
                }
                val o = JSONObject(out.toString(Charsets.UTF_8.name()))
                if (o.optString("app") == "TabDesk" && o.optString("kind") == "iconTheme") fromJson(o.optJSONObject("theme")) else null
            } catch (e: Exception) { null }
            main.post {
                if (t == null) { act.toast("That file isn't a TabDesk icon theme"); return@post }
                val named = t.copy(name = freeName(t.name))
                store(all() + named)
                act.toast("Added “${named.name}”: tap it to use it")
                act.refreshSettings()
            }
        }
    }
}
