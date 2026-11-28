package dev.tabdesk

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import dev.tabdesk.helper.HelperClient

/**
 * Window management, through the helper: a taskbar button minimises its app's window or brings it back, the app
 * menu snaps floating windows to half the screen, Show desktop clears the screen and brings it all back, and the
 * pointer resting on a running app shows its windows. Without the helper none of this shows, and the taskbar opens
 * apps as before.
 */
internal object Windows {
    private const val ME = "dev.tabdesk"

    val available get() = HelperClient.connected

    /**
     * Every open window but TabDesk's own, front to back as Recents orders them. (Their "last active" times don't
     * follow: a window brought to the front by TabDesk keeps its old one.)
     */
    private fun open() = HelperClient.tasks.filter { it.running && it.pkg != ME }

    /** An app's open windows, front to back. */
    fun of(pkg: String): List<HelperClient.Task> = open().filter { it.pkg == pkg }

    /** The window in use: the focused one, or else the frontmost on screen. */
    private fun top(): HelperClient.Task? {
        val shown = open().filter { it.visible }
        return shown.firstOrNull { it.focused } ?: shown.firstOrNull()
    }

    /**
     * A tap on a running app's taskbar button, as on a desktop taskbar: the window in use minimises, a minimised
     * one (or one behind others) comes to the front, and with several windows their previews show to pick from.
     * False if the app has no window open: then it opens as usual.
     */
    fun tap(act: HomeActivity, app: AppInfo, anchor: View): Boolean {
        if (!available) return false
        val mine = of(app.pkg)
        if (mine.isEmpty()) return false
        if (mine.size > 1) {
            when {
                Previews.showing(app.pkg) -> Previews.pin()     // the pointer opened them: keep them
                !Previews.justClosed(app.pkg) -> Previews.show(act, app, anchor, sticky = true)
            }
            return true
        }
        Previews.hide()
        val t = mine[0]
        if (t.visible && top()?.id == t.id) HelperClient.minimise(t.id) else HelperClient.front(t.id)
        return true
    }

    /**
     * Apps that, for safety, hide other apps' windows drawn above them while they're on screen: Android's Settings,
     * permission requests and the installer. TabDesk isn't told, so it has to know them.
     */
    /**
     * TabDesk may draw above other windows, and what it draws there would be seen: some windows (permission
     * screens, the installer, and on some tablets Settings) make Android hide other apps' overlays while they show.
     */
    fun canDrawAbove(act: HomeActivity) = Settings.canDrawOverlays(act) && !HelperClient.overlaysHidden

    /** A floating window is on screen and TabDesk can draw above it: menus go above windows, or they'd be hidden. */
    fun covering(act: HomeActivity) = available && open().any { it.visible && it.floating } && canDrawAbove(act)

    // ------------------------------------------------------------ show desktop

    /** The windows Show desktop minimised, back to front, to bring back in that order (so the front one ends on top). */
    private var cleared: List<Int> = emptyList()

    /** Minimises every window on screen; pressed again (nothing on screen), brings them back. */
    fun showDesktop(act: HomeActivity) {
        if (minimiseAll() > 0) return
        val back = cleared.filter { id -> open().any { it.id == id } }
        cleared = emptyList()
        if (back.isEmpty()) act.toast("No windows to bring back") else back.forEach { HelperClient.front(it) }
    }

    /**
     * Show desktop's first half: minimises every window on screen and remembers them, so the next press of Show
     * desktop brings them back. Also an agent's `key` "minimise_all", which never brings any back, so a phrase can't
     * reopen the user's windows. TabDesk's own windows stay, as they always have with Show desktop. On the main
     * thread. Answers how many it minimised.
     */
    fun minimiseAll(): Int {
        val shown = open().filter { it.visible }.reversed()
        if (shown.isEmpty()) return 0
        cleared = shown.map { it.id }
        shown.forEach { HelperClient.minimise(it.id) }
        return shown.size
    }

    // ------------------------------------------------------------ snapping

    enum class Snap { LEFT, RIGHT, TOP, BOTTOM, FILL }

    /** A floating window's place before TabDesk snapped it, for "Back to its size". Forgotten once it closes. */
    private val before = HashMap<Int, Rect>()

    /**
     * How TabDesk last snapped a window, to fit it again when the screen turns: [want] is where TabDesk asked for it
     * ([since] is when), [at] where Android actually put it, which can differ a little.
     */
    private class Snapped(val where: Snap, val want: Rect, val landscape: Boolean) {
        var since = SystemClock.uptimeMillis()
        var at: Rect? = null
    }
    private val snapped = HashMap<Int, Snapped>()

