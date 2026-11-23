package dev.tabdesk.icons

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import dev.tabdesk.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Effects, applied last to whatever the style, colours and pack drew. */
object IconEffects {

    const val NONE = "none"

    /** Effects, applied last to whatever the style, colours and pack made. Ids are stored and exported. */
    val ALL = listOf(
        NONE to "None", "glass" to "Glass", "8bit" to "8-bit", "16bit" to "16-bit", "crt" to "CRT", "neon" to "Neon", "neonglass" to "Neon glass",
        "halftone" to "Halftone", "pencil" to "Pencil sketch", "stitch" to "Cross-stitch", "mosaic" to "Mosaic",
        "emboss" to "Embossed", "sticker" to "Sticker",
    )

    fun isEffect(id: String?) = ALL.any { it.first == id }

    /**
     * Effects that take an app's solid background off what's drawn on it. A Themed or Long shadow tile is drawn without
     * its set's finish under these, or the grid, snow or sun would be left behind as stray marks.
     */
    internal val TILE_AS_BACKGROUND = setOf("glass", "neonglass", "pencil", "emboss")

    /** [b] with effect [id]; [seed] keeps random touches (mosaic tiles) the same for the same app. */
    fun render(b: Bitmap, id: String, seed: Int): Bitmap = try {
        when (id) {
            "8bit" -> pixels(b, 16, 3)
            "16bit" -> pixels(b, 32, 15)
            "stitch" -> stitch(b)
            "halftone" -> halftone(b)
            "mosaic" -> mosaic(b, seed)
            "sticker" -> sticker(b)
            "crt" -> crt(b)
            "neon" -> neon(b, plate = true)
            "neonglass" -> neonGlass(b)
            "pencil" -> pencil(b, seed)
            "emboss" -> emboss(b)
            "glass" -> glass(b)
            else -> b
        }
    } catch (e: Exception) { b }

