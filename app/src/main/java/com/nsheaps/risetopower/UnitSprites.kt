package com.nsheaps.risetopower

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.LongSparseArray
import com.nsheaps.risetopower.TerrainLayers.Companion.shade
import com.nsheaps.risetopower.core.Age
import com.nsheaps.risetopower.core.UnitType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pre-rendered unit figures. A figure is drawn once per (type, age, team colour, facing, animation
 * frame, carried goods, zoom bucket) into a bitmap and blitted afterwards, so a battle with a
 * hundred units costs a hundred bitmap draws per frame instead of thousands of path fills. The
 * cache is bounded by [BUDGET] bytes and evicts the least recently used sprites first. Figures are
 * drawn in "unit pixels" with the feet at the origin; the camera scale is applied when rendering
 * the bitmap (quantised to half-octave buckets) and the remainder when blitting.
 */
class UnitSprites {
    enum class Anim(val frames: Int) { IDLE(2), WALK(6), ATTACK(4) }

    /** Sprite box per unit class in unit pixels: half width, height above the feet, depth below. */
    private class Box(val ax: Float, val ay: Float, val below: Float) {
        val w get() = ax * 2f
        val h get() = ay + below
    }

    private class Sprite(val bmp: Bitmap, val scale: Float) {
        val bytes = bmp.width * bmp.height * 4
        var used = 0
    }

    private val cache = LongSparseArray<Sprite>(128)
    private var bytes = 0
    /** Monotonic counter stamped on sprites as they are used, for least-recently-used eviction. */
    private var clock = 0
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val painter = Painter()

    /** Approximate memory held by cached sprites, for diagnostics and tests. */
    val cacheBytes get() = bytes
    val cacheSize get() = cache.size()

    // ------------------------------------------------------------------ public drawing

    /**
     * Blits the figure of [type] with its feet at (x, y). [dir] is +1 when facing screen right.
     * [carry] is 0 for nothing, 1..4 for food/wood/gold/stone, 5 for a builder's hammer.
     */
    fun draw(c: Canvas, type: UnitType, age: Age, color: Int, dir: Float, anim: Anim, frame: Int, carry: Int, x: Float, y: Float, s: Float) {
        val bucket = bucketOf(s)
        val rs = scaleOf(bucket)
        val k = key(type.ordinal, age.ordinal, color, dir, anim.ordinal, frame, carry, bucket)
        val sp = cache.get(k) ?: renderSprite(k, type, age, color, dir, anim, frame, carry, rs)
        sp.used = ++clock
        val box = boxOf(type)
        val f = s / rs
        val l = x - (box.ax + PAD) * s
        val t = y - (box.ay + PAD) * s
        dst.set(l, t, l + sp.bmp.width * f, t + sp.bmp.height * f)
        c.drawBitmap(sp.bmp, null, dst, blit)
    }

    /** Soft ground shadow for a unit of [type], centred at its feet. */
    fun drawShadow(c: Canvas, type: UnitType, x: Float, y: Float, s: Float) {
        val kind = shadowKind(type)
        val bucket = bucketOf(s)
        val rs = scaleOf(bucket)
        val k = key(15, kind, 0, 1f, 0, 0, 0, bucket)
        val sp = cache.get(k) ?: renderShadow(k, kind, rs)
        sp.used = ++clock
        val f = s / rs
        val rx = SHADOW_RX[kind] * s
        val ry = SHADOW_RY[kind] * s
        dst.set(x - rx - PAD * s, y - ry - PAD * s, x - rx - PAD * s + sp.bmp.width * f, y - ry - PAD * s + sp.bmp.height * f)
        c.drawBitmap(sp.bmp, null, dst, blit)
    }

    /** Draws a figure directly with vector calls (used for HUD icons, which have their own scale). */
    fun drawDirect(c: Canvas, type: UnitType, age: Age, color: Int, dir: Float, anim: Anim, frame: Int, carry: Int, x: Float, y: Float, s: Float) {
        c.save()
        c.translate(x, y)
        c.scale(s, s)
        painter.draw(c, type, age, color, dir, anim, frame, carry)
        c.restore()
    }

    fun clear() {
        for (i in 0 until cache.size()) cache.valueAt(i).bmp.recycle()
        cache.clear()
        bytes = 0
    }

    // ------------------------------------------------------------------ cache

    private fun bucketOf(s: Float): Int = (ln(max(s, 0.01f)) / ln(2f) * 2f).roundToInt().coerceIn(-2, 3) + 2

    private fun scaleOf(bucket: Int): Float = Math.pow(2.0, (bucket - 2) / 2.0).toFloat()

    private fun key(type: Int, age: Int, color: Int, dir: Float, anim: Int, frame: Int, carry: Int, bucket: Int): Long {
        var k = type.toLong()
        k = k * 8 + age
        k = k * 2 + (if (dir > 0f) 1 else 0)
        k = k * 4 + anim
        k = k * 8 + frame
        k = k * 8 + carry
        k = k * 8 + bucket
        return (k shl 32) or (color.toLong() and 0xFFFFFFFFL)
    }

    private fun put(k: Long, sp: Sprite): Sprite {
        cache.put(k, sp)
        sp.used = ++clock
        bytes += sp.bytes
        if (bytes > BUDGET) evict(k)
        return sp
    }

    /** Drops the least recently used sprites until the cache is comfortably under budget. */
    private fun evict(keep: Long) {
        val n = cache.size()
        val order = ArrayList<Int>(n)
        for (i in 0 until n) order += i
        order.sortBy { cache.valueAt(it).used }
        val doomed = ArrayList<Long>()
        for (i in order) {
            if (bytes <= BUDGET * 3 / 4) break
            val key = cache.keyAt(i)
            if (key == keep) continue
            val sp = cache.valueAt(i)
            bytes -= sp.bytes
            sp.bmp.recycle()
            doomed += key
        }
        for (key in doomed) cache.remove(key)
    }

    private fun renderSprite(k: Long, type: UnitType, age: Age, color: Int, dir: Float, anim: Anim, frame: Int, carry: Int, rs: Float): Sprite {
        val box = boxOf(type)
        val bw = ((box.w + PAD * 2) * rs).toInt().coerceAtLeast(2)
        val bh = ((box.h + PAD * 2) * rs).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate((box.ax + PAD) * rs, (box.ay + PAD) * rs)
        c.scale(rs, rs)
        painter.draw(c, type, age, color, dir, anim, frame, carry)
        return put(k, Sprite(bmp, rs))
    }

