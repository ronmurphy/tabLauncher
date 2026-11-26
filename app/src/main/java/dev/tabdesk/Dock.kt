package dev.tabdesk

import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The widget dock: a panel that slides out at the right from its taskbar button, holding widgets and Folder Views
 * one above the other, to keep the desktop clear. It opens above floating windows when there are some (like
 * menus), and closes on a tap outside it. Drag its left edge to make it wider or narrower; each item keeps its own
 * height ("Resize" in its menu).
 *
 * Items are [DesktopItem]s (WIDGET or FILES) with [DesktopItem.spanY] holding the height in dp.
 */
class Dock(private val act: HomeActivity) {
    val items: MutableList<DesktopItem> = Prefs.loadDock()
    private val views = HashMap<Long, View>()
    private var layer: Overlays.Layer? = null
    private var panel: Panel? = null
    private val pal get() = act.pal

    val isOpen get() = layer?.let { act.overlays.isOpen(it) } == true

    fun widgetIds() = items.filter { it.type == DesktopItem.WIDGET }.map { it.widgetId }.toSet()
    fun has(item: DesktopItem) = items.any { it === item }
    fun save() = Prefs.saveDock(items)

    fun toggle() = if (isOpen) close() else open()

    fun open() {
        if (isOpen) return
        act.closeTransient()
        val p = Panel()
        panel = p
        layer = act.overlays.show(p, aboveWindows = true, onDismiss = { panel = null; p.release() }, frame = ::frame)
        act.taskbar.setDockActive(true)
    }

    fun close() { layer?.let { act.overlays.dismiss(it) }; layer = null }

    /** At the right, from under the status bar to just above the taskbar. */
    private fun frame(): Rect {
        val a = act.workArea()
        val w = width()
        return Rect(a.right - w - act.dp(8), a.top + act.dp(8), a.right - act.dp(8), a.bottom - act.dp(8))
    }

    private fun width(): Int {
        val a = act.workArea()
        return act.dp(Prefs.dockDp).coerceIn(act.dp(MIN_DP), max(act.dp(MIN_DP), min(act.dp(MAX_DP), (a.width() * 0.6f).toInt())))
    }

    // ------------------------------------------------------------ items

    fun addWidget(id: Int) {
        val info = act.widgets.mgr.getAppWidgetInfo(id) ?: return
        val d = act.resources.displayMetrics.density
        val h = if (info.targetCellHeight > 0) info.targetCellHeight * act.desktop.cellH / d else info.minHeight / d + 16
        add(DesktopItem(DesktopItem.newId(), DesktopItem.WIDGET, widgetId = id, spanY = h.roundToInt().coerceIn(MIN_H, 560)))
    }

    fun addFolderView(path: String, show: Boolean = true) = add(DesktopItem(DesktopItem.newId(), DesktopItem.FILES, path, spanY = 300), show)

    /** [show]: open the dock to show it (not when it came from TabDesk Files, which is in front). */
    fun add(item: DesktopItem, show: Boolean = true) {
        item.spanX = 1
        if (item.spanY < MIN_H) item.spanY = 240
        items += item
        save()
        if (isOpen) panel?.rebuild() else if (show) open()
    }

    /** Out of the dock: [delete] frees a widget; moving it to the desktop keeps it. */
    fun remove(item: DesktopItem, delete: Boolean) {
        items.removeAll { it === item }
        views.remove(item.id)?.let { (it.parent as? ViewGroup)?.removeView(it) }
        if (delete && item.type == DesktopItem.WIDGET) act.widgets.delete(item.widgetId)
        save()
        panel?.rebuild()
    }

    private fun move(item: DesktopItem, by: Int) {
        val i = items.indexOfFirst { it === item }
        val j = (i + by).coerceIn(0, items.size - 1)
        if (i < 0 || i == j) return
        items.removeAt(i); items.add(j, item)
        save(); panel?.rebuild()
    }

    /** From the desktop's menu: the item leaves the desktop (its widget kept) and comes here. */
    fun takeFromDesktop(item: DesktopItem, heightPx: Int) {
        if (!act.desktop.takeOut(item)) return
        item.spanY = (heightPx / act.resources.displayMetrics.density).roundToInt().coerceIn(MIN_H, 560)
        add(item)
    }

