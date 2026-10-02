package com.nsheaps.risetopower

import android.graphics.Bitmap
import android.graphics.Color
import com.nsheaps.risetopower.core.Terrain
import com.nsheaps.risetopower.core.World
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tile-space bitmaps (terrain, water shimmer, fog, territory, minimap) that are drawn through the
 * iso matrix. Everything expensive is painted once here; per frame the renderer only blits.
 */
class TerrainLayers(private val world: World, private val humanId: Int) {
    private val map = world.map
    val w = map.width
    val h = map.height
    val ppt = when {
        w <= 64 -> 32
        w <= 96 -> 24
        else -> 16
    }

    /** Pixels per tile of the water shimmer layer (half the terrain resolution keeps it small). */
    val shimmerPpt = ppt / 2
    /** Pixels per tile of the territory layer: finer lines on small maps, bounded memory on large ones. */
    val terrPpt = when {
        w <= 64 -> 10
        w <= 96 -> 8
        else -> 6
    }

    val terrain: Bitmap = Bitmap.createBitmap(w * ppt, h * ppt, Bitmap.Config.ARGB_8888)
    /** Soft highlights on water only (transparent elsewhere); the renderer pulses and drifts it. */
    val shimmer: Bitmap = Bitmap.createBitmap(w * shimmerPpt, h * shimmerPpt, Bitmap.Config.ARGB_8888)
    val fog: Bitmap = Bitmap.createBitmap(w * FOG_PPT, h * FOG_PPT, Bitmap.Config.ARGB_8888)
    val territory: Bitmap = Bitmap.createBitmap(w * terrPpt, h * terrPpt, Bitmap.Config.ARGB_8888)
    val minimap: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    /** True when there is any water on the map, so the renderer can skip the shimmer pass. */
    var hasWater = false
        private set

    private val fogPixels = IntArray(w * FOG_PPT * h * FOG_PPT)
    private val fogRow = FloatArray((w + 2) * (h + 2)) // per-tile darkness incl. a border ring
    private val fogRowUnexplored = FloatArray((w + 2) * (h + 2))
    private val miniPixels = IntArray(w * h)
    private val tileColor = IntArray(w * h)
    private val terrPixels = IntArray(w * terrPpt * h * terrPpt)
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

    /** Smooth value noise in [0, 1). */
    private fun noise(x: Float, y: Float, seed: Int): Float {
        val ix = kotlin.math.floor(x).toInt(); val iy = kotlin.math.floor(y).toInt()
        val fx = x - ix; val fy = y - iy
        fun v(a: Int, b: Int) = (hash(a + seed * 101, b - seed * 37) and 1023) / 1024f
        val ux = fx * fx * (3 - 2 * fx); val uy = fy * fy * (3 - 2 * fy)
        val a = v(ix, iy) + (v(ix + 1, iy) - v(ix, iy)) * ux
        val b = v(ix, iy + 1) + (v(ix + 1, iy + 1) - v(ix, iy + 1)) * ux
        return a + (b - a) * uy
    }

    /** Fractal noise centred on 0, roughly in [-0.5, 0.5]. */
    private fun fbm(x: Float, y: Float, seed: Int): Float =
        noise(x, y, seed) * 0.5f + noise(x * 2.1f, y * 2.1f, seed + 1) * 0.3f + noise(x * 4.3f, y * 4.3f, seed + 2) * 0.2f - 0.5f

    /** Elevation sampled bilinearly between tile centres. */
    private fun elevationAt(fx: Float, fy: Float): Float {
        val gx = (fx - 0.5f).coerceIn(0f, w - 1f); val gy = (fy - 0.5f).coerceIn(0f, h - 1f)
        val x0 = gx.toInt(); val y0 = gy.toInt()
        val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
        val ax = gx - x0; val ay = gy - y0
        val e = map.elevation
        val top = e[map.idx(x0, y0)] + (e[map.idx(x1, y0)] - e[map.idx(x0, y0)]) * ax
        val bot = e[map.idx(x0, y1)] + (e[map.idx(x1, y1)] - e[map.idx(x0, y1)]) * ax
        return top + (bot - top) * ay
    }

