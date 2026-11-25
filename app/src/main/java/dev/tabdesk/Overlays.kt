package dev.tabdesk

import android.content.res.ColorStateList
import android.graphics.Insets
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import kotlin.math.max
import kotlin.math.min

class MenuEntry(
    val label: CharSequence,
    val glyph: Int = 0,
    val icon: Drawable? = null,
    val action: (() -> Unit)? = null,
) {
    companion object { val DIVIDER = MenuEntry("") }
}

/** Topmost layer: context menus and dialogs, each with its own tap-outside-to-close scrim. */
class Overlays(private val act: HomeActivity) : FrameLayout(act) {
    private val stack = ArrayList<Layer>()
    private var bars: Insets = Insets.NONE
    private var ime = 0
    /** The keyboard's height over the window above floating windows (while a dialog there takes typing). */
    private var aboveIme = 0
    /** The status bar's height there, when it shows (TabDesk's own window may lay out as if it were hidden). */
    private var aboveTop = 0
    private val pal get() = act.pal

    val isOpen get() = stack.isNotEmpty()

    fun setInsets(b: Insets, imeBottom: Int) {
        bars = b; ime = imeBottom
        stack.forEach { it.requestLayout() }
    }

    /** [frame]: where the content goes exactly, in screen coordinates (e.g. the widget dock); else by [anchor]. */
    inner class Layer(val content: View, private val anchor: Rect?, val dim: Boolean, val onDismiss: (() -> Unit)?,
                      private val frame: (() -> Rect)? = null) : FrameLayout(act) {
        private val imeNow get() = if (above != null && parent === above) aboveIme else ime
        private val topNow get() = if (above != null && parent === above) max(bars.top, aboveTop) else bars.top

        init {
            setBackgroundColor(if (dim) pal.scrim else 0)
            isClickable = true
            setOnClickListener { dismiss(this) }
            content.isClickable = true
            addView(content)
        }

        private fun framed(): Rect? = frame?.invoke()?.let { r ->
            val o = IntArray(2).also { getLocationOnScreen(it) }
            Rect(r).apply { offset(-o[0], -o[1]) }
        }

        override fun onMeasure(ws: Int, hs: Int) {
            val w = MeasureSpec.getSize(ws); val h = MeasureSpec.getSize(hs)
            setMeasuredDimension(w, h)
            framed()?.let { r ->
                content.measure(MeasureSpec.makeMeasureSpec(max(0, r.width()), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(max(0, r.height()), MeasureSpec.EXACTLY))
                return
            }
            val m = dp(12)
            val maxW = max(0, w - bars.left - bars.right - 2 * m)
            val maxH = max(0, h - topNow - max(bars.bottom, imeNow) - 2 * m)
            val lp = content.layoutParams
            val cw = if (lp.width > 0) MeasureSpec.makeMeasureSpec(min(lp.width, maxW), MeasureSpec.EXACTLY)
            else MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST)
            val ch = if (lp.height > 0) MeasureSpec.makeMeasureSpec(min(lp.height, maxH), MeasureSpec.EXACTLY)
            else MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
            content.measure(cw, ch)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            framed()?.let { f -> content.layout(f.left, f.top, f.left + content.measuredWidth, f.top + content.measuredHeight); return }
            val w = r - l; val h = b - t
            val cw = content.measuredWidth; val ch = content.measuredHeight
            val m = dp(12)
            val top = topNow + m
            val bottom = h - max(bars.bottom, imeNow) - m
            val minX = bars.left + m
            val maxX = max(minX, w - bars.right - m - cw)
            val a = anchor
            val x: Int; val y: Int
            if (a != null) {
                x = (if (a.width() == 0) a.left else a.centerX() - cw / 2).coerceIn(minX, maxX)
                y = when {
                    a.bottom + dp(6) + ch <= bottom -> a.bottom + dp(6)
                    a.top - dp(6) - ch >= top -> a.top - dp(6) - ch
                    else -> max(top, bottom - ch)
                }
            } else {
                x = (w - cw) / 2
                y = top + max(0, (bottom - top - ch) / 2)
            }
            content.layout(x, y, x + cw, y + ch)
        }
    }

    /**
     * Floating windows cover TabDesk's own window, so while one is on screen, menus, dialogs and the widget dock go
     * in a window above them ("Display over other apps", which the helper grants). It covers the screen like the
     * layers here do, and goes once its last layer closes. It takes the keyboard only while a dialog is in it (to
     * type a name, say); with just menus, the app below keeps it.
     */
    private var above: FrameLayout? = null

