package dev.tabdesk.helper

import android.accessibilityservice.AccessibilityService
import android.app.UiAutomation
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.InvocationTargetException

/**
 * The helper's acting, for agent control (docs/agent-control.md, tool list 2, plan T10): taps, swipes, scrolls, keys
 * and typing in the app TabDesk names, like a finger or a keyboard would.
 *
 * Just before each one, as the plan asks:
 * - the app is still on top of its task, and the task is in front;
 * - the item is still the one the agent saw (its fingerprint, [Reader.fingerprint]), or the answer is "stale";
 * - for a touch, the window under the point is the app's own, or the answer is "covered", naming whose it is;
 * - for typing and keys, the focused window is the app's, and the field isn't a password field.
 *
 * A gesture always ends with its finger lifted, even when TabDesk stops waiting for the answer.
 */
internal object Actor {
    private const val TAG = "TabDeskHelper"
    private const val TAP_MS = 60L
    private const val LONG_MS = 800L
    private const val SWIPE_MS = 300L
    private const val SCROLL_MS = 450L
    private const val MAX_TEXT = 2000

    private class Failed(val o: JSONObject) : Exception()

    private fun fail(why: String, by: AccessibilityWindowInfo? = null): Nothing {
        val o = JSONObject().put("ok", false).put("why", why)
        if (by != null) o.put("by", describe(by))
        throw Failed(o)
    }

    /** Whose a window is, for TabDesk to name or not: never what's in it. */
    private fun describe(w: AccessibilityWindowInfo) = JSONObject().put("type", Reader.typeOf(w))
        .put("pkg", w.root?.packageName?.toString() ?: JSONObject.NULL).put("title", w.title?.toString() ?: JSONObject.NULL)
        .put("bounds", Reader.bounds(w).let { JSONArray(listOf(it.left, it.top, it.right, it.bottom)) })

    /**
     * One action, as TabDesk asks it: {"op": "tap" | "swipe" | "scroll" | "type" | "key", "pkg", "task", and
     * "id" + "fp" (an item from the agent's read), or "x", "y"; tap: "long"; swipe: "dir" (up, down, left, right: the
     * way the finger moves), "dist" ("short", "long" or pixels); scroll: "dir" (the way the content goes), "amount"
     * ("page", "half"); type: "text", "replace", "submit"; key: "key" (back, home, enter)}.
     * Answers {"ok": true, "at": [x, y], …}, or {"ok": false, "why": "front" | "no_windows" | "no_item" | "stale" |
     * "covered" (with "by") | "outside" | "focus" (with "by" when another app's window has it) | "password" |
     * "not_editable" | "not_scrollable" | "unavailable" | "failed"}.
     */
    fun act(r: JSONObject): JSONObject = synchronized(Reader) {
        try {
            run(r)
        } catch (f: Failed) {
            f.o
        } catch (e: Throwable) {
            Log.w(TAG, "act: ${(e as? InvocationTargetException)?.cause ?: e}")
            JSONObject().put("ok", false).put("why", "failed")
        }
    }

    private fun run(r: JSONObject): JSONObject {
        val pkg = r.getString("pkg")
        val taskId = r.getInt("task")
        val task = Reader.front()
        if (task == null || task.id != taskId || task.top != pkg) return Reader.notInFront(task)
        val u = try { Reader.automation() } catch (e: Throwable) {
            Log.w(TAG, "can't act: ${(e as? InvocationTargetException)?.cause ?: e}")
            return JSONObject().put("ok", false).put("why", "unavailable")
        }
        val windows = windowsOf(u)
        val mine = windows.filter { Reader.isOf(it, pkg, task) }
        if (mine.isEmpty()) fail("no_windows")

        val id = r.optString("id").takeIf { it.isNotEmpty() }
        val node = id?.let { Reader.find(mine, it) ?: fail("no_item") }
        if (node != null && Reader.fingerprint(node) != r.optString("fp")) fail("stale")

        return when (r.getString("op")) {
            "tap" -> {
                val p = pointFor(node, r, mine)
                checkUnder(windows, mine, p)
                touch(u, listOf(p), 0, if (r.optBoolean("long")) LONG_MS else TAP_MS)
                ok(p)
            }
            "swipe" -> {
                val from = pointFor(node, r, mine)
                checkUnder(windows, mine, from)
                val to = swipeEnd(from, r.getString("dir"), r.opt("dist"), mine)
                touch(u, line(from, to, 12), SWIPE_MS, 0)
                ok(from).put("to", JSONArray(listOf(to.x.toInt(), to.y.toInt())))
            }
            "scroll" -> scroll(u, windows, mine, node, r)
            "type" -> type(u, mine, node, r, pkg, task)
            "key" -> key(u, windows, mine, r.getString("key"))
            else -> fail("failed")
        }
    }

