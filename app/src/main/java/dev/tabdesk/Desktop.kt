package dev.tabdesk

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextUtils
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The desktop: a snap-to-grid surface over the wallpaper holding app icons, pinned
 * shortcuts and widgets. Positions are remembered separately for portrait and landscape.
 */
class DesktopLayout(private val act: HomeActivity) : ViewGroup(act) {
    companion object {
        /** Cell size targets (dp) for a given density setting; the grid stretches cells to fill the screen. */
        fun cellTargets(): Pair<Int, Int> {
            val icon = Prefs.iconDp
            val w = maxOf(Prefs.cellDp, icon + 20)
            val h = maxOf(Prefs.cellDp + 10, icon + if (Prefs.labels) 46 else 20)
            return w to h
        }
    }

    var cols = 1; private set
    var rows = 1; private set
    var cellW = 1f; private set
    var cellH = 1f; private set
    private var orient = "port"

    private var items: MutableList<DesktopItem> = Prefs.loadDesktop()
    private val views = HashMap<Long, View>()
    /** Effective placement this layout pass: col, row, spanX, spanY. */
    private val placed = HashMap<Long, IntArray>()

    private var drop: Rect? = null
    /** App or folder an app is hovering over (dropping makes / fills a folder). */
    private var mergeHi: DesktopItem? = null
    private var resizing: DesktopItem? = null
    private var resizeDrag = false
    private var swallow = false
    private var swipeY = -1f
    private var tapX = 0f
    private var tapY = 0f
    private var lastTapAt = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val pal get() = act.pal
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmp = RectF()

