package dev.tabdesk.icons

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DiscretePathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import dev.tabdesk.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Painting a drawing (an alpha mask) in one of the styles, with the colour set's finish. */
internal object IconStyles {
    /** [mask] painted in [style] (an Icons style other than Original). [plain]: leave the finish off the tile. */
    fun paint(style: Int, mask: Bitmap, k: Colours, seed: Int, size: Int, plain: Boolean): Bitmap = when (style) {
        Icons.WATERCOLOUR -> watercolour(mask, k, seed, size)
        Icons.INKED -> inked(mask, k, seed, size)
        Icons.MINIMAL -> minimal(mask, size, k, seed)
        Icons.OUTLINE -> outline(mask, size, k, seed)
        Icons.PAPERCUT -> paperCut(mask, size, k, seed)
        Icons.LONGSHADOW -> longShadow(mask, size, k, seed, plain)
        Icons.BADGE -> badge(mask, size, k, seed)
        else -> themed(mask, size, k, seed, plain)
    }

    // ------------------------------------------------------------ painting

    private var tileSize = 0
    private var tilePath: Path? = null

    /**
     * A rounded square, inset like the apps' own icons. (Not the system icon mask: some phones report
     * a shape their own launcher doesn't use, e.g. a teardrop on Lenovo's ZUI, which draws rounded squares.)
     */
    @Synchronized fun tile(size: Int): Path {
        tilePath?.takeIf { tileSize == size }?.let { return it }
        val inset = size * 0.035f
        val r = (size - 2 * inset) * 0.24f
        return Path().apply { addRoundRect(RectF(inset, inset, size - inset, size - inset), r, r, Path.Direction.CW) }
            .also { tilePath = it; tileSize = size }
    }

