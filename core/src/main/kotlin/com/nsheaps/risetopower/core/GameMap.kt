package com.nsheaps.risetopower.core

class GameMap(val width: Int, val height: Int) {
    val terrain = Array(width * height) { Terrain.GRASS }

    /** Height used for subtle shading only. */
    val elevation = FloatArray(width * height)

    /** Entity id occupying each tile (buildings and resource nodes), or -1. */
    val occupant = IntArray(width * height) { -1 }

    /** True when the tile can not be walked over. */
    val blocked = BooleanArray(width * height)

    /** Owning player of each tile's territory, or -1. */
    val territory = IntArray(width * height) { -1 }

    fun idx(x: Int, y: Int) = x + y * width

    fun inBounds(x: Int, y: Int) = x in 0 until width && y in 0 until height

    fun terrainAt(x: Int, y: Int) = terrain[idx(x, y)]

    fun isWalkable(x: Int, y: Int) = inBounds(x, y) && !blocked[idx(x, y)]

    fun isWalkableF(x: Float, y: Float): Boolean {
        if (x < 0f || y < 0f) return false
        return isWalkable(x.toInt(), y.toInt())
    }

    fun refreshBlocked(i: Int, entityBlocks: Boolean) {
        blocked[i] = !terrain[i].walkable || entityBlocks
    }
}
