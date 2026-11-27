package dev.tabdesk.agent

import android.content.Context
import dev.tabdesk.helper.HelperClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * Acting for agent control (docs/agent-control.md, tool list 2, plan T10): `tap`, `type`, `swipe`, `scroll`, `key`
 * and `wait_for`. Each one checks Part 3's limits 6 and 7 here (the screen, an allowed app in front), finds its item
 * by a locator ([ScreenTools.locate]), and has the helper act (helper/Actor.kt), which checks again just before:
 * the same app in front, the same item, and the window under the finger or the focus being the app's own.
 *
 * All of it needs "Let the agent tap and type" (Customize → Agent), which is off until the user switches it on.
 */
internal object ActTools {
    private const val MAX_TEXT = 2000
    private const val MAX_WAIT_MS = 15_000
    private const val WAIT_EVERY_MS = 400L

    private fun bad(text: String) = Tools.error("bad_arguments", text) to null

    /** The checks every action starts with: the switch, the screen, an allowed app in front. */
    private fun start(ctx: Context): Pair<HelperClient.Task?, Pair<JSONObject, String?>> {
        if (!Agent.acting) return null to (Tools.refused("acting_off", "Tapping and typing is switched off on the tablet " +
            "(TabDesk → Customize → Agent → “Let the agent tap and type”). The agent can still read the screen.") to null)
        return ScreenTools.readable(ctx)
    }

    private fun boolArg(args: JSONObject, key: String): Boolean? = when (val v = args.opt(key)) {
        null -> false
        is Boolean -> v
        else -> null
    }

    private fun intArg(args: JSONObject, key: String): Int? =
        (args.opt(key) as? Number)?.takeIf { it.toDouble() == it.toInt().toDouble() }?.toInt()

    // ------------------------------------------------------------ the tools

    fun tap(ctx: Context, caller: String, args: JSONObject, deadline: Long): Pair<JSONObject, String?> {
        val long = boolArg(args, "long") ?: return bad("long is true or false.")
        val hasXY = args.has("x") || args.has("y")
        if (args.has("target") == hasXY) return bad("Give a target, or x and y (not both).")
        val (task, refusal) = start(ctx)
        if (task == null) return refusal
        val name = Agent.label(task.top)
        val req = JSONObject().put("op", "tap").put("long", long)
        val what: String
        var quoted = false
        if (hasXY) {
            val x = intArg(args, "x"); val y = intArg(args, "y")
            if (x == null || y == null) return bad("x and y are whole numbers: a point on the screen, in pixels.")
            req.put("x", x).put("y", y)
            what = "at $x, $y"
        } else {
            val (t, err) = ScreenTools.locate(ctx, caller, task, args.opt("target"), deadline)
            if (t == null) return err!! to name
            req.put("id", t.item.id).put("fp", t.item.fp)
            what = ScreenTools.named(t.item)
            quoted = quotes(t.item)
        }
        val r = act(ctx, task, req, deadline) ?: return ScreenTools.noAnswer() to name
        failure(ctx, task, r)?.let { return it to name }
        return done(ctx, task, "${if (long) "Long-pressed" else "Tapped"} $what.", quoted) to name
    }

    fun type(ctx: Context, caller: String, args: JSONObject, deadline: Long): Pair<JSONObject, String?> {
        val text = args.opt("text") as? String ?: return bad("text is a string, at most $MAX_TEXT characters.")
        if (text.length > MAX_TEXT) return bad("text is at most $MAX_TEXT characters.")
        val submit = boolArg(args, "submit") ?: return bad("submit is true or false.")
        val replace = boolArg(args, "replace") ?: return bad("replace is true or false.")
        val (task, refusal) = start(ctx)
        if (task == null) return refusal
        val name = Agent.label(task.top)
        val req = JSONObject().put("op", "type").put("text", text).put("submit", submit).put("replace", replace)
        var into: String? = null
        var quoted = false
        if (args.has("target")) {
            val (t, err) = ScreenTools.locate(ctx, caller, task, args.opt("target"), deadline)
            if (t == null) return err!! to name
            if ("password" in t.item.state) return Tools.refused("password_field", "That's a password field: the agent never types into one.") to name
            req.put("id", t.item.id).put("fp", t.item.fp)
            into = ScreenTools.named(t.item)
            quoted = quotes(t.item)
        }
        val r = act(ctx, task, req, deadline) ?: return ScreenTools.noAnswer() to name
        failure(ctx, task, r)?.let { return it to name }
        val hint = r.optString("field").takeIf { it.isNotEmpty() && it != "null" }
        if (into == null && hint != null) quoted = true
        val field = into ?: hint?.let { "\"${ScreenTools.cut(it)}\"" } ?: "the focused field"
        val chars = if (text.length == 1) "1 character" else "${text.length} characters"
        val how = if (replace) "Replaced the text in $field with $chars" else "Typed $chars into $field"
        val enter = when {
            !submit -> ""
            r.optBoolean("submitted") -> " and pressed Enter"
            else -> ", but Enter didn't go through"
        }
        return done(ctx, task, "$how$enter.", quoted) to name
    }

