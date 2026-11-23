package dev.tabdesk.icons

import android.graphics.Bitmap
import android.graphics.Color
import dev.tabdesk.*
import kotlin.math.max
import kotlin.math.min

/**
 * The colours for redrawn icons. Each style uses the parts it needs: Themed a tile and the drawing
 * on it; Watercolour paper, washes and ink; Inked paper, ink and a frame in the wash colour;
 * Minimal just the mark (the drawing on its own).
 */
class Colours(
    val id: String, val name: String,
    val tile: Int, val tileInk: Int,
    val paper: Int, val ink: Int, val wash: Int, val wash2: Int?,
    val mark: Int, val glow: Boolean = false,
    /** A finishing touch some sets add, with [extra] as its colour where it needs one. */
    val finish: Finish = Finish.PLAIN, val extra: Int = 0,
)

/**
 * RISO: the drawing printed twice, a second ink slightly out of line (overlaps darken, as with real
 * risograph inks). CHALK: grain and dry streaks through the drawing. SEAL: a small stamp in the
 * corner, as on woodblock prints. SNOW: flakes over the tile or paper. SUN: warm light from a corner
 * and a few glints, like sun on water. GRID: an 80s neon grid running to a glowing horizon.
 */
enum class Finish { PLAIN, RISO, CHALK, SEAL, SNOW, SUN, GRID }

/** The colour sets, and the colours an icon is drawn in for a given Icons.Spec. */
object IconColours {

    const val ACCENT = "accent"; const val APP = "app"; const val AUTO = "auto"

    private fun c(v: Long) = v.toInt()

    private val WHITE_ON_BLACK = Colours("white", "White on black", c(0xFF1A1B1E), c(0xFFF4F4F4),
        c(0xFF1C1C1F), c(0xFFF2F2F2), c(0xFF6E6E75), null, c(0xFFFFFFFF))
    private val BLACK_ON_WHITE = Colours("black", "Black on white", c(0xFFF5F5F5), c(0xFF151515),
        c(0xFFFBFBF9), c(0xFF151515), c(0xFF8E8E8E), null, c(0xFF151515))

    /**
     * Named sets, each picked to look right in every style. Some pairings make their own art:
     * Black on white + Watercolour is ink-wash painting, Sakura + Watercolour cherry blossom,
     * Blueprint + Inked an architect's drawing, Terminal + Minimal an old green screen.
     */
    private val SETS = listOf(
        WHITE_ON_BLACK, BLACK_ON_WHITE,
        //       id           name         tile             drawing on tile   paper            ink              wash             second wash      mark
        Colours("sakura", "Sakura", c(0xFFF6C4D1), c(0xFF3B2630), c(0xFFFCF6F1), c(0xFF2A2326), c(0xFFEE9DB5), c(0xFFF7CFDA), c(0xFFF5AFC3)),
        Colours("summer", "Summer", c(0xFF12A5B4), c(0xFFFFF7DC), c(0xFFFDF4E0), c(0xFF0F3B4C), c(0xFF2DC2C9), c(0xFFFFD54A), c(0xFFFFD84D),
            finish = Finish.SUN, extra = c(0xFFFFE89A)),
        Colours("autumn", "Autumn", c(0xFF6B3A22), c(0xFFF2C46A), c(0xFFF6ECD9), c(0xFF3E2414), c(0xFFD39B2A), c(0xFFA8432A), c(0xFFE9A94B)),
        Colours("winter", "Winter", c(0xFF2C4A66), c(0xFFEAF4FB), c(0xFFF2F7FA), c(0xFF203547), c(0xFF8DB8D8), c(0xFFCFE2EE), c(0xFFEAF5FC),
            finish = Finish.SNOW, extra = c(0xFFFFFFFF)),
        Colours("ukiyoe", "Ukiyo-e", c(0xFF1F3A60), c(0xFFF1E6CC), c(0xFFF2E7CF), c(0xFF1B2433), c(0xFF33689A), c(0xFFA9C3D8), c(0xFFE2654A),
            finish = Finish.SEAL, extra = c(0xFFD2442D)),
        Colours("matcha", "Matcha", c(0xFFAFC788), c(0xFF25321A), c(0xFFF6F4E6), c(0xFF2A321F), c(0xFF8BB054), c(0xFFCADDA6), c(0xFFB9D58E)),
        Colours("indigo", "Indigo", c(0xFF263C6E), c(0xFFEEF2FA), c(0xFFF5F2EA), c(0xFF1C2A4E), c(0xFF4A68AE), c(0xFF8FA6D6), c(0xFFAFC2EE)),
        Colours("maple", "Maple", c(0xFFE07A3E), c(0xFFFFF3E3), c(0xFFFBF3E5), c(0xFF3A2317), c(0xFFDB5A2C), c(0xFFF0A23C), c(0xFFF2934F)),
        Colours("sepia", "Sepia", c(0xFFD8C29E), c(0xFF3A2918), c(0xFFF0E4CC), c(0xFF3D2B1B), c(0xFFAE8757), null, c(0xFFE3CFAB)),
        Colours("riso", "Riso", c(0xFFFBE3EC), c(0xFF0F98A8), c(0xFFF6F1E6), c(0xFF0F98A8), c(0xFFFF7DB5), c(0xFF7FD0CF), c(0xFF19A9BA),
            finish = Finish.RISO, extra = c(0xFFFF5AA8)),
        Colours("chalk", "Chalkboard", c(0xFF2E4A3B), c(0xFFF0EFE6), c(0xFF2B4437), c(0xFFECEBE2), c(0xFF557A66), c(0xFF6E8E7C), c(0xFFF2F1E8),
            finish = Finish.CHALK),
        Colours("blueprint", "Blueprint", c(0xFF1F4D8A), c(0xFFE9F2FF), c(0xFF1E4B87), c(0xFFEAF3FF), c(0xFF5B8ED2), null, c(0xFFCFE3FF)),
        Colours("terminal", "Terminal", c(0xFF0B110C), c(0xFF5DFF7E), c(0xFF0A0F0B), c(0xFF62F58B), c(0xFF1E7A36), null, c(0xFF6BFF8E), glow = true),
        Colours("synthwave", "Synthwave", c(0xFF2A1052), c(0xFF5CF4FF), c(0xFF1E0B3A), c(0xFF6AF6FF), c(0xFFFF3CAC), c(0xFF8B4DFF), c(0xFFFF5BD6),
            glow = true, finish = Finish.GRID, extra = c(0xFFFF4FC8)),
        Colours("gold", "Gold", c(0xFF16150F), c(0xFFDDB662), c(0xFF18160F), c(0xFFE0BA69), c(0xFF8E6C2B), null, c(0xFFE4C171)),
    )

