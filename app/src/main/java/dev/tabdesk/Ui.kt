package dev.tabdesk

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------- layout helpers

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density
fun View.dp(v: Number) = context.dp(v)
fun View.dpf(v: Number) = context.dpf(v)

fun llp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
fun flp(w: Int, h: Int, gravity: Int = -1) = FrameLayout.LayoutParams(w, h, gravity)

inline fun linear(ctx: Context, vertical: Boolean, block: LinearLayout.() -> Unit = {}) =
    LinearLayout(ctx).apply { orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; block() }

/** Bold-ish text in the chosen font (see [Fonts]). */
val MEDIUM: Typeface get() = Fonts.medium

fun rounded(color: Int, radius: Float, strokeW: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius
    if (strokeW > 0) setStroke(strokeW, strokeColor)
}

fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

/**
 * Ripple with a rounded mask; [fill] is the resting background (transparent by default).
 * Under a mouse pointer it shows a soft wash in the ripple colour: the ripple's own hover tint is too faint to see.
 */
fun rippleBg(ripple: Int, radius: Float, fill: Int = Color.TRANSPARENT): Drawable {
    val wash = Color.argb(Color.alpha(ripple) * 3 / 4, Color.red(ripple), Color.green(ripple), Color.blue(ripple))
    val content = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_hovered), LayerDrawable(arrayOf(rounded(fill, radius), rounded(wash, radius))))
        addState(intArrayOf(), rounded(fill, radius))
    }
    return RippleDrawable(ColorStateList.valueOf(ripple), content, rounded(Color.WHITE, radius))
}

/** Long-press, or a right-click with a mouse: [action], usually the view's menu. For views that use plain clicks. */
fun View.onMenu(action: (View) -> Unit) {
    setOnLongClickListener { action(it); true }
    setOnContextClickListener { action(it); true }
}

fun mix(a: Int, b: Int, t: Float): Int {
    fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
    return Color.argb(
        ch(Color.alpha(a), Color.alpha(b)), ch(Color.red(a), Color.red(b)),
        ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)),
    )
}

fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

fun View.screenRect(): Rect {
    val l = IntArray(2); getLocationOnScreen(l)
    return Rect(l[0], l[1], l[0] + width, l[1] + height)
}

/** Rect of [v] in [ancestor]'s coordinate space. */
fun View.rectIn(ancestor: View): Rect {
    val a = IntArray(2); val b = IntArray(2)
    getLocationInWindow(a); ancestor.getLocationInWindow(b)
    return Rect(a[0] - b[0], a[1] - b[1], a[0] - b[0] + width, a[1] - b[1] + height)
}

// ---------------------------------------------------------------- theme

class Palette(val dark: Boolean, val accent: Int) {
    val onAccent = if (Color.luminance(accent) > 0.45f) 0xFF111216.toInt() else Color.WHITE
    val surfaceSolid = mix(if (dark) 0xFF1D1E22.toInt() else 0xFFF7F7F9.toInt(), accent, 0.05f)
    val surface = withAlpha(surfaceSolid, 0xFC)
    val field = mix(if (dark) 0xFF2C2D32.toInt() else 0xFFE8E8ED.toInt(), accent, 0.07f)
    val text = if (dark) 0xFFECEDF0.toInt() else 0xFF1A1B1F.toInt()
    val textDim = if (dark) 0xFFA2A5AC.toInt() else 0xFF5C5F66.toInt()
    val divider = if (dark) 0x22FFFFFF else 0x1C000000
    val taskbar = withAlpha(mix(if (dark) 0xFF131417.toInt() else 0xFFF1F1F4.toInt(), accent, 0.07f), 0xEB)
    val ripple = if (dark) 0x33FFFFFF else 0x22000000
    val accentSoft = withAlpha(accent, if (dark) 0x3A else 0x2E)
    val scrim = 0x66000000

