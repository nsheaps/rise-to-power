package com.nsheaps.risetopower

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.nsheaps.risetopower.TerrainLayers.Companion.lerpColor
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
 * once per zoom bucket and blitted. Sprites are anchored at the entity's feet and lit from the
 * upper left, matching the terrain's hill shading.
 */
class NatureSprites {
    enum class Kind(val w: Float, val h: Float, val ax: Float, val ay: Float) {
        CONIFER(50f, 92f, 25f, 82f),
        LEAFY(58f, 88f, 29f, 80f),
        BIRCH(46f, 92f, 23f, 84f),
        BERRIES(44f, 40f, 22f, 32f),
        GOLD(78f, 56f, 39f, 38f),
        STONE(78f, 56f, 39f, 38f),
        DEER(44f, 40f, 20f, 34f),
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
     * (x, y) at scale [s]. [mirror] flips it horizontally; [sway] shears the top sideways by that
     * fraction of its height (a gentle breeze on trees).
     */
    fun draw(c: Canvas, kind: Kind, variant: Int, level: Int, x: Float, y: Float, s: Float, mirror: Boolean = false, sway: Float = 0f) {
        val sp = sprite(kind, variant, level, s)
        val k = s / sp.scale
        val w = sp.bmp.width * k; val h = sp.bmp.height * k
        val l = x - kind.ax * s - PAD * s
        val t = y - kind.ay * s - PAD * s
        val transformed = mirror || sway != 0f
        if (transformed) {
            c.save()
            if (mirror) c.scale(-1f, 1f, x, y)
            if (sway != 0f) { c.translate(x, y); c.skew(-sway, 0f); c.translate(-x, -y) }
        }
        dst.set(l, t, l + w, t + h)
        c.drawBitmap(sp.bmp, null, dst, blit)
        if (transformed) c.restore()
    }

    /** True when the drawn sprite has an opaque pixel (not just shadow) at screen point (px, py). */
    fun hit(kind: Kind, variant: Int, level: Int, x: Float, y: Float, s: Float, mirror: Boolean, px: Float, py: Float): Boolean {
        val sp = sprite(kind, variant, level, s)
        val k = s / sp.scale
        val qx = if (mirror) 2 * x - px else px
        val bx = ((qx - (x - kind.ax * s - PAD * s)) / k).toInt()
        val by = ((py - (y - kind.ay * s - PAD * s)) / k).toInt()
        if (bx < 0 || by < 0 || bx >= sp.bmp.width || by >= sp.bmp.height) return false
        return (sp.bmp.getPixel(bx, by) ushr 24) >= 128
    }

    private fun sprite(kind: Kind, variant: Int, level: Int, s: Float): Sprite {
        val b = (s * 8f).roundToInt().coerceAtLeast(1)
        if (b != bucket) { clear(); bucket = b }
        val key = (kind.ordinal * 64 + variant) * 16 + level
        return cache.getOrPut(key) { render(kind, variant, level, b / 8f) }
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
            Kind.BIRCH -> birch(c, x, y, s, variant, rnd)
            Kind.BERRIES -> berries(c, x, y, s, level, rnd)
            Kind.GOLD -> rocks(c, x, y, s, level, true, rnd)
            Kind.STONE -> rocks(c, x, y, s, level, false, rnd)
            Kind.DEER -> deer(c, x, y, s, variant)
        }
        return Sprite(bmp, s)
    }

    // ------------------------------------------------------------------ helpers

