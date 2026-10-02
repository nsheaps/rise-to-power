package com.nsheaps.risetopower

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.nsheaps.risetopower.core.ResourceType
import kotlin.random.Random

/**
 * Draws the HUD's chrome: framed panels, bevelled buttons, ribbons and resource icons.
 * Everything that can be allocated up front is, so drawing a frame allocates nothing.
 */
class HudSkin(res: Resources, private val d: Float) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val grain = Paint()
    private val m = Matrix()
    private val path = Path()
    private val tmp = RectF()

    // Gradients span y = 0..1 and are stretched over each rect with a local matrix.
    private val panelGrad = vertical(0xFF2E2217.toInt(), 0xFF1E1610.toInt(), 0xFF140E08.toInt())
    private val buttonGrad = vertical(0xFF8A6240.toInt(), 0xFF5E4026.toInt(), 0xFF462B18.toInt())
    private val pressedGrad = vertical(0xFF2E1C0E.toInt(), 0xFF4A3018.toInt(), 0xFF5A3C20.toInt())
    private val activeGrad = vertical(0xFF6E8A3E.toInt(), 0xFF4A6428.toInt(), 0xFF364C1C.toInt())
    private val disabledGrad = vertical(0xFF48403A.toInt(), 0xFF3A322C.toInt(), 0xFF2E2622.toInt())
    private val barGrad = vertical(0xFF3A2C1E.toInt(), 0xFF241A10.toInt(), 0xFF160E08.toInt())

    /** Icons indexed by [ResourceType.ordinal]; null when a bitmap could not be decoded. */
    private val icons: Array<Bitmap?> = arrayOf(
        decode(res, R.drawable.hud_food), decode(res, R.drawable.hud_wood), decode(res, R.drawable.hud_gold), decode(res, R.drawable.hud_stone),
    )

    init {
        // A faint speckle tiled over panels so they read as leather rather than flat paint.
        val size = 64
        val px = IntArray(size * size)
        val rnd = Random(7)
        for (i in px.indices) {
            val v = rnd.nextInt(256)
            px[i] = if (v < 40) 0x14000000 else if (v > 236) 0x10FFFFFF else 0
        }
        val tex = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        grain.shader = BitmapShader(tex, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun vertical(vararg colors: Int) = LinearGradient(0f, 0f, 0f, 1f, colors, null, Shader.TileMode.CLAMP)

    private fun decode(res: Resources, id: Int): Bitmap? = try {
        BitmapFactory.decodeResource(res, id, BitmapFactory.Options().apply { inScaled = false })
    } catch (_: Throwable) { null }

    /** Points [shader] at [r] vertically and makes it the fill. */
    private fun useGradient(shader: Shader, r: RectF, alpha: Int = 255) {
        m.setScale(1f, r.height())
        m.postTranslate(0f, r.top)
        shader.setLocalMatrix(m)
        fill.shader = shader
        fill.alpha = alpha
    }

    private fun plain(color: Int) {
        fill.shader = null
        fill.color = color
    }

    // ------------------------------------------------------------------ panels

    /** A framed panel: shadow, leather face, dark edge, gold frame and corner brackets. */
    fun panel(c: Canvas, r: RectF, alpha: Int = 0xE0) {
        val rad = 7f * d
        plain(0x50000000)
        c.drawRoundRect(r.left, r.top + 2f * d, r.right, r.bottom + 2.5f * d, rad, rad, fill)
        useGradient(panelGrad, r, alpha)
        c.drawRoundRect(r, rad, rad, fill)
        fill.shader = null
        fill.alpha = 255
        c.drawRoundRect(r, rad, rad, grain)
        line.color = 0xFF120B06.toInt(); line.strokeWidth = 1.5f * d
        c.drawRoundRect(r, rad, rad, line)
        val inset = 3f * d
        line.color = 0xFFC79A4C.toInt(); line.strokeWidth = 1.3f * d
        c.drawRoundRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, rad - 2f * d, rad - 2f * d, line)
        line.color = 0x3CFFE8A8; line.strokeWidth = 1f * d
        c.drawRoundRect(r.left + inset + 2f * d, r.top + inset + 2f * d, r.right - inset - 2f * d, r.bottom - inset - 2f * d, rad - 3f * d, rad - 3f * d, line)
        brackets(c, r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, 7f * d, 0xFFE8C16A.toInt())
    }

    private fun brackets(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, k: Float, color: Int) {
        line.color = color; line.strokeWidth = 1.5f * d
        c.drawLine(x0, y0 + k, x0 + k, y0, line); c.drawLine(x1 - k, y0, x1, y0 + k, line)
        c.drawLine(x0, y1 - k, x0 + k, y1, line); c.drawLine(x1 - k, y1, x1, y1 - k, line)
    }

    /** The strip across the top of the screen. */
    fun topBar(c: Canvas, w: Float, h: Float) {
        tmp.set(0f, 0f, w, h)
        useGradient(barGrad, tmp, 0xF2)
        c.drawRect(tmp, fill)
        fill.shader = null; fill.alpha = 255
        c.drawRect(tmp, grain)
        plain(0x2AFFFFFF)
        c.drawRect(0f, 0f, w, 1f * d, fill)
        plain(0xFFC79A4C.toInt())
        c.drawRect(0f, h - 2.5f * d, w, h - 1f * d, fill)
        plain(0xFF0E0804.toInt())
        c.drawRect(0f, h - 1f * d, w, h, fill)
        plain(0x40000000)
        c.drawRect(0f, h, w, h + 3f * d, fill)
    }

    /** A soft dark pill behind a group of readouts in the top bar. */
    fun chip(c: Canvas, r: RectF) {
        plain(0x48000000)
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fill)
        line.color = 0x30FFFFFF; line.strokeWidth = 1f * d
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, line)
    }

    // ------------------------------------------------------------------ buttons

    /**
     * A bevelled button face. [ornate] adds corner brackets for the large menu buttons.
     */
    fun button(c: Canvas, r: RectF, enabled: Boolean, pressed: Boolean, active: Boolean, ornate: Boolean = false) {
        val rad = 5f * d
        plain(0x60000000)
        c.drawRoundRect(r.left, r.top + 1.5f * d, r.right, r.bottom + 2f * d, rad, rad, fill)
        val grad = when {
            !enabled -> disabledGrad
            pressed -> pressedGrad
            active -> activeGrad
            else -> buttonGrad
        }
        useGradient(grad, r)
        c.drawRoundRect(r, rad, rad, fill)
        fill.shader = null
        if (!pressed) {
            plain(if (enabled) 0x30FFFFFF else 0x14FFFFFF)
            c.drawRoundRect(r.left + 2f * d, r.top + 1.5f * d, r.right - 2f * d, r.top + r.height() * 0.45f, rad, rad, fill)
        } else {
            plain(0x50000000)
            c.drawRoundRect(r.left + 1.5f * d, r.top + 1.5f * d, r.right - 1.5f * d, r.top + 5f * d, rad, rad, fill)
        }
        line.color = 0xFF140C06.toInt(); line.strokeWidth = 1.2f * d
        c.drawRoundRect(r, rad, rad, line)
        line.color = when {
            !enabled -> 0xFF6A5A48.toInt()
            active -> 0xFFB8E878.toInt()
            pressed -> 0xFFF0D48A.toInt()
            else -> 0xFFD0A65A.toInt()
        }
        line.strokeWidth = 1.3f * d
        val inset = 2f * d
        c.drawRoundRect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset, rad - 1.5f * d, rad - 1.5f * d, line)
        if (ornate) brackets(c, r.left + inset + 1f * d, r.top + inset + 1f * d, r.right - inset - 1f * d, r.bottom - inset - 1f * d, 6f * d, if (enabled) 0xFFF0D48A.toInt() else 0xFF6A5A48.toInt())
    }

    // ------------------------------------------------------------------ ribbons & rules

    /** A banner with notched ends, for mode hints and network status. */
    fun ribbon(c: Canvas, r: RectF, accent: Int) {
        val notch = r.height() * 0.42f
        path.reset()
        path.moveTo(r.left, r.top); path.lineTo(r.right, r.top)
        path.lineTo(r.right - notch, r.centerY()); path.lineTo(r.right, r.bottom)
        path.lineTo(r.left, r.bottom); path.lineTo(r.left + notch, r.centerY()); path.close()
        c.save()
        c.translate(0f, 2f * d)
        plain(0x60000000)
        c.drawPath(path, fill)
        c.restore()
        useGradient(panelGrad, r, 0xE8)
        c.drawPath(path, fill)
        fill.shader = null; fill.alpha = 255
        line.color = accent; line.strokeWidth = 1.3f * d
        c.drawPath(path, line)
        // Thin inner thread in the accent colour.
        line.color = (accent and 0x00FFFFFF) or 0x50000000; line.strokeWidth = 1f * d
        c.drawLine(r.left + notch + 4f * d, r.top + 3f * d, r.right - notch - 4f * d, r.top + 3f * d, line)
        c.drawLine(r.left + notch + 4f * d, r.bottom - 3f * d, r.right - notch - 4f * d, r.bottom - 3f * d, line)
    }

    /** A gold rule with a diamond at its centre, drawn under modal titles. */
    fun rule(c: Canvas, cx: Float, y: Float, half: Float) {
        line.color = 0xFFC79A4C.toInt(); line.strokeWidth = 1.3f * d
        c.drawLine(cx - half, y, cx - 10f * d, y, line)
        c.drawLine(cx + 10f * d, y, cx + half, y, line)
        plain(0xFFF0D48A.toInt())
        val s = 4f * d
        path.reset(); path.moveTo(cx, y - s); path.lineTo(cx + s, y); path.lineTo(cx, y + s); path.lineTo(cx - s, y); path.close()
        c.drawPath(path, fill)
    }

    /** A dim backdrop for an icon or portrait. */
    fun well(c: Canvas, r: RectF, rad: Float) {
        plain(0x66000000)
        c.drawRoundRect(r, rad, rad, fill)
        line.color = 0x28FFFFFF; line.strokeWidth = 1f * d
        c.drawRoundRect(r.left + 0.5f * d, r.top + 0.5f * d, r.right - 0.5f * d, r.bottom - 0.5f * d, rad, rad, line)
    }

    // ------------------------------------------------------------------ icons

    /** Draws a resource icon centred on ([cx], [cy]) with half-size [rad]. */
    fun resIcon(c: Canvas, r: ResourceType, cx: Float, cy: Float, rad: Float) {
        val icon = icons[r.ordinal]
        if (icon != null) {
            val s = rad * 1.25f
            tmp.set(cx - s, cy - s, cx + s, cy + s)
            c.drawBitmap(icon, null, tmp, bmp)
            return
        }
        val col = when (r) {
            ResourceType.FOOD -> 0xFFD0453A.toInt()
            ResourceType.WOOD -> 0xFF9A6A3A.toInt()
            ResourceType.GOLD -> 0xFFF2C640.toInt()
            ResourceType.STONE -> 0xFFB4B4B0.toInt()
        }
        plain(0xFF1A120A.toInt())
        c.drawCircle(cx, cy, rad + 1.5f * d, fill)
        plain(col)
        c.drawCircle(cx, cy, rad, fill)
        plain(0x55FFFFFF)
        c.drawCircle(cx - rad * 0.3f, cy - rad * 0.3f, rad * 0.35f, fill)
    }
}
