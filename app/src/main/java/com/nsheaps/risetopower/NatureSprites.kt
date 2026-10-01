package com.nsheaps.risetopower

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.nsheaps.risetopower.TerrainLayers.Companion.shade
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Pre-rendered, gradient-shaded sprites for trees, bushes, mines and game animals. Drawing them
 * with gradients every frame would be too slow for hundreds of trees, so each variant is rendered
 * once per zoom bucket and blitted. Sprites are anchored at the entity's feet.
 */
class NatureSprites {
    enum class Kind(val w: Float, val h: Float, val ax: Float, val ay: Float) {
        CONIFER(44f, 80f, 22f, 70f),
        LEAFY(48f, 76f, 24f, 68f),
        BERRIES(40f, 34f, 20f, 26f),
        GOLD(76f, 52f, 38f, 34f),
        STONE(76f, 52f, 38f, 34f),
        DEER(40f, 34f, 18f, 28f),
    }

    private class Sprite(val bmp: Bitmap, val scale: Float)

    private val cache = HashMap<Int, Sprite>()
    private var bucket = -1
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val dst = RectF()

    /**
     * Draws sprite [kind] (with [variant] and [level], e.g. how much is left) with its anchor at
     * (x, y) at scale [s]. [mirror] flips it horizontally.
     */
    fun draw(c: Canvas, kind: Kind, variant: Int, level: Int, x: Float, y: Float, s: Float, mirror: Boolean = false) {
        val b = (s * 8f).roundToInt().coerceAtLeast(1)
        if (b != bucket) { clear(); bucket = b }
        val key = (kind.ordinal * 64 + variant) * 16 + level
        val sp = cache.getOrPut(key) { render(kind, variant, level, b / 8f) }
        val k = s / sp.scale
        val w = sp.bmp.width * k; val h = sp.bmp.height * k
        val l = x - kind.ax * s - PAD * s
        val t = y - kind.ay * s - PAD * s
        if (mirror) {
            c.save()
            c.scale(-1f, 1f, x, y)
        }
        dst.set(l, t, l + w, t + h)
        c.drawBitmap(sp.bmp, null, dst, blit)
        if (mirror) c.restore()
    }

    /** True when the drawn sprite has an opaque pixel (not just shadow) at screen point (px, py). */
    fun hit(kind: Kind, variant: Int, level: Int, x: Float, y: Float, s: Float, mirror: Boolean, px: Float, py: Float): Boolean {
        val b = (s * 8f).roundToInt().coerceAtLeast(1)
        if (b != bucket) { clear(); bucket = b }
        val key = (kind.ordinal * 64 + variant) * 16 + level
        val sp = cache.getOrPut(key) { render(kind, variant, level, b / 8f) }
        val k = s / sp.scale
        val qx = if (mirror) 2 * x - px else px
        val bx = ((qx - (x - kind.ax * s - PAD * s)) / k).toInt()
        val by = ((py - (y - kind.ay * s - PAD * s)) / k).toInt()
        if (bx < 0 || by < 0 || bx >= sp.bmp.width || by >= sp.bmp.height) return false
        return (sp.bmp.getPixel(bx, by) ushr 24) >= 128
    }

    fun clear() {
        cache.values.forEach { it.bmp.recycle() }
        cache.clear()
    }

    private fun render(kind: Kind, variant: Int, level: Int, s: Float): Sprite {
        val bw = ((kind.w + PAD * 2) * s).toInt().coerceAtLeast(2)
        val bh = ((kind.h + PAD * 2) * s).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Anchor in bitmap coordinates.
        val x = (kind.ax + PAD) * s
        val y = (kind.ay + PAD) * s
        val rnd = Random(variant * 31 + kind.ordinal * 977 + level)
        when (kind) {
            Kind.CONIFER -> conifer(c, x, y, s, variant, rnd)
            Kind.LEAFY -> leafy(c, x, y, s, variant, rnd)
            Kind.BERRIES -> berries(c, x, y, s, level, rnd)
            Kind.GOLD -> rocks(c, x, y, s, level, true, rnd)
            Kind.STONE -> rocks(c, x, y, s, level, false, rnd)
            Kind.DEER -> deer(c, x, y, s, variant)
        }
        return Sprite(bmp, s)
    }

    // ------------------------------------------------------------------ helpers