    private fun toDesktop(item: DesktopItem) {
        val (sx, sy) = if (item.type == DesktopItem.WIDGET) act.widgets.info(item)?.let { act.widgets.spansFor(it) } ?: (2 to 2)
            else 4 to 3
        val oldX = item.spanX; val oldY = item.spanY
        item.spanX = sx; item.spanY = sy
        views.remove(item.id)?.let { (it.parent as? ViewGroup)?.removeView(it) }
        if (act.desktop.putBack(item)) {
            items.removeAll { it === item }; save(); panel?.rebuild()
        } else { item.spanX = oldX; item.spanY = oldY; panel?.rebuild() }
    }

    fun refreshFolderViews(toRoot: Boolean = false) {
        for (item in items) if (item.type == DesktopItem.FILES) (views[item.id] as? FolderPanel)?.reload(toRoot)
    }

    /** The dock's part of an item's menu: size, order, back to the desktop, remove. */
    fun entriesFor(item: DesktopItem): List<MenuEntry> {
        val e = ArrayList<MenuEntry>()
        e += MenuEntry("Resize", G.RESIZE) { panel?.startResize(item) }
        val i = items.indexOfFirst { it === item }
        if (i > 0) e += MenuEntry("Move up", G.SNAP_TOP) { move(item, -1) }
        if (i in 0 until items.size - 1) e += MenuEntry("Move down", G.SNAP_BOTTOM) { move(item, 1) }
        e += MenuEntry("Move to the desktop", G.DESKTOP) { toDesktop(item) }
        e += if (item.type == DesktopItem.WIDGET) MenuEntry("Remove widget", G.TRASH) { remove(item, delete = true) }
            else MenuEntry("Remove from the dock", G.CLOSE) { remove(item, delete = true); act.toast("Removed. The folder and its files are still there") }
        return e
    }

    private fun widgetMenu(item: DesktopItem, anchor: View) {
        val e = ArrayList<MenuEntry>()
        if (act.widgets.canReconfigure(item)) e += MenuEntry("Widget settings", G.SLIDERS) { act.widgets.reconfigure(item) }
        e += entriesFor(item)
        act.overlays.menu(act.anchorFor(anchor), e, act.widgets.info(item)?.loadLabel(act.packageManager))
    }

    private fun viewFor(item: DesktopItem): View? = views[item.id] ?: when (item.type) {
        DesktopItem.WIDGET -> act.widgets.createView(item)?.also { w ->
            w.press = PressHelper(w, onLong = { v, _, _ -> widgetMenu(item, v) }, mouseDrag = false)
        }
        DesktopItem.FILES -> FolderPanel(act, item, popup = false, inDock = true)
        else -> null
    }?.also { views[item.id] = it }

    // ------------------------------------------------------------ the panel

