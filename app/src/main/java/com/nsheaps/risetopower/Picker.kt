package com.nsheaps.risetopower

import com.nsheaps.risetopower.core.Building
import com.nsheaps.risetopower.core.BuildingType
import com.nsheaps.risetopower.core.Entity
import com.nsheaps.risetopower.core.GameUnit
import com.nsheaps.risetopower.core.NodeKind
import com.nsheaps.risetopower.core.ResourceNode
import com.nsheaps.risetopower.core.UnitType
import com.nsheaps.risetopower.core.World
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds the entity under a tap. Every entity gets a screen-space hit shape that matches what the
 * [Renderer] draws for it (a building's box silhouette, a tree's trunk and crown, a unit's figure).
 * Among shapes that contain the tap, the one drawn in front wins, so empty ground behind a
 * building stays tappable. If nothing is hit directly, the closest shape within a finger-sized
 * slop is used.
 */
class Picker(private val world: World, private val humanId: Int, private val cam: IsoCamera, private val density: Float) {
    private val poly = FloatArray(12)

    /** Optional pixel-accurate tests against the drawn sprites (set once the renderer exists). */
    var nodeHit: ((ResourceNode, Float, Float) -> Boolean)? = null
    var buildingHit: ((Building, Float, Float) -> Boolean)? = null

    fun pick(x: Float, y: Float): Entity? {
        val s = cam.scale
        val slop = 16f * density
        var front: Entity? = null
        var frontKey = -Float.MAX_VALUE
        var near: Entity? = null
        var nearD = slop
        // Own units hidden behind a building are drawn as a silhouette on top, so they stay pickable.
        var friend: GameUnit? = null
        var friendKey = -Float.MAX_VALUE

        fun consider(e: Entity, key: Float, d: Float) {
            if (d <= 0f) {
                if (key > frontKey) { frontKey = key; front = e }
            } else if (d < nearD) { nearD = d; near = e }
        }

        for (u in world.units) {
            if (!u.alive) continue
            if (u.owner != humanId && !world.isAlly(humanId, u.owner) && !world.isVisibleTo(humanId, u.x, u.y)) continue
            val ux = cam.sx(u.x, u.y); val uy = cam.sy(u.x, u.y)
            val r = unitRect(u)
            val d = rectDist(x, y, ux + r[0] * s, uy + r[1] * s, ux + r[2] * s, uy + r[3] * s)
            consider(u, u.x + u.y, d)
            if (d == 0f && (u.owner == humanId || world.isAlly(humanId, u.owner)) && u.x + u.y > friendKey) {
                friendKey = u.x + u.y; friend = u
            }
        }
        for (n in world.nodes) {
            if (!n.alive || !world.isExploredBy(humanId, n.tx, n.ty)) continue
            val nx = cam.sx(n.x, n.y); val ny = cam.sy(n.x, n.y)
            if (nx < -200f * s || ny < -200f * s || nx > cam.viewW + 200f * s || ny > cam.viewH + 200f * s) continue
            val r = nodeRect(n)
            var d = rectDist(x, y, nx + r[0] * s, ny + r[1] * s, nx + r[2] * s, ny + r[3] * s)
            if (r.size > 4) d = min(d, rectDist(x, y, nx + r[4] * s, ny + r[5] * s, nx + r[6] * s, ny + r[7] * s))
            // Inside the box only counts as a direct hit where the sprite is actually drawn.
            val hit = nodeHit
            if (d == 0f && hit != null && !hit(n, x, y)) d = 0.01f
            consider(n, n.x + n.y, d)
        }
        for (b in world.buildings) {
            if (!b.alive) continue
            if (b.owner != humanId && !world.isAlly(humanId, b.owner) && !world.isExploredBy(humanId, b.tx, b.ty)) continue
            silhouette(b)
            // Buildings are big enough to hit directly; no slop so nearby ground stays free.
            if (!insideConvex(x, y, poly)) continue
            if (buildingHit?.invoke(b, x, y) == false) continue
            consider(b, if (b.type == BuildingType.FARM) -1000f + b.x + b.y else b.x + b.y, 0f)
        }
        if (front is Building && friend != null) return friend
        return front ?: near
    }

    /** Projected outline of a building: its footprint diamond extruded upwards by its drawn height. */
    fun silhouette(b: Building): FloatArray = silhouette(b, cam, poly)

    companion object {
        fun silhouette(b: Building, cam: IsoCamera, poly: FloatArray): FloatArray {
            val inset = Renderer.buildingInset(b.type)
            val x0 = b.minX + inset; val y0 = b.minY + inset; val x1 = b.maxX - inset; val y1 = b.maxY - inset
            val h = when {
                b.type == BuildingType.FARM -> 0f
                !b.constructed -> Renderer.buildingHeight(b.type) * 0.6f
                else -> Renderer.buildingHeight(b.type)
            } * IsoCamera.HALF_W * cam.scale
            // left, bottom, right at ground; right, top, left raised.
            poly[0] = cam.sx(x0, y1); poly[1] = cam.sy(x0, y1)
            poly[2] = cam.sx(x1, y1); poly[3] = cam.sy(x1, y1)
            poly[4] = cam.sx(x1, y0); poly[5] = cam.sy(x1, y0)
            poly[6] = poly[4]; poly[7] = poly[5] - h
            poly[8] = cam.sx(x0, y0); poly[9] = cam.sy(x0, y0) - h
            poly[10] = poly[0]; poly[11] = poly[1] - h
            return poly
        }

        /** Hit rectangle of a unit relative to its feet, in unscaled pixels: left, top, right, bottom. */
        fun unitRect(u: GameUnit): FloatArray = when {
            u.type == UnitType.SCOUT || u.type == UnitType.HORSEMAN || u.type == UnitType.HORSE_ARCHER -> floatArrayOf(-15f, -38f, 15f, 3f)
            u.type == UnitType.CATAPULT -> floatArrayOf(-16f, -26f, 16f, 4f)
            else -> floatArrayOf(-9f, -36f, 9f, 3f)
        }

        /** Hit rectangles of a resource node relative to its base (one or two: left, top, right, bottom each). */
        fun nodeRect(n: ResourceNode): FloatArray = when (n.kind) {
            // Crown, then trunk.
            NodeKind.TREE -> floatArrayOf(-17f, -74f, 17f, -24f, -5f, -24f, 5f, 4f)
            NodeKind.BERRIES -> floatArrayOf(-15f, -22f, 15f, 5f)
            NodeKind.GAME -> floatArrayOf(-14f, -26f, 14f, 3f)
            NodeKind.GOLD, NodeKind.STONE -> {
                val f = (n.amount / n.kind.amount).coerceIn(0.25f, 1f)
                floatArrayOf(-30f * f, -26f * f, 30f * f, 12f * f)
            }
        }

        /** Distance from a point to a rectangle; 0 when inside. */
        fun rectDist(x: Float, y: Float, l: Float, t: Float, r: Float, b: Float): Float {
            val dx = max(max(l - x, 0f), x - r)
            val dy = max(max(t - y, 0f), y - b)
            return if (dx == 0f && dy == 0f) 0f else sqrt(dx * dx + dy * dy)
        }

        /** Point in convex polygon (vertices in either winding order). */
        fun insideConvex(x: Float, y: Float, p: FloatArray): Boolean {
            val n = p.size / 2
            var sign = 0
            for (i in 0 until n) {
                val ax = p[i * 2]; val ay = p[i * 2 + 1]
                val bx = p[(i + 1) % n * 2]; val by = p[(i + 1) % n * 2 + 1]
                val cross = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
                if (cross == 0f) continue
                val sg = if (cross > 0f) 1 else -1
                if (sign == 0) sign = sg else if (sg != sign) return false
            }
            return true
        }
    }
}