    fun swipe(ctx: Context, caller: String, args: JSONObject, deadline: Long): Pair<JSONObject, String?> {
        val dir = args.opt("direction") as? String
        if (dir !in setOf("up", "down", "left", "right")) return bad("direction is up, down, left or right: the way the finger moves.")
        val dist: Any = when (val d = args.opt("distance") ?: "short") {
            "short", "long" -> d
            is Number -> intArg(args, "distance")?.takeIf { it in 10..5000 } ?: return bad("distance is short, long, or 10 to 5000 pixels.")
            else -> return bad("distance is short, long, or 10 to 5000 pixels.")
        }
        val hasXY = args.has("x") || args.has("y")
        if (args.has("from") == hasXY) return bad("Give from (a target), or x and y (not both).")
        val (task, refusal) = start(ctx)
        if (task == null) return refusal
        val name = Agent.label(task.top)
        val req = JSONObject().put("op", "swipe").put("dir", dir).put("dist", dist)
        val what: String
        var quoted = false
        if (hasXY) {
            val x = intArg(args, "x"); val y = intArg(args, "y")
            if (x == null || y == null) return bad("x and y are whole numbers: a point on the screen, in pixels.")
            req.put("x", x).put("y", y)
            what = "from $x, $y"
        } else {
            val (t, err) = ScreenTools.locate(ctx, caller, task, args.opt("from"), deadline)
            if (t == null) return err!! to name
            req.put("id", t.item.id).put("fp", t.item.fp)
            what = "from ${ScreenTools.named(t.item)}"
            quoted = quotes(t.item)
        }
        val r = act(ctx, task, req, deadline) ?: return ScreenTools.noAnswer() to name
        failure(ctx, task, r)?.let { return it to name }
        return done(ctx, task, "Swiped $dir $what.", quoted) to name
    }

    fun scroll(ctx: Context, caller: String, args: JSONObject, deadline: Long): Pair<JSONObject, String?> {
        val dir = args.opt("direction") as? String
        if (dir !in setOf("up", "down", "left", "right")) return bad("direction is up, down, left or right: down shows what's further down.")
        val amount = args.opt("amount") ?: "page"
        if (amount != "page" && amount != "half") return bad("amount is page or half.")
        val (task, refusal) = start(ctx)
        if (task == null) return refusal
        val name = Agent.label(task.top)
        val req = JSONObject().put("op", "scroll").put("dir", dir).put("amount", amount)
        var what = "the biggest scrolling part of $name"
        var quoted = false
        if (args.has("target")) {
            val (t, err) = ScreenTools.locate(ctx, caller, task, args.opt("target"), deadline)
            if (t == null) return err!! to name
            req.put("id", t.item.id).put("fp", t.item.fp)
            what = ScreenTools.named(t.item)
            quoted = quotes(t.item)
        }
        val r = act(ctx, task, req, deadline) ?: return ScreenTools.noAnswer() to name
        failure(ctx, task, r)?.let { return it to name }
        return done(ctx, task, "Scrolled $dir ${if (amount == "half") "half a page" else "a page"} in $what.", quoted) to name
    }

    fun key(ctx: Context, args: JSONObject, deadline: Long): Pair<JSONObject, String?> {
        val key = args.opt("key") as? String
        if (key !in setOf("back", "home", "enter")) return bad("key is back, home or enter.")
        val (task, refusal) = start(ctx)
        if (task == null) return refusal
        val name = Agent.label(task.top)
        val r = act(ctx, task, JSONObject().put("op", "key").put("key", key), deadline) ?: return ScreenTools.noAnswer() to name
        failure(ctx, task, r)?.let { return it to name }
        if (key == "home") return Tools.ok(Tools.text("Pressed Home: TabDesk's home screen is in front now, which the agent " +
            "can't read. Open an allowed app with open_app.")) to name
        return done(ctx, task, "Pressed ${key!!.replaceFirstChar { it.uppercase() }}.") to name
    }