    /** When the screen last turned: windows move by themselves for a moment after. */
    private var turnedAt = 0L

    /** The screen's shape now, from the window manager (the activity's own configuration can lag a turn). */
    private fun landscapeNow(act: HomeActivity) = act.windowManager.currentWindowMetrics.bounds.let { it.width() > it.height() }

    private fun rectFor(act: HomeActivity, where: Snap): Rect {
        val a = act.workArea()
        return when (where) {
            Snap.LEFT -> Rect(a.left, a.top, a.centerX(), a.bottom)
            Snap.RIGHT -> Rect(a.centerX(), a.top, a.right, a.bottom)
            Snap.TOP -> Rect(a.left, a.top, a.right, a.centerY())
            Snap.BOTTOM -> Rect(a.left, a.centerY(), a.right, a.bottom)
            Snap.FILL -> Rect(a)
        }
    }

    private fun snap(act: HomeActivity, t: HelperClient.Task, where: Snap) {
        val r = rectFor(act, where)
        if (t.id !in before) t.bounds?.let { before[t.id] = Rect(it) }
        snapped[t.id] = Snapped(where, r, landscapeNow(act))
        HelperClient.resize(t.id, r)
        HelperClient.front(t.id)
    }

    private fun unsnap(act: HomeActivity, t: HelperClient.Task) {
        val r = before.remove(t.id) ?: return
        snapped.remove(t.id)
        // Its old place may be off the screen since it turned: keep the size, as far as it fits, inside.
        val a = act.workArea()
        val w = minOf(r.width(), a.width()); val h = minOf(r.height(), a.height())
        val left = r.left.coerceIn(a.left, a.right - w); val top = r.top.coerceIn(a.top, a.bottom - h)
        HelperClient.resize(t.id, Rect(left, top, left + w, top + h))
        HelperClient.front(t.id)
    }

    /**
     * Each new window list: a snapped window that has since moved, while the screen wasn't turning, was moved by
     * hand, so TabDesk leaves it be from then on. (Some tablets move floating windows themselves when the screen
     * turns, which isn't the user's doing.)
     */
    fun track(act: HomeActivity) {
        if (snapped.isEmpty()) return
        val alive = open().associateBy { it.id }
        val now = SystemClock.uptimeMillis()
        for ((id, s) in snapped.toList()) {
            val t = alive[id]
            if (t == null || !t.floating) { snapped.remove(id); continue }
            val b = t.bounds ?: continue
            if (s.landscape != landscapeNow(act)) continue   // turning: refit sees to it
            val at = s.at
            // Just snapped or turned: wherever Android puts it is where it belongs.
            if (at == null || now - maxOf(s.since, turnedAt) < SETTLE_MS) s.at = Rect(b)
            else if (b != at) snapped.remove(id)
        }
    }

    private const val SETTLE_MS = 3000L

    /**
     * The screen turned: Android leaves floating windows where they were, so a snapped one would run off the screen
     * and over the taskbar. Windows TabDesk snapped, and nobody has moved since, fit the new shape: left becomes top,
     * right becomes bottom, and back.
     */
    fun refit(act: HomeActivity) {
        if (!available) return
        val alive = open().associateBy { it.id }
        snapped.keys.retainAll(alive.keys)
        before.keys.retainAll(alive.keys)
        val landscape = landscapeNow(act)
        var turned = false
        for ((id, s) in snapped.toList()) {
            if (s.landscape == landscape || alive[id]?.floating != true) continue
            val where = when (s.where) {
                Snap.LEFT, Snap.TOP -> if (landscape) Snap.LEFT else Snap.TOP
                Snap.RIGHT, Snap.BOTTOM -> if (landscape) Snap.RIGHT else Snap.BOTTOM
                Snap.FILL -> Snap.FILL
            }
            val r = rectFor(act, where)
            snapped[id] = Snapped(where, r, landscape)
            HelperClient.resize(id, r)
            turned = true
        }
        if (!turned) return
        turnedAt = SystemClock.uptimeMillis()
        // Some tablets place floating windows again a moment after a turn, over TabDesk's: once more, if so.
        act.root.postDelayed({
            for ((id, s) in snapped) {
                val b = open().firstOrNull { it.id == id }?.bounds ?: continue
                if (s.landscape == landscapeNow(act) && b != s.want) {
                    s.since = SystemClock.uptimeMillis(); s.at = null
                    HelperClient.resize(id, s.want)
                }
            }
        }, 1200)
    }

