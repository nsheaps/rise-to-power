package com.nsheaps.risetopower

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.widget.TextView
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Shared colours and bitmaps for the menu screens. */
object MenuStyle {
    const val GOLD = 0xFFE2B04A.toInt()
    const val GOLD_LIGHT = 0xFFF6DC8E.toInt()
    const val GOLD_DARK = 0xFF8A5E18.toInt()
    const val PARCHMENT = 0xFFF5E9CF.toInt()
    const val PARCHMENT_DIM = 0xFFD8CBAE.toInt()
    const val EDGE = 0xFF1A100A.toInt()

    private var backdrop: Bitmap? = null
    private var emblem: Bitmap? = null

    /** The painted landscape behind every menu; decoded once per process. */
    fun backdrop(res: Resources): Bitmap? {
        backdrop?.let { return it }
        return decode(res, R.drawable.menu_backdrop).also { backdrop = it }
    }

    /** The laurel crest shown above the title. */
    fun emblem(res: Resources): Bitmap? {
        emblem?.let { return it }
        return decode(res, R.drawable.menu_emblem).also { emblem = it }
    }

    private fun decode(res: Resources, id: Int): Bitmap? = try {
        BitmapFactory.decodeResource(res, id, BitmapFactory.Options().apply { inScaled = false })
    } catch (_: Throwable) { null }
}

/**
 * The menu background: the landscape painting scaled to cover the screen, under a scrim that
 * keeps text readable. Falls back to a plain gradient if the bitmap can't be decoded.
 */
class BackdropDrawable(context: Context) : Drawable() {
    private val bitmap = MenuStyle.backdrop(context.resources)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val scrim = Paint()
    private val matrix = Matrix()