    companion object {
        /** 0 = follow wallpaper (Material You accent). */
        val PRESETS = intArrayOf(
            0, 0xFF4C8DF6.toInt(), 0xFF26A69A.toInt(), 0xFF5BB974.toInt(), 0xFFF6B93B.toInt(),
            0xFFFF8A50.toInt(), 0xFFEF5350.toInt(), 0xFFEC6FA5.toInt(), 0xFF9C7CF4.toInt(), 0xFF8A9BB0.toInt(),
        )

        fun isDark(ctx: Context) = when (Prefs.themeMode) {
            1 -> false
            2 -> true
            else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }

        fun wallpaperAccent(ctx: Context, dark: Boolean): Int =
            ctx.getColor(if (dark) android.R.color.system_accent1_300 else android.R.color.system_accent1_600)

        fun resolve(ctx: Context): Palette {
            val dark = isDark(ctx)
            val a = Prefs.accent.takeIf { it != 0 } ?: wallpaperAccent(ctx, dark)
            return Palette(dark, a)
        }
    }
}

// ---------------------------------------------------------------- line-icon glyphs (drawn, not bitmaps)

object G {
    const val SEARCH = 1; const val WIDGETS = 2; const val IMAGE = 3; const val SLIDERS = 4; const val GEAR = 5
    const val INFO = 6; const val PIN = 7; const val UNPIN = 8; const val MONITOR = 9; const val CLOSE = 10
    const val EDIT = 11; const val EYE_OFF = 12; const val EYE = 13; const val TRASH = 14; const val RESIZE = 15
    const val PLUS = 16; const val START = 17; const val GLOBE = 18; const val CHECK = 19; const val GRID = 20
    const val HOME = 21; const val BELL = 22; const val WINDOW = 23; const val BOX = 24; const val BOX_ON = 25
    const val EQUALS = 26; const val FOLDER = 27; const val LOCK = 28; const val SAVE = 29; const val PALETTE = 30
}