    /** The window entries of an app's menu (its newest window): minimise or restore, and for a floating one, snap. */
    fun menuEntries(act: HomeActivity, app: AppInfo): List<MenuEntry> {
        if (!available) return emptyList()
        before.keys.retainAll(open().map { it.id }.toSet())
        val t = of(app.pkg).firstOrNull() ?: return emptyList()
        val e = ArrayList<MenuEntry>()
        e += if (t.visible) MenuEntry("Minimise", G.MINIMISE) { HelperClient.minimise(t.id) }
            else MenuEntry("Restore", G.WINDOW) { HelperClient.front(t.id) }
        if (t.floating) {
            if (act.isLandscape) {
                e += MenuEntry("Snap left", G.SNAP_LEFT) { snap(act, t, Snap.LEFT) }
                e += MenuEntry("Snap right", G.SNAP_RIGHT) { snap(act, t, Snap.RIGHT) }
            } else {
                e += MenuEntry("Snap to top", G.SNAP_TOP) { snap(act, t, Snap.TOP) }
                e += MenuEntry("Snap to bottom", G.SNAP_BOTTOM) { snap(act, t, Snap.BOTTOM) }
            }
            e += MenuEntry("Fill the screen", G.RESIZE) { snap(act, t, Snap.FILL) }
            if (t.id in before) e += MenuEntry("Back to its size", G.RESTORE) { unsnap(act, t) }
        }
        return e
    }
}

/**
 * Previews of an app's windows, above its taskbar button: while a mouse pointer rests on the button, or after a tap
 * on an app with several windows. A preview brings its window to the front; its × closes it.
 *
 * They show above floating windows, which cover TabDesk's own window, in an overlay ("Display over other apps", which
 * the helper grants). Without it they show in TabDesk's window, behind any floating window that reaches them.
 */
internal object Previews {
    private val main = Handler(Looper.getMainLooper())
    /** What's on screen: an overlay window's view, or a popup in TabDesk's window. */
    private var overlay: View? = null
    private var overlayWm: WindowManager? = null
    private var popup: PopupWindow? = null
    private var showingFor: String? = null
    private var sticky = false
    /** The tap previews a touch elsewhere just closed, so a tap on their own button closes them, not reopens them. */
    private var closedFor: String? = null
    private var closedAt = 0L
    /** The last picture of each window, shown at once while a fresh one comes. */
    private val cache = HashMap<Int, Bitmap>()
    private var pending: Runnable? = null
    private val hideSoon = Runnable { hide() }

    private val shown get() = overlay != null || popup != null

    fun showing(pkg: String) = shown && showingFor == pkg

    fun justClosed(pkg: String) = closedFor == pkg && SystemClock.uptimeMillis() - closedAt < 700

    /** Keeps previews the pointer opened, as if a tap had opened them: clicked on their button. */
    fun pin() { sticky = true }

    /** The pointer came onto a taskbar button ([on]) or left it. */
    fun hover(act: HomeActivity, app: AppInfo, anchor: View, on: Boolean) {
        pending?.let { main.removeCallbacks(it) }; pending = null
        if (!on) { if (!sticky) main.postDelayed(hideSoon, 300); return }
        main.removeCallbacks(hideSoon)
        if (!Windows.available || Windows.of(app.pkg).isEmpty()) { if (!sticky) hide(); return }
        if (showing(app.pkg)) return
        // Already showing another app's: switch at once, as the pointer moves along the taskbar.
        val r = Runnable { pending = null; show(act, app, anchor, sticky = false) }
        pending = r
        main.postDelayed(r, if (shown) 0L else 450L)
    }

