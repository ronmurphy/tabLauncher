package dev.tabdesk.agent

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The last 500 calls (plan T8): when, which app called, the tool, the app in front where the tool looks at it, the
 * result or refusal, and which of the switches that widen what the agent may do were on (Part 3: "the log names
 * them"). Never what was read. Kept in no_backup/ and shown in Customize → Agent.
 */
object AgentLog {
    const val KEEP = 500

    /** [on]: the switches on at the time, by short name ("tap and type, wake the screen"), or null if none. */
    class Entry(val time: Long, val caller: String, val tool: String, val front: String?, val result: String, val on: String?)

    private lateinit var file: File
    private val entries = ArrayDeque<Entry>()
    private val writer = Executors.newSingleThreadExecutor()
    private var loaded = false

    fun init(ctx: Context) {
        synchronized(this) {
            if (loaded) return
            loaded = true
            file = File(ctx.applicationContext.noBackupFilesDir, "agent-log.jsonl")
            try {
                file.readLines().takeLast(KEEP).forEach { l ->
                    val o = JSONObject(l)
                    entries += Entry(o.getLong("t"), o.getString("app"), o.getString("tool"),
                        o.optString("front").takeIf { it.isNotEmpty() }, o.getString("result"), o.optString("on").takeIf { it.isNotEmpty() })
                }
            } catch (_: Exception) {}
        }
    }

    /** Whether a write is waiting on [writer]: it writes whatever is there when it runs, so one is enough. */
    private var writePending = false

    fun add(caller: String, tool: String, front: String?, result: String) {
        val on = Agent.switchesOn().takeIf { it.isNotEmpty() }?.joinToString()
        val e = Entry(System.currentTimeMillis(), caller, tool, front, result, on)
        synchronized(this) {
            entries += e
            while (entries.size > KEEP) entries.removeFirst()
            if (writePending) return
            writePending = true
        }
        writer.execute {
            val all = synchronized(this) { writePending = false; entries.toList() }
            try {
                val tmp = File(file.path + ".new")
                tmp.bufferedWriter().use { w ->
                    for (x in all) w.append(JSONObject().put("t", x.time).put("app", x.caller).put("tool", x.tool)
                        .put("front", x.front ?: "").put("result", x.result).put("on", x.on ?: "").toString()).append('\n')
                }
                tmp.renameTo(file)
            } catch (_: Exception) {}
        }
    }

    /** Newest first. */
    fun recent(n: Int): List<Entry> = synchronized(this) { entries.toList().takeLast(n).reversed() }
}
