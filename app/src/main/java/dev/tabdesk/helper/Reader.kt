package dev.tabdesk.helper

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException
import kotlin.concurrent.thread

/**
 * The helper's screen reading, for agent control (docs/agent-control.md, plan T2). It reads an app's windows the way
 * `uiautomator dump` does, through UiAutomation, and takes a picture of that app alone.
 *
 * - It reads only the app TabDesk names, and only while that app is the one in front. It checks that before the read
 *   and again after it, and answers nothing of what it read if the app in front changed meanwhile.
 * - It never reads a password field's text.
 * - Of other apps' windows over the app it reports only where they are and whose they are, for TabDesk to name or
 *   not; they're blacked out of the picture.
 *
 * While UiAutomation is connected it counts as an accessibility service: apps see accessibility as switched on. So it
 * connects when first needed, with the flag that leaves the user's own accessibility services (TalkBack, TabDesk's
 * double-tap to lock) running, and disconnects after [IDLE_MS] without a read, or when TabDesk says control is idle.
 */
internal object Reader {
    private const val TAG = "TabDeskHelper"
    private const val IDLE_MS = 20_000L
    /** At most this many items in one read. TabDesk sends the agent at most 400, but filters over all of these. */
    private const val MAX_NODES = 3000

    private var ua: UiAutomation? = null
    private var looper: HandlerThread? = null
    private var connection: Any? = null
    @Volatile private var lastUse = 0L

    /** The task in front, by the same rule as TabDesk's (Tools.inFront); null for the home screen. */
    fun front(): Helper.Task? {
        val t = Helper.tasks()
        return t.firstOrNull { it.focused } ?: t.firstOrNull { it.visible && it.mode != 5 }
    }

    /**
     * Reads [pkg]'s windows if it's on top of task [taskId] and that task is in front: the items from [root] (an
     * item id, null for all its windows) down [depth] levels (-1: all), and with [pictureMax] > 0 a picture of the
     * app at most that size. The answer (see HelperClient.read):
     *     {"ok": true, "task": 12, "pkg": "…", "screen": [w, h], "items": [node…], "covers": [{type, pkg, title, bounds}…],
     *      "more": true, "picture": {"jpeg": "<base64>", "width": …, "height": …, "of": [l, t, r, b]}}
     *     {"ok": false, "why": "front", "front": {"pkg": "…", "task": 12} | null}, or "why": "no_windows",
     *     "no_item", "unavailable" (UiAutomation can't connect: another tool is reading the screen), "failed".
     * Each node: {"id", "fp" (see [fingerprint]), "class", "text", "desc", "hint", "showing_hint", "state_desc", "state": […], "bounds": [l, t, r, b],
     * "actions": […], "child_count", "children": […], "omitted": n}; empty ones are left out, and so are nodes that
     * aren't visible. An id is the window's place among the app's windows (top first), then each child's index.
     */
    @Synchronized
    fun read(pkg: String?, taskId: Int, root: String?, depth: Int, pictureMax: Int): JSONObject {
        val task = front()
        if (pkg == null || task == null || task.id != taskId || task.top != pkg) return notInFront(task)
        val u = try { automation() } catch (e: Throwable) {
            Log.w(TAG, "can't read the screen: ${(e as? InvocationTargetException)?.cause ?: e}")
            return JSONObject().put("ok", false).put("why", "unavailable")
        }
        val windows = try { u.windows } catch (e: Exception) { emptyList<AccessibilityWindowInfo>() }   // top first
        val mine = windows.filter { isOf(it, pkg, task) }
        if (mine.isEmpty()) return JSONObject().put("ok", false).put("why", "no_windows")

        val budget = intArrayOf(MAX_NODES)
        val items = JSONArray()
        if (root == null) {
            mine.forEachIndexed { i, w -> w.root?.let { items.put(node(it, "$i", depth, budget)) } }
        } else {
            val n = find(mine, root) ?: return JSONObject().put("ok", false).put("why", "no_item")
            items.put(node(n, root, depth, budget))
        }

        // Other windows over the app's: what they are and whose, never what's in them.
        val myBounds = mine.map { bounds(it) }
        val covers = JSONArray()
        val coverRects = ArrayList<Rect>()
        for (w in windows) {
            if (w in mine) continue
            val b = bounds(w)
            if (mine.indices.none { w.layer > mine[it].layer && Rect.intersects(b, myBounds[it]) }) continue
            coverRects += b
            covers.put(JSONObject().put("type", typeOf(w)).put("pkg", w.root?.packageName?.toString() ?: JSONObject.NULL)
                .put("title", w.title?.toString() ?: JSONObject.NULL).put("bounds", JSONArray(listOf(b.left, b.top, b.right, b.bottom))))
        }

        val picture = if (pictureMax > 0) try {
            // Under a see-through window (a translucent or dialog-like activity) whatever is below shows, and the
            // accessibility window list leaves out what's below, so it can't be blacked out. Android's activity list
            // says what shows through: another app's activity in the same task means no picture; the task itself
            // being see-through means only a picture of the task alone (its snapshot), never of the whole screen.
            when (seeThrough(task.id, pkg)) {
                Through.NOTHING -> picture(u, task, myBounds, coverRects, pictureMax, wholeScreen = true)
                Through.BELOW_TASK -> picture(u, task, myBounds, coverRects, pictureMax, wholeScreen = false)
                else -> null
            }
        } catch (e: Throwable) {
            Helper.log("picture", e); null
        } else null

        // The second look: if another app came to the front meanwhile, nothing read is sent.
        val after = front()
        if (after == null || after.id != task.id || after.top != pkg) return notInFront(after)

        val o = JSONObject().put("ok", true).put("task", task.id).put("pkg", pkg)
            .put("screen", screenSize()?.let { JSONArray(listOf(it.first, it.second)) } ?: JSONObject.NULL)
            .put("items", items).put("covers", covers)
        if (budget[0] <= 0) o.put("more", true)
        if (picture != null) o.put("picture", picture)
        return o
    }