    fun show(act: HomeActivity, app: AppInfo, anchor: View, sticky: Boolean) {
        hide()
        val windows = Windows.of(app.pkg)
        if (windows.isEmpty() || !anchor.isAttachedToWindow) return
        cache.keys.retainAll(HelperClient.tasks.map { it.id }.toSet())
        val pal = act.pal
        val tileW = act.dp(210)
        val row = LinearLayout(act).apply {
            setPadding(act.dp(6), act.dp(6), act.dp(6), act.dp(6))
            background = rounded(pal.surfaceSolid, act.dpf(16), 1, pal.divider)
            elevation = act.dpf(14)
        }

        windows.forEachIndexed { i, t ->
            val tile = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = rippleBg(pal.ripple, act.dpf(12))
                setPadding(act.dp(8), act.dp(4), act.dp(4), act.dp(8))
                contentDescription = "${app.label}, window ${i + 1}"
            }
            val head = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
            head.addView(TextView(act).label(pal.text, 13f, bold = true).apply {
                text = if (windows.size > 1) "${app.label} ${i + 1}" else app.label
                isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            }, llp(0, WRAP, 1f))
            if (!t.visible) head.addView(TextView(act).label(pal.textDim, 11.5f).apply {
                text = "Minimised"; setPadding(act.dp(6), 0, act.dp(2), 0)
            }, llp(WRAP, WRAP))
            head.addView(ImageView(act).apply {
                setImageDrawable(Glyph(G.CLOSE, pal.textDim))
                setPadding(act.dp(7), act.dp(7), act.dp(7), act.dp(7))
                background = rippleBg(pal.ripple, act.dpf(14))
                contentDescription = "Close"
                tooltipText = "Close"
                setOnClickListener {
                    HelperClient.close(t.id)
                    row.removeView(tile)
                    if (row.childCount == 0) hide()
                }
            }, llp(act.dp(30), act.dp(30)))
            tile.addView(head, llp(MATCH, WRAP))

            // The picture, shaped like the window; a soft blank until it comes.
            val b = t.bounds
            val imgH = if (b != null && b.width() > 0) (tileW * b.height() / b.width()).coerceIn(act.dp(90), act.dp(160)) else act.dp(130)
            val img = ImageView(act).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(pal.field, act.dpf(8))
                clipToOutline = true
                cache[t.id]?.let { setImageBitmap(it) }
            }
            tile.addView(img, llp(MATCH, imgH).apply { topMargin = act.dp(2) })
            HelperClient.preview(t.id, act.dp(240)) { bmp ->
                if (bmp != null) { cache[t.id] = bmp; img.setImageBitmap(bmp) }
            }
            tile.setOnClickListener { HelperClient.front(t.id); hide() }
            row.addView(tile, llp(tileW, WRAP).apply { if (i > 0) marginStart = act.dp(4) })
        }

        // While the pointer is on the previews they stay; leaving them hides them (unless they came from a tap).
        row.setOnHoverListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> main.removeCallbacks(hideSoon)
                MotionEvent.ACTION_HOVER_EXIT -> if (!this.sticky) main.postDelayed(hideSoon, 300)
            }
            false
        }
        // A touch anywhere else closes the ones a tap opened.
        val outside = View.OnTouchListener { _, e ->
            if (e.actionMasked != MotionEvent.ACTION_OUTSIDE) return@OnTouchListener false
            if (this.sticky) { closedFor = showingFor; closedAt = SystemClock.uptimeMillis(); hide() }
            true
        }

        // Above the button, centred on it, kept on screen.
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = row.measuredWidth; val h = row.measuredHeight
        val a = anchor.screenRect()
        val screen = act.windowManager.currentWindowMetrics.bounds
        val x = (a.centerX() - w / 2).coerceIn(act.dp(8), maxOf(act.dp(8), screen.width() - w - act.dp(8)))
        val y = maxOf(act.dp(8), a.top - h - act.dp(8))
        showingFor = app.pkg; this.sticky = sticky

        if (Windows.canDrawAbove(act)) {
            // Room around the card for its shadow.
            val m = act.dp(20)
            val frame = FrameLayout(act).apply {
                setPadding(m, m, m, m); clipToPadding = false
                addView(row, flp(WRAP, WRAP))
                setOnTouchListener(outside)
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x - m; this.y = y - m
                fitInsetsTypes = 0
                title = "TabDesk window previews"
            }
            try {
                act.windowManager.addView(frame, lp)
                overlay = frame; overlayWm = act.windowManager
                return
            } catch (e: Exception) {
                Log.w("TabDeskWindows", "no overlay: $e")
            }
        }
        val pw = PopupWindow(row, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, false)
        pw.setBackgroundDrawable(null)
        pw.elevation = act.dpf(14)
        pw.isOutsideTouchable = true
        pw.setTouchInterceptor(outside)
        pw.setOnDismissListener { if (popup === pw) { popup = null; showingFor = null } }
        popup = pw
        try { pw.showAtLocation(act.root, Gravity.NO_GRAVITY, x, y) } catch (_: Exception) { popup = null; showingFor = null }
    }

    fun hide() {
        main.removeCallbacks(hideSoon)
        pending?.let { main.removeCallbacks(it) }; pending = null
        val o = overlay; val p = popup
        overlay = null; popup = null; showingFor = null; sticky = false
        o?.let { v -> try { overlayWm?.removeViewImmediate(v) } catch (_: Exception) {} }
        overlayWm = null
        p?.let { try { it.dismiss() } catch (_: Exception) {} }
    }
}
