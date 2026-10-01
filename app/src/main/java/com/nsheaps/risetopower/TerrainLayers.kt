package com.nsheaps.risetopower

import android.graphics.Bitmap
import android.graphics.Color
import com.nsheaps.risetopower.core.Terrain
import com.nsheaps.risetopower.core.World
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Tile-space bitmaps (terrain, fog, territory, minimap) that are drawn through the iso matrix.
 */
class TerrainLayers(private val world: World, private val humanId: Int) {
    private val map = world.map
    val w = map.width
    val h = map.height
    val ppt = if (w <= 96) 20 else 14

    val terrain: Bitmap = Bitmap.createBitmap(w * ppt, h * ppt, Bitmap.Config.ARGB_8888)
    val fog: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val territory: Bitmap = Bitmap.createBitmap(w * TERR_PPT, h * TERR_PPT, Bitmap.Config.ARGB_8888)
    val minimap: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    private val fogPixels = IntArray(w * h)
    private val miniPixels = IntArray(w * h)
    private val tileColor = IntArray(w * h)
    private var territoryVersion = -1

    init {
        paintTerrain()
        updateFog()
    }

    private fun hash(x: Int, y: Int): Int {
        var v = x * 374761393 + y * 668265263
        v = (v xor (v ushr 13)) * 1274126177
        return v xor (v ushr 16)
    }

    private fun baseColor(t: Terrain, e: Float, x: Int, y: Int): Int {
        val n = ((hash(x, y) and 255) / 255f - 0.5f) * 0.08f
        return when (t) {
            Terrain.GRASS -> shade(0xFF5E8C3A.toInt(), 0.85f + e * 0.35f + n)
            Terrain.DIRT -> shade(0xFF8C7048.toInt(), 0.9f + e * 0.2f + n)
            Terrain.SAND -> shade(0xFFD8C48A.toInt(), 0.95f + n)
            Terrain.SHALLOWS -> shade(0xFF4E9AB0.toInt(), 0.95f + n)
            Terrain.WATER -> shade(0xFF2A5F8F.toInt(), 0.75f + e * 0.6f)
            Terrain.MOUNTAIN -> shade(0xFF7D7468.toInt(), 0.85f + e * 0.2f + n)
        }
    }

    private fun paintTerrain() {
        for (y in 0 until h) for (x in 0 until w) {
            val i = map.idx(x, y)
            tileColor[i] = baseColor(map.terrain[i], map.elevation[i], x, y)
        }
        val tw = w * ppt
        val pixels = IntArray(tw * ppt)
        // Paint row by row of tiles to limit memory use.
        for (ty in 0 until h) {
            for (py in 0 until ppt) {
                val fy = ty + (py + 0.5f) / ppt
                for (px in 0 until tw) {
                    val fx = (px + 0.5f) / ppt
                    val tx = px / ppt
                    val own = tileColor[map.idx(tx, ty)]
                    val terr = map.terrain[map.idx(tx, ty)]
                    // Bilinear blend of neighbouring tile colours for soft transitions.
                    val gx = fx - 0.5f
                    val gy = fy - 0.5f
                    val x0 = gx.toInt().coerceIn(0, w - 1)
                    val y0 = gy.toInt().coerceIn(0, h - 1)
                    val x1 = min(w - 1, x0 + 1)
                    val y1 = min(h - 1, y0 + 1)
                    val ax = (gx - x0).coerceIn(0f, 1f)
                    val ay = (gy - y0).coerceIn(0f, 1f)
                    val blend = lerpColor(
                        lerpColor(tileColor[map.idx(x0, y0)], tileColor[map.idx(x1, y0)], ax),
                        lerpColor(tileColor[map.idx(x0, y1)], tileColor[map.idx(x1, y1)], ax), ay
                    )
                    var c = lerpColor(own, blend, if (terr == Terrain.WATER || terr == Terrain.MOUNTAIN) 0.35f else 0.6f)
                    val hsh = hash(px, ty * ppt + py)
                    val grain = ((hsh and 255) / 255f - 0.5f)
                    c = when (terr) {
                        Terrain.WATER, Terrain.SHALLOWS -> {
                            val wave = sin(fx * 2.1f + fy * 1.3f + sin(fy * 0.9f) * 2f)
                            shade(c, 1f + wave * 0.06f + grain * 0.03f)
                        }
                        Terrain.GRASS -> {
                            // Occasional darker grass tufts and light flowers.
                            when {
                                (hsh ushr 8) and 63 == 0 -> shade(c, 0.78f)
                                (hsh ushr 8) and 1023 == 1 -> 0xFFE8E0A0.toInt()
                                else -> shade(c, 1f + grain * 0.07f)
                            }
                        }
                        Terrain.MOUNTAIN -> shade(c, 1f + grain * 0.14f)
                        else -> shade(c, 1f + grain * 0.09f)
                    }
                    pixels[px + py * tw] = c
                }
            }
            terrain.setPixels(pixels, 0, tw, 0, ty * ppt, tw, ppt)
        }
    }