    private fun shadow(c: Canvas, x: Float, y: Float, rx: Float, ry: Float) {
        fill.color = 0xFF000000.toInt(); fill.shader = RadialGradient(x, y, max(rx, 1f), intArrayOf(0x5C000000, 0x38000000, 0x00000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.save()
        c.scale(1f, ry / rx, x, y)
        c.drawCircle(x, y, rx, fill)
        c.restore()
        fill.shader = null
    }

    /** A sphere-like blob lit from the upper left. */
    private fun blob(c: Canvas, x: Float, y: Float, r: Float, color: Int, hi: Float = 1.35f, lo: Float = 0.6f) {
        fill.color = 0xFF000000.toInt(); fill.shader = RadialGradient(x - r * 0.38f, y - r * 0.42f, r * 1.4f,
            intArrayOf(shade(color, hi), color, shade(color, lo)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, fill)
        fill.shader = null
    }

    /** Trunk with a flared, rooted base and bark streaks. */
    private fun trunk(c: Canvas, x: Float, y: Float, s: Float, h: Float, w: Float, bark: Int = 0xFF5E3E24.toInt()) {
        fill.color = 0xFF000000.toInt(); fill.shader = LinearGradient(x - w, 0f, x + w, 0f, intArrayOf(shade(bark, 1.3f), bark, shade(bark, 0.55f)), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        path.reset()
        path.moveTo(x - w * 2.1f, y + 0.5f * s)
        path.quadTo(x - w * 1.1f, y - 1.5f * s, x - w * 0.85f, y - h * 0.4f)
        path.lineTo(x - w * 0.75f, y - h)
        path.lineTo(x + w * 0.75f, y - h)
        path.lineTo(x + w * 0.85f, y - h * 0.4f)
        path.quadTo(x + w * 1.1f, y - 1.5f * s, x + w * 2.1f, y + 0.5f * s)
        path.close()
        c.drawPath(path, fill)
        fill.shader = null
        // Bark streaks.
        stroke.color = 0x38000000; stroke.strokeWidth = max(0.6f, 0.6f * s)
        c.drawLine(x - w * 0.25f, y - h * 0.9f, x - w * 0.35f, y - h * 0.25f, stroke)
        c.drawLine(x + w * 0.3f, y - h * 0.7f, x + w * 0.4f, y - h * 0.15f, stroke)
        stroke.color = 0x30FFF0D0; c.drawLine(x - w * 0.6f, y - h * 0.85f, x - w * 0.65f, y - h * 0.4f, stroke)
    }

    // ------------------------------------------------------------------ sprites

    private fun conifer(c: Canvas, x: Float, y: Float, s: Float, variant: Int, rnd: Random) {
        val greens = intArrayOf(0xFF234E2C.toInt(), 0xFF2B5A2E.toInt(), 0xFF1E4A30.toInt(), 0xFF2E6030.toInt(), 0xFF27543A.toInt())
        val g = greens[variant % greens.size]
        val size = 0.88f + (variant % 5) * 0.06f
        val lean = ((variant % 3) - 1) * 1.2f * s
        shadow(c, x + 7f * s, y + 1f * s, 17f * s * size, 6.5f * s * size)
        trunk(c, x, y, s, 16f * s, 2.3f * s)
        val tiers = 5
        for (k in 0 until tiers) {
            val base = y - (10f + k * 12.5f) * s * size
            val cx = x + lean * k / tiers
            val w = (19f - k * 3.3f) * s * size
            val h = (24f - k * 1.8f) * s * size
            // Apex, then a jagged, drooping lower edge from right to left.
            path.reset()
            path.moveTo(cx, base - h)
            val teeth = 7
            for (i in teeth downTo -teeth) {
                val f = i / teeth.toFloat()
                val sag = (1f - abs(f)) * 3.5f * s
                path.lineTo(cx + w * f, base + sag + (if ((i + teeth) % 2 == 0) 0f else 3.2f * s) + (if (i == 0) 1.5f * s else 0f))
            }
            path.close()
            val tier = shade(g, 0.88f + k * 0.08f)
            fill.color = 0xFF000000.toInt(); fill.shader = LinearGradient(cx - w, base - h, cx + w * 0.8f, base, intArrayOf(shade(tier, 1.5f), tier, shade(tier, 0.5f)), floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(path, fill)
            fill.shader = null
            stroke.color = 0x48000000; stroke.strokeWidth = max(0.8f, 0.8f * s)
            c.drawPath(path, stroke)
            // Shadow cast by the tier above onto this one.
            if (k < tiers - 1) {
                fill.color = 0x30000000
                c.drawOval(cx - w * 0.75f, base - h * 0.95f, cx + w * 0.75f, base - h * 0.55f, fill)
            }
        }
        // Lit needle tips along the left side, a few dark ones on the right.
        repeat(12) {
            val ty = y - (12f + rnd.nextFloat() * 50f) * s * size
            val frac = 1f - (y - ty) / (62f * s * size)
            fill.color = 0x44F0FFC8
            c.drawCircle(x - (2f + rnd.nextFloat() * 12f * frac) * s, ty, (0.9f + rnd.nextFloat() * 0.6f) * s, fill)
            fill.color = 0x30001008
            c.drawCircle(x + (2f + rnd.nextFloat() * 10f * frac) * s, ty + 2f * s, (0.8f + rnd.nextFloat() * 0.6f) * s, fill)
        }
    }

    private fun leafy(c: Canvas, x: Float, y: Float, s: Float, variant: Int, rnd: Random) {
        val greens = intArrayOf(0xFF3A7230.toInt(), 0xFF457E2E.toInt(), 0xFF34682E.toInt(), 0xFF56822A.toInt(), 0xFF3F7838.toInt(), 0xFF8E8030.toInt())
        val g = greens[variant % greens.size]
        val size = 0.9f + (variant % 4) * 0.07f
        shadow(c, x + 8f * s, y + 1f * s, 20f * s * size, 7.5f * s * size)
        trunk(c, x, y, s, 26f * s * size, 2.8f * s)
        // Branches into the crown.
        stroke.color = 0xFF4E321E.toInt(); stroke.strokeWidth = max(1f, 1.7f * s)
        c.drawLine(x, y - 20f * s * size, x - 8f * s, y - 32f * s * size, stroke)
        c.drawLine(x, y - 22f * s * size, x + 7f * s, y - 33f * s * size, stroke)
        val cy = y - 42f * s * size
        val blobs = listOf(
            floatArrayOf(-11f, 7f, 10.5f), floatArrayOf(10f, 6f, 10.5f), floatArrayOf(0f, 9f, 11f),
            floatArrayOf(-13f, -4f, 10f), floatArrayOf(12f, -5f, 10f), floatArrayOf(-4f, -13f, 11f), floatArrayOf(6f, -3f, 12f), floatArrayOf(-3f, 0f, 12f),
        )
        // Dark underside silhouette, then the lit body, then smaller highlight clusters up-left.
        for (b in blobs) { fill.color = shade(g, 0.42f); c.drawCircle(x + b[0] * s * size + 1f * s, cy + b[1] * s * size + 2.5f * s, b[2] * s * size, fill) }
        for (b in blobs.sortedByDescending { it[1] }) blob(c, x + b[0] * s * size, cy + b[1] * s * size, b[2] * s * size * 0.96f, shade(g, 0.92f + rnd.nextFloat() * 0.14f))
        for (b in blobs.sortedByDescending { it[1] }) {
            if (rnd.nextInt(3) == 0) continue
            blob(c, x + (b[0] - 2.5f) * s * size, cy + (b[1] - 3f) * s * size, b[2] * s * size * 0.55f, shade(g, 1.06f), hi = 1.22f, lo = 0.9f)
        }
        // Leaf flecks: light on the sunny side, dark in the shade.
        repeat(26) {
            val a = rnd.nextFloat() * 6.283f; val r = rnd.nextFloat() * 19f * s * size
            val lx = x + cos(a) * r; val ly = cy + sin(a) * r * 0.85f
            val sunny = (lx - x) - (ly - cy) < 0f
            fill.color = if (sunny) 0x34F0FFB8 else 0x40102008
            c.drawCircle(lx, ly, (0.9f + rnd.nextFloat()) * s, fill)
        }
        // Rim light along the top-left edge of the crown.
        stroke.color = 0x38FFFFD0; stroke.strokeWidth = max(1f, 1.4f * s)
        c.drawArc(x - 17f * s * size, cy - 24f * s * size, x + 15f * s * size, cy + 12f * s * size, 195f, 70f, false, stroke)
    }

    private fun birch(c: Canvas, x: Float, y: Float, s: Float, variant: Int, rnd: Random) {
        val g = if (variant % 2 == 0) 0xFF7FA23A.toInt() else 0xFF8DAA40.toInt()
        val size = 0.9f + (variant % 3) * 0.06f
        shadow(c, x + 6f * s, y + 1f * s, 14f * s * size, 5.5f * s * size)
        val h = 46f * s * size
        trunk(c, x, y, s, h, 1.9f * s, bark = 0xFFE4DCCC.toInt())
        // Dark bark dashes.
        fill.color = 0xFF3A3028.toInt()
        for (k in 0 until 6) {
            val ty = y - h * (0.12f + k * 0.14f) - rnd.nextFloat() * 2f * s
            val side = if (k % 2 == 0) -1f else 1f
            c.drawRect(x + side * 0.4f * s - 1.2f * s, ty, x + side * 0.4f * s + 1.2f * s, ty + 1.1f * s, fill)
        }
        stroke.color = 0xFFD8D0C0.toInt(); stroke.strokeWidth = max(1f, 1.2f * s)
        c.drawLine(x, y - h * 0.65f, x - 7f * s, y - h * 0.95f, stroke)
        c.drawLine(x, y - h * 0.75f, x + 6f * s, y - h * 1.02f, stroke)
        val cy = y - h - 10f * s * size
        val blobs = listOf(floatArrayOf(-8f, 6f, 8f), floatArrayOf(8f, 4f, 8f), floatArrayOf(0f, -6f, 9f), floatArrayOf(-6f, -4f, 7f), floatArrayOf(7f, -8f, 6.5f), floatArrayOf(0f, 8f, 7f))
        for (b in blobs) { fill.color = shade(g, 0.5f); c.drawCircle(x + b[0] * s * size + 1f * s, cy + b[1] * s * size + 2f * s, b[2] * s * size, fill) }
        for (b in blobs.sortedByDescending { it[1] }) blob(c, x + b[0] * s * size, cy + b[1] * s * size, b[2] * s * size * 0.95f, shade(g, 0.95f + rnd.nextFloat() * 0.12f), hi = 1.4f)
        repeat(26) {
            val a = rnd.nextFloat() * 6.283f; val r = rnd.nextFloat() * 15f * s * size
            val lx = x + cos(a) * r; val ly = cy + sin(a) * r * 0.9f
            fill.color = if ((lx - x) - (ly - cy) < 0f) 0x58FFFFC0 else 0x38203010
            c.drawCircle(lx, ly, (0.8f + rnd.nextFloat()) * s, fill)
        }
    }

    private fun berries(c: Canvas, x: Float, y: Float, s: Float, level: Int, rnd: Random) {
        shadow(c, x + 2f * s, y + 0.5f * s, 16f * s, 5.5f * s)
        val leaf = 0xFF3C7C30.toInt()
        val blobs = listOf(floatArrayOf(-8f, -6f, 8.5f), floatArrayOf(8f, -6f, 8.5f), floatArrayOf(0f, -4f, 9f), floatArrayOf(-3f, -13f, 7.5f), floatArrayOf(5f, -14f, 7f), floatArrayOf(-12f, -2f, 5.5f), floatArrayOf(13f, -2f, 5.5f))
        for (b in blobs) { fill.color = shade(leaf, 0.45f); c.drawCircle(x + b[0] * s + 0.8f * s, y + b[1] * s + 1.8f * s, b[2] * s, fill) }
        for (b in blobs.sortedByDescending { it[1] }) blob(c, x + b[0] * s, y + b[1] * s, b[2] * s * 0.95f, shade(leaf, 0.95f + rnd.nextFloat() * 0.15f))
        // Small leaves.
        repeat(16) {
            val lx = x + (rnd.nextFloat() * 26f - 13f) * s; val ly = y - (2f + rnd.nextFloat() * 17f) * s
            fill.color = if ((lx - x) - (ly - y + 8f * s) < 0f) 0x55D8FFA0 else 0x40102008
            c.save(); c.rotate(rnd.nextFloat() * 180f, lx, ly)
            c.drawOval(lx - 1.6f * s, ly - 0.9f * s, lx + 1.6f * s, ly + 0.9f * s, fill)
            c.restore()
        }
        // Berries in clusters; more when the bush is full.
        val clusters = 2 + level * 2
        repeat(clusters) {
            val bx = x + (rnd.nextFloat() * 22f - 11f) * s
            val by = y - (4f + rnd.nextFloat() * 13f) * s
            repeat(3) { k ->
                val ox = bx + (k - 1) * 1.9f * s + (rnd.nextFloat() - 0.5f) * s; val oy = by + (if (k == 1) -1.6f else 0.4f) * s
                fill.color = 0xFF6E0C1E.toInt(); c.drawCircle(ox + 0.5f * s, oy + 0.6f * s, 1.9f * s, fill)
                fill.color = 0xFFD8203E.toInt(); c.drawCircle(ox, oy, 1.7f * s, fill)
                fill.color = 0xDDFFE4EA.toInt(); c.drawCircle(ox - 0.55f * s, oy - 0.55f * s, 0.55f * s, fill)
            }
        }
    }

    private fun rocks(c: Canvas, x: Float, y: Float, s: Float, level: Int, gold: Boolean, rnd: Random) {
        val frac = 0.4f + level * 0.2f
        shadow(c, x + 3f * s, y + 2f * s, 36f * s * frac, 14f * s * frac)
        // Scattered pebbles and, for stone, a little moss at the base.
        repeat(7) {
            val px = x + (rnd.nextFloat() - 0.5f) * 64f * s * frac; val py = y + (rnd.nextFloat() - 0.3f) * 12f * s * frac
            fill.color = if (!gold && rnd.nextInt(3) == 0) 0xAA4E7A2C.toInt() else 0xFF8C8880.toInt()
            c.drawOval(px - 1.6f * s, py - 1f * s, px + 1.6f * s, py + 1f * s, fill)
        }
        val base = if (gold) 0xFF8E7A5A.toInt() else 0xFF9A9A96.toInt()
        val spots = listOf(floatArrayOf(-14f, -2f, 11f), floatArrayOf(12f, -1f, 10f), floatArrayOf(-1f, 6f, 11f), floatArrayOf(-3f, -12f, 12f),
            floatArrayOf(-20f, 7f, 7f), floatArrayOf(19f, 8f, 7f), floatArrayOf(6f, -6f, 8f))
        val count = 3 + level
        for ((k, r) in spots.take(count + 1).sortedBy { it[1] }.withIndex()) {
            val rock = shade(base, 0.9f + rnd.nextFloat() * 0.2f)
            val rx = x + r[0] * s * frac; val ry = y + r[1] * s * frac
            val rr = r[2] * s * frac
            val peakX = rx + (rnd.nextFloat() - 0.3f) * rr * 0.6f; val peakY = ry - rr * (1.1f + rnd.nextFloat() * 0.4f)
            val lx = rx - rr; val rgt = rx + rr
            val midX = rx + rr * 0.15f; val midY = ry - rr * 0.35f
            // Contact shadow under this rock.
            fill.color = 0x40000000; c.drawOval(lx, ry - rr * 0.2f, rgt + rr * 0.2f, ry + rr * 0.35f, fill)
            // Three facets: lit left, top, shaded right.
            facet(c, shade(rock, 1.1f), lx, ry, midX, ry + rr * 0.15f, midX, midY, peakX, peakY)
            facet(c, shade(rock, 0.66f), midX, ry + rr * 0.15f, rgt, ry, peakX, peakY, midX, midY)
            facet(c, shade(rock, 1.4f), lx, ry, peakX, peakY, lx + rr * 0.35f, ry - rr * 0.7f, lx + rr * 0.2f, ry - rr * 0.3f)
            // Darker band near the ground on the lit side.
            facet(c, 0x22000000, lx, ry, midX, ry + rr * 0.15f, midX, ry - rr * 0.1f, lx + rr * 0.1f, ry - rr * 0.15f)
            stroke.color = 0x58000000; stroke.strokeWidth = max(0.8f, 0.8f * s)
            path.reset(); path.moveTo(lx, ry); path.lineTo(peakX, peakY); path.lineTo(rgt, ry); path.lineTo(midX, ry + rr * 0.15f); path.close()
            c.drawPath(path, stroke)
            // Ridge highlight from the peak down the lit edge.
            stroke.color = 0x60FFFFFF; stroke.strokeWidth = max(0.7f, 0.7f * s)
            c.drawLine(peakX, peakY, lx + rr * 0.1f, ry - rr * 0.1f, stroke)
            if (gold) {
                // Veins and glinting nuggets.
                stroke.color = 0xB0E8B830.toInt(); stroke.strokeWidth = max(0.8f, 0.9f * s)
                val vx = lx + rr * 0.4f; val vy = ry - rr * 0.3f
                path.reset(); path.moveTo(vx, vy); path.lineTo(vx + rr * 0.25f, vy - rr * 0.3f); path.lineTo(vx + rr * 0.35f, vy - rr * 0.6f)
                c.drawPath(path, stroke)
                repeat(2 + k % 2) {
                    val gx = rx + (rnd.nextFloat() - 0.5f) * rr; val gy = ry - rnd.nextFloat() * rr * 0.8f
                    fill.color = 0xFF9A6A10.toInt(); c.drawCircle(gx + 0.5f * s, gy + 0.5f * s, rr * 0.2f, fill)
                    fill.color = 0xFFFFD040.toInt(); c.drawCircle(gx, gy, rr * 0.18f, fill)
                    fill.color = 0xFFFFFAD0.toInt(); c.drawCircle(gx - rr * 0.06f, gy - rr * 0.06f, rr * 0.07f, fill)
                    // Four-point sparkle.
                    stroke.color = 0xC0FFFFF0.toInt(); stroke.strokeWidth = max(0.6f, 0.6f * s)
                    c.drawLine(gx - rr * 0.3f, gy, gx + rr * 0.3f, gy, stroke); c.drawLine(gx, gy - rr * 0.3f, gx, gy + rr * 0.3f, stroke)
                }
            } else {
                stroke.color = 0x60303030; stroke.strokeWidth = max(0.7f, 0.7f * s)
                c.drawLine(midX - rr * 0.4f, midY + rr * 0.1f, midX + rr * 0.1f, midY - rr * 0.3f, stroke)
                c.drawLine(midX + rr * 0.1f, midY - rr * 0.3f, midX + rr * 0.3f, midY - rr * 0.25f, stroke)
                // Cool-blue sheen on the top facet.
                fill.color = 0x28C8D8FF; c.drawCircle(lx + rr * 0.3f, ry - rr * 0.55f, rr * 0.25f, fill)
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
        val fawn = variant % 4 == 3
        val k = if (fawn) 0.72f else 1f
        shadow(c, x, y, 11f * s * k, 3.8f * s * k)
        val body = when (variant % 3) { 0 -> 0xFF8E6236.toInt(); 1 -> 0xFFA4743E.toInt(); else -> 0xFF9A6A3A.toInt() }
        val belly = 0xFFE8D8C0.toInt()
        // Legs: tapered, with dark hooves; back pair slightly darker.
        for (leg in 0 until 4) {
            val back = leg < 2
            val lx = x + (-6.5f + leg * 3.8f) * s * k
            val kneeX = lx + (if (back) -1.2f else 0.4f) * s * k
            fill.color = shade(body, if (back) 0.62f else 0.8f)
            path.reset()
            path.moveTo(lx - 1.3f * s * k, y - 8f * s * k); path.lineTo(lx + 1.3f * s * k, y - 8f * s * k)
            path.lineTo(kneeX + 0.8f * s * k, y - 3.5f * s * k); path.lineTo(kneeX + 0.7f * s * k, y)
            path.lineTo(kneeX - 0.7f * s * k, y); path.lineTo(kneeX - 0.8f * s * k, y - 3.5f * s * k)
            path.close()
            c.drawPath(path, fill)
            fill.color = 0xFF2A1A10.toInt(); c.drawRect(kneeX - 0.8f * s * k, y - 1f * s * k, kneeX + 0.8f * s * k, y, fill)
        }
        // Body with a pale belly.
        fill.color = 0xFF000000.toInt(); fill.shader = LinearGradient(0f, y - 14f * s * k, 0f, y - 5f * s * k, intArrayOf(shade(body, 1.25f), body, lerpColor(body, belly, 0.7f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawOval(x - 9f * s * k, y - 14f * s * k, x + 8.5f * s * k, y - 5f * s * k, fill)
        fill.shader = null
        if (fawn) {
            fill.color = 0xCCFFF4E0.toInt()
            for (i in 0 until 7) c.drawCircle(x + (-6f + i * 2f) * s * k, y - (10.5f + (i % 2) * 1.6f) * s * k, 0.6f * s, fill)
        }
        // White rump and tail.
        fill.color = 0xFFF4ECE0.toInt(); c.drawOval(x - 9.6f * s * k, y - 13f * s * k, x - 6f * s * k, y - 8f * s * k, fill)
        fill.color = shade(body, 0.8f); c.drawCircle(x - 9.4f * s * k, y - 11.5f * s * k, 1.1f * s * k, fill)
        // Neck and head.
        fill.color = body
        path.reset()
        path.moveTo(x + 5f * s * k, y - 13.5f * s * k); path.lineTo(x + 8.5f * s * k, y - 11f * s * k)
        path.lineTo(x + 11.5f * s * k, y - 19f * s * k); path.lineTo(x + 8.5f * s * k, y - 20f * s * k)
        path.close()
        c.drawPath(path, fill)
        fill.color = shade(body, 1.05f)
        c.drawOval(x + 8f * s * k, y - 21.5f * s * k, x + 14.5f * s * k, y - 17f * s * k, fill)
        // Muzzle, eye, ears.
        fill.color = lerpColor(body, belly, 0.5f); c.drawOval(x + 12f * s * k, y - 19.5f * s * k, x + 15f * s * k, y - 17.2f * s * k, fill)
        fill.color = 0xFF2A1A10.toInt(); c.drawCircle(x + 14.6f * s * k, y - 18.2f * s * k, 0.5f * s, fill)
        c.drawCircle(x + 11.3f * s * k, y - 19.8f * s * k, 0.7f * s * k, fill)
        fill.color = shade(body, 0.9f)
        c.drawOval(x + 7f * s * k, y - 24f * s * k, x + 9.5f * s * k, y - 20.5f * s * k, fill)
        c.drawOval(x + 10f * s * k, y - 24.5f * s * k, x + 12.5f * s * k, y - 21f * s * k, fill)
        // Antlers on stags.
        if (variant % 2 == 0 && !fawn) {
            stroke.color = 0xFFD8C8A8.toInt(); stroke.strokeWidth = max(0.8f, 0.9f * s)
            c.drawLine(x + 9.5f * s, y - 22.5f * s, x + 7.5f * s, y - 29f * s, stroke)
            c.drawLine(x + 8.3f * s, y - 26f * s, x + 5.5f * s, y - 28f * s, stroke)
            c.drawLine(x + 8f * s, y - 28f * s, x + 9.5f * s, y - 31f * s, stroke)
            c.drawLine(x + 11.5f * s, y - 22.5f * s, x + 13.5f * s, y - 29f * s, stroke)
            c.drawLine(x + 12.6f * s, y - 26f * s, x + 15.5f * s, y - 27.5f * s, stroke)
            c.drawLine(x + 13.2f * s, y - 28f * s, x + 12.5f * s, y - 31f * s, stroke)
        }
    }

    companion object {
        private const val PAD = 3f
    }
}