    private inner class Slot(val item: DesktopItem, val child: View) : FrameLayout(act) {
        var handle: View? = null
        init { addView(child, flp(MATCH, MATCH)) }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            (child as? TabWidgetView)?.let { act.widgets.reportSize(it, r - l, b - t) }
        }
    }

    private inner class Panel : LinearLayout(act) {
        private val list = linear(act, true)
        private val scroll = ScrollView(act)
        private var resizing: Slot? = null

        init {
            orientation = HORIZONTAL
            background = rounded(pal.surfaceSolid, dpf(22), 1, pal.divider)
            elevation = dpf(18)
            clipToOutline = true

            // The left edge: drag it to make the dock wider or narrower.
            val grip = object : View(act) {
                private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = pal.textDim; alpha = 110 }
                override fun onDraw(c: android.graphics.Canvas) {
                    val cx = width / 2f; val cy = height / 2f
                    c.drawRoundRect(cx - dpf(2), cy - dpf(22), cx + dpf(2), cy + dpf(22), dpf(2), dpf(2), p)
                }
            }
            grip.contentDescription = "Drag to change the width"
            grip.setOnTouchListener(object : OnTouchListener {
                var startX = 0f; var startW = 0
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { startX = e.rawX; startW = width(); v.parent?.requestDisallowInterceptTouchEvent(true) }
                        MotionEvent.ACTION_MOVE -> {
                            Prefs.dockDp = ((startW + (startX - e.rawX)) / resources.displayMetrics.density).roundToInt()
                            layer?.requestLayout()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            Prefs.dockDp = (width() / resources.displayMetrics.density).roundToInt()
                        }
                    }
                    return true
                }
            })
            addView(grip, llp(dp(16), MATCH))

            val column = linear(act, true)
            val head = linear(act, false) { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(8), dp(6), dp(4)) }
            head.addView(TextView(act).label(pal.text, 16f, bold = true).apply { text = "Widgets"; isSingleLine = true },
                llp(0, WRAP, 1f))
            head.addView(iconButton(G.PLUS, "Add to the dock") { v -> addMenu(v) }, llp(dp(38), dp(38)))
            head.addView(iconButton(G.CLOSE, "Close") { close() }, llp(dp(38), dp(38)))
            column.addView(head, llp(MATCH, WRAP))
            scroll.isVerticalScrollBarEnabled = false
            scroll.addView(list, flp(MATCH, WRAP))
            list.setPadding(0, dp(2), dp(10), dp(10))
            column.addView(scroll, llp(MATCH, 0, 1f))
            addView(column, llp(0, MATCH, 1f))
            rebuild()
        }

        private fun iconButton(glyph: Int, desc: String, onClick: (View) -> Unit) = FrameLayout(act).apply {
            background = rippleBg(pal.ripple, dpf(19))
            contentDescription = desc; tooltipText = desc
            addView(ImageView(act).apply { setImageDrawable(Glyph(glyph, pal.text)) }, flp(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { onClick(it) }
        }

        private fun addMenu(anchor: View) {
            act.overlays.menu(act.anchorFor(anchor), listOf(
                MenuEntry("Add a widget", G.WIDGETS) { close(); act.widgets.showPicker(null, toDock = true) },
                MenuEntry("Add a Folder View", G.FOLDER) { act.addFolderViewToDock() },
            ))
        }

        fun rebuild() {
            endResize()
            for (i in 0 until list.childCount) (list.getChildAt(i) as? Slot)?.removeAllViews()
            list.removeAllViews()
            if (items.isEmpty()) {
                list.addView(TextView(act).label(pal.textDim, 14f).apply {
                    text = "Keep widgets here instead of on the desktop. Tap + to add one, or choose “Move to the dock” " +
                        "in a desktop widget's menu."
                    setLineSpacing(0f, 1.15f); setPadding(dp(4), dp(24), dp(8), dp(24))
                }, llp(MATCH, WRAP))
                return
            }
            for (item in items) {
                val v = viewFor(item) ?: continue
                (v.parent as? ViewGroup)?.removeView(v)
                list.addView(Slot(item, v), llp(MATCH, dp(item.spanY)).apply { bottomMargin = dp(10) })
            }
        }

        /** A handle under the item: drag it up or down; letting go keeps the new height. */
        fun startResize(item: DesktopItem) {
            endResize()
            val slot = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { (it as? Slot)?.item === item } as? Slot ?: return
            resizing = slot
            slot.foreground = rounded(0, dpf(16), dp(2), pal.accent)
            val handle = object : View(act) {
                private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = pal.accent }
                override fun onDraw(c: android.graphics.Canvas) {
                    val cx = width / 2f; val cy = height / 2f
                    c.drawRoundRect(cx - dpf(28), cy - dpf(4), cx + dpf(28), cy + dpf(4), dpf(4), dpf(4), p)
                }
            }
            handle.contentDescription = "Drag to change the height"
            handle.setOnTouchListener(object : OnTouchListener {
                var startY = 0f; var startH = 0
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { startY = e.rawY; startH = slot.height; v.parent?.requestDisallowInterceptTouchEvent(true) }
                        MotionEvent.ACTION_MOVE -> {
                            val h = (startH + (e.rawY - startY)).roundToInt().coerceIn(dp(MIN_H), dp(900))
                            slot.layoutParams = slot.layoutParams.apply { height = h }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            item.spanY = (slot.height / resources.displayMetrics.density).roundToInt()
                            save(); endResize()
                        }
                    }
                    return true
                }
            })
            slot.handle = handle
            slot.addView(handle, flp(MATCH, dp(30), Gravity.BOTTOM))
        }

        private fun endResize() {
            val s = resizing ?: return
            resizing = null
            s.foreground = null
            s.handle?.let { s.removeView(it) }; s.handle = null
        }

        /** Closing: the cached views come out so they can go into the next panel. */
        fun release() {
            endResize()
            for (i in 0 until list.childCount) (list.getChildAt(i) as? Slot)?.removeAllViews()
            list.removeAllViews()
            act.taskbar.setDockActive(false)
        }
    }

    companion object {
        const val MIN_DP = 260
        const val MAX_DP = 640
        const val MIN_H = 72
    }
}
