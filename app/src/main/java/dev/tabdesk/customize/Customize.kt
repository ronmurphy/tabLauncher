package dev.tabdesk.customize

import android.content.res.ColorStateList
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.tabdesk.*

/** Customize: the panel of tabs (long-press or right-click Start). Each tab is built by a Page extension in its own file. */
object Customize {
    const val LOOK = 0; const val ICONS = 1; const val DESKTOP = 2; const val TASKBAR = 3; const val SYSTEM = 4; const val HELPER = 5
    const val AGENT = 6; const val BACKUP = 7; const val ABOUT = 8

    private class Tab(val title: String, val glyph: Int, val build: Page.() -> Unit)

    private val TABS = listOf(
        Tab("Look", G.IMAGE) { look() },
        Tab("Icons", G.PALETTE) { icons() },
        Tab("Desktop", G.GRID) { desktop() },
        Tab("Taskbar & Start", G.START) { taskbarStart() },
        Tab("Screen & system", G.MONITOR) { system() },
        Tab("Helper", G.WINDOW) { helper() },
        Tab("Agent", G.AGENT) { agent() },
        Tab("Backup", G.SAVE) { backup() },
        Tab("About", G.INFO) { about() },
    )

    /** Tab and scroll position to come back to (e.g. after a theme change rebuilds everything). */
    private var lastTab = LOOK
    private var lastScroll = 0
    private var layer: Overlays.Layer? = null
    /** Rebuilds the open tab where it is scrolled to. */
    private var reselect: (() -> Unit)? = null

    fun isShowing(act: HomeActivity) = layer?.let { act.overlays.isOpen(it) } == true

    /** Rebuilds [tab] if it's the one open, e.g. when coming back from Settings with something now done. */
    fun refreshIf(act: HomeActivity, tab: Int) { if (isShowing(act) && lastTab == tab) reselect?.invoke() }