    override fun onBoundsChange(b: Rect) {
        val w = b.width().toFloat(); val h = b.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val bmp = bitmap
        if (bmp != null) {
            // Cover the bounds, anchored a little above centre so the citadel stays in view.
            val s = max(w / bmp.width, h / bmp.height)
            matrix.setScale(s, s)
            matrix.postTranslate(b.left + (w - bmp.width * s) / 2f, b.top + (h - bmp.height * s) * 0.35f)
        }
        scrim.shader = LinearGradient(
            0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(0x66000000, 0x33000000, 0x66000000, 0xC0000000.toInt()),
            floatArrayOf(0f, 0.3f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (bitmap != null) {
            c.drawBitmap(bitmap, matrix, paint)
        } else {
            paint.shader = null
            paint.color = 0xFF2A1F14.toInt()
            c.drawRect(b, paint)
        }
        c.drawRect(b, scrim)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/**
 * A carved wooden button with a gold frame and bracketed corners. Pressed buttons sink into
 * a darker, inset face. [chevrons] marks a button that cycles through values when tapped.
 */
class OrnateButtonDrawable(private val dp: Float, private val chevrons: Boolean = false) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private var pressed = false
    private var face: Shader? = null
    private var facePressed: Shader? = null

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val p = state.contains(android.R.attr.state_pressed)
        if (p == pressed) return false
        pressed = p
        invalidateSelf()
        return true
    }

    override fun onBoundsChange(b: Rect) {
        val top = b.top.toFloat(); val bottom = b.bottom.toFloat()
        face = LinearGradient(0f, top, 0f, bottom, intArrayOf(0xFF8A5F3A.toInt(), 0xFF5E3D22.toInt(), 0xFF46291A.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        facePressed = LinearGradient(0f, top, 0f, bottom, intArrayOf(0xFF2E1B0E.toInt(), 0xFF3E2814.toInt()), null, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        // Leave room for the drop shadow below.
        r.set(b.left + 1f * dp, b.top + 1f * dp, b.right - 1f * dp, b.bottom - 3f * dp)
        val rad = 6f * dp
        fill.shader = null
        fill.color = 0x70000000
        c.drawRoundRect(r.left, r.top + 2.5f * dp, r.right, r.bottom + 2.5f * dp, rad, rad, fill)
        // Face
        fill.shader = if (pressed) facePressed else face
        c.drawRoundRect(r, rad, rad, fill)
        fill.shader = null
        if (!pressed) {
            // Sheen along the top edge.
            fill.color = 0x2EFFFFFF
            c.drawRoundRect(r.left + 3f * dp, r.top + 2f * dp, r.right - 3f * dp, r.top + r.height() * 0.42f, rad, rad, fill)
        } else {
            fill.color = 0x50000000
            c.drawRoundRect(r.left + 2f * dp, r.top + 2f * dp, r.right - 2f * dp, r.top + 6f * dp, rad, rad, fill)
        }
        // Dark outer edge and gold frame.
        line.color = MenuStyle.EDGE; line.strokeWidth = 1.5f * dp
        c.drawRoundRect(r, rad, rad, line)
        val inset = 3f * dp
        line.color = if (pressed) MenuStyle.GOLD_DARK else MenuStyle.GOLD; line.strokeWidth = 1.5f * dp
        c.drawRoundRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, rad - 2f * dp, rad - 2f * dp, line)
        line.color = if (pressed) 0x40E2B04A else 0x66FFE8A8; line.strokeWidth = 1f * dp
        c.drawRoundRect(r.left + inset + 2f * dp, r.top + inset + 2f * dp, r.right - inset - 2f * dp, r.bottom - inset - 2f * dp, rad - 3f * dp, rad - 3f * dp, line)
        // Corner brackets.
        line.color = if (pressed) MenuStyle.GOLD_DARK else MenuStyle.GOLD_LIGHT; line.strokeWidth = 1.5f * dp
        val k = 6f * dp
        val x0 = r.left + inset; val x1 = r.right - inset; val y0 = r.top + inset; val y1 = r.bottom - inset
        c.drawLine(x0, y0 + k, x0 + k, y0, line); c.drawLine(x1 - k, y0, x1, y0 + k, line)
        c.drawLine(x0, y1 - k, x0 + k, y1, line); c.drawLine(x1 - k, y1, x1, y1 - k, line)
        if (chevrons) {
            line.color = if (pressed) MenuStyle.GOLD_DARK else 0xCCE2B04A.toInt(); line.strokeWidth = 1.8f * dp
            val cy = r.centerY(); val s = 4f * dp
            val lx = r.left + 13f * dp; val rx = r.right - 13f * dp
            c.drawLine(lx + s, cy - s, lx, cy, line); c.drawLine(lx, cy, lx + s, cy + s, line)
            c.drawLine(rx - s, cy - s, rx, cy, line); c.drawLine(rx, cy, rx - s, cy + s, line)
        }
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; line.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; line.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * A dark, framed panel that menu content sits on so it reads over the painting. [bar] makes
 * the flat variant used for the button strip pinned to the bottom of a screen.
 */
class PanelDrawable(private val dp: Float, private val bar: Boolean = false) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val r = RectF()
    private var face: Shader? = null

    override fun onBoundsChange(b: Rect) {
        face = LinearGradient(
            0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(0xE6281C12.toInt(), 0xEE1C130C.toInt(), 0xF2140D08.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (bar) {
            fill.shader = null
            fill.color = 0xE8120C07.toInt()
            c.drawRect(b, fill)
            line.strokeWidth = 1.5f * dp
            line.color = MenuStyle.GOLD
            c.drawLine(b.left.toFloat(), b.top + 1f * dp, b.right.toFloat(), b.top + 1f * dp, line)
            line.color = 0x66FFE8A8
            c.drawLine(b.left.toFloat(), b.top + 3.5f * dp, b.right.toFloat(), b.top + 3.5f * dp, line)
            return
        }
        r.set(b)
        val rad = 10f * dp
        fill.shader = null
        fill.color = 0x60000000
        c.drawRoundRect(r.left, r.top + 3f * dp, r.right, r.bottom + 3f * dp, rad, rad, fill)
        fill.shader = face
        c.drawRoundRect(r, rad, rad, fill)
        fill.shader = null
        line.color = MenuStyle.EDGE; line.strokeWidth = 1.5f * dp
        c.drawRoundRect(r, rad, rad, line)
        val inset = 4f * dp
        line.color = MenuStyle.GOLD; line.strokeWidth = 1.5f * dp
        c.drawRoundRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, rad - 3f * dp, rad - 3f * dp, line)
        line.color = 0x55E2B04A; line.strokeWidth = 1f * dp
        c.drawRoundRect(r.left + inset + 3f * dp, r.top + inset + 3f * dp, r.right - inset - 3f * dp, r.bottom - inset - 3f * dp, rad - 5f * dp, rad - 5f * dp, line)
        // Corner ornaments: a small diamond on each corner of the frame.
        fill.color = MenuStyle.GOLD
        val x0 = r.left + inset; val x1 = r.right - inset; val y0 = r.top + inset; val y1 = r.bottom - inset
        val k = 5f * dp
        diamond(c, x0 + k, y0 + k, 3.5f * dp); diamond(c, x1 - k, y0 + k, 3.5f * dp)
        diamond(c, x0 + k, y1 - k, 3.5f * dp); diamond(c, x1 - k, y1 - k, 3.5f * dp)
    }

    private val path = Path()
    private fun diamond(c: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx, cy - s); path.lineTo(cx + s, cy); path.lineTo(cx, cy + s); path.lineTo(cx - s, cy); path.close()
        c.drawPath(path, fill)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; line.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; line.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** A gold rule with a diamond at its centre, drawn under headings. */
class FlourishView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cy = h / 2f
        val cx = w / 2f
        val half = min(w / 2f - 8f * dp, 170f * dp)
        paint.shader = LinearGradient(cx - half, 0f, cx + half, 0f, intArrayOf(0x00E2B04A, MenuStyle.GOLD, MenuStyle.GOLD_LIGHT, MenuStyle.GOLD, 0x00E2B04A), floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 1f), Shader.TileMode.CLAMP)
        paint.strokeWidth = 1.5f * dp
        paint.style = Paint.Style.STROKE
        c.drawLine(cx - half, cy, cx - 12f * dp, cy, paint)
        c.drawLine(cx + 12f * dp, cy, cx + half, cy, paint)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = MenuStyle.GOLD_LIGHT
        val s = 4.5f * dp
        path.reset(); path.moveTo(cx, cy - s); path.lineTo(cx + s, cy); path.lineTo(cx, cy + s); path.lineTo(cx - s, cy); path.close()
        c.drawPath(path, paint)
        paint.color = MenuStyle.GOLD
        val t = 2f * dp
        path.reset(); path.moveTo(cx - 9f * dp, cy - t); path.lineTo(cx - 9f * dp + t, cy); path.lineTo(cx - 9f * dp, cy + t); path.lineTo(cx - 9f * dp - t, cy); path.close()
        c.drawPath(path, paint)
        path.reset(); path.moveTo(cx + 9f * dp, cy - t); path.lineTo(cx + 9f * dp + t, cy); path.lineTo(cx + 9f * dp, cy + t); path.lineTo(cx + 9f * dp - t, cy); path.close()
        c.drawPath(path, paint)
    }
}

/** Shows the crest bitmap scaled to fit, with a soft glow behind it. */
class EmblemView(context: Context) : View(context) {
    private val bitmap = MenuStyle.emblem(context.resources)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = min(w, h)
        val cx = w / 2f; val cy = h / 2f
        glow.shader = android.graphics.RadialGradient(cx, cy, s * 0.55f, intArrayOf(0x50FFD070, 0x00FFD070), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, s * 0.55f, glow)
        val bmp = bitmap ?: return
        dst.set(cx - s / 2f, cy - s / 2f, cx + s / 2f, cy + s / 2f)
        c.drawBitmap(bmp, null, dst, paint)
    }
}

/** Gold text that catches the light: a vertical gradient over the glyphs plus a dark shadow. */
class GoldTitleView(context: Context) : TextView(context) {
    private var shaderSize = -1f

    override fun onDraw(c: Canvas) {
        // Auto-sizing changes the text size after layout, so the gradient follows it.
        if (paint.textSize != shaderSize) {
            shaderSize = paint.textSize
            val top = paddingTop.toFloat()
            paint.shader = LinearGradient(0f, top, 0f, top + shaderSize, intArrayOf(MenuStyle.GOLD_LIGHT, 0xFFF0C860.toInt(), MenuStyle.GOLD, 0xFFB47E24.toInt()), floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
        }
        super.onDraw(c)
    }
}
