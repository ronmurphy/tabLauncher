package dev.tabdesk

import org.json.JSONArray
import org.json.JSONObject

class DesktopItem(
    val id: Long,
    val type: Int,
    /** APP: flattened component. SHORTCUT: "package/shortcutId". FILES: the folder's path. */
    var key: String = "",
    var widgetId: Int = -1,
    var spanX: Int = 1,
    var spanY: Int = 1,
    var label: String? = null,
    /** FILES: FolderViews flags (e.g. newest first). */
    var opts: Int = 0,
) {
    /**
     * Grid cell per orientation ("port" / "land") as [col, row, cols, rows]; the grid size lets a
     * layout be scaled when the grid density changes instead of being thrown away.
     */
    val pos = HashMap<String, IntArray>()
    /** FOLDER: app keys inside, in order. */
    val apps = ArrayList<String>()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("type", type); put("key", key); put("wid", widgetId)
        put("sx", spanX); put("sy", spanY); label?.let { put("label", it) }
        if (opts != 0) put("opts", opts)
        val p = JSONObject(); pos.forEach { (k, v) -> p.put(k, JSONArray().apply { v.forEach { put(it) } }) }
        put("pos", p)
        if (apps.isNotEmpty()) put("apps", JSONArray(apps))
    }

    companion object {
        /** FILES: a Folder View; [key] is the folder's path. */
        const val APP = 0; const val WIDGET = 1; const val SHORTCUT = 2; const val FOLDER = 3; const val FILES = 4

        fun newId() = System.nanoTime()

        fun fromJson(o: JSONObject) = DesktopItem(
            o.getLong("id"), o.getInt("type"), o.optString("key"), o.optInt("wid", -1),
            o.optInt("sx", 1), o.optInt("sy", 1), if (o.has("label")) o.getString("label") else null, o.optInt("opts"),
        ).apply {
            val p = o.optJSONObject("pos")
            p?.keys()?.forEach { k -> val a = p.getJSONArray(k); pos[k] = IntArray(a.length()) { a.getInt(it) } }
            o.optJSONArray("apps")?.let { a -> for (i in 0 until a.length()) apps += a.getString(i) }
        }
    }
}
