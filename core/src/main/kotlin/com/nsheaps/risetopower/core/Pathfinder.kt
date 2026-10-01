package com.nsheaps.risetopower.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Grid A* with 8-way movement (no corner cutting) followed by line-of-sight smoothing.
 * When the goal is unreachable the path leads to the closest reachable tile.
 */
class Pathfinder(private val map: GameMap) {
    private val n = map.width * map.height
    private val g = FloatArray(n)
    private val parent = IntArray(n)
    private val stamp = IntArray(n)
    private val closed = IntArray(n)
    private var gen = 0

    private var heap = IntArray(1024)
    private var heapF = FloatArray(1024)
    private var heapSize = 0

    var maxExpansions = 9000

    /** Path to a point. */
    fun findPath(sx: Float, sy: Float, gx: Float, gy: Float): FloatArray {
        val gtx = gx.toInt().coerceIn(0, map.width - 1)
        val gty = gy.toInt().coerceIn(0, map.height - 1)
        val path = search(sx, sy, gtx, gty, gtx, gty, false)
        // Replace the final waypoint with the precise destination when it is on the goal tile.
        if (path.size >= 2) {
            val lx = path[path.size - 2].toInt()
            val ly = path[path.size - 1].toInt()
            if (lx == gtx && ly == gty) {
                path[path.size - 2] = gx
                path[path.size - 1] = gy
            }
        }
        return path
    }

    /** Path to any walkable tile adjacent to the rectangle [x0,x1]x[y0,y1] (inclusive tile coords). */
    fun findPathToRect(sx: Float, sy: Float, x0: Int, y0: Int, x1: Int, y1: Int): FloatArray =
        search(sx, sy, x0, y0, x1, y1, true)

