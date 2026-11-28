package dev.tabdesk.agent

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.tabdesk.people.Look
import dev.tabdesk.people.PixelPeople
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The agent using the tablet as a Pixel Person on the taskbar: who it says it is, and what it's doing.
 *
 * Who comes from the caller, in a tools/call's `_meta` "dev.tabdesk/agent" (docs/agent-control.md). It's only drawn,
 * never trusted: it decides nothing, it's cut short and cleaned, and the approved app's own name, from Binder, is
 * always shown beside it. Without it, the person is a default look for the approved app.
 */
object AgentPerson {
    /** [name]: the agent's chat or project; [agent]: its ACP `agentInfo` name, for the bar's colours; [person]: its look. */
    class Who(val name: String?, val agent: String?, val model: String?, val person: JSONObject?)

    /** The app the rest is about: the one whose call last began. */
    @Volatile var pkg: String? = null; private set
    @Volatile var who: Who? = null; private set
    /** The tool running now, or null between calls. */
    @Volatile var tool: String? = null; private set
    /** The last call was refused or failed. */
    @Volatile var failed = false; private set

    /** Called on the main thread after any change. */
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    private val OPTIONS = listOf("skin", "hair", "haircolor", "letter", "shirt", "bottom", "bottomcolor")

    /** A call from [pkg] passed its checks and starts. */
    fun began(pkg: String, tool: String, who: Who?) {
        if (this.pkg != pkg) failed = false
        this.pkg = pkg
        this.who = who
        this.tool = tool
        changed()
    }

    /** [pkg]'s call is done; [failed] if it was refused or failed. */
    fun ended(pkg: String, failed: Boolean) {
        if (this.pkg != pkg) return
        tool = null
        this.failed = failed
        changed()
    }

    private fun changed() = main.post { listeners.forEach { try { it() } catch (_: Exception) {} } }

    /** The `_meta` "dev.tabdesk/agent" of a tools/call, cleaned; null when there's none. */
    fun parse(meta: JSONObject?): Who? {
        val o = meta?.optJSONObject("dev.tabdesk/agent") ?: return null
        val p = o.optJSONObject("person")?.let { src ->
            JSONObject().also { out ->
                for (k in OPTIONS) (src.opt(k) as? String)?.let { out.put(k, clean(it, 20)) }
                src.optJSONArray("accessories")?.let { a ->
                    out.put("accessories", org.json.JSONArray((0 until minOf(a.length(), 4)).mapNotNull { (a.opt(it) as? String)?.let { s -> clean(s, 20) } }))
                }
            }
        }
        return Who(text(o, "name", 32), text(o, "agent", 40), text(o, "model", 40), p)
    }

    private fun text(o: JSONObject, key: String, max: Int) = (o.opt(key) as? String)?.let { clean(it, max) }?.takeIf { it.isNotBlank() }

    /** Letters, digits, spaces and plain punctuation only (no control or direction marks), at most [max] characters. */
    private fun clean(s: String, max: Int) =
        s.filter { it.isLetterOrDigit() || it == ' ' || it in "-_.,:()/+'&#" }.trim().take(max)

    /** The pose for what it's doing, from `sprites.txt`'s poses; the words are in [doing]. */
    fun pose(control: String): String = when (control) {
        Agent.STOPPED, Agent.PAUSED -> "stop"
        else -> when (tool) {
            null -> if (failed) "oops" else "think"
            "get_item", "wait_for" -> "search"
            "apps", "usage", "folder_list", "folder_read", "folder_write" -> "book"
            else -> "tablet"   // screen, open_app, and tool list 2's taps and typing
        }
    }

    /** What it's doing, in words, for the tooltip and TalkBack. */
    fun doing(control: String): String = when (control) {
        Agent.STOPPED -> "stopped"
        Agent.PAUSED -> "paused"
        else -> when (tool) {
            null -> if (failed) "its last call didn't go through" else "thinking"
            "screen" -> "reading the screen"
            "get_item" -> "looking closer at the screen"
            "open_app" -> "opening an app"
            "apps" -> "looking at the allowed apps"
            "usage" -> "looking at what's running"
            "tap" -> "tapping"
            "type" -> "typing"
            "swipe" -> "swiping"
            "scroll" -> "scrolling"
            "key" -> "pressing a key"
            "wait_for" -> "waiting for something on the screen"
            "folder_list" -> "looking in the shared folder"
            "folder_read" -> "copying a file from the shared folder"
            "folder_write" -> "putting a file in the shared folder"
            else -> "using the tablet"
        }
    }

    /** The name to show: the agent's own, else the approved app's. */
    fun name(appLabel: String): String = who?.name ?: appLabel

    /** Its look: the one it chose, over a default that stays the same for its name (or the approved app). */
    fun look(ctx: Context, appLabel: String): Look {
        val people = PixelPeople.get(ctx)
        val w = who
        val name = name(appLabel)
        val default = people.defaultLook(w?.name ?: pkg ?: appLabel, people.agentFor(w?.agent),
            name.firstOrNull { it.isLetterOrDigit() })
        val p = w?.person ?: return default
        fun opt(k: String) = (p.opt(k) as? String)?.takeIf { it.isNotBlank() }
        val acc = p.optJSONArray("accessories")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }.orEmpty()
        return people.chosen(default, opt("skin"), opt("hair"), opt("haircolor"), acc, opt("letter")?.singleOrNull(), opt("shirt"),
            opt("bottom"), opt("bottomcolor"))
    }
}