    /** For trying things out from adb: each window on screen, top first. */
    @Synchronized
    fun windows(): List<String> = automation().windows.map { w ->
        val b = bounds(w)
        val id = try { taskIdMethod?.invoke(w) } catch (_: Throwable) { null }
        "${typeOf(w)} layer ${w.layer} task ${id ?: "?"} ${b.left},${b.top},${b.right},${b.bottom} " +
            "${w.root?.packageName} \"${w.title}\"${if (w.isFocused) " focused" else ""}${if (w.isActive) " active" else ""}"
    }

    internal fun notInFront(t: Helper.Task?) = JSONObject().put("ok", false).put("why", "front")
        .put("front", t?.let { JSONObject().put("pkg", it.top).put("task", it.id) } ?: JSONObject.NULL)

    /** Stops reading: UiAutomation lets go, and apps no longer see accessibility as on (unless the user's is). */
    @Synchronized
    fun disconnect() {
        val u = ua ?: return
        ua = null
        try { UiAutomation::class.java.getMethod("disconnect").invoke(u) } catch (e: Throwable) { Helper.log("disconnect", e) }
        try { connection?.let { c -> c.javaClass.getMethod("shutdown").invoke(c) } } catch (_: Throwable) {}
        connection = null
        looper?.quitSafely(); looper = null
        Log.i(TAG, "stopped reading the screen")
    }

    /**
     * Android 16's accessibility client posts to the main looper, which app_process doesn't make: so one runs on a
     * thread of its own (the helper's main thread is busy taking connections).
     */
    private fun mainLooper() {
        if (Looper.getMainLooper() != null) return
        val ready = java.util.concurrent.CountDownLatch(1)
        thread(name = "main-looper", isDaemon = true) {
            @Suppress("DEPRECATION") Looper.prepareMainLooper()
            ready.countDown()
            Looper.loop()
        }
        ready.await()
    }

    internal fun automation(): UiAutomation {
        lastUse = SystemClock.uptimeMillis()
        ua?.let { return it }
        mainLooper()
        val ht = HandlerThread("uia").apply { start() }
        try {
            val conn = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance()
            val iface = Class.forName("android.app.IUiAutomationConnection")
            val u = UiAutomation::class.java.getConstructor(Looper::class.java, iface).newInstance(ht.looper, conn)
            val flags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
            val timed = UiAutomation::class.java.methods.firstOrNull { it.name == "connectWithTimeout" && it.parameterTypes.size == 2 }
            if (timed != null) timed.invoke(u, flags, 5000L)
            else UiAutomation::class.java.getMethod("connect", Int::class.javaPrimitiveType).invoke(u, flags)
            // Windows other than the active one, and only the views that matter to accessibility (as TalkBack sees).
            val info = u.serviceInfo
            info.flags = (info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) and
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
            u.serviceInfo = info
            // Android fills in the list of windows a moment after that: wait until two looks agree.
            var last = ""
            for (i in 0 until 15) {
                Thread.sleep(100)
                val now = try { u.windows.joinToString { "${it.id}" } } catch (_: Exception) { "" }
                if (now.isNotEmpty() && now == last) break
                last = now
            }
            ua = u; looper = ht; connection = conn
            Log.i(TAG, "reading the screen")
            thread(name = "uia-idle", isDaemon = true) {
                while (true) {
                    Thread.sleep(5000)
                    synchronized(this) {
                        if (ua !== u) return@thread
                        if (SystemClock.uptimeMillis() - lastUse > IDLE_MS) { disconnect(); return@thread }
                    }
                }
            }
            return u
        } catch (e: Throwable) {
            ht.quitSafely()
            throw e
        }
    }