    private fun ok(p: PointF) = JSONObject().put("ok", true).put("at", JSONArray(listOf(p.x.toInt(), p.y.toInt())))

    private fun windowsOf(u: UiAutomation): List<AccessibilityWindowInfo> =
        try { u.windows } catch (_: Exception) { emptyList() }

    // ------------------------------------------------------------ where

    /** The item's centre (the part of it inside its window and the screen), or the agent's x, y inside the app. */
    private fun pointFor(node: AccessibilityNodeInfo?, r: JSONObject, mine: List<AccessibilityWindowInfo>): PointF {
        if (node != null) {
            val b = Rect().also { node.getBoundsInScreen(it) }
            mine.firstOrNull { it.id == node.windowId }?.let { if (!b.intersect(Reader.bounds(it))) fail("outside") }
            Reader.screenSize()?.let { (w, h) -> if (!b.intersect(0, 0, w, h)) fail("outside") }
            if (b.isEmpty) fail("outside")
            return PointF(b.exactCenterX(), b.exactCenterY())
        }
        if (!r.has("x") || !r.has("y")) fail("failed")
        val x = r.getInt("x")
        val y = r.getInt("y")
        if (mine.none { Reader.bounds(it).contains(x, y) }) fail("outside")
        return PointF(x + 0.5f, y + 0.5f)
    }

    /** The part of the screen a window takes touches in: its touchable region where Android says (13 and later). */
    private fun regionOf(w: AccessibilityWindowInfo): Region {
        if (Build.VERSION.SDK_INT >= 33) {
            val reg = Region()
            try { w.getRegionInScreen(reg); if (!reg.isEmpty) return reg } catch (_: Throwable) {}
        }
        return Region(Reader.bounds(w))
    }

    /** The window a finger at [p] would touch is one of the app's, or the answer is "covered", naming whose. */
    private fun checkUnder(windows: List<AccessibilityWindowInfo>, mine: List<AccessibilityWindowInfo>, p: PointF) {
        val x = p.x.toInt()
        val y = p.y.toInt()
        val at = windows.filter { regionOf(it).contains(x, y) }.maxByOrNull { it.layer } ?: fail("outside")
        if (mine.none { it.id == at.id }) fail("covered", at)
    }

    /** Where a swipe from [from] ends: [dir] by [dist], kept inside the app's windows. */
    private fun swipeEnd(from: PointF, dir: String, dist: Any?, mine: List<AccessibilityWindowInfo>): PointF {
        val area = Rect().also { a -> mine.forEach { a.union(Reader.bounds(it)) } }
        val (sw, sh) = Reader.screenSize() ?: (area.width() to area.height())
        val along = if (dir == "left" || dir == "right") sw else sh
        val d = when (dist) {
            null, "short" -> along * 0.2f
            "long" -> along * 0.5f
            is Number -> dist.toFloat()
            else -> fail("failed")
        }
        val (dx, dy) = when (dir) {
            "up" -> 0f to -d; "down" -> 0f to d; "left" -> -d to 0f; "right" -> d to 0f
            else -> fail("failed")
        }
        return PointF((from.x + dx).coerceIn(area.left + 1f, area.right - 2f), (from.y + dy).coerceIn(area.top + 1f, area.bottom - 2f))
    }

    private fun line(a: PointF, b: PointF, steps: Int) = (0..steps).map { i ->
        val t = i.toFloat() / steps
        PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }

    // ------------------------------------------------------------ touches

    private fun motion(down: Long, at: Long, action: Int, p: PointF): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = 1f; size = 1f })
        return MotionEvent.obtain(down, at, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    /**
     * A finger down at the first point, along the others over [moveMs], held [holdMs] at the last, then lifted. It's
     * always lifted, whatever happens on the way.
     */
    private fun touch(u: UiAutomation, points: List<PointF>, moveMs: Long, holdMs: Long) {
        val down = SystemClock.uptimeMillis()
        var last = points.first()
        fun inject(action: Int, p: PointF) = motion(down, SystemClock.uptimeMillis(), action, p).let { e ->
            try { u.injectInputEvent(e, true) } finally { e.recycle() }
        }
        if (!inject(MotionEvent.ACTION_DOWN, last)) fail("failed")
        try {
            val step = if (points.size > 1) moveMs / (points.size - 1) else 0
            for (p in points.drop(1)) {
                if (step > 0) SystemClock.sleep(step)
                inject(MotionEvent.ACTION_MOVE, p)
                last = p
            }
            if (holdMs > 0) SystemClock.sleep(holdMs)
        } finally {
            inject(MotionEvent.ACTION_UP, last)
        }
    }

    // ------------------------------------------------------------ scrolling

    /**
     * Scrolls [node] (a scrollable item), or else the biggest scrollable item in the app, as a finger dragging inside
     * it: "down" shows what's further down. A page is 70% of it, half is 35%; slow enough not to fling.
     */
    private fun scroll(u: UiAutomation, windows: List<AccessibilityWindowInfo>, mine: List<AccessibilityWindowInfo>,
                       node: AccessibilityNodeInfo?, r: JSONObject): JSONObject {
        val target = node ?: biggestScrollable(mine) ?: fail("not_scrollable")
        if (!target.isScrollable) fail("not_scrollable")
        val b = Rect().also { target.getBoundsInScreen(it) }
        mine.firstOrNull { it.id == target.windowId }?.let { if (!b.intersect(Reader.bounds(it))) fail("outside") }
        if (b.width() < 20 || b.height() < 20) fail("outside")
        val part = if (r.optString("amount", "page") == "half") 0.35f else 0.7f
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val hx = b.width() * part / 2
        val hy = b.height() * part / 2
        // The finger moves against the content: to see further down, it drags up.
        val (from, to) = when (r.getString("dir")) {
            "down" -> PointF(cx, cy + hy) to PointF(cx, cy - hy)
            "up" -> PointF(cx, cy - hy) to PointF(cx, cy + hy)
            "right" -> PointF(cx + hx, cy) to PointF(cx - hx, cy)
            "left" -> PointF(cx - hx, cy) to PointF(cx + hx, cy)
            else -> fail("failed")
        }
        checkUnder(windows, mine, from)
        touch(u, line(from, to, 15), SCROLL_MS, 150)
        return ok(from).put("to", JSONArray(listOf(to.x.toInt(), to.y.toInt())))
            .put("scrolled", target.className?.toString() ?: JSONObject.NULL)
    }

    private fun biggestScrollable(mine: List<AccessibilityWindowInfo>): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        var budget = 3000
        fun walk(n: AccessibilityNodeInfo) {
            if (budget-- <= 0 || !n.isVisibleToUser) return
            if (n.isScrollable) {
                val b = Rect().also { n.getBoundsInScreen(it) }
                val area = b.width().toLong() * b.height()
                if (area > bestArea) { best = n; bestArea = area }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        mine.forEach { w -> w.root?.let { walk(it) } }
        return best
    }

    // ------------------------------------------------------------ typing and keys

    /** The window with the keyboard's focus is the app's, or the answer is "focus", naming whose it is. */
    private fun checkFocus(windows: List<AccessibilityWindowInfo>, mine: List<AccessibilityWindowInfo>) {
        val focused = windows.firstOrNull { it.isFocused } ?: windows.firstOrNull { it.isActive } ?: fail("focus")
        if (mine.none { it.id == focused.id }) fail("focus", focused)
    }

    /**
     * Types [r]'s text into the field with the input focus: after tapping [node] first if there is one, which must
     * then be the field (or hold it). The text goes at the end of what's there, or replaces it with "replace"; with
     * "submit", Enter follows. Never into a password field.
     */
    private fun type(u: UiAutomation, mine0: List<AccessibilityWindowInfo>, node: AccessibilityNodeInfo?, r: JSONObject,
                     pkg: String, task: Helper.Task): JSONObject {
        val text = r.getString("text")
        if (text.length > MAX_TEXT) fail("failed")
        var windows = windowsOf(u)
        var mine = mine0
        var at: PointF? = null
        if (node != null) {
            if (node.isPassword) fail("password")
            val p = pointFor(node, r, mine)
            checkUnder(windows, mine, p)
            touch(u, listOf(p), 0, TAP_MS)
            at = p
        }
        // The field the keyboard types into: after a tap it takes a moment to get the focus.
        var field: AccessibilityNodeInfo? = null
        val until = SystemClock.uptimeMillis() + 1500
        while (true) {
            val f = try { u.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
            if (f != null && (node == null || inside(f, node))) { field = f; break }
            if (SystemClock.uptimeMillis() > until) break
            SystemClock.sleep(100)
        }
        windows = windowsOf(u)
        mine = windows.filter { Reader.isOf(it, pkg, task) }
        val f = field ?: fail("focus")
        val fw = windows.firstOrNull { it.id == f.windowId }
        if (fw == null || mine.none { it.id == fw.id }) fail("focus", fw)
        checkFocus(windows, mine)
        if (f.isPassword) fail("password")
        if (!f.isEditable) fail("not_editable")
        val current = if (f.isShowingHintText) "" else f.text?.toString() ?: ""
        val now = if (r.optBoolean("replace")) text else current + text
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, now) }
        if (!f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) fail("failed")
        var submitted = false
        if (r.optBoolean("submit")) {
            submitted = f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) ||
                pressKey(u, KeyEvent.KEYCODE_ENTER)
        }
        val o = JSONObject().put("ok", true).put("typed", text.length).put("field", fieldName(f))
        if (at != null) o.put("at", JSONArray(listOf(at.x.toInt(), at.y.toInt())))
        if (r.optBoolean("submit")) o.put("submitted", submitted)
        return o
    }

    /** Whether [f] is [target] or lies within it (a field inside the item that was tapped). */
    private fun inside(f: AccessibilityNodeInfo, target: AccessibilityNodeInfo): Boolean {
        if (f == target) return true
        if (f.windowId != target.windowId) return false
        val a = Rect().also { target.getBoundsInScreen(it) }
        val b = Rect().also { f.getBoundsInScreen(it) }
        return a.contains(b)
    }

    /** A field's name for the answer: its hint or description, never its text. */
    private fun fieldName(f: AccessibilityNodeInfo): Any =
        (f.hintText ?: f.contentDescription)?.toString()?.take(100) ?: JSONObject.NULL

    private fun key(u: UiAutomation, windows: List<AccessibilityWindowInfo>, mine: List<AccessibilityWindowInfo>, key: String): JSONObject {
        when (key) {
            // Home leaves the app for TabDesk's home screen, wherever the focus is.
            "home" -> if (!u.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) fail("failed")
            // Back and Enter go to the focused window, which must be the app's.
            "back" -> { checkFocus(windows, mine); if (!u.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) fail("failed") }
            "enter" -> { checkFocus(windows, mine); if (!pressKey(u, KeyEvent.KEYCODE_ENTER)) fail("failed") }
            else -> fail("failed")
        }
        return JSONObject().put("ok", true)
    }

    private fun pressKey(u: UiAutomation, code: Int): Boolean {
        val t = SystemClock.uptimeMillis()
        fun ev(action: Int) = KeyEvent(t, SystemClock.uptimeMillis(), action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            InputDevice.SOURCE_KEYBOARD)
        val down = u.injectInputEvent(ev(KeyEvent.ACTION_DOWN), true)
        val up = u.injectInputEvent(ev(KeyEvent.ACTION_UP), true)
        return down && up
    }
}