    private fun isWater(t: Terrain) = t == Terrain.WATER || t == Terrain.SHALLOWS

    private fun baseColor(t: Terrain, e: Float, x: Int, y: Int): Int {
        val n = ((hash(x, y) and 255) / 255f - 0.5f) * 0.07f
        return when (t) {
            Terrain.GRASS -> shade(0xFF5C9238.toInt(), 0.84f + e * 0.36f + n)
            Terrain.DIRT -> shade(0xFF96724A.toInt(), 0.9f + e * 0.2f + n)
            Terrain.SAND -> shade(0xFFE2CE92.toInt(), 0.96f + n)
            Terrain.SHALLOWS -> shade(0xFF3F9EB4.toInt(), 0.97f + n)
            Terrain.WATER -> shade(0xFF24608F.toInt(), 0.8f + e * 0.5f)
            Terrain.MOUNTAIN -> shade(0xFF7A7268.toInt(), 0.85f + e * 0.2f + n)
        }
    }

    // Low-frequency detail sampled on a coarse grid (FIELD_RES points per tile) and interpolated per pixel.
    private val fw get() = w * FIELD_RES + 1
    private val fh get() = h * FIELD_RES + 1
    private lateinit var patchField: FloatArray
    private lateinit var dryField: FloatArray
    private lateinit var hillField: FloatArray
    private lateinit var rippleField: FloatArray
    private lateinit var warpXField: FloatArray
    private lateinit var warpYField: FloatArray
    /** Per tile (padded by one): 1 for water tiles, else 0. */
    private lateinit var waterTile: FloatArray
    /** Per tile: distance in tiles to the nearest land tile (0 on land), capped. */
    private lateinit var depthTile: FloatArray
    /** Per tile: distance to the nearest water tile (0 in water), capped; used to wet the beach. */
    private lateinit var wetTile: FloatArray

    private fun buildFields() {
        patchField = FloatArray(fw * fh); dryField = FloatArray(fw * fh); hillField = FloatArray(fw * fh)
        rippleField = FloatArray(fw * fh); warpXField = FloatArray(fw * fh); warpYField = FloatArray(fw * fh)
        for (j in 0 until fh) for (i in 0 until fw) {
            val fx = i / FIELD_RES.toFloat(); val fy = j / FIELD_RES.toFloat()
            val k = i + j * fw
            patchField[k] = fbm(fx * 0.35f, fy * 0.35f, 3)
            dryField[k] = fbm(fx * 0.18f + 40f, fy * 0.18f, 9)
            rippleField[k] = fbm(fx * 1.5f, fy * 3f, 5)
            warpXField[k] = fbm(fx * 0.9f + 11f, fy * 0.9f, 21)
            warpYField[k] = fbm(fx * 0.9f, fy * 0.9f + 17f, 27)
            val slopeX = elevationAt(fx + 0.5f, fy) - elevationAt(fx - 0.5f, fy)
            val slopeY = elevationAt(fx, fy + 0.5f) - elevationAt(fx, fy - 0.5f)
            hillField[k] = (-(slopeX + slopeY) * 2.4f).coerceIn(-0.28f, 0.28f)
        }
        // Tile fields with a one-tile padding ring that repeats the edge, for bilinear sampling.
        val pw = w + 2
        waterTile = FloatArray(pw * (h + 2)); depthTile = FloatArray(pw * (h + 2)); wetTile = FloatArray(pw * (h + 2))
        for (y in -1..h) for (x in -1..w) {
            val cx = x.coerceIn(0, w - 1); val cy = y.coerceIn(0, h - 1)
            val water = isWater(map.terrain[map.idx(cx, cy)])
            if (water) hasWater = true
            waterTile[(x + 1) + (y + 1) * pw] = if (water) 1f else 0f
        }
        distanceTransform(waterTile, depthTile, pw, h + 2, insideIsOne = true, cap = 7f)
        distanceTransform(waterTile, wetTile, pw, h + 2, insideIsOne = false, cap = 3f)
    }

