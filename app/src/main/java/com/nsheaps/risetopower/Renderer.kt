package com.nsheaps.risetopower

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
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
    private val path = Path()
    private val matrix = Matrix()
    private val bounds = IntArray(4)
    private val materials = Materials()
    private val sprites = NatureSprites()
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
        cam.tileMatrix(matrix, TerrainLayers.TERR_PPT.toFloat())
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
            cam.tileMatrix(matrix, 1f)
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

    private fun drawMountain(c: Canvas, x: Int, y: Int) {
        val e = world.map.elevation[world.map.idx(x, y)]
        val hsh = ((x * 73856093) xor (y * 19349663)) and 255
        val h = 0.5f + (hsh / 255f) * 0.6f + (e - 0.7f).coerceAtLeast(0f) * 2f
        val cxw = x + 0.5f + ((hsh and 7) - 3.5f) * 0.04f
        val cyw = y + 0.5f
        val base = 0xFF7A7064.toInt()
        val ax = sx(cxw, cyw); val ay = sy(cxw, cyw) - up(h)
        val lx = sx(x - 0.05f, y + 1.05f); val ly = sy(x - 0.05f, y + 1.05f)
        val bx = sx(x + 1.05f, y + 1.05f); val by = sy(x + 1.05f, y + 1.05f)
        val rx = sx(x + 1.05f, y - 0.05f); val ry = sy(x + 1.05f, y - 0.05f)
        tri(c, shade(base, 0.72f), lx, ly, bx, by, ax, ay)
        tri(c, shade(base, 0.95f), bx, by, rx, ry, ax, ay)
        if (h > 0.95f) {
            // Snow cap.
            val t = 0.28f
            tri(c, 0xFFE8ECF0.toInt(), ax + (lx - ax) * t, ay + (ly - ay) * t, ax + (bx - ax) * t, ay + (by - ay) * t, ax, ay)
            tri(c, 0xFFFFFFFF.toInt(), ax + (bx - ax) * t, ay + (by - ay) * t, ax + (rx - ax) * t, ay + (ry - ay) * t, ax, ay)
        }
    }

    private class NodeLook(var kind: NatureSprites.Kind = NatureSprites.Kind.CONIFER, var variant: Int = 0, var level: Int = 0, var mirror: Boolean = false)

    private val look = NodeLook()

    private fun lookOf(n: ResourceNode): NodeLook {
        val left = n.amount / n.kind.amount
        val v = n.variant % 12
        look.level = 0; look.mirror = false; look.variant = v
        when (n.kind) {
            NodeKind.TREE -> { look.kind = if (v % 2 == 0) NatureSprites.Kind.CONIFER else NatureSprites.Kind.LEAFY; look.mirror = v % 3 == 0 }
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
        sprites.draw(c, l.kind, l.variant, l.level, sx(n.x, n.y), sy(n.x, n.y), s, l.mirror)
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
            ellipse(c, 0x33000000, sx(b.x + 0.3f, b.y + 0.3f), sy(b.x + 0.3f, b.y + 0.3f), t.size * 26f * s, t.size * 11f * s)
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
        val h = buildingHeight(b.type) * 0.6f
        val ph = h * b.progress
        if (ph > 0.02f) box(c, x0, y0, x1, y1, 0f, ph, 0xFFB09070.toInt())
        // Scaffold poles.
        val pole = 0xFF6A4A2A.toInt()
        val w = max(1f, 1.3f * s)
        for ((px, py) in listOf(x0 to y1, x1 to y1, x1 to y0)) {
            line(c, pole, sx(px, py), sy(px, py), sx(px, py), sy(px, py) - up(h), w)
        }
        line(c, pole, sx(x0, y1), sy(x0, y1) - up(h), sx(x1, y1), sy(x1, y1) - up(h), w)
        line(c, pole, sx(x1, y1), sy(x1, y1) - up(h), sx(x1, y0), sy(x1, y0) - up(h), w)
        line(c, pole, sx(x0, y1), sy(x0, y1) - up(h * 0.5f), sx(x1, y1), sy(x1, y1) - up(h * 0.5f), w)
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
                // Door and banners.
                quad(c, 0xFF4A3020.toInt(), sx(mx - 0.25f, y1), sy(mx - 0.25f, y1), sx(mx + 0.25f, y1), sy(mx + 0.25f, y1), sx(mx + 0.25f, y1), sy(mx + 0.25f, y1) - up(0.4f), sx(mx - 0.25f, y1), sy(mx - 0.25f, y1) - up(0.4f))
                quad(c, pc, sx(x1, my - 0.5f), sy(x1, my - 0.5f) - up(0.6f), sx(x1, my - 0.2f), sy(x1, my - 0.2f) - up(0.6f), sx(x1, my - 0.2f), sy(x1, my - 0.2f) - up(0.2f), sx(x1, my - 0.5f), sy(x1, my - 0.5f) - up(0.25f))
                flag(c, mx, my, 2.15f, 0.8f, pc)
            }
            BuildingType.HOUSE -> {
                box(c, x0 + 0.1f, y0 + 0.1f, x1 - 0.1f, y1 - 0.1f, 0f, 0.55f, if (age >= Age.MEDIEVAL) 0xFFE0D0B0.toInt() else plaster)
                gableRoof(c, x0, y0, x1, y1, 0.55f, 0.55f, if (age >= Age.GUNPOWDER) 0xFF6A3A2A.toInt() else brownRoof)
                quad(c, 0xFF4A3020.toInt(), sx(mx - 0.1f, y1 - 0.1f), sy(mx - 0.1f, y1 - 0.1f), sx(mx + 0.15f, y1 - 0.1f), sy(mx + 0.15f, y1 - 0.1f), sx(mx + 0.15f, y1 - 0.1f), sy(mx + 0.15f, y1 - 0.1f) - up(0.32f), sx(mx - 0.1f, y1 - 0.1f), sy(mx - 0.1f, y1 - 0.1f) - up(0.32f))
                quad(c, pc, sx(x1 - 0.1f, my - 0.15f), sy(x1 - 0.1f, my - 0.15f) - up(0.42f), sx(x1 - 0.1f, my + 0.15f), sy(x1 - 0.1f, my + 0.15f) - up(0.42f), sx(x1 - 0.1f, my + 0.15f), sy(x1 - 0.1f, my + 0.15f) - up(0.22f), sx(x1 - 0.1f, my - 0.15f), sy(x1 - 0.1f, my - 0.15f) - up(0.22f))
            }
            BuildingType.MILL -> {
                box(c, x0 + 0.15f, y0 + 0.15f, x1 - 0.15f, y1 - 0.15f, 0f, 0.9f, plaster)
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
                gableRoof(c, x0 - 0.05f, y0 - 0.05f, x1 + 0.05f, y1 + 0.05f, 0.85f, 0.55f, 0xFF4E3A2A.toInt())
                quad(c, 0xFF2A1A10.toInt(), sx(mx - 0.5f, y1), sy(mx - 0.5f, y1), sx(mx + 0.5f, y1), sy(mx + 0.5f, y1), sx(mx + 0.5f, y1), sy(mx + 0.5f, y1) - up(0.65f), sx(mx - 0.5f, y1), sy(mx - 0.5f, y1) - up(0.65f))
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
                quad(c, 0xFF2A2018.toInt(), sx(x1, my - 0.1f), sy(x1, my - 0.1f) - up(1.4f), sx(x1, my + 0.1f), sy(x1, my + 0.1f) - up(1.4f), sx(x1, my + 0.1f), sy(x1, my + 0.1f) - up(1.1f), sx(x1, my - 0.1f), sy(x1, my - 0.1f) - up(1.1f))
                flag(c, mx, my, 2.3f, 0.7f, pc)
            }
            BuildingType.WALL -> {
                box(c, x0, y0, x1, y1, 0f, 0.6f, if (age >= Age.MEDIEVAL) 0xFFBAB2A0.toInt() else 0xFFA89C84.toInt())
                box(c, x0 + 0.3f, y0 + 0.3f, x1 - 0.3f, y1 - 0.3f, 0.6f, 0.15f, stone)
            }
            BuildingType.FORTRESS -> {
                box(c, x0, y0, x1, y1, 0f, 0.9f, darkStone)
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
            c.saveLayer(x - 24f * s, y - 44f * s, x + 24f * s, y + 6f * s, ghostPaint)
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
        ellipse(c, 0x40000000, x, y, 7f * s * (if (isMounted(u.type)) 1.6f else 1f), 3f * s)
        if (selected) {
            stroke.color = selColor(u.owner)
            stroke.strokeWidth = max(1.2f, 1.4f * s)
            val r = if (isMounted(u.type) || u.type == UnitType.CATAPULT) 13f else 9f
            c.drawOval(x - r * s, y - r * 0.5f * s, x + r * s, y + r * 0.5f * s, stroke)
        }
        figure(c, u)
        if (selected || u.hp < u.maxHp) {
            hpBar(c, x, y - (if (isMounted(u.type)) 34f else 30f) * s, 16f * s, u.hp / u.maxHp, if (selected) 0 else color)
        }
    }

    private fun figure(c: Canvas, u: GameUnit) {
        val p = world.players[u.owner]
        // Screen-space facing: east in iso screen when cos-sin > 0.
        val dir = if (cos(u.facing) - sin(u.facing) >= 0f) 1f else -1f
        drawFigure(c, u.type, p.color, sx(u.x, u.y), sy(u.x, u.y), s, dir, u.animTime, if (u.moving) 1f else 0f, u.attackAnim, p.age, u.carryType, u.carryAmount, u.order)
    }

    private fun isMounted(t: UnitType) = t == UnitType.SCOUT || t == UnitType.HORSEMAN || t == UnitType.HORSE_ARCHER

    /** Draws a unit figure with feet at (x, y). Also used for HUD icons. */
    fun drawFigure(
        c: Canvas, type: UnitType, color: Int, x: Float, y: Float, s: Float, dir: Float,
        anim: Float, moving: Float, attack: Float, age: Age, carry: ResourceType?, carryAmount: Float, order: OrderType,
    ) {
        val skin = 0xFFE2B88E.toInt()
        val swing = sin(anim * 11f) * 2.2f * s * moving
        val lw = max(1f, 1.6f * s)
        val dark = 0xFF2A2018.toInt()
        val armor = when {
            age >= Age.GUNPOWDER -> 0xFF3A4458.toInt()
            age >= Age.MEDIEVAL -> 0xFFA8A8B0.toInt()
            age >= Age.CLASSICAL -> 0xFFC09040.toInt()
            else -> 0xFF8A6440.toInt()
        }
        val atkSwing = if (attack > 0f) sin(attack * 20f) else 0f
        val ink = 0xC0201810.toInt()
        val ow = max(0.8f, 0.9f * s)
        fun legs(top: Float) {
            line(c, ink, x - 2f * s, y - top, x - 2f * s + swing, y + 0.5f * s, lw + ow * 2)
            line(c, ink, x + 2f * s, y - top, x + 2f * s - swing, y + 0.5f * s, lw + ow * 2)
            line(c, dark, x - 2f * s, y - top, x - 2f * s + swing, y, lw)
            line(c, dark, x + 2f * s, y - top, x + 2f * s - swing, y, lw)
            // Boots.
            line(c, 0xFF4A3424.toInt(), x - 2f * s + swing, y - 1.5f * s, x - 2f * s + swing, y, lw * 1.3f)
            line(c, 0xFF4A3424.toInt(), x + 2f * s - swing, y - 1.5f * s, x + 2f * s - swing, y, lw * 1.3f)
        }
        fun torso(col: Int, top: Float, bottom: Float, w: Float = 4.2f) {
            fill.color = ink
            c.drawRoundRect(x - w * s - ow, y - top * s - ow, x + w * s + ow, y - bottom * s + ow, 2.4f * s, 2.4f * s, fill)
            fill.color = shade(col, 0.78f)
            c.drawRoundRect(x - w * s, y - top * s, x + w * s, y - bottom * s, 2f * s, 2f * s, fill)
            // Lit side facing the light (upper left).
            fill.color = shade(col, 1.12f)
            c.drawRoundRect(x - w * s, y - top * s, x + w * 0.15f * s, y - bottom * s - 0.5f * s, 2f * s, 2f * s, fill)
            fill.color = 0x30FFFFFF
            c.drawRect(x - w * s + 0.8f * s, y - top * s + 0.6f * s, x + w * s - 0.8f * s, y - top * s + 1.6f * s, fill)
        }
        fun head(hy: Float, helmet: Int?) {
            circle(c, ink, x, y - hy * s, 3.2f * s + ow)
            circle(c, shade(skin, 0.82f), x, y - hy * s, 3.2f * s)
            circle(c, skin, x - 0.6f * s, y - (hy + 0.6f) * s, 2.5f * s)
            circle(c, 0xFF2A1A12.toInt(), x + dir * 1.3f * s, y - (hy + 0.3f) * s, 0.45f * s) // eye
            if (helmet != null) {
                fill.color = helmet
                c.drawArc(x - 3.6f * s, y - (hy + 3.8f) * s, x + 3.6f * s, y - (hy - 2f) * s, 180f, 180f, true, fill)
                fill.color = 0x50FFFFFF
                c.drawArc(x - 2.8f * s, y - (hy + 3.3f) * s, x + 0.5f * s, y - (hy - 1f) * s, 200f, 70f, true, fill)
            } else {
                fill.color = 0xFF5A3A22.toInt() // hair
                c.drawArc(x - 3.3f * s, y - (hy + 3.4f) * s, x + 3.3f * s, y - (hy - 1.2f) * s, 180f, 180f, true, fill)
            }
        }
        fun horse(col: Int) {
            for (k in 0 until 4) {
                val lx = x + (-7f + k * 4.5f) * s
                val ph = if (k % 2 == 0) swing else -swing
                line(c, ink, lx, y - 8f * s, lx + ph, y + 0.5f * s, lw + ow * 2)
                line(c, shade(col, if (k % 2 == 0) 0.6f else 0.75f), lx, y - 8f * s, lx + ph, y, lw)
            }
            ellipse(c, ink, x, y - 10f * s, 10f * s + ow, 4.6f * s + ow)
            ellipse(c, shade(col, 0.8f), x, y - 10f * s, 10f * s, 4.6f * s)
            ellipse(c, shade(col, 1.15f), x - 1.5f * s, y - 11.2f * s, 7.5f * s, 2.8f * s)
            line(c, ink, x + dir * 8f * s, y - 12f * s, x + dir * 12f * s, y - 18f * s, max(1f, 3.4f * s) + ow * 2)
            line(c, col, x + dir * 8f * s, y - 12f * s, x + dir * 12f * s, y - 18f * s, max(1f, 3.4f * s))
            ellipse(c, ink, x + dir * 13.5f * s, y - 18.5f * s, 3.2f * s + ow, 2f * s + ow)
            ellipse(c, col, x + dir * 13.5f * s, y - 18.5f * s, 3.2f * s, 2f * s)
            // Mane and tail.
            line(c, 0xFF2A1E14.toInt(), x + dir * 8.5f * s, y - 14f * s, x + dir * 11.5f * s, y - 19.5f * s, max(1f, 1.4f * s))
            line(c, shade(col, 0.5f), x - dir * 10f * s, y - 11f * s, x - dir * 13f * s, y - 5f * s, max(1f, 1.8f * s))
        }
        when (type) {
            UnitType.VILLAGER -> {
                legs(8f * s)
                torso(shade(color, 0.85f), 18f, 7f)
                // Belt in neutral colour so citizens read differently from soldiers.
                fill.color = 0xFF8A6440.toInt()
                c.drawRect(x - 4.2f * s, y - 10f * s, x + 4.2f * s, y - 8.5f * s, fill)
                head(21f, null)
                circle(c, 0xFFC8A060.toInt(), x, y - 23f * s, 2.4f * s) // straw hat
                val working = order == OrderType.GATHER || order == OrderType.BUILD
                val a = if (working && attack > 0f) -0.6f + sin(anim * 9f) * 0.9f else 0.6f
                val hx = x + dir * 4f * s; val hy = y - 15f * s
                val ex = hx + dir * cos(a) * 9f * s; val ey = hy - sin(a) * 9f * s
                line(c, 0xFF6A4A2A.toInt(), hx, hy, ex, ey, lw)
                if (working) circle(c, 0xFF9A9AA0.toInt(), ex, ey, 1.8f * s)
                if (carry != null && carryAmount > 0.5f) {
                    val col = when (carry) {
                        ResourceType.FOOD -> 0xFFD04A3A.toInt()
                        ResourceType.WOOD -> 0xFF8A5A2A.toInt()
                        ResourceType.GOLD -> 0xFFF0C838.toInt()
                        ResourceType.STONE -> 0xFFB0B0B0.toInt()
                    }
                    fill.color = col
                    c.drawRect(x - dir * 7.5f * s, y - 18f * s, x - dir * 3.5f * s, y - 12f * s, fill)
                }
            }
            UnitType.SPEARMAN, UnitType.WARRIOR -> {
                legs(8f * s)
                torso(color, 19f, 7f)
                fill.color = armor
                c.drawRect(x - 4.2f * s, y - 19f * s, x + 4.2f * s, y - 15f * s, fill)
                head(22f, armor)
                if (type == UnitType.SPEARMAN) {
                    val jab = if (attack > 0f) atkSwing * 4f * s else 0f
                    if (age >= Age.GUNPOWDER) {
                        line(c, 0xFF3A2A1A.toInt(), x + dir * (2f * s + jab), y - 20f * s, x + dir * (14f * s + jab), y - 13f * s, max(1f, 1.8f * s))
                        line(c, 0xFFC8C8D0.toInt(), x + dir * (14f * s + jab), y - 13f * s, x + dir * (18f * s + jab), y - 11f * s, lw)
                    } else {
                        line(c, 0xFF6A4A2A.toInt(), x + dir * (5f * s + jab * 0.3f), y - 4f * s, x + dir * (7f * s + jab), y - 34f * s, lw)
                        tri(c, 0xFFC8C8D0.toInt(), x + dir * (5.5f * s + jab), y - 34f * s, x + dir * (8.5f * s + jab), y - 34f * s, x + dir * (7f * s + jab), y - 39f * s)
                    }
                    circle(c, shade(color, 1.15f), x - dir * 4f * s, y - 13f * s, 4.2f * s)
                    circle(c, armor, x - dir * 4f * s, y - 13f * s, 1.5f * s)
                } else {
                    val a = if (attack > 0f) 1.4f - atkSwing * 1.6f else 1.1f
                    val hx = x + dir * 4f * s; val hy = y - 15f * s
                    line(c, 0xFFD0D0D8.toInt(), hx, hy, hx + dir * cos(a) * 11f * s, hy - sin(a) * 11f * s, max(1f, 1.9f * s))
                    fill.color = shade(color, 0.75f)
                    c.drawRect(x - dir * 8f * s - 4f * s, y - 19f * s, x - dir * 8f * s + 4f * s, y - 7f * s, fill)
                    line(c, armor, x - dir * 8f * s, y - 19f * s, x - dir * 8f * s, y - 7f * s, lw)
                }
            }
            UnitType.ARCHER -> {
                legs(8f * s)
                torso(shade(color, 0.95f), 18f, 7f, 3.8f)
                head(21f, if (age >= Age.MEDIEVAL) armor else null)
                if (age >= Age.GUNPOWDER) {
                    val kick = if (attack > 0f) 2f * s else 0f
                    line(c, 0xFF3A2A1A.toInt(), x - dir * (2f * s + kick), y - 16f * s, x + dir * (14f * s - kick), y - 18f * s, max(1f, 2f * s))
                    if (attack > 0.15f) circle(c, 0xCCFFE080.toInt(), x + dir * 16f * s, y - 18f * s, 2.4f * s)
                } else {
                    stroke.color = 0xFF6A4A2A.toInt()
                    stroke.strokeWidth = max(1f, 1.4f * s)
                    val bx = x + dir * 6f * s
                    c.drawArc(bx - 5f * s, y - 26f * s, bx + 5f * s, y - 8f * s, if (dir > 0) -80f else 100f, 160f, false, stroke)
                    line(c, 0xFFE0D8C0.toInt(), bx, y - 25f * s, bx, y - 9f * s, max(0.8f, 0.6f * s))
                }
                fill.color = 0xFF6A4A2A.toInt()
                c.drawRect(x - dir * 6f * s, y - 21f * s, x - dir * 3.5f * s, y - 12f * s, fill)
            }
            UnitType.SCOUT, UnitType.HORSEMAN, UnitType.HORSE_ARCHER -> {
                horse(if (type == UnitType.SCOUT) 0xFFB08050.toInt() else if (type == UnitType.HORSEMAN) 0xFF5A4030.toInt() else 0xFF8A6A48.toInt())
                fill.color = color
                c.drawRoundRect(x - 3.5f * s, y - 24f * s, x + 3.5f * s, y - 13f * s, 2f * s, 2f * s, fill)
                circle(c, skin, x, y - 27f * s, 3f * s)
                if (type == UnitType.HORSEMAN) {
                    fill.color = armor
                    c.drawArc(x - 3.4f * s, y - 31f * s, x + 3.4f * s, y - 25f * s, 180f, 180f, true, fill)
                    val jab = if (attack > 0f) atkSwing * 4f * s else 0f
                    line(c, 0xFF6A4A2A.toInt(), x - dir * 6f * s, y - 16f * s, x + dir * (18f * s + jab), y - 24f * s, lw)
                    tri(c, color, x + dir * 8f * s, y - 22f * s, x + dir * 13f * s, y - 23.5f * s, x + dir * 9f * s, y - 19f * s)
                } else if (type == UnitType.HORSE_ARCHER) {
                    stroke.color = 0xFF6A4A2A.toInt()
                    stroke.strokeWidth = max(1f, 1.3f * s)
                    val bx = x + dir * 5f * s
                    c.drawArc(bx - 4f * s, y - 30f * s, bx + 4f * s, y - 16f * s, if (dir > 0) -80f else 100f, 160f, false, stroke)
                } else {
                    tri(c, 0xFF6A4A2A.toInt(), x - 3f * s, y - 29f * s, x + 3f * s, y - 29f * s, x, y - 33f * s)
                }
            }
            UnitType.CATAPULT -> {
                val wood = 0xFF8A6440.toInt()
                fill.color = shade(wood, 0.8f)
                c.drawRect(x - 13f * s, y - 9f * s, x + 13f * s, y - 4f * s, fill)
                circle(c, 0xFF4A3420.toInt(), x - 9f * s, y - 3f * s, 4f * s)
                circle(c, 0xFF4A3420.toInt(), x + 9f * s, y - 3f * s, 4f * s)
                circle(c, wood, x - 9f * s, y - 3f * s, 1.5f * s)
                circle(c, wood, x + 9f * s, y - 3f * s, 1.5f * s)
                line(c, wood, x - 6f * s, y - 9f * s, x, y - 20f * s, max(1f, 2.4f * s))
                line(c, wood, x + 6f * s, y - 9f * s, x, y - 20f * s, max(1f, 2.4f * s))
                if (age >= Age.GUNPOWDER) {
                    line(c, 0xFF3A3A40.toInt(), x - dir * 4f * s, y - 12f * s, x + dir * 15f * s, y - 16f * s, max(1f, 5f * s))
                } else {
                    val a = if (attack > 0f) 1.9f - attack * 3f else 0.5f
                    val ex = x - dir * cos(a) * 17f * s; val ey = y - 20f * s - sin(a) * 10f * s
                    line(c, 0xFF6A4A2A.toInt(), x, y - 20f * s, ex, ey, max(1f, 2f * s))
                    circle(c, 0xFF5A5A5A.toInt(), ex, ey, 2.5f * s)
                }
                fill.color = color
                c.drawRect(x - 13f * s, y - 11f * s, x - 9f * s, y - 9f * s, fill)
            }
            UnitType.HEALER -> {
                tri(c, 0xFFF0EAD8.toInt(), x - 6f * s, y, x + 6f * s, y, x, y - 20f * s)
                fill.color = color
                c.drawRect(x - 4.5f * s, y - 11f * s, x + 4.5f * s, y - 9f * s, fill)
                head(21f, null)
                fill.color = 0xFFF0EAD8.toInt()
                c.drawArc(x - 3.8f * s, y - 25f * s, x + 3.8f * s, y - 18f * s, 180f, 180f, true, fill)
                line(c, 0xFF8A6440.toInt(), x + dir * 6f * s, y, x + dir * 6f * s, y - 28f * s, lw)
                if (attack > 0f) circle(c, 0x8870FF70.toInt(), x + dir * 6f * s, y - 29f * s, 3.5f * s)
            }
        }
    }

    // ------------------------------------------------------------------ projectiles & effects

    private fun drawProjectile(c: Canvas, p: Projectile) {
        val prog = p.progress()
        val dist = com.nsheaps.risetopower.core.dist(p.sx, p.sy, p.tx, p.ty)
        val arc = sin(prog * PI.toFloat()) * dist * (if (p.kind == Projectile.BULLET) 0.02f else 0.25f)
        val x = sx(p.x, p.y); val y = sy(p.x, p.y) - up(0.5f + arc)
        when (p.kind) {
            Projectile.ARROW -> {
                val dx = sx(p.tx, p.ty) - sx(p.sx, p.sy)
                val dy = sy(p.tx, p.ty) - sy(p.sx, p.sy) - (cos(prog * PI.toFloat()) * dist * 0.25f) * 32f * s * 1.5f
                val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.01f)
                line(c, 0xFF3A2A1A.toInt(), x - dx / len * 7f * s, y - dy / len * 7f * s, x, y, max(1f, 1.1f * s))
            }
            Projectile.STONE -> circle(c, 0xFF4A4440.toInt(), x, y, 3f * s)
            else -> circle(c, 0xFFFFE070.toInt(), x, y, 1.5f * s)
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
                Effect.PUFF -> circle(c, Color.argb((120 * (1 - f)).toInt(), 90, 80, 70), x, y - 8f * s - f * 8f * s, (4f + f * 6f) * s)
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
        val sc = size / (if (type == UnitType.CATAPULT || isMounted(type)) 44f else 40f)
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