    /** Ids and names, in the order Customize shows them. */
    val CHOICES: List<Pair<String, String>> by lazy {
        listOf(ACCENT to "Accent", APP to "App colours", AUTO to "Auto") + SETS.map { it.id to it.name }
    }

    fun isColours(id: String) = CHOICES.any { it.first == id }

    fun resolve(spec: Icons.Spec, onLight: Boolean, own: Bitmap): Colours = when (spec.colours) {
        APP -> appColours(own, spec)
        AUTO -> if (onLight) BLACK_ON_WHITE else WHITE_ON_BLACK
        ACCENT -> accentColours(spec, onLight)
        else -> SETS.firstOrNull { it.id == spec.colours } ?: accentColours(spec, onLight)
    }

    private fun hsv(c: Int) = FloatArray(3).also { Color.colorToHSV(c, it) }
    private fun tone(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s, v))
    fun contrast(a: Int, b: Int): Float {
        val la = Color.luminance(a) + 0.05f; val lb = Color.luminance(b) + 0.05f
        return max(la, lb) / min(la, lb)
    }

    /** Your accent colour, lighter or deeper to suit the theme (and, for Minimal, what's behind it). */
    private fun accentColours(spec: Icons.Spec, onLight: Boolean): Colours {
        val a = spec.accent; val h = hsv(a)[0]; val d = spec.dark
        return Colours(ACCENT, "Accent",
            tile = if (d) mix(c(0xFF1C1D22), a, 0.32f) else mix(Color.WHITE, a, 0.2f),
            tileInk = if (d) mix(Color.WHITE, a, 0.42f) else mix(c(0xFF0E0F12), a, 0.6f),
            paper = mix(c(0xFFF6F0E4), a, 0.1f), ink = tone(h + 10f, 0.8f, 0.24f),
            wash = tone(h - 6f, 0.66f, 0.93f), wash2 = null,
            mark = if (onLight) mix(c(0xFF101114), a, 0.72f) else mix(Color.WHITE, a, 0.22f))
    }

    /** Each app's own main colour and, where it has one, its second colour. */
    private fun appColours(own: Bitmap, spec: Icons.Spec): Colours {
        val (found, second) = mainColours(own)
        val m = hsv(found ?: spec.accent); val h = m[0]
        val tile = tone(h, m[1].coerceIn(0.45f, 0.8f), m[2].coerceIn(0.62f, 0.9f))
        val dark = tone(h, 0.6f, 0.18f)
        val tileInk = second?.takeIf { contrast(it, tile) >= 2.8f }
            ?: if (contrast(Color.WHITE, tile) >= contrast(dark, tile)) Color.WHITE else dark
        return Colours(APP, "App colours", tile, tileInk,
            paper = tone(h, 0.14f, 0.99f), ink = tone(h + 10f, 0.85f, 0.27f),
            wash = tone(h - 6f, 0.66f, 0.93f), wash2 = second?.let { tone(hsv(it)[0], 0.6f, 0.92f) },
            mark = tone(h, m[1].coerceAtLeast(0.55f), m[2].coerceAtLeast(0.85f)))
    }

    /** The most colourful hue in an icon, and a second clearly different one if it has one. Null for grey icons. */
    private fun mainColours(b: Bitmap): Pair<Int?, Int?> {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val n = 24
        val weight = FloatArray(n); val rs = FloatArray(n); val gs = FloatArray(n); val bs = FloatArray(n)
        val hsv = FloatArray(3)
        var i = 0; var count = 0
        while (i < px.size) {
            val c = px[i]; i += 3
            if (c ushr 24 < 200) continue
            count++
            Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.25f || hsv[2] < 0.25f) continue
            val wt = hsv[1] * hsv[2]
            val k = (hsv[0] / 15f).toInt().coerceIn(0, n - 1)
            weight[k] += wt; rs[k] += Color.red(c) * wt; gs[k] += Color.green(c) * wt; bs[k] += Color.blue(c) * wt
        }
        fun colour(k: Int) = Color.rgb((rs[k] / weight[k]).toInt(), (gs[k] / weight[k]).toInt(), (bs[k] / weight[k]).toInt())
        val best = weight.indices.maxByOrNull { weight[it] } ?: return null to null
        if (count == 0 || weight[best] < count * 0.04f) return null to null
        // The second colour must be at least 45° of hue away and a real part of the icon.
        val next = weight.indices.filter { k -> min((k - best + n) % n, (best - k + n) % n) >= 3 && weight[k] >= weight[best] * 0.15f }
            .maxByOrNull { weight[it] }
        return colour(best) to next?.let { colour(it) }
    }

}