    /**
     * Two-pass chamfer distance (in tiles) to the nearest cell where [src] is 0 (when
     * [insideIsOne]) or 1 (otherwise). Cells on the other side get 0.
     */
    private fun distanceTransform(src: FloatArray, out: FloatArray, pw: Int, ph: Int, insideIsOne: Boolean, cap: Float) {
        for (i in out.indices) out[i] = if ((src[i] > 0.5f) == insideIsOne) cap else 0f
        val d1 = 1f; val d2 = 1.4142f
        for (y in 0 until ph) for (x in 0 until pw) {
            val k = x + y * pw
            if (out[k] == 0f) continue
            var v = out[k]
            if (x > 0) v = min(v, out[k - 1] + d1)
            if (y > 0) {
                v = min(v, out[k - pw] + d1)
                if (x > 0) v = min(v, out[k - pw - 1] + d2)
                if (x < pw - 1) v = min(v, out[k - pw + 1] + d2)
            }
            out[k] = v
        }
        for (y in ph - 1 downTo 0) for (x in pw - 1 downTo 0) {
            val k = x + y * pw
            if (out[k] == 0f) continue
            var v = out[k]
            if (x < pw - 1) v = min(v, out[k + 1] + d1)
            if (y < ph - 1) {
                v = min(v, out[k + pw] + d1)
                if (x > 0) v = min(v, out[k + pw - 1] + d2)
                if (x < pw - 1) v = min(v, out[k + pw + 1] + d2)
            }
            out[k] = min(v, cap)
        }
    }

    private fun sample(f: FloatArray, x: Float, y: Float): Float {
        val gx = (x * FIELD_RES).coerceIn(0f, fw - 1.001f); val gy = (y * FIELD_RES).coerceIn(0f, fh - 1.001f)
        val i = gx.toInt(); val j = gy.toInt()
        val ax = gx - i; val ay = gy - j
        val k = i + j * fw
        val top = f[k] + (f[k + 1] - f[k]) * ax
        val bot = f[k + fw] + (f[k + fw + 1] - f[k + fw]) * ax
        return top + (bot - top) * ay
    }

    /** Bilinear sample of a padded per-tile field at world position (x, y), tile centres at .5. */
    private fun sampleTile(f: FloatArray, x: Float, y: Float): Float {
        val pw = w + 2
        val gx = (x + 0.5f).coerceIn(0f, w + 0.999f); val gy = (y + 0.5f).coerceIn(0f, h + 0.999f)
        val i = gx.toInt(); val j = gy.toInt()
        val ax = gx - i; val ay = gy - j
        val k = i + j * pw
        val top = f[k] + (f[k + 1] - f[k]) * ax
        val bot = f[k + pw] + (f[k + pw + 1] - f[k + pw]) * ax
        return top + (bot - top) * ay
    }