    /** [b] cut into an n×n grid: each cell's average colour (opaque) and how covered it is (0–1). */
    private fun cells(b: Bitmap, n: Int): Pair<IntArray, FloatArray> {
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val col = IntArray(n * n); val cov = FloatArray(n * n)
        for (cy in 0 until n) for (cx in 0 until n) {
            val x0 = cx * w / n; val x1 = (cx + 1) * w / n; val y0 = cy * h / n; val y1 = (cy + 1) * h / n
            var a = 0L; var r = 0L; var g = 0L; var bl = 0L; var cnt = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val c = px[y * w + x]; val al = c ushr 24
                a += al; r += ((c shr 16) and 255) * al; g += ((c shr 8) and 255) * al; bl += (c and 255) * al; cnt++
            }
            val i = cy * n + cx
            cov[i] = if (cnt == 0) 0f else a / (255f * cnt)
            col[i] = if (a == 0L) 0 else Color.rgb((r / a).toInt(), (g / a).toInt(), (bl / a).toInt())
        }
        return col to cov
    }

    /** Up to [k] colours that best stand for [cols] (a few rounds of k-means, started from light to dark). */
    private fun palette(cols: List<Int>, k: Int): IntArray {
        val distinct = cols.distinct()
        if (distinct.size <= k) return distinct.toIntArray()
        val sorted = cols.sortedBy { Color.luminance(it) }
        val cent = IntArray(k) { sorted[(it * (sorted.size - 1)) / (k - 1).coerceAtLeast(1)] }
        val idx = IntArray(cols.size)
        repeat(8) {
            cols.forEachIndexed { i, c -> idx[i] = nearestIndex(cent, c) }
            for (j in 0 until k) {
                var n = 0; var r = 0; var g = 0; var b = 0
                cols.forEachIndexed { i, c -> if (idx[i] == j) { n++; r += Color.red(c); g += Color.green(c); b += Color.blue(c) } }
                if (n > 0) cent[j] = Color.rgb(r / n, g / n, b / n)
            }
        }
        return cent
    }

    private fun nearestIndex(cent: IntArray, c: Int): Int {
        var best = 0; var bd = Int.MAX_VALUE
        for (j in cent.indices) {
            val dr = Color.red(c) - Color.red(cent[j]); val dg = Color.green(c) - Color.green(cent[j]); val db = Color.blue(c) - Color.blue(cent[j])
            val d = 3 * dr * dr + 4 * dg * dg + 2 * db * db
            if (d < bd) { bd = d; best = j }
        }
        return best
    }

    /** Game-console sprites: an n×n grid of hard-edged pixels in at most [k] colours. */
    private fun pixels(b: Bitmap, n: Int, k: Int): Bitmap {
        val size = b.width
        val (col, cov) = cells(b, n)
        val on = cov.indices.filter { cov[it] > 0.5f }
        val pal = palette(on.map { col[it] }, k)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); val p = Paint()
        for (i in on) {
            val x = i % n; val y = i / n
            p.color = pal[nearestIndex(pal, col[i])]
            c.drawRect((x * size / n).toFloat(), (y * size / n).toFloat(), ((x + 1) * size / n).toFloat(), ((y + 1) * size / n).toFloat(), p)
        }
        return out
    }

    /** Embroidery: X stitches in a few thread colours on a square of cloth. */
    private fun stitch(b: Bitmap): Bitmap {
        val size = b.width; val n = 20
        val (col, cov) = cells(b, n)
        val on = cov.indices.filter { cov[it] > 0.45f }
        val threads = palette(on.map { col[it] }, 8)
        val light = on.isNotEmpty() && on.map { Color.luminance(col[it]) }.average() > 0.62
        val cloth = if (light) 0xFF2B3547.toInt() else 0xFFF1EADB.toInt()
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val tile = IconStyles.tile(size)
        p.color = cloth; c.drawPath(tile, p)
        val inner = size * 0.82f; val o = (size - inner) / 2f; val cell = inner / n
        // The weave: a hole at every corner of the grid.
        p.color = withAlpha(mix(cloth, Color.BLACK, 0.35f), 110)
        c.save(); c.clipPath(tile)
        for (y in 0..n) for (x in 0..n) c.drawCircle(o + x * cell, o + y * cell, cell * 0.1f, p)
        c.restore()
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = cell * 0.3f
        val m = cell * 0.14f
        for (i in on) {
            val x0 = o + (i % n) * cell; val y0 = o + (i / n) * cell
            val t = threads[nearestIndex(threads, col[i])]
            p.color = mix(t, Color.BLACK, 0.18f); c.drawLine(x0 + m, y0 + m, x0 + cell - m, y0 + cell - m, p)
            p.color = t; c.drawLine(x0 + cell - m, y0 + m, x0 + m, y0 + cell - m, p)
        }
        return out
    }

    /** Comic print: flat colours, a dot screen in a deeper shade, and a dark ink outline. */
    private fun halftone(b: Bitmap): Bitmap {
        val size = b.width
        val w = size; val px = IntArray(w * w); b.getPixels(px, 0, w, 0, 0, w, w)
        val (col, cov) = cells(b, 32)
        val pal = palette(cov.indices.filter { cov[it] > 0.4f }.map { col[it] }, 5)
        if (pal.isEmpty()) return b
        val lighter = IntArray(pal.size) { mix(pal[it], Color.WHITE, 0.22f) }
        val flat = IntArray(w * w)
        val which = IntArray(w * w)
        for (i in px.indices) {
            val a = px[i] ushr 24
            if (a < 8) continue
            val j = nearestIndex(pal, px[i] or (0xFF shl 24)); which[i] = j
            flat[i] = (a shl 24) or (lighter[j] and 0xFFFFFF)
        }
        val base = Bitmap.createBitmap(flat, w, w, Bitmap.Config.ARGB_8888)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // Outline: the shape drawn dark a little way out in every direction.
        val shape = base.extractAlpha()
        val ink = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = 0xFF1A1A1E.toInt() }
        val r = size * 0.022f
        for (k in 0 until 12) { val a = k * PI / 6; c.drawBitmap(shape, (r * cos(a)).toFloat(), (r * sin(a)).toFloat(), ink) }
        c.drawBitmap(base, 0f, 0f, null)
        // Dots on a 45° screen, bigger where the colour is darker.
        val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.SRC_ATOP }
        val d = size / 14f
        val steps = (size * 1.5f / d).toInt() + 2
        for (gy in -steps..steps) for (gx in -steps..steps) {
            val x = size / 2f + (gx - gy) * d * 0.7071f; val y = size / 2f + (gx + gy) * d * 0.7071f
            val xi = x.toInt(); val yi = y.toInt()
            if (xi !in 0 until w || yi !in 0 until w || (px[yi * w + xi] ushr 24) < 128) continue
            val colr = pal[which[yi * w + xi]]
            dots.color = mix(colr, Color.BLACK, 0.3f)
            c.drawCircle(x, y, d * 0.5f * (0.3f + 0.55f * (1f - Color.luminance(colr))), dots)
        }
        return out
    }

    /** Little square tiles, each a touch uneven, set in grout. */
    private fun mosaic(b: Bitmap, seed: Int): Bitmap {
        val size = b.width; val n = 9
        val (col, cov) = cells(b, n)
        val rnd = Random(seed * 7 + 11)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cell = size / n.toFloat()
        // Grout under every tile, dark or pale to suit the tiles' colours.
        val on = cov.indices.filter { cov[it] > 0.4f }
        val light = on.isNotEmpty() && on.map { Color.luminance(col[it]) }.average() < 0.35
        p.color = if (light) 0xFFD9D3C4.toInt() else 0xFF2A2826.toInt()
        for (i in on) {
            val x0 = (i % n) * cell; val y0 = (i / n) * cell
            c.drawRect(x0 - 0.5f, y0 - 0.5f, x0 + cell + 0.5f, y0 + cell + 0.5f, p)
        }
        for (i in cov.indices) {
            if (cov[i] <= 0.4f) { rnd.nextFloat(); continue }
            val x0 = (i % n) * cell; val y0 = (i / n) * cell
            val shade = (rnd.nextFloat() - 0.5f) * 0.12f
            p.color = if (shade > 0) mix(col[i], Color.WHITE, shade) else mix(col[i], Color.BLACK, -shade)
            fun gap() = cell * (0.04f + rnd.nextFloat() * 0.05f)
            val l = x0 + gap(); val t = y0 + gap(); val r = x0 + cell - gap(); val bt = y0 + cell - gap()
            c.save(); c.rotate((rnd.nextFloat() - 0.5f) * 7f, (l + r) / 2f, (t + bt) / 2f)
            c.drawRoundRect(l, t, r, bt, cell * 0.14f, cell * 0.14f, p)
            c.restore()
        }
        return out
    }

    /** A die-cut sticker: a thick border (white, or dark for light drawings) and a soft shadow. */
    private fun sticker(b: Bitmap): Bitmap {
        val size = b.width
        val small = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val s = size * 0.8f; val o = (size - s) / 2f
        Canvas(small).drawBitmap(b, null, RectF(o, o, o + s, o + s), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val shape = small.extractAlpha()
        // A white border, or a dark one for a light drawing that white wouldn't set off.
        val (col, cov) = cells(small, 16)
        val inked = cov.indices.filter { cov[it] > 0.5f }
        val border = if (inked.isNotEmpty() && inked.map { Color.luminance(col[it]) }.average() > 0.72) 0xFF24272E.toInt() else Color.WHITE
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = size * 0.05f
        fun ring(paint: Paint, dy: Float) {
            for (k in 0 until 16) { val a = k * PI / 8; c.drawBitmap(shape, (r * cos(a)).toFloat(), (r * sin(a)).toFloat() + dy, paint) }
            c.drawBitmap(shape, 0f, dy, paint)
        }
        ring(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x50000000; maskFilter = BlurMaskFilter(size * 0.03f, BlurMaskFilter.Blur.NORMAL) }, size * 0.025f)
        ring(Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = border }, 0f)
        c.drawBitmap(small, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** An old monitor: phosphor glow, the colours a little out of line, and scanlines. */
    private fun crt(b: Bitmap): Bitmap {
        val size = b.width
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val soft = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(b, (size / 8).coerceAtLeast(2), (size / 8).coerceAtLeast(2), true), size, size, true)
        c.drawBitmap(soft, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 150 })
        val shift = (size * 0.018f).coerceAtLeast(1f)
        fun channel(r: Float, g: Float, bl: Float) = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(r, 0f, 0f, 0f, 0f, 0f, g, 0f, 0f, 0f, 0f, 0f, bl, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
            blendMode = BlendMode.PLUS
        }
        val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        lc.drawBitmap(b, -shift, 0f, channel(1f, 0f, 0f))
        lc.drawBitmap(b, 0f, 0f, channel(0f, 1f, 0f))
        lc.drawBitmap(b, shift, 0f, channel(0f, 0f, 1f))
        c.drawBitmap(layer, 0f, 0f, null)
        val lines = Paint().apply { color = 0x6A000000; blendMode = BlendMode.SRC_ATOP }
        val pitch = (size / 32f).coerceAtLeast(2f)
        var y = 0f
        while (y < size) { c.drawRect(0f, y, size.toFloat(), y + pitch * 0.45f, lines); y += pitch }
        return out
    }

    // ---- helpers for the drawn effects

    /** [b] shrunk to [frac] of its size, centred (room for a plate, paper or glass around it). */
    private fun inset(b: Bitmap, frac: Float): Bitmap {
        val size = b.width
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val s = size * frac; val o = (size - s) / 2f
        Canvas(out).drawBitmap(b, null, RectF(o, o, o + s, o + s), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** A cheap soft blur: shrink by [f], then grow back in two smooth steps. */
    private fun soften(b: Bitmap, f: Int): Bitmap {
        val s = (b.width / f).coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(b, s, s, true)
        val mid = Bitmap.createScaledBitmap(small, s * 2, s * 2, true)
        return Bitmap.createScaledBitmap(mid, b.width, b.height, true)
    }

    private fun lum(c: Int) = (0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)) / 255f

    /** How strongly each pixel is an edge (0–1): where the shape ends or its colours change (Sobel). */
    private fun edgeMap(px: IntArray, w: Int): FloatArray {
        val a = FloatArray(w * w) { (px[it] ushr 24) / 255f }
        val l = FloatArray(w * w) { lum(px[it]) * a[it] }
        val out = FloatArray(w * w)
        for (y in 1 until w - 1) for (x in 1 until w - 1) {
            fun sob(v: FloatArray): Float {
                val i = y * w + x
                val gx = (v[i - w + 1] + 2 * v[i + 1] + v[i + w + 1]) - (v[i - w - 1] + 2 * v[i - 1] + v[i + w - 1])
                val gy = (v[i + w - 1] + 2 * v[i + w] + v[i + w + 1]) - (v[i - w - 1] + 2 * v[i - w] + v[i - w + 1])
                return kotlin.math.sqrt(gx * gx + gy * gy) / 4f
            }
            out[y * w + x] = (sob(a) + sob(l) * 1.3f).coerceAtMost(1f)
        }
        return out
    }

    /** Each pixel's colour, borrowed from an opaque neighbour where it's see-through (edges sit half outside). */
    private fun nearColour(px: IntArray, w: Int, i: Int): Int {
        if ((px[i] ushr 24) > 128) return px[i]
        val x = i % w; val y = i / w
        for (dy in -2..2) for (dx in -2..2) {
            val xx = x + dx; val yy = y + dy
            if (xx in 0 until w && yy in 0 until w && (px[yy * w + xx] ushr 24) > 128) return px[yy * w + xx]
        }
        return px[i]
    }

    /** A rounded-tile mask the size of the icon (for effects that light or clip a plate). */
    private fun tileAlpha(size: Int): FloatArray {
        val m = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(m).drawPath(IconStyles.tile(size), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val px = IntArray(size * size); m.getPixels(px, 0, size, 0, 0, size, size)
        return FloatArray(size * size) { (px[it] ushr 24) / 255f }
    }

    /** How different two colours look (0–255, green weighted most, as the eye does). */
    private fun colourDist(a: Int, b: Int): Float {
        val dr = Color.red(a) - Color.red(b); val dg = Color.green(a) - Color.green(b); val db = Color.blue(a) - Color.blue(b)
        return kotlin.math.sqrt((3f * dr * dr + 4f * dg * dg + 2f * db * db) / 9f)
    }

    /**
     * The colour of a solid background the drawing sits on (most apps' own icons, a Themed tile), or
     * null if there isn't one: sampled round the edges, which must all be opaque and nearly the same.
     */
    private fun backing(b: Bitmap): Int? {
        val s = b.width
        val at = floatArrayOf(0.3f, 0.5f, 0.7f)
        val pts = at.flatMap { v -> listOf(v to 0.09f, v to 0.91f, 0.09f to v, 0.91f to v) }
        val cols = pts.map { (x, y) -> b.getPixel((x * s).toInt().coerceIn(0, s - 1), (y * s).toInt().coerceIn(0, s - 1)) }
            .filter { (it ushr 24) > 220 }
        if (cols.size < pts.size - 1) return null
        val avg = Color.rgb(cols.sumOf { Color.red(it) } / cols.size, cols.sumOf { Color.green(it) } / cols.size, cols.sumOf { Color.blue(it) } / cols.size)
        return avg.takeIf { cols.count { colourDist(it, avg) < 45f } >= cols.size - 1 }
    }

    /** [b] with its background colour [bg] taken out, leaving what's drawn on it. */
    private fun withoutBacking(b: Bitmap, bg: Int): Bitmap {
        val w = b.width
        val px = IntArray(w * w); b.getPixels(px, 0, w, 0, 0, w, w)
        for (i in px.indices) {
            val k = ((colourDist(px[i], bg) - 22f) / 40f).coerceIn(0f, 1f)
            px[i] = (((px[i] ushr 24) * k).toInt() shl 24) or (px[i] and 0xFFFFFF)
        }
        return Bitmap.createBitmap(px, w, w, Bitmap.Config.ARGB_8888)
    }

    // ---- the drawn effects

    /**
     * Neon: the outlines as glowing tubes in the app's own colours. With [plate], on a dark backplate
     * like a sign on a wall; without, just the tubes and their glow (the background left out), for glass.
     */
    private fun neon(b: Bitmap, plate: Boolean): Bitmap {
        val size = b.width
        val bg = if (plate) null else backing(b)
        // Without its background an app's drawing is smaller, so it's shrunk less.
        val src = if (plate) inset(b, 0.78f) else inset(bg?.let { withoutBacking(b, it) } ?: b, if (bg != null) 0.86f else 0.72f)
        val px = IntArray(size * size); src.getPixels(px, 0, size, 0, 0, size, size)
        val e = edgeMap(px, size)
        val hsv = FloatArray(3)
        val tubes = IntArray(size * size)
        for (i in px.indices) {
            val v = e[i]
            if (v < 0.22f) continue
            Color.colorToHSV(nearColour(px, size, i), hsv)
            // Greys and whites become a cool cyan tube; everything else is pushed to full brightness.
            if (hsv[1] < 0.18f) { hsv[0] = 188f; hsv[1] = 0.7f } else hsv[1] = max(hsv[1], 0.75f)
            hsv[2] = 1f
            val a = ((v - 0.22f) * 2.6f).coerceIn(0f, 1f)
            tubes[i] = Color.HSVToColor((a * 255).toInt(), hsv)
        }
        val tube = Bitmap.createBitmap(tubes, size, size, Bitmap.Config.ARGB_8888)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (plate) {
            p.color = 0xEE0E1016.toInt(); c.drawPath(IconStyles.tile(size), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.012f; p.color = 0x22FFFFFF; c.drawPath(IconStyles.tile(size), p)
            p.style = Paint.Style.FILL; p.color = Color.WHITE
        }
        val wide = soften(tube, 7); val near = soften(tube, 3)
        p.blendMode = BlendMode.PLUS
        c.drawBitmap(wide, 0f, 0f, p); c.drawBitmap(wide, 0f, 0f, p)
        c.drawBitmap(near, 0f, 0f, p)
        p.blendMode = null
        // The tube itself: a little thicker, and nearly white at its core.
        val core = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(
                0.5f, 0f, 0f, 0f, 128f, 0f, 0.5f, 0f, 0f, 128f, 0f, 0f, 0.5f, 0f, 128f, 0f, 0f, 0f, 1f, 0f))
        }
        val d = size * 0.006f
        c.drawBitmap(tube, -d, 0f, core); c.drawBitmap(tube, d, 0f, core); c.drawBitmap(tube, 0f, -d, core); c.drawBitmap(tube, 0f, d, core)
        return out
    }

    /**
     * A pencil sketch on a paper card: graphite outlines, coloured-pencil hatching (crossed where darker)
     * and a light wash of colour. An app's background becomes a tint on the paper, not more hatching.
     */
    private fun pencil(b: Bitmap, seed: Int): Bitmap {
        val size = b.width
        val bg = backing(b)
        val src = inset(bg?.let { withoutBacking(b, it) } ?: b, 0.8f)
        val px = IntArray(size * size); src.getPixels(px, 0, size, 0, 0, size, size)
        val e = edgeMap(px, size)
        val t = tileAlpha(size)
        val rnd = Random(seed * 5 + 1)
        val graphite = 0xFF2E2E34.toInt()
        // Paper tinted with the background's hue (pale, however dark the background was).
        val paper = bg?.let { val v = FloatArray(3); Color.colorToHSV(it, v); Color.HSVToColor(floatArrayOf(v[0], min(v[1], 0.5f) * 0.22f, 0.96f)) }
            ?: 0xFFF5F2EA.toInt()
        val sp = (size / 20f).coerceAtLeast(3f)
        val wob = FloatArray(size) { (rnd.nextFloat() - 0.5f) * 0.9f }
        val hsv = FloatArray(3)
        val out = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val i = y * size + x
            if (t[i] <= 0f) continue
            val a = (px[i] ushr 24) / 255f
            val col = px[i] or (0xFF shl 24)
            Color.colorToHSV(col, hsv)
            // How much pencil goes here: more for darker or stronger colours.
            val ink = a * max(1f - lum(col), hsv[1] * hsv[2] * 0.85f)
            val h1 = if (((x + y + wob[y]) % sp + sp) % sp < 1.15f) 1f else 0f
            val h2 = if (((x - y + wob[x]) % sp + sp) % sp < 1.15f) 1f else 0f
            val hatch = (((ink - 0.12f) * 2.5f).coerceIn(0f, 1f) * h1 + ((ink - 0.62f) * 3f).coerceIn(0f, 1f) * h2).coerceAtMost(1f)
            val line = ((e[i] - 0.14f) * 2f).coerceIn(0f, 1f)
            var c = mix(paper, col, a * 0.14f)
            if (hatch > 0f) c = mix(c, mix(col, graphite, 0.2f), hatch * 0.85f)
            if (line > 0f) c = mix(c, graphite, line * 0.9f)
            // Paper grain.
            if (rnd.nextInt(9) == 0) c = mix(c, 0xFFB8B2A6.toInt(), 0.18f)
            out[i] = ((t[i] * 255).toInt() shl 24) or (c and 0xFFFFFF)
        }
        return Bitmap.createBitmap(out, size, size, Bitmap.Config.ARGB_8888)
    }

    /**
     * Embossed: the app pressed up out of a plate, lit from the top left. The plate is the app's
     * background colour if it has one (and only what's drawn on it is raised), else its main colour.
     */
    private fun emboss(b: Bitmap): Bitmap {
        val size = b.width
        val bg = backing(b)
        val src = inset(bg?.let { withoutBacking(b, it) } ?: b, 0.8f)
        val px = IntArray(size * size); src.getPixels(px, 0, size, 0, 0, size, size)
        val t = tileAlpha(size)
        val (col, cov) = cells(b, 12)
        // Softened a little towards grey, like a real material.
        val plate = mix(bg ?: dominant(col, cov) ?: 0xFF8A8A8A.toInt(), 0xFF8C8C8C.toInt(), 0.2f)
        // Height: the plate's own bevel, the drawing raised, its darker parts a touch higher.
        var h = FloatArray(size * size) { i ->
            val a = (px[i] ushr 24) / 255f
            t[i] * 0.35f + a * (0.5f + 0.3f * (1f - lum(px[i])))
        }
        repeat(2) {
            val n = FloatArray(size * size)
            for (y in 1 until size - 1) for (x in 1 until size - 1) {
                val i = y * size + x
                n[i] = (h[i - size - 1] + h[i - size] + h[i - size + 1] + h[i - 1] + h[i] + h[i + 1] + h[i + size - 1] + h[i + size] + h[i + size + 1]) / 9f
            }
            h = n
        }
        val d = (size / 64).coerceAtLeast(1)
        val out = IntArray(size * size)
        for (y in d until size - d) for (x in d until size - d) {
            val i = y * size + x
            if (t[i] <= 0f) continue
            // Rising towards the bottom right means facing the light at the top left.
            val s = (h[i + d * size + d] - h[i - d * size - d]) * 3.2f
            val c = if (s > 0) mix(plate, Color.WHITE, s.coerceAtMost(0.75f)) else mix(plate, Color.BLACK, (-s).coerceAtMost(0.7f))
            out[i] = ((t[i] * 255).toInt() shl 24) or (c and 0xFFFFFF)
        }
        return Bitmap.createBitmap(out, size, size, Bitmap.Config.ARGB_8888)
    }

    /** The colour covering most of [col]'s covered cells, roughly (grouped by a coarse hue/lightness key). */
    private fun dominant(col: IntArray, cov: FloatArray): Int? {
        val groups = HashMap<Int, MutableList<Int>>()
        val hsv = FloatArray(3)
        for (i in cov.indices) {
            if (cov[i] <= 0.5f) continue
            Color.colorToHSV(col[i], hsv)
            val k = if (hsv[1] < 0.2f) 1000 + (hsv[2] * 4).toInt() else (hsv[0] / 30f).toInt() * 10 + (hsv[2] * 3).toInt()
            groups.getOrPut(k) { ArrayList() } += col[i]
        }
        val best = groups.values.maxByOrNull { it.size } ?: return null
        return Color.rgb(best.sumOf { Color.red(it) } / best.size, best.sumOf { Color.green(it) } / best.size, best.sumOf { Color.blue(it) } / best.size)
    }

    /**
     * Glass, in the spirit of Apple's Liquid Glass: the app's background becomes the glass itself, tinted
     * its colour, with what was drawn on it floating inside. Apps without one get clear frosted glass
     * faintly tinted with their colours (smoky for light drawings, which wouldn't show on clear glass).
     */
    private fun glass(b: Bitmap): Bitmap {
        val size = b.width
        val bg = backing(b)
        val fg = bg?.let { withoutBacking(b, it) } ?: b
        val (col, cov) = cells(fg, 16)
        val inked = cov.indices.filter { cov[it] > 0.4f }
        val lightArt = inked.isNotEmpty() && inked.map { Color.luminance(col[it]) }.average() > 0.7
        val avg = if (inked.isEmpty()) Color.WHITE else Color.rgb(inked.sumOf { Color.red(col[it]) } / inked.size,
            inked.sumOf { Color.green(col[it]) } / inked.size, inked.sumOf { Color.blue(col[it]) } / inked.size)
        val (top, bottom) = when {
            bg != null -> withAlpha(bg, 175) to withAlpha(bg, 115)
            lightArt -> withAlpha(mix(0xFF202430.toInt(), avg, 0.2f), 0x78) to withAlpha(mix(0xFF101318.toInt(), avg, 0.12f), 0x48)
            else -> withAlpha(mix(Color.WHITE, avg, 0.3f), 0x70) to withAlpha(mix(Color.WHITE, avg, 0.45f), 0x30)
        }
        return pane(size, inset(fg, if (bg != null) 0.8f else 0.64f), top, bottom, true)
    }

    /** Neon glass: the neon tubes sealed in a pane of dark violet smoked glass. */
    private fun neonGlass(b: Bitmap): Bitmap =
        pane(b.width, neon(b, plate = false), 0xC0180E30.toInt(), 0xA80A0716.toInt(), false)

    /** A rounded glass pane shaded [top] to [bottom], with [art] inside. */
    private fun pane(size: Int, art: Bitmap, top: Int, bottom: Int, artShadow: Boolean): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val tile = IconStyles.tile(size)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // A soft shadow lifts the pane off the wallpaper.
        p.color = 0x30000000; p.maskFilter = BlurMaskFilter(size * 0.035f, BlurMaskFilter.Blur.NORMAL)
        c.save(); c.translate(0f, size * 0.025f); c.drawPath(tile, p); c.restore()
        p.maskFilter = null
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, size.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        c.drawPath(tile, p); p.shader = null
        if (artShadow) {
            val shadow = art.extractAlpha()
            p.color = 0x40000000; p.maskFilter = BlurMaskFilter(size * 0.025f, BlurMaskFilter.Blur.NORMAL)
            c.drawBitmap(shadow, 0f, size * 0.02f, p); p.maskFilter = null
        }
        c.drawBitmap(art, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        // A sweep of glare across the top left, over everything (it's on the glass).
        c.save(); c.clipPath(tile)
        p.shader = android.graphics.LinearGradient(0f, 0f, size * 0.7f, size * 0.7f,
            intArrayOf(0x48FFFFFF, 0x0CFFFFFF, 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p); p.shader = null
        c.restore()
        // The rim: bright where the light catches the top left, fading round to the bottom right.
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.022f
        p.shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(0xEEFFFFFF.toInt(), 0x30FFFFFF, 0x70FFFFFF), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(tile, p); p.shader = null
        p.strokeWidth = size * 0.008f; p.color = 0x26000000
        c.save(); c.scale(0.965f, 0.965f, size / 2f, size / 2f); c.drawPath(tile, p); c.restore()
        return out
    }

}