    fun updateFog() {
        val p = world.players[humanId]
        val reveal = world.revealed
        for (i in fogPixels.indices) {
            fogPixels[i] = when {
                reveal -> 0
                p.visible[i] > 0 -> 0
                p.explored[i] -> 0x99000000.toInt()
                else -> 0xFF0A0806.toInt()
            }
        }
        fog.setPixels(fogPixels, 0, w, 0, 0, w, h)
    }

    /** Rebuilds the territory border bitmap and minimap base when territory changed. */
    fun updateTerritory(force: Boolean = false) {
        if (!force && territoryVersion == world.territoryVersion) return
        territoryVersion = world.territoryVersion
        val tp = TERR_PPT
        val tw = w * tp
        val px = IntArray(tw * h * tp)
        for (y in 0 until h) for (x in 0 until w) {
            val o = map.territory[map.idx(x, y)]
            if (o < 0) continue
            val col = world.players[o].color
            val fill = (col and 0x00FFFFFF) or 0x22000000
            val line = (col and 0x00FFFFFF) or 0xDD000000.toInt()
            val left = x == 0 || map.territory[map.idx(x - 1, y)] != o
            val right = x == w - 1 || map.territory[map.idx(x + 1, y)] != o
            val top = y == 0 || map.territory[map.idx(x, y - 1)] != o
            val bottom = y == h - 1 || map.territory[map.idx(x, y + 1)] != o
            for (py in 0 until tp) for (pxx in 0 until tp) {
                val edge = (left && pxx == 0) || (right && pxx == tp - 1) || (top && py == 0) || (bottom && py == tp - 1)
                px[(x * tp + pxx) + (y * tp + py) * tw] = if (edge) line else fill
            }
        }
        territory.setPixels(px, 0, tw, 0, 0, tw, h * tp)
    }

    fun updateMinimap() {
        val p = world.players[humanId]
        val reveal = world.revealed
        for (i in miniPixels.indices) {
            if (!reveal && !p.explored[i]) { miniPixels[i] = 0xFF0A0806.toInt(); continue }
            var c = tileColor[i]
            val o = map.territory[i]
            if (o >= 0) c = lerpColor(c, world.players[o].color, 0.3f)
            if (!reveal && p.visible[i] == 0.toByte()) c = shade(c, 0.6f)
            miniPixels[i] = c
        }
        for (n in world.nodes) {
            val i = map.idx(n.tx, n.ty)
            if (!reveal && !p.explored[i]) continue
            val c = when (n.kind) {
                com.nsheaps.risetopower.core.NodeKind.TREE -> 0xFF2F5A22.toInt()
                com.nsheaps.risetopower.core.NodeKind.GOLD -> 0xFFF0D040.toInt()
                com.nsheaps.risetopower.core.NodeKind.STONE -> 0xFFC8C8C8.toInt()
                else -> 0xFFD05A5A.toInt()
            }
            for (dy in 0 until n.kind.size) for (dx in 0 until n.kind.size) miniPixels[map.idx(n.tx + dx, n.ty + dy)] = c
        }
        minimap.setPixels(miniPixels, 0, w, 0, 0, w, h)
    }

    fun recycle() {
        terrain.recycle(); fog.recycle(); territory.recycle(); minimap.recycle()
    }

    companion object {
        const val TERR_PPT = 6

        fun shade(c: Int, f: Float): Int {
            val r = (Color.red(c) * f).toInt().coerceIn(0, 255)
            val g = (Color.green(c) * f).toInt().coerceIn(0, 255)
            val b = (Color.blue(c) * f).toInt().coerceIn(0, 255)
            return Color.argb(Color.alpha(c), r, g, b)
        }

        fun lerpColor(a: Int, b: Int, t: Float): Int {
            val tt = max(0f, min(1f, t))
            val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * tt).toInt()
            val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * tt).toInt()
            val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * tt).toInt()
            val al = (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * tt).toInt()
            return Color.argb(al, r, g, bl)
        }
    }
}