class Glyph(private val kind: Int, color: Int) : Drawable() {
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.9f; this.color = color
    }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    private val p = Path()
    private val r = RectF()

    private fun Canvas.rr(l: Float, t: Float, rt: Float, b: Float, rad: Float) { r.set(l, t, rt, b); drawRoundRect(r, rad, rad, s) }
    private fun Canvas.line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(x1, y1, x2, y2, s)
    private fun poly(vararg xy: Float, close: Boolean = false) {
        p.reset(); p.moveTo(xy[0], xy[1])
        var i = 2; while (i < xy.size) { p.lineTo(xy[i], xy[i + 1]); i += 2 }
        if (close) p.close()
    }

    override fun draw(c: Canvas) {
        val b = bounds
        val sc = min(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.left + (b.width() - 24 * sc) / 2f, b.top + (b.height() - 24 * sc) / 2f)
        c.scale(sc, sc)
        when (kind) {
            G.SEARCH -> { c.drawCircle(10.5f, 10.5f, 6.5f, s); c.line(15.5f, 15.5f, 20.5f, 20.5f) }
            G.WIDGETS -> {
                c.rr(3.5f, 3.5f, 10.5f, 10.5f, 2f); c.rr(13.5f, 3.5f, 20.5f, 10.5f, 2f); c.rr(3.5f, 13.5f, 10.5f, 20.5f, 2f)
                c.line(17f, 13.5f, 17f, 20.5f); c.line(13.5f, 17f, 20.5f, 17f)
            }
            G.GRID -> {
                c.rr(3.5f, 3.5f, 10.5f, 10.5f, 2f); c.rr(13.5f, 3.5f, 20.5f, 10.5f, 2f)
                c.rr(3.5f, 13.5f, 10.5f, 20.5f, 2f); c.rr(13.5f, 13.5f, 20.5f, 20.5f, 2f)
            }
            G.IMAGE -> {
                c.rr(3f, 4f, 21f, 20f, 3f); c.drawCircle(8.5f, 9f, 1.7f, f)
                poly(3.5f, 17.5f, 9f, 12.5f, 13f, 16f, 16f, 13.5f, 20.5f, 17.5f); c.drawPath(p, s)
            }
            G.SLIDERS -> {
                c.line(3f, 6.5f, 21f, 6.5f); c.line(3f, 12f, 21f, 12f); c.line(3f, 17.5f, 21f, 17.5f)
                c.drawCircle(15f, 6.5f, 2.6f, f); c.drawCircle(8f, 12f, 2.6f, f); c.drawCircle(17f, 17.5f, 2.6f, f)
            }
            G.GEAR -> {
                p.reset()
                for (k in 0 until 32) {
                    val a = Math.toRadians(k * 11.25 - 5.6)
                    val rad = if (k % 4 < 2) 9.6 else 7.0
                    val x = (12 + rad * cos(a)).toFloat(); val y = (12 + rad * sin(a)).toFloat()
                    if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close(); c.drawPath(p, s); c.drawCircle(12f, 12f, 3f, s)
            }
            G.INFO -> { c.drawCircle(12f, 12f, 9f, s); c.line(12f, 11f, 12f, 16.5f); c.drawCircle(12f, 7.7f, 1.2f, f) }
            G.PIN, G.UNPIN -> {
                c.line(8.5f, 3.5f, 15.5f, 3.5f)
                poly(10f, 3.5f, 10f, 9f, 7f, 13.5f, 17f, 13.5f, 14f, 9f, 14f, 3.5f); c.drawPath(p, s)
                c.line(12f, 13.5f, 12f, 20.5f)
                if (kind == G.UNPIN) c.line(3.5f, 3.5f, 20.5f, 20.5f)
            }
            G.MONITOR -> { c.rr(2.5f, 4f, 21.5f, 16f, 2f); c.line(12f, 16f, 12f, 20f); c.line(8f, 20f, 16f, 20f) }
            G.CLOSE -> { c.line(6f, 6f, 18f, 18f); c.line(18f, 6f, 6f, 18f) }
            G.EDIT -> {
                poly(4f, 20f, 8f, 19.5f, 19f, 8.5f, 15.5f, 5f, 4.5f, 16f, close = true); c.drawPath(p, s)
                c.line(13f, 7.5f, 16.5f, 11f)
            }
            G.EYE, G.EYE_OFF -> {
                p.reset(); p.moveTo(2.5f, 12f)
                p.cubicTo(5f, 7f, 8.5f, 5f, 12f, 5f); p.cubicTo(15.5f, 5f, 19f, 7f, 21.5f, 12f)
                p.cubicTo(19f, 17f, 15.5f, 19f, 12f, 19f); p.cubicTo(8.5f, 19f, 5f, 17f, 2.5f, 12f); p.close()
                c.drawPath(p, s); c.drawCircle(12f, 12f, 3f, s)
                if (kind == G.EYE_OFF) c.line(4f, 4f, 20f, 20f)
            }
            G.TRASH -> {
                c.line(3.5f, 6.5f, 20.5f, 6.5f)
                poly(5.5f, 6.5f, 6.5f, 20f, 17.5f, 20f, 18.5f, 6.5f); c.drawPath(p, s)
                poly(9f, 6.5f, 9f, 3.5f, 15f, 3.5f, 15f, 6.5f); c.drawPath(p, s)
                c.line(10f, 10.5f, 10f, 16f); c.line(14f, 10.5f, 14f, 16f)
            }
            G.RESIZE -> {
                poly(14f, 3.5f, 20.5f, 3.5f, 20.5f, 10f); c.drawPath(p, s); c.line(20.5f, 3.5f, 13.5f, 10.5f)
                poly(10f, 20.5f, 3.5f, 20.5f, 3.5f, 14f); c.drawPath(p, s); c.line(3.5f, 20.5f, 10.5f, 13.5f)
            }
            G.PLUS -> { c.line(12f, 5f, 12f, 19f); c.line(5f, 12f, 19f, 12f) }
            G.START -> { s.strokeWidth = 2.3f; c.drawCircle(12f, 12f, 8.6f, s); c.drawCircle(12f, 12f, 3.7f, f); s.strokeWidth = 1.9f }
            G.GLOBE -> {
                c.drawCircle(12f, 12f, 9f, s); c.line(3f, 12f, 21f, 12f)
                r.set(7.5f, 3f, 16.5f, 21f); c.drawOval(r, s)
            }
            G.CHECK -> { s.strokeWidth = 2.6f; poly(5f, 12.5f, 10f, 17.5f, 19.5f, 7f); c.drawPath(p, s); s.strokeWidth = 1.9f }
            G.WINDOW -> {
                c.rr(3f, 4.5f, 21f, 19.5f, 2.5f); c.line(3f, 8.8f, 21f, 8.8f)
                c.drawCircle(6f, 6.65f, 0.9f, f); c.drawCircle(8.8f, 6.65f, 0.9f, f)
            }
            G.PALETTE -> {
                p.reset(); p.moveTo(12f, 3.5f)
                p.cubicTo(17f, 3.5f, 21f, 7f, 21f, 11.5f); p.cubicTo(21f, 15f, 18.2f, 15.6f, 16.2f, 15.1f)
                p.cubicTo(14.3f, 14.6f, 12.9f, 16f, 13.5f, 17.6f); p.cubicTo(14.1f, 19.4f, 13.2f, 20.5f, 11.6f, 20.5f)
                p.cubicTo(6.8f, 20.5f, 3f, 16.7f, 3f, 12f); p.cubicTo(3f, 7.3f, 7f, 3.5f, 12f, 3.5f); p.close()
                c.drawPath(p, s)
                c.drawCircle(7.8f, 11f, 1.4f, f); c.drawCircle(10.8f, 7.6f, 1.4f, f); c.drawCircle(15.4f, 8.2f, 1.4f, f)
            }
            G.EQUALS -> { c.line(6f, 9.5f, 18f, 9.5f); c.line(6f, 14.5f, 18f, 14.5f) }
            G.FOLDER -> {
                poly(3f, 6.5f, 3f, 18.5f, 21f, 18.5f, 21f, 8.5f, 11.5f, 8.5f, 9.5f, 5.5f, 4f, 5.5f, close = true); c.drawPath(p, s)
            }
            G.LOCK -> { c.rr(5f, 10.5f, 19f, 20f, 2.5f); poly(8f, 10.5f, 8f, 7.5f); c.drawPath(p, s); r.set(8f, 3.5f, 16f, 11.5f); c.drawArc(r, 180f, 180f, false, s); c.line(16f, 7.5f, 16f, 10.5f) }
            G.SAVE -> { c.line(12f, 4f, 12f, 14.5f); poly(7.5f, 10.5f, 12f, 15f, 16.5f, 10.5f); c.drawPath(p, s); poly(4.5f, 15.5f, 4.5f, 19.5f, 19.5f, 19.5f, 19.5f, 15.5f); c.drawPath(p, s) }
            G.BOX, G.BOX_ON -> {
                c.rr(4f, 4f, 20f, 20f, 4.5f)
                if (kind == G.BOX_ON) { poly(8f, 12.4f, 11f, 15.4f, 16.5f, 9.2f); c.drawPath(p, s) }
            }
            G.HOME -> { poly(3.5f, 11f, 12f, 4f, 20.5f, 11f); c.drawPath(p, s); poly(6f, 9.5f, 6f, 20f, 18f, 20f, 18f, 9.5f); c.drawPath(p, s) }
            G.BELL -> {
                p.reset(); p.moveTo(5f, 17f); p.lineTo(19f, 17f); p.lineTo(17.5f, 15f); p.lineTo(17.5f, 10.5f)
                p.cubicTo(17.5f, 7f, 15f, 4.5f, 12f, 4.5f); p.cubicTo(9f, 4.5f, 6.5f, 7f, 6.5f, 10.5f)
                p.lineTo(6.5f, 15f); p.close(); c.drawPath(p, s); c.line(10f, 20f, 14f, 20f)
            }
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) { s.alpha = alpha; f.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { s.colorFilter = cf; f.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

// ---------------------------------------------------------------- icon with notification dot

class IconView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    var drawable: Drawable? = null
        set(v) { field = v; invalidate() }
    var dot = false
        set(v) { if (field != v) { field = v; invalidate() } }
    var dotColor = Color.RED
    var dotRing = Color.WHITE

    private val bp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val dstI = Rect()

    override fun onDraw(c: Canvas) {
        val size = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        val l = paddingLeft + (width - paddingLeft - paddingRight - size) / 2f
        val t = paddingTop + (height - paddingTop - paddingBottom - size) / 2f
        dst.set(l, t, l + size, t + size)
        val b = bitmap
        if (b != null) c.drawBitmap(b, null, dst, bp)
        else drawable?.let { dst.round(dstI); it.bounds = dstI; it.draw(c) }
        if (dot) {
            val r = size * 0.115f
            val cx = dst.right - r * 0.8f
            val cy = dst.top + r * 0.8f
            if (Color.alpha(dotRing) > 0) { dp.color = dotRing; c.drawCircle(cx, cy, r + size * 0.04f, dp) }
            dp.color = dotColor; c.drawCircle(cx, cy, r, dp)
        }
    }
}

// ---------------------------------------------------------------- tap / long-press-menu / drag gesture

/**
 * One gesture model for every launchable thing: tap opens, holding shows a menu,
 * and moving while still holding turns into a drag (the menu closes).
 */
class PressHelper(
    private val view: View,
    var onTap: ((View) -> Unit)? = null,
    var onLong: ((View, Float, Float) -> Unit)? = null,
    var onDrag: ((View, Float, Float) -> Unit)? = null,
    /** With a mouse, moving with the button down drags at once, without holding still first. Off for widgets, so
     *  dragging inside one still scrolls or slides its own content. */
    private val mouseDrag: Boolean = true,
    /** Middle-click (the wheel pressed): the other way to open, e.g. in a floating window. None: it does nothing. */
    var onMiddle: ((View) -> Unit)? = null,
) : View.OnTouchListener {
    companion object {
        /** A mouse right-click, or a pen's side button: the gesture that opens a menu straight away. */
        fun isMenuClick(e: MotionEvent) =
            e.isButtonPressed(MotionEvent.BUTTON_SECONDARY) || e.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)
        fun isMiddleClick(e: MotionEvent) = e.isButtonPressed(MotionEvent.BUTTON_TERTIARY)
    }

    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
    var downX = 0f; private set
    var downY = 0f; private set
    var longPressed = false; private set
    private var tracking = false
    private var dragging = false
    private var mouse = false
    private var middle = false

    init {
        // These views take touches themselves, so Android doesn't mark them hovered: do it, for rippleBg's highlight.
        view.setOnHoverListener { v, e ->
            if (e.isFromSource(InputDevice.SOURCE_MOUSE)) when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> v.isHovered = true
                MotionEvent.ACTION_HOVER_EXIT -> v.isHovered = false
            }
            false
        }
    }

    private val longRunnable = Runnable {
        if (!tracking) return@Runnable
        longPressed = true
        view.isPressed = false
        view.parent?.requestDisallowInterceptTouchEvent(true)
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLong?.invoke(view, downX, downY)
    }
    private val pressRunnable = Runnable { if (tracking) view.isPressed = true }
    private val unpress = Runnable { view.isPressed = false }

    override fun onTouch(v: View, e: MotionEvent) = handle(e)

    fun handle(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelTimers()
                downX = e.x; downY = e.y
                if (isMenuClick(e)) {
                    // Right-click: the long-press menu now. Marked long-pressed so the release isn't a tap.
                    tracking = false; longPressed = true; dragging = false
                    onLong?.invoke(view, downX, downY)
                    return true
                }
                middle = isMiddleClick(e)
                if (middle) {
                    // Middle-click: fires on release like a click, and moving away cancels it; no menu, no drag.
                    tracking = onMiddle != null; longPressed = false; dragging = false; mouse = false
                    if (tracking) view.postDelayed(pressRunnable, ViewConfiguration.getTapTimeout().toLong())
                    return true
                }
                tracking = true; longPressed = false; dragging = false
                mouse = e.isFromSource(InputDevice.SOURCE_MOUSE) && mouseDrag && onDrag != null
                // A list around the view would take the mouse's drag as a scroll; the wheel scrolls instead.
                if (mouse) view.parent?.requestDisallowInterceptTouchEvent(true)
                view.drawableHotspotChanged(e.x, e.y)
                view.postDelayed(pressRunnable, ViewConfiguration.getTapTimeout().toLong())
                if (onLong != null || onDrag != null)
                    view.postDelayed(longRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val moved = abs(e.x - downX) > slop || abs(e.y - downY) > slop
                if (moved && !longPressed && mouse) {
                    cancelTimers(); longPressed = true; dragging = true
                    view.isPressed = false
                    onDrag?.invoke(view, downX, downY)
                    return true
                }
                if (moved && !longPressed) { stop(); return false }
                if (moved && longPressed && !dragging && onDrag != null) {
                    dragging = true
                    onDrag?.invoke(view, downX, downY)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val tap = tracking && !longPressed
                stop()
                if (tap) {
                    view.isPressed = true
                    view.postDelayed(unpress, 90)
                    if (middle) onMiddle?.invoke(view) else onTap?.invoke(view)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { stop(); return true }
        }
        return tracking
    }

    fun cancelLong() { view.removeCallbacks(longRunnable) }

    private fun stop() { tracking = false; cancelTimers(); view.isPressed = false }
    private fun cancelTimers() { view.removeCallbacks(longRunnable); view.removeCallbacks(pressRunnable); view.removeCallbacks(unpress) }
}

// ---------------------------------------------------------------- drag & drop plumbing

class DragPayload(
    val source: Int,
    val appKey: String?,
    val item: DesktopItem?,
    /** Touch point inside the shadow, and the shadow size, in px. */
    val offX: Float, val offY: Float, val w: Int, val h: Int,
    val sourceView: View?,
    /** FOLDER source: the desktop folder the app is being dragged out of. */
    val folder: DesktopItem? = null,
) {
    companion object { const val MENU = 0; const val DESKTOP = 1; const val TASKBAR = 2; const val FOLDER = 3 }
}

class BitmapShadow(private val bmp: Bitmap, private val size: Int) : View.DragShadowBuilder() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    override fun onProvideShadowMetrics(outSize: Point, outTouch: Point) { outSize.set(size, size); outTouch.set(size / 2, size / 2) }
    override fun onDrawShadow(c: Canvas) { c.drawBitmap(bmp, null, Rect(0, 0, size, size), paint) }
}

class ViewShadow(v: View, private val tx: Int, private val ty: Int) : View.DragShadowBuilder(v) {
    override fun onProvideShadowMetrics(outSize: Point, outTouch: Point) {
        val v = view
        outSize.set(maxOf(1, v.width), maxOf(1, v.height)); outTouch.set(tx, ty)
    }
}

// ---------------------------------------------------------------- battery

class BatteryView(ctx: Context, private val fg: Int, private val accent: Int) : View(ctx) {
    private var level = -1
    private var charging = false
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; textSize = ctx.dpf(13); typeface = MEDIUM }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bw = ctx.dpf(22); private val bh = ctx.dpf(11.5f); private val gap = ctx.dpf(6)
    private val rect = RectF()
    private val bolt = Path()

    fun update(l: Int, c: Boolean) {
        if (l == level && c == charging) return
        val relayout = text().length != "$l%".length
        level = l; charging = c
        contentDescription = "Battery $l%" + if (c) ", charging" else ""
        if (relayout) requestLayout()
        invalidate()
    }

    private fun text() = if (level < 0) "" else "$level%"

    override fun onMeasure(w: Int, h: Int) {
        val width = paddingLeft + bw + dpf(2.5f) + gap + tp.measureText(text()) + paddingRight
        setMeasuredDimension(width.toInt() + 1, resolveSize(dp(40), h))
    }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val l = paddingLeft.toFloat()
        p.style = Paint.Style.STROKE; p.strokeWidth = dpf(1.4f); p.color = withAlpha(fg, 0xD8)
        rect.set(l, cy - bh / 2, l + bw, cy + bh / 2)
        c.drawRoundRect(rect, dpf(3), dpf(3), p)
        p.style = Paint.Style.FILL
        rect.set(l + bw + dpf(0.8f), cy - dpf(2.5f), l + bw + dpf(2.5f), cy + dpf(2.5f))
        c.drawRoundRect(rect, dpf(1), dpf(1), p)
        val lv = level.coerceIn(0, 100)
        val inset = dpf(2.4f)
        p.color = when {
            charging -> accent
            lv <= 15 -> 0xFFE5534B.toInt()
            else -> fg
        }
        rect.set(l + inset, cy - bh / 2 + inset, l + inset + (bw - 2 * inset) * lv / 100f, cy + bh / 2 - inset)
        c.drawRoundRect(rect, dpf(1.5f), dpf(1.5f), p)
        var tx = l + bw + dpf(2.5f) + gap
        if (charging) {
            // small bolt between the battery and the percentage
            val bx = l + bw / 2f; val s = bh / 12f
            bolt.reset()
            bolt.moveTo(bx + 1 * s, cy - 7 * s); bolt.lineTo(bx - 3 * s, cy + 1 * s); bolt.lineTo(bx, cy + 1 * s)
            bolt.lineTo(bx - 1 * s, cy + 7 * s); bolt.lineTo(bx + 3 * s, cy - 1 * s); bolt.lineTo(bx, cy - 1 * s); bolt.close()
            p.color = fg; c.drawPath(bolt, p)
            tx += 0f
        }
        val fm = tp.fontMetrics
        c.drawText(text(), tx, cy - (fm.ascent + fm.descent) / 2, tp)
    }
}

