package com.nsheaps.risetopower

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.nsheaps.risetopower.TerrainLayers.Companion.lerpColor
import com.nsheaps.risetopower.TerrainLayers.Companion.shade
import com.nsheaps.risetopower.core.Age
import com.nsheaps.risetopower.core.Building
import com.nsheaps.risetopower.core.BuildingType
import com.nsheaps.risetopower.core.GameUnit
import com.nsheaps.risetopower.core.NodeKind
import com.nsheaps.risetopower.core.OrderType
import com.nsheaps.risetopower.core.Projectile
import com.nsheaps.risetopower.core.ResourceNode
import com.nsheaps.risetopower.core.ResourceType
import com.nsheaps.risetopower.core.SoundCue
import com.nsheaps.risetopower.core.Terrain
import com.nsheaps.risetopower.core.UnitType
import com.nsheaps.risetopower.core.World
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Visual-only effects (dust clouds, markers). */
class Effect(val kind: Int, val x: Float, val y: Float, var t: Float, val life: Float, val color: Int = 0) {
    companion object {
        const val DUST = 0
        const val PUFF = 1
        const val MOVE_MARK = 2
        const val ATTACK_MARK = 3
        const val SPARK = 4
    }
}

/** Mutable UI state shared between input handling, HUD and renderer (all on the game thread). */
class UiState {
    val selection = LinkedHashSet<Int>()
    var placing: BuildingType? = null
    var placeX = 0
    var placeY = 0
    var placeValid = false
    var wallStart: Pair<Int, Int>? = null
    var boxActive = false
    val box = RectF()
    val effects = ArrayList<Effect>()
}

