package com.nsheaps.risetopower.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** Smooth 2D value noise. */
class ValueNoise(seed: Long) {
    private val perm = IntArray(512)

    init {
        val r = Random(seed)
        val p = IntArray(256) { it }
        for (i in 255 downTo 1) {
            val j = r.nextInt(i + 1)
            val t = p[i]; p[i] = p[j]; p[j] = t
        }
        for (i in 0 until 512) perm[i] = p[i and 255]
    }

    private fun hash(x: Int, y: Int) = perm[(perm[x and 255] + y) and 511] / 255f

    private fun smooth(t: Float) = t * t * (3 - 2 * t)

    fun sample(x: Float, y: Float): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = smooth(x - x0); val fy = smooth(y - y0)
        val a = hash(x0, y0); val b = hash(x0 + 1, y0)
        val c = hash(x0, y0 + 1); val d = hash(x0 + 1, y0 + 1)
        val top = a + (b - a) * fx
        val bot = c + (d - c) * fx
        return top + (bot - top) * fy
    }

    fun fractal(x: Float, y: Float, octaves: Int = 4): Float {
        var amp = 1f; var freq = 1f; var sum = 0f; var norm = 0f
        for (o in 0 until octaves) {
            sum += sample(x * freq, y * freq) * amp
            norm += amp
            amp *= 0.5f
            freq *= 2f
        }
        return sum / norm
    }
}

class MapGenerator(private val world: World) {
    private val map = world.map
    private val rng = world.rng
    private val w = map.width
    private val h = map.height

    lateinit var starts: List<Pair<Float, Float>>

    fun generate() {
        val type = world.settings.mapType
        val elev = ValueNoise(rng.nextLong())
        val moist = ValueNoise(rng.nextLong())
        val detail = ValueNoise(rng.nextLong())
        val (waterT, mountainT, forestT) = when (type) {
            MapType.CONTINENTAL -> Triple(0.30f, 0.80f, 0.62f)
            MapType.HIGHLANDS -> Triple(0.20f, 0.68f, 0.64f)
            MapType.LAKES -> Triple(0.42f, 0.90f, 0.63f)
            MapType.FOREST -> Triple(0.22f, 0.88f, 0.52f)
        }
        val scale = 1f / 18f
        val forestMask = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = map.idx(x, y)
            val e = elev.fractal(x * scale, y * scale)
            val m = moist.fractal(x * scale * 1.3f + 50f, y * scale * 1.3f + 50f)
            val d = detail.sample(x / 3f, y / 3f)
            map.elevation[i] = e
            map.terrain[i] = when {
                e < waterT - 0.03f -> Terrain.WATER
                e < waterT -> Terrain.SHALLOWS
                e < waterT + 0.025f -> Terrain.SAND
                e > mountainT -> Terrain.MOUNTAIN
                d > 0.78f || (e > mountainT - 0.06f) -> Terrain.DIRT
                else -> Terrain.GRASS
            }
            forestMask[i] = m > forestT && map.terrain[i].buildable
            map.refreshBlocked(i, false)
        }

        // Starting positions on a circle.
        val n = world.players.size
        val radius = w * 0.36f
        val offset = rng.nextFloat() * 2f * PI.toFloat()
        starts = (0 until n).map { k ->
            val a = offset + k * 2f * PI.toFloat() / n
            Pair(w / 2f + cos(a) * radius, h / 2f + sin(a) * radius)
        }

        // Connect every start to the middle so all players can reach each other by land.
        for ((sx, sy) in starts) carve(sx, sy, w / 2f, h / 2f, forestMask)
        for (k in starts.indices) {
            val (ax, ay) = starts[k]; val (bx, by) = starts[(k + 1) % n]
            if (n > 2) carve(ax, ay, bx, by, forestMask)
        }
        // Clear start areas.
        for ((sx, sy) in starts) clearArea(sx, sy, 9f, forestMask)

        // Start resources before forests so they get good spots.
        for ((k, p) in world.players.withIndex()) setupPlayer(p, starts[k])