/** 1px divider that never asks for width of its own, so it can't stretch a wrap-content menu. */
class Hairline(ctx: Context) : View(ctx) {
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(
        if (MeasureSpec.getMode(w) == MeasureSpec.EXACTLY) MeasureSpec.getSize(w) else 0,
        MeasureSpec.getSize(h).coerceAtMost(1).coerceAtLeast(1),
    )
}

/**
 * Wrap-width text with a few dp of slack: many Google Fonts draw slightly past their measured width,
 * and TextView trims anything outside its padding, which clipped the last letter of buttons.
 */
open class RoomyText(ctx: Context) : TextView(ctx) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        val mode = MeasureSpec.getMode(widthSpec)
        if (mode == MeasureSpec.EXACTLY) return
        val limit = if (mode == MeasureSpec.AT_MOST) MeasureSpec.getSize(widthSpec) else Int.MAX_VALUE
        setMeasuredDimension(minOf(measuredWidth + dp(4), limit), measuredHeight)
    }
}

/** A TextClock with the same spare room as [RoomyText]; keep it left-aligned so the room is on the side that overhangs. */
class RoomyClock(ctx: Context) : android.widget.TextClock(ctx) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        super.onMeasure(widthSpec, heightSpec)
        val mode = MeasureSpec.getMode(widthSpec)
        if (mode == MeasureSpec.EXACTLY) return
        val limit = if (mode == MeasureSpec.AT_MOST) MeasureSpec.getSize(widthSpec) else Int.MAX_VALUE
        setMeasuredDimension(minOf(measuredWidth + dp(3), limit), measuredHeight)
    }
}

fun TextView.label(color: Int, sizeSp: Float, bold: Boolean = false): TextView {
    setTextColor(color); textSize = sizeSp
    typeface = if (bold) Fonts.medium else Fonts.regular
    return this
}
