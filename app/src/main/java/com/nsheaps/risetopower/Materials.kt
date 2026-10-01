package com.nsheaps.risetopower

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import kotlin.math.max
import kotlin.random.Random

/**
 * Procedural surface detail for building faces. Each material is a small tileable overlay
 * (mortar lines, plank seams, roof tiles) drawn on top of a flat-shaded face and aligned to it
 * with an affine matrix, so a box reads as stone, timber or plaster instead of a flat colour.
 */
class Materials {
    enum class Kind { NONE, BLOCKS, PLANKS, PLASTER, MARBLE, SHINGLES }

    private val bitmaps = HashMap<Kind, Bitmap>()
    private val shaders = HashMap<Kind, BitmapShader>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { style = Paint.Style.FILL }
    private val aoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = LinearGradient(0f, 0f, 0f, 1f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val m = Matrix()
    private val vals = FloatArray(9)

    init {
        for (k in Kind.entries) if (k != Kind.NONE) {
            val b = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
            paintTexture(k, b)
            bitmaps[k] = b
            shaders[k] = BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }

    /**
     * Fills [path] with material [k]. The texture's u axis follows screen vector (ux, uy) and its v
     * axis (vx, vy), each spanning one texture tile, starting from (ox, oy).
     */
    fun overlay(c: Canvas, path: Path, k: Kind, ox: Float, oy: Float, ux: Float, uy: Float, vx: Float, vy: Float) {
        val sh = shaders[k] ?: return
        setAffine(ox, oy, ux / SIZE, uy / SIZE, vx / SIZE, vy / SIZE)
        sh.setLocalMatrix(m)
        paint.shader = sh
        c.drawPath(path, paint)
    }

    /** Darkens the bottom of a wall: a gradient from the ground line (o) along (vx, vy). */
    fun ambientOcclusion(c: Canvas, path: Path, ox: Float, oy: Float, ux: Float, uy: Float, vx: Float, vy: Float) {
        setAffine(ox, oy, ux, uy, vx, vy)
        aoPaint.shader.setLocalMatrix(m)
        c.drawPath(path, aoPaint)
    }

    fun outline(c: Canvas, path: Path, color: Int, width: Float) {
        edge.color = color
        edge.strokeWidth = width
        c.drawPath(path, edge)
    }

    private fun setAffine(ox: Float, oy: Float, a: Float, d: Float, b: Float, e: Float) {
        vals[0] = a; vals[1] = b; vals[2] = ox
        vals[3] = d; vals[4] = e; vals[5] = oy
        vals[6] = 0f; vals[7] = 0f; vals[8] = 1f
        m.setValues(vals)
    }

    private fun paintTexture(k: Kind, b: Bitmap) {
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = Random(k.ordinal * 7919 + 13)
        fun rect(l: Float, t: Float, r: Float, bt: Float, color: Int) { p.color = color; c.drawRect(l, t, r, bt, p) }
        fun speckle(n: Int, maxAlpha: Int) {
            repeat(n) {
                val x = rnd.nextFloat() * SIZE; val y = rnd.nextFloat() * SIZE
                val a = rnd.nextInt(maxAlpha)
                rect(x, y, x + 1.5f, y + 1.5f, if (rnd.nextBoolean()) Color.argb(a, 0, 0, 0) else Color.argb(a, 255, 255, 255))
            }
        }
        val s = SIZE.toFloat()
        when (k) {
            Kind.BLOCKS -> {
                val rows = 4
                val rh = s / rows
                for (r in 0 until rows) {
                    val off = if (r % 2 == 0) 0f else s / 4
                    for (i in -1 until 2) {
                        val l = off + i * s / 2
                        val tint = rnd.nextInt(-28, 28)
                        rect(l, r * rh, l + s / 2, (r + 1) * rh, if (tint > 0) Color.argb(tint, 255, 255, 255) else Color.argb(-tint, 0, 0, 0))
                        // Mortar: dark bottom and left, light top highlight.
                        rect(l, (r + 1) * rh - 3f, l + s / 2, (r + 1) * rh, 0x70000000)
                        rect(l, r * rh, l + 3f, (r + 1) * rh, 0x60000000)
                        rect(l + 3f, r * rh, l + s / 2, r * rh + 2f, 0x30FFFFFF)
                    }
                }
                speckle(260, 40)
            }
            Kind.PLANKS -> {
                val rows = 6
                val rh = s / rows
                for (r in 0 until rows) {
                    val tint = rnd.nextInt(-30, 22)
                    rect(0f, r * rh, s, (r + 1) * rh, if (tint > 0) Color.argb(tint, 255, 240, 220) else Color.argb(-tint, 30, 15, 0))
                    rect(0f, (r + 1) * rh - 2.5f, s, (r + 1) * rh, 0x80000000.toInt())
                    // Grain streaks and an occasional knot.
                    repeat(3) {
                        val y = r * rh + 2f + rnd.nextFloat() * (rh - 5f)
                        val x = rnd.nextFloat() * s
                        rect(x, y, x + s * (0.2f + rnd.nextFloat() * 0.4f), y + 1f, 0x28000000)
                    }
                    val seam = rnd.nextFloat() * s
                    rect(seam, r * rh, seam + 2f, (r + 1) * rh, 0x50000000)
                }
            }
            Kind.PLASTER -> {
                speckle(500, 30)
                // Timber framing: a post and two beams per tile.
                val beam = 0xB04A3020.toInt()
                rect(0f, 0f, 5f, s, beam)
                rect(0f, s - 6f, s, s, beam)
                rect(0f, s * 0.45f, s, s * 0.45f + 4f, beam)
                // Diagonal brace.
                p.color = beam; p.strokeWidth = 4f
                c.drawLine(5f, s * 0.45f, s * 0.55f, 4f, p)
            }
            Kind.MARBLE -> {
                for (r in 0 until 2) {
                    val y = (r + 1) * s / 2
                    rect(0f, y - 2.5f, s, y, 0x48000000)
                    val x = if (r == 0) 0f else s / 2
                    rect(x, r * s / 2, x + 2.5f, (r + 1) * s / 2, 0x40000000)
                }
                // Faint veins.
                p.color = 0x30404050; p.strokeWidth = 1.2f
                repeat(5) {
                    val x0 = rnd.nextFloat() * s; val y0 = rnd.nextFloat() * s
                    c.drawLine(x0, y0, x0 + rnd.nextFloat() * 24f - 12f, y0 + rnd.nextFloat() * 24f - 12f, p)
                }
                speckle(200, 18)
            }
            Kind.SHINGLES -> {
                val rows = 5
                val rh = s / rows
                for (r in 0 until rows) {
                    val n = 4
                    val w = s / n
                    val off = if (r % 2 == 0) 0f else w / 2
                    for (i in -1 until n) {
                        val l = off + i * w
                        val tint = rnd.nextInt(-34, 26)
                        rect(l, r * rh, l + w, (r + 1) * rh, if (tint > 0) Color.argb(tint, 255, 255, 255) else Color.argb(-tint, 0, 0, 0))
                        rect(l, r * rh, l + 2f, (r + 1) * rh, 0x50000000)
                    }
                    // Shadow under each course and a lit lip above it.
                    rect(0f, (r + 1) * rh - 3.5f, s, (r + 1) * rh, 0x80000000.toInt())
                    rect(0f, r * rh, s, r * rh + 1.5f, 0x38FFFFFF)
                }
            }
            Kind.NONE -> {}
        }
    }

    fun recycle() {
        bitmaps.values.forEach { it.recycle() }
    }

    companion object {
        const val SIZE = 64

        /** Picks a wall material from a base colour so existing building palettes get sensible surfaces. */
        fun forWall(color: Int): Kind {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            val sat = hsv[1]; val v = hsv[2]
            return when {
                v > 0.8f && sat < 0.14f -> Kind.MARBLE
                v > 0.78f && sat in 0.1f..0.32f && hsv[0] in 25f..60f -> Kind.PLASTER
                sat > 0.4f && v < 0.62f && hsv[0] in 15f..45f -> Kind.PLANKS
                else -> Kind.BLOCKS
            }
        }

        fun lineWidth(scale: Float) = max(1f, 0.9f * scale)
    }
}
