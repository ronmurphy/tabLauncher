package dev.tabdesk

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Backup and restore of the whole TabDesk setup as one small JSON file. Saving and opening go through
 * Android's file picker, where the Google Drive app shows up as a place, so there's no Google sign-in,
 * API key or Drive library here: Drive uploads the file itself.
 */
object Backup {
    const val REQ_EXPORT = 31
    const val REQ_IMPORT = 32
    private const val MIME = "application/json"
    private const val MAX_BYTES = 4 shl 20
    private const val DRIVE = "com.google.android.apps.docs.storage"

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** The Drive app is installed, so the buttons can say Google Drive. */
    fun driveInstalled(ctx: Context) = try { ctx.packageManager.resolveContentProvider(DRIVE, 0) != null } catch (e: Exception) { false }

    // ------------------------------------------------------------ snapshot

    /** Everything that makes TabDesk yours: settings, taskbar, desktop, folders, renames, hidden apps, font. */
    fun snapshot(act: HomeActivity): JSONObject = JSONObject().apply {
        put("app", "TabDesk"); put("format", 1)
        put("version", BuildInfo.version(act))
        put("saved", System.currentTimeMillis())
        put("device", Build.MODEL)
        put("settings", Prefs.exportSettings())
        val desk = JSONArray()
        Prefs.loadDesktop().forEach { item ->
            val o = item.toJson()
            if (item.type == DesktopItem.WIDGET) act.widgets.providerOf(item.widgetId)?.let { (cn, label) ->
                o.put("provider", cn.flattenToString()); o.put("widgetLabel", label)
            }
            desk.put(o)
        }
        put("desktop", desk)
    }

    // ------------------------------------------------------------ export

    fun export(act: HomeActivity) {
        val name = "TabDesk backup ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.json"
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(MIME)
            .putExtra(Intent.EXTRA_TITLE, name)
        startAt(i)
        act.safeStartForResult(i, REQ_EXPORT)
    }