    private fun aboveHost(): FrameLayout? {
        above?.let { return it }
        if (!Windows.covering(act)) return null
        val host = object : FrameLayout(act) {
            // Back closes the top layer here, as it does in TabDesk's own window (Android 12; later ones use the
            // callback in updateAboveFocus).
            override fun dispatchKeyEvent(e: KeyEvent): Boolean {
                if (e.keyCode != KeyEvent.KEYCODE_BACK || Build.VERSION.SDK_INT >= 33) return super.dispatchKeyEvent(e)
                if (e.action == KeyEvent.ACTION_UP) dismissTopAbove()
                return true
            }
        }
        host.setOnApplyWindowInsetsListener { _, ins ->
            val b = ins.getInsets(WindowInsets.Type.ime()).bottom
            val t = ins.getInsets(WindowInsets.Type.statusBars()).top
            if (b != aboveIme || t != aboveTop) { aboveIme = b; aboveTop = t; stack.forEach { it.requestLayout() } }
            ins
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            title = "TabDesk menu"
        }
        return try { act.windowManager.addView(host, lp); above = host; host } catch (_: Exception) { null }
    }

    private fun dismissTopAbove() { val h = above ?: return; stack.lastOrNull { it.parent === h }?.let { dismiss(it) } }
    private var aboveBack: Any? = null

    /** Focusable (keyboard and Back) only while a dialog is in the window above. */
    private fun updateAboveFocus() {
        val h = above ?: return
        val lp = h.layoutParams as? WindowManager.LayoutParams ?: return
        val nf = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val focus = stack.any { it.parent === h && it.dim }
        val fs = if (Prefs.hideStatus) @Suppress("DEPRECATION") WindowManager.LayoutParams.FLAG_FULLSCREEN else 0
        val flags = if (focus) (lp.flags and nf.inv()) or fs else (lp.flags or nf) and fs.inv()
        if (flags == lp.flags) return
        lp.flags = flags
        try { act.windowManager.updateViewLayout(h, lp) } catch (_: Exception) {}
        if (focus) h.windowInsetsController?.let { c ->
            // Holding the keyboard, this window decides the status bar: keep it as TabDesk has it, or the
            // desktop would shrink under it while the dialog is open.
            c.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (Prefs.hideStatus) c.hide(WindowInsets.Type.statusBars())
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val d = h.findOnBackInvokedDispatcher() ?: return
            if (focus && aboveBack == null) {
                val cb = OnBackInvokedCallback { dismissTopAbove() }
                d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
                aboveBack = cb
            } else if (!focus) (aboveBack as? OnBackInvokedCallback)?.let { d.unregisterOnBackInvokedCallback(it); aboveBack = null }
        }
    }

    private fun closeAbove() {
        val h = above ?: return
        above = null
        aboveIme = 0; aboveTop = 0
        aboveBack = null
        try { act.windowManager.removeViewImmediate(h) } catch (_: Exception) {}
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closeAbove()
    }