    /** Open Customize; [tab] picks a page, otherwise it opens where it was last. */
    fun show(act: HomeActivity, restoreScroll: Boolean, tab: Int? = null) {
        val pal = act.pal
        if (tab != null) lastTab = tab
        // Tabs down the side only on a wide, landscape screen; portrait and phones get tabs across the top.
        val cfg = act.resources.configuration
        val wide = cfg.screenWidthDp >= 640 && cfg.screenWidthDp > cfg.screenHeightDp
        val card = act.overlays.dialogCard(act.dp(if (wide) 820 else 560), "Customize")
        card.layoutParams.height = act.dp(580)

        val body = linear(act, true) { setPadding(act.dp(if (wide) 10 else 2), act.dp(4), act.dp(6), act.dp(18)) }
        // Where the last touch landed, in the page's own coordinates: the row there is the one to keep in place.
        var touchedY = -1
        val scroll = object : ScrollView(act) {
            override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) touchedY = ev.y.toInt() + scrollY
                return super.dispatchTouchEvent(ev)
            }
        }.apply { isVerticalScrollBarEnabled = false; addView(body) }
        val tabViews = ArrayList<View>()
        val footer = "TabDesk ${BuildInfo.version(act)} · long-press or right-click Start to open this panel"

        fun style(v: View, i: Int) {
            val on = i == lastTab
            val glyph = v.findViewWithTag<ImageView>("glyph")
            val label = v.findViewWithTag<TextView>("label")
            glyph?.setImageDrawable(Glyph(TABS[i].glyph, if (on) pal.accent else pal.textDim))
            label?.setTextColor(pal.text)
            label?.typeface = if (on) Fonts.medium else Fonts.regular
            v.background = if (on) rounded(pal.accentSoft, act.dpf(if (wide) 12 else 16)) else rippleBg(pal.ripple, act.dpf(if (wide) 12 else 16))
        }

        /**
         * Each row's name, for finding the same row again in the rebuilt page: its first words (a switch's label, a
         * heading, a note), numbered when the same words come more than once. Null for a row without words.
         */
        fun rowNames(): List<String?> {
            fun words(v: View): String? = when {
                v is android.widget.EditText -> null
                v is TextView -> v.text?.toString()?.takeIf { it.isNotBlank() }
                v is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { words(v.getChildAt(it)) }
                else -> null
            }
            val seen = HashMap<String, Int>()
            return (0 until body.childCount).map { k ->
                words(body.getChildAt(k))?.let { w -> val n = seen.merge(w, 1, Int::plus)!!; "$n $w" }
            }
        }

        /**
         * Builds tab [i]. [keepPlace]: it's the open tab rebuilt in place (a switch flipped, a folder picked), so the
         * row the user last touched stays where it is on screen, even when something above it grew or shrank: a note
         * that lists the switches that are on, or the list of apps, which may have been only partly loaded when the
         * page was first built (just after TabDesk was installed or updated). If that touch is out of view now, the
         * first row that starts inside the panel stays instead. The row is found again by its words, since rows above it
         * may have come or gone (the apps list gets a search box once it has more than 8 apps), or else by the nearest
         * row above it that has the same words as before (its own may have changed: "Not set up yet" became the
         * folder's name). Otherwise it scrolls to [scrollTo].
         */
        fun select(i: Int, scrollTo: Int, keepPlace: Boolean = false) {
            var anchor = -1
            var offset = 0
            if (keepPlace && i == lastTab && body.isLaidOut) {
                val y = scroll.scrollY
                val bottom = y + scroll.height
                if (touchedY in y until bottom) for (k in 0 until body.childCount) {
                    val c = body.getChildAt(k)
                    if (touchedY >= c.top && touchedY < c.bottom) { anchor = k; offset = y - c.top; break }
                }
                if (anchor < 0) for (k in 0 until body.childCount) {
                    val c = body.getChildAt(k)
                    if (c.top >= y && c.top < bottom) { anchor = k; offset = y - c.top; break }
                }
                if (anchor < 0) for (k in 0 until body.childCount) {
                    val c = body.getChildAt(k)
                    if (c.bottom > y) { anchor = k; offset = y - c.top; break }
                }
            }
            val before = if (anchor >= 0) rowNames() else emptyList()
            if (i != lastTab) touchedY = -1
            lastTab = i
            body.removeAllViews()
            TABS[i].build(Page(act, body))
            if (anchor >= 0) {
                val after = rowNames()
                anchor = (anchor downTo 0).firstNotNullOfOrNull { k ->
                    before[k]?.let { after.indexOf(it) }?.takeIf { it >= 0 }?.let { it + anchor - k }
                } ?: anchor
            }
            if (!wide && i == TABS.lastIndex) body.addView(TextView(act).label(pal.textDim, 11.5f).apply {
                text = footer; gravity = Gravity.CENTER; setPadding(0, act.dp(22), 0, 0)
            }, llp(MATCH, WRAP))
            tabViews.forEachIndexed { j, v -> style(v, j) }
            if (anchor in 0 until body.childCount) {
                // Once the new rows are laid out: the same row at the same place in the panel as before.
                body.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int) {
                        v.removeOnLayoutChangeListener(this)
                        if (anchor < body.childCount) scroll.scrollTo(0, body.getChildAt(anchor).top + offset)
                    }
                })
                return
            }
            scroll.scrollTo(0, 0)
            if (scrollTo > 0) scroll.post { scroll.scrollTo(0, scrollTo) }
        }
        scroll.setOnScrollChangeListener { _, _, y, _, _ -> lastScroll = y }

        if (wide) {
            // Tabs down the left, like Android's own Settings on a tablet.
            val main = linear(act, false)
            val rail = linear(act, true) { setPadding(0, act.dp(4), 0, 0) }
            TABS.forEachIndexed { i, t ->
                val item = linear(act, false) {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(act.dp(12), 0, act.dp(10), 0)
                    contentDescription = t.title
                    addView(ImageView(act).apply { tag = "glyph" }, llp(act.dp(20), act.dp(20)))
                    addView(TextView(act).label(pal.text, 14.5f).apply {
                        tag = "label"; text = t.title; isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
                    }, llp(0, WRAP, 1f).apply { marginStart = act.dp(12) })
                    setOnClickListener { if (lastTab != i) select(i, 0) }
                }
                tabViews += item
                rail.addView(item, llp(MATCH, act.dp(46)).apply { bottomMargin = act.dp(3) })
            }
            rail.addView(View(act), llp(MATCH, 0, 1f))
            rail.addView(TextView(act).label(pal.textDim, 11f).apply { text = footer; setPadding(act.dp(12), 0, act.dp(8), act.dp(4)) })
            main.addView(rail, llp(act.dp(200), MATCH))
            main.addView(View(act).apply { setBackgroundColor(pal.divider) }, llp(1, MATCH).apply { marginStart = act.dp(10); marginEnd = act.dp(8) })
            main.addView(scroll, llp(0, MATCH, 1f))
            card.addView(main, llp(MATCH, 0, 1f))
        } else {
            // Narrow screen: the tabs become a row of chips under the title.
            val chips = linear(act, false) { gravity = Gravity.CENTER_VERTICAL }
            TABS.forEachIndexed { i, t ->
                val chip = RoomyText(act).label(pal.text, 13.5f).apply {
                    tag = "label"; text = t.title; gravity = Gravity.CENTER
                    setPadding(act.dp(14), 0, act.dp(14), 0)
                    setOnClickListener { if (lastTab != i) select(i, 0) }
                }
                tabViews += chip
                chips.addView(chip, llp(WRAP, act.dp(34)).apply { marginEnd = act.dp(4) })
            }
            card.addView(android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(chips) },
                llp(MATCH, WRAP).apply { bottomMargin = act.dp(10) })
            card.addView(scroll, llp(MATCH, 0, 1f))
        }

        select(lastTab, if (restoreScroll) lastScroll else 0)
        reselect = { select(lastTab, lastScroll, keepPlace = true) }
        layer = act.overlays.show(card, dim = true)
    }

}