    /** Waits until an item matching the locator is on screen (or, with gone, isn't), reading every 0.4 s. */
    fun waitFor(ctx: Context, caller: String, args: JSONObject, deadline: Long): Pair<JSONObject, String?> {
        val gone = boolArg(args, "gone") ?: return bad("gone is true or false.")
        val timeout = if (args.has("timeout_ms")) intArg(args, "timeout_ms")?.takeIf { it in 0..MAX_WAIT_MS }
            ?: return bad("timeout_ms is 0 to $MAX_WAIT_MS.") else 5000
        val loc = args.opt("target") as? JSONObject ?: return bad("target is {\"content_contains\": \"…\"}, with an optional role.")
        if (loc.has("id")) return bad("wait_for looks for an item by what it says: content_contains and role, not an id.")
        val contains = loc.opt("content_contains") as? String
        val role = loc.opt("role") as? String
        if (loc.keys().asSequence().any { it !in setOf("content_contains", "role") } || (contains.isNullOrEmpty() && role.isNullOrEmpty()))
            return bad("target is {\"content_contains\": \"…\"}, with an optional role.")
        // Reading doesn't need the acting switch: wait_for only reads.
        val until = minOf(System.currentTimeMillis() + timeout, deadline - 1500)
        var name: String? = null
        while (true) {
            val (task, refusal) = ScreenTools.readable(ctx)
            if (task == null) return refusal
            name = Agent.label(task.top)
            val (matches, err) = ScreenTools.find(ctx, caller, task, contains, role, deadline)
            if (err != null) return err to name
            if (!gone && matches.isNotEmpty()) {
                val o = JSONObject().put("found", JSONArray(matches.take(10).map { ScreenTools.flat(it) }))
                if (matches.size > 10) o.put("more_matches", matches.size - 10)
                return Tools.ok(Tools.text(ScreenTools.SENTENCE), Tools.text(o.toString())) to name
            }
            if (gone && matches.isEmpty()) return Tools.ok(Tools.text("Gone: nothing on screen matches it now.")) to name
            if (System.currentTimeMillis() + WAIT_EVERY_MS > until) break
            Thread.sleep(WAIT_EVERY_MS)
        }
        val secs = "%.1f".format(timeout / 1000.0)
        return Tools.error("not_found", if (gone) "Still on screen after $secs s." else "Not on screen after $secs s.") to name
    }

    /** Whether naming [i] in an answer quotes the screen: its label or value, not just its role and id. */
    private fun quotes(i: ScreenTools.Item) = i.label != null || i.value != null

    // ------------------------------------------------------------ the helper

    private fun act(ctx: Context, task: HelperClient.Task, req: JSONObject, deadline: Long): JSONObject? =
        HelperClient.act(req.put("pkg", task.top).put("task", task.id), deadline)

    /** What the helper's "ok": false means for the agent; null when it acted. */
    private fun failure(ctx: Context, task: HelperClient.Task, r: JSONObject): JSONObject? {
        if (r.optBoolean("ok")) return null
        val app = Agent.label(task.top)
        val by = r.optJSONObject("by")
        return when (r.optString("why")) {
            "covered" -> Tools.refused("covered", "Covered by ${ScreenTools.windowName(ctx, by, screenSize(ctx))}: a finger there " +
                "would touch that, not $app. Ask the user to move it, or act on another item.")
            "focus" -> if (by != null) Tools.refused("focus", "The keyboard's focus is in ${ScreenTools.windowName(ctx, by, screenSize(ctx))}, " +
                "not $app. Tap the field first (type with a target).")
                else Tools.error("focus", "No field in $app has the keyboard's focus: give type a target to tap first.")
            "password" -> Tools.refused("password_field", "That's a password field: the agent never types into one.")
            "not_editable" -> Tools.error("not_editable", "The focused item in $app isn't a text field.")
            "not_scrollable" -> Tools.error("not_scrollable", "Nothing there scrolls. Read the screen for an item with the state scrollable.")
            "outside" -> Tools.error("outside", "That isn't inside $app's window on screen.")
            "no_item", "stale" -> ScreenTools.stale()
            "failed" -> Tools.error("failed", "The tablet couldn't do that.")
            else -> ScreenTools.failure(ctx, r)
        }
    }

    private fun screenSize(ctx: Context): Pair<Int, Int>? = try {
        val p = android.graphics.Point()
        @Suppress("DEPRECATION") ctx.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0).getRealSize(p)
        p.x to p.y
    } catch (_: Exception) { null }

    /**
     * The answer after an action: what it did, and which app is in front now if that changed. [quoted]: it quotes
     * text from the screen (an item's label or a field's hint), so the fixed sentence comes first, as Part 2 says.
     */
    private fun done(ctx: Context, task: HelperClient.Task, said: String, quoted: Boolean = false): JSONObject {
        fun answer(text: String) = if (quoted) Tools.ok(Tools.text(ScreenTools.SENTENCE), Tools.text(text)) else Tools.ok(Tools.text(text))
        // A tap that opens or closes something takes a moment to show in the task list.
        var now = ScreenTools.frontTask()
        val until = System.currentTimeMillis() + 200
        while (now != null && now.id == task.id && now.top == task.top && System.currentTimeMillis() < until) {
            Thread.sleep(100); now = ScreenTools.frontTask()
        }
        if (now != null && now.id == task.id && now.top == task.top) return answer(said)
        val (front, _) = Tools.inFront(ctx, Never.list(ctx), now)
        return answer("$said ${front?.let { "Now $it is in front." } ?: "Now an app that isn't allowed is in front."}")
    }
}