    fun show(content: View, anchor: Rect? = null, dim: Boolean = false, onDismiss: (() -> Unit)? = null,
             aboveWindows: Boolean = false, frame: (() -> Rect)? = null): Layer {
        if (content.layoutParams == null) content.layoutParams = LayoutParams(WRAP, WRAP)
        // Dialogs too: one under a floating window couldn't be seen or used.
        val host = if (aboveWindows || dim) aboveHost() else null
        // The window above starts at the screen's corner; this view may not.
        val at = if (host != null && anchor != null) Rect(anchor).apply {
            val o = IntArray(2).also { getLocationOnScreen(it) }; offset(o[0], o[1])
        } else anchor
        val layer = Layer(content, at, dim, onDismiss, frame)
        (host ?: this).addView(layer, LayoutParams(MATCH, MATCH))
        stack += layer
        content.alpha = 0f; content.scaleX = 0.96f; content.scaleY = 0.96f
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).setInterpolator(DecelerateInterpolator(1.6f)).start()
        if (dim) { layer.alpha = 0f; layer.animate().alpha(1f).setDuration(150).start() }
        if (host != null) updateAboveFocus()
        return layer
    }

    fun dismiss(layer: Layer) {
        if (!stack.remove(layer)) return
        layer.isClickable = false
        layer.findFocus()?.let { f -> f.windowInsetsController?.hide(WindowInsets.Type.ime()) ?: act.hideIme() }
        layer.onDismiss?.invoke()
        if (layer.parent === above) updateAboveFocus()
        layer.animate().alpha(0f).setDuration(110).withEndAction {
            val parent = layer.parent as? ViewGroup
            parent?.removeView(layer)
            if (parent != null && parent === above && parent.childCount == 0) closeAbove()
        }.start()
    }

    fun dismissTop(): Boolean {
        val l = stack.lastOrNull() ?: return false
        dismiss(l); return true
    }

    fun dismissAll() = stack.toList().forEach { dismiss(it) }

    /** TabDesk went out of sight: menus above other windows would stay over whatever is in front now. */
    fun dismissAbove() { val h = above ?: return; stack.filter { it.parent === h }.forEach { dismiss(it) } }

    fun isOpen(layer: Layer) = layer in stack

    // ------------------------------------------------------------ building blocks

    fun menu(anchor: Rect, entries: List<MenuEntry>, title: CharSequence? = null) {
        val card = linear(act, true) {
            background = rounded(pal.surfaceSolid, dpf(16), 1, pal.divider)
            elevation = dpf(14)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            minimumWidth = dp(230)
        }
        if (title != null) card.addView(TextView(act).label(pal.textDim, 12.5f, bold = true).apply {
            text = title; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(280)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        })
        lateinit var layer: Layer
        for (e in entries) {
            if (e === MenuEntry.DIVIDER) {
                card.addView(Hairline(act).apply { setBackgroundColor(pal.divider) }, llp(MATCH, 1).apply { setMargins(dp(10), dp(4), dp(10), dp(4)) })
                continue
            }
            val row = linear(act, false) {
                gravity = Gravity.CENTER_VERTICAL
                background = rippleBg(pal.ripple, dpf(11))
                setPadding(dp(12), 0, dp(18), 0)
            }
            row.addView(ImageView(act).apply { setImageDrawable(e.icon ?: if (e.glyph != 0) Glyph(e.glyph, pal.text) else null) }, llp(dp(20), dp(20)))
            row.addView(TextView(act).label(pal.text, 14.5f).apply {
                text = e.label; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(280)
                setPadding(dp(14), 0, 0, 0)
            })
            row.setOnClickListener { dismiss(layer); e.action?.invoke() }
            card.addView(row, llp(MATCH, dp(44)))
        }
        layer = show(card, anchor, aboveWindows = true)
    }

    /** Rounded dialog card with an optional title row and close button. Add content to the returned layout. */
    fun dialogCard(width: Int, title: String?): LinearLayout {
        val card = linear(act, true) {
            background = rounded(pal.surfaceSolid, dpf(22), 1, pal.divider)
            elevation = dpf(18)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LayoutParams(width, WRAP)
        }
        if (title != null) {
            val head = linear(act, false) { gravity = Gravity.CENTER_VERTICAL }
            head.addView(TextView(act).label(pal.text, 19f, bold = true).apply { text = title }, llp(0, WRAP, 1f))
            head.addView(ImageView(act).apply {
                setImageDrawable(Glyph(G.CLOSE, pal.textDim))
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rippleBg(pal.ripple, dpf(18))
                contentDescription = "Close"
                setOnClickListener { dismissTop() }
            }, llp(dp(38), dp(38)))
            card.addView(head, llp(MATCH, WRAP).apply { bottomMargin = dp(6) })
        }
        return card
    }

    fun button(text: String, filled: Boolean, onClick: () -> Unit) = RoomyText(act).apply {
        this.text = text
        textSize = 14.5f; typeface = MEDIUM; gravity = Gravity.CENTER
        setPadding(dp(18), 0, dp(18), 0)
        minWidth = dp(76)
        setTextColor(if (filled) pal.onAccent else pal.accent)
        background = if (filled) RippleDrawable(ColorStateList.valueOf(pal.ripple), rounded(pal.accent, dpf(20)), null)
        else rippleBg(pal.ripple, dpf(20))
        setOnClickListener { onClick() }
    }

    fun prompt(title: String, initial: String, hint: String, onReset: (() -> Unit)?, onOk: (String) -> Unit) {
        val card = dialogCard(dp(400), title)
        val et = EditText(act).apply {
            setText(initial); setSelectAllOnFocus(true); isSingleLine = true
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = rounded(pal.field, dpf(12))
            setTextColor(pal.text); setHintTextColor(pal.textDim); textSize = 16f; typeface = Fonts.regular
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(et, llp(MATCH, WRAP).apply { topMargin = dp(6); bottomMargin = dp(14) })
        val buttons = linear(act, false) { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        lateinit var layer: Layer
        val save = { onOk(et.text.toString()); dismiss(layer) }
        if (onReset != null) {
            buttons.addView(button("Reset", false) { onReset(); dismiss(layer) }, llp(WRAP, dp(40)))
            buttons.addView(View(act), llp(0, 1, 1f))
        }
        buttons.addView(button("Cancel", false) { dismiss(layer) }, llp(WRAP, dp(40)))
        buttons.addView(button("Save", true) { save() }, llp(WRAP, dp(40)).apply { marginStart = dp(8) })
        card.addView(buttons, llp(MATCH, WRAP))
        et.setOnEditorActionListener { _, _, _ -> save(); true }
        layer = show(card, dim = true)
        et.requestFocus()
        et.post { et.windowInsetsController?.show(WindowInsets.Type.ime()) }
    }
}