    private fun smooth(t: Float): Float { val c = t.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

    // Scratch for the land colour blend (avoids allocating per pixel).
    private var blendR = 0f; private var blendG = 0f; private var blendB = 0f

    /**
     * Blends the colours of the four tiles around world position (x, y), weighting towards the
     * nearest tile for firmer (but still soft) edges and ignoring water tiles so beaches stay dry.
     */
    private fun landBlend(x: Float, y: Float) {
        val gx = (x - 0.5f).coerceIn(0f, w - 1.001f); val gy = (y - 0.5f).coerceIn(0f, h - 1.001f)
        val x0 = gx.toInt(); val y0 = gy.toInt()
        val x1 = min(w - 1, x0 + 1); val y1 = min(h - 1, y0 + 1)
        val ax = gx - x0; val ay = gy - y0
        blendR = 0f; blendG = 0f; blendB = 0f; blendSum = 0f
        addLand(x0, y0, (1 - ax) * (1 - ay)); addLand(x1, y0, ax * (1 - ay)); addLand(x0, y1, (1 - ax) * ay); addLand(x1, y1, ax * ay)
        if (blendSum <= 0f) { blendR = 226f; blendG = 206f; blendB = 146f; return }
        blendR /= blendSum; blendG /= blendSum; blendB /= blendSum
    }

    private var blendSum = 0f

    private fun addLand(tx: Int, ty: Int, wgt: Float) {
        val i = map.idx(tx, ty)
        if (wgt <= 0f || isWater(map.terrain[i])) return
        val ww = wgt * wgt
        val c = tileColor[i]
        blendR += ((c shr 16) and 255) * ww; blendG += ((c shr 8) and 255) * ww; blendB += (c and 255) * ww; blendSum += ww
    }

    private fun paintTerrain() {
        buildFields()
        for (y in 0 until h) for (x in 0 until w) {
            val i = map.idx(x, y)
            tileColor[i] = baseColor(map.terrain[i], map.elevation[i], x, y)
        }
        val tw = w * ppt
        val pixels = IntArray(tw * ppt)
        val sp = shimmerPpt
        val stw = w * sp
        val spix = IntArray(stw * sp)
        val deep = 0xFF173F6E.toInt()
        val shallow = 0xFF4FB2BE.toInt()
        val foam = 0xFFEAF6F2.toInt()
        val flowerColors = intArrayOf(0xFFF2EAA8.toInt(), 0xFFE8A6C8.toInt(), 0xFFF7F3E8.toInt(), 0xFFE9B55A.toInt())
        // Paint row by row of tiles to limit memory use.
        for (ty in 0 until h) {
            for (py in 0 until ppt) {
                val fy = ty + (py + 0.5f) / ppt
                for (px in 0 until tw) {
                    val fx = (px + 0.5f) / ppt
                    val tx = px / ppt
                    val ti = map.idx(tx, ty)
                    val terr = map.terrain[ti]
                    val hsh = hash(px, ty * ppt + py)
                    val grain = ((hsh and 255) / 255f - 0.5f)
                    // Domain-warped position: terrain edges wander instead of following the grid.
                    val wx = fx + sample(warpXField, fx, fy) * 0.55f
                    val wy = fy + sample(warpYField, fx, fy) * 0.55f
                    val water = sampleTile(waterTile, wx, wy)
                    val ripple = sample(rippleField, fx, fy)
                    val patch = sample(patchField, fx, fy)
                    val hill = sample(hillField, fx, fy)

                    // ---- land colour
                    landBlend(wx, wy)
                    var c = Color.rgb(blendR.toInt().coerceIn(0, 255), blendG.toInt().coerceIn(0, 255), blendB.toInt().coerceIn(0, 255))
                    // Which land type dominates here (nearest tile at the warped position).
                    val nx = wx.toInt().coerceIn(0, w - 1); val ny = wy.toInt().coerceIn(0, h - 1)
                    var landTerr = map.terrain[map.idx(nx, ny)]
                    if (isWater(landTerr)) landTerr = if (isWater(terr)) Terrain.SAND else terr
                    c = shade(c, 1f + hill + patch * 0.2f)
                    when (landTerr) {
                        Terrain.GRASS -> {
                            // Drier, yellower grass in some patches, lush darker grass in others.
                            val dry = sample(dryField, fx, fy)
                            c = if (dry > 0.06f) lerpColor(c, 0xFF8FA046.toInt(), (dry - 0.06f) * 1.5f)
                            else lerpColor(c, 0xFF2F6A2A.toInt(), (-dry) * 1.1f)
                            // Clumps: cells of 3x3 pixels with a tuft, flower or bare spot.
                            val cell = hash(px / 3, (ty * ppt + py) / 3)
                            val cx = px % 3; val cy = (ty * ppt + py) % 3
                            c = when {
                                cell and 63 == 0 && (cx == 1 || cy == 2) -> shade(c, if (cy == 2) 0.78f else 1.22f)
                                cell and 63 == 1 && cy >= 1 && cx <= 1 -> shade(c, 0.8f)
                                cell and 1023 == 2 && cx <= 1 && cy <= 1 -> if (cx == 0 && cy == 0) shade(c, 0.7f) else flowerColors[(cell ushr 12) and 3]
                                cell and 511 == 3 && cx == 1 && cy == 1 -> shade(c, 1.3f)
                                else -> shade(c, 1f + grain * 0.07f)
                            }
                        }
                        Terrain.DIRT -> {
                            val cell = hash(px / 2, (ty * ppt + py) / 2)
                            c = when {
                                cell and 127 == 0 -> shade(c, 1.28f) // pebble
                                cell and 127 == 1 -> shade(c, 0.72f) // pebble shadow / hole
                                cell and 255 == 2 && px % 2 == 0 -> shade(c, 0.86f)
                                else -> shade(c, 1f + grain * 0.11f + sample(rippleField, fx * 0.7f, fy * 0.7f) * 0.1f)
                            }
                        }
                        Terrain.SAND -> {
                            val rip = sample(rippleField, fx * 1.3f + 7f, fy * 0.4f)
                            c = shade(c, 1f + grain * 0.05f + rip * 0.12f)
                            if ((hsh ushr 9) and 255 == 0) c = shade(c, 1.2f)
                        }
                        Terrain.MOUNTAIN -> c = shade(c, 1f + grain * 0.15f)
                        else -> c = shade(c, 1f + grain * 0.08f)
                    }
                    // Wet, darker sand right at the water line.
                    val wet = sampleTile(wetTile, wx, wy)
                    if (wet < 0.9f) c = lerpColor(c, 0xFF9C8458.toInt(), (0.9f - wet) * 0.5f)

                    // ---- water colour, blended in by the warped coverage
                    if (water > 0.3f) {
                        val depth = sampleTile(depthTile, wx, wy) // tiles from land
                        var wc = lerpColor(shallow, deep, smooth((depth - 0.2f) / 2.6f))
                        if (terr == Terrain.SHALLOWS) wc = lerpColor(wc, 0xFF5CB8B8.toInt(), 0.2f)
                        // Gentle swells and caustic-like light.
                        val wave = sin(fx * 2.1f + fy * 1.3f + sin(fy * 0.9f + fx * 0.3f) * 2f)
                        val caustic = max(0f, sample(rippleField, fx * 0.9f + 3f, fy * 0.6f) + ripple * 0.5f)
                        wc = shade(wc, 1f + wave * 0.045f + ripple * 0.1f + caustic * 0.35f * (1f - smooth(depth / 3f)) + grain * 0.02f)
                        // Light band and broken foam along the shore.
                        val shoreT = smooth((water - 0.3f) / 0.7f)
                        wc = lerpColor(wc, 0xFF8ED2CF.toInt(), (1f - shoreT) * 0.55f)
                        val foamEdge = 0.5f + ripple * 0.35f + grain * 0.1f
                        if (water < foamEdge) wc = lerpColor(wc, foam, 0.75f * (1f - smooth((water - 0.3f) / (foamEdge - 0.3f))))
                        c = lerpColor(c, wc, smooth((water - 0.3f) / 0.25f))
                    }
                    pixels[px + py * tw] = c
                }
            }
            terrain.setPixels(pixels, 0, tw, 0, ty * ppt, tw, ppt)

            // Shimmer layer for this tile row: streaky highlights on open water only.
            if (hasWater) {
                for (py in 0 until sp) {
                    val fy = ty + (py + 0.5f) / sp
                    for (px in 0 until stw) {
                        val fx = (px + 0.5f) / sp
                        val wx = fx + sample(warpXField, fx, fy) * 0.55f
                        val wy = fy + sample(warpYField, fx, fy) * 0.55f
                        val water = sampleTile(waterTile, wx, wy)
                        if (water < 0.6f) { spix[px + py * stw] = 0; continue }
                        val depth = sampleTile(depthTile, wx, wy)
                        // Thin glints running along the iso diagonal, gated by patchy noise so most water stays calm.
                        val streak = sin(fx * 7.5f + fy * 7.5f + sample(rippleField, fx * 1.3f, fy * 1.3f) * 6f)
                        val gate = sample(rippleField, fx * 0.45f + 31f, fy * 0.45f + 5f) + sample(rippleField, fx * 1.9f + 3f, fy * 1.9f + 9f) * 0.7f
                        var a = max(0f, streak - 0.82f) * 5.5f * max(0f, gate - 0.02f) * 3.5f
                        a *= smooth((water - 0.6f) / 0.3f) * (0.4f + 0.6f * smooth(depth / 2f))
                        val alpha = (a * 150f).toInt().coerceIn(0, 150)
                        spix[px + py * stw] = if (alpha == 0) 0 else Color.argb(alpha, 230, 248, 255)
                    }
                }
                shimmer.setPixels(spix, 0, stw, 0, ty * sp, stw, sp)
            }
        }
    }

    /**
     * Fog of war rendered at [FOG_PPT] subpixels per tile with soft, slightly irregular edges.
     * Only the band of tile rows whose visibility changed since the last call is re-rendered.
     */
    fun updateFog() {
        val p = world.players[humanId]
        val fp = FOG_PPT
        val fwid = w * fp
        if (world.revealed) {
            if (!fogCleared) {
                java.util.Arrays.fill(fogPixels, 0)
                fog.setPixels(fogPixels, 0, fwid, 0, 0, fwid, h * fp)
                fogCleared = true; fogValid = false
            }
            return
        }
        fogCleared = false
        val pw = w + 2
        // Per-tile darkness (0 clear, 1 dark) for the explored-but-unseen and never-seen layers,
        // tracking which tile rows changed.
        var minRow = Int.MAX_VALUE; var maxRow = -1
        for (y in 0 until h) {
            var changed = false
            for (x in 0 until w) {
                val k = (x + 1) + (y + 1) * pw
                val i = map.idx(x, y)
                val dim = if (p.visible[i] > 0) 0f else 1f
                val dark = if (p.explored[i]) 0f else 1f
                if (fogRow[k] != dim || fogRowUnexplored[k] != dark) { changed = true; fogRow[k] = dim; fogRowUnexplored[k] = dark }
            }
            if (changed) { minRow = min(minRow, y); maxRow = max(maxRow, y) }
        }
        if (!fogValid) {
            // First pass (or after a reveal): every row is dirty, and the ring outside the map is
            // extra dark so the fog reaches full black right at the map edge.
            for (x in 0 until pw) { fogRow[x] = 2f; fogRowUnexplored[x] = 2f; fogRow[x + (h + 1) * pw] = 2f; fogRowUnexplored[x + (h + 1) * pw] = 2f }
            for (y in 0 until h + 2) { fogRow[y * pw] = 2f; fogRowUnexplored[y * pw] = 2f; fogRow[w + 1 + y * pw] = 2f; fogRowUnexplored[w + 1 + y * pw] = 2f }
            minRow = 0; maxRow = h - 1
            fogValid = true
        }
        if (maxRow < 0) return
        // Bilinear footprints reach half a tile into the neighbouring rows.
        val y0 = max(0, minRow - 1); val y1 = min(h - 1, maxRow + 1)
        for (ty in y0..y1) for (sy in 0 until fp) {
            val py = ty * fp + sy
            val row = py * fwid
            // Tile-row interpolation: sub-row centre relative to tile centres.
            val gy = ty + (sy + 0.5f) / fp + 0.5f
            val j = gy.toInt(); val ay = gy - j
            val rowA = j * pw; val rowB = rowA + pw
            for (tx in 0 until w) for (sxp in 0 until fp) {
                val px = tx * fp + sxp
                val gx = tx + (sxp + 0.5f) / fp + 0.5f
                val i = gx.toInt(); val ax = gx - i
                val w00 = (1 - ax) * (1 - ay); val w10 = ax * (1 - ay); val w01 = (1 - ax) * ay; val w11 = ax * ay
                val n = ((hash(px, py) and 255) / 255f - 0.5f) * 0.12f
                val dark = smooth(fogRowUnexplored[rowA + i] * w00 + fogRowUnexplored[rowA + i + 1] * w10 + fogRowUnexplored[rowB + i] * w01 + fogRowUnexplored[rowB + i + 1] * w11 + n)
                val dim = smooth(fogRow[rowA + i] * w00 + fogRow[rowA + i + 1] * w10 + fogRow[rowB + i] * w01 + fogRow[rowB + i + 1] * w11 + n)
                val a = (dark * 255f + (1f - dark) * dim * 0x99).toInt().coerceIn(0, 255)
                fogPixels[row + px] = if (a == 0) 0 else {
                    // Unexplored is near black; explored-but-unseen is a cool blue-grey dusk.
                    val r = (10f * dark + 16f * (1 - dark)).toInt(); val g = (8f * dark + 20f * (1 - dark)).toInt(); val b = (6f * dark + 34f * (1 - dark)).toInt()
                    Color.argb(a, r, g, b)
                }
            }
        }
        val top = y0 * fp
        fog.setPixels(fogPixels, top * fwid, fwid, 0, top, fwid, (y1 - y0 + 1) * fp)
    }

    private var fogValid = false
    private var fogCleared = false

    /** Rebuilds the territory border bitmap when territory changed: a soft tinted glow and a beaded line. */
    fun updateTerritory(force: Boolean = false) {
        if (!force && territoryVersion == world.territoryVersion) return
        territoryVersion = world.territoryVersion
        val tp = terrPpt
        val tw = w * tp
        val px = terrPixels
        java.util.Arrays.fill(px, 0)
        val terr = map.territory
        fun owner(x: Int, y: Int) = if (x < 0 || y < 0 || x >= w || y >= h) -1 else terr[map.idx(x, y)]
        for (y in 0 until h) for (x in 0 until w) {
            val o = terr[map.idx(x, y)]
            if (o < 0) continue
            val col = world.players[o].color
            val cr = Color.red(col); val cg = Color.green(col); val cb = Color.blue(col)
            val left = owner(x - 1, y) != o; val right = owner(x + 1, y) != o
            val top = owner(x, y - 1) != o; val bottom = owner(x, y + 1) != o
            val tl = owner(x - 1, y - 1) != o; val tr = owner(x + 1, y - 1) != o
            val bl = owner(x - 1, y + 1) != o; val br = owner(x + 1, y + 1) != o
            val interior = !(left || right || top || bottom || tl || tr || bl || br)
            for (py in 0 until tp) for (pxx in 0 until tp) {
                val k = (x * tp + pxx) + (y * tp + py) * tw
                if (interior) { px[k] = Color.argb(0x16, cr, cg, cb); continue }
                // Distance (in sub-pixels) from this pixel to the nearest foreign edge or corner.
                val u = pxx + 0.5f; val v = py + 0.5f
                var d = tp * 2f
                if (left) d = min(d, u); if (right) d = min(d, tp - u)
                if (top) d = min(d, v); if (bottom) d = min(d, tp - v)
                if (tl) d = min(d, hyp(u, v)); if (tr) d = min(d, hyp(tp - u, v))
                if (bl) d = min(d, hyp(u, tp - v)); if (br) d = min(d, hyp(tp - u, tp - v))
                val glow = (1f - d / tp).coerceIn(0f, 1f)
                var a = 0x16 + (glow * glow * 0x5A).toInt()
                var r = cr; var g = cg; var b = cb
                if (d < tp * 0.14f) {
                    // Crisp line with brighter beads every few pixels along the edge.
                    val along = if (left || right) y * tp + py else x * tp + pxx
                    val bead = (along / 3) % 2 == 0
                    a = if (bead) 0xF0 else 0xC4
                    val lift = if (bead) 0.35f else 0.15f
                    r = (cr + (255 - cr) * lift).toInt(); g = (cg + (255 - cg) * lift).toInt(); b = (cb + (255 - cb) * lift).toInt()
                } else if (d < tp * 0.26f) {
                    a = max(a, 0x66)
                }
                px[k] = Color.argb(a.coerceIn(0, 255), r, g, b)
            }
        }
        territory.setPixels(px, 0, tw, 0, 0, tw, h * tp)
    }

    private fun hyp(a: Float, b: Float) = sqrt(a * a + b * b)

    fun updateMinimap() {
        val p = world.players[humanId]
        val reveal = world.revealed
        for (i in miniPixels.indices) {
            if (!reveal && !p.explored[i]) { miniPixels[i] = 0xFF0A0806.toInt(); continue }
            var c = tileColor[i]
            if (isWater(map.terrain[i])) c = lerpColor(c, 0xFF173F6E.toInt(), smooth((depthTile[(i % w + 1) + (i / w + 1) * (w + 2)] - 0.5f) / 2.5f))
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
        terrain.recycle(); shimmer.recycle(); fog.recycle(); territory.recycle(); minimap.recycle()
    }

    companion object {
        const val FOG_PPT = 4
        private const val FIELD_RES = 4

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