    private fun renderShadow(k: Long, kind: Int, rs: Float): Sprite {
        val rx = SHADOW_RX[kind]; val ry = SHADOW_RY[kind]
        val bw = ((rx * 2 + PAD * 2) * rs).toInt().coerceAtLeast(2)
        val bh = ((ry * 2 + PAD * 2) * rs).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = (rx + PAD) * rs; val cy = (ry + PAD) * rs
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(cx, cy, rx * rs, intArrayOf(0x70000000, 0x48000000, 0x00000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.save()
        c.scale(1f, ry / rx, cx, cy)
        c.drawCircle(cx, cy, rx * rs, p)
        c.restore()
        return put(k, Sprite(bmp, rs))
    }

    // ------------------------------------------------------------------ figure painter

    /** Per-age look of soldiers: armour metal, leather, trousers, helmet and shield shapes. */
    private class Style(val metal: Int, val leather: Int, val cloth: Int, val helmet: Int, val shield: Int)

    private class Pose {
        var bob = 0f
        var footF = 0f; var footB = 0f
        var liftF = 0f; var liftB = 0f
        var swing = 0f
        /** Attack progress 0..1, or -1 when not attacking. */
        var atk = -1f
    }

    private inner class Painter {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val path = Path()
        private val rect = RectF()
        private val pose = Pose()
        private lateinit var c: Canvas
        private var dir = 1f
        private var team = 0
        private var age = Age.ANCIENT
        private lateinit var st: Style
        private var anim = Anim.IDLE
        private var frame = 0
        private var hx = 0f
        private var hy = 0f

        fun draw(canvas: Canvas, type: UnitType, age: Age, color: Int, dir: Float, anim: Anim, frame: Int, carry: Int) {
            c = canvas
            this.dir = if (dir < 0f) -1f else 1f
            team = color
            this.age = age
            st = STYLES[age.ordinal]
            this.anim = anim
            this.frame = frame
            computePose(type)
            when (type) {
                UnitType.VILLAGER -> villager(carry)
                UnitType.SPEARMAN -> spearman()
                UnitType.WARRIOR -> warrior()
                UnitType.ARCHER -> archer()
                UnitType.SCOUT, UnitType.HORSEMAN, UnitType.HORSE_ARCHER -> mounted(type)
                UnitType.CATAPULT -> siege()
                UnitType.HEALER -> healer()
            }
        }

        private fun computePose(type: UnitType) {
            val p = pose
            p.bob = 0f; p.footF = 0f; p.footB = 0f; p.liftF = 0f; p.liftB = 0f; p.swing = 0f; p.atk = -1f
            when (anim) {
                Anim.IDLE -> p.bob = if (frame == 1) -0.5f else 0f
                Anim.WALK -> {
                    val ph = frame / Anim.WALK.frames.toFloat() * 2f * PI.toFloat()
                    val stride = if (type == UnitType.CATAPULT) 0f else 3.2f
                    p.footF = sin(ph) * stride
                    p.footB = -p.footF
                    p.liftF = max(0f, cos(ph)) * 2.4f
                    p.liftB = max(0f, -cos(ph)) * 2.4f
                    p.bob = -(1f - abs(sin(ph))) * 1.3f
                    p.swing = sin(ph)
                }
                Anim.ATTACK -> p.atk = frame / (Anim.ATTACK.frames - 1f)
            }
        }

        // -------------------------------------------------------------- primitives

        private fun outlineStroke(w: Float) {
            stroke.color = INK; stroke.strokeWidth = w; stroke.shader = null
        }

        /** A limb or shaft: dark outline, body colour and a thin highlight along the lit side. */
        private fun limb(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, col: Int, hi: Boolean = true) {
            outlineStroke(w + 1.4f)
            c.drawLine(x0, y0, x1, y1, stroke)
            stroke.color = col; stroke.strokeWidth = w
            c.drawLine(x0, y0, x1, y1, stroke)
            if (hi && w >= 2f) {
                stroke.color = shade(col, 1.3f); stroke.strokeWidth = w * 0.35f
                c.drawLine(x0 - 0.35f, y0 - 0.3f, x1 - 0.35f, y1 - 0.3f, stroke)
            }
        }

        private fun thin(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, col: Int) {
            stroke.color = col; stroke.strokeWidth = w; stroke.shader = null
            c.drawLine(x0, y0, x1, y1, stroke)
        }

        /** Sphere-like blob lit from the upper left, with an outline. */
        private fun blob(x: Float, y: Float, rx: Float, ry: Float, col: Int, outline: Boolean = true) {
            if (outline) {
                fill.shader = null; fill.color = INK
                rect.set(x - rx - 0.7f, y - ry - 0.7f, x + rx + 0.7f, y + ry + 0.7f)
                c.drawOval(rect, fill)
            }
            fill.shader = RadialGradient(x - rx * 0.35f, y - ry * 0.4f, max(rx, ry) * 1.35f, intArrayOf(shade(col, 1.35f), col, shade(col, 0.6f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            rect.set(x - rx, y - ry, x + rx, y + ry)
            c.drawOval(rect, fill)
            fill.shader = null
        }

        /** Rounded block shaded left to right, used for torsos, blocks and boxes. */
        private fun block(l: Float, t: Float, r: Float, b: Float, rad: Float, col: Int, outline: Boolean = true) {
            if (outline) {
                fill.shader = null; fill.color = INK
                rect.set(l - 0.7f, t - 0.7f, r + 0.7f, b + 0.7f)
                c.drawRoundRect(rect, rad + 0.5f, rad + 0.5f, fill)
            }
            fill.shader = LinearGradient(l, t, r, b, intArrayOf(shade(col, 1.25f), col, shade(col, 0.68f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            rect.set(l, t, r, b)
            c.drawRoundRect(rect, rad, rad, fill)
            fill.shader = null
        }

        private fun flat(col: Int) { fill.shader = null; fill.color = col }

        private fun poly(col: Int, vararg pts: Float) {
            path.reset()
            path.moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
            path.close()
            flat(col)
            c.drawPath(path, fill)
        }

        private fun polyOutlined(col: Int, vararg pts: Float) {
            poly(col, *pts)
            outlineStroke(1.1f)
            c.drawPath(path, stroke)
        }

        // -------------------------------------------------------------- body parts

        private fun legs(col: Int, boots: Int = BOOT) {
            val hip = -8.5f + pose.bob
            leg(-dir * 1.9f, hip, pose.footB, pose.liftB, shade(col, 0.75f), shade(boots, 0.8f))
            leg(dir * 1.9f, hip, pose.footF, pose.liftF, col, boots)
        }

        private fun leg(hx: Float, hip: Float, foot: Float, lift: Float, col: Int, boot: Int) {
            val fx = hx + foot * dir; val fy = -lift
            val kx = (hx + fx) / 2f + dir * (0.6f + lift * 0.5f); val ky = hip * 0.5f - lift * 0.6f + 0.3f
            limb(hx, hip, kx, ky, 2.5f, col)
            limb(kx, ky, fx, fy - 0.8f, 2.3f, col)
            limb(fx - dir * 0.6f, fy - 1.1f, fx + dir * 1.3f, fy - 0.3f, 2.1f, boot, hi = false)
        }

        private fun torso(top: Float, bottom: Float, w: Float, col: Int) {
            val b = pose.bob
            block(-w, top + b, w, bottom + b, 2.2f, col)
            // Soft sheen across the chest.
            flat(0x28FFFFFF)
            rect.set(-w + 0.9f, top + b + 0.7f, w - 1.4f, top + b + 2.2f)
            c.drawRoundRect(rect, 1f, 1f, fill)
        }

        private fun belt(y: Float, col: Int, w: Float = 4.3f) {
            flat(col)
            rect.set(-w, y + pose.bob, w, y + 1.6f + pose.bob)
            c.drawRect(rect, fill)
            flat(shade(col, 1.6f))
            rect.set(-0.8f, y + pose.bob + 0.2f, 0.8f, y + 1.4f + pose.bob)
            c.drawRect(rect, fill)
        }

        private fun head(hy: Float, hair: Int?) {
            val y = hy + pose.bob
            blob(0f, y, 3.3f, 3.3f, SKIN)
            // Eye and brow towards the facing direction, small mouth shadow.
            flat(0xFF2A1A12.toInt())
            c.drawCircle(dir * 1.5f, y - 0.2f, 0.5f, fill)
            thin(dir * 0.7f, y - 1.2f, dir * 2.2f, y - 1.3f, 0.5f, 0xFF5A3A2A.toInt())
            flat(0x30000000)
            c.drawCircle(dir * 1.2f, y + 1.6f, 0.5f, fill)
            if (hair != null) {
                flat(hair)
                rect.set(-3.5f, y - 3.7f, 3.5f, y + 0.6f)
                c.drawArc(rect, 180f, 180f, true, fill)
                flat(shade(hair, 1.4f))
                rect.set(-2.4f, y - 3.2f, 0.6f, y - 1.2f)
                c.drawArc(rect, 200f, 80f, true, fill)
            }
        }

        private fun helmet(hy: Float, kind: Int, metal: Int = st.metal) {
            val y = hy + pose.bob
            when (kind) {
                HELM_CAP -> {
                    flat(st.leather)
                    rect.set(-3.6f, y - 3.9f, 3.6f, y + 0.4f)
                    c.drawArc(rect, 180f, 180f, true, fill)
                    outlineStroke(0.9f); c.drawArc(rect, 180f, 180f, false, stroke)
                    thin(-3.5f, y - 1.2f, 3.5f, y - 1.2f, 1.1f, shade(st.leather, 0.65f))
                    flat(0x40FFFFFF); rect.set(-2.6f, y - 3.4f, 0.4f, y - 1.6f); c.drawArc(rect, 200f, 70f, true, fill)
                }
                HELM_CREST -> {
                    dome(y, metal)
                    // Cheek guard and a team-coloured horsehair crest.
                    thin(dir * 2.2f, y - 1.5f, dir * 2.6f, y + 2f, 1.6f, shade(metal, 0.85f))
                    path.reset()
                    path.moveTo(-dir * 3.5f, y - 2.5f)
                    path.quadTo(-dir * 1f, y - 8.5f, dir * 3.2f, y - 5.6f)
                    path.lineTo(dir * 2.4f, y - 3.4f)
                    path.quadTo(-dir * 0.5f, y - 6.2f, -dir * 2.8f, y - 1.6f)
                    path.close()
                    flat(team); c.drawPath(path, fill)
                    outlineStroke(0.9f); c.drawPath(path, stroke)
                }
                HELM_NASAL -> {
                    dome(y, metal)
                    thin(dir * 1.5f, y - 1f, dir * 1.5f, y + 1.8f, 1.2f, shade(metal, 0.8f))
                    // Mail coif around the neck.
                    flat(shade(metal, 0.72f))
                    rect.set(-3.6f, y - 1f, 3.6f, y + 3.6f)
                    c.drawArc(rect, 0f, 180f, true, fill)
                    flat(SKIN); c.drawCircle(dir * 0.3f, y + 0.4f, 2.1f, fill)
                    flat(0xFF2A1A12.toInt()); c.drawCircle(dir * 1.5f, y - 0.2f, 0.5f, fill)
                }
                HELM_MORION -> {
                    dome(y, metal)
                    thin(-4.6f, y - 0.4f, 4.6f, y - 0.4f, 1.3f, shade(metal, 0.9f))
                    thin(-0.5f, y - 7f, 0.5f, y - 3.2f, 1.4f, shade(metal, 1.1f))
                }
                HELM_TRICORNE -> {
                    flat(0xFF2A2420.toInt())
                    rect.set(-3.6f, y - 4.4f, 3.6f, y - 0.6f); c.drawArc(rect, 180f, 180f, true, fill)
                    poly(0xFF2A2420.toInt(), -5.4f, y - 2.4f, 5.2f, y - 2.6f, 4f, y - 1.2f, -4.2f, y - 1f)
                    outlineStroke(0.9f); c.drawPath(path, stroke)
                    thin(-4.8f, y - 2.2f, 4.6f, y - 2.4f, 0.7f, team)
                }
                HELM_KEPI -> {
                    block(-3.2f, y - 5.4f, 3.2f, y - 2.2f, 0.8f, team)
                    poly(0xFF1E1A18.toInt(), dir * 0.5f, y - 2.4f, dir * 4.4f, y - 1.8f, dir * 4.2f, y - 1f, dir * 0.5f, y - 1.4f)
                    thin(-3.2f, y - 3.2f, 3.2f, y - 3.2f, 0.7f, shade(team, 0.6f))
                }
                HELM_PLUME -> {
                    dome(y, metal)
                    thin(dir * 0.8f, y - 3.6f, -dir * 4.6f, y - 7.2f, 2.2f, team)
                    thin(dir * 0.6f, y - 3.4f, -dir * 3.8f, y - 6.4f, 0.9f, shade(team, 1.4f))
                }
                HELM_MITRE -> {
                    poly(team, -3f, y - 2f, 3f, y - 2f, 1.6f, y - 10.5f, -1.6f, y - 10.5f)
                    outlineStroke(0.9f); c.drawPath(path, stroke)
                    flat(st.metal); c.drawCircle(0f, y - 6.2f, 1.4f, fill)
                }
            }
        }

        private fun dome(y: Float, metal: Int) {
            fill.shader = RadialGradient(-1.2f, y - 3f, 5f, intArrayOf(shade(metal, 1.4f), metal, shade(metal, 0.62f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            rect.set(-3.7f, y - 4.1f, 3.7f, y + 0.6f)
            c.drawArc(rect, 180f, 180f, true, fill)
            fill.shader = null
            outlineStroke(0.9f); c.drawArc(rect, 180f, 180f, false, stroke)
        }

        /** Chest armour over the tunic for the age: leather straps, bronze cuirass, mail, breastplate or a coat. */
        private fun armor(top: Float, w: Float) {
            val b = pose.bob
            when (age) {
                Age.ANCIENT -> {
                    thin(-w + 0.8f, top + b, w * 0.1f, top + b + 9f, 1.4f, st.leather)
                    thin(w - 0.8f, top + b, -w * 0.1f, top + b + 9f, 1.4f, st.leather)
                }
                Age.CLASSICAL -> {
                    block(-w + 0.3f, top + b + 0.3f, w - 0.3f, top + b + 7f, 1.8f, st.metal, outline = false)
                    thin(-w + 1.5f, top + b + 7.2f, w - 1.5f, top + b + 7.2f, 1f, shade(st.metal, 0.7f))
                    flat(0x30FFFFFF); c.drawCircle(-1.2f, top + b + 3f, 1.2f, fill)
                }
                Age.MEDIEVAL -> {
                    block(-w + 0.3f, top + b + 0.3f, w - 0.3f, top + b + 7.5f, 1.8f, st.metal, outline = false)
                    // Mail rings.
                    flat(0x40000000)
                    var yy = top + b + 2f
                    while (yy < top + b + 7f) { var xx = -w + 1.4f; while (xx < w - 0.8f) { c.drawCircle(xx, yy, 0.45f, fill); xx += 1.4f }; yy += 1.4f }
                    thin(0f, top + b + 0.5f, 0f, top + b + 7.5f, 1.2f, team)
                }
                Age.GUNPOWDER -> {
                    block(-w + 0.3f, top + b + 0.3f, w - 0.3f, top + b + 8f, 2f, st.metal, outline = false)
                    thin(-w + 1f, top + b + 1f, -w + 1f, top + b + 8f, 0.9f, shade(st.metal, 1.5f))
                    thin(0f, top + b + 1f, 0f, top + b + 8f, 1.1f, shade(st.metal, 0.6f))
                }
                Age.INDUSTRIAL -> {
                    // Uniform coat: lapels and a row of buttons.
                    thin(-dir * 1.6f, top + b + 0.6f, 0f, top + b + 4f, 1.2f, shade(team, 0.55f))
                    thin(dir * 1.6f, top + b + 0.6f, 0f, top + b + 4f, 1.2f, shade(team, 0.55f))
                    flat(0xFFE8D070.toInt())
                    for (i in 0 until 3) c.drawCircle(0f, top + b + 4.5f + i * 2f, 0.55f, fill)
                    thin(-w + 0.4f, top + b + 0.4f, -w + 0.4f, top + b + 3f, 1.6f, 0xFFE8D070.toInt())
                    thin(w - 0.4f, top + b + 0.4f, w - 0.4f, top + b + 3f, 1.6f, 0xFFE8D070.toInt())
                }
            }
        }

        /** Draws an arm from (sx, sy) towards (tx, ty) with the given reach; sets [hx], [hy] to the hand. */
        private fun armTo(sx: Float, sy: Float, tx: Float, ty: Float, len: Float, sleeve: Int) {
            val dx = tx - sx; val dy = ty - sy
            val d = max(0.01f, sqrt(dx * dx + dy * dy))
            hx = sx + dx / d * len; hy = sy + dy / d * len
            // Slight elbow bend away from the direction of travel.
            val ex = (sx + hx) / 2f - dy / d * 0.9f * dir; val ey = (sy + hy) / 2f + dx / d * 0.9f * dir
            limb(sx, sy, ex, ey, 2.2f, sleeve)
            limb(ex, ey, hx, hy, 2.1f, sleeve)
            flat(SKIN); c.drawCircle(hx, hy, 1.25f, fill)
        }

        private fun armAngle(sx: Float, sy: Float, a: Float, len: Float, sleeve: Int) =
            armTo(sx, sy, sx + dir * cos(a) * len, sy - sin(a) * len, len, sleeve)

        private fun shield(x: Float, y: Float, kind: Int, big: Boolean) {
            val r = if (big) 5f else 4.2f
            when (kind) {
                SHIELD_HIDE -> {
                    blob(x, y, r, r, shade(st.leather, 1.1f))
                    thin(x - r * 0.6f, y, x + r * 0.6f, y, 1f, team)
                    thin(x, y - r * 0.6f, x, y + r * 0.6f, 1f, team)
                    flat(st.metal); c.drawCircle(x, y, 1.2f, fill)
                }
                SHIELD_ROUND -> {
                    blob(x, y, r, r, team)
                    outlineStroke(1f); stroke.color = shade(st.metal, 0.9f); c.drawCircle(x, y, r - 0.9f, stroke)
                    flat(st.metal); c.drawCircle(x, y, 1.4f, fill)
                    flat(0x50FFFFFF); c.drawCircle(x - 0.5f, y - 0.5f, 0.6f, fill)
                }
                SHIELD_KITE -> {
                    path.reset()
                    path.moveTo(x - r, y - r * 0.9f)
                    path.quadTo(x, y - r * 1.5f, x + r, y - r * 0.9f)
                    path.quadTo(x + r * 0.9f, y + r * 0.5f, x, y + r * 1.5f)
                    path.quadTo(x - r * 0.9f, y + r * 0.5f, x - r, y - r * 0.9f)
                    path.close()
                    fill.shader = LinearGradient(x - r, y - r, x + r, y + r, intArrayOf(shade(team, 1.3f), team, shade(team, 0.65f)), null, Shader.TileMode.CLAMP)
                    c.drawPath(path, fill); fill.shader = null
                    outlineStroke(1.1f); c.drawPath(path, stroke)
                    thin(x, y - r * 1.1f, x, y + r * 1.1f, 1.1f, shade(st.metal, 1.1f))
                    thin(x - r * 0.7f, y - r * 0.2f, x + r * 0.7f, y - r * 0.2f, 1.1f, shade(st.metal, 1.1f))
                }
            }
        }

        // -------------------------------------------------------------- weapons

        private fun spear(x: Float, y: Float, tilt: Float) {
            // Shaft through the hand, tip at the top.
            val bx = x - dir * (1.2f + tilt * 0.5f); val by = y + 12f
            val tx = x + dir * (1.4f + tilt * 3f); val ty = y - 21f + tilt * 1.5f
            limb(bx, by, tx, ty, 1.5f, WOOD, hi = false)
            val dx = tx - bx; val dy = ty - by; val d = sqrt(dx * dx + dy * dy)
            val ux = dx / d; val uy = dy / d
            val px = -uy; val py = ux
            polyOutlined(shade(st.metal, 1.25f),
                tx - ux * 1f + px * 1.6f, ty - uy * 1f + py * 1.6f,
                tx + ux * 5.5f, ty + uy * 5.5f,
                tx - ux * 1f - px * 1.6f, ty - uy * 1f - py * 1.6f)
            thin(tx, ty, tx + ux * 4f, ty + uy * 4f, 0.5f, 0xB0FFFFFF.toInt())
        }

        private fun sword(x: Float, y: Float, a: Float, len: Float) {
            val ux = dir * cos(a); val uy = -sin(a)
            // Cross guard and grip.
            limb(x - ux * 2.5f, y - uy * 2.5f, x + ux * 1f, y + uy * 1f, 1.6f, st.leather, hi = false)
            thin(x + ux * 1f - uy * 2.2f, y + uy * 1f + ux * 2.2f, x + ux * 1f + uy * 2.2f, y + uy * 1f - ux * 2.2f, 1.4f, st.metal)
            val tx = x + ux * len; val ty = y + uy * len
            polyOutlined(shade(st.metal, 1.3f),
                x + ux * 1.5f - uy * 1.1f, y + uy * 1.5f + ux * 1.1f,
                tx + ux * 0f - uy * 0.2f, ty - uy * 0f + ux * 0.2f,
                tx + ux * 2.5f, ty + uy * 2.5f,
                x + ux * 1.5f + uy * 1.1f, y + uy * 1.5f - ux * 1.1f)
            thin(x + ux * 2f, y + uy * 2f, tx, ty, 0.5f, 0xC0FFFFFF.toInt())
        }

        private fun axe(x: Float, y: Float, a: Float, len: Float, metal: Int, double: Boolean) {
            val ux = dir * cos(a); val uy = -sin(a)
            limb(x - ux * 3f, y - uy * 3f, x + ux * len, y + uy * len, 1.4f, WOOD, hi = false)
            val tx = x + ux * (len - 1.5f); val ty = y + uy * (len - 1.5f)
            val px = -uy; val py = ux
            polyOutlined(metal,
                tx - ux * 2f + px * 0.6f, ty - uy * 2f + py * 0.6f,
                tx - ux * 2.6f + px * 4.2f, ty - uy * 2.6f + py * 4.2f,
                tx + ux * 2.6f + px * 4.2f, ty + uy * 2.6f + py * 4.2f,
                tx + ux * 2f + px * 0.6f, ty + uy * 2f + py * 0.6f)
            if (double) polyOutlined(metal,
                tx - ux * 2f - px * 0.6f, ty - uy * 2f - py * 0.6f,
                tx - ux * 2.6f - px * 4.2f, ty - uy * 2.6f - py * 4.2f,
                tx + ux * 2.6f - px * 4.2f, ty + uy * 2.6f - py * 4.2f,
                tx + ux * 2f - px * 0.6f, ty + uy * 2f - py * 0.6f)
        }

        private fun pickaxe(x: Float, y: Float, a: Float, len: Float) {
            val ux = dir * cos(a); val uy = -sin(a)
            limb(x - ux * 3f, y - uy * 3f, x + ux * len, y + uy * len, 1.4f, WOOD, hi = false)
            val tx = x + ux * (len - 1f); val ty = y + uy * (len - 1f)
            val px = -uy; val py = ux
            path.reset()
            path.moveTo(tx - px * 5.5f, ty - py * 5.5f)
            path.quadTo(tx + ux * 1.5f, ty + uy * 1.5f, tx + px * 5.5f, ty + py * 5.5f)
            path.quadTo(tx - ux * 1f, ty - uy * 1f, tx - px * 5.5f, ty - py * 5.5f)
            path.close()
            flat(0xFF8A8E98.toInt()); c.drawPath(path, fill)
            outlineStroke(1f); c.drawPath(path, stroke)
        }

        private fun hammer(x: Float, y: Float, a: Float) {
            val ux = dir * cos(a); val uy = -sin(a)
            limb(x - ux * 2.5f, y - uy * 2.5f, x + ux * 7f, y + uy * 7f, 1.3f, WOOD, hi = false)
            val tx = x + ux * 6.5f; val ty = y + uy * 6.5f
            val px = -uy; val py = ux
            polyOutlined(0xFF7A7E88.toInt(),
                tx - ux * 1.4f + px * 2.8f, ty - uy * 1.4f + py * 2.8f,
                tx + ux * 1.4f + px * 2.8f, ty + uy * 1.4f + py * 2.8f,
                tx + ux * 1.4f - px * 2.8f, ty + uy * 1.4f - py * 2.8f,
                tx - ux * 1.4f - px * 2.8f, ty - uy * 1.4f - py * 2.8f)
        }

        private fun sickle(x: Float, y: Float, a: Float) {
            val ux = dir * cos(a); val uy = -sin(a)
            limb(x - ux * 2f, y - uy * 2f, x + ux * 4f, y + uy * 4f, 1.3f, WOOD, hi = false)
            val tx = x + ux * 4f; val ty = y + uy * 4f
            stroke.color = INK; stroke.strokeWidth = 2.6f
            rect.set(tx - 4f, ty - 4f, tx + 4f, ty + 4f)
            val start = (atan2(uy, ux) * 180f / PI.toFloat()) - 90f
            c.drawArc(rect, start, 160f * (if (dir > 0) 1f else -1f) * (if (uy > 0) -1f else 1f), false, stroke)
            stroke.color = 0xFFC8CCD4.toInt(); stroke.strokeWidth = 1.3f
            c.drawArc(rect, start, 160f * (if (dir > 0) 1f else -1f) * (if (uy > 0) -1f else 1f), false, stroke)
        }

        /** Bow held in the hand at (x, y); [draw] 0..1 pulls the string back to (bx, by). */
        private fun bow(x: Float, y: Float, draw: Float, bx: Float, by: Float, arrow: Boolean) {
            val r = 8.5f
            stroke.color = INK; stroke.strokeWidth = 2.6f; stroke.shader = null
            rect.set(x - r * 0.55f, y - r, x + r * 0.55f, y + r)
            val start = if (dir > 0) -90f else 90f
            c.drawArc(rect, start, 180f, false, stroke)
            stroke.color = WOOD; stroke.strokeWidth = 1.4f
            c.drawArc(rect, start, 180f, false, stroke)
            stroke.color = shade(WOOD, 1.4f); stroke.strokeWidth = 0.5f
            c.drawArc(rect, start, 180f, false, stroke)
            // String from both bow tips to the draw point.
            val tipTop = y - r; val tipBot = y + r
            val sxp = x + (bx - x) * draw; val syp = y + (by - y) * draw
            thin(x, tipTop, sxp, syp, 0.6f, 0xFFF0E8D0.toInt())
            thin(x, tipBot, sxp, syp, 0.6f, 0xFFF0E8D0.toInt())
            if (arrow) {
                thin(sxp, syp, sxp + dir * 11f, syp - 0.3f, 0.9f, 0xFF6A4A2A.toInt())
                thin(sxp + dir * 10.5f, syp - 0.3f, sxp + dir * 12.5f, syp - 0.4f, 1.3f, 0xFFD0D4DC.toInt())
                thin(sxp, syp, sxp + dir * 1.5f, syp - 1.4f, 0.8f, team)
            }
        }

        private fun crossbow(x: Float, y: Float, draw: Float, flash: Boolean) {
            // Stock along the aim, bow across it.
            limb(x - dir * 6f, y + 1.5f, x + dir * 8f, y - 0.5f, 2.2f, WOOD, hi = false)
            limb(x + dir * 7f, y - 4.5f, x + dir * 7f, y + 4f, 1.5f, shade(st.metal, 0.9f), hi = false)
            val sx = x + dir * (7f - 5f * draw)
            thin(x + dir * 7f, y - 4.5f, sx, y - 0.4f, 0.6f, 0xFFF0E8D0.toInt())
            thin(x + dir * 7f, y + 4f, sx, y - 0.4f, 0.6f, 0xFFF0E8D0.toInt())
            if (flash) thin(x + dir * 7.5f, y - 0.6f, x + dir * 14f, y - 1f, 1f, 0xFFD0D4DC.toInt())
        }

        /** Musket or rifle held two-handed from (x, y) at the trigger, pointing forward with [tilt]. */
        private fun gun(x: Float, y: Float, tilt: Float, len: Float, flash: Boolean, bayonet: Boolean) {
            val ux = dir * cos(tilt); val uy = -sin(tilt)
            // Stock behind, barrel ahead.
            limb(x - ux * 5f, y - uy * 5f + 1.5f, x + ux * 2f, y + uy * 2f, 2.6f, 0xFF4A3220.toInt(), hi = false)
            limb(x + ux * 1f, y + uy * 1f, x + ux * len, y + uy * len, 1.6f, shade(st.metal, 0.75f), hi = false)
            thin(x + ux * 2f, y + uy * 2f - 0.5f, x + ux * (len - 1f), y + uy * (len - 1f) - 0.5f, 0.5f, shade(st.metal, 1.5f))
            if (bayonet) thin(x + ux * len, y + uy * len - 0.3f, x + ux * (len + 4.5f), y + uy * (len + 4.5f) - 0.5f, 1.1f, 0xFFD8DCE4.toInt())
            if (flash) {
                val fx = x + ux * (len + 1.5f); val fy = y + uy * (len + 1.5f)
                flat(0x90FFE080.toInt()); c.drawCircle(fx, fy, 3.8f, fill)
                flat(0xFFFFF4C0.toInt()); c.drawCircle(fx, fy, 1.8f, fill)
                thin(fx, fy, fx + ux * 5f, fy + uy * 5f, 1.4f, 0xE0FFE8A0.toInt())
                flat(0x50A0A0A8); c.drawCircle(fx + ux * 3f, fy + uy * 3f - 2f, 3f, fill)
            }
        }

        private fun quiver() {
            val b = pose.bob
            val qx = -dir * 4.5f
            block(qx - 1.6f, -19f + b, qx + 1.6f, -10f + b, 1.2f, st.leather)
            for (i in -1..1) thin(qx + i * 0.9f, -19f + b, qx + i * 1.1f, -23f + b, 0.8f, 0xFF6A4A2A.toInt())
            for (i in -1..1) { flat(team); c.drawCircle(qx + i * 1.1f, -23.3f + b, 0.7f, fill) }
        }

        // -------------------------------------------------------------- units

        private fun villager(carry: Int) {
            val b = pose.bob
            val tunic = shade(team, 0.92f)
            val working = pose.atk >= 0f
            legs(0xFF6A5A48.toInt(), 0xFF4A3424.toInt())
            // Back arm: either carrying something over the shoulder or hanging.
            val bsx = -dir * 3.4f; val sy = -16.5f + b
            if (!working && carry in 1..4) {
                armTo(bsx, sy, bsx - dir * 2f, sy - 4f, 5.5f, tunic)
            } else {
                armAngle(bsx, sy, -1.35f + pose.swing * 0.5f, 6.5f, tunic)
            }
            torso(-18.5f, -7.5f, 4.1f, tunic)
            // Apron in undyed cloth so citizens read differently from soldiers.
            flat(0xFFD8CBB0.toInt())
            rect.set(-2.6f, -15f + b, 2.6f, -8f + b)
            c.drawRoundRect(rect, 1f, 1f, fill)
            outlineStroke(0.7f); c.drawRoundRect(rect, 1f, 1f, stroke)
            belt(-10.5f, 0xFF6A4A2A.toInt(), 4.1f)
            head(-21.8f, HAIR)
            // Headwear by age: straw hat, hood, flat cap.
            val hy = -21.8f + b
            when {
                age >= Age.GUNPOWDER -> {
                    poly(0xFF5A4A3A.toInt(), -3.8f, hy - 2.2f, 3.8f, hy - 2.4f, 3.2f, hy - 4.4f, -2.8f, hy - 4.4f)
                    outlineStroke(0.8f); c.drawPath(path, stroke)
                    poly(0xFF3A2E24.toInt(), dir * 1f, hy - 2.2f, dir * 4.6f, hy - 2f, dir * 4.4f, hy - 1.2f, dir * 1f, hy - 1.4f)
                }
                age >= Age.MEDIEVAL -> {
                    flat(shade(team, 0.7f))
                    rect.set(-3.8f, hy - 4.2f, 3.8f, hy + 1.2f); c.drawArc(rect, 170f, 200f, true, fill)
                    outlineStroke(0.8f); c.drawArc(rect, 170f, 200f, false, stroke)
                    flat(SKIN); c.drawCircle(dir * 0.4f, hy + 0.2f, 2.3f, fill)
                    flat(0xFF2A1A12.toInt()); c.drawCircle(dir * 1.5f, hy - 0.2f, 0.5f, fill)
                }
                else -> {
                    blob(0f, hy - 3f, 2.9f, 1.7f, 0xFFD2B068.toInt())
                    flat(0xFFD2B068.toInt())
                    rect.set(-5.4f, hy - 3.2f, 5.4f, hy - 1.2f); c.drawOval(rect, fill)
                    outlineStroke(0.8f); c.drawOval(rect, stroke)
                    thin(-2.6f, hy - 2.6f, 2.6f, hy - 2.6f, 0.9f, shade(team, 0.8f))
                }
            }
            if (!working && carry in 1..4) {
                // Goods carried over the back shoulder.
                val gx = -dir * 6.5f; val gy = -17.5f + b
                when (carry) {
                    1 -> {
                        block(gx - 3.2f, gy - 2.2f, gx + 3.2f, gy + 2.8f, 1.4f, 0xFFC8A060.toInt())
                        flat(0xFFD04A3A.toInt()); for (i in -1..1) c.drawCircle(gx + i * 1.6f, gy - 2.2f, 1.1f, fill)
                        flat(0xFF8AC050.toInt()); c.drawCircle(gx + 0.8f, gy - 3f, 0.8f, fill)
                    }
                    2 -> {
                        for (i in 0 until 3) {
                            val ly = gy - 1.6f + i * 1.9f
                            limb(gx - 5.5f, ly + 0.8f, gx + 4.5f, ly - 1.4f, 2f, shade(WOOD, 0.9f + i * 0.1f))
                            flat(0xFFD8B080.toInt()); c.drawCircle(gx - 5.5f, ly + 0.8f, 0.8f, fill)
                        }
                    }
                    3 -> {
                        blob(gx, gy + 0.5f, 3.4f, 3f, 0xFFB08A50.toInt())
                        thin(gx - 1.2f, gy - 2.6f, gx + 1.2f, gy - 2.6f, 1.2f, 0xFF6A4A2A.toInt())
                        flat(0xFFF4D040.toInt()); c.drawCircle(gx - 1f, gy + 0.6f, 1f, fill); c.drawCircle(gx + 1.2f, gy, 0.8f, fill)
                    }
                    else -> {
                        block(gx - 3.4f, gy - 2.6f, gx + 3.4f, gy + 2.6f, 0.8f, 0xFFA8A8B0.toInt())
                        thin(gx - 2f, gy - 1f, gx + 1f, gy + 1.5f, 0.6f, 0x50000000)
                    }
                }
            }
            // Front arm with a tool while working, or swinging.
            val fsx = dir * 3.4f
            if (working) {
                val p = pose.atk
                // Raise and strike: up at p = 0, down at p = 1.
                val a = 1.9f - p * 2.1f
                armAngle(fsx, sy, a, 6.5f, tunic)
                when (carry) {
                    2 -> axe(hx, hy, a + 0.3f, 8f, 0xFF9A9EA8.toInt(), false)
                    3, 4 -> pickaxe(hx, hy, a + 0.3f, 8.5f)
                    5 -> hammer(hx, hy, a + 0.2f)
                    1 -> sickle(hx, hy, a - 0.2f)
                    else -> axe(hx, hy, a + 0.3f, 8f, 0xFF9A9EA8.toInt(), false)
                }
            } else {
                armAngle(fsx, sy, -1.35f - pose.swing * 0.5f, 6.5f, tunic)
            }
        }

        private fun spearman() {
            val b = pose.bob
            legs(st.cloth)
            val sy = -16.5f + b
            // Back arm with shield, then body on top, then the shield proper.
            armAngle(-dir * 3.5f, sy, -1f, 5.5f, team)
            val shx = hx; val shy = hy
            torso(-19f, -7.5f, 4.3f, team)
            armor(-19f, 4.3f)
            belt(-10.5f, st.leather)
            head(-22.3f, HAIR)
            helmet(-22.3f, when (age) { Age.ANCIENT -> HELM_CAP; Age.CLASSICAL -> HELM_CREST; Age.MEDIEVAL -> HELM_NASAL; Age.GUNPOWDER -> HELM_TRICORNE; Age.INDUSTRIAL -> HELM_KEPI })
            if (st.shield != SHIELD_NONE) shield(shx - dir * 0.5f, shy - 1.5f, st.shield, false)
            val p = pose.atk
            if (age >= Age.GUNPOWDER) {
                val thrust = if (p >= 0f) sin(p * PI.toFloat()) * 5f else 0f
                armTo(dir * 3.5f, sy, dir * 9f, sy + 2f, 6f, team)
                gun(hx + dir * thrust, hy, 0.15f, 11f, false, true)
                flat(SKIN); c.drawCircle(hx + dir * thrust - dir * 4f, hy + 1.2f, 1.2f, fill)
            } else {
                val jab = if (p >= 0f) sin(p * PI.toFloat()) * 5.5f else 0f
                val tilt = if (p >= 0f) sin(p * PI.toFloat()) else 0f
                armTo(dir * 3.5f, sy, dir * (6.5f + jab), sy + 2f, 5.5f + jab * 0.4f, team)
                spear(hx, hy, tilt)
            }
        }

        private fun warrior() {
            val b = pose.bob
            legs(st.cloth)
            val sy = -16.5f + b
            armAngle(-dir * 3.5f, sy, -0.9f, 5.5f, team)
            val shx = hx; val shy = hy
            torso(-19.5f, -7.5f, 4.5f, team)
            armor(-19.5f, 4.5f)
            belt(-10.5f, st.leather, 4.5f)
            if (age >= Age.GUNPOWDER) thin(-dir * 4.5f, -19f + b, dir * 4.5f, -9f + b, 2f, shade(team, 1.35f)) // sash
            head(-22.8f, HAIR)
            helmet(-22.8f, when (age) { Age.ANCIENT -> HELM_CAP; Age.CLASSICAL -> HELM_CREST; Age.MEDIEVAL -> HELM_NASAL; Age.GUNPOWDER -> HELM_MITRE; Age.INDUSTRIAL -> HELM_KEPI })
            if (st.shield != SHIELD_NONE) shield(shx - dir * 0.3f, shy - 1.5f, if (st.shield == SHIELD_HIDE) SHIELD_HIDE else st.shield, true)
            // Sword swing: raised behind the head, then brought down in front.
            val p = pose.atk
            val a = if (p >= 0f) 2.3f - p * 2.9f else 0.9f
            armAngle(dir * 3.5f, sy, a, 6f, team)
            when (age) {
                Age.ANCIENT -> axe(hx, hy, a, 7.5f, st.metal, true)
                Age.CLASSICAL -> sword(hx, hy, a, 8.5f)
                else -> sword(hx, hy, a, 11f)
            }
        }

        private fun archer() {
            val b = pose.bob
            legs(st.cloth)
            val sy = -16.5f + b
            quiver()
            torso(-18.5f, -7.5f, 3.9f, shade(team, 0.95f))
            if (age >= Age.MEDIEVAL) armor(-18.5f, 3.9f) else {
                thin(-dir * 3.5f, -18f + b, dir * 3.5f, -9f + b, 1.6f, st.leather) // baldric
            }
            belt(-10.5f, st.leather, 3.9f)
            head(-21.8f, HAIR)
            when (age) {
                Age.ANCIENT -> {}
                Age.CLASSICAL -> helmet(-21.8f, HELM_CAP)
                Age.MEDIEVAL -> helmet(-21.8f, HELM_MORION, shade(st.metal, 0.9f))
                Age.GUNPOWDER -> helmet(-21.8f, HELM_TRICORNE)
                Age.INDUSTRIAL -> helmet(-21.8f, HELM_KEPI)
            }
            val p = pose.atk
            when {
                age >= Age.GUNPOWDER -> {
                    val flash = p >= 0.3f && p < 0.8f
                    val kick = if (p >= 0.6f) 1.5f else 0f
                    armTo(-dir * 3.5f, sy, dir * 6f, sy - 1f, 7f, team)
                    gun(dir * 2f - dir * kick, sy - 0.5f, 0.1f, if (age == Age.INDUSTRIAL) 12f else 10.5f, flash, age == Age.INDUSTRIAL)
                    armTo(dir * 3.5f, sy, dir * 1f, sy + 3f, 4f, team)
                }
                age == Age.MEDIEVAL -> {
                    val draw = if (p < 0f) 0.3f else if (p < 0.5f) 1f else 0f
                    armTo(-dir * 3.5f, sy, dir * 5f, sy - 0.5f, 7f, team)
                    crossbow(dir * 2f, sy, draw, p >= 0.5f && p < 0.8f)
                    armTo(dir * 3.5f, sy, dir * 1.5f, sy + 3f, 3.5f, team)
                }
                else -> {
                    // Draw the string with the back hand, release at the third frame.
                    val draw = if (p < 0f) 0f else if (p < 0.6f) 0.4f + p else 0f
                    val bowX = dir * 7f; val bowY = sy + 1f
                    val bx = -dir * 1.5f; val by = sy - 0.5f
                    armTo(dir * 3.5f, sy, bowX, bowY, 5.5f, team)
                    bow(hx, hy, draw, bx, by, p >= 0f && p < 0.6f)
                    if (draw > 0f) armTo(-dir * 3.5f, sy, hx + (bx - hx) * draw, hy + (by - hy) * draw, 4.5f + draw * 1f, team)
                    else armAngle(-dir * 3.5f, sy, -1.1f, 5.5f, team)
                }
            }
        }

        private fun healer() {
            val b = pose.bob
            val robe = when (age) { Age.GUNPOWDER -> 0xFF2A2630.toInt(); Age.INDUSTRIAL -> 0xFF8A8E98.toInt(); else -> 0xFFF0EAD8.toInt() }
            // Long robe down to the feet, swaying with the walk.
            val sway = pose.swing * 0.8f
            path.reset()
            path.moveTo(-4.4f, -18f + b); path.lineTo(4.4f, -18f + b)
            path.quadTo(6.4f + sway, -8f + b * 0.5f, 6.6f + sway, 0.5f)
            path.lineTo(-6.6f + sway, 0.5f)
            path.quadTo(-6.4f + sway, -8f + b * 0.5f, -4.4f, -18f + b)
            path.close()
            fill.shader = LinearGradient(-6f, 0f, 6f, 0f, intArrayOf(shade(robe, 1.15f), robe, shade(robe, 0.68f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(path, fill); fill.shader = null
            outlineStroke(1.1f); c.drawPath(path, stroke)
            // Team-coloured stole down the front, hem band.
            thin(-dir * 1.6f, -17f + b, -dir * 2.4f, -3f + b * 0.3f, 1.7f, team)
            thin(dir * 1.6f, -17f + b, dir * 2.4f, -3f + b * 0.3f, 1.7f, team)
            thin(-5.8f + sway, -1f, 5.8f + sway, -1f, 1.2f, shade(team, 0.8f))
            val sy = -16.5f + b
            armAngle(-dir * 3.3f, sy, -1.2f, 6f, robe)
            // Rope belt.
            thin(-4.2f, -10.5f + b, 4.2f, -10.5f + b, 1.1f, 0xFFB09060.toInt())
            head(-21.5f, if (age >= Age.GUNPOWDER) HAIR else null)
            val hy = -21.5f + b
            when {
                age == Age.INDUSTRIAL -> {
                    helmet(-21.5f, HELM_KEPI)
                    // Armband with a white cross.
                    thin(-dir * 4.6f, -15.5f + b, -dir * 3.8f, -13f + b, 2.4f, team)
                    thin(-dir * 4.2f, -15.4f + b, -dir * 4.2f, -13.2f + b, 0.7f, 0xFFFFFFFF.toInt())
                    thin(-dir * 5.2f, -14.3f + b, -dir * 3.2f, -14.3f + b, 0.7f, 0xFFFFFFFF.toInt())
                }
                age == Age.GUNPOWDER -> {
                    poly(0xFF1E1A18.toInt(), -3.4f, hy - 3f, 3.4f, hy - 3f, 2.6f, hy - 8f, -2.6f, hy - 8f)
                    poly(0xFF1E1A18.toInt(), -5.2f, hy - 2.4f, 5.2f, hy - 2.4f, 5f, hy - 3.4f, -5f, hy - 3.4f)
                    thin(-3f, hy + 2.6f, 3f, hy + 2.6f, 1.6f, 0xFFF0F0F0.toInt()) // collar
                }
                age == Age.MEDIEVAL -> {
                    // Tonsure and cowl.
                    flat(HAIR); rect.set(-3.5f, hy - 3.6f, 3.5f, hy + 0.5f); c.drawArc(rect, 180f, 180f, true, fill)
                    flat(SKIN); c.drawCircle(0f, hy - 2.3f, 1.6f, fill)
                    flat(robe); rect.set(-4.6f, hy - 1f, 4.6f, hy + 4f); c.drawArc(rect, 0f, 180f, true, fill)
                }
                else -> {
                    // Hood with a team-coloured band.
                    flat(robe); rect.set(-3.9f, hy - 4.2f, 3.9f, hy + 1.6f); c.drawArc(rect, 160f, 220f, true, fill)
                    outlineStroke(0.8f); c.drawArc(rect, 160f, 220f, false, stroke)
                    flat(SKIN); c.drawCircle(dir * 0.5f, hy + 0.4f, 2.3f, fill)
                    flat(0xFF2A1A12.toInt()); c.drawCircle(dir * 1.6f, hy - 0.1f, 0.5f, fill)
                    thin(-3.6f, hy - 2.4f, 3.6f, hy - 2.4f, 1f, team)
                }
            }
            // Staff with an emblem; it glows while healing.
            val p = pose.atk
            val raise = if (p >= 0f) 3f + sin(p * PI.toFloat()) * 1.5f else 0f
            val stx = dir * 6.2f
            armTo(dir * 3.3f, sy, stx, sy - raise, 4.5f, robe)
            val top = -29f + b - raise
            if (age >= Age.GUNPOWDER) {
                limb(stx, 0f, stx, top + 8f, 1.3f, 0xFF3A2A1A.toInt(), hi = false)
                flat(0xFFD8B860.toInt()); c.drawCircle(stx, top + 7.5f, 1.2f, fill)
            } else {
                limb(stx, 0f, stx, top + 2f, 1.5f, WOOD, hi = false)
                if (age == Age.MEDIEVAL) {
                    thin(stx, top + 3f, stx, top - 3f, 1.6f, 0xFFE8C860.toInt())
                    thin(stx - 2f, top + 0.5f, stx + 2f, top + 0.5f, 1.6f, 0xFFE8C860.toInt())
                } else {
                    outlineStroke(1.8f); stroke.color = 0xFFE8C860.toInt(); c.drawCircle(stx, top - 0.5f, 2.4f, stroke)
                    flat(team); c.drawCircle(stx, top - 0.5f, 1.1f, fill)
                }
            }
            if (p >= 0f) {
                val g = 0.6f + sin(p * PI.toFloat()) * 0.4f
                fill.shader = RadialGradient(stx, top, 6.5f * g, intArrayOf(0xA0D0FFC0.toInt(), 0x40A0FF90, 0x0080FF80), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(stx, top, 6.5f * g, fill); fill.shader = null
                flat(0xC0FFFFFF.toInt())
                for (i in 0 until 3) c.drawCircle(stx + cos(i * 2.1f + p * 4f) * 4.5f, top - 2f + sin(i * 2.1f + p * 4f) * 3f - p * 3f, 0.8f, fill)
            }
        }

        private fun mounted(type: UnitType) {
            val b = pose.bob * 0.6f
            val coat = when (type) { UnitType.SCOUT -> 0xFFB8865A.toInt(); UnitType.HORSEMAN -> 0xFF5A4030.toInt(); else -> 0xFF8A6A48.toInt() }
            val armored = type == UnitType.HORSEMAN && age >= Age.MEDIEVAL
            // Legs: far pair first, near pair later so they overlap the belly.
            horseLeg(-dir * 8.5f, -10.5f + b, pose.footB, pose.liftB, shade(coat, 0.7f))
            horseLeg(dir * 5.5f, -10.5f + b, pose.footB, pose.liftB, shade(coat, 0.7f))
            // Tail.
            stroke.color = INK; stroke.strokeWidth = 3f
            path.reset(); path.moveTo(-dir * 10f, -14f + b); path.quadTo(-dir * 14f, -12f + b, -dir * 13.5f, -5f + b)
            c.drawPath(path, stroke)
            stroke.color = shade(coat, 0.45f); stroke.strokeWidth = 1.8f; c.drawPath(path, stroke)
            // Body and neck.
            blob(0f, -13.5f + b, 11.5f, 5f, coat)
            limb(dir * 8f, -15f + b, dir * 13.5f, -20.5f + b, 5.4f, coat)
            // Head: elongated towards the muzzle.
            val hdx = dir * 16.2f; val hdy = -21.8f + b
            blob(hdx, hdy, 3.8f, 2.6f, coat)
            blob(hdx + dir * 2.6f, hdy + 0.9f, 2.1f, 1.7f, shade(coat, 1.1f), outline = false)
            flat(0xFF201810.toInt()); c.drawCircle(hdx + dir * 0.8f, hdy - 0.9f, 0.6f, fill)
            flat(0x60000000); c.drawCircle(hdx + dir * 4.2f, hdy + 1.3f, 0.5f, fill)
            poly(shade(coat, 0.9f), hdx - dir * 1.5f, hdy - 2f, hdx - dir * 0.2f, hdy - 2.2f, hdx - dir * 1.1f, hdy - 4.6f)
            outlineStroke(0.7f); c.drawPath(path, stroke)
            // Mane.
            stroke.color = 0xFF2A1E14.toInt(); stroke.strokeWidth = 1.6f
            for (i in 0 until 4) {
                val t = i / 3f
                val mx = dir * (8.5f + t * 5.5f); val my = -17.5f - t * 5f + b
                c.drawLine(mx, my, mx - dir * 2.2f, my + 0.8f, stroke)
            }
            // Bridle and reins.
            thin(hdx - dir * 1f, hdy - 1.5f, hdx + dir * 2.5f, hdy + 1f, 0.7f, st.leather)
            thin(hdx + dir * 1f, hdy + 1.5f, dir * 2f, -20f + b, 0.6f, st.leather)
            if (armored) {
                // Caparison: team cloth over the body.
                path.reset()
                path.moveTo(-dir * 9f, -15f + b); path.lineTo(dir * 8f, -16f + b)
                path.lineTo(dir * 8.5f, -7f + b); path.lineTo(-dir * 8f, -6f + b); path.close()
                fill.shader = LinearGradient(-9f, -16f, 9f, -6f, intArrayOf(shade(team, 1.2f), team, shade(team, 0.7f)), null, Shader.TileMode.CLAMP)
                c.drawPath(path, fill); fill.shader = null
                outlineStroke(0.9f); c.drawPath(path, stroke)
                for (i in 0 until 4) { flat(0xFFE8D070.toInt()); c.drawCircle(-dir * 6f + dir * i * 4.2f, -8f + b, 0.7f, fill) }
                block(hdx - 3f, hdy - 3f, hdx + dir * 2f + 1.6f, hdy + 1.8f, 1.2f, st.metal, outline = false) // chanfron
            } else {
                // Saddle blanket in team colour.
                block(-dir * 4.5f, -17.5f + b, dir * 4.5f, -10.5f + b, 1.4f, team)
                thin(-dir * 4f, -11.4f + b, dir * 4f, -11.4f + b, 0.8f, shade(team, 1.5f))
            }
            // Girth strap and saddle.
            thin(0f, -9f + b, 0f, -17f + b, 1.2f, st.leather)
            block(-2.8f, -19f + b, 2.8f, -16f + b, 1.6f, st.leather)
            // Near legs.
            horseLeg(-dir * 6.5f, -10f + b, pose.footF, pose.liftF, coat)
            horseLeg(dir * 8f, -10f + b, pose.footF, pose.liftF, coat)
            rider(type, b)
        }

        private fun horseLeg(hx: Float, hip: Float, swing: Float, lift: Float, col: Int) {
            val fx = hx + dir * swing * 0.9f; val fy = -lift * 0.7f
            val kx = (hx + fx) / 2f - dir * 0.6f; val ky = hip * 0.55f - lift * 0.3f
            limb(hx, hip, kx, ky, 2.6f, col)
            limb(kx, ky, fx, fy - 0.8f, 2.1f, col)
            limb(fx - dir * 0.3f, fy - 0.9f, fx + dir * 0.8f, fy - 0.3f, 1.9f, 0xFF2A2018.toInt(), hi = false)
        }

        private fun rider(type: UnitType, b: Float) {
            val ry = -17f + b
            // Near leg hanging along the horse's flank.
            limb(dir * 1f, ry - 2f, dir * 3.2f, ry + 7.5f, 2.4f, st.cloth)
            limb(dir * 2.8f, ry + 7f, dir * 4.4f, ry + 8f, 2f, BOOT, hi = false)
            flat(st.leather); c.drawRoundRect(dir * 2.4f - 1.4f, ry + 6.5f, dir * 2.4f + 1.4f, ry + 9f, 0.6f, 0.6f, fill) // stirrup
            val sy = ry - 9.5f
            // Back arm holds the reins or a shield.
            armTo(-dir * 3.2f, sy, dir * 3f, sy + 4f, 5.5f, team)
            val shx = hx; val shy = hy
            torso(ry - 12f, ry - 1f, 3.9f, team)
            if (type != UnitType.SCOUT) armor(ry - 12f, 3.9f)
            belt(ry - 4f, st.leather, 3.9f)
            val hy0 = ry - 15.3f
            head(hy0 - b, HAIR)
            when (type) {
                UnitType.SCOUT -> when {
                    age >= Age.GUNPOWDER -> helmet(hy0 - b, HELM_TRICORNE)
                    else -> {
                        // Wide-brimmed traveller's hat.
                        val y = hy0
                        blob(0f, y - 3.2f, 2.6f, 1.6f, 0xFF6A5030.toInt())
                        flat(0xFF6A5030.toInt()); rect.set(-5.2f, y - 3.3f, 5.2f, y - 1.3f); c.drawOval(rect, fill)
                        outlineStroke(0.8f); c.drawOval(rect, stroke)
                    }
                }
                UnitType.HORSEMAN -> helmet(hy0 - b, when (age) { Age.ANCIENT, Age.CLASSICAL -> HELM_CREST; Age.MEDIEVAL -> HELM_NASAL; else -> HELM_PLUME })
                else -> helmet(hy0 - b, when (age) { Age.MEDIEVAL -> HELM_CAP; Age.GUNPOWDER -> HELM_TRICORNE; else -> HELM_KEPI })
            }
            val p = pose.atk
            when (type) {
                UnitType.HORSEMAN -> {
                    if (st.shield != SHIELD_NONE) shield(shx - dir * 1f, shy - 2f, st.shield, false)
                    if (age >= Age.GUNPOWDER) {
                        val a = if (p >= 0f) 2.2f - p * 2.8f else 1f
                        armAngle(dir * 3.2f, sy, a, 6f, team)
                        sword(hx, hy, a, 11f)
                    } else {
                        // Lance couched forward; thrusts on attack.
                        val jab = if (p >= 0f) sin(p * PI.toFloat()) * 5f else 0f
                        armTo(dir * 3.2f, sy, dir * 8f, sy + 3f, 5.5f, team)
                        val lx = hx + dir * jab; val ly = hy
                        limb(lx - dir * 7f, ly + 3f, lx + dir * 16f, ly - 4.5f, 1.6f, WOOD, hi = false)
                        val tx = lx + dir * 16f; val ty = ly - 4.5f
                        polyOutlined(shade(st.metal, 1.25f), tx - dir * 1f, ty - 1.5f, tx + dir * 5f, ty - 1.5f, tx - dir * 1f, ty + 1.3f)
                        // Pennant.
                        poly(team, lx + dir * 7f, ly - 1.6f, lx + dir * 13f, ly - 4.2f, lx + dir * 8f, ly - 5.5f)
                        outlineStroke(0.7f); c.drawPath(path, stroke)
                    }
                }
                UnitType.HORSE_ARCHER -> {
                    if (age >= Age.GUNPOWDER) {
                        val flash = p >= 0.3f && p < 0.8f
                        armTo(dir * 3.2f, sy, dir * 7f, sy - 1f, 6f, team)
                        gun(dir * 2f, sy - 1f, 0.2f, 9f, flash, false)
                    } else {
                        val draw = if (p < 0f) 0f else if (p < 0.6f) 0.4f + p else 0f
                        armTo(dir * 3.2f, sy, dir * 8f, sy, 5.5f, team)
                        bow(hx, hy, draw, -dir * 1.5f, sy - 0.5f, p >= 0f && p < 0.6f)
                        if (draw > 0f) armTo(-dir * 3.2f, sy, hx - dir * 9f * draw, hy, 4.5f, team)
                    }
                }
                else -> {
                    // Scout: spear held upright, or a carbine later.
                    if (age >= Age.GUNPOWDER) {
                        armTo(dir * 3.2f, sy, dir * 6f, sy + 2f, 5f, team)
                        gun(hx, hy, 1.2f, 8f, false, false)
                    } else {
                        armTo(dir * 3.2f, sy, dir * 6f, sy + 1f, 5f, team)
                        limb(hx, hy + 8f, hx + dir * 0.5f, hy - 16f, 1.3f, WOOD, hi = false)
                        polyOutlined(shade(st.metal, 1.2f), hx - 1.4f, hy - 16f, hx + 1.4f, hy - 16f, hx + dir * 0.4f, hy - 20.5f)
                        poly(team, hx + dir * 0.4f, hy - 15f, hx + dir * 6f, hy - 13f, hx + dir * 0.4f, hy - 11f)
                    }
                }
            }
        }

        private fun siege() {
            when {
                age <= Age.CLASSICAL -> onager()
                age == Age.MEDIEVAL -> trebuchet()
                else -> cannon(age == Age.INDUSTRIAL)
            }
        }

        private fun wheel(x: Float, y: Float, r: Float, iron: Boolean) {
            blob(x, y, r, r, 0xFF5A3E24.toInt())
            if (iron) { outlineStroke(1.4f); stroke.color = 0xFF3A3A40.toInt(); c.drawCircle(x, y, r - 0.7f, stroke) }
            stroke.color = shade(WOOD, 1.1f); stroke.strokeWidth = 1f
            for (i in 0 until 4) { val a = i * PI.toFloat() / 4f; c.drawLine(x - cos(a) * (r - 1f), y - sin(a) * (r - 1f), x + cos(a) * (r - 1f), y + sin(a) * (r - 1f), stroke) }
            flat(0xFF3A3A40.toInt()); c.drawCircle(x, y, 1.3f, fill)
        }

        private fun beam(x0: Float, y0: Float, x1: Float, y1: Float, w: Float) {
            limb(x0, y0, x1, y1, w, WOOD)
            // Grain.
            thin(x0 + (x1 - x0) * 0.1f, y0 + (y1 - y0) * 0.1f + w * 0.2f, x0 + (x1 - x0) * 0.9f, y0 + (y1 - y0) * 0.9f + w * 0.2f, 0.5f, 0x50201008)
        }

        private fun banner(x: Float, y: Float) {
            limb(x, y, x, y - 9f, 1f, 0xFF3A2A1A.toInt(), hi = false)
            poly(team, x, y - 9f, x + dir * 6f, y - 7.2f, x, y - 5.2f)
            outlineStroke(0.7f); c.drawPath(path, stroke)
        }

        private fun onager() {
            val p = pose.atk
            // Rear wheels and frame.
            wheel(-dir * 9.5f, -4f, 3.6f, false)
            wheel(dir * 8.5f, -4f, 3.6f, false)
            beam(-14f, -7.5f, 14f, -7.5f, 3.4f)
            beam(-11f, -8f, -5f, -19f, 2.4f)
            beam(11f, -8f, 5f, -19f, 2.4f)
            beam(-6f, -18.5f, 6f, -18.5f, 2.2f)
            // Torsion bundle.
            limb(-7f, -10f, 7f, -10f, 3f, 0xFFB09870.toInt(), hi = false)
            for (i in -2..2) thin(i * 2.6f - 0.8f, -11.4f, i * 2.6f + 0.8f, -8.6f, 0.6f, 0x60403020)
            // Throwing arm: cocked back when idle, swung up and forward while firing.
            val a = if (p >= 0f) (0.35f + p * 1.9f) else 0.35f
            val ax = -dir * cos(a) * 17f; val ay = -10f - sin(a) * 17f
            beam(0f, -10f, ax, ay, 2.4f)
            // Spoon with the stone until it is released.
            blob(ax, ay, 2.6f, 2.6f, 0xFF6A5A3A.toInt())
            if (p < 0.6f) blob(ax - dir * 0.3f, ay - 1.5f, 2.2f, 2.2f, 0xFF5A5A5E.toInt())
            wheel(-dir * 11.5f, -3f, 4f, false)
            wheel(dir * 11f, -3f, 4f, false)
            banner(-dir * 13f, -8f)
        }

        private fun trebuchet() {
            val p = pose.atk
            wheel(-dir * 11f, -3.5f, 3.6f, true)
            wheel(dir * 10f, -3.5f, 3.6f, true)
            beam(-15f, -7f, 15f, -7f, 3.2f)
            // A-frame.
            beam(-9f, -7.5f, -1f, -30f, 2.4f)
            beam(9f, -7.5f, 1f, -30f, 2.4f)
            beam(-7f, -16f, 7f, -16f, 1.8f)
            // Long arm with counterweight: hangs back when idle, swings over on firing.
            val a = if (p >= 0f) (-0.3f + p * 2.6f) else -0.3f
            val px = 0f; val py = -30f
            val lx = px - dir * cos(a) * 20f; val ly = py - sin(a) * 20f
            val wx = px + dir * cos(a) * 7f; val wy = py + sin(a) * 7f
            beam(wx, wy, lx, ly, 2.6f)
            block(wx - 3.6f, wy - 1f, wx + 3.6f, wy + 6f, 1f, 0xFF4A4A50.toInt())
            // Sling and stone.
            if (p < 0.6f) {
                thin(lx, ly, lx - dir * 2f, ly + 7f, 0.8f, 0xFFB09870.toInt())
                blob(lx - dir * 2f, ly + 8f, 2.2f, 2.2f, 0xFF5A5A5E.toInt())
            } else thin(lx, ly, lx + dir * 5f, ly + 2f, 0.8f, 0xFFB09870.toInt())
            flat(0xFF3A3A40.toInt()); c.drawCircle(px, py, 1.6f, fill)
            wheel(-dir * 13f, -2.5f, 4f, true)
            wheel(dir * 12f, -2.5f, 4f, true)
            banner(-dir * 14f, -7f)
        }

        private fun cannon(modern: Boolean) {
            val p = pose.atk
            val iron = if (modern) 0xFF50545C.toInt() else 0xFF3A3A40.toInt()
            val carriage = if (modern) 0xFF4A6A48.toInt() else WOOD
            wheel(-dir * 6f, -4.5f, 4.6f, true)
            // Trail and carriage.
            limb(-dir * 14f, -1.5f, -dir * 2f, -9f, 3.2f, carriage)
            block(-dir * 7f - 5f, -12f, -dir * 7f + 5f, -6f, 1.2f, carriage)
            val recoil = if (p >= 0.3f && p < 0.9f) -dir * 2f else 0f
            // Barrel: short fat bombard, or a long modern gun.
            val len = if (modern) 20f else 15f
            val bx0 = -dir * 7f + recoil; val by0 = -12f
            val bx1 = bx0 + dir * len; val by1 = by0 - len * 0.22f
            limb(bx0, by0, bx1, by1, if (modern) 3.6f else 5.4f, iron)
            thin(bx0 + dir * 2f, by0 - 1.4f, bx1 - dir * 2f, by1 - 1.4f, 0.8f, shade(iron, 1.6f))
            // Reinforcing bands / muzzle swell.
            for (i in 0 until if (modern) 2 else 3) {
                val t = 0.25f + i * 0.3f
                limb(bx0 + (bx1 - bx0) * t, by0 + (by1 - by0) * t - 3.2f, bx0 + (bx1 - bx0) * t, by0 + (by1 - by0) * t + 3.2f, 1.6f, shade(iron, 0.8f), hi = false)
            }
            blob(bx1, by1, if (modern) 2.2f else 3.2f, if (modern) 2.2f else 3.2f, iron)
            flat(0xFF101014.toInt()); c.drawCircle(bx1 + dir * 0.6f, by1, if (modern) 1.2f else 1.8f, fill)
            if (modern) block(-dir * 1f - 1.2f, -17f, -dir * 1f + 1.2f, -6f, 0.8f, 0xFF6A7A66.toInt()) // gun shield
            wheel(dir * 2f, -4f, 5.2f, true)
            if (p >= 0.3f && p < 0.8f) {
                val fx = bx1 + dir * 4f; val fy = by1 - 1f
                fill.shader = RadialGradient(fx, fy, 7f, intArrayOf(0xFFFFF4C0.toInt(), 0xC0FFC060.toInt(), 0x00FF8040), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(fx, fy, 7f, fill); fill.shader = null
                flat(0x60B0B0B8); c.drawCircle(fx + dir * 4f, fy - 3f, 4.5f, fill)
            }
            banner(-dir * 13f, -3f)
        }
    }

    companion object {
        private const val PAD = 3f
        /** Upper bound for the bitmaps held by the cache, in bytes. */
        const val BUDGET = 24 * 1024 * 1024

        private val FOOT = Box(23f, 46f, 6f)
        private val MOUNTED = Box(31f, 48f, 6f)
        private val SIEGE = Box(30f, 46f, 6f)

        private val SHADOW_RX = floatArrayOf(7.5f, 13f, 14f)
        private val SHADOW_RY = floatArrayOf(3.2f, 4.8f, 5.6f)

        private const val INK = 0xFF1E160F.toInt()
        private const val SKIN = 0xFFE2B88E.toInt()
        private const val HAIR = 0xFF4A2E1A.toInt()
        private const val BOOT = 0xFF4A3424.toInt()
        private const val WOOD = 0xFF7A5634.toInt()

        private const val HELM_CAP = 1
        private const val HELM_CREST = 2
        private const val HELM_NASAL = 3
        private const val HELM_MORION = 4
        private const val HELM_TRICORNE = 5
        private const val HELM_KEPI = 6
        private const val HELM_PLUME = 7
        private const val HELM_MITRE = 8

        private const val SHIELD_NONE = 0
        private const val SHIELD_HIDE = 1
        private const val SHIELD_ROUND = 2
        private const val SHIELD_KITE = 3

        private val STYLES = arrayOf(
            Style(0xFFB08A50.toInt(), 0xFF7A5230.toInt(), 0xFF6A5A48.toInt(), HELM_CAP, SHIELD_HIDE),
            Style(0xFFD8AC58.toInt(), 0xFF8A5A30.toInt(), 0xFFD8CFBF.toInt(), HELM_CREST, SHIELD_ROUND),
            Style(0xFFC0C4D0.toInt(), 0xFF5A3A24.toInt(), 0xFF4A4A58.toInt(), HELM_NASAL, SHIELD_KITE),
            Style(0xFF7A808E.toInt(), 0xFF3A2A1E.toInt(), 0xFFE8E0D0.toInt(), HELM_TRICORNE, SHIELD_NONE),
            Style(0xFF5A5E68.toInt(), 0xFF2A2420.toInt(), 0xFF3A3E50.toInt(), HELM_KEPI, SHIELD_NONE),
        )

        fun isMounted(t: UnitType) = t == UnitType.SCOUT || t == UnitType.HORSEMAN || t == UnitType.HORSE_ARCHER

        private fun boxOf(t: UnitType) = when {
            isMounted(t) -> MOUNTED
            t == UnitType.CATAPULT -> SIEGE
            else -> FOOT
        }

        private fun shadowKind(t: UnitType) = when {
            isMounted(t) -> 1
            t == UnitType.CATAPULT -> 2
            else -> 0
        }
    }
}