class Renderer(private val world: World, private val humanId: Int, private val cam: IsoCamera, private val layers: TerrainLayers) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bmpPaintSharp = Paint()
    private val shimmerPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    /** Unit-circle radial gradient; positioned per draw through its local matrix (no allocation). */
    private val shadowShader = RadialGradient(0f, 0f, 1f, intArrayOf(0x5A000000, 0x40000000, 0x00000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = shadowShader }
    private val shadowMatrix = Matrix()
    private val path = Path()
    private val matrix = Matrix()
    private val bounds = IntArray(4)
    private val materials = Materials()
    private val sprites = NatureSprites()
    private val unitSprites = UnitSprites()
    private var time = 0f
    private val probe = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val probeCanvas = Canvas(probe)
    private val ghostPaint = Paint()
    private val occluders = ArrayList<Building>()
    private val outline = FloatArray(12)

    private class DrawItem {
        var key = 0f
        var kind = 0
        var ref: Any? = null
        var tx = 0
        var ty = 0
    }

    private val items = ArrayList<DrawItem>()
    private val pool = ArrayList<DrawItem>()
    private val comparator = Comparator<DrawItem> { a, b -> a.key.compareTo(b.key) }

    /** Enemy buildings the human has seen at least once. */
    private val seenBuildings = HashSet<Int>()

    private fun item(): DrawItem {
        val it = if (pool.isEmpty()) DrawItem() else pool.removeAt(pool.size - 1)
        items += it
        return it
    }

    // ------------------------------------------------------------------ helpers

    private val s get() = cam.scale
    private fun sx(x: Float, y: Float) = cam.sx(x, y)
    private fun sy(x: Float, y: Float) = cam.sy(x, y)

    /** Vertical offset in pixels for a height expressed in "tile half-widths". */
    private fun up(h: Float) = h * 32f * s

    private fun visibleToHuman(x: Float, y: Float) = world.isVisibleTo(humanId, x, y)

    private fun quad(c: Canvas, color: Int, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        path.reset()
        path.moveTo(x0, y0); path.lineTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
        fill.color = color
        c.drawPath(path, fill)
    }

    private fun tri(c: Canvas, color: Int, x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float) {
        path.reset()
        path.moveTo(x0, y0); path.lineTo(x1, y1); path.lineTo(x2, y2); path.close()
        fill.color = color
        c.drawPath(path, fill)
    }

    private fun circle(c: Canvas, color: Int, x: Float, y: Float, r: Float) {
        fill.color = color
        c.drawCircle(x, y, r, fill)
    }

    private fun line(c: Canvas, color: Int, x0: Float, y0: Float, x1: Float, y1: Float, w: Float) {
        stroke.color = color
        stroke.strokeWidth = w
        c.drawLine(x0, y0, x1, y1, stroke)
    }

    private fun ellipse(c: Canvas, color: Int, x: Float, y: Float, rx: Float, ry: Float) {
        fill.color = color
        c.drawOval(x - rx, y - ry, x + rx, y + ry, fill)
    }

    /** Soft-edged ground shadow (radial falloff) centred at (x, y). */
    private fun softShadow(c: Canvas, x: Float, y: Float, rx: Float, ry: Float) {
        shadowMatrix.setScale(rx, ry)
        shadowMatrix.postTranslate(x, y)
        shadowShader.setLocalMatrix(shadowMatrix)
        c.drawOval(x - rx, y - ry, x + rx, y + ry, shadowPaint)
    }

    /** Diamond for a world rectangle at ground level. */
    private fun groundRect(c: Canvas, color: Int, x0: Float, y0: Float, x1: Float, y1: Float, h: Float = 0f) {
        val o = up(h)
        quad(c, color, sx(x0, y0), sy(x0, y0) - o, sx(x1, y0), sy(x1, y0) - o, sx(x1, y1), sy(x1, y1) - o, sx(x0, y1), sy(x0, y1) - o)
    }

    private fun groundOutline(c: Canvas, color: Int, x0: Float, y0: Float, x1: Float, y1: Float, w: Float) {
        path.reset()
        path.moveTo(sx(x0, y0), sy(x0, y0)); path.lineTo(sx(x1, y0), sy(x1, y0))
        path.lineTo(sx(x1, y1), sy(x1, y1)); path.lineTo(sx(x0, y1), sy(x0, y1)); path.close()
        stroke.color = color
        stroke.strokeWidth = w
        c.drawPath(path, stroke)
    }

    /** Iso box on world rect [x0,x1]x[y0,y1] from height [base] to [base+h]. Draws the two front faces and the top. */
    private fun box(
        c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, base: Float, h: Float, color: Int, top: Int? = null,
        mat: Materials.Kind = Materials.forWall(color),
    ) {
        val b = up(base); val t = up(base + h)
        val lx = sx(x0, y1); val ly = sy(x0, y1)
        val bx = sx(x1, y1); val by = sy(x1, y1)
        val rx = sx(x1, y0); val ry = sy(x1, y0)
        val tx = sx(x0, y0); val ty = sy(x0, y0)
        val detailed = s > 0.5f && h > 0.25f
        val tile = up(MAT_TILE) // one texture tile spans MAT_TILE world units
        val edgeW = Materials.lineWidth(s)
        // Left (south-west) face, texture u runs along world x.
        quad(c, shade(color, 0.74f), lx, ly - b, bx, by - b, bx, by - t, lx, ly - t)
        if (detailed) {
            val ox = sx(0f, y1); val oy = sy(0f, y1) - b
            materials.overlay(c, path, mat, ox, oy, tile, tile / 2f, 0f, tile)
            if (base < 0.05f) materials.ambientOcclusion(c, path, lx, ly, bx - lx, by - ly, 0f, -up(min(h, 0.6f)))
            materials.outline(c, path, 0x50000000, edgeW)
        }
        // Right (south-east) face, texture u runs along world y.
        quad(c, shade(color, 0.95f), bx, by - b, rx, ry - b, rx, ry - t, bx, by - t)
        if (detailed) {
            val ox = sx(x1, 0f); val oy = sy(x1, 0f) - b
            materials.overlay(c, path, mat, ox, oy, -tile, tile / 2f, 0f, tile)
            if (base < 0.05f) materials.ambientOcclusion(c, path, bx, by, rx - bx, ry - by, 0f, -up(min(h, 0.6f)))
            materials.outline(c, path, 0x50000000, edgeW)
        }
        quad(c, top ?: shade(color, 1.12f), tx, ty - t, rx, ry - t, bx, by - t, lx, ly - t)
        if (detailed) {
            // Lit top edges.
            line(c, 0x40FFFFFF, lx, ly - t, bx, by - t, edgeW)
            line(c, 0x40FFFFFF, bx, by - t, rx, ry - t, edgeW)
        }
    }

    /** Roof tiles on the roof plane just filled into [path]: courses parallel to the eave (ex,ey)->(fx,fy), climbing to (ax,ay). */
    private fun roofTiles(c: Canvas, ex: Float, ey: Float, fx: Float, fy: Float, ax: Float, ay: Float, eaveLen: Float) {
        if (s <= 0.5f) return
        val mx = (ex + fx) / 2f; val my = (ey + fy) / 2f
        val rise = kotlin.math.hypot(ax - mx, ay - my)
        val courses = max(2f, rise / (9f * s))
        val n = max(1f, eaveLen / MAT_TILE)
        // u along the eave (n tiles), v from apex down to the eave (courses).
        materials.overlay(c, path, Materials.Kind.SHINGLES, ax, ay, (fx - ex) / n, (fy - ey) / n, (mx - ax) / courses * 5f, (my - ay) / courses * 5f)
        materials.outline(c, path, 0x40000000, Materials.lineWidth(s))
    }

    private fun pyramidRoof(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, base: Float, h: Float, color: Int) {
        val b = up(base)
        val mx = (x0 + x1) / 2f; val my = (y0 + y1) / 2f
        val ax = sx(mx, my); val ay = sy(mx, my) - up(base + h)
        val lx = sx(x0, y1); val ly = sy(x0, y1) - b
        val bx = sx(x1, y1); val by = sy(x1, y1) - b
        val rx = sx(x1, y0); val ry = sy(x1, y0) - b
        tri(c, shade(color, 0.8f), lx, ly, bx, by, ax, ay)
        roofTiles(c, lx, ly, bx, by, ax, ay, x1 - x0)
        tri(c, shade(color, 1.0f), bx, by, rx, ry, ax, ay)
        roofTiles(c, bx, by, rx, ry, ax, ay, y1 - y0)
        line(c, shade(color, 1.3f), bx, by, ax, ay, max(1f, s * 1.1f))
    }

    /** Gable roof with its ridge running along the x axis. */
    private fun gableRoof(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, base: Float, h: Float, color: Int) {
        val b = up(base); val r = up(base + h)
        val my = (y0 + y1) / 2f
        val p1x = sx(x0, my); val p1y = sy(x0, my) - r
        val p2x = sx(x1, my); val p2y = sy(x1, my) - r
        val tx = sx(x0, y0); val ty = sy(x0, y0) - b
        val rx = sx(x1, y0); val ry = sy(x1, y0) - b
        val bx = sx(x1, y1); val by = sy(x1, y1) - b
        val lx = sx(x0, y1); val ly = sy(x0, y1) - b
        quad(c, shade(color, 1.1f), tx, ty, rx, ry, p2x, p2y, p1x, p1y)
        roofTiles(c, tx, ty, rx, ry, (p1x + p2x) / 2f, (p1y + p2y) / 2f, x1 - x0)
        quad(c, shade(color, 0.85f), lx, ly, bx, by, p2x, p2y, p1x, p1y)
        roofTiles(c, lx, ly, bx, by, (p1x + p2x) / 2f, (p1y + p2y) / 2f, x1 - x0)
        // Gable end wall under the roof.
        tri(c, shade(color, 0.62f), bx, by, rx, ry, p2x, p2y)
        if (s > 0.5f) {
            materials.outline(c, path, 0x50000000, Materials.lineWidth(s))
            line(c, 0x55000000, bx, by + 1.5f * s, rx, ry + 1.5f * s, max(1f, 1.5f * s)) // eave shadow
        }
        // Ridge highlight.
        line(c, shade(color, 1.3f), p1x, p1y, p2x, p2y, max(1f, s * 1.4f))
    }

    /**
     * Small windows on the two front faces of a box spanning [x0,x1]x[y0,y1] between heights
     * [base] and [base]+[h]: [nl] along the south-west face, [nr] along the south-east face.
     * Drawn inside the faces, so building silhouettes (and hit tests) are unchanged.
     */
    private fun windows(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, base: Float, h: Float, nl: Int, nr: Int, slit: Boolean = false) {
        if (s <= 0.5f) return
        val ww = if (slit) 0.07f else 0.16f
        val lo = up(base + h * (if (slit) 0.3f else 0.38f)); val hi = up(base + h * (if (slit) 0.75f else 0.66f))
        val glass = 0xFF2C3A4E.toInt(); val frame = 0x66FFF4D8; val sill = 0x55000000
        for (k in 0 until nl) {
            val fx = x0 + (x1 - x0) * (k + 0.5f) / nl
            val ax = sx(fx - ww, y1); val ay = sy(fx - ww, y1); val bx = sx(fx + ww, y1); val by = sy(fx + ww, y1)
            quad(c, glass, ax, ay - lo, bx, by - lo, bx, by - hi, ax, ay - hi)
            tri(c, 0x50C8E0FF, ax, ay - hi, bx, by - hi, ax, ay - lo - (hi - lo) * 0.3f)
            line(c, frame, ax, ay - hi, bx, by - hi, max(1f, s))
            line(c, sill, ax, ay - lo + s, bx, by - lo + s, max(1f, s))
        }
        for (k in 0 until nr) {
            val fy = y0 + (y1 - y0) * (k + 0.5f) / nr
            val ax = sx(x1, fy + ww); val ay = sy(x1, fy + ww); val bx = sx(x1, fy - ww); val by = sy(x1, fy - ww)
            quad(c, glass, ax, ay - lo, bx, by - lo, bx, by - hi, ax, ay - hi)
            tri(c, 0x38C8E0FF, ax, ay - hi, bx, by - hi, ax, ay - lo - (hi - lo) * 0.3f)
            line(c, frame, ax, ay - hi, bx, by - hi, max(1f, s))
            line(c, sill, ax, ay - lo + s, bx, by - lo + s, max(1f, s))
        }
    }

    /** A door with a shaded recess and a lit lintel, centred at world x [fx] on the south-west face y=[fy]. */
    private fun door(c: Canvas, fx: Float, fy: Float, halfW: Float, h: Float, base: Float = 0f) {
        val b = up(base); val t = up(base + h)
        val ax = sx(fx - halfW, fy); val ay = sy(fx - halfW, fy); val bx = sx(fx + halfW, fy); val by = sy(fx + halfW, fy)
        quad(c, 0xFF3A2416.toInt(), ax, ay - b, bx, by - b, bx, by - t, ax, ay - t)
        quad(c, 0xFF5A3A22.toInt(), ax + (bx - ax) * 0.2f, ay + (by - ay) * 0.2f - b, bx, by - b, bx, by - t + (t - b) * 0.08f, ax + (bx - ax) * 0.2f, ay + (by - ay) * 0.2f - t + (t - b) * 0.08f)
        if (s > 0.5f) {
            line(c, 0x70FFF0D0, ax - s, ay - t, bx + s, by - t, max(1f, 1.4f * s))
            circle(c, 0xFFE0C060.toInt(), bx - (bx - ax) * 0.25f, by - (by - ay) * 0.25f - (b + t) / 2f, max(0.8f, 0.9f * s))
        }
    }

    private fun crenellations(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, base: Float, color: Int) {
        val n = max(2, ((x1 - x0) * 3).toInt())
        val sz = (x1 - x0) / (n * 2f)
        for (k in 0 until n) {
            val fx = x0 + k * 2 * sz + sz / 2f
            box(c, fx, y1 - sz, fx + sz, y1, base, 0.18f, color)
        }
        val m = max(2, ((y1 - y0) * 3).toInt())
        val sy2 = (y1 - y0) / (m * 2f)
        for (k in 0 until m) {
            val fy = y0 + k * 2 * sy2 + sy2 / 2f
            box(c, x1 - sy2, fy, x1, fy + sy2, base, 0.18f, color)
        }
    }

    private fun flag(c: Canvas, wx: Float, wy: Float, base: Float, h: Float, color: Int) {
        val x = sx(wx, wy); val y = sy(wx, wy) - up(base)
        val top = y - up(h)
        line(c, 0xFF3A2A1A.toInt(), x, y, x, top, max(1f, s * 1.4f))
        val wave = sin(time * 4f + wx) * 2f * s
        tri(c, color, x, top, x + 14f * s, top + 4f * s + wave, x, top + 9f * s)
    }

    private fun hpBar(c: Canvas, x: Float, y: Float, w: Float, frac: Float, color: Int) {
        val h = max(3f, 3.5f * s)
        fill.color = 0xCC000000.toInt()
        c.drawRect(x - w / 2 - 1, y - 1, x + w / 2 + 1, y + h + 1, fill)
        fill.color = when {
            frac > 0.6f -> 0xFF4CD04C.toInt()
            frac > 0.3f -> 0xFFE0C030.toInt()
            else -> 0xFFE04030.toInt()
        }
        c.drawRect(x - w / 2, y, x - w / 2 + w * frac.coerceIn(0f, 1f), y + h, fill)
        if (color != 0) {
            fill.color = color
            c.drawRect(x - w / 2 - 1, y - 1, x - w / 2 + 2 * s, y + h + 1, fill)
        }
    }

    // ------------------------------------------------------------------ frame

    fun draw(c: Canvas, ui: UiState, dt: Float) {
        time += dt
        c.drawColor(0xFF0A0806.toInt())
        // Terrain & territory layers.
        cam.tileMatrix(matrix, layers.ppt.toFloat())
        c.drawBitmap(layers.terrain, matrix, bmpPaint)
        drawWaterShimmer(c)
        cam.tileMatrix(matrix, layers.terrPpt.toFloat())
        c.drawBitmap(layers.territory, matrix, bmpPaint)

        cam.visibleBounds(bounds, 3)
        val x0 = bounds[0]; val y0 = bounds[1]; val x1 = bounds[2]; val y1 = bounds[3]

        // Ground pass: farms and foundations.
        for (b in world.buildings) {
            if (!b.alive || !buildingShown(b)) continue
            if (b.tx > x1 + 4 || b.ty > y1 + 4 || b.tx + b.type.size < x0 - 4 || b.ty + b.type.size < y0 - 4) continue
            if (b.type == BuildingType.FARM) drawFarm(c, b)
            else if (ui.selection.contains(b.id)) groundOutline(c, selColor(b.owner), b.minX - 0.1f, b.minY - 0.1f, b.maxX + 0.1f, b.maxY + 0.1f, max(1.5f, 2f * s))
        }

        // Collect depth sorted items.
        for (it in items) { it.ref = null; pool += it }
        items.clear()
        for (n in world.nodes) {
            if (!n.alive || n.tx < x0 || n.ty < y0 || n.tx > x1 || n.ty > y1) continue
            if (!world.isExploredBy(humanId, n.tx, n.ty)) continue
            val d = item(); d.kind = 0; d.ref = n; d.key = n.x + n.y
        }
        for (b in world.buildings) {
            if (!b.alive || b.type == BuildingType.FARM || !buildingShown(b)) continue
            if (b.tx > x1 + 4 || b.ty > y1 + 4 || b.tx + b.type.size < x0 - 4 || b.ty + b.type.size < y0 - 4) continue
            val d = item(); d.kind = 1; d.ref = b; d.key = b.x + b.y
        }
        for (u in world.units) {
            if (!u.alive) continue
            if (u.x < x0 - 1 || u.y < y0 - 1 || u.x > x1 + 1 || u.y > y1 + 1) continue
            if (u.owner != humanId && !world.isAlly(humanId, u.owner) && !visibleToHuman(u.x, u.y)) continue
            val d = item(); d.kind = 2; d.ref = u; d.key = u.x + u.y
        }
        val map = world.map
        for (y in y0..y1) for (x in x0..x1) {
            if (map.terrain[map.idx(x, y)] != Terrain.MOUNTAIN || !world.isExploredBy(humanId, x, y)) continue
            val d = item(); d.kind = 3; d.tx = x; d.ty = y; d.key = x + y + 1f
        }
        items.sortWith(comparator)

        for (d in items) {
            when (d.kind) {
                0 -> drawNode(c, d.ref as ResourceNode)
                1 -> drawBuilding(c, d.ref as Building, ui.selection.contains((d.ref as Building).id))
                2 -> drawUnit(c, d.ref as GameUnit, ui.selection.contains((d.ref as GameUnit).id))
                3 -> drawMountain(c, d.tx, d.ty)
            }
        }
        drawHiddenUnits(c)

        for (p in world.projectiles) {
            if (!visibleToHuman(p.x, p.y)) continue
            drawProjectile(c, p)
        }
        drawEffects(c, ui, dt)

        // Fog of war on top.
        if (!world.revealed) {
            cam.tileMatrix(matrix, TerrainLayers.FOG_PPT.toFloat())
            c.drawBitmap(layers.fog, matrix, bmpPaint)
        }

        // Rally point of a selected own building.
        for (id in ui.selection) {
            val b = world.building(id) ?: continue
            if (b.owner == humanId && b.hasRally) {
                line(c, 0x88FFFFFF.toInt(), sx(b.x, b.y), sy(b.x, b.y), sx(b.rallyX, b.rallyY), sy(b.rallyX, b.rallyY), max(1f, s))
                flag(c, b.rallyX, b.rallyY, 0f, 0.9f, world.players[humanId].color)
            }
        }
        drawPlacement(c, ui)
    }

    private fun buildingShown(b: Building): Boolean {
        if (b.owner == humanId || world.isAlly(humanId, b.owner) || world.revealed) return true
        if (seenBuildings.contains(b.id)) return true
        for (dy in 0 until b.type.size) for (dx in 0 until b.type.size) {
            if (visibleToHuman(b.tx + dx + 0.5f, b.ty + dy + 0.5f)) { seenBuildings += b.id; return true }
        }
        return false
    }

    private fun selColor(owner: Int) = when {
        owner == humanId -> 0xFF7CFF6C.toInt()
        owner < 0 -> 0xFFFFE070.toInt()
        world.isAlly(humanId, owner) -> 0xFF6CC8FF.toInt()
        else -> 0xFFFF5040.toInt()
    }

    // ------------------------------------------------------------------ terrain features

    /**
     * Two drifting, pulsing copies of the pre-rendered water highlight layer. The layer is
     * transparent over land, so the sub-tile drift never spills onto the beach.
     */
    private fun drawWaterShimmer(c: Canvas) {
        if (!layers.hasWater) return
        val sp = layers.shimmerPpt.toFloat()
        val t = time
        for (k in 0 until 2) {
            val ph = t * 0.9f + k * 2.1f
            val dx = sin(ph) * 0.09f + k * 0.06f
            val dy = cos(ph * 0.7f) * 0.07f
            cam.tileMatrix(matrix, sp)
            matrix.preTranslate(dx * sp, dy * sp)
            shimmerPaint.alpha = (110 + 90 * sin(t * 1.3f + k * 3.1f)).toInt().coerceIn(0, 255)
            c.drawBitmap(layers.shimmer, matrix, shimmerPaint)
        }
    }

    /**
     * A rocky peak per mountain tile. Peaks are offset and sized by a hash so neighbouring tiles
     * overlap into a ragged massif rather than a grid of pyramids; sunlit from the north-west.
     */
    private fun drawMountain(c: Canvas, x: Int, y: Int) {
        val e = world.map.elevation[world.map.idx(x, y)]
        val hsh = ((x * 73856093) xor (y * 19349663)) ushr 1
        val r1 = (hsh and 255) / 255f; val r2 = ((hsh ushr 8) and 255) / 255f; val r3 = ((hsh ushr 16) and 255) / 255f
        val h = 0.5f + r1 * 0.8f + (e - 0.7f).coerceAtLeast(0f) * 2.2f
        val cxw = x + 0.5f + (r2 - 0.5f) * 0.36f
        val cyw = y + 0.5f + (r3 - 0.5f) * 0.36f
        val base = lerpColor(0xFF8E7E6A.toInt(), 0xFF7E746E.toInt(), r3)
        val ax = sx(cxw, cyw); val ay = sy(cxw, cyw) - up(h)
        // Footprint larger than the tile so peaks merge into a range.
        val m = 0.3f
        val lx = sx(x - m, y + 1 + m); val ly = sy(x - m, y + 1 + m)
        val bx = sx(x + 1 + m, y + 1 + m); val by = sy(x + 1 + m, y + 1 + m)
        val rx = sx(x + 1 + m, y - m); val ry = sy(x + 1 + m, y - m)
        softShadow(c, sx(x + 0.6f, y + 0.6f), sy(x + 0.6f, y + 0.6f), 48f * s, 22f * s)
        // A shoulder on each face breaks the straight silhouette.
        val shL = 0.35f + r2 * 0.25f; val shR = 0.3f + r1 * 0.25f
        val slx = ax + (lx - ax) * shL + 5f * s; val sly = ay + (ly - ay) * shL - 6f * s * r1
        val srx = ax + (rx - ax) * shR - 5f * s; val sry = ay + (ry - ay) * shR - 6f * s * r2
        val fold = 0.5f + (r3 - 0.5f) * 0.3f
        val fx = ax + (bx - ax) * fold; val fy = ay + (by - ay) * fold + 5f * s
        // Left (lit) face with a darker fold, right (shaded) face with a deeper fold.
        path.reset(); path.moveTo(lx, ly); path.lineTo(fx, fy); path.lineTo(ax, ay); path.lineTo(slx, sly); path.close()
        fill.color = shade(base, 1.08f); c.drawPath(path, fill)
        path.reset(); path.moveTo(fx, fy); path.lineTo(bx, by); path.lineTo(ax, ay); path.close()
        fill.color = shade(base, 0.9f); c.drawPath(path, fill)
        path.reset(); path.moveTo(bx, by); path.lineTo(fx + (rx - fx) * 0.5f, fy + (ry - fy) * 0.5f + 4f * s); path.lineTo(ax, ay); path.close()
        fill.color = shade(base, 0.74f); c.drawPath(path, fill)
        path.reset(); path.moveTo(fx + (rx - fx) * 0.5f, fy + (ry - fy) * 0.5f + 4f * s); path.lineTo(rx, ry); path.lineTo(srx, sry); path.lineTo(ax, ay); path.close()
        fill.color = shade(base, 0.64f); c.drawPath(path, fill)
        // Lit ridge and a few strata lines.
        line(c, 0x60FFF4E0, slx, sly, ax, ay, max(1f, 1.3f * s))
        line(c, 0x28000000, lx + (fx - lx) * 0.3f, ly + (fy - ly) * 0.3f + 6f * s, fx + (ax - fx) * 0.25f, fy + (ay - fy) * 0.25f, max(1f, s))
        line(c, 0x28000000, fx + (bx - fx) * 0.5f, fy + (by - fy) * 0.5f, rx + (ax - rx) * 0.3f, ry + (ay - ry) * 0.3f, max(1f, s))
        if (h > 1.4f) {
            // Snow cap with a ragged lower edge.
            val t = 0.3f + r2 * 0.1f
            path.reset()
            path.moveTo(ax, ay)
            path.lineTo(slx + (ax - slx) * (1 - t), sly + (ay - sly) * (1 - t))
            path.lineTo(ax + (lx - ax) * t * 0.6f, ay + (ly - ay) * t * 0.6f + 3f * s)
            path.lineTo(ax + (fx - ax) * t, ay + (fy - ay) * t - 2f * s)
            path.lineTo(ax + (rx - ax) * t * 0.55f, ay + (ry - ay) * t * 0.55f + 3f * s)
            path.lineTo(srx + (ax - srx) * (1 - t), sry + (ay - sry) * (1 - t))
            path.close()
            fill.color = 0xFFF2F5F8.toInt(); c.drawPath(path, fill)
            tri(c, 0xFFC9D3DE.toInt(), ax, ay, ax + (fx - ax) * t, ay + (fy - ay) * t - 2f * s, srx + (ax - srx) * (1 - t), sry + (ay - sry) * (1 - t))
        }
    }

    private class NodeLook(var kind: NatureSprites.Kind = NatureSprites.Kind.CONIFER, var variant: Int = 0, var level: Int = 0, var mirror: Boolean = false)

    private val look = NodeLook()

    private fun lookOf(n: ResourceNode): NodeLook {
        val left = n.amount / n.kind.amount
        val v = n.variant % 12
        look.level = 0; look.mirror = false; look.variant = v
        when (n.kind) {
            NodeKind.TREE -> {
                look.kind = when {
                    v == 5 || v == 11 -> NatureSprites.Kind.BIRCH
                    v % 2 == 0 -> NatureSprites.Kind.CONIFER
                    else -> NatureSprites.Kind.LEAFY
                }
                look.mirror = v % 3 == 0
            }
            NodeKind.BERRIES -> { look.kind = NatureSprites.Kind.BERRIES; look.variant = 0; look.level = (left * 3f).toInt().coerceIn(0, 3) }
            NodeKind.GAME -> { look.kind = NatureSprites.Kind.DEER; look.variant = v % 4; look.mirror = v % 2 == 1 }
            NodeKind.GOLD, NodeKind.STONE -> {
                look.kind = if (n.kind == NodeKind.GOLD) NatureSprites.Kind.GOLD else NatureSprites.Kind.STONE
                look.variant = v % 3; look.level = (left * 4f - 0.01f).toInt().coerceIn(0, 3)
            }
        }
        return look
    }

    private fun drawNode(c: Canvas, n: ResourceNode) {
        val l = lookOf(n)
        // Trees sway a touch in the breeze; a shear of ~1% of the height is below hit-test precision.
        val sway = if (n.kind == NodeKind.TREE) sin(time * 1.1f + n.x * 0.9f + n.y * 0.4f) * 0.012f else 0f
        sprites.draw(c, l.kind, l.variant, l.level, sx(n.x, n.y), sy(n.x, n.y), s, l.mirror, sway)
    }

    /** Pixel-accurate hit test against the sprite drawn for [n]. */
    fun nodeHit(n: ResourceNode, px: Float, py: Float): Boolean {
        val l = lookOf(n)
        return sprites.hit(l.kind, l.variant, l.level, sx(n.x, n.y), sy(n.x, n.y), s, l.mirror, px, py)
    }

    // ------------------------------------------------------------------ buildings

    private fun drawFarm(c: Canvas, b: Building) {
        val x0 = b.minX + 0.06f; val y0 = b.minY + 0.06f; val x1 = b.maxX - 0.06f; val y1 = b.maxY - 0.06f
        if (!b.constructed) {
            groundRect(c, 0xFF7A6040.toInt(), x0, y0, x1, y1)
            groundOutline(c, 0xFFE0D0A0.toInt(), x0, y0, x1, y1, max(1f, s))
            val p = b.progress
            groundRect(c, 0xFF9A8A4A.toInt(), x0, y0, x0 + (x1 - x0) * p, y1)
            return
        }
        val worked = b.farmerId >= 0
        groundRect(c, if (worked) 0xFFC2A44E.toInt() else 0xFF9E8A4E.toInt(), x0, y0, x1, y1)
        // Furrows.
        val rows = 7
        for (k in 1 until rows) {
            val fy = y0 + (y1 - y0) * k / rows
            line(c, 0x55553A1A, sx(x0, fy), sy(x0, fy), sx(x1, fy), sy(x1, fy), max(1f, 1.2f * s))
        }
        groundOutline(c, 0xFF6A4A2A.toInt(), x0, y0, x1, y1, max(1f, 1.4f * s))
    }

    private fun drawBuilding(c: Canvas, b: Building, selected: Boolean) {
        val t = b.type
        val pc = world.players[b.owner].color
        val inset = buildingInset(t)
        val x0 = b.minX + inset; val y0 = b.minY + inset; val x1 = b.maxX - inset; val y1 = b.maxY - inset
        if (!b.constructed) {
            drawFoundation(c, b, x0, y0, x1, y1)
        } else {
            softShadow(c, sx(b.x + 0.35f, b.y + 0.35f), sy(b.x + 0.35f, b.y + 0.35f), t.size * 30f * s, t.size * 13f * s)
            drawBuildingBody(c, b, t, x0, y0, x1, y1, pc)
            if (b.underAttackTimer > 0f && (time * 6).toInt() % 2 == 0) {
                circle(c, 0x66FF4020, sx(b.x, b.y), sy(b.x, b.y) - up(0.6f), 6f * s)
            }
            if (b.hp < b.maxHp * 0.5f) drawFire(c, b)
        }
        if (selected || b.hp < b.maxHp * 0.98f && b.constructed && b.owner == humanId) {
            val topY = sy(b.minX, b.minY) - up(buildingHeight(t)) - 8f * s
            hpBar(c, sx(b.x, b.y), topY, t.size * 22f * s, b.hp / b.maxHp, pc)
        }
        // Production progress indicator.
        if (b.owner == humanId && b.queue.isNotEmpty() && b.constructed) {
            val topY = sy(b.minX, b.minY) - up(buildingHeight(t)) - 3f * s
            fill.color = 0xCC000000.toInt()
            val w = t.size * 22f * s
            c.drawRect(sx(b.x, b.y) - w / 2, topY, sx(b.x, b.y) + w / 2, topY + 3f * s, fill)
            fill.color = 0xFF6CB8FF.toInt()
            c.drawRect(sx(b.x, b.y) - w / 2, topY, sx(b.x, b.y) - w / 2 + w * b.queueProgress, topY + 3f * s, fill)
        }
        if (b.wonderTimer > 0f) {
            fill.color = 0xFFFFE070.toInt()
            fill.textSize = 14f * s
            fill.textAlign = Paint.Align.CENTER
            val m = (b.wonderTimer / 60).toInt(); val sec = (b.wonderTimer % 60).toInt()
            c.drawText("%d:%02d".format(m, sec), sx(b.x, b.y), sy(b.minX, b.minY) - up(3.2f), fill)
        }
    }


    private fun drawFoundation(c: Canvas, b: Building, x0: Float, y0: Float, x1: Float, y1: Float) {
        groundRect(c, 0xFF8A7050.toInt(), b.minX + 0.05f, b.minY + 0.05f, b.maxX - 0.05f, b.maxY - 0.05f)
        if (s > 0.5f) {
            // Trampled earth with a few stacked stones waiting to be used.
            groundOutline(c, 0x50000000, b.minX + 0.05f, b.minY + 0.05f, b.maxX - 0.05f, b.maxY - 0.05f, max(1f, 1.2f * s))
            val px = sx(x0 + 0.25f, y1 - 0.15f); val py = sy(x0 + 0.25f, y1 - 0.15f)
            quad(c, 0xFFA89A88.toInt(), px - 7f * s, py, px + 1f * s, py - 2f * s, px + 1f * s, py - 7f * s, px - 7f * s, py - 5f * s)
            quad(c, 0xFF8C8070.toInt(), px + 1f * s, py - 2f * s, px + 7f * s, py - 5f * s, px + 7f * s, py - 10f * s, px + 1f * s, py - 7f * s)
            quad(c, 0xFFC4B8A4.toInt(), px - 7f * s, py - 5f * s, px + 1f * s, py - 7f * s, px + 7f * s, py - 10f * s, px - 1f * s, py - 8f * s)
        }
        val h = buildingHeight(b.type) * 0.6f
        val ph = h * b.progress
        if (ph > 0.02f) box(c, x0, y0, x1, y1, 0f, ph, 0xFFB09070.toInt())
        // Scaffold: poles at the three visible corners, rails, diagonal braces and a plank walkway.
        val pole = 0xFF6A4A2A.toInt()
        val w = max(1f, 1.3f * s)
        val lx = sx(x0, y1); val ly = sy(x0, y1)
        val bx = sx(x1, y1); val by = sy(x1, y1)
        val rx = sx(x1, y0); val ry = sy(x1, y0)
        val top = up(h); val mid = up(h * 0.5f)
        line(c, pole, lx, ly, lx, ly - top, w)
        line(c, pole, bx, by, bx, by - top, w)
        line(c, pole, rx, ry, rx, ry - top, w)
        line(c, pole, lx, ly - top, bx, by - top, w)
        line(c, pole, bx, by - top, rx, ry - top, w)
        line(c, pole, lx, ly - mid, bx, by - mid, w)
        line(c, pole, bx, by - mid, rx, ry - mid, w)
        if (s > 0.5f) {
            val thin = max(1f, 0.9f * s)
            line(c, 0xCC7A5A38.toInt(), lx, ly - mid, bx, by - top, thin)
            line(c, 0xCC7A5A38.toInt(), bx, by - mid, rx, ry - top, thin)
            // Plank walkway along the south-west rail.
            quad(c, 0xFFB89468.toInt(), lx, ly - top, bx, by - top, bx - 5f * s, by - top - 3f * s, lx - 5f * s, ly - top - 3f * s)
        }
        hpBar(c, sx(b.x, b.y), sy(b.minX, b.minY) - up(h) - 10f * s, b.type.size * 20f * s, b.progress, 0xFF6CB8FF.toInt())
    }

    private fun drawFire(c: Canvas, b: Building) {
        val n = 2
        for (k in 0 until n) {
            val fx = b.x + (k - 0.5f) * 0.6f; val fy = b.y + 0.2f
            val flick = sin(time * 12f + k * 2f) * 2f * s
            val x = sx(fx, fy); val y = sy(fx, fy) - up(0.9f)
            tri(c, 0xDDFF7A20.toInt(), x - 4f * s, y, x + 4f * s, y, x, y - 12f * s - flick)
            tri(c, 0xEEFFD040.toInt(), x - 2f * s, y, x + 2f * s, y, x, y - 7f * s - flick)
            circle(c, 0x44303030, x + 3f * s, y - 18f * s - (time * 10 % 10) * s, 4f * s)
        }
    }

    private fun drawBuildingBody(c: Canvas, b: Building, t: BuildingType, x0: Float, y0: Float, x1: Float, y1: Float, pc: Int) {
        val stone = 0xFFB8AC94.toInt()
        val darkStone = 0xFF8C8478.toInt()
        val wood = 0xFF8A6440.toInt()
        val plaster = 0xFFD8C8A0.toInt()
        val marble = 0xFFE4E0D4.toInt()
        val redRoof = 0xFFA8442E.toInt()
        val brownRoof = 0xFF7A4E30.toInt()
        val age = world.players[b.owner].age
        val mx = (x0 + x1) / 2f; val my = (y0 + y1) / 2f
        when (t) {
            BuildingType.TOWN_CENTER -> {
                val wallC = if (age >= Age.MEDIEVAL) 0xFFC4B8A0.toInt() else stone
                box(c, x0, y0, x1, y1, 0f, 0.7f, wallC)
                crenellations(c, x0, y0, x1, y1, 0.7f, wallC)
                val k = 0.75f
                box(c, mx - k, my - k, mx + k, my + k, 0.7f, 0.7f, shade(wallC, 1.05f))
                pyramidRoof(c, mx - k - 0.05f, my - k - 0.05f, mx + k + 0.05f, my + k + 0.05f, 1.4f, 0.75f, if (age >= Age.GUNPOWDER) 0xFF4A5A7A.toInt() else redRoof)
                // Door, windows and banners.
                door(c, mx, y1, 0.25f, 0.42f)
                windows(c, x0, y0, mx - 0.4f, y1, 0f, 0.7f, 1, 0)
                windows(c, mx + 0.4f, y0, x1, y1, 0f, 0.7f, 1, 0)
                windows(c, x0, y0, x1, my - 0.75f, 0f, 0.7f, 0, 1)
                windows(c, x0, my + 0.75f, x1, y1, 0f, 0.7f, 0, 1)
                windows(c, mx - k, my - k, mx + k, my + k, 0.7f, 0.7f, 2, 2)
                quad(c, pc, sx(x1, my - 0.5f), sy(x1, my - 0.5f) - up(0.6f), sx(x1, my - 0.2f), sy(x1, my - 0.2f) - up(0.6f), sx(x1, my - 0.2f), sy(x1, my - 0.2f) - up(0.2f), sx(x1, my - 0.5f), sy(x1, my - 0.5f) - up(0.25f))
                flag(c, mx, my, 2.15f, 0.8f, pc)
            }
            BuildingType.HOUSE -> {
                box(c, x0 + 0.1f, y0 + 0.1f, x1 - 0.1f, y1 - 0.1f, 0f, 0.55f, if (age >= Age.MEDIEVAL) 0xFFE0D0B0.toInt() else plaster)
                windows(c, x0 + 0.1f, y0 + 0.1f, mx - 0.2f, y1 - 0.1f, 0f, 0.55f, 1, 0)
                windows(c, x0 + 0.1f, y0 + 0.1f, x1 - 0.1f, my - 0.3f, 0f, 0.55f, 0, 1)
                gableRoof(c, x0, y0, x1, y1, 0.55f, 0.55f, if (age >= Age.GUNPOWDER) 0xFF6A3A2A.toInt() else brownRoof)
                door(c, mx + 0.05f, y1 - 0.1f, 0.13f, 0.34f)
                quad(c, pc, sx(x1 - 0.1f, my - 0.15f), sy(x1 - 0.1f, my - 0.15f) - up(0.42f), sx(x1 - 0.1f, my + 0.15f), sy(x1 - 0.1f, my + 0.15f) - up(0.42f), sx(x1 - 0.1f, my + 0.15f), sy(x1 - 0.1f, my + 0.15f) - up(0.22f), sx(x1 - 0.1f, my - 0.15f), sy(x1 - 0.1f, my - 0.15f) - up(0.22f))
            }
            BuildingType.MILL -> {
                box(c, x0 + 0.15f, y0 + 0.15f, x1 - 0.15f, y1 - 0.15f, 0f, 0.9f, plaster)
                windows(c, x0 + 0.15f, y0 + 0.15f, x1 - 0.15f, y1 - 0.15f, 0.35f, 0.5f, 1, 0)
                door(c, (x0 + x1) / 2f, y1 - 0.15f, 0.16f, 0.4f)
                pyramidRoof(c, x0 + 0.1f, y0 + 0.1f, x1 - 0.1f, y1 - 0.1f, 0.9f, 0.6f, brownRoof)
                val hx = sx(x1 - 0.15f, my); val hy = sy(x1 - 0.15f, my) - up(0.9f)
                val a0 = time * 1.2f
                for (k in 0 until 4) {
                    val a = a0 + k * PI.toFloat() / 2f
                    val ex = hx + cos(a) * 20f * s; val ey = hy + sin(a) * 20f * s
                    line(c, 0xFF5A3A22.toInt(), hx, hy, ex, ey, max(1f, 1.5f * s))
                    val px = -sin(a) * 4f * s; val py = cos(a) * 4f * s
                    quad(c, 0xDDEEE6D0.toInt(), hx + cos(a) * 6f * s, hy + sin(a) * 6f * s, ex, ey, ex + px, ey + py, hx + cos(a) * 6f * s + px, hy + sin(a) * 6f * s + py)
                }
                circle(c, 0xFF3A2A1A.toInt(), hx, hy, 2f * s)
            }
            BuildingType.LUMBER_CAMP, BuildingType.MINING_CAMP -> {
                val lumber = t == BuildingType.LUMBER_CAMP
                box(c, x0, y0, x1 - 0.4f, y1 - 0.2f, 0f, 0.5f, if (lumber) wood else 0xFF8A7F70.toInt())
                gableRoof(c, x0 - 0.05f, y0 - 0.05f, x1 - 0.35f, y1 - 0.15f, 0.5f, 0.4f, if (lumber) brownRoof else 0xFF5E5850.toInt())
                // Pile.
                val px = x1 - 0.1f; val py = y1 - 0.05f
                val bx = sx(px, py); val by = sy(px, py)
                if (lumber) {
                    for (k in 0 until 3) circle(c, 0xFFA07A50.toInt(), bx - 6f * s + k * 6f * s, by - 3f * s, 3f * s)
                    for (k in 0 until 2) circle(c, 0xFFB08A60.toInt(), bx - 3f * s + k * 6f * s, by - 8f * s, 3f * s)
                } else {
                    circle(c, 0xFF9A9A98.toInt(), bx - 5f * s, by - 3f * s, 4f * s)
                    circle(c, 0xFFE8C040.toInt(), bx + 3f * s, by - 4f * s, 4f * s)
                    circle(c, 0xFFB0B0AE.toInt(), bx - 1f * s, by - 8f * s, 3.5f * s)
                }
                flag(c, x0 + 0.1f, y1 - 0.2f, 0f, 1.0f, pc)
            }
            BuildingType.BARRACKS -> {
                box(c, x0, y0, x1, y1, 0f, 0.7f, if (age >= Age.MEDIEVAL) stone else wood)
                windows(c, x0, y0, x1, y1, 0.05f, 0.7f, 3, 2)
                door(c, mx, y1, 0.22f, 0.45f)
                gableRoof(c, x0 - 0.05f, y0 - 0.05f, x1 + 0.05f, y1 + 0.05f, 0.7f, 0.6f, redRoof)
                // Weapon rack.
                for (k in 0 until 3) {
                    val fx = x0 + 0.4f + k * 0.35f
                    line(c, 0xFF9A9A9A.toInt(), sx(fx, y1 + 0.15f), sy(fx, y1 + 0.15f), sx(fx, y1 + 0.15f), sy(fx, y1 + 0.15f) - up(0.8f), max(1f, s))
                }
                flag(c, x1 - 0.2f, y1 - 0.2f, 1.0f, 0.8f, pc)
            }
            BuildingType.ARCHERY_RANGE -> {
                box(c, x0, y0, x1, my, 0f, 0.6f, wood)
                gableRoof(c, x0 - 0.05f, y0 - 0.05f, x1 + 0.05f, my + 0.05f, 0.6f, 0.45f, 0xFF6A7A3A.toInt())
                for (k in 0 until 2) {
                    val fx = x0 + 0.5f + k * 1.0f; val fy = y1 - 0.2f
                    val tx = sx(fx, fy); val ty = sy(fx, fy) - up(0.4f)
                    line(c, 0xFF5A3A22.toInt(), tx, ty, tx, sy(fx, fy), max(1f, s))
                    circle(c, 0xFFF0E8D0.toInt(), tx, ty, 5f * s)
                    circle(c, 0xFFD03A2A.toInt(), tx, ty, 3.2f * s)
                    circle(c, 0xFFF0E8D0.toInt(), tx, ty, 1.4f * s)
                }
                flag(c, x0 + 0.1f, y0 + 0.1f, 1.0f, 0.7f, pc)
            }
            BuildingType.STABLE -> {
                box(c, x0, y0, x1, y1 - 0.5f, 0f, 0.6f, wood)
                door(c, mx, y1 - 0.5f, 0.3f, 0.45f)
                windows(c, x0, y0, x1, y1 - 0.5f, 0f, 0.6f, 0, 2)
                gableRoof(c, x0 - 0.05f, y0 - 0.05f, x1 + 0.05f, y1 - 0.45f, 0.6f, 0.5f, 0xFFC8A850.toInt())
                // Fence.
                val f = 0xFF6A4A2A.toInt()
                line(c, f, sx(x0, y1), sy(x0, y1) - up(0.2f), sx(x1, y1), sy(x1, y1) - up(0.2f), max(1f, s))
                for (k in 0..4) {
                    val fx = x0 + (x1 - x0) * k / 4f
                    line(c, f, sx(fx, y1), sy(fx, y1), sx(fx, y1), sy(fx, y1) - up(0.3f), max(1f, s))
                }
                flag(c, x1 - 0.2f, y0 + 0.2f, 1.0f, 0.7f, pc)
            }
            BuildingType.BLACKSMITH -> {
                box(c, x0, y0, x1, y1, 0f, 0.6f, darkStone)
                windows(c, x0, y0, x1, y1, 0f, 0.6f, 0, 2)
                door(c, mx, y1, 0.3f, 0.45f)
                pyramidRoof(c, x0 - 0.05f, y0 - 0.05f, x1 + 0.05f, y1 + 0.05f, 0.6f, 0.35f, 0xFF4A4440.toInt())
                box(c, x0 + 0.2f, y0 + 0.2f, x0 + 0.45f, y0 + 0.45f, 0.6f, 0.7f, darkStone)
                val chx = sx(x0 + 0.32f, y0 + 0.32f); val chy = sy(x0 + 0.32f, y0 + 0.32f) - up(1.3f)
                for (k in 0 until 3) {
                    val ph = (time * 0.6f + k / 3f) % 1f
                    circle(c, Color.argb((90 * (1 - ph)).toInt(), 80, 80, 80), chx + ph * 8f * s, chy - ph * 22f * s, (3f + ph * 5f) * s)
                }
                circle(c, 0xFFFF8A30.toInt(), sx(mx, y1), sy(mx, y1) - up(0.2f), 2.5f * s)
                flag(c, x1 - 0.1f, y1 - 0.1f, 0.6f, 0.6f, pc)
            }
            BuildingType.LIBRARY -> {
                box(c, x0, y0, x1, y1, 0f, 0.8f, marble, top = 0xFFB4AC9C.toInt())
                box(c, x0 + 0.08f, y0 + 0.08f, x1 - 0.08f, y1 - 0.08f, 0.8f, 0.08f, 0xFFC8C0B0.toInt(), mat = Materials.Kind.NONE)
                // Columns on the front faces.
                for (k in 1..4) {
                    val fx = x0 + (x1 - x0) * k / 5f
                    line(c, 0xFFB8B4A8.toInt(), sx(fx, y1), sy(fx, y1), sx(fx, y1), sy(fx, y1) - up(0.8f), max(1f, 1.6f * s))
                    val fy = y0 + (y1 - y0) * k / 5f
                    line(c, 0xFFC8C4B8.toInt(), sx(x1, fy), sy(x1, fy), sx(x1, fy), sy(x1, fy) - up(0.8f), max(1f, 1.6f * s))
                }
                val dx = sx(mx, my); val dy = sy(mx, my) - up(0.8f)
                fill.color = 0xFF5A7AA8.toInt()
                c.drawArc(dx - 24f * s, dy - 22f * s, dx + 24f * s, dy + 8f * s, 180f, 180f, true, fill)
                fill.color = 0xFF7A9AC8.toInt()
                c.drawArc(dx - 14f * s, dy - 19f * s, dx + 6f * s, dy + 2f * s, 200f, 80f, true, fill)
                flag(c, mx, my, 1.55f, 0.6f, pc)
            }
            BuildingType.SIEGE_WORKSHOP -> {
                box(c, x0, y0, x1, y1, 0f, 0.85f, wood)
                windows(c, x0, y0, x1, y1, 0.1f, 0.85f, 0, 2)
                gableRoof(c, x0 - 0.05f, y0 - 0.05f, x1 + 0.05f, y1 + 0.05f, 0.85f, 0.55f, 0xFF4E3A2A.toInt())
                door(c, mx, y1, 0.5f, 0.65f)
                flag(c, x1 - 0.2f, y0 + 0.2f, 1.4f, 0.6f, pc)
            }
            BuildingType.TEMPLE -> {
                box(c, x0 - 0.1f, y0 - 0.1f, x1 + 0.1f, y1 + 0.1f, 0f, 0.2f, 0xFFCFC8B4.toInt())
                box(c, x0 + 0.2f, y0 + 0.2f, x1 - 0.2f, y1 - 0.2f, 0.2f, 0.8f, marble)
                for (k in 0..5) {
                    val fx = x0 + 0.2f + (x1 - x0 - 0.4f) * k / 5f
                    line(c, 0xFFF8F4EA.toInt(), sx(fx, y1 - 0.1f), sy(fx, y1 - 0.1f) - up(0.2f), sx(fx, y1 - 0.1f), sy(fx, y1 - 0.1f) - up(1f), max(1f, 2f * s))
                }
                gableRoof(c, x0 + 0.1f, y0 + 0.1f, x1 - 0.1f, y1 - 0.1f, 1.0f, 0.5f, 0xFFC8A060.toInt())
                flag(c, x1 - 0.2f, my, 1.5f, 0.6f, pc)
            }
            BuildingType.MARKET -> {
                val tents = listOf(Triple(x0 + 0.5f, y0 + 0.5f, 0xFFC84A3A.toInt()), Triple(x1 - 0.5f, y0 + 0.6f, 0xFF3A7AC8.toInt()), Triple(x0 + 0.6f, y1 - 0.5f, 0xFFE0B040.toInt()), Triple(x1 - 0.5f, y1 - 0.5f, pc))
                for ((tx, ty, col) in tents) {
                    box(c, tx - 0.35f, ty - 0.35f, tx + 0.35f, ty + 0.35f, 0f, 0.35f, 0xFFD8C8A0.toInt())
                    pyramidRoof(c, tx - 0.45f, ty - 0.45f, tx + 0.45f, ty + 0.45f, 0.35f, 0.45f, col)
                }
                circle(c, 0xFF8A6440.toInt(), sx(mx, my), sy(mx, my) - 3f * s, 4f * s)
            }
            BuildingType.TOWER -> {
                val col = if (age >= Age.GUNPOWDER) 0xFFA8A090.toInt() else stone
                box(c, x0, y0, x1, y1, 0f, 2.0f, col)
                box(c, x0 - 0.12f, y0 - 0.12f, x1 + 0.12f, y1 + 0.12f, 2.0f, 0.15f, shade(col, 1.05f))
                crenellations(c, x0 - 0.12f, y0 - 0.12f, x1 + 0.12f, y1 + 0.12f, 2.15f, col)
                windows(c, x0, y0, x1, y1, 0.4f, 1.0f, 1, 1, slit = true)
                windows(c, x0, y0, x1, y1, 1.3f, 0.7f, 1, 1, slit = true)
                door(c, mx, y1, 0.14f, 0.4f)
                flag(c, mx, my, 2.3f, 0.7f, pc)
            }
            BuildingType.WALL -> {
                box(c, x0, y0, x1, y1, 0f, 0.6f, if (age >= Age.MEDIEVAL) 0xFFBAB2A0.toInt() else 0xFFA89C84.toInt())
                box(c, x0 + 0.3f, y0 + 0.3f, x1 - 0.3f, y1 - 0.3f, 0.6f, 0.15f, stone)
            }
            BuildingType.FORTRESS -> {
                box(c, x0, y0, x1, y1, 0f, 0.9f, darkStone)
                windows(c, x0 + 0.5f, y0 + 0.5f, x1 - 0.5f, y1 - 0.5f, 0.2f, 0.7f, 3, 3, slit = true)
                door(c, mx, y1, 0.35f, 0.6f)
                crenellations(c, x0, y0, x1, y1, 0.9f, darkStone)
                val k = 0.4f
                for ((tx, ty) in listOf(x0 to y0, x1 to y0, x0 to y1, x1 to y1)) {
                    box(c, tx - k, ty - k, tx + k, ty + k, 0f, 1.5f, stone)
                    pyramidRoof(c, tx - k - 0.05f, ty - k - 0.05f, tx + k + 0.05f, ty + k + 0.05f, 1.5f, 0.5f, 0xFF4A4A5A.toInt())
                }
                box(c, mx - 0.6f, my - 0.6f, mx + 0.6f, my + 0.6f, 0.9f, 0.9f, stone)
                crenellations(c, mx - 0.6f, my - 0.6f, mx + 0.6f, my + 0.6f, 1.8f, stone)
                flag(c, mx, my, 1.95f, 0.9f, pc)
            }
            BuildingType.WONDER -> {
                val gold = 0xFFD8B860.toInt()
                var inset = 0f
                for (k in 0 until 4) {
                    box(c, x0 + inset, y0 + inset, x1 - inset, y1 - inset, k * 0.55f, 0.55f, shade(gold, 0.9f + k * 0.05f))
                    inset += 0.38f
                }
                pyramidRoof(c, x0 + inset, y0 + inset, x1 - inset, y1 - inset, 2.2f, 0.7f, 0xFFFFE070.toInt())
                flag(c, mx, my, 2.9f, 0.8f, pc)
            }
            BuildingType.FARM -> {}
        }
    }

    /** Pixel-accurate hit test against the building body as drawn (shadows and bars excluded). */
    fun buildingHit(b: Building, px: Float, py: Float): Boolean {
        probe.eraseColor(0)
        probeCanvas.save()
        probeCanvas.translate(0.5f - px, 0.5f - py)
        drawBuildingShape(probeCanvas, b)
        probeCanvas.restore()
        return Color.alpha(probe.getPixel(0, 0)) >= 128
    }

    private fun drawBuildingShape(c: Canvas, b: Building) {
        val t = b.type
        if (t == BuildingType.FARM) { drawFarm(c, b); return }
        val inset = buildingInset(t)
        val x0 = b.minX + inset; val y0 = b.minY + inset; val x1 = b.maxX - inset; val y1 = b.maxY - inset
        if (!b.constructed) drawFoundation(c, b, x0, y0, x1, y1)
        else drawBuildingBody(c, b, t, x0, y0, x1, y1, world.players[b.owner].color)
    }

    /**
     * Own and allied units standing behind a building are drawn again on top as a translucent
     * silhouette in their player colour, so they can still be seen and tapped.
     */
    private fun drawHiddenUnits(c: Canvas) {
        occluders.clear()
        for (d in items) if (d.kind == 1) occluders += d.ref as Building
        if (occluders.isEmpty()) return
        for (d in items) {
            if (d.kind != 2) continue
            val u = d.ref as GameUnit
            if (u.owner != humanId && !world.isAlly(humanId, u.owner)) continue
            if (!isHidden(u)) continue
            val x = sx(u.x, u.y); val y = sy(u.x, u.y)
            ghostPaint.colorFilter = PorterDuffColorFilter(world.players[u.owner].color, PorterDuff.Mode.SRC_IN)
            ghostPaint.alpha = 0x90
            c.saveLayer(x - 34f * s, y - 52f * s, x + 34f * s, y + 9f * s, ghostPaint)
            figure(c, u)
            c.restore()
        }
    }

    /** True when the middle of [u]'s figure is covered by a building drawn in front of it. */
    fun isHidden(u: GameUnit): Boolean {
        val px = sx(u.x, u.y); val py = sy(u.x, u.y) - 16f * s
        val key = u.x + u.y
        for (b in occluders) {
            if (b.x + b.y <= key || !b.alive) continue
            if (!Picker.insideConvex(px, py, Picker.silhouette(b, cam, outline))) continue
            if (buildingHit(b, px, py)) return true
        }
        return false
    }

    // ------------------------------------------------------------------ units

    fun drawUnit(c: Canvas, u: GameUnit, selected: Boolean) {
        val x = sx(u.x, u.y); val y = sy(u.x, u.y)
        val p = world.players[u.owner]
        val color = p.color
        unitSprites.drawShadow(c, u.type, x, y, s)
        if (selected) selectionRing(c, x, y, if (UnitSprites.isMounted(u.type) || u.type == UnitType.CATAPULT) 13f else 9f, selColor(u.owner))
        figure(c, u)
        if (selected || u.hp < u.maxHp) {
            unitBar(c, x, y - (if (UnitSprites.isMounted(u.type)) 36f else 31f) * s, 16f * s, u.hp / u.maxHp, if (selected) 0 else color)
        }
    }

    private val barRect = RectF()

    /** Rounded, framed health bar for units; the fill colour runs red to green with the fraction. */
    private fun unitBar(c: Canvas, x: Float, y: Float, w: Float, frac: Float, color: Int) {
        val h = max(3f, 3.4f * s)
        val f = frac.coerceIn(0f, 1f)
        val l = x - w / 2; val r = x + w / 2
        val edge = max(1f, 1.1f * s)
        fill.color = 0xD8140E08.toInt()
        barRect.set(l - edge, y - edge, r + edge, y + h + edge)
        c.drawRoundRect(barRect, h * 0.6f + edge, h * 0.6f + edge, fill)
        fill.color = 0xFF3A2C24.toInt()
        barRect.set(l, y, r, y + h)
        c.drawRoundRect(barRect, h * 0.6f, h * 0.6f, fill)
        fill.color = when {
            f > 0.5f -> lerpColor(0xFFE0C030.toInt(), 0xFF58D848.toInt(), (f - 0.5f) * 2f)
            else -> lerpColor(0xFFE03828.toInt(), 0xFFE0C030.toInt(), f * 2f)
        }
        if (f > 0f) {
            barRect.set(l, y, l + max(h, w * f), y + h)
            c.drawRoundRect(barRect, h * 0.6f, h * 0.6f, fill)
            fill.color = 0x48FFFFFF
            barRect.set(l + h * 0.4f, y + h * 0.15f, l + max(h, w * f) - h * 0.4f, y + h * 0.5f)
            c.drawRoundRect(barRect, h * 0.25f, h * 0.25f, fill)
        }
        if (color != 0) {
            fill.color = 0xFF1E160F.toInt()
            c.drawCircle(l - edge, y + h / 2, h * 0.75f + edge * 0.5f, fill)
            fill.color = color
            c.drawCircle(l - edge, y + h / 2, h * 0.75f, fill)
        }
    }

    /** Glowing ground ellipse under a selected unit. */
    private fun selectionRing(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        val rx = r * s; val ry = r * 0.5f * s
        stroke.color = (color and 0x00FFFFFF) or 0x48000000
        stroke.strokeWidth = max(3f, 3.6f * s)
        c.drawOval(x - rx, y - ry, x + rx, y + ry, stroke)
        stroke.color = color
        stroke.strokeWidth = max(1.2f, 1.3f * s)
        c.drawOval(x - rx, y - ry, x + rx, y + ry, stroke)
        fill.color = (color and 0x00FFFFFF) or 0x22000000
        c.drawOval(x - rx, y - ry, x + rx, y + ry, fill)
    }

    private fun figure(c: Canvas, u: GameUnit) {
        val p = world.players[u.owner]
        // Screen-space facing: east in iso screen when cos-sin > 0.
        val dir = if (cos(u.facing) - sin(u.facing) >= 0f) 1f else -1f
        val anim: UnitSprites.Anim
        val frame: Int
        val working = u.type == UnitType.VILLAGER && (u.order == OrderType.GATHER || u.order == OrderType.BUILD)
        if (u.attackAnim > 0f) {
            anim = UnitSprites.Anim.ATTACK
            frame = when {
                // Gathering and healing keep the animation flag set, so cycle on their own clocks.
                working -> (u.animTime * 7f).toInt() % 4
                u.type == UnitType.HEALER -> (time * 6f).toInt() % 4
                else -> ((1f - u.attackAnim / 0.3f) * 4f).toInt().coerceIn(0, 3)
            }
        } else if (u.moving) {
            anim = UnitSprites.Anim.WALK
            val rate = if (UnitSprites.isMounted(u.type)) 14f else 9f
            frame = (u.animTime * rate).toInt() % 6
        } else {
            anim = UnitSprites.Anim.IDLE
            frame = if (((time * 1.1f + u.id * 0.37f) % 1f) < 0.5f) 0 else 1
        }
        val carry = when {
            u.type != UnitType.VILLAGER -> 0
            u.attackAnim > 0f && u.order == OrderType.BUILD -> 5
            u.attackAnim > 0f && u.order == OrderType.GATHER -> carryIndex(u.gatherResource ?: u.carryType)
            u.carryAmount > 0.5f -> carryIndex(u.carryType)
            else -> 0
        }
        unitSprites.draw(c, u.type, p.age, p.color, dir, anim, frame, carry, sx(u.x, u.y), sy(u.x, u.y), s)
    }

    private fun carryIndex(r: ResourceType?) = when (r) {
        null -> 0
        ResourceType.FOOD -> 1
        ResourceType.WOOD -> 2
        ResourceType.GOLD -> 3
        ResourceType.STONE -> 4
    }

    /** Draws a unit figure with feet at (x, y) using direct vector calls. Used for HUD icons. */
    fun drawFigure(
        c: Canvas, type: UnitType, color: Int, x: Float, y: Float, s: Float, dir: Float,
        anim: Float, moving: Float, attack: Float, age: Age, carry: ResourceType?, carryAmount: Float, order: OrderType,
    ) {
        val a = when {
            attack > 0f -> UnitSprites.Anim.ATTACK
            moving > 0f -> UnitSprites.Anim.WALK
            else -> UnitSprites.Anim.IDLE
        }
        val frame = when (a) {
            UnitSprites.Anim.ATTACK -> ((1f - attack / 0.3f) * 4f).toInt().coerceIn(0, 3)
            UnitSprites.Anim.WALK -> (anim * 9f).toInt() % 6
            UnitSprites.Anim.IDLE -> 0
        }
        val carryIdx = if (type == UnitType.VILLAGER && carryAmount > 0.5f) carryIndex(carry) else if (order == OrderType.BUILD) 5 else 0
        unitSprites.drawDirect(c, type, age, color, dir, a, frame, carryIdx, x, y, s)
    }

    // ------------------------------------------------------------------ projectiles & effects

    private fun drawProjectile(c: Canvas, p: Projectile) {
        val prog = p.progress()
        val dist = com.nsheaps.risetopower.core.dist(p.sx, p.sy, p.tx, p.ty)
        val arcH = if (p.kind == Projectile.BULLET) 0.02f else 0.25f
        val arc = sin(prog * PI.toFloat()) * dist * arcH
        val gx = sx(p.x, p.y); val gy = sy(p.x, p.y)
        val x = gx; val y = gy - up(0.5f + arc)
        // Direction of travel on screen: the ground velocity plus the vertical arc derivative.
        val ddx = sx(p.tx, p.ty) - sx(p.sx, p.sy)
        val ddy = sy(p.tx, p.ty) - sy(p.sx, p.sy) - cos(prog * PI.toFloat()) * PI.toFloat() * dist * arcH * 32f * s
        val len = kotlin.math.sqrt(ddx * ddx + ddy * ddy).coerceAtLeast(0.01f)
        val ux = ddx / len; val uy = ddy / len
        // Ground shadow.
        ellipse(c, 0x38000000, gx, gy, 3f * s, 1.4f * s)
        val team = world.players[p.owner].color
        when (p.kind) {
            Projectile.ARROW -> {
                val l = 9f * s
                line(c, 0x30201810, x - ux * l * 2.2f, y - uy * l * 2.2f, x - ux * l, y - uy * l, max(1f, 1.4f * s))
                line(c, 0xFF4A3220.toInt(), x - ux * l, y - uy * l, x, y, max(1f, 1.1f * s))
                // Fletching in the owner's colour, steel head.
                val px = -uy; val py = ux
                val fl = l - 2.2f * s
                line(c, team, x - ux * fl, y - uy * fl, x - ux * (l + 0.5f * s) + px * 1.1f * s, y - uy * (l + 0.5f * s) + py * 1.1f * s, max(0.7f, 0.8f * s))
                line(c, team, x - ux * fl, y - uy * fl, x - ux * (l + 0.5f * s) - px * 1.1f * s, y - uy * (l + 0.5f * s) - py * 1.1f * s, max(0.7f, 0.8f * s))
                line(c, 0xFFD8DCE4.toInt(), x - ux * 2f * s, y - uy * 2f * s, x + ux * 1.5f * s, y + uy * 1.5f * s, max(1f, 1.6f * s))
            }
            Projectile.STONE -> {
                val r = 3f * s
                circle(c, 0x40201810, x - ux * r * 2.5f, y - uy * r * 2.5f, r * 0.8f)
                circle(c, 0xFF1E160F.toInt(), x, y, r + max(0.8f, 0.7f * s))
                circle(c, 0xFF4E4A48.toInt(), x, y, r)
                circle(c, 0xFF7A7672.toInt(), x - r * 0.3f, y - r * 0.35f, r * 0.55f)
                circle(c, 0x60FFFFFF, x - r * 0.4f, y - r * 0.45f, r * 0.22f)
            }
            else -> {
                val l = 7f * s
                line(c, 0x50FFE080, x - ux * l * 1.6f, y - uy * l * 1.6f, x, y, max(1.5f, 2.6f * s))
                line(c, 0xFFFFF0B0.toInt(), x - ux * l, y - uy * l, x, y, max(1f, 1.2f * s))
                circle(c, 0xFFFFFFFF.toInt(), x, y, max(1f, 1.3f * s))
            }
        }
    }

    fun addCueEffect(cue: SoundCue) {
        // Called from the game thread when sound cues are drained.
        when (cue.kind) {
            SoundCue.COLLAPSE -> pendingEffects += Effect(Effect.DUST, cue.x, cue.y, 0f, 1.6f)
            SoundCue.DEATH -> pendingEffects += Effect(Effect.PUFF, cue.x, cue.y, 0f, 0.8f)
            SoundCue.SIEGE -> {}
            else -> {}
        }
    }

    private val pendingEffects = ArrayList<Effect>()

    private fun drawEffects(c: Canvas, ui: UiState, dt: Float) {
        ui.effects.addAll(pendingEffects)
        pendingEffects.clear()
        val it = ui.effects.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.t += dt
            if (e.t >= e.life) { it.remove(); continue }
            val f = e.t / e.life
            val x = sx(e.x, e.y); val y = sy(e.x, e.y)
            when (e.kind) {
                Effect.DUST -> {
                    for (k in 0 until 6) {
                        val a = k * 1.05f
                        val r = (10f + f * 30f) * s
                        circle(c, Color.argb((150 * (1 - f)).toInt(), 140, 120, 90), x + cos(a) * r, y + sin(a) * r * 0.5f - f * 10f * s, (8f + f * 10f) * s)
                    }
                }
                Effect.PUFF -> {
                    // A few smoke wisps drifting up and apart.
                    val a = Color.argb((110 * (1 - f) * (1 - f)).toInt(), 120, 110, 100)
                    circle(c, a, x, y - 8f * s - f * 10f * s, (3.5f + f * 6f) * s)
                    circle(c, a, x - (3f + f * 6f) * s, y - 5f * s - f * 7f * s, (2.5f + f * 4f) * s)
                    circle(c, a, x + (3f + f * 5f) * s, y - 7f * s - f * 9f * s, (2f + f * 4f) * s)
                }
                Effect.MOVE_MARK, Effect.ATTACK_MARK -> {
                    stroke.color = if (e.kind == Effect.MOVE_MARK) Color.argb((255 * (1 - f)).toInt(), 120, 255, 110) else Color.argb((255 * (1 - f)).toInt(), 255, 80, 60)
                    stroke.strokeWidth = max(1.5f, 2f * s)
                    val r = (14f * (1 - f) + 4f) * s
                    c.drawOval(x - r, y - r * 0.5f, x + r, y + r * 0.5f, stroke)
                }
                Effect.SPARK -> circle(c, Color.argb((220 * (1 - f)).toInt(), 255, 230, 120), x, y - 10f * s, 3f * s)
            }
        }
    }

    private fun drawPlacement(c: Canvas, ui: UiState) {
        val t = ui.placing ?: return
        val col = if (ui.placeValid) 0x6650FF50 else 0x66FF4040
        val ws = ui.wallStart
        if (t == BuildingType.WALL && ws != null) {
            val (ax, ay) = ws
            val bx = ui.placeX; val by = ui.placeY
            val steps = max(abs(bx - ax), abs(by - ay))
            for (k in 0..steps) {
                val f = if (steps == 0) 0f else k.toFloat() / steps
                val x = kotlin.math.round(ax + (bx - ax) * f); val y = kotlin.math.round(ay + (by - ay) * f)
                groundRect(c, col, x, y, x + 1f, y + 1f)
            }
            return
        }
        val x0 = ui.placeX.toFloat(); val y0 = ui.placeY.toFloat()
        groundRect(c, col, x0, y0, x0 + t.size, y0 + t.size)
        groundOutline(c, if (ui.placeValid) 0xFF80FF80.toInt() else 0xFFFF6060.toInt(), x0, y0, x0 + t.size, y0 + t.size, max(1.5f, 2f * s))
        // Border radius preview for territory buildings.
        if (t.territory > 0f) {
            stroke.color = 0x88FFFFFF.toInt()
            stroke.strokeWidth = max(1f, s)
            val r = t.territory + world.players[humanId].territoryBonus
            val cx = sx(x0 + t.size / 2f, y0 + t.size / 2f); val cy = sy(x0 + t.size / 2f, y0 + t.size / 2f)
            c.drawOval(cx - r * 45.25f * s, cy - r * 22.6f * s, cx + r * 45.25f * s, cy + r * 22.6f * s, stroke)
        }
    }

    // ------------------------------------------------------------------ icons

    fun drawUnitIcon(c: Canvas, type: UnitType, color: Int, r: RectF, age: Age) {
        val size = min(r.width(), r.height())
        val sc = size / (if (type == UnitType.CATAPULT || UnitSprites.isMounted(type)) 46f else 42f)
        drawFigure(c, type, color, r.centerX(), r.bottom - size * 0.12f, sc, 1f, 0.3f, 0f, 0f, age, null, 0f, OrderType.IDLE)
    }

    fun drawBuildingIcon(c: Canvas, type: BuildingType, color: Int, r: RectF, owner: Int) {
        // Temporarily replace the camera with an icon projection.
        val saved = floatArrayOf(cam.cx, cam.cy, cam.zoom, cam.viewW, cam.viewH)
        val size = min(r.width(), r.height())
        val fit = when (type.size) { 1 -> 1.6f; 2 -> 2.6f; 3 -> 3.6f; else -> 4.6f }
        cam.zoom = size / (fit * 64f) / cam.baseScale
        cam.viewW = r.width(); cam.viewH = r.height()
        val half = type.size / 2f
        cam.cx = 0f; cam.cy = (half + half) * IsoCamera.HALF_H - size * 0.12f / cam.scale
        c.save()
        c.translate(r.left, r.top)
        c.clipRect(0f, 0f, r.width(), r.height())
        val b = Building(-1, owner, type, 0, 0)
        b.constructed = true
        b.hp = 1f; b.maxHp = 1f
        if (type == BuildingType.FARM) {
            b.farmerId = 1
            drawFarm(c, b)
        } else {
            val inset = when (type) { BuildingType.WALL -> 0.08f; BuildingType.TOWER -> 0.45f; else -> 0.2f }
            drawBuildingBody(c, b, type, inset, inset, type.size - inset, type.size - inset, color)
        }
        c.restore()
        cam.cx = saved[0]; cam.cy = saved[1]; cam.zoom = saved[2]; cam.viewW = saved[3]; cam.viewH = saved[4]
    }

    @Suppress("unused")
    private fun blend(a: Int, b: Int, t: Float) = lerpColor(a, b, t)

    companion object {
        /** World units covered by one material texture tile. */
        private const val MAT_TILE = 0.5f

        /** Overall drawn height of a building, in tile half-widths (also used for hit testing). */
        fun buildingHeight(t: BuildingType) = when (t) {
            BuildingType.TOWER -> 2.6f
            BuildingType.WONDER -> 3f
            BuildingType.FORTRESS, BuildingType.TOWN_CENTER -> 2.2f
            BuildingType.WALL -> 0.9f
            BuildingType.FARM -> 0.2f
            else -> 1.4f
        }

        /** How far the drawn body is inset from the building's footprint, in tiles. */
        fun buildingInset(t: BuildingType) = when (t) {
            BuildingType.WALL -> 0.08f
            BuildingType.TOWER -> 0.45f
            else -> 0.2f
        }
    }
}
