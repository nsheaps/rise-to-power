package com.nsheaps.risetopower.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

const val GAIA = -1

sealed class Entity(val id: Int, var owner: Int) {
    var x = 0f
    var y = 0f
    var hp = 1f
    var maxHp = 1f
    val alive get() = hp > 0f

    /** Axis aligned footprint (in tiles) used for range checks. */
    abstract val minX: Float
    abstract val minY: Float
    abstract val maxX: Float
    abstract val maxY: Float

    /** Distance from point (px,py) to the edge of this entity's footprint. */
    fun distanceTo(px: Float, py: Float): Float {
        val dx = max(max(minX - px, 0f), px - maxX)
        val dy = max(max(minY - py, 0f), py - maxY)
        return sqrt(dx * dx + dy * dy)
    }
}

enum class OrderType { IDLE, MOVE, ATTACK_MOVE, ATTACK, GATHER, BUILD, RETURN, HEAL }

class GameUnit(id: Int, owner: Int, val type: UnitType) : Entity(id, owner) {
    val radius = when (type) {
        UnitType.CATAPULT -> 0.4f
        UnitType.HORSEMAN, UnitType.HORSE_ARCHER, UnitType.SCOUT -> 0.32f
        else -> 0.24f
    }
    override val minX get() = x - radius
    override val minY get() = y - radius
    override val maxX get() = x + radius
    override val maxY get() = y + radius

    var order = OrderType.IDLE
    var targetId = -1
    var destX = 0f
    var destY = 0f

    /** Waypoints in world coordinates: x0,y0,x1,y1... */
    var path: FloatArray = FloatArray(0)
    var pathIndex = 0
    var repathTimer = 0f
    var stuckTimer = 0f

    var carryType: ResourceType? = null
    var carryAmount = 0f
    var gatherNodeId = -1
    var gatherResource: ResourceType? = null
    var gatherX = 0f
    var gatherY = 0f

    var attackTimer = 0f
    var facing = 0f
    var moving = false
    var animTime = 0f
    var attackAnim = 0f
    var idleTime = 0f
    var attritionTimer = 0f
    var kills = 0

    /** Destination of an attack-move that is resumed after each engagement. */
    var attackMove = false
    var amX = 0f
    var amY = 0f

    /** What the current path leads to, to know when to recompute it. */
    var pathGoalId = -1
    var pathGoalX = 0f
    var pathGoalY = 0f

    fun clearPath() {
        path = FloatArray(0)
        pathIndex = 0
    }

    val hasPath get() = pathIndex * 2 < path.size
}

sealed class ProdItem {
    abstract val time: Float
    data class Train(val unit: UnitType, override val time: Float) : ProdItem()
    data class Research(val tech: Tech, override val time: Float) : ProdItem()
    data class Advance(val age: Age, override val time: Float) : ProdItem()
}

class Building(id: Int, owner: Int, val type: BuildingType, val tx: Int, val ty: Int) : Entity(id, owner) {
    init {
        x = tx + type.size / 2f
        y = ty + type.size / 2f
    }

    override val minX get() = tx.toFloat()
    override val minY get() = ty.toFloat()
    override val maxX get() = (tx + type.size).toFloat()
    override val maxY get() = (ty + type.size).toFloat()

    var constructed = false
    var progress = 0f
    val queue = ArrayList<ProdItem>()
    var queueProgress = 0f
    var rallyX = Float.NaN
    var rallyY = Float.NaN
    var attackTimer = 0f
    var farmerId = -1
    var wonderTimer = -1f
    var underAttackTimer = 0f
    var tradeTimer = 0f

    val hasRally get() = !rallyX.isNaN()

    fun contains(px: Int, py: Int) = px >= tx && py >= ty && px < tx + type.size && py < ty + type.size
}

class ResourceNode(id: Int, val kind: NodeKind, val tx: Int, val ty: Int, var amount: Float) : Entity(id, GAIA) {
    init {
        x = tx + kind.size / 2f
        y = ty + kind.size / 2f
        hp = 1f
        maxHp = 1f
    }

    override val minX get() = tx.toFloat()
    override val minY get() = ty.toFloat()
    override val maxX get() = (tx + kind.size).toFloat()
    override val maxY get() = (ty + kind.size).toFloat()

    /** Visual variation seed. */
    val variant = (tx * 7349 + ty * 1931) and 0xff
}

class Projectile(
    val owner: Int,
    val sourceId: Int,
    val targetId: Int,
    var x: Float,
    var y: Float,
    var tx: Float,
    var ty: Float,
    val damage: Float,
    val pierce: Boolean,
    val splash: Float,
    val speed: Float,
    val kind: Int,
    val bonus: Map<ArmorClass, Float>,
) {
    val sx = x
    val sy = y
    var done = false

    fun progress(): Float {
        val total = dist(sx, sy, tx, ty)
        if (total < 0.001f) return 1f
        return min(1f, dist(sx, sy, x, y) / total)
    }

    companion object {
        const val ARROW = 0
        const val STONE = 1
        const val BULLET = 2
    }
}

fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = ax - bx
    val dy = ay - by
    return sqrt(dx * dx + dy * dy)
}