    private fun shadow(c: Canvas, x: Float, y: Float, rx: Float, ry: Float) {
        fill.color = 0xFF000000.toInt(); fill.shader = RadialGradient(x, y, max(rx, 1f), intArrayOf(0x66000000, 0x33000000, 0x00000000), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.save()
        c.scale(1f, ry / rx, x, y)
        c.drawCircle(x, y, rx, fill)
        c.restore()
        fill.shader = null
    }

    /** A sphere-like blob lit from the upper left. */
    private fun blob(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        fill.color = 0xFF000000.toInt(); fill.shader = RadialGradient(x - r * 0.35f, y - r * 0.4f, r * 1.35f,
            intArrayOf(shade(color, 1.35f), color, shade(color, 0.62f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, fill)
        fill.shader = null
    }

    private fun trunk(c: Canvas, x: Float, y: Float, s: Float, h: Float, w: Float) {
        val bark = 0xFF5E3E24.toInt()
        fill.color = 0xFF000000.toInt(); fill.shader = LinearGradient(x - w, 0f, x + w, 0f, intArrayOf(shade(bark, 1.25f), bark, shade(bark, 0.6f)), null, Shader.TileMode.CLAMP)
        path.reset()
        path.moveTo(x - w * 1.6f, y)
        path.quadTo(x - w, y - 2f * s, x - w * 0.8f, y - h)
        path.lineTo(x + w * 0.8f, y - h)
        path.quadTo(x + w, y - 2f * s, x + w * 1.6f, y)
        path.close()
        c.drawPath(path, fill)
        fill.shader = null
    }

    // ------------------------------------------------------------------ sprites

    private fun conifer(c: Canvas, x: Float, y: Float, s: Float, variant: Int, rnd: Random) {
        val greens = intArrayOf(0xFF24502A.toInt(), 0xFF2C5A2C.toInt(), 0xFF1F4728.toInt())
        val g = greens[variant % greens.size]
        val size = 0.9f + (variant % 5) * 0.06f
        shadow(c, x + 6f * s, y, 16f * s * size, 6f * s * size)
        trunk(c, x, y, s, 14f * s, 2.2f * s)
        val tiers = 4
        for (k in 0 until tiers) {
            val base = y - (10f + k * 12f) * s * size
            val w = (17f - k * 3.4f) * s * size
            val h = (22f - k * 1.5f) * s * size
            // Apex, then a jagged lower edge from right to left.
            path.reset()
            path.moveTo(x, base - h)
            val teeth = 6
            for (i in teeth downTo -teeth) {
                val f = i / teeth.toFloat()
                val sag = (1f - abs(f)) * 3f * s
                path.lineTo(x + w * f, base + sag + (if (i % 2 == 0) 0f else 3f * s))
            }
            path.close()
            val tier = shade(g, 0.9f + k * 0.07f)
            fill.color = 0xFF000000.toInt(); fill.shader = LinearGradient(x - w, base - h, x + w, base, intArrayOf(shade(tier, 1.45f), tier, shade(tier, 0.55f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(path, fill)
            fill.shader = null
            stroke.color = 0x55000000; stroke.strokeWidth = max(0.8f, 0.8f * s)
            c.drawPath(path, stroke)
        }
        // Needle highlights.
        fill.color = 0x30E8FFD0
        repeat(10) {
            val ty = y - (14f + rnd.nextFloat() * 40f) * s * size
            val tx = x - rnd.nextFloat() * 8f * s
            c.drawCircle(tx, ty, 1.1f * s, fill)
        }
    }

    private fun leafy(c: Canvas, x: Float, y: Float, s: Float, variant: Int, rnd: Random) {
        val greens = intArrayOf(0xFF3E7230.toInt(), 0xFF4A7A2C.toInt(), 0xFF35652E.toInt(), 0xFF5A7E2A.toInt())
        val g = greens[variant % greens.size]
        val size = 0.9f + (variant % 4) * 0.07f
        shadow(c, x + 7f * s, y, 18f * s * size, 7f * s * size)
        trunk(c, x, y, s, 24f * s * size, 2.6f * s)
        // Branches into the crown.
        stroke.color = 0xFF4E321E.toInt(); stroke.strokeWidth = max(1f, 1.6f * s)
        c.drawLine(x, y - 18f * s * size, x - 7f * s, y - 28f * s * size, stroke)
        c.drawLine(x, y - 20f * s * size, x + 6f * s, y - 30f * s * size, stroke)
        val cy = y - 38f * s * size
        val blobs = listOf(
            floatArrayOf(-9f, 6f, 10f), floatArrayOf(9f, 5f, 10f), floatArrayOf(0f, 8f, 10f),
            floatArrayOf(-11f, -4f, 10f), floatArrayOf(10f, -5f, 10f), floatArrayOf(-3f, -12f, 11f), floatArrayOf(5f, -2f, 11f),
        )
        // Dark underside silhouette first, then lit blobs.
        for (b in blobs) { fill.color = shade(g, 0.45f); c.drawCircle(x + b[0] * s * size, cy + b[1] * s * size + 1.5f * s, b[2] * s * size, fill) }
        for (b in blobs.sortedBy { it[1] }.reversed()) blob(c, x + b[0] * s * size, cy + b[1] * s * size, b[2] * s * size * 0.95f, shade(g, 0.95f + rnd.nextFloat() * 0.12f))
        // Leaf flecks.
        repeat(26) {
            val a = rnd.nextFloat() * 6.283f; val r = rnd.nextFloat() * 17f * s * size
            fill.color = if (rnd.nextInt(3) == 0) 0x40102008 else 0x38E0FFB0
            c.drawCircle(x + cos(a) * r, cy + sin(a) * r * 0.85f, (0.9f + rnd.nextFloat()) * s, fill)
        }
    }

    private fun berries(c: Canvas, x: Float, y: Float, s: Float, level: Int, rnd: Random) {
        shadow(c, x + 2f * s, y, 15f * s, 5f * s)
        val leaf = 0xFF3E7A2E.toInt()
        val blobs = listOf(floatArrayOf(-7f, -6f, 8f), floatArrayOf(7f, -6f, 8f), floatArrayOf(0f, -4f, 8.5f), floatArrayOf(-3f, -12f, 7.5f), floatArrayOf(4f, -13f, 7f))
        for (b in blobs) { fill.color = shade(leaf, 0.5f); c.drawCircle(x + b[0] * s, y + b[1] * s + 1.2f * s, b[2] * s, fill) }
        for (b in blobs) blob(c, x + b[0] * s, y + b[1] * s, b[2] * s * 0.95f, shade(leaf, 0.95f + rnd.nextFloat() * 0.15f))
        val n = 3 + level * 3
        repeat(n) {
            val bx = x + (rnd.nextFloat() * 24f - 12f) * s
            val by = y - (3f + rnd.nextFloat() * 15f) * s
            fill.color = 0xFF7A0E22.toInt(); c.drawCircle(bx + 0.4f * s, by + 0.4f * s, 2.1f * s, fill)
            fill.color = 0xFFD8203E.toInt(); c.drawCircle(bx, by, 1.8f * s, fill)
            fill.color = 0xCCFFE0E8.toInt(); c.drawCircle(bx - 0.6f * s, by - 0.6f * s, 0.6f * s, fill)
        }
    }

    private fun rocks(c: Canvas, x: Float, y: Float, s: Float, level: Int, gold: Boolean, rnd: Random) {
        val frac = 0.4f + level * 0.2f
        shadow(c, x + 3f * s, y + 2f * s, 34f * s * frac, 13f * s * frac)
        val rock = if (gold) 0xFF8C7A5E.toInt() else 0xFF9C9C98.toInt()
        val spots = listOf(floatArrayOf(-14f, -2f, 11f), floatArrayOf(12f, -1f, 10f), floatArrayOf(-1f, 6f, 11f), floatArrayOf(-3f, -12f, 12f),
            floatArrayOf(-20f, 7f, 7f), floatArrayOf(19f, 8f, 7f), floatArrayOf(6f, -6f, 8f))
        val count = 3 + level
        for ((k, r) in spots.take(count + 1).sortedBy { it[1] }.withIndex()) {
            val rx = x + r[0] * s * frac; val ry = y + r[1] * s * frac
            val rr = r[2] * s * frac
            val peakX = rx + (rnd.nextFloat() - 0.3f) * rr * 0.6f; val peakY = ry - rr * (1.1f + rnd.nextFloat() * 0.4f)
            val lx = rx - rr; val rgt = rx + rr
            val midX = rx + rr * 0.15f; val midY = ry - rr * 0.35f
            // Three facets: lit left, top, shaded right.
            facet(c, shade(rock, 1.15f), lx, ry, midX, ry + rr * 0.15f, midX, midY, peakX, peakY)
            facet(c, shade(rock, 0.7f), midX, ry + rr * 0.15f, rgt, ry, peakX, peakY, midX, midY)
            facet(c, shade(rock, 1.35f), lx, ry, peakX, peakY, lx + rr * 0.35f, ry - rr * 0.7f, lx + rr * 0.2f, ry - rr * 0.3f)
            stroke.color = 0x60000000; stroke.strokeWidth = max(0.8f, 0.8f * s)
            path.reset(); path.moveTo(lx, ry); path.lineTo(peakX, peakY); path.lineTo(rgt, ry); path.lineTo(midX, ry + rr * 0.15f); path.close()
            c.drawPath(path, stroke)
            if (gold) {
                repeat(2 + k % 2) {
                    val gx = rx + (rnd.nextFloat() - 0.5f) * rr; val gy = ry - rnd.nextFloat() * rr * 0.8f
                    fill.color = 0xFF9A6A10.toInt(); c.drawCircle(gx + 0.5f * s, gy + 0.5f * s, rr * 0.2f, fill)
                    fill.color = 0xFFFFD040.toInt(); c.drawCircle(gx, gy, rr * 0.18f, fill)
                    fill.color = 0xFFFFFAD0.toInt(); c.drawCircle(gx - rr * 0.06f, gy - rr * 0.06f, rr * 0.06f, fill)
                }
            } else {
                stroke.color = 0x55303030; stroke.strokeWidth = max(0.7f, 0.7f * s)
                c.drawLine(midX - rr * 0.4f, midY + rr * 0.1f, midX + rr * 0.1f, midY - rr * 0.3f, stroke)
            }
        }
    }

    private fun facet(c: Canvas, color: Int, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        path.reset()
        path.moveTo(x0, y0); path.lineTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
        fill.color = color
        c.drawPath(path, fill)
    }

    private fun deer(c: Canvas, x: Float, y: Float, s: Float, variant: Int) {
        shadow(c, x, y, 10f * s, 3.5f * s)
        val body = if (variant % 3 == 0) 0xFF8E6236.toInt() else 0xFFA4743E.toInt()
        stroke.strokeWidth = max(1f, 1.3f * s)
        for (k in 0 until 4) {
            stroke.color = shade(body, if (k % 2 == 0) 0.6f else 0.75f)
            val lx = x + (-6f + k * 3.6f) * s
            c.drawLine(lx, y - 7f * s, lx + (if (k < 2) -0.6f else 0.6f) * s, y, stroke)
        }
        fill.color = 0xFF000000.toInt(); fill.shader = LinearGradient(0f, y - 12f * s, 0f, y - 4f * s, intArrayOf(shade(body, 1.2f), body, 0xFFE8D8C0.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawOval(x - 8f * s, y - 12f * s, x + 8f * s, y - 5f * s, fill)
        fill.shader = null
        // Neck and head.
        stroke.color = body; stroke.strokeWidth = max(1f, 2.8f * s)
        c.drawLine(x + 5.5f * s, y - 10f * s, x + 9f * s, y - 16f * s, stroke)
        fill.color = body
        c.drawOval(x + 7.5f * s, y - 18.5f * s, x + 13.5f * s, y - 14.5f * s, fill)
        fill.color = 0xFF2A1A10.toInt(); c.drawCircle(x + 13.2f * s, y - 16.3f * s, 0.8f * s, fill)
        fill.color = 0xFF2A1A10.toInt(); c.drawCircle(x + 10.5f * s, y - 17.3f * s, 0.6f * s, fill)
        // Antlers on stags.
        if (variant % 2 == 0) {
            stroke.color = 0xFFD8C8A8.toInt(); stroke.strokeWidth = max(0.8f, 0.8f * s)
            c.drawLine(x + 9.5f * s, y - 18f * s, x + 8f * s, y - 24f * s, stroke)
            c.drawLine(x + 8.6f * s, y - 21f * s, x + 6f * s, y - 23f * s, stroke)
            c.drawLine(x + 11f * s, y - 18f * s, x + 12.5f * s, y - 24f * s, stroke)
            c.drawLine(x + 12f * s, y - 21.5f * s, x + 14.5f * s, y - 23f * s, stroke)
        }
        // Tail.
        fill.color = 0xFFF4ECE0.toInt(); c.drawCircle(x - 8.2f * s, y - 10.5f * s, 1.6f * s, fill)
    }

    companion object {
        private const val PAD = 3f
    }
}
