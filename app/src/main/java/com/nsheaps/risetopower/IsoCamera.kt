package com.nsheaps.risetopower

import android.graphics.Matrix

/**
 * Isometric camera. World tile (x, y) maps to iso space ((x - y) * 32, (x + y) * 16), which is
 * then scaled by [scale] and centred on the view.
 */
class IsoCamera(private val density: Float) {
    var viewW = 1f
    var viewH = 1f
    var cx = 0f
    var cy = 0f
    var zoom = 1f
    var mapW = 64
    var mapH = 64

    val baseScale get() = density * 0.55f
    val scale get() = zoom * baseScale

    fun sx(wx: Float, wy: Float) = ((wx - wy) * HALF_W - cx) * scale + viewW / 2f
    fun sy(wx: Float, wy: Float) = ((wx + wy) * HALF_H - cy) * scale + viewH / 2f

    fun worldX(sx: Float, sy: Float): Float {
        val ix = (sx - viewW / 2f) / scale + cx
        val iy = (sy - viewH / 2f) / scale + cy
        return (ix / HALF_W + iy / HALF_H) / 2f
    }

    fun worldY(sx: Float, sy: Float): Float {
        val ix = (sx - viewW / 2f) / scale + cx
        val iy = (sy - viewH / 2f) / scale + cy
        return (iy / HALF_H - ix / HALF_W) / 2f
    }

    fun centerOn(wx: Float, wy: Float) {
        cx = (wx - wy) * HALF_W
        cy = (wx + wy) * HALF_H
        clamp()
    }

    fun pan(dxScreen: Float, dyScreen: Float) {
        cx -= dxScreen / scale
        cy -= dyScreen / scale
        clamp()
    }

    fun zoomAround(factor: Float, fx: Float, fy: Float) {
        val wx = worldX(fx, fy)
        val wy = worldY(fx, fy)
        zoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        // Keep the focal world point under the fingers.
        val nsx = sx(wx, wy)
        val nsy = sy(wx, wy)
        cx += (nsx - fx) / scale
        cy += (nsy - fy) / scale
        clamp()
    }

    fun clamp() {
        val wx = worldXOfCenter().coerceIn(0f, mapW.toFloat())
        val wy = worldYOfCenter().coerceIn(0f, mapH.toFloat())
        cx = (wx - wy) * HALF_W
        cy = (wx + wy) * HALF_H
    }

    fun worldXOfCenter() = (cx / HALF_W + cy / HALF_H) / 2f
    fun worldYOfCenter() = (cy / HALF_H - cx / HALF_W) / 2f

    /** Matrix mapping a tile-space bitmap with [ppt] pixels per tile onto the screen. */
    fun tileMatrix(m: Matrix, ppt: Float) {
        val s = scale
        val a = HALF_W * s / ppt
        val d = HALF_H * s / ppt
        vals[0] = a; vals[1] = -a; vals[2] = -cx * s + viewW / 2f
        vals[3] = d; vals[4] = d; vals[5] = -cy * s + viewH / 2f
        vals[6] = 0f; vals[7] = 0f; vals[8] = 1f
        m.setValues(vals)
    }

    private val vals = FloatArray(9)

    /** World-space bounding box of the visible screen, padded by [pad] tiles. */
    fun visibleBounds(out: IntArray, pad: Int) {
        val xs = floatArrayOf(worldX(0f, 0f), worldX(viewW, 0f), worldX(0f, viewH), worldX(viewW, viewH))
        val ys = floatArrayOf(worldY(0f, 0f), worldY(viewW, 0f), worldY(0f, viewH), worldY(viewW, viewH))
        out[0] = (xs.min().toInt() - pad).coerceIn(0, mapW - 1)
        out[1] = (ys.min().toInt() - pad).coerceIn(0, mapH - 1)
        out[2] = (xs.max().toInt() + pad).coerceIn(0, mapW - 1)
        out[3] = (ys.max().toInt() + pad).coerceIn(0, mapH - 1)
    }

    companion object {
        const val HALF_W = 32f
        const val HALF_H = 16f
        const val MIN_ZOOM = 0.35f
        const val MAX_ZOOM = 2.6f
    }
}