    private fun drawMask(c: Canvas, mask: Bitmap, color: Int, alpha: Int, blur: Float = 0f, dy: Float = 0f,
                         dx: Float = 0f, blend: BlendMode? = null) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.color = withAlpha(color, alpha)
        if (blur > 0f) p.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        if (blend != null) p.blendMode = blend
        c.drawBitmap(mask, dx, dy, p)
    }

    /** The drawing itself, in [color], with the set's finish (Riso's second ink, Chalkboard's grain). */
    private fun drawing(c: Canvas, mask: Bitmap, color: Int, alpha: Int, k: Colours, size: Int, seed: Int) {
        when (k.finish) {
            Finish.RISO -> {
                drawMask(c, mask, k.extra, 205, dx = size * 0.042f, dy = size * 0.03f)
                drawMask(c, mask, color, alpha, blend = BlendMode.MULTIPLY)
            }
            Finish.CHALK -> {
                val layer = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val lc = Canvas(layer)
                drawMask(lc, mask, color, alpha)
                val rnd = Random(seed * 31 + 7)
                val out = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
                // Grain where the chalk skipped, and a few dry streaks running the way it was drawn.
                repeat(size * 4) {
                    out.color = withAlpha(Color.BLACK, 90 + rnd.nextInt(165))
                    lc.drawCircle(rnd.nextFloat() * size, rnd.nextFloat() * size, size * (0.003f + rnd.nextFloat() * 0.008f), out)
                }
                out.style = Paint.Style.STROKE; out.strokeCap = Paint.Cap.ROUND
                repeat(7) {
                    out.strokeWidth = size * (0.004f + rnd.nextFloat() * 0.006f); out.color = withAlpha(Color.BLACK, 110 + rnd.nextInt(90))
                    val x = rnd.nextFloat() * size; val y = rnd.nextFloat() * size; val len = size * (0.15f + rnd.nextFloat() * 0.25f)
                    lc.drawLine(x, y, x + len, y - len * 0.35f, out)
                }
                c.drawBitmap(layer, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            else -> drawMask(c, mask, color, alpha)
        }
    }

    /**
     * The set's finish on the tile or paper, under the drawing: Ukiyo-e's seal (at [sealX], [sealY], as
     * fractions of the icon) or Winter's snow, clipped to [area]. [bg] is the colour it sits on.
     */
    private fun backdrop(c: Canvas, k: Colours, size: Int, seed: Int, area: Path, bg: Int, sealX: Float = 0.765f, sealY: Float = 0.775f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (k.finish) {
            Finish.SEAL -> {
                val s = size * 0.1f; val cx = size * sealX; val cy = size * sealY
                c.save(); c.rotate(-5f, cx, cy)
                p.color = withAlpha(k.extra, 230)
                c.drawRoundRect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2, s * 0.14f, s * 0.14f, p)
                // Carved lines, as on a name stamp.
                p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.1f; p.color = withAlpha(bg, 210)
                c.drawRoundRect(cx - s * 0.3f, cy - s * 0.3f, cx + s * 0.3f, cy + s * 0.3f, s * 0.06f, s * 0.06f, p)
                c.drawLine(cx, cy - s * 0.3f, cx, cy + s * 0.3f, p)
                c.drawLine(cx - s * 0.3f, cy, cx, cy, p)
                c.restore()
            }
            Finish.SUN -> {
                c.save(); c.clipPath(area)
                p.shader = RadialGradient(size * 0.16f, size * 0.12f, size * 0.8f,
                    intArrayOf(withAlpha(k.extra, 175), withAlpha(k.extra, 55), 0), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
                p.shader = null
                // Glints: little four-pointed sparkles.
                val rnd = Random(seed * 13 + 5)
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = size * 0.012f
                repeat(3) {
                    val x = size * (0.15f + rnd.nextFloat() * 0.7f); val y = size * (0.6f + rnd.nextFloat() * 0.28f)
                    val r = size * (0.018f + rnd.nextFloat() * 0.02f)
                    p.color = withAlpha(Color.WHITE, 170 + rnd.nextInt(70))
                    c.drawLine(x - r, y, x + r, y, p); c.drawLine(x, y - r, x, y + r, p)
                }
                c.restore()
            }
            Finish.SNOW -> {
                val rnd = Random(seed * 17 + 3)
                // White flakes, or pale blue-grey ones on light paper where white wouldn't show.
                val flake = if (Color.luminance(bg) > 0.7f) mix(k.wash, k.ink, 0.15f) else k.extra
                c.save(); c.clipPath(area)
                repeat(size / 5) {
                    p.color = withAlpha(flake, 120 + rnd.nextInt(120))
                    c.drawCircle(rnd.nextFloat() * size, rnd.nextFloat() * size, size * (0.006f + rnd.nextFloat() * 0.013f), p)
                }
                c.restore()
            }
            Finish.GRID -> {
                c.save(); c.clipPath(area)
                val w = size.toFloat(); val hy = w * 0.66f; val cx = w / 2f
                // The sky glowing at the horizon, the ground a shade darker.
                p.shader = android.graphics.LinearGradient(0f, hy - w * 0.32f, 0f, hy,
                    0, withAlpha(k.wash, 110), Shader.TileMode.CLAMP)
                c.drawRect(0f, hy - w * 0.32f, w, hy, p); p.shader = null
                p.color = 0x38000000; c.drawRect(0f, hy, w, w, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.009f
                p.color = withAlpha(k.extra, 120)
                for (i in -7..7) c.drawLine(cx + i * w * 0.035f, hy, cx + i * w * 0.24f, w, p)
                for (j in 1..5) { val y = hy + (w - hy) * (j / 5f).let { it * it }; c.drawLine(0f, y, w, y, p) }
                p.strokeWidth = w * 0.014f; p.color = withAlpha(k.extra, 230)
                c.drawLine(0f, hy, w, hy, p)
                c.restore()
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------ the styles

    /** Like Android's own themed icons: a tile with the drawing on it. */
    private fun themed(mask: Bitmap, size: Int, k: Colours, seed: Int, plain: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val tile = tile(size)
        c.drawPath(tile, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = k.tile })
        if (!plain) backdrop(c, k, size, seed, tile, k.tile)
        drawing(c, mask, k.tileInk, 255, k, size, seed)
        return out
    }

    /** Just the drawing, no tile, with a shadow (or a glow, for dark drawings) so it reads on any wallpaper. */
    private fun minimal(mask: Bitmap, size: Int, k: Colours, seed: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        when {
            k.glow -> drawMask(c, mask, k.mark, 150, size * 0.06f)
            Color.luminance(k.mark) > 0.4f -> drawMask(c, mask, Color.BLACK, 110, size * 0.03f, size * 0.015f)
            else -> drawMask(c, mask, Color.WHITE, 150, size * 0.035f)
        }
        drawing(c, mask, k.mark, 255, k, size, seed)
        return out
    }

    /** [mask] grown by [r] all round (drawn again at offsets round two rings). */
    private fun dilate(mask: Bitmap, r: Float): Bitmap {
        val out = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ALPHA_8)
        val c = Canvas(out); val p = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(mask, 0f, 0f, p)
        for ((rad, n) in listOf(r to 24, r * 0.5f to 12)) for (i in 0 until n) {
            val a = 2.0 * PI * i / n
            c.drawBitmap(mask, (rad * cos(a)).toFloat(), (rad * sin(a)).toFloat(), p)
        }
        return out
    }

    /** True if [mask] is already drawn in thin lines (line-art pack icons), rather than filled shapes. */
    private fun lineArt(mask: Bitmap, size: Int): Boolean {
        val w = mask.width
        val px = IntArray(w * w); mask.getPixels(px, 0, w, 0, 0, w, w)
        fun on(i: Int) = (px[i] ushr 24) >= 128
        var area = 0; var edge = 0
        for (y in 1 until w - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            if (!on(i)) continue
            area++
            if (!on(i - 1) || !on(i + 1) || !on(i - w) || !on(i + w)) edge++
        }
        // Twice the area over the perimeter is about how thick its strokes are.
        return edge > 0 && 2f * area / edge < size * 0.04f
    }

    private fun cut(c: Canvas, mask: Bitmap) =
        c.drawBitmap(mask, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })

    /** Line art: every edge of the drawing traced in one even line, hollow inside, with no tile. */
    private fun outline(mask: Bitmap, size: Int, k: Colours, seed: Int): Bitmap {
        val line = if (lineArt(mask, size)) mask else dilate(mask, size * 0.03f).also { cut(Canvas(it), mask) }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        when {
            k.glow -> drawMask(c, line, k.mark, 170, size * 0.05f)
            Color.luminance(k.mark) > 0.4f -> drawMask(c, line, Color.BLACK, 130, size * 0.02f, size * 0.012f)
            else -> drawMask(c, line, Color.WHITE, 170, size * 0.025f)
        }
        drawing(c, line, k.mark, 255, k, size, seed)
        return out
    }

    /**
     * Layered paper: the drawing cut through a top sheet and, a little larger, through the sheet below
     * it, so you look down through a rim of the second colour to the third; each sheet casts a shadow.
     */
    private fun paperCut(mask: Bitmap, size: Int, k: Colours, seed: Int): Bitmap {
        val tile = tile(size)
        val paper = k.paper
        // The rim is the set's own colour. Below it, on light paper, a deeper shade of it; on dark paper the
        // set's brightest colour, as if lit from behind like a lightbox.
        val darkPaper = Color.luminance(paper) < 0.3f
        val deep = if (darkPaper) k.mark else mix(k.wash, k.ink, 0.5f)
        val mid = if (IconColours.contrast(k.wash, deep) >= 1.3f) k.wash else k.wash2?.takeIf { IconColours.contrast(it, deep) >= 1.3f } ?: mix(paper, deep, 0.5f)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // The bottom sheet, lit from the top left.
        p.shader = android.graphics.LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
            mix(deep, Color.WHITE, 0.12f), mix(deep, Color.BLACK, 0.15f), Shader.TileMode.CLAMP)
        c.drawPath(tile, p); p.shader = null
        fun sheet(colour: Int, hole: Bitmap, finish: Boolean): Bitmap {
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val sc = Canvas(b)
            sc.drawPath(tile, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour })
            if (finish) backdrop(sc, k, size, seed, tile, colour)
            // A little fibre in the paper.
            val rnd = Random(seed + colour)
            val f = Paint(Paint.ANTI_ALIAS_FLAG)
            sc.save(); sc.clipPath(tile)
            repeat(size * 2) {
                f.color = withAlpha(if (Color.luminance(colour) > 0.5f) Color.BLACK else Color.WHITE, 6 + rnd.nextInt(10))
                sc.drawCircle(rnd.nextFloat() * size, rnd.nextFloat() * size, size * (0.002f + rnd.nextFloat() * 0.004f), f)
            }
            sc.restore()
            cut(sc, hole)
            return b
        }
        fun lay(b: Bitmap) {
            c.save(); c.clipPath(tile)
            p.color = 0x66000000; p.maskFilter = BlurMaskFilter(size * 0.022f, BlurMaskFilter.Blur.NORMAL)
            c.drawBitmap(b.extractAlpha(), size * 0.018f, size * 0.024f, p); p.maskFilter = null
            c.restore()
            c.drawBitmap(b, 0f, 0f, null)
        }
        lay(sheet(mid, mask, false))
        lay(sheet(paper, dilate(mask, size * 0.05f), true))
        return out
    }

    /** Flat design: a plain tile and the drawing casting a long shadow down to the bottom right. */
    private fun longShadow(mask: Bitmap, size: Int, k: Colours, seed: Int, plain: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val tile = tile(size)
        c.drawPath(tile, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = k.tile })
        if (!plain) backdrop(c, k, size, seed, tile, k.tile)
        // On a near-black tile a dark shadow wouldn't show, so it's a faint trail of the drawing's colour.
        val shade = if (Color.luminance(k.tile) < 0.06f) mix(k.tile, k.tileInk, 0.2f) else mix(k.tile, Color.BLACK, 0.3f)
        val trail = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val tc = Canvas(trail)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (i in 1..size) tc.drawBitmap(mask, i.toFloat(), i.toFloat(), p)
        // Fading a little as it goes.
        tc.drawRect(0f, 0f, size.toFloat(), size.toFloat(), Paint().apply {
            shader = android.graphics.LinearGradient(size * 0.3f, size * 0.3f, size.toFloat(), size.toFloat(),
                0xFF000000.toInt(), 0x40000000, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        })
        c.save(); c.clipPath(tile); drawMask(c, trail, shade, 255); c.restore()
        drawing(c, mask, k.tileInk, 255, k, size, seed)
        return out
    }

    /** A round badge, like a button or a pin: a disc with a little light across it, an inner ring and the drawing. */
    private fun badge(mask: Bitmap, size: Int, k: Colours, seed: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val cx = size / 2f; val r = size * 0.46f
        val disc = Path().apply { addCircle(cx, cx, r, Path.Direction.CW) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = k.tile; c.drawPath(disc, p)
        p.shader = android.graphics.LinearGradient(0f, cx - r, 0f, cx + r, 0x22FFFFFF, 0x1C000000, Shader.TileMode.CLAMP)
        c.drawPath(disc, p); p.shader = null
        backdrop(c, k, size, seed, disc, k.tile, sealX = 0.7f, sealY = 0.74f)
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.014f; p.color = withAlpha(k.tileInk, 120)
        c.drawCircle(cx, cx, r * 0.86f, p)
        drawing(c, mask, k.tileInk, 255, k, size, seed)
        return out
    }

    /** A wobbly round shape, the same for the same seed. */
    private fun blob(rnd: Random, cx: Float, cy: Float, r: Float, wobble: Float): Path {
        val ks = intArrayOf(2, 3, 5, 7)
        val amp = FloatArray(ks.size) { wobble * (0.4f + rnd.nextFloat() * 0.6f) / (it + 1) }
        val ph = FloatArray(ks.size) { rnd.nextFloat() * 2f * PI.toFloat() }
        val path = Path()
        val n = 90
        for (i in 0..n) {
            val a = 2.0 * PI * i / n
            var k = 1.0
            for (j in ks.indices) k += amp[j] * sin(ks[j] * a + ph[j])
            val x = cx + (r * k * cos(a)).toFloat(); val y = cy + (r * k * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    /** Layered translucent washes on paper, pigment pooling at their edges, the drawing in ink with a little bleed. */
    private fun watercolour(mask: Bitmap, k: Colours, seed: Int, size: Int): Bitmap {
        val rnd = Random(seed)
        val paper = k.paper; val ink = k.ink
        val deep = mix(k.wash, ink, 0.35f)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val cx = size / 2f; val cy = size / 2f; val r = size * 0.43f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        val sheet = blob(rnd, cx, cy, r, 0.05f)
        p.color = paper; p.maskFilter = BlurMaskFilter(size * 0.01f, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(sheet, p)
        c.save(); c.clipPath(sheet)
        p.maskFilter = BlurMaskFilter(size * 0.025f, BlurMaskFilter.Blur.NORMAL)
        for (i in 0 until 3) {
            val w = blob(rnd, cx + (rnd.nextFloat() - 0.5f) * r * 0.35f, cy + (rnd.nextFloat() - 0.5f) * r * 0.35f,
                r * (0.8f - i * 0.12f), 0.14f)
            p.style = Paint.Style.FILL; p.color = withAlpha(if (i == 1) k.wash2 ?: k.wash else k.wash, 185 - i * 35)
            c.drawPath(w, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.02f; p.color = withAlpha(deep, 95)
            c.drawPath(w, p)
        }
        p.style = Paint.Style.FILL; p.maskFilter = null
        repeat(size * 3) {
            p.color = withAlpha(deep, 22 + rnd.nextInt(36))
            c.drawCircle(rnd.nextFloat() * size, rnd.nextFloat() * size, size * (0.003f + rnd.nextFloat() * 0.006f), p)
        }
        p.maskFilter = BlurMaskFilter(size * 0.04f, BlurMaskFilter.Blur.NORMAL); p.color = withAlpha(paper, 110)
        c.drawPath(blob(rnd, cx + (rnd.nextFloat() - 0.5f) * r * 0.8f, cy + (rnd.nextFloat() - 0.5f) * r * 0.8f, r * 0.28f, 0.2f), p)
        p.maskFilter = BlurMaskFilter(size * 0.012f, BlurMaskFilter.Blur.NORMAL)
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.03f; p.color = withAlpha(deep, 85)
        c.drawPath(sheet, p)
        c.restore()
        backdrop(c, k, size, seed, sheet, k.wash, sealX = 0.725f, sealY = 0.745f)

        drawMask(c, mask, ink, 80, size * 0.012f)
        drawing(c, mask, ink, 235, k, size, seed)
        return out
    }

    /** Ink on paper: a tile with grain and a hand-drawn frame (ink, then a line of the wash colour), the drawing in ink. */
    private fun inked(mask: Bitmap, k: Colours, seed: Int, size: Int): Bitmap {
        val rnd = Random(seed)
        val paper = k.paper; val ink = k.ink
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val tile = tile(size)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = paper
        c.drawPath(tile, p)
        c.save(); c.clipPath(tile)
        // Aged edges: brown on light paper, deeper shadow on dark paper.
        val edge = if (Color.luminance(paper) < 0.3f) 0x50000000 else 0x288A6A3A
        p.shader = RadialGradient(size / 2f, size / 2f, size * 0.62f, intArrayOf(0, 0, edge),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = null
        repeat(size * 4) {
            p.color = withAlpha(ink, 6 + rnd.nextInt(12))
            c.drawCircle(rnd.nextFloat() * size, rnd.nextFloat() * size, size * (0.002f + rnd.nextFloat() * 0.005f), p)
        }
        c.restore()

        val frame = Path(tile).apply { transform(Matrix().apply { setScale(0.86f, 0.86f, size / 2f, size / 2f) }) }
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        p.pathEffect = DiscretePathEffect(size * 0.07f, size * 0.007f)
        p.strokeWidth = size * 0.016f; p.color = withAlpha(ink, 190)
        c.drawPath(frame, p)
        p.strokeWidth = size * 0.011f; p.color = withAlpha(k.wash, 170)
        c.save(); c.rotate(1.2f + rnd.nextFloat(), size / 2f, size / 2f); c.drawPath(frame, p); c.restore()
        backdrop(c, k, size, seed, tile, paper, sealX = 0.745f, sealY = 0.755f)

        drawMask(c, mask, ink, 70, size * 0.012f)
        drawing(c, mask, ink, 240, k, size, seed)
        return out
    }
}