    fun import(act: HomeActivity) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(MIME, "text/plain", "application/octet-stream"))
        startAt(i)
        act.safeStartForResult(i, REQ_IMPORT)
    }

    /** Open the picker where the last backup went (it also remembers Drive by itself). */
    private fun startAt(i: Intent) {
        Prefs.lastBackup?.let { i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it)) }
    }

    fun onResult(act: HomeActivity, req: Int, res: Int, data: Intent?): Boolean {
        if (req != REQ_EXPORT && req != REQ_IMPORT) return false
        val uri = data?.data
        if (res != Activity.RESULT_OK || uri == null) return true
        if (req == REQ_EXPORT) write(act, uri) else read(act, uri)
        return true
    }

    private fun write(act: HomeActivity, uri: Uri) {
        val bytes = snapshot(act).toString(1).toByteArray()
        val app = act.applicationContext
        exec.execute {
            val ok = listOf("wt", "w").any { mode ->
                try { app.contentResolver.openOutputStream(uri, mode)!!.use { it.write(bytes) }; true } catch (e: Exception) { false }
            }
            main.post {
                if (!ok) { act.toast("Couldn't save the backup"); return@post }
                Prefs.lastBackup = uri.toString()
                act.toast(if (uri.authority?.startsWith(DRIVE) == true) "Backed up to Google Drive" else "Backup saved")
            }
        }
    }

    // ------------------------------------------------------------ import

    private fun read(act: HomeActivity, uri: Uri) {
        val app = act.applicationContext
        exec.execute {
            val o = try {
                val out = ByteArrayOutputStream()
                app.contentResolver.openInputStream(uri)!!.use { s ->
                    val buf = ByteArray(16 shl 10)
                    while (true) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n); require(out.size() <= MAX_BYTES) }
                }
                JSONObject(out.toString(Charsets.UTF_8.name())).takeIf { it.optString("app") == "TabDesk" && it.has("desktop") }
            } catch (e: Exception) { null }
            main.post {
                if (o == null) act.toast("That file isn't a TabDesk backup")
                else { Prefs.lastBackup = uri.toString(); confirm(act, o) }
            }
        }
    }

    private fun confirm(act: HomeActivity, o: JSONObject) {
        val items = o.optJSONArray("desktop")?.length() ?: 0
        val pins = o.optJSONObject("settings")?.optJSONArray("pins")?.length() ?: 0
        val saved = o.optLong("saved").takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) }
        val device = o.optString("device").takeIf { it.isNotBlank() }
        val from = listOfNotNull(saved?.let { "Saved $it" }, device?.let { "on $it" }).joinToString(" ")
        val card = act.overlays.dialogCard(act.dp(460), "Restore this backup?")
        card.addView(TextView(act).label(act.pal.text, 14.5f).apply {
            text = (if (from.isNotEmpty()) "$from. " else "") +
                "${plural(items, "item")} on the desktop, ${plural(pins, "app")} on the taskbar.\n\n" +
                "This replaces your desktop, folders, taskbar, font and Customize settings. " +
                "TabDesk keeps a copy of your current setup, so you can undo this in Customize."
            setLineSpacing(0f, 1.15f)
        }, llp(MATCH, WRAP).apply { bottomMargin = act.dp(16) })
        val buttons = linear(act, false) { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        buttons.addView(act.overlays.button("Cancel", false) { act.overlays.dismissTop() }, llp(WRAP, act.dp(40)))
        buttons.addView(act.overlays.button("Restore", true) { apply(act, o, keepUndo = true) },
            llp(WRAP, act.dp(40)).apply { marginStart = act.dp(8) })
        card.addView(buttons, llp(MATCH, WRAP))
        act.overlays.show(card, dim = true)
    }

    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

    // ------------------------------------------------------------ apply / undo

    private fun undoFile(ctx: Context) = File(ctx.filesDir, "before-import.json")
    private var held: Set<Int>? = null

    fun canUndo(ctx: Context) = undoFile(ctx).exists()

    /** Widget ids the undo copy uses; kept bound so undo can bring those widgets back. */
    fun heldWidgets(ctx: Context): Set<Int> = held ?: try {
        val a = JSONObject(undoFile(ctx).readText()).getJSONArray("desktop")
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optInt("type") == DesktopItem.WIDGET }.map { it.optInt("wid", -1) }.toSet()
    } catch (e: Exception) { emptySet<Int>() }.also { held = it }

    fun undo(act: HomeActivity) {
        val o = try { JSONObject(undoFile(act).readText()) } catch (e: Exception) { null }
        if (o == null) { act.toast("Nothing to undo"); return }
        apply(act, o, keepUndo = false)
        undoFile(act).delete(); held = null
    }

    private fun apply(act: HomeActivity, o: JSONObject, keepUndo: Boolean) {
        if (keepUndo) {
            try { undoFile(act).writeText(snapshot(act).toString()) } catch (e: Exception) { }
            held = null
        }
        Prefs.importSettings(o.optJSONObject("settings") ?: JSONObject())
        Apps.load()  // picked icons and the icon style may have changed

        val a = o.optJSONArray("desktop") ?: JSONArray()
        val items = ArrayList<DesktopItem>()
        val lost = ArrayList<String>()
        for (i in 0 until a.length()) {
            val j = a.optJSONObject(i) ?: continue
            val item = try { DesktopItem.fromJson(j) } catch (e: Exception) { continue }
            if (item.type !in listOf(DesktopItem.APP, DesktopItem.WIDGET, DesktopItem.SHORTCUT, DesktopItem.FOLDER)) continue
            if (item.type == DesktopItem.WIDGET) {
                val id = act.widgets.restore(item.widgetId, ComponentName.unflattenFromString(j.optString("provider")))
                if (id == -1) { lost += j.optString("widgetLabel").ifBlank { "a widget" }; continue }
                item.widgetId = id
            }
            items += item
        }
        Prefs.saveDesktop(items)
        act.repinShortcuts(items)

        Fonts.load(act)
        act.overlays.dismissAll()
        Apps.resort()
        act.rebuildUi()
        act.ensureFont()
        act.toast(when {
            !keepUndo -> "Back to your setup from before the import"
            lost.isEmpty() -> "Setup restored"
            else -> "Setup restored. Add again from Widgets: ${lost.joinToString()}"
        })
    }
}

/** The app's version name, for the Customize footer and backup files. */
object BuildInfo {
    fun version(act: HomeActivity): String = try { act.packageManager.getPackageInfo(act.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
}