    private val empty = PressHelper(
        this,
        onTap = { emptyTap() },
        onLong = { _, x, y -> act.desktopMenu(x, y, cellAt(x, y)) },
    )

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        rebuild()
    }

    // ------------------------------------------------------------ content

    fun reload() { items = Prefs.loadDesktop(); rebuild() }

    fun rebuild() {
        removeAllViews(); views.clear()
        items.forEach { addViewFor(it) }
        requestLayout()
    }

    private fun addViewFor(item: DesktopItem) {
        val v: View = when (item.type) {
            DesktopItem.APP, DesktopItem.SHORTCUT, DesktopItem.FOLDER -> AppCell(item)
            DesktopItem.FILES -> if (isPanel(item)) FolderPanel(act, item, popup = false) else AppCell(item)
            DesktopItem.WIDGET -> act.widgets.createView(item)?.also { w ->
                w.press = PressHelper(w,
                    onLong = { v, _, _ -> act.widgetMenu(item, v) },
                    onDrag = { v, x, y -> startDrag(item, v, x, y) },
                    mouseDrag = false)
            } ?: return
            else -> return
        }
        views[item.id] = v
        if (!Prefs.showIcons && isIcon(item)) v.visibility = GONE
        addView(v)
    }

    /** On the desktop, directly or inside a folder. */
    fun has(appKey: String) = items.any { (it.type == DesktopItem.APP && it.key == appKey) || (it.type == DesktopItem.FOLDER && appKey in it.apps) }
    private fun topLevel(appKey: String?) = items.firstOrNull { it.type == DesktopItem.APP && it.key == appKey }
    fun viewOf(item: DesktopItem): View? = views[item.id]
    fun widgetIds() = items.filter { it.type == DesktopItem.WIDGET }.map { it.widgetId }.toSet()

    fun addApp(key: String, cell: IntArray? = null) {
        if (has(key)) return
        add(DesktopItem(DesktopItem.newId(), DesktopItem.APP, key), cell)
        hiddenNote()
    }

    fun addFolderView(path: String, cell: IntArray?) = add(DesktopItem(DesktopItem.newId(), DesktopItem.FILES, path), cell)

    /** A Folder View bigger than one cell shows its files where it sits; at one cell it's an icon. */
    private fun isPanel(item: DesktopItem) = item.spanX > 1 || item.spanY > 1

    /** Its view no longer suits its size (icon ↔ panel): make the other kind. */
    private fun refit(item: DesktopItem) {
        val v = views[item.id] ?: return
        if ((v is FolderPanel) == isPanel(item)) return
        removeView(v); views.remove(item.id)
        addViewFor(item)
    }

    /** "Show as a panel" / "Show as an icon": [sx] × [sy] cells, moved to the nearest place with room if need be. */
    fun setFolderViewSize(item: DesktopItem, sx: Int, sy: Int) {
        val w = min(sx, cols); val h = min(sy, rows)
        val p = placed[item.id]
        if (p != null && !fits(occupancy(item), p[0], p[1], w, h)) {
            val s = nearestFree(occupancy(item), p[0], p[1], w, h) ?: run { act.toast("No room on the desktop for it"); return }
            item.pos[orient] = at(s)
        }
        item.spanX = w; item.spanY = h
        refit(item)
        save(); requestLayout()
    }

    /** Folder Views read their folders again (access turned on, a folder changed, renamed, sorted). */
    fun refreshFolderViews(toRoot: Boolean = false) {
        for (item in items) if (item.type == DesktopItem.FILES) when (val v = views[item.id]) {
            is FolderPanel -> v.reload(toRoot)
            is AppCell -> v.bind()
        }
    }

    fun addWidget(id: Int, sx: Int, sy: Int, cell: IntArray?) =
        add(DesktopItem(DesktopItem.newId(), DesktopItem.WIDGET, widgetId = id, spanX = sx, spanY = sy), cell)

    private fun add(item: DesktopItem, cell: IntArray?) {
        if (!isLaidOut) {
            // Grid size unknown yet: let the next layout pass find it a spot.
            items += item; addViewFor(item); save(); requestLayout()
            return
        }
        val sx = min(item.spanX, cols); val sy = min(item.spanY, rows)
        val occ = occupancy(null)
        val spot = when {
            cell != null && fits(occ, cell[0], cell[1], sx, sy) -> cell
            cell != null -> nearestFree(occ, cell[0], cell[1], sx, sy)
            else -> firstFree(occ, sx, sy)
        }
        if (spot == null) { act.toast("No room on the desktop"); if (item.type == DesktopItem.WIDGET) act.widgets.delete(item.widgetId); return }
        item.pos[orient] = at(spot)
        placed[item.id] = intArrayOf(spot[0], spot[1], sx, sy)
        items += item
        addViewFor(item)
        save(); requestLayout()
    }

    fun remove(item: DesktopItem) {
        items.remove(item)
        views.remove(item.id)?.let { removeView(it) }
        if (resizing === item) endResize()
        when (item.type) {
            DesktopItem.WIDGET -> act.widgets.delete(item.widgetId)
            DesktopItem.SHORTCUT -> act.unpinShortcut(item, items)
        }
        save(); requestLayout()
    }

    /** Out to the widget dock: off the desktop, its widget kept. */
    fun takeOut(item: DesktopItem): Boolean {
        if (!items.remove(item)) return false
        if (resizing === item) endResize()
        views.remove(item.id)?.let { removeView(it) }
        placed.remove(item.id)
        item.pos.clear()
        save(); requestLayout()
        return true
    }

    /** Back from the dock, to the first place with room; false (with a note) if there's none. */
    fun putBack(item: DesktopItem): Boolean {
        val sx = min(item.spanX, cols); val sy = min(item.spanY, rows)
        val spot = (if (isLaidOut) firstFree(occupancy(null), sx, sy) else intArrayOf(0, 0))
            ?: run { act.toast("No room on the desktop"); return false }
        item.pos.clear()
        if (isLaidOut) { item.pos[orient] = at(spot); placed[item.id] = intArrayOf(spot[0], spot[1], sx, sy) }
        items += item
        addViewFor(item)
        save(); requestLayout()
        return true
    }

    /** Re-flow icons alphabetically (column by column); widgets stay where they are. */
    fun arrange() {
        val (widgets, icons) = items.partition { it.type == DesktopItem.WIDGET || (it.type == DesktopItem.FILES && isPanel(it)) }
        val sorted = icons.sortedBy { labelOf(it).lowercase() }
        sorted.forEach { it.pos.remove(orient) }
        items = (widgets + sorted).toMutableList()
        save(); requestLayout()
    }

    private fun labelOf(it: DesktopItem) = when (it.type) {
        DesktopItem.APP -> Apps.get(it.key)?.label ?: ""
        DesktopItem.FILES -> FolderViews.labelOf(it)
        else -> it.label ?: ""
    }

    fun refreshApps() {
        for (v in views.values) if (v is AppCell) v.bind()
        requestLayout()
    }

    fun refreshDots() { for (v in views.values) if (v is AppCell) v.bindDot() }

    /** Icon size / labels / grid density changed in Customize. */
    fun applyMetrics() {
        for (v in views.values) if (v is AppCell) v.applyMetrics()
        requestLayout()
    }

    /** Columns × rows the current screen would get with the current settings. */
    fun gridPreview(): Pair<Int, Int> {
        val (cw, ch) = cellTargets()
        val aw = width - paddingLeft - paddingRight
        val ah = height - paddingTop - paddingBottom
        return max(1, (aw / dpf(cw)).toInt()) to max(1, (ah / dpf(ch)).toInt())
    }

    private fun at(spot: IntArray) = intArrayOf(spot[0], spot[1], cols, rows)

    fun save() = Prefs.saveDesktop(items)

    // ------------------------------------------------------------ icon cell

    inner class AppCell(val item: DesktopItem) : LinearLayout(act) {
        val icon = IconView(act).apply { dotColor = pal.accent; dotRing = 0xEEFFFFFF.toInt() }
        private val label = TextView(act)
        private var pkg = ""

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = rippleBg(0x30FFFFFF, dpf(14))
            addView(icon, llp(dp(Prefs.iconDp), dp(Prefs.iconDp)))
            label.apply {
                // Dark text on a light wallpaper, white on anything else (Android says which suits it).
                val light = act.lightWallpaper
                label(if (light) 0xFF17181C.toInt() else Color.WHITE, 12.5f)
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_HORIZONTAL
                setShadowLayer(dpf(3), 0f, dpf(1), if (light) 0xA6FFFFFF.toInt() else 0xB4000000.toInt())
                setPadding(dp(4), dp(5), dp(4), 0)
            }
            addView(label, llp(MATCH, WRAP))
            label.visibility = if (Prefs.labels) VISIBLE else GONE
            bind()
            setOnTouchListener(PressHelper(this,
                onTap = { open() },
                onLong = { v, _, _ ->
                    when (item.type) {
                        DesktopItem.APP -> act.appMenu(Apps.get(item.key), v, desktopItem = item)
                        DesktopItem.FOLDER -> act.folderMenu(item, v)
                        DesktopItem.FILES -> act.folderViewMenu(item, v)
                        else -> act.shortcutMenu(item, v)
                    }
                },
                onDrag = { v, x, y -> startDrag(item, v, x, y) },
                onMiddle = { if (item.type == DesktopItem.APP) Apps.get(item.key)?.let { act.launchInWindow(it, icon) } else open() },
                onHover = if (item.type == DesktopItem.FILES) { v, on -> FolderViews.hover(act, item, v, on) } else null))
        }

        val present get() = when (item.type) {
            DesktopItem.APP -> !Apps.loaded || Apps.get(item.key) != null
            DesktopItem.FOLDER -> !Apps.loaded || item.apps.any { Apps.get(it) != null }
            else -> true
        }

        fun bind() {
            if (item.type == DesktopItem.FILES) {
                pkg = ""
                icon.bitmap = FolderViews.folderIcon(act)
                label.text = FolderViews.labelOf(item)
            } else if (item.type == DesktopItem.FOLDER) {
                pkg = ""
                icon.bitmap = folderPreview(item.apps.mapNotNull { Apps.get(it) })
                label.text = item.label ?: "Folder"
            } else if (item.type == DesktopItem.APP) {
                val app = Apps.get(item.key)
                pkg = app?.pkg ?: ""
                icon.bitmap = app?.deskIcon
                label.text = app?.label ?: ""
            } else {
                pkg = item.key.substringBefore('/')
                val si = Apps.pinnedShortcut(pkg, item.key.substringAfter('/'))
                val d = si?.let { act.la.getShortcutIconDrawable(it, resources.displayMetrics.densityDpi * 2) }
                icon.bitmap = d?.let { Apps.render(it, dp(64).coerceAtLeast(96)) } ?: Apps.firstOf(pkg)?.icon
                label.text = item.label ?: si?.shortLabel ?: ""
            }
            contentDescription = label.text
            bindDot()
        }

        fun bindDot() {
            icon.dot = if (item.type == DesktopItem.FOLDER) item.apps.any { k -> Apps.get(k)?.let { Badges.has(it.pkg) } == true }
            else pkg.isNotEmpty() && Badges.has(pkg)
        }

        fun applyMetrics() {
            icon.layoutParams = llp(dp(Prefs.iconDp), dp(Prefs.iconDp))
            label.visibility = if (Prefs.labels) VISIBLE else GONE
        }

        private fun open() {
            when (item.type) {
                DesktopItem.APP -> Apps.get(item.key)?.let { act.launch(it, icon) }
                DesktopItem.FOLDER -> act.openFolder(item, this)
                DesktopItem.FILES -> act.openFolderView(item, this)
                else -> act.launchShortcut(item, icon)
            }
        }
    }

    // ------------------------------------------------------------ grid layout

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws); val h = MeasureSpec.getSize(hs)
        setMeasuredDimension(w, h)
        val aw = w - paddingLeft - paddingRight
        val ah = h - paddingTop - paddingBottom
        if (aw <= 0 || ah <= 0) return
        val (cw, ch) = cellTargets()
        cols = max(1, (aw / dpf(cw)).toInt())
        rows = max(1, (ah / dpf(ch)).toInt())
        cellW = aw.toFloat() / cols
        cellH = ah.toFloat() / rows
        orient = if (w > h) "land" else "port"
        resolve()
        for (item in items) {
            val v = views[item.id] ?: continue
            val r = rectFor(item)
            v.measure(exactly(r?.width() ?: 0), exactly(r?.height() ?: 0))
        }
    }

    private fun exactly(px: Int) = MeasureSpec.makeMeasureSpec(max(0, px), MeasureSpec.EXACTLY)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (item in items) {
            val v = views[item.id] ?: continue
            val rect = rectFor(item)
            if (rect == null) { v.layout(0, 0, 0, 0); continue }
            v.layout(rect.left, rect.top, rect.right, rect.bottom)
            if (v is TabWidgetView) act.widgets.reportSize(v, rect.width(), rect.height())
        }
    }

    private fun cellBounds(c: Int, r: Int, sx: Int, sy: Int, pad: Int): Rect {
        val x = paddingLeft + c * cellW
        val y = paddingTop + r * cellH
        return Rect((x + pad).toInt(), (y + pad).toInt(), (x + sx * cellW - pad).toInt(), (y + sy * cellH - pad).toInt())
    }

    private fun rectFor(item: DesktopItem): Rect? {
        val p = placed[item.id] ?: return null
        return cellBounds(p[0], p[1], p[2], p[3], if (item.type == DesktopItem.WIDGET) dp(5) else dp(3))
    }

    private fun isShown(item: DesktopItem): Boolean {
        val v = views[item.id] ?: return false
        if (!Prefs.showIcons && isIcon(item)) return false
        return v !is AppCell || v.present
    }

    /** The desktop icons "Show desktop icons" hides: apps, shortcuts, folders (not widgets or Folder Views). */
    private fun isIcon(item: DesktopItem) = item.type == DesktopItem.APP || item.type == DesktopItem.SHORTCUT || item.type == DesktopItem.FOLDER

    /** "Show desktop icons" changed: hidden icons keep their places for when they're shown again. */
    fun applyShowIcons() {
        for (item in items) if (isIcon(item)) views[item.id]?.visibility = if (Prefs.showIcons) VISIBLE else GONE
        requestLayout()
    }

    private fun hiddenNote() { if (!Prefs.showIcons) act.toast("Added. Desktop icons are hidden: Customize → Desktop shows them") }

    /** Where an item wants to be on the current grid: its saved cell, scaled if it was saved for a different grid size. */
    private fun desired(it: DesktopItem): IntArray? {
        val p = it.pos[orient] ?: return null
        if (p.size < 4 || p[2] <= 0 || p[3] <= 0 || (p[2] == cols && p[3] == rows)) return intArrayOf(p[0], p[1])
        return intArrayOf(((p[0] + 0.5f) * cols / p[2]).toInt(), ((p[1] + 0.5f) * rows / p[3]).toInt())
    }

    /**
     * Work out where everything sits for the current grid. A saved position is an anchor that only
     * changes when you move something: if the grid is a different size the layout is scaled for display
     * but not saved, so going back to the old grid size restores it exactly.
     */
    private fun resolve() {
        placed.clear()
        val occ = Array(cols) { BooleanArray(rows) }
        val later = ArrayList<Pair<DesktopItem, IntArray?>>()
        var changed = false
        for (it in items) {
            if (!isShown(it)) continue
            val sx = min(it.spanX, cols); val sy = min(it.spanY, rows)
            val d = desired(it)
            if (d != null && fits(occ, d[0], d[1], sx, sy)) {
                place(occ, it, d[0], d[1], sx, sy)
                // One-time upgrade of old [col, row] anchors to [col, row, cols, rows].
                if (it.pos[orient]!!.size < 4) { it.pos[orient] = at(d); changed = true }
            } else later += it to d
        }
        for ((it, d) in later) {
            val sx = min(it.spanX, cols); val sy = min(it.spanY, rows)
            val spot = (if (d != null) nearestFree(occ, d[0], d[1], sx, sy) else firstFree(occ, sx, sy)) ?: continue
            place(occ, it, spot[0], spot[1], sx, sy)
            // Only items that never had a place get one saved; displaced items keep their anchor.
            if (it.pos[orient] == null || it.pos[orient]!!.size < 4) { it.pos[orient] = at(spot); changed = true }
        }
        if (changed) save()
    }

    private fun place(occ: Array<BooleanArray>, it: DesktopItem, c: Int, r: Int, sx: Int, sy: Int) {
        for (x in c until c + sx) for (y in r until r + sy) occ[x][y] = true
        placed[it.id] = intArrayOf(c, r, sx, sy)
    }

    private fun occupancy(except: DesktopItem?): Array<BooleanArray> {
        val occ = Array(cols) { BooleanArray(rows) }
        for ((id, p) in placed) {
            if (except != null && id == except.id) continue
            for (x in p[0] until min(cols, p[0] + p[2])) for (y in p[1] until min(rows, p[1] + p[3])) occ[x][y] = true
        }
        return occ
    }

    private fun fits(occ: Array<BooleanArray>, c: Int, r: Int, sx: Int, sy: Int): Boolean {
        if (c < 0 || r < 0 || c + sx > cols || r + sy > rows) return false
        for (x in c until c + sx) for (y in r until r + sy) if (occ[x][y]) return false
        return true
    }

    private fun firstFree(occ: Array<BooleanArray>, sx: Int, sy: Int): IntArray? {
        for (c in 0..cols - sx) for (r in 0..rows - sy) if (fits(occ, c, r, sx, sy)) return intArrayOf(c, r)
        return null
    }

    private fun nearestFree(occ: Array<BooleanArray>, c0: Int, r0: Int, sx: Int, sy: Int): IntArray? {
        var best: IntArray? = null; var bestD = Int.MAX_VALUE
        for (c in 0..cols - sx) for (r in 0..rows - sy) {
            val d = (c - c0) * (c - c0) + (r - r0) * (r - r0)
            if (d < bestD && fits(occ, c, r, sx, sy)) { bestD = d; best = intArrayOf(c, r) }
        }
        return best
    }

    fun cellAt(x: Float, y: Float): IntArray = intArrayOf(
        ((x - paddingLeft) / cellW).toInt().coerceIn(0, cols - 1),
        ((y - paddingTop) / cellH).toInt().coerceIn(0, rows - 1),
    )

    // ------------------------------------------------------------ drag & drop

    fun startDrag(item: DesktopItem, v: View, x: Float, y: Float) {
        act.overlays.dismissAll()
        endResize()
        val key = if (item.type == DesktopItem.APP) item.key else null
        val payload = DragPayload(DragPayload.DESKTOP, key, item, x, y, v.width, v.height, v)
        if (v.startDragAndDrop(null, ViewShadow(v, x.toInt(), y.toInt()), payload, View.DRAG_FLAG_OPAQUE))
            v.visibility = INVISIBLE
    }

    override fun onDragEvent(e: DragEvent): Boolean {
        val p = e.localState as? DragPayload ?: return false
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                mergeHi = mergeTarget(p, e.x, e.y)
                drop = if (mergeHi != null) null else target(p, e.x, e.y)
                invalidate()
            }
            DragEvent.ACTION_DRAG_EXITED -> { drop = null; mergeHi = null; invalidate() }
            DragEvent.ACTION_DROP -> {
                val m = mergeTarget(p, e.x, e.y)
                val t = target(p, e.x, e.y)
                drop = null; mergeHi = null; invalidate()
                return if (m != null) merge(p, m) else accept(p, t)
            }
            DragEvent.ACTION_DRAG_ENDED -> { drop = null; mergeHi = null; invalidate(); act.onDragEnded(p) }
        }
        return true
    }

    private fun target(p: DragPayload, x: Float, y: Float): Rect {
        val sx = min(p.item?.spanX ?: 1, cols); val sy = min(p.item?.spanY ?: 1, rows)
        val cx = x - p.offX + p.w / 2f - paddingLeft
        val cy = y - p.offY + p.h / 2f - paddingTop
        val c = floor(cx / cellW - (sx - 1) / 2f).toInt().coerceIn(0, cols - sx)
        val r = floor(cy / cellH - (sy - 1) / 2f).toInt().coerceIn(0, rows - sy)
        return Rect(c, r, c + sx, r + sy)
    }

    private fun accept(p: DragPayload, t: Rect): Boolean {
        val moving = p.item ?: if (p.source == DragPayload.FOLDER) null else topLevel(p.appKey)
        val occ = occupancy(moving)
        val spot = if (fits(occ, t.left, t.top, t.width(), t.height())) intArrayOf(t.left, t.top)
        else nearestFree(occ, t.left, t.top, t.width(), t.height()) ?: return false
        if (moving != null) moving.pos[orient] = at(spot)
        else {
            val item = DesktopItem(DesktopItem.newId(), DesktopItem.APP, p.appKey ?: return false)
            item.pos[orient] = at(spot)
            placed[item.id] = intArrayOf(spot[0], spot[1], 1, 1)
            items += item
            addViewFor(item)
            hiddenNote()
        }
        p.folder?.let { f -> p.appKey?.let { removeFromFolder(f, it, persist = false) } }
        save(); requestLayout()
        return true
    }

    // ------------------------------------------------------------ folders

    /**
     * The app or folder under the dragged icon's centre, if an app is being dragged and is held near
     * that icon's middle (further out, the drop just moves it to a free cell as before).
     */
    private fun mergeTarget(p: DragPayload, x: Float, y: Float): DesktopItem? {
        val key = p.appKey ?: return null
        if (p.item != null && p.item.type != DesktopItem.APP) return null
        val cx = x - p.offX + p.w / 2f
        val cy = y - p.offY + p.h / 2f
        val c = floor((cx - paddingLeft) / cellW).toInt()
        val r = floor((cy - paddingTop) / cellH).toInt()
        if (c !in 0 until cols || r !in 0 until rows) return null
        val mx = paddingLeft + (c + 0.5f) * cellW
        val my = paddingTop + (r + 0.5f) * cellH
        if (hypot(cx - mx, cy - my) > min(cellW, cellH) * 0.36f) return null
        val hit = items.firstOrNull { val q = placed[it.id]; q != null && q[0] == c && q[1] == r && q[2] == 1 && q[3] == 1 } ?: return null
        return when {
            hit === p.item -> null
            hit.type == DesktopItem.FOLDER -> hit
            hit.type == DesktopItem.APP && hit.key != key -> hit
            else -> null
        }
    }

    /** Drop an app onto another app (makes a folder of the two) or onto a folder (adds it). */
    private fun merge(p: DragPayload, into: DesktopItem): Boolean {
        val key = p.appKey ?: return false
        if (into === p.folder) return true
        val moving = p.item ?: if (p.source == DragPayload.FOLDER) null else topLevel(key)
        val folder = if (into.type == DesktopItem.FOLDER) into
        else replace(into, DesktopItem(DesktopItem.newId(), DesktopItem.FOLDER, label = "Folder").apply { apps += into.key })
        if (key !in folder.apps) folder.apps += key
        if (moving != null && moving !== into) detach(moving)
        p.folder?.let { removeFromFolder(it, key, persist = false) }
        (views[folder.id] as? AppCell)?.bind()
        save(); requestLayout()
        return true
    }

    fun removeFromFolder(folder: DesktopItem, key: String, persist: Boolean = true) {
        folder.apps.remove(key)
        when {
            folder.apps.isEmpty() -> detach(folder)
            // One app left: the folder turns back into that app's icon (unless it's already on the desktop).
            folder.apps.size == 1 -> {
                val last = folder.apps[0]
                if (topLevel(last) != null) detach(folder) else replace(folder, DesktopItem(DesktopItem.newId(), DesktopItem.APP, last))
            }
            else -> (views[folder.id] as? AppCell)?.bind()
        }
        if (persist) { save(); requestLayout() }
    }

    /** Put the folder's apps back on the desktop, starting where the folder was. */
    fun ungroup(folder: DesktopItem) {
        val at = placed[folder.id]?.let { intArrayOf(it[0], it[1]) }
        val keys = folder.apps.toList()
        detach(folder)
        save()
        for (k in keys) if (topLevel(k) == null) add(DesktopItem(DesktopItem.newId(), DesktopItem.APP, k), at)
        requestLayout()
    }

    /** Swap [new] into [old]'s place: same cell in both orientations, same position in the list. */
    private fun replace(old: DesktopItem, new: DesktopItem): DesktopItem {
        old.pos.forEach { (k, v) -> new.pos[k] = v.copyOf() }
        placed[old.id]?.let { placed[new.id] = it }
        val i = items.indexOf(old)
        if (i >= 0) items[i] = new else items += new
        detach(old)
        addViewFor(new)
        return new
    }

    private fun detach(item: DesktopItem) {
        items.remove(item)
        placed.remove(item.id)
        views.remove(item.id)?.let { removeView(it) }
    }

    /** Folder icon: a frosted rounded square with up to four of its apps. */
    private fun folderPreview(apps: List<AppInfo>): Bitmap {
        val size = 192
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x5CFFFFFF }
        c.drawRoundRect(RectF(6f, 6f, size - 6f, size - 6f), 50f, 50f, bg)
        bg.style = Paint.Style.STROKE; bg.strokeWidth = 2.5f; bg.color = 0x70FFFFFF
        c.drawRoundRect(RectF(7f, 7f, size - 7f, size - 7f), 49f, 49f, bg)
        val ip = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val cell = 70f; val gap = 14f; val start = (size - 2 * cell - gap) / 2f
        val r = RectF()
        apps.take(4).forEachIndexed { i, a ->
            val x = start + (i % 2) * (cell + gap); val y = start + (i / 2) * (cell + gap)
            r.set(x, y, x + cell, y + cell); c.drawBitmap(a.deskIcon, null, r, ip)
        }
        return b
    }

    // ------------------------------------------------------------ widget resize mode

    fun startResize(item: DesktopItem) { resizing = item; invalidate() }

    fun endResize(): Boolean {
        if (resizing == null) return false
        resizing = null; resizeDrag = false; invalidate()
        return true
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        val item = resizing ?: return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val r = rectFor(item) ?: return false
            if (hypot(e.x - r.right, e.y - r.bottom) < dp(40)) { resizeDrag = true; return true }
            endResize(); swallow = true
            return true
        }
        return resizeDrag
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (resizeDrag) { resizeTouch(e); return true }
        if (swallow) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) swallow = false
            return true
        }
        // On empty desktop: swipe up opens Start, swipe down pulls down Android's notification panel.
        when (e.actionMasked) {
            // Right- or middle-click: moving with the button held isn't a swipe.
            MotionEvent.ACTION_DOWN -> {
                swipeY = if (PressHelper.isMenuClick(e) || PressHelper.isMiddleClick(e)) -1f else e.y
                tapX = e.x; tapY = e.y
            }
            MotionEvent.ACTION_MOVE -> if (swipeY >= 0 && kotlin.math.abs(e.y - swipeY) > dp(70)) {
                val up = e.y < swipeY
                swipeY = -1f
                val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                empty.handle(cancel); cancel.recycle()
                if (up) act.openStart() else act.openShade()
                return true
            }
        }
        return empty.handle(e)
    }

    /** A tap on empty desktop closes Start / resize mode; two quick taps in the same spot lock the screen. */
    private fun emptyTap() {
        val busy = act.startMenu.isOpen || resizing != null || act.overlays.isOpen
        act.closeTransient()
        val now = SystemClock.uptimeMillis()
        if (!busy && now - lastTapAt < 400 && hypot(tapX - lastTapX, tapY - lastTapY) < dp(48)) {
            lastTapAt = 0L
            act.doubleTapDesktop()
        } else {
            lastTapAt = if (busy) 0L else now; lastTapX = tapX; lastTapY = tapY
        }
    }

    private fun resizeTouch(e: MotionEvent) {
        val item = resizing ?: return
        val p = placed[item.id] ?: return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val (minX, minY, axes) = act.widgets.resizeLimits(item, cellW, cellH)
                val left = paddingLeft + p[0] * cellW
                val top = paddingTop + p[1] * cellH
                val sx = if (axes and 1 != 0) ((e.x - left) / cellW).roundToInt().coerceIn(minX, cols - p[0]) else p[2]
                val sy = if (axes and 2 != 0) ((e.y - top) / cellH).roundToInt().coerceIn(minY, rows - p[1]) else p[3]
                if ((sx != p[2] || sy != p[3]) && fits(occupancy(item), p[0], p[1], sx, sy)) {
                    item.spanX = sx; item.spanY = sy
                    if (item.type == DesktopItem.FILES) refit(item)
                    requestLayout(); invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { resizeDrag = false; save() }
        }
    }

    // ------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        mergeHi?.let { m ->
            placed[m.id]?.let { q ->
                tmp.set(cellBounds(q[0], q[1], 1, 1, dp(4)))
                paint.style = Paint.Style.FILL; paint.color = 0x2EFFFFFF
                c.drawRoundRect(tmp, dpf(16), dpf(16), paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dpf(2); paint.color = pal.accent
                c.drawRoundRect(tmp, dpf(16), dpf(16), paint)
            }
        }
        val d = drop ?: return
        tmp.set(cellBounds(d.left, d.top, d.width(), d.height(), dp(4)))
        paint.style = Paint.Style.FILL; paint.color = 0x2EFFFFFF
        c.drawRoundRect(tmp, dpf(16), dpf(16), paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dpf(1.5f); paint.color = 0xA0FFFFFF.toInt()
        c.drawRoundRect(tmp, dpf(16), dpf(16), paint)
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        val item = resizing ?: return
        val r = rectFor(item) ?: return
        tmp.set(r); tmp.inset(-dpf(3), -dpf(3))
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dpf(2); paint.color = pal.accent
        c.drawRoundRect(tmp, dpf(16), dpf(16), paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE; c.drawCircle(tmp.right - dpf(4), tmp.bottom - dpf(4), dpf(12), paint)
        paint.color = pal.accent; c.drawCircle(tmp.right - dpf(4), tmp.bottom - dpf(4), dpf(9), paint)
    }
}
