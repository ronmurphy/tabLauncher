package dev.tabdesk

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Insets
import android.graphics.Paint
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SectionIndexer
import android.widget.TextView
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

// ==================================================================== taskbar

class Taskbar(private val act: HomeActivity) : LinearLayout(act) {
    companion object { const val MAX_RECENTS = 5 }

    private val pal = act.pal
    private val scale = Prefs.taskbarDp / 56f
    private fun s(v: Number) = dp(v.toFloat() * scale)

    private val startBtn = FrameLayout(act)
    private val pins = LinearLayout(act)
    private val recentDivider = View(act)
    private val recents = LinearLayout(act)
    private val strip = AppStrip()
    val battery = BatteryView(act, pal.text, pal.accent)
    private var dropIndex = -1
    private var draggingKey: String? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Pins, then a divider and recently used apps; recents that don't fit are dropped rather than clipped. */
    private inner class AppStrip : LinearLayout(act) {
        override fun onMeasure(w: Int, h: Int) {
            val item = s(50)
            val room = ((MeasureSpec.getSize(w) - pins.childCount * item - dp(13)) / item).coerceAtLeast(0)
            for (i in 0 until recents.childCount) {
                val v = recents.getChildAt(i)
                val vis = if (i < room) VISIBLE else GONE
                if (v.visibility != vis) v.visibility = vis
            }
            val div = if (room > 0 && recents.childCount > 0 && pins.childCount > 0) VISIBLE else GONE
            if (recentDivider.visibility != div) recentDivider.visibility = div
            super.onMeasure(w, h)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
        background = android.graphics.drawable.ColorDrawable(pal.taskbar)

        startBtn.apply {
            background = rippleBg(pal.ripple, dpf(12))
            contentDescription = "Start"
            addView(ImageView(act).apply { setImageDrawable(Glyph(G.START, pal.accent)) }, flp(s(28), s(28), Gravity.CENTER))
            setOnClickListener { act.toggleStart() }
            onMenu { act.showSettings() }
        }
        addView(startBtn, llp(s(48), s(46)))
        addView(View(act).apply { setBackgroundColor(pal.divider) }, llp(1, s(26)).apply { setMargins(dp(8), 0, dp(6), 0) })

        strip.gravity = Gravity.CENTER_VERTICAL
        pins.gravity = Gravity.CENTER_VERTICAL
        recents.gravity = Gravity.CENTER_VERTICAL
        strip.addView(pins, llp(WRAP, MATCH))
        recentDivider.setBackgroundColor(pal.divider)
        strip.addView(recentDivider, llp(1, s(22)).apply { setMargins(dp(6), 0, dp(6), 0) })
        strip.addView(recents, llp(WRAP, MATCH))
        addView(strip, llp(0, MATCH, 1f))

        battery.setPadding(dp(8), 0, dp(8), 0)
        battery.background = rippleBg(pal.ripple, dpf(12))
        // Tap: Android's own notification / quick-settings panel. Long-press or right-click: battery usage.
        battery.setOnClickListener { act.openShade() }
        battery.onMenu { act.safeStart(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }
        addView(battery, llp(WRAP, s(44)))

        val clock = linear(act, true) {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(10), 0, dp(10), 0)
            background = rippleBg(pal.ripple, dpf(12))
            setOnClickListener { act.openCalendar() }
        }
        // WRAP width (not the column's default MATCH) so they re-measure when the text grows, e.g. 7:59 → 8:00,
        // and a little spare room on the right for glyphs that draw past their measured width ("Nov").
        clock.addView(RoomyClock(act).apply {
            format12Hour = "h:mm a"; format24Hour = "HH:mm"
            label(pal.text, 14.5f * scale.coerceAtMost(1.1f), bold = true)
        }, llp(WRAP, WRAP))
        clock.addView(RoomyClock(act).apply {
            format12Hour = "EEE d MMM"; format24Hour = "EEE d MMM"
            label(pal.textDim, 11.5f)
        }, llp(WRAP, WRAP))
        addView(clock, llp(WRAP, s(44)).apply { marginStart = dp(2) })
    }

    fun applyInsets(bars: Insets) {
        val h = dp(Prefs.taskbarDp) + bars.bottom
        if (layoutParams.height != h) { layoutParams.height = h; requestLayout() }
        setPadding(dp(6) + bars.left, 0, dp(6) + bars.right, bars.bottom)
    }

    fun setStartActive(on: Boolean) {
        startBtn.background = rippleBg(pal.ripple, dpf(12), if (on) pal.accentSoft else Color.TRANSPARENT)
    }

    fun bindPins() {
        pins.removeAllViews()
        for (key in Prefs.pins) {
            val app = Apps.get(key) ?: continue
            pins.addView(appView(app), llp(s(50), s(48)))
        }
        bindRecents()
    }

    fun bindRecents() {
        recents.removeAllViews()
        val pinned = Prefs.pins.mapNotNull { Apps.get(it)?.pkg }.toSet()
        Recents.shown().asSequence()
            .filter { it !in pinned }
            .mapNotNull { Apps.firstOf(it) }
            .filter { !Prefs.isHidden(it.key) }
            .take(MAX_RECENTS)
            .forEach { recents.addView(appView(it, recent = true), llp(s(50), s(48))) }
        strip.requestLayout()
    }

    fun refreshDots() {
        for (group in listOf(pins, recents)) for (i in 0 until group.childCount) {
            val f = group.getChildAt(i) as FrameLayout
            val app = Apps.get(f.tag as String) ?: continue
            (f.getChildAt(0) as IconView).dot = Badges.has(app.pkg)
        }
    }

    private fun appView(app: AppInfo, recent: Boolean = false): View {
        val f = FrameLayout(act)
        f.tag = app.key
        f.contentDescription = app.label
        f.tooltipText = app.label   // shown when a mouse pointer rests on it
        f.background = rippleBg(pal.ripple, dpf(12))
        val icon = IconView(act).apply { bitmap = app.icon; dotColor = pal.accent; dotRing = pal.surfaceSolid; dot = Badges.has(app.pkg) }
        f.addView(icon, flp(s(34), s(34), Gravity.CENTER))
        f.setOnTouchListener(PressHelper(f,
            onTap = { act.launch(app, icon) },
            onMiddle = { act.launchInWindow(app, icon) },
            onLong = { v, _, _ ->
                act.appMenu(app, v, extra = if (!recent) null else MenuEntry("Remove from taskbar", G.CLOSE) {
                    Recents.remove(app.pkg); bindRecents()
                })
            },
            onDrag = { v, _, _ ->
                act.overlays.dismissAll()
                val size = dp(54)
                val p = DragPayload(DragPayload.TASKBAR, app.key, null, size / 2f, size / 2f, size, size, v)
                if (v.startDragAndDrop(null, BitmapShadow(app.icon, size), p, View.DRAG_FLAG_OPAQUE)) v.alpha = 0.25f
            }))
        return f
    }

    // ---------------------------------------------------------- drop: pin or reorder

    override fun onDragEvent(e: DragEvent): Boolean {
        val p = e.localState as? DragPayload ?: return false
        val key = p.appKey ?: return false
        when (e.action) {
            DragEvent.ACTION_DRAG_STARTED -> { draggingKey = key; return true }
            DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> { dropIndex = indexAt(e.x); invalidate() }
            DragEvent.ACTION_DRAG_EXITED -> { dropIndex = -1; invalidate() }
            DragEvent.ACTION_DROP -> {
                val i = indexAt(e.x)
                dropIndex = -1; invalidate()
                act.pinAt(key, i)
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> { dropIndex = -1; draggingKey = null; invalidate() }
        }
        return true
    }

    /** Pins other than the one being dragged, in order. */
    private fun others(): List<View> = (0 until pins.childCount).map { pins.getChildAt(it) }.filter { it.tag != draggingKey }

    private fun indexAt(x: Float): Int {
        val px = x - strip.left - pins.left
        return others().count { it.left + it.width / 2f < px }
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        paint.color = pal.divider
        c.drawRect(0f, 0f, width.toFloat(), 1f, paint)
        if (dropIndex < 0) return
        val o = others()
        val x = strip.left + pins.left + when {
            o.isEmpty() -> dp(4)
            dropIndex < o.size -> o[dropIndex].left
            else -> o.last().right
        }.toFloat()
        val h = dp(Prefs.taskbarDp)
        paint.color = pal.accent
        c.drawRoundRect(x - dpf(1.5f), h * 0.22f, x + dpf(1.5f), h * 0.78f, dpf(2), dpf(2), paint)
    }
}

// ==================================================================== start menu

/**
 * Equal-width cells in as few, evenly filled rows as fit, centred. Cells aim for at least [minCell]
 * wide (and grow up to 2× that to fill a row); rather than start a third row they shrink down to
 * [tightCell], so a full A–Z plus 0–9 still sits on two lines.
 */
private class EvenGrid(ctx: android.content.Context, private val minCell: Int, private val tightCell: Int, private val cellH: Int) : ViewGroup(ctx) {
    private var per = 1
    private var cellW = 0
    private var left = 0

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        val n = childCount
        if (n == 0) { setMeasuredDimension(w, 0); return }
        val inner = w - paddingLeft - paddingRight
        val maxPer = maxOf(1, inner / minCell)
        var lines = (n + maxPer - 1) / maxPer
        if (lines > 2 && (n + 1) / 2 <= inner / tightCell) lines = 2
        per = (n + lines - 1) / lines
        cellW = minOf(inner / per, minCell * 2)
        left = paddingLeft + (inner - per * cellW) / 2
        for (i in 0 until n) getChildAt(i).measure(
            MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, paddingTop + paddingBottom + lines * cellH)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val x = left + (i % per) * cellW
            val y = paddingTop + (i / per) * cellH
            getChildAt(i).layout(x, y, x + cellW, y + cellH)
        }
    }
}