    // ------------------------------------------------------------ windows

    private val taskIdMethod by lazy {
        AccessibilityWindowInfo::class.java.methods.firstOrNull { it.name == "getTaskId" && it.parameterTypes.isEmpty() }
    }

    /**
     * Whether [w] is one of [pkg]'s windows in [task]. By the task it's in where Android says (Android 13 and later),
     * else by lying within the task's bounds. Its own package must match too, as a cross-check: that one comes from
     * the app, so it's never enough alone.
     */
    internal fun isOf(w: AccessibilityWindowInfo, pkg: String, task: Helper.Task): Boolean {
        if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) return false
        val id = try { taskIdMethod?.invoke(w) as? Int } catch (_: Throwable) { null }
        if (id != null && id != task.id) return false
        if (id == null) task.rect()?.let { if (!Rect(it).apply { inset(-2, -2) }.contains(bounds(w))) return false }
        return w.root?.packageName?.toString() == pkg
    }

    internal fun bounds(w: AccessibilityWindowInfo) = Rect().also { w.getBoundsInScreen(it) }

    internal fun typeOf(w: AccessibilityWindowInfo) = when (w.type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "divider"
        else -> "other"
    }

    /** The item at [id] ("window.child.child…") among the app's windows; null if there's none. */
    internal fun find(mine: List<AccessibilityWindowInfo>, id: String): AccessibilityNodeInfo? {
        val path = id.split('.').map { it.toIntOrNull() ?: return null }
        var n = mine.getOrNull(path[0])?.root ?: return null
        for (i in path.drop(1)) n = (if (i in 0 until n.childCount) n.getChild(i) else null) ?: return null
        return n.takeIf { it.isVisibleToUser }
    }

    /** The real screen's size, as turned now. */
    internal fun screenSize(): Pair<Int, Int>? = try {
        val dmg = Class.forName("android.hardware.display.DisplayManagerGlobal").getMethod("getInstance").invoke(null)!!
        val info = dmg.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType).invoke(dmg, 0)!!
        (Helper.field(info, "logicalWidth") as Int) to (Helper.field(info, "logicalHeight") as Int)
    } catch (e: Throwable) { Helper.log("screen size", e); null }

    // ------------------------------------------------------------ items

    /**
     * What an item is, in a few characters: from its class, text (never a password field's), description and hint.
     * An action names the item's id and this, from the read the agent saw; just before acting, the item at that id
     * must still have it, or the answer is "stale".
     */
    internal fun fingerprint(n: AccessibilityNodeInfo): String {
        val s = listOf(n.className, if (n.isPassword) "" else n.text, n.contentDescription,
            if (Build.VERSION.SDK_INT >= 26) n.hintText else "").joinToString("\u0000") { it?.toString() ?: "" }
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun node(n: AccessibilityNodeInfo, id: String, depth: Int, budget: IntArray): JSONObject {
        budget[0]--
        val o = JSONObject().put("id", id).put("fp", fingerprint(n))
        fun put(key: String, v: CharSequence?) { if (!v.isNullOrEmpty()) o.put(key, v.toString()) }
        put("class", n.className)
        val password = n.isPassword
        if (!password) put("text", n.text)   // never a password field's text
        put("desc", n.contentDescription)
        if (Build.VERSION.SDK_INT >= 26) {
            put("hint", n.hintText)
            if (n.isShowingHintText) o.put("showing_hint", true)
        }
        if (Build.VERSION.SDK_INT >= 30 && !password) put("state_desc", n.stateDescription)
        val s = JSONArray()
        if (n.isClickable) s.put("clickable")
        if (n.isLongClickable) s.put("long_clickable")
        if (n.isFocused) s.put("focused")
        if (n.isSelected) s.put("selected")
        if (n.isCheckable) s.put(if (n.isChecked) "checked" else "unchecked")
        if (!n.isEnabled) s.put("disabled")
        if (n.isEditable) s.put("editable")
        if (n.isScrollable) s.put("scrollable")
        if (password) s.put("password")
        if (s.length() > 0) o.put("state", s)
        val b = Rect().also { n.getBoundsInScreen(it) }
        o.put("bounds", JSONArray(listOf(b.left, b.top, b.right, b.bottom)))
        val actions = n.actionList.mapNotNull { actionName(it) }.distinct()
        if (actions.isNotEmpty()) o.put("actions", JSONArray(actions))
        o.put("child_count", n.childCount)
        if (depth != 0 && n.childCount > 0) {
            val kids = JSONArray()
            var left = 0
            for (i in 0 until n.childCount) {
                if (budget[0] <= 0) { left = n.childCount - i; break }
                val c = n.getChild(i) ?: continue
                if (!c.isVisibleToUser) continue
                kids.put(node(c, "$id.$i", depth - 1, budget))
            }
            if (kids.length() > 0) o.put("children", kids)
            if (left > 0) o.put("omitted", left)
        }
        return o
    }

    /** Android's standard actions, by the names the agent sees; an app's own (custom) actions are left out. */
    private fun actionName(a: AccessibilityAction): String? = when (a.id) {
        AccessibilityNodeInfo.ACTION_CLICK -> "click"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_click"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
        AccessibilityAction.ACTION_SCROLL_UP.id -> "scroll_up"
        AccessibilityAction.ACTION_SCROLL_DOWN.id -> "scroll_down"
        AccessibilityAction.ACTION_SCROLL_LEFT.id -> "scroll_left"
        AccessibilityAction.ACTION_SCROLL_RIGHT.id -> "scroll_right"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
        AccessibilityNodeInfo.ACTION_EXPAND -> "expand"
        AccessibilityNodeInfo.ACTION_COLLAPSE -> "collapse"
        AccessibilityNodeInfo.ACTION_DISMISS -> "dismiss"
        AccessibilityNodeInfo.ACTION_COPY -> "copy"
        AccessibilityNodeInfo.ACTION_PASTE -> "paste"
        AccessibilityNodeInfo.ACTION_CUT -> "cut"
        AccessibilityAction.ACTION_SET_PROGRESS.id -> "set_progress"
        AccessibilityAction.ACTION_CONTEXT_CLICK.id -> "context_click"
        else -> null
    }

    // ------------------------------------------------------------ the picture

    /**
     * What shows through [pkg]'s activities on top of task [taskId]: NOTHING (one of them hides everything under it),
     * BELOW_TASK (they're all see-through down to the task's bottom, so other tasks, the home screen or the wallpaper
     * show), ANOTHER_APP (another app's activity in the task shows), or UNKNOWN (Android's list didn't say).
     */
    enum class Through { NOTHING, BELOW_TASK, ANOTHER_APP, UNKNOWN }

    private class Activity(val key: String, val index: Int, val pkg: String) {
        var occludes: Boolean? = null
        var visible: Boolean? = null
    }

    private val histRe = Regex("""\* Hist\s+#(\d+): ActivityRecord\{(\S+) u\d+ ([^/\s]+)/\S+ t(\d+)""")
    private val occludesRe = Regex("""\boccludesParent=(true|false)""")
    private val visibleRe = Regex("""\bmVisible=(true|false)""")

    /**
     * By the window manager's own list (dumpsys activity activities): the task's activities top first, each with
     * occludesParent (it hides what's under it, i.e. it isn't translucent or floating) and mVisible.
     */
    private fun seeThrough(taskId: Int, pkg: String): Through {
        val acts = LinkedHashMap<String, Activity>()
        var cur: Activity? = null
        try {
            val p = Runtime.getRuntime().exec(arrayOf("dumpsys", "activity", "activities"))
            p.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val h = histRe.find(line)
                    if (h != null) {
                        val (index, key, apkg, t) = h.destructured
                        // Each activity once, from where it's first listed with its details.
                        cur = if (t.toIntOrNull() == taskId && key !in acts) Activity(key, index.toInt(), apkg).also { acts[key] = it } else null
                        continue
                    }
                    if (line.trimStart().startsWith("* ")) { cur = null; continue }   // a task or another section's list
                    val a = cur ?: continue
                    if (a.occludes == null) occludesRe.find(line)?.let { a.occludes = it.groupValues[1] == "true" }
                    if (a.visible == null) visibleRe.find(line)?.let { a.visible = it.groupValues[1] == "true" }
                }
            }
            p.destroy()
        } catch (e: Throwable) { Helper.log("activities", e); return Through.UNKNOWN }
        val top = acts.values.sortedByDescending { it.index }
        if (top.firstOrNull()?.pkg != pkg) return Through.UNKNOWN
        for (a in top) {
            if (a.visible == false) continue   // not drawn, so nothing of it shows
            if (a.pkg != pkg) return Through.ANOTHER_APP
            when (a.occludes) {
                true -> return Through.NOTHING
                null -> return Through.UNKNOWN
                false -> {}
            }
        }
        return Through.BELOW_TASK
    }

    /**
     * A picture of the app alone, at most [max] on its longer side: its task as Recents would keep it (Android 12L
     * and later), else the whole screen if [wholeScreen] allows. Either way it's cut to the app's windows, and
     * blacked out wherever none of them is, and wherever [covers] (other windows over the app) are.
     */
    private fun picture(u: UiAutomation, task: Helper.Task, mine: List<Rect>, covers: List<Rect>, max: Int,
                        wholeScreen: Boolean): JSONObject? {
        val screen = screenSize()?.let { Rect(0, 0, it.first, it.second) } ?: return null
        val area = Rect().also { r -> mine.forEach { r.union(it) } }
        if (!area.intersect(screen)) return null

        var src: Bitmap? = null
        var of = screen
        val taskRect = task.rect() ?: screen
        Helper.taskPicture(task.id, fresh = true)?.let { b ->
            // Only if it's of the task as it is now (not, say, before the tablet was turned).
            val ratio = b.width.toFloat() / b.height / (taskRect.width().toFloat() / taskRect.height())
            if (ratio in 0.97f..1.03f) { src = b; of = taskRect } else b.recycle()
        }
        if (src == null && !wholeScreen) return null
        if (src == null) src = u.takeScreenshot()?.let { s ->
            (if (s.config == Bitmap.Config.ARGB_8888) s else s.copy(Bitmap.Config.ARGB_8888, false).also { s.recycle() })
        }
        val full = src ?: return null
        if (!area.intersect(of)) { full.recycle(); return null }

        // Screen pixels to this picture's.
        val sx = full.width.toFloat() / of.width()
        val sy = full.height.toFloat() / of.height()
        fun map(r: Rect) = Rect(((r.left - of.left) * sx).toInt(), ((r.top - of.top) * sy).toInt(),
            ((r.right - of.left) * sx + 0.999f).toInt(), ((r.bottom - of.top) * sy + 0.999f).toInt())
        val cut = map(area)
        if (!cut.intersect(0, 0, full.width, full.height) || cut.isEmpty) { full.recycle(); return null }
        val pic = Bitmap.createBitmap(cut.width(), cut.height(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(pic)
        canvas.drawBitmap(full, cut, Rect(0, 0, cut.width(), cut.height()), null)
        full.recycle()

        // Black wherever the app's own windows aren't, and wherever another window covers them.
        fun local(r: Rect) = map(r).apply { offset(-cut.left, -cut.top) }
        val black = Region(0, 0, pic.width, pic.height)
        for (r in mine) black.op(local(r), Region.Op.DIFFERENCE)
        for (r in covers) black.op(local(r), Region.Op.UNION)
        black.op(0, 0, pic.width, pic.height, Region.Op.INTERSECT)
        val paint = Paint().apply { color = Color.BLACK }
        val it = RegionIterator(black)
        val r = Rect()
        while (it.next(r)) canvas.drawRect(r, paint)

        val scale = minOf(1f, max.toFloat() / maxOf(pic.width, pic.height))
        val out = if (scale < 1f) Bitmap.createScaledBitmap(pic, (pic.width * scale).toInt().coerceAtLeast(1),
            (pic.height * scale).toInt().coerceAtLeast(1), true) else pic
        val bytes = java.io.ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        val o = JSONObject().put("jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("width", out.width).put("height", out.height)
            .put("of", JSONArray(listOf(area.left, area.top, area.right, area.bottom)))
        if (out !== pic) out.recycle()
        pic.recycle()
        return o
    }
}