/** One tab's content, with helpers for its sections, notes and rows. */
internal class Page(val act: HomeActivity, val body: LinearLayout) {
    val pal = act.pal
    fun section(title: String) = body.addView(TextView(act).label(pal.accent, 13f, bold = true).apply {
        text = title; setPadding(act.dp(2), if (body.childCount == 0) act.dp(2) else act.dp(20), 0, act.dp(8))
    })
    fun note(text: String) = TextView(act).label(pal.textDim, 12.5f).apply { this.text = text; setPadding(act.dp(2), act.dp(4), 0, 0) }
    fun add(v: View, lp: LinearLayout.LayoutParams = llp(MATCH, WRAP)) = body.addView(v, lp)
    fun addNote(text: String) = add(note(text))
    /** Explanation on the left, a button on the right. */
    fun noteWithButton(text: String, button: View, top: Int = 0) {
        val r = linear(act, false) { gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(top), 0, 0) }
        r.addView(note(text), llp(0, WRAP, 1f))
        r.addView(button, llp(WRAP, act.dp(38)).apply { marginStart = act.dp(10) })
        add(r)
    }
    /** Row of equal options, the current one filled with the accent colour. */
    fun segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val seg = linear(act, false) { background = rounded(pal.field, act.dpf(14)); setPadding(act.dp(4), act.dp(4), act.dp(4), act.dp(4)) }
        options.forEachIndexed { i, name ->
            val on = i == selected
            seg.addView(TextView(act).label(if (on) pal.onAccent else pal.text, 14f, bold = true).apply {
                text = name; gravity = Gravity.CENTER
                background = if (on) rounded(pal.accent, act.dpf(11)) else rippleBg(pal.ripple, act.dpf(11))
                setOnClickListener { if (!on) onPick(i) }
            }, llp(0, act.dp(40), 1f))
        }
        add(seg)
    }
}

/** Label + current value on one line, slider underneath. [invert] makes "right" mean smaller values (denser grid). */
internal fun sliderRow(act: HomeActivity, title: String, value: TextView, min: Int, max: Int, step: Int, current: Int,
                      invert: Boolean = false, onChange: (Int) -> Unit): View {
    val pal = act.pal
    val box = linear(act, true) { setPadding(0, act.dp(4), 0, act.dp(2)) }
    val top = linear(act, false) { gravity = Gravity.CENTER_VERTICAL }
    top.addView(TextView(act).label(pal.text, 14.5f).apply { text = title }, llp(0, WRAP, 1f))
    top.addView(value)
    box.addView(top, llp(MATCH, WRAP))
    val steps = (max - min) / step
    fun toValue(p: Int) = min + (if (invert) steps - p else p) * step
    box.addView(SeekBar(act).apply {
        this.max = steps
        progress = ((current - min) / step).let { if (invert) steps - it else it }
        progressTintList = ColorStateList.valueOf(pal.accent)
        thumbTintList = ColorStateList.valueOf(pal.accent)
        progressBackgroundTintList = ColorStateList.valueOf(pal.textDim)
        setPadding(act.dp(10), act.dp(8), act.dp(10), act.dp(8))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(toValue(p)) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }, llp(MATCH, WRAP))
    return box
}

@Suppress("DEPRECATION")
internal fun switchRow(act: HomeActivity, text: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
    val pal = act.pal
    val r = linear(act, false) { gravity = Gravity.CENTER_VERTICAL }
    r.addView(TextView(act).label(pal.text, 14.5f).apply { this.text = text }, llp(0, WRAP, 1f))
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    r.addView(Switch(act).apply {
        isChecked = checked
        thumbTintList = ColorStateList(states, intArrayOf(pal.accent, pal.textDim))
        trackTintList = ColorStateList(states, intArrayOf(pal.accentSoft, pal.field))
        setOnCheckedChangeListener { _, c -> onChange(c) }
    })
    return r
}