class StartMenu(private val act: HomeActivity) : LinearLayout(act) {
    private val pal = act.pal
    val search = EditText(act)
    private val clear = ImageView(act)
    private val list = ListView(act)
    private val adapter = Adapter()
    var isOpen = false; private set

    private sealed class Row {
        class H(val letter: String) : Row()
        class A(val app: AppInfo, val added: Boolean = false) : Row()
        class Web(val q: String) : Row()
        class Calc(val result: String) : Row()
        class Url(val address: String) : Row()
    }

    /** A–Z bar: one button per first character the visible apps actually start with. */
    private val letters = EvenGrid(act, dp(30), dp(20), dp(32))
    /** Character picked in the bar (list shows only those apps), or null for the full list. */
    private var letter: String? = null

    private var rows: List<Row> = emptyList()
    private var sections: Array<Any> = emptyArray()
    private var sectionPos = IntArray(0)

    init {
        orientation = VERTICAL
        isClickable = true
        elevation = dpf(18)
        background = rounded(pal.surface, dpf(22), 1, pal.divider)
        clipToOutline = true
        setPadding(dp(12), dp(14), dp(12), dp(6))

        // search field
        val box = linear(act, false) {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(pal.field, dpf(16))
            setPadding(dp(14), 0, dp(6), 0)
        }
        box.addView(ImageView(act).apply { setImageDrawable(Glyph(G.SEARCH, pal.textDim)) }, llp(dp(20), dp(20)))
        search.apply {
            hint = "Search apps"; background = null
            setTextColor(pal.text); setHintTextColor(pal.textDim); textSize = 15.5f; typeface = Fonts.regular
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(10), 0, dp(4), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { if (!s.isNullOrEmpty()) letter = null; refresh() }
            })
            setOnEditorActionListener { _, _, _ -> launchFirst(); true }
        }
        box.addView(search, llp(0, dp(46), 1f))
        clear.apply {
            setImageDrawable(Glyph(G.CLOSE, pal.textDim)); setPadding(dp(8), dp(8), dp(8), dp(8))
            background = rippleBg(pal.ripple, dpf(16)); visibility = GONE; contentDescription = "Clear"
            setOnClickListener { search.setText("") }
        }
        box.addView(clear, llp(dp(34), dp(34)))
        addView(box, llp(MATCH, WRAP))
        addView(letters, llp(MATCH, WRAP).apply { topMargin = dp(8) })

        // app list
        list.apply {
            divider = null; dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            clipToPadding = false
            setPadding(0, dp(8), 0, dp(8))
            adapter = this@StartMenu.adapter
            isFastScrollEnabled = true
        }
        addView(list, llp(MATCH, 0, 1f))

        // footer
        addView(View(act).apply { setBackgroundColor(pal.divider) }, llp(MATCH, 1))
        val footer = linear(act, false) { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        footer.addView(footerButton(G.WIDGETS, "Widgets") { act.closeStart(false); act.widgets.showPicker(null) }, llp(0, dp(56), 1f))
        footer.addView(footerButton(G.IMAGE, "Wallpaper") { act.closeStart(false); act.pickWallpaper() }, llp(0, dp(56), 1f))
        footer.addView(footerButton(G.SLIDERS, "Customize") { act.closeStart(false); act.showSettings() }, llp(0, dp(56), 1f))
        footer.addView(footerButton(G.GEAR, "Settings") { act.closeStart(false); act.safeStart(Intent(android.provider.Settings.ACTION_SETTINGS)) }, llp(0, dp(56), 1f))
        addView(footer, llp(MATCH, WRAP))
    }

    private fun footerButton(glyph: Int, text: String, onClick: () -> Unit) = linear(act, true) {
        gravity = Gravity.CENTER
        background = rippleBg(pal.ripple, dpf(14))
        addView(ImageView(act).apply { setImageDrawable(Glyph(glyph, pal.text)) }, llp(dp(21), dp(21)))
        addView(TextView(act).label(pal.textDim, 11.5f).apply { this.text = text; setPadding(0, dp(4), 0, 0) }, llp(WRAP, WRAP))
        setOnClickListener { onClick() }
    }

    fun position(screenW: Int, screenH: Int, bars: Insets, ime: Int) {
        val lp = layoutParams as FrameLayout.LayoutParams
        val bottom = maxOf(dp(Prefs.taskbarDp) + bars.bottom, ime) + dp(8)
        lp.gravity = Gravity.BOTTOM or Gravity.START
        lp.width = min(dp(420), screenW - dp(16) - bars.left - bars.right)
        lp.height = min(dp(680), screenH - bars.top - bottom - dp(8))
        lp.leftMargin = dp(8) + bars.left
        lp.bottomMargin = bottom
        layoutParams = lp
    }

    fun open() {
        isOpen = true
        letter = null
        search.setText("")
        refresh()
        list.setSelection(0)
        animate().cancel()
        visibility = VISIBLE
        alpha = 0f; translationY = dpf(28)
        animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator(2.2f)).start()
    }

    /** Fades out; [done] runs when hidden. */
    fun close(animate: Boolean, done: () -> Unit) {
        isOpen = false
        search.clearFocus()
        animate().cancel()
        if (animate) animate().alpha(0f).translationY(dpf(18)).setDuration(130).withEndAction(done).start()
        else { alpha = 0f; done() }
    }

    // ---------------------------------------------------------- content

    fun refresh() {
        val q = search.text.toString().trim().lowercase()
        clear.visibility = if (q.isEmpty()) GONE else VISIBLE
        val out = ArrayList<Row>()
        letters.visibility = if (q.isEmpty()) VISIBLE else GONE
        val all = Apps.visible()
        if (q.isEmpty()) bindLetters(all)
        val pick = letter
        if (q.isEmpty() && pick != null) {
            all.filter { sectionOf(it.label) == pick }.forEach { out += Row.A(it) }
            sections = emptyArray(); sectionPos = IntArray(0)
        } else if (q.isEmpty()) {
            val since = System.currentTimeMillis() - 14L * 24 * 3600_000
            val removed = Prefs.removedAdded
            val fresh = Apps.visible().filter { !it.system && it.installed > since && it.key !in removed }.sortedByDescending { it.installed }.take(3)
            if (fresh.isNotEmpty()) { out += Row.H("Recently added"); fresh.forEach { out += Row.A(it, added = true) } }
            val secs = ArrayList<Any>(); val pos = ArrayList<Int>()
            var last = ""
            for (app in all) {
                val l = sectionOf(app.label)
                if (l != last) { secs += l; pos += out.size; out += Row.H(l); last = l }
                out += Row.A(app)
            }
            sections = secs.toTypedArray(); sectionPos = pos.toIntArray()
        } else {
            val raw = search.text.toString().trim()
            val web = WebAddress.query(raw)
            if (web != null) {
                // "web:site.com" opens the site; "web:anything else" searches for it.
                out += when {
                    web.isEmpty() -> Row.H("Type a website after web:   e.g. web:wikipedia.org")
                    WebAddress.looksLike(web) -> Row.Url(web)
                    else -> Row.Web(web)
                }
            } else {
                Calc.answer(raw)?.let { out += Row.Calc(it) }
                out += Apps.visible().mapNotNull { a -> score(a.label.lowercase(), q).takeIf { it >= 0 }?.let { it to a } }
                    .sortedBy { it.first }.map { Row.A(it.second) }
                out += Row.Web(raw)
            }
            sections = emptyArray(); sectionPos = IntArray(0)
        }
        rows = out
        adapter.notifyDataSetChanged()
    }

    private fun sectionOf(label: String): String {
        val c = label.firstOrNull()?.uppercaseChar() ?: '#'
        return if (c.isLetterOrDigit()) c.toString() else "#"
    }

    /** Rebuild the A–Z bar from the apps' first characters (digits first, then letters, "#" last). */
    private fun bindLetters(apps: List<AppInfo>) {
        val keys = apps.map { sectionOf(it.label) }.distinct()
            .sortedWith(compareBy<String>({ it == "#" }, { !it[0].isDigit() }, { it }))
        if (letter != null && letter !in keys) letter = null
        letters.removeAllViews()
        for (k in keys) {
            val on = k == letter
            letters.addView(TextView(act).label(if (on) pal.onAccent else pal.text, 13.5f, bold = true).apply {
                text = k; gravity = Gravity.CENTER
                background = if (on) rounded(pal.accent, dpf(9)) else rippleBg(pal.ripple, dpf(9))
                contentDescription = if (k == "#") "Apps starting with a symbol" else "Apps starting with $k"
                setOnClickListener {
                    letter = if (letter == k) null else k
                    refresh(); list.setSelection(0)
                }
            })
        }
    }

    /** Back with a letter picked: show the full list again. */
    fun clearLetter(): Boolean {
        if (letter == null) return false
        letter = null; refresh(); list.setSelection(0)
        return true
    }

    private fun score(label: String, q: String): Int {
        if (label.startsWith(q)) return 0
        val words = label.split(' ', '-', '.', '_').filter { it.isNotEmpty() }
        if (words.any { it.startsWith(q) }) return 1
        if (label.contains(q)) return 2
        if (q.length >= 2 && words.joinToString("") { it.take(1) }.startsWith(q)) return 3
        return -1
    }

    private fun launchFirst() {
        rows.firstOrNull { it !is Row.H }?.let { activate(it, null) }
    }

    /** Tap (or Enter on the top row). */
    private fun activate(r: Row, icon: View?) {
        when (r) {
            is Row.A -> act.launch(r.app, icon)
            is Row.Web -> webSearch(r.q)
            is Row.Url -> openUrl(r.address)
            is Row.Calc -> copy(r.result)
            else -> {}
        }
    }

    private fun openUrl(address: String) {
        act.safeStart(Intent(Intent.ACTION_VIEW, Uri.parse(WebAddress.normalize(address))).addCategory(Intent.CATEGORY_BROWSABLE))
        act.closeStart(false)
    }

    private fun copy(text: String) {
        act.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Result", text))
        act.toast("Copied $text")
    }

    private fun webSearch(q: String) {
        act.safeStart(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))
        act.closeStart(false)
    }

    private class Holder(val icon: IconView, val label: TextView) { var row: Row? = null }

    private inner class Adapter : BaseAdapter(), SectionIndexer {
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p] is Row.H) 0 else 1
        override fun isEnabled(p: Int) = false
        override fun areAllItemsEnabled() = false

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val row = rows[p]
            if (row is Row.H) {
                val tv = (convert as? TextView) ?: TextView(act).label(pal.accent, 12.5f, bold = true).apply {
                    setPadding(dp(14), dp(10), dp(14), dp(2))
                    layoutParams = AbsListView.LayoutParams(MATCH, WRAP)
                }
                tv.text = row.letter
                return tv
            }
            val v = convert ?: appRow()
            val h = v.tag as Holder
            h.row = row
            when (row) {
                is Row.A -> {
                    h.icon.bitmap = row.app.icon; h.icon.drawable = null; h.icon.dot = Badges.has(row.app.pkg)
                    h.label.text = row.app.label
                }
                is Row.Web -> {
                    h.icon.bitmap = null; h.icon.drawable = Glyph(G.GLOBE, pal.accent); h.icon.dot = false
                    h.label.text = "Search the web for “${row.q}”"
                }
                is Row.Url -> {
                    h.icon.bitmap = null; h.icon.drawable = Glyph(G.GLOBE, pal.accent); h.icon.dot = false
                    h.label.text = "Open ${row.address}"
                }
                is Row.Calc -> {
                    h.icon.bitmap = null; h.icon.drawable = Glyph(G.EQUALS, pal.accent); h.icon.dot = false
                    h.label.text = "= ${row.result}   ·  tap to copy"
                }
                else -> {}
            }
            return v
        }

        override fun getSections(): Array<Any> = this@StartMenu.sections
        override fun getPositionForSection(s: Int) = sectionPos.getOrElse(s) { 0 }
        override fun getSectionForPosition(p: Int): Int {
            var s = 0
            for (i in sectionPos.indices) if (sectionPos[i] <= p) s = i else break
            return s
        }
    }

    private fun appRow(): View {
        val row = linear(act, false) {
            gravity = Gravity.CENTER_VERTICAL
            background = rippleBg(pal.ripple, dpf(12))
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = AbsListView.LayoutParams(MATCH, dp(50))
        }
        val icon = IconView(act).apply { dotColor = pal.accent; dotRing = pal.surfaceSolid }
        row.addView(icon, llp(dp(34), dp(34)))
        val label = TextView(act).label(pal.text, 15f).apply {
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(14), 0, 0, 0)
        }
        row.addView(label, llp(0, WRAP, 1f))
        val h = Holder(icon, label)
        row.tag = h
        row.setOnTouchListener(PressHelper(row,
            onTap = { h.row?.let { activate(it, icon) } },
            onMiddle = { h.row?.let { r -> if (r is Row.A) act.launchInWindow(r.app, icon) else activate(r, icon) } },
            onLong = { v, _, _ ->
                (h.row as? Row.A)?.let { r ->
                    act.appMenu(r.app, v, inStart = true, extra = if (!r.added) null else MenuEntry("Remove from Recently added", G.CLOSE) {
                        Prefs.removedAdded += r.app.key; this@StartMenu.refresh()
                    })
                }
            },
            onDrag = { v, _, _ ->
                val app = (h.row as? Row.A)?.app ?: return@PressHelper
                act.overlays.dismissAll()
                val size = dp(58)
                val p = DragPayload(DragPayload.MENU, app.key, null, size / 2f, size / 2f, size, size, null)
                if (v.startDragAndDrop(null, BitmapShadow(app.icon, size), p, View.DRAG_FLAG_OPAQUE)) act.hideStartForDrag()
            }))
        return row
    }
}