        // Forests.
        for (y in 0 until h) for (x in 0 until w) {
            val i = map.idx(x, y)
            if (!forestMask[i]) continue
            if (starts.any { (sx, sy) -> dist(sx, sy, x + 0.5f, y + 0.5f) < 11f }) continue
            if (rng.nextFloat() < 0.88f) world.spawnNode(NodeKind.TREE, x, y)
        }
        // Scattered single trees.
        repeat(w * h / 60) {
            val x = rng.nextInt(w); val y = rng.nextInt(h)
            if (map.terrain[map.idx(x, y)].buildable && starts.none { (sx, sy) -> dist(sx, sy, x + 0.5f, y + 0.5f) < 9f }) world.spawnNode(NodeKind.TREE, x, y)
        }
        // Neutral resources.
        val area = w * h / 4096f
        scatter(NodeKind.GOLD, (5 * area).toInt() + n)
        scatter(NodeKind.STONE, (4 * area).toInt() + n)
        repeat((5 * area).toInt()) { cluster(NodeKind.BERRIES, randomFreeSpot() ?: return@repeat, 5) }
        repeat((7 * area).toInt()) { cluster(NodeKind.GAME, randomFreeSpot() ?: return@repeat, 3) }
    }

    private fun carve(ax: Float, ay: Float, bx: Float, by: Float, forestMask: BooleanArray) {
        val steps = (dist(ax, ay, bx, by) * 2).toInt() + 1
        // Gentle wobble so the corridors don't look ruler straight.
        val wob = rng.nextFloat() * 6f
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            val px = ax + (bx - ax) * t + sin(t * 9f + wob) * 2f
            val py = ay + (by - ay) * t + cos(t * 7f + wob) * 2f
            for (dy in -2..2) for (dx in -2..2) {
                val x = (px + dx).toInt(); val y = (py + dy).toInt()
                if (!map.inBounds(x, y)) continue
                val i = map.idx(x, y)
                val tt = map.terrain[i]
                if (tt == Terrain.WATER) map.terrain[i] = Terrain.SHALLOWS
                else if (tt == Terrain.MOUNTAIN) map.terrain[i] = Terrain.DIRT
                if (abs(dx) <= 1 && abs(dy) <= 1) forestMask[i] = false
                map.refreshBlocked(i, false)
            }
        }
    }

    private fun clearArea(cx: Float, cy: Float, r: Float, forestMask: BooleanArray) {
        for (y in (cy - r).toInt()..(cy + r).toInt()) for (x in (cx - r).toInt()..(cx + r).toInt()) {
            if (!map.inBounds(x, y) || dist(cx, cy, x + 0.5f, y + 0.5f) > r) continue
            val i = map.idx(x, y)
            if (!map.terrain[i].buildable) map.terrain[i] = Terrain.GRASS
            forestMask[i] = false
            map.refreshBlocked(i, false)
        }
    }

    private fun setupPlayer(p: Player, start: Pair<Float, Float>) {
        val (sx, sy) = start
        val tcx = sx.toInt() - 1
        val tcy = sy.toInt() - 1
        world.addBuilding(p.id, BuildingType.TOWN_CENTER, tcx, tcy, true)
        val villagers = 4 + (if (p.civ == Civ.CHINESE) 2 else 0)
        val toCenter = kotlin.math.atan2(h / 2f - sy, w / 2f - sx)
        for (k in 0 until villagers) {
            val a = toCenter + (k - villagers / 2f) * 0.5f
            val u = world.spawnUnit(p.id, UnitType.VILLAGER, sx + cos(a) * 2.8f, sy + sin(a) * 2.8f)
            u.facing = a
        }
        world.spawnUnit(p.id, UnitType.SCOUT, sx + cos(toCenter) * 3.5f, sy + sin(toCenter) * 3.5f)

        // Resources around the town center, away from the map centre side where possible.
        val base = toCenter + PI.toFloat()
        placeAround(sx, sy, 6f, base + 0.8f, 1)?.let { cluster(NodeKind.BERRIES, it, 6) }
        placeAround(sx, sy, 8.5f, base - 0.9f, 2)?.let { (x, y) -> world.spawnNode(NodeKind.GOLD, x, y) }
        placeAround(sx, sy, 9f, base + 2.0f, 2)?.let { (x, y) -> world.spawnNode(NodeKind.STONE, x, y) }
        placeAround(sx, sy, 15f, base - 2.2f, 2)?.let { (x, y) -> world.spawnNode(NodeKind.GOLD, x, y) }
        placeAround(sx, sy, 7f, base - 2.4f, 1)?.let { cluster(NodeKind.GAME, it, 3) }
        // A woodline.
        placeAround(sx, sy, 10f, base, 1)?.let { (x, y) -> forestBlob(x, y, 4.2f) }
        placeAround(sx, sy, 12f, base + 1.7f, 1)?.let { (x, y) -> forestBlob(x, y, 3.2f) }
    }

    private fun placeAround(cx: Float, cy: Float, r: Float, angle: Float, size: Int): Pair<Int, Int>? {
        for (attempt in 0 until 40) {
            val a = angle + (attempt / 2) * 0.25f * (if (attempt % 2 == 0) 1 else -1)
            val rr = r + (attempt % 3)
            val x = (cx + cos(a) * rr).toInt()
            val y = (cy + sin(a) * rr).toInt()
            if (areaFree(x, y, size, 1)) return Pair(x, y)
        }
        return null
    }

    private fun areaFree(x: Int, y: Int, size: Int, margin: Int): Boolean {
        for (dy in -margin until size + margin) for (dx in -margin until size + margin) {
            val tx = x + dx; val ty = y + dy
            if (!map.inBounds(tx, ty)) return false
            val i = map.idx(tx, ty)
            if (map.blocked[i] || !map.terrain[i].buildable || map.occupant[i] >= 0) return false
        }
        return true
    }

    private fun forestBlob(cx: Int, cy: Int, r: Float) {
        for (y in (cy - r).toInt()..(cy + r).toInt()) for (x in (cx - r).toInt()..(cx + r).toInt()) {
            if (dist(cx.toFloat(), cy.toFloat(), x.toFloat(), y.toFloat()) > r + rng.nextFloat() - 0.5f) continue
            if (!map.inBounds(x, y) || !map.terrain[map.idx(x, y)].buildable) continue
            if (world.buildings.any { it.distanceTo(x + 0.5f, y + 0.5f) < 3f }) continue
            world.spawnNode(NodeKind.TREE, x, y)
        }
    }

    private fun cluster(kind: NodeKind, at: Pair<Int, Int>, count: Int) {
        var placed = 0
        var tries = 0
        while (placed < count && tries < count * 8) {
            tries++
            val x = at.first + rng.nextInt(-1, 3)
            val y = at.second + rng.nextInt(-1, 3)
            if (map.inBounds(x, y) && map.terrain[map.idx(x, y)].buildable && world.spawnNode(kind, x, y) != null) placed++
        }
    }

    private fun scatter(kind: NodeKind, count: Int) {
        repeat(count) {
            val spot = randomFreeSpot(kind.size) ?: return@repeat
            world.spawnNode(kind, spot.first, spot.second)
        }
    }

    private fun randomFreeSpot(size: Int = 1): Pair<Int, Int>? {
        for (t in 0 until 200) {
            val x = rng.nextInt(2, w - 2 - size)
            val y = rng.nextInt(2, h - 2 - size)
            if (starts.any { (sx, sy) -> dist(sx, sy, x.toFloat(), y.toFloat()) < 16f }) continue
            if (areaFree(x, y, size, 2)) return Pair(x, y)
        }
        return null
    }

    companion object {
        fun maxPlayers(size: MapSize) = when (size) {
            MapSize.SMALL -> 4
            MapSize.MEDIUM -> 6
            MapSize.LARGE -> 8
        }
    }
}
