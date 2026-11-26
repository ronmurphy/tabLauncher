package dev.tabdesk.people

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/**
 * One Pixel Person on its agent's bar, as DroidACP draws them: a whole number of screen pixels per sprite pixel, the
 * largest that fits the view's height, with no smoothing.
 */
class PersonView(ctx: Context) : View(ctx) {
    private var sprite: Bitmap? = null
    private var bar: Bitmap? = null
    private var key = ""
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val dst = Rect()

    /** Draws [look] in [pose]; nothing is drawn again if neither changed. */
    fun show(look: Look, pose: String) {
        val k = "$look $pose"
        if (k == key) return
        key = k
        val people = PixelPeople.get(context)
        sprite = people.render(pose, look).toBitmap()
        bar = people.bar(look.agent).toBitmap()
        invalidate()
    }

    private fun Sprite.toBitmap() = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

    /** Sprite pixels from the top of the person to the bottom of its bar. */
    private val rows = PixelPeople.SIZE + PixelPeople.BAR_HEIGHT

    private fun unit(h: Int) = (h / rows).coerceAtLeast(1)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val h = MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(unit(h) * PixelPeople.SIZE, h)
    }

    override fun onDraw(canvas: Canvas) {
        val s = sprite ?: return
        val u = unit(height)
        val top = (height - rows * u) / 2
        dst.set(0, top, PixelPeople.SIZE * u, top + PixelPeople.SIZE * u)
        canvas.drawBitmap(s, null, dst, paint)
        val b = bar ?: return
        val left = (PixelPeople.SIZE - PixelPeople.BAR_WIDTH) / 2 * u
        dst.set(left, dst.bottom, left + PixelPeople.BAR_WIDTH * u, dst.bottom + PixelPeople.BAR_HEIGHT * u)
        canvas.drawBitmap(b, null, dst, paint)
    }
}