    private fun search(sx: Float, sy: Float, x0: Int, y0: Int, x1: Int, y1: Int, adjacent: Boolean): FloatArray {
        val w = map.width
        var stx = sx.toInt().coerceIn(0, w - 1)
        var sty = sy.toInt().coerceIn(0, map.height - 1)
        if (map.blocked[map.idx(stx, sty)]) {
            // Unit is standing on a blocked tile (e.g. a building appeared); start from nearest free tile.
            val free = nearestFree(stx, sty) ?: return FloatArray(0)
            stx = free % w; sty = free / w
        }
        gen++
        heapSize = 0
        val start = stx + sty * w
        g[start] = 0f
        parent[start] = -1
        stamp[start] = gen
        push(start, heuristic(stx, sty, x0, y0, x1, y1))
        var best = start
        var bestH = heuristic(stx, sty, x0, y0, x1, y1)
        var found = -1
        var expansions = 0
        while (heapSize > 0) {
            val cur = pop()
            if (closed[cur] == gen) continue
            closed[cur] = gen
            val cx = cur % w
            val cy = cur / w
            if (isGoal(cx, cy, x0, y0, x1, y1, adjacent)) {
                found = cur; break
            }
            val h = heuristic(cx, cy, x0, y0, x1, y1)
            if (h < bestH) { bestH = h; best = cur }
            if (++expansions > maxExpansions) break
            for (d in 0 until 8) {
                val dx = DX[d]
                val dy = DY[d]
                val nx = cx + dx
                val ny = cy + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= map.height) continue
                val ni = nx + ny * w
                if (map.blocked[ni]) continue
                if (dx != 0 && dy != 0) {
                    if (map.blocked[cx + dx + cy * w] || map.blocked[cx + (cy + dy) * w]) continue
                }
                if (closed[ni] == gen) continue
                val ng = g[cur] + if (dx != 0 && dy != 0) 1.4142f else 1f
                if (stamp[ni] != gen || ng < g[ni]) {
                    stamp[ni] = gen
                    g[ni] = ng
                    parent[ni] = cur
                    push(ni, ng + heuristic(nx, ny, x0, y0, x1, y1))
                }
            }
        }
        val end = if (found >= 0) found else best
        return buildPath(end, sx, sy)
    }

    private fun isGoal(x: Int, y: Int, x0: Int, y0: Int, x1: Int, y1: Int, adjacent: Boolean): Boolean {
        if (!adjacent) return x == x0 && y == y0
        return x >= x0 - 1 && x <= x1 + 1 && y >= y0 - 1 && y <= y1 + 1
    }

    private fun heuristic(x: Int, y: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        val dx = max(max(x0 - x, 0), x - x1)
        val dy = max(max(y0 - y, 0), y - y1)
        val mn = min(dx, dy)
        val mx = max(dx, dy)
        return mx + 0.4142f * mn
    }

    private fun buildPath(end: Int, sx: Float, sy: Float): FloatArray {
        val w = map.width
        val tiles = ArrayList<Int>()
        var c = end
        while (c != -1) {
            tiles.add(c)
            c = parent[c]
        }
        tiles.reverse()
        // Drop the start tile; we're already there.
        if (tiles.isNotEmpty()) tiles.removeAt(0)
        if (tiles.isEmpty()) {
            return if (end % w == sx.toInt() && end / w == sy.toInt()) FloatArray(0)
            else floatArrayOf(end % w + 0.5f, end / w + 0.5f)
        }
        // Line of sight smoothing.
        val out = ArrayList<Float>()
        var ax = sx
        var ay = sy
        var i = 0
        while (i < tiles.size) {
            var j = tiles.size - 1
            while (j > i) {
                val tx = tiles[j] % w + 0.5f
                val ty = tiles[j] / w + 0.5f
                if (lineWalkable(ax, ay, tx, ty)) break
                j--
            }
            val px = tiles[j] % w + 0.5f
            val py = tiles[j] / w + 0.5f
            out.add(px); out.add(py)
            ax = px; ay = py
            i = j + 1
        }
        return out.toFloatArray()
    }

    fun lineWalkable(ax: Float, ay: Float, bx: Float, by: Float): Boolean {
        val d = dist(ax, ay, bx, by)
        val steps = (d / 0.2f).toInt() + 1
        // Check with a small lateral clearance so units do not clip building corners.
        val nx = if (d > 0) -(by - ay) / d * 0.22f else 0f
        val ny = if (d > 0) (bx - ax) / d * 0.22f else 0f
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            val px = ax + (bx - ax) * t
            val py = ay + (by - ay) * t
            if (!map.isWalkableF(px, py)) return false
            if (!map.isWalkableF(px + nx, py + ny)) return false
            if (!map.isWalkableF(px - nx, py - ny)) return false
        }
        return true
    }

    fun nearestFree(x: Int, y: Int, maxR: Int = 12): Int? {
        for (r in 0..maxR) {
            for (dy in -r..r) for (dx in -r..r) {
                if (abs(dx) != r && abs(dy) != r) continue
                val nx = x + dx
                val ny = y + dy
                if (map.isWalkable(nx, ny)) return map.idx(nx, ny)
            }
        }
        return null
    }

    private fun push(node: Int, f: Float) {
        if (heapSize == heap.size) {
            heap = heap.copyOf(heapSize * 2)
            heapF = heapF.copyOf(heapSize * 2)
        }
        var i = heapSize++
        heap[i] = node
        heapF[i] = f
        while (i > 0) {
            val p = (i - 1) shr 1
            if (heapF[p] <= heapF[i]) break
            swap(i, p)
            i = p
        }
    }

    private fun pop(): Int {
        val top = heap[0]
        heapSize--
        if (heapSize > 0) {
            heap[0] = heap[heapSize]
            heapF[0] = heapF[heapSize]
            var i = 0
            while (true) {
                val l = i * 2 + 1
                val r = l + 1
                var m = i
                if (l < heapSize && heapF[l] < heapF[m]) m = l
                if (r < heapSize && heapF[r] < heapF[m]) m = r
                if (m == i) break
                swap(i, m)
                i = m
            }
        }
        return top
    }

    private fun swap(a: Int, b: Int) {
        val t = heap[a]; heap[a] = heap[b]; heap[b] = t
        val f = heapF[a]; heapF[a] = heapF[b]; heapF[b] = f
    }

    companion object {
        private val DX = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
        private val DY = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)
    }
}
