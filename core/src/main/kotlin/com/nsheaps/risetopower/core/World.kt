package com.nsheaps.risetopower.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

enum class EventType { INFO, ATTACKED, AGE_UP, BUILT, TRAINED, RESEARCHED, DEFEATED, VICTORY, WONDER, WARNING }

/** Lightweight audio cue for the presentation layer. */
class SoundCue(val kind: Int, val x: Float, val y: Float) {
    companion object {
        const val MELEE = 0
        const val SHOOT = 1
        const val SIEGE = 2
        const val DEATH = 3
        const val COLLAPSE = 4
        const val BUILD = 5
    }
}

class GameEvent(val type: EventType, val player: Int, val text: String, val x: Float = Float.NaN, val y: Float = Float.NaN)

data class PlayerSetup(
    val name: String,
    val civ: Civ,
    val isHuman: Boolean,
    val team: Int,
    val difficulty: Difficulty = Difficulty.NORMAL,
    val color: Int,
)

data class GameSettings(
    val mapSize: MapSize = MapSize.MEDIUM,
    val mapType: MapType = MapType.CONTINENTAL,
    val seed: Long = 1L,
    val players: List<PlayerSetup>,
    val startingResources: Int = 200,
    val wonderVictory: Boolean = true,
    val revealMap: Boolean = false,
)

/**
 * The complete game simulation. All game rules live here; the Android layer only renders the
 * state and translates touches into commands.
 */
class World(val settings: GameSettings, generate: Boolean = true) {
    val map = GameMap(settings.mapSize.tiles, settings.mapSize.tiles)
    val players: List<Player> = settings.players.mapIndexed { i, s ->
        Player(i, s.name, s.color, s.civ, s.isHuman, s.team, s.difficulty)
    }
    val entities = HashMap<Int, Entity>()
    val units = ArrayList<GameUnit>()
    val buildings = ArrayList<Building>()
    val nodes = ArrayList<ResourceNode>()
    val projectiles = ArrayList<Projectile>()
    val events = ArrayDeque<GameEvent>()
    val sounds = ArrayList<SoundCue>()
    val pathfinder = Pathfinder(map)
    var rng = Random(settings.seed)
    val ais = ArrayList<AiController>()

    var nextId = 1
    var time = 0f
    var tick = 0L
    var gameOver = false
    /** Set when a defeated human keeps spectating: removes the fog of war. */
    var revealAll = false
    val revealed get() = settings.revealMap || revealAll
    var winnerTeam = -1
    var territoryDirty = true
    /** Incremented whenever territory changes so renderers can refresh caches. */
    var territoryVersion = 0
    var visionDirty = true

    private val lastAlert = FloatArray(players.size) { -100f }
    private val lastHousingAlert = FloatArray(players.size) { -100f }
    private var visionTimer = 0f
    private var checkTimer = 0f

    // Spatial hash of units, rebuilt each tick.
    private val cellSize = 4
    private val cellsW = (map.width + cellSize - 1) / cellSize
    private val cellsH = (map.height + cellSize - 1) / cellSize
    private val cells = Array(cellsW * cellsH) { ArrayList<GameUnit>(8) }

    init {
        for (p in players) {
            p.explored = BooleanArray(map.width * map.height)
            p.visible = ByteArray(map.width * map.height)
        }
        if (generate) {
            MapGenerator(this).generate()
            for (p in players) {
                for (r in ResourceType.entries) p.stock[r.ordinal] = settings.startingResources.toFloat()
                p.stock[ResourceType.FOOD.ordinal] += 100f
                p.stock[ResourceType.WOOD.ordinal] += 100f
                if (!p.isHuman) ais += AiController(this, p.id)
            }
            if (settings.revealMap) players.forEach { it.explored.fill(true) }
            recomputePopulation()
            updateTerritory()
            updateVision()
        }
    }

    // ------------------------------------------------------------------ queries

    fun get(id: Int): Entity? = entities[id]?.takeIf { it.alive }
    fun unit(id: Int): GameUnit? = get(id) as? GameUnit
    fun building(id: Int): Building? = get(id) as? Building
    fun player(id: Int): Player? = players.getOrNull(id)

    fun isEnemy(a: Int, b: Int): Boolean {
        if (a == GAIA || b == GAIA || a == b) return false
        return players[a].team != players[b].team
    }

    fun isAlly(a: Int, b: Int) = a != GAIA && b != GAIA && players[a].team == players[b].team

    fun entityAtTile(x: Int, y: Int): Entity? {
        if (!map.inBounds(x, y)) return null
        val id = map.occupant[map.idx(x, y)]
        return if (id >= 0) get(id) else null
    }

    fun forUnitsNear(x: Float, y: Float, r: Float, fn: (GameUnit) -> Unit) {
        val cx0 = ((x - r).toInt() / cellSize).coerceIn(0, cellsW - 1)
        val cx1 = ((x + r).toInt() / cellSize).coerceIn(0, cellsW - 1)
        val cy0 = ((y - r).toInt() / cellSize).coerceIn(0, cellsH - 1)
        val cy1 = ((y + r).toInt() / cellSize).coerceIn(0, cellsH - 1)
        val r2 = r * r
        for (cy in cy0..cy1) for (cx in cx0..cx1) {
            val list = cells[cx + cy * cellsW]
            for (i in list.indices) {
                val u = list[i]
                if (!u.alive) continue
                val dx = u.x - x
                val dy = u.y - y
                if (dx * dx + dy * dy <= r2) fn(u)
            }
        }
    }

    fun isVisibleTo(playerId: Int, x: Float, y: Float): Boolean {
        val p = players[playerId]
        val xi = x.toInt(); val yi = y.toInt()
        if (!map.inBounds(xi, yi)) return false
        return revealed || p.visible[map.idx(xi, yi)] > 0
    }

    fun isExploredBy(playerId: Int, x: Int, y: Int): Boolean {
        if (!map.inBounds(x, y)) return false
        return revealed || players[playerId].explored[map.idx(x, y)]
    }

    fun townCenters(owner: Int) = buildings.filter { it.alive && it.owner == owner && it.type == BuildingType.TOWN_CENTER }

    fun countBuildings(owner: Int, type: BuildingType, includeFoundations: Boolean = true) =
        buildings.count { it.alive && it.owner == owner && it.type == type && (includeFoundations || it.constructed) }

    fun hasBuilding(owner: Int, type: BuildingType) = buildings.any { it.alive && it.owner == owner && it.type == type && it.constructed }

    fun armorClassOf(e: Entity) = when (e) {
        is GameUnit -> e.type.armorClass
        else -> ArmorClass.BUILDING
    }

    // ------------------------------------------------------------------ creation / removal

    fun spawnUnit(owner: Int, type: UnitType, x: Float, y: Float): GameUnit {
        val u = GameUnit(nextId++, owner, type)
        u.x = x; u.y = y
        u.maxHp = players[owner].unitMaxHp(type)
        u.hp = u.maxHp
        u.facing = rng.nextFloat() * 6.28f
        entities[u.id] = u
        units += u
        addToCell(u)
        return u
    }

    fun spawnNode(kind: NodeKind, tx: Int, ty: Int): ResourceNode? {
        for (dy in 0 until kind.size) for (dx in 0 until kind.size) {
            val x = tx + dx; val y = ty + dy
            if (!map.inBounds(x, y) || map.blocked[map.idx(x, y)]) return null
        }
        val n = ResourceNode(nextId++, kind, tx, ty, kind.amount.toFloat())
        entities[n.id] = n
        nodes += n
        occupy(n.id, tx, ty, kind.size, true)
        return n
    }

    fun addBuilding(owner: Int, type: BuildingType, tx: Int, ty: Int, constructed: Boolean): Building {
        val b = Building(nextId++, owner, type, tx, ty)
        b.maxHp = players[owner].buildingMaxHp(type)
        b.constructed = constructed
        b.progress = if (constructed) 1f else 0f
        b.hp = if (constructed) b.maxHp else max(1f, b.maxHp * 0.05f)
        entities[b.id] = b
        buildings += b
        occupy(b.id, tx, ty, type.size, type.blocksMovement)
        if (type.blocksMovement) evictUnits(b)
        if (constructed) {
            territoryDirty = true
            visionDirty = true
        }
        return b
    }

    private fun occupy(id: Int, tx: Int, ty: Int, size: Int, blocks: Boolean) {
        for (dy in 0 until size) for (dx in 0 until size) {
            val i = map.idx(tx + dx, ty + dy)
            map.occupant[i] = id
            map.refreshBlocked(i, blocks)
        }
    }

    private fun release(tx: Int, ty: Int, size: Int) {
        for (dy in 0 until size) for (dx in 0 until size) {
            val i = map.idx(tx + dx, ty + dy)
            map.occupant[i] = -1
            map.refreshBlocked(i, false)
        }
    }

    private fun evictUnits(b: Building) {
        for (u in units) {
            if (!u.alive) continue
            if (u.x >= b.minX - 0.1f && u.x <= b.maxX + 0.1f && u.y >= b.minY - 0.1f && u.y <= b.maxY + 0.1f) {
                val free = pathfinder.nearestFree(u.x.toInt(), u.y.toInt()) ?: continue
                u.x = free % map.width + 0.5f
                u.y = free / map.width + 0.5f
                u.clearPath()
            }
        }
    }

    private fun removeEntity(e: Entity) {
        entities.remove(e.id)
        when (e) {
            is Building -> {
                release(e.tx, e.ty, e.type.size)
                buildings.remove(e)
                territoryDirty = true
                visionDirty = true
            }
            is ResourceNode -> {
                release(e.tx, e.ty, e.kind.size)
                nodes.remove(e)
            }
            is GameUnit -> Unit // removed from list at end of tick
        }
    }

    fun cue(kind: Int, x: Float, y: Float) {
        if (sounds.size < 64) sounds += SoundCue(kind, x, y)
    }

    fun emit(e: GameEvent) {
        events.addLast(e)
        while (events.size > 200) events.removeFirst()
    }

    // ------------------------------------------------------------------ placement rules

    /** Returns null when placement is legal, otherwise a human readable reason. */
    fun placementError(owner: Int, type: BuildingType, tx: Int, ty: Int, checkCost: Boolean = true): String? {
        val p = players[owner]
        if (p.age < type.minAge) return "Requires ${type.minAge.displayName}"
        if (checkCost && !p.canAfford(type.cost)) return "Not enough resources"
        for (dy in 0 until type.size) for (dx in 0 until type.size) {
            val x = tx + dx; val y = ty + dy
            if (!map.inBounds(x, y)) return "Out of bounds"
            val i = map.idx(x, y)
            if (!map.terrain[i].buildable || map.blocked[i] || map.occupant[i] >= 0) return "Blocked"
        }
        val cx = tx + type.size / 2f
        val cy = ty + type.size / 2f
        when (type) {
            BuildingType.TOWN_CENTER -> {
                val maxTc = 2 + p.age.ordinal
                if (countBuildings(owner, type) >= maxTc) return "City limit reached ($maxTc)"
                for (b in buildings) if (b.alive && b.type == BuildingType.TOWN_CENTER && dist(b.x, b.y, cx, cy) < 14f) return "Too close to another city"
                if (anyTile(tx, ty, type.size) { t -> t >= 0 && isEnemy(owner, t) }) return "Inside enemy territory"
            }
            BuildingType.TOWER, BuildingType.WALL, BuildingType.FORTRESS -> {
                if (anyTile(tx, ty, type.size) { t -> t >= 0 && !isAlly(owner, t) }) return "Inside enemy territory"
            }
            BuildingType.WONDER -> {
                if (countBuildings(owner, type) >= 1) return "Only one Wonder allowed"
                if (anyTile(tx, ty, type.size) { t -> t != owner }) return "Must be inside your borders"
            }
            else -> if (anyTile(tx, ty, type.size) { t -> t != owner }) return "Must be inside your borders"
        }
        // Units in the way of a blocking building.
        if (type.blocksMovement) {
            for (u in units) {
                if (!u.alive || !isEnemy(owner, u.owner)) continue
                if (u.x >= tx && u.x <= tx + type.size && u.y >= ty && u.y <= ty + type.size) return "Enemy units in the way"
            }
        }
        return null
    }

    private inline fun anyTile(tx: Int, ty: Int, size: Int, pred: (Int) -> Boolean): Boolean {
        for (dy in 0 until size) for (dx in 0 until size) if (pred(map.territory[map.idx(tx + dx, ty + dy)])) return true
        return false
    }

    // ------------------------------------------------------------------ commands

    fun placeFoundation(owner: Int, type: BuildingType, tx: Int, ty: Int, builders: List<Int>): Building? {
        val err = placementError(owner, type, tx, ty)
        if (err != null) {
            if (players[owner].isHuman) emit(GameEvent(EventType.WARNING, owner, err))
            return null
        }
        players[owner].pay(type.cost)
        val b = addBuilding(owner, type, tx, ty, false)
        commandBuild(builders, b.id)
        return b
    }

    /** Places a straight line of wall segments; returns the number placed. */
    fun placeWall(owner: Int, x0: Int, y0: Int, x1: Int, y1: Int, builders: List<Int>): Int {
        val pts = ArrayList<Pair<Int, Int>>()
        val dx = x1 - x0
        val dy = y1 - y0
        val steps = max(abs(dx), abs(dy))
        for (s in 0..steps) {
            val t = if (steps == 0) 0f else s.toFloat() / steps
            val p = Pair(x0 + (dx * t).let { kotlin.math.round(it).toInt() }, y0 + (dy * t).let { kotlin.math.round(it).toInt() })
            if (pts.isEmpty() || pts.last() != p) pts += p
        }
        var placed = 0
        var first: Building? = null
        val segments = ArrayList<Building>()
        for ((x, y) in pts) {
            if (placementError(owner, BuildingType.WALL, x, y) != null) continue
            players[owner].pay(BuildingType.WALL.cost)
            val b = addBuilding(owner, BuildingType.WALL, x, y, false)
            segments += b
            if (first == null) first = b
            placed++
        }
        if (first != null) commandBuild(builders, first.id)
        if (placed == 0 && players[owner].isHuman) emit(GameEvent(EventType.WARNING, owner, "Can't place wall there"))
        return placed
    }

    private fun ownUnits(owner: Int, ids: List<Int>) = ids.mapNotNull { unit(it) }.filter { it.owner == owner }

    fun commandStop(ids: List<Int>) {
        for (id in ids) {
            val u = unit(id) ?: continue
            setIdle(u)
        }
    }

    fun commandMove(ids: List<Int>, x: Float, y: Float, attackMove: Boolean = false) {
        val list = ids.mapNotNull { unit(it) }
        if (list.isEmpty()) return
        val offsets = formationOffsets(list.size)
        // Keep relative ordering stable: closest units take the central slots.
        val sorted = list.sortedBy { dist(it.x, it.y, x, y) }
        var slot = 0
        for (u in sorted) {
            var dx = 0f; var dy = 0f
            while (slot < offsets.size) {
                val (ox, oy) = offsets[slot++]
                if (map.isWalkableF(x + ox, y + oy)) { dx = ox; dy = oy; break }
            }
            val tx = (x + dx).coerceIn(0.3f, map.width - 0.3f)
            val ty = (y + dy).coerceIn(0.3f, map.height - 0.3f)
            u.order = if (attackMove && u.type.isMilitary && u.type != UnitType.HEALER) OrderType.ATTACK_MOVE else OrderType.MOVE
            u.attackMove = u.order == OrderType.ATTACK_MOVE
            u.amX = tx; u.amY = ty
            u.targetId = -1
            moveTo(u, tx, ty)
        }
    }

    private fun formationOffsets(n: Int): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        out += Pair(0f, 0f)
        var ring = 1
        val spacing = 0.75f
        while (out.size < n * 3 + 8) {
            for (dy in -ring..ring) for (dx in -ring..ring) {
                if (max(abs(dx), abs(dy)) != ring) continue
                out += Pair(dx * spacing, dy * spacing)
            }
            ring++
        }
        return out
    }

    fun commandAttack(ids: List<Int>, targetId: Int) {
        val t = get(targetId) ?: return
        for (id in ids) {
            val u = unit(id) ?: continue
            if (u.type == UnitType.HEALER) { commandMove(listOf(id), t.x, t.y); continue }
            if (u.type.attack <= 0) continue
            u.attackMove = false
            u.order = OrderType.ATTACK
            u.targetId = t.id
            u.clearPath()
        }
    }

    fun commandGather(ids: List<Int>, targetId: Int) {
        val t = get(targetId) ?: return
        for (id in ids) {
            val u = unit(id) ?: continue
            if (u.type != UnitType.VILLAGER) { commandMove(listOf(id), t.x, t.y); continue }
            when (t) {
                is ResourceNode -> startGather(u, t)
                is Building -> if (t.type == BuildingType.FARM && t.constructed) startFarm(u, t)
                else -> {}
            }
        }
    }

    fun commandBuild(ids: List<Int>, buildingId: Int) {
        val b = building(buildingId) ?: return
        for (id in ids) {
            val u = unit(id) ?: continue
            if (u.type != UnitType.VILLAGER || u.owner != b.owner) continue
            u.attackMove = false
            u.order = OrderType.BUILD
            u.targetId = b.id
            u.clearPath()
        }
    }

    fun commandHeal(ids: List<Int>, targetId: Int) {
        for (id in ids) {
            val u = unit(id) ?: continue
            if (u.type != UnitType.HEALER) continue
            u.order = OrderType.HEAL
            u.targetId = targetId
            u.clearPath()
        }
    }

    /** Return carried resources to the nearest drop site. */
    fun commandReturn(ids: List<Int>) {
        for (id in ids) {
            val u = unit(id) ?: continue
            if (u.carryAmount > 0f) { u.order = OrderType.RETURN; u.clearPath() }
        }
    }

    /** Context sensitive command used for taps: decides between move, attack, gather, build, heal. */
    fun commandSmart(owner: Int, ids: List<Int>, x: Float, y: Float, target: Entity?) {
        val list = ownUnits(owner, ids)
        if (list.isEmpty()) return
        if (target == null) { commandMove(list.map { it.id }, x, y); return }
        val villagers = list.filter { it.type == UnitType.VILLAGER }.map { it.id }
        val healers = list.filter { it.type == UnitType.HEALER }.map { it.id }
        val military = list.filter { it.type != UnitType.VILLAGER && it.type != UnitType.HEALER }.map { it.id }
        when (target) {
            is ResourceNode -> {
                commandGather(villagers, target.id)
                if (military.isNotEmpty() || healers.isNotEmpty()) commandMove(military + healers, x, y)
            }
            is Building -> {
                if (isEnemy(owner, target.owner)) {
                    commandAttack(list.map { it.id }.filter { unit(it)?.type != UnitType.HEALER }, target.id)
                } else if (target.owner == owner) {
                    if (target.type == BuildingType.FARM && target.constructed && target.farmerId < 0 && villagers.isNotEmpty()) {
                        commandGather(listOf(villagers.first()), target.id)
                        if (villagers.size > 1) commandBuild(villagers.drop(1), target.id)
                    } else if (!target.constructed || target.hp < target.maxHp) {
                        commandBuild(villagers, target.id)
                    } else if (target.type.isDropSite && villagers.isNotEmpty()) {
                        commandReturn(villagers)
                    } else commandMove(villagers, x, y)
                    if (military.isNotEmpty() || healers.isNotEmpty()) commandMove(military + healers, x, y)
                } else commandMove(list.map { it.id }, x, y)
            }
            is GameUnit -> {
                if (isEnemy(owner, target.owner)) {
                    commandAttack((military + villagers), target.id)
                    if (healers.isNotEmpty()) commandMove(healers, x, y)
                } else {
                    if (healers.isNotEmpty() && target.hp < target.maxHp) commandHeal(healers, target.id)
                    else if (healers.isNotEmpty()) commandMove(healers, x, y)
                    commandMove(military + villagers, x, y)
                }
            }
        }
    }

    fun queueTrain(buildingId: Int, type: UnitType): String? {
        val b = building(buildingId) ?: return "Invalid building"
        val p = players[b.owner]
        if (!b.constructed) return "Under construction"
        if (type !in b.type.trains()) return "Can't train here"
        if (p.age < type.minAge) return "Requires ${type.minAge.displayName}"
        if (b.queue.size >= 10) return "Queue full"
        if (!p.pay(type.cost)) return "Not enough resources"
        b.queue += ProdItem.Train(type, p.trainTime(type))
        return null
    }

    fun queueResearch(buildingId: Int, tech: Tech): String? {
        val b = building(buildingId) ?: return "Invalid building"
        val p = players[b.owner]
        if (!b.constructed) return "Under construction"
        if (tech.building != b.type) return "Can't research here"
        if (!canResearch(p, tech)) return "Not available"
        if (!p.pay(p.techCost(tech))) return "Not enough resources"
        b.queue += ProdItem.Research(tech, tech.time)
        return null
    }

    fun canResearch(p: Player, tech: Tech): Boolean {
        if (p.has(tech) || p.age < tech.minAge) return false
        if (tech.requires != null && !p.has(tech.requires)) return false
        // Not already queued anywhere.
        for (b in buildings) if (b.owner == p.id && b.alive) for (q in b.queue) if (q is ProdItem.Research && q.tech == tech) return false
        return true
    }

    fun isAdvancing(owner: Int) = buildings.any { b -> b.alive && b.owner == owner && b.queue.any { it is ProdItem.Advance } }

    fun queueAdvance(buildingId: Int): String? {
        val b = building(buildingId) ?: return "Invalid building"
        if (b.type != BuildingType.TOWN_CENTER || !b.constructed) return "Requires a Town Center"
        val p = players[b.owner]
        val next = p.age.next ?: return "Already in the final age"
        if (isAdvancing(p.id)) return "Already advancing"
        if (!p.pay(next.advanceCost)) return "Not enough resources"
        b.queue += ProdItem.Advance(next, next.advanceTime)
        return null
    }

    fun cancelQueue(buildingId: Int, index: Int) {
        val b = building(buildingId) ?: return
        if (index !in b.queue.indices) return
        val item = b.queue.removeAt(index)
        val p = players[b.owner]
        when (item) {
            is ProdItem.Train -> p.refund(item.unit.cost)
            is ProdItem.Research -> p.refund(p.techCost(item.tech))
            is ProdItem.Advance -> p.refund(item.age.advanceCost)
        }
        if (index == 0) b.queueProgress = 0f
        recomputePopulation()
    }

    fun setRally(buildingId: Int, x: Float, y: Float) {
        val b = building(buildingId) ?: return
        b.rallyX = x; b.rallyY = y
    }

    /** Destroys an own entity (or cancels a foundation with a refund). */
    fun deleteOwn(owner: Int, id: Int) {
        val e = get(id) ?: return
        if (e.owner != owner) return
        if (e is Building && !e.constructed) {
            players[owner].refund(e.type.cost, 1f - e.progress * 0.5f)
            e.hp = 0f
            removeEntity(e)
            return
        }
        kill(e, GAIA)
    }

    /** Buys (positive amount) or sells (negative) 100 units of a resource for gold. */
    fun marketTrade(owner: Int, r: ResourceType, buy: Boolean): String? {
        if (r == ResourceType.GOLD) return "Can't trade gold"
        if (!hasBuilding(owner, BuildingType.MARKET)) return "Requires a Market"
        val p = players[owner]
        val price = p.marketPrice[r.ordinal]
        val spread = p.marketSpread
        if (buy) {
            val cost = price * (1f + spread)
            if (p.stock[ResourceType.GOLD.ordinal] < cost) return "Not enough gold"
            p.stock[ResourceType.GOLD.ordinal] -= cost
            p.stock[r.ordinal] += 100f
            p.marketPrice[r.ordinal] = min(400f, price + 6f)
        } else {
            if (p.stock[r.ordinal] < 100f) return "Not enough ${r.displayName.lowercase()}"
            p.stock[r.ordinal] -= 100f
            p.stock[ResourceType.GOLD.ordinal] += price * (1f - spread)
            p.marketPrice[r.ordinal] = max(20f, price - 6f)
        }
        return null
    }

    fun buyPrice(owner: Int, r: ResourceType) = players[owner].marketPrice[r.ordinal] * (1f + players[owner].marketSpread)
    fun sellPrice(owner: Int, r: ResourceType) = players[owner].marketPrice[r.ordinal] * (1f - players[owner].marketSpread)

    fun resign(owner: Int) {
        defeat(players[owner])
    }

    /**
     * Applies a player's command. Only the player's own units and buildings are affected, so a
     * command received over the network can never move someone else's army.
     */
    fun execute(owner: Int, c: Command) {
        if (owner !in players.indices || players[owner].defeated || gameOver) return
        fun units(ids: List<Int>) = ownUnits(owner, ids).map { it.id }
        fun own(id: Int) = building(id)?.takeIf { it.owner == owner }
        val err: String? = when (c) {
            is Command.Smart -> { commandSmart(owner, c.ids, c.x, c.y, get(c.targetId)); null }
            is Command.Move -> { commandMove(units(c.ids), c.x, c.y, c.attackMove); null }
            is Command.Stop -> { commandStop(units(c.ids)); null }
            is Command.Return -> { commandReturn(units(c.ids)); null }
            is Command.Delete -> { for (id in c.ids) deleteOwn(owner, id); null }
            is Command.Place -> { placeFoundation(owner, c.type, c.tx, c.ty, units(c.builders)); null }
            is Command.Wall -> { placeWall(owner, c.x0, c.y0, c.x1, c.y1, units(c.builders)); null }
            is Command.Train -> own(c.buildingId)?.let { queueTrain(it.id, c.unit) }
            is Command.Research -> own(c.buildingId)?.let { queueResearch(it.id, c.tech) }
            is Command.Advance -> own(c.buildingId)?.let { queueAdvance(it.id) }
            is Command.CancelQueue -> { own(c.buildingId)?.let { cancelQueue(it.id, c.index) }; null }
            is Command.Rally -> { for (id in c.buildingIds) own(id)?.let { setRally(it.id, c.x, c.y) }; null }
            is Command.Trade -> marketTrade(owner, c.resource, c.buy)
            Command.Resign -> { resign(owner); null }
        }
        if (err != null && players[owner].isHuman) emit(GameEvent(EventType.WARNING, owner, err))
    }

    /**
     * Hash of the simulation state, used to detect when networked games drift apart. It covers
     * everything that decides the outcome of the game: positions, health, orders and stockpiles.
     */
    fun checksum(): Long {
        var h = 1125899906842597L
        fun mix(v: Long) { h = (h xor v) * 0x100000001B3L }
        fun mix(v: Int) = mix(v.toLong())
        fun mix(v: Float) = mix(v.toRawBits())
        mix(tick); mix(nextId)
        for (p in players) { for (v in p.stock) mix(v); mix(p.age.ordinal); mix(p.popUsed); mix(if (p.defeated) 1 else 0) }
        for (u in units) { mix(u.id); mix(u.x); mix(u.y); mix(u.hp); mix(u.order.ordinal); mix(u.targetId); mix(u.carryAmount) }
        for (b in buildings) { mix(b.id); mix(b.hp); mix(b.progress); mix(b.queueProgress); mix(b.queue.size) }
        for (n in nodes) { mix(n.id); mix(n.amount) }
        for (pr in projectiles) { mix(pr.x); mix(pr.y) }
        return h
    }

    // ------------------------------------------------------------------ simulation

    fun update(dt: Float) {
        if (gameOver) return
        time += dt
        tick++
        rebuildCells()
        for (ai in ais) ai.update(dt)
        val n = units.size
        for (i in 0 until n) {
            val u = units[i]
            if (u.alive) updateUnit(u, dt)
        }
        separateUnits(dt)
        for (i in buildings.indices.reversed()) {
            if (i >= buildings.size) continue
            val b = buildings[i]
            if (b.alive) updateBuilding(b, dt)
        }
        updateProjectiles(dt)
        // Remove dead units.
        if (units.any { !it.alive }) {
            units.removeAll { u -> if (!u.alive) { entities.remove(u.id); true } else false }
        }
        recomputePopulation()
        if (territoryDirty) updateTerritory()
        visionTimer -= dt
        if (visionTimer <= 0f || visionDirty) {
            visionTimer = 0.25f
            updateVision()
        }
        checkTimer -= dt
        if (checkTimer <= 0f) {
            checkTimer = 1f
            checkVictory()
        }
    }

    private fun rebuildCells() {
        for (c in cells) c.clear()
        for (u in units) if (u.alive) addToCell(u)
    }

    private fun addToCell(u: GameUnit) {
        val cx = (u.x.toInt() / cellSize).coerceIn(0, cellsW - 1)
        val cy = (u.y.toInt() / cellSize).coerceIn(0, cellsH - 1)
        cells[cx + cy * cellsW].add(u)
    }

    fun recomputePopulation() {
        for (p in players) { p.popUsed = 0; p.popCap = 0 }
        for (u in units) if (u.alive) players[u.owner].popUsed += u.type.pop
        for (b in buildings) {
            if (!b.alive) continue
            val p = players[b.owner]
            if (b.constructed) p.popCap += b.type.pop
            val front = b.queue.firstOrNull()
            if (front is ProdItem.Train && b.queueProgress > 0f) p.popUsed += front.unit.pop
        }
        for (p in players) p.popCap = min(p.popCap, p.popLimit)
    }

    // ---- units

    private fun setIdle(u: GameUnit) {
        u.order = OrderType.IDLE
        u.targetId = -1
        u.attackMove = false
        u.clearPath()
        u.moving = false
    }

    private fun moveTo(u: GameUnit, x: Float, y: Float) {
        u.destX = x; u.destY = y
        u.pathGoalId = -1
        u.pathGoalX = x; u.pathGoalY = y
        u.path = if (pathfinder.lineWalkable(u.x, u.y, x, y)) floatArrayOf(x, y) else pathfinder.findPath(u.x, u.y, x, y)
        u.pathIndex = 0
        u.stuckTimer = 0f
    }

    private fun pathToEntity(u: GameUnit, e: Entity) {
        u.pathGoalId = e.id
        u.pathGoalX = e.x; u.pathGoalY = e.y
        u.stuckTimer = 0f
        u.pathIndex = 0
        when (e) {
            is GameUnit -> {
                u.path = if (pathfinder.lineWalkable(u.x, u.y, e.x, e.y)) floatArrayOf(e.x, e.y) else pathfinder.findPath(u.x, u.y, e.x, e.y)
            }
            is Building -> {
                u.path = if (!e.type.blocksMovement) pathfinder.findPath(u.x, u.y, e.x, e.y)
                else pathfinder.findPathToRect(u.x, u.y, e.tx, e.ty, e.tx + e.type.size - 1, e.ty + e.type.size - 1)
            }
            is ResourceNode -> {
                u.path = pathfinder.findPathToRect(u.x, u.y, e.tx, e.ty, e.tx + e.kind.size - 1, e.ty + e.kind.size - 1)
            }
        }
    }

    /** Moves the unit along its path. Returns true when the path is finished. */
    private fun followPath(u: GameUnit, dt: Float): Boolean {
        if (!u.hasPath) { u.moving = false; return true }
        val speed = players[u.owner].unitSpeed(u.type) * (if (map.terrainAt(u.x.toInt().coerceIn(0, map.width - 1), u.y.toInt().coerceIn(0, map.height - 1)) == Terrain.SHALLOWS) 0.7f else 1f)
        var budget = speed * dt
        u.moving = true
        u.animTime += dt
        val startX = u.x; val startY = u.y
        while (budget > 0f && u.hasPath) {
            val wx = u.path[u.pathIndex * 2]
            val wy = u.path[u.pathIndex * 2 + 1]
            val d = dist(u.x, u.y, wx, wy)
            if (d <= budget) {
                u.x = wx; u.y = wy
                budget -= d
                u.pathIndex++
            } else {
                val nx = u.x + (wx - u.x) / d * budget
                val ny = u.y + (wy - u.y) / d * budget
                if (!map.isWalkableF(nx, ny) && map.isWalkableF(u.x, u.y)) {
                    // Something now blocks the way; recompute the path.
                    u.clearPath()
                    u.repathTimer = 0f
                    u.stuckTimer += 0.5f
                    break
                }
                u.x = nx; u.y = ny
                budget = 0f
            }
        }
        val moved = dist(startX, startY, u.x, u.y)
        if (moved > 0.0001f) u.facing = atan2(u.y - startY, u.x - startX)
        if (moved < speed * dt * 0.2f) u.stuckTimer += dt else u.stuckTimer = max(0f, u.stuckTimer - dt)
        return !u.hasPath
    }

    /** Approach an entity until within [reach]; returns true when in reach. */
    private fun approach(u: GameUnit, e: Entity, reach: Float, dt: Float): Boolean {
        if (e.distanceTo(u.x, u.y) <= reach) {
            u.clearPath()
            u.moving = false
            return true
        }
        u.repathTimer -= dt
        val goalMoved = u.pathGoalId != e.id || dist(u.pathGoalX, u.pathGoalY, e.x, e.y) > 1.5f
        if (!u.hasPath || (goalMoved && u.repathTimer <= 0f)) {
            pathToEntity(u, e)
            u.repathTimer = 0.8f + (u.id % 5) * 0.1f
            if (!u.hasPath) {
                // Already on the closest tile: walk straight towards the nearest point of the target.
                val cx = u.x.coerceIn(e.minX, e.maxX)
                val cy = u.y.coerceIn(e.minY, e.maxY)
                val d = dist(u.x, u.y, cx, cy)
                if (d > 0.001f) {
                    val keep = min(d, reach * 0.5f)
                    u.path = floatArrayOf(cx + (u.x - cx) / d * keep, cy + (u.y - cy) / d * keep)
                    u.pathIndex = 0
                } else {
                    u.stuckTimer += dt * 4
                    return false
                }
            }
        }
        followPath(u, dt)
        return e.distanceTo(u.x, u.y) <= reach
    }

    private fun updateUnit(u: GameUnit, dt: Float) {
        u.attackTimer -= dt
        if (u.attackAnim > 0f) u.attackAnim -= dt
        if (u.order == OrderType.IDLE) u.idleTime += dt else u.idleTime = 0f
        applyAttrition(u, dt)
        if (!u.alive) return
        when (u.order) {
            OrderType.IDLE -> updateIdle(u, dt)
            OrderType.MOVE -> {
                if (!u.hasPath && dist(u.x, u.y, u.destX, u.destY) > 0.5f && u.stuckTimer < 2f) moveTo(u, u.destX, u.destY)
                if (followPath(u, dt) || u.stuckTimer > 3f) setIdle(u)
            }
            OrderType.ATTACK_MOVE -> {
                if ((tick + u.id) % 8 == 0L) {
                    val enemy = findTarget(u, players[u.owner].unitSight(u.type))
                    if (enemy != null) {
                        u.order = OrderType.ATTACK
                        u.targetId = enemy.id
                        u.clearPath()
                        return
                    }
                }
                if (!u.hasPath && dist(u.x, u.y, u.amX, u.amY) > 0.5f && u.stuckTimer < 2f) moveTo(u, u.amX, u.amY)
                if (followPath(u, dt) || u.stuckTimer > 3f) setIdle(u)
            }
            OrderType.ATTACK -> updateAttack(u, dt)
            OrderType.GATHER -> updateGather(u, dt)
            OrderType.RETURN -> updateReturn(u, dt)
            OrderType.BUILD -> updateBuild(u, dt)
            OrderType.HEAL -> updateHeal(u, dt)
        }
    }

    private fun updateIdle(u: GameUnit, dt: Float) {
        u.moving = false
        if ((tick + u.id) % 10 != 0L) return
        when {
            u.type == UnitType.HEALER -> {
                val t = findHealTarget(u, players[u.owner].unitSight(u.type))
                if (t != null) { u.order = OrderType.HEAL; u.targetId = t.id }
            }
            u.type.isMilitary -> {
                val t = findTarget(u, players[u.owner].unitSight(u.type))
                if (t != null) {
                    u.order = OrderType.ATTACK
                    u.targetId = t.id
                    // Return to this spot afterwards.
                    u.attackMove = true
                    u.amX = u.x; u.amY = u.y
                }
            }
        }
    }

    /** Finds the most appropriate enemy near the unit: prefers units, then buildings. */
    fun findTarget(u: GameUnit, radius: Float): Entity? {
        var best: Entity? = null
        var bestScore = Float.MAX_VALUE
        forUnitsNear(u.x, u.y, radius) { o ->
            // Scouts only pick fights with other soldiers on their own; they raid citizens when ordered to.
            if (isEnemy(u.owner, o.owner) && !(u.type == UnitType.SCOUT && o.type == UnitType.VILLAGER)) {
                var d = dist(u.x, u.y, o.x, o.y)
                // Prefer armed targets slightly over villagers.
                if (o.type == UnitType.VILLAGER) d += 1.5f
                if (d < bestScore) { bestScore = d; best = o }
            }
        }
        if (best != null) return best
        if (u.type == UnitType.SCOUT) return null
        for (b in buildings) {
            if (!b.alive || !isEnemy(u.owner, b.owner)) continue
            val d = b.distanceTo(u.x, u.y)
            if (d > radius) continue
            var score = d
            if (b.type == BuildingType.WALL) score += 6f
            if (b.type.attack > 0) score -= 1f
            if (score < bestScore) { bestScore = score; best = b }
        }
        return best
    }

    private fun findHealTarget(u: GameUnit, radius: Float): GameUnit? {
        var best: GameUnit? = null
        var bestD = Float.MAX_VALUE
        forUnitsNear(u.x, u.y, radius) { o ->
            if (o !== u && isAlly(u.owner, o.owner) && o.hp < o.maxHp) {
                val d = dist(u.x, u.y, o.x, o.y)
                if (d < bestD) { bestD = d; best = o }
            }
        }
        return best
    }

    private fun endEngagement(u: GameUnit) {
        // After a kill: look for another target nearby, or resume attack move.
        val next = if (u.type.isMilitary) findTarget(u, players[u.owner].unitSight(u.type)) else null
        if (next != null && (u.attackMove || u.order == OrderType.ATTACK)) {
            u.order = OrderType.ATTACK
            u.targetId = next.id
            u.clearPath()
            return
        }
        if (u.attackMove) {
            u.order = OrderType.ATTACK_MOVE
            u.targetId = -1
            moveTo(u, u.amX, u.amY)
        } else setIdle(u)
    }

    private fun updateAttack(u: GameUnit, dt: Float) {
        val t = get(u.targetId)
        if (t == null || t is ResourceNode || !isEnemy(u.owner, t.owner)) { endEngagement(u); return }
        val p = players[u.owner]
        val range = p.unitRange(u.type) + u.radius + 0.1f
        // Give up chasing units that have run far away during an attack move / auto engagement.
        if (u.attackMove && t is GameUnit && dist(u.x, u.y, u.amX, u.amY) > p.unitSight(u.type) + 6f && t.distanceTo(u.x, u.y) > range + 2f) {
            endEngagement(u); return
        }
        if (approach(u, t, range, dt)) {
            u.moving = false
            u.facing = atan2(t.y - u.y, t.x - u.x)
            if (u.attackTimer <= 0f) {
                u.attackTimer = u.type.cooldown
                u.attackAnim = 0.3f
                performAttack(u, t)
            }
        } else if (u.stuckTimer > 4f) {
            // Unreachable target.
            u.stuckTimer = 0f
            endEngagement(u)
        }
    }

    private fun performAttack(u: GameUnit, t: Entity) {
        val p = players[u.owner]
        val atk = p.unitAttack(u.type)
        cue(if (u.type == UnitType.CATAPULT) SoundCue.SIEGE else if (u.type.ranged) SoundCue.SHOOT else SoundCue.MELEE, u.x, u.y)
        if (u.type.ranged) {
            val kind = when {
                u.type == UnitType.CATAPULT -> Projectile.STONE
                p.age >= Age.GUNPOWDER -> Projectile.BULLET
                else -> Projectile.ARROW
            }
            val speed = when (kind) { Projectile.STONE -> 6f; Projectile.BULLET -> 20f; else -> 10f }
            projectiles += Projectile(
                u.owner, u.id, t.id, u.x, u.y, t.x, t.y, atk, u.type != UnitType.CATAPULT, u.type.splash, speed, kind, u.type.bonus
            )
        } else {
            dealDamage(u.owner, u.id, t, atk, false, u.type.bonus)
        }
    }

    fun computeDamage(attackerOwner: Int, attack: Float, pierce: Boolean, bonus: Map<ArmorClass, Float>, t: Entity): Float {
        val cls = armorClassOf(t)
        val raw = attack * (bonus[cls] ?: 1f)
        val armor = when (t) {
            is GameUnit -> if (pierce) players[t.owner].unitPierceArmor(t.type) else players[t.owner].unitMeleeArmor(t.type)
            is Building -> {
                val age = players[t.owner].age.ordinal
                if (pierce) 6f + age else 1f + age * 0.5f
            }
            else -> 0f
        }
        return max(1f, raw - armor)
    }

    private fun dealDamage(attackerOwner: Int, attackerId: Int, t: Entity, attack: Float, pierce: Boolean, bonus: Map<ArmorClass, Float>) {
        if (!t.alive) return
        val dmg = computeDamage(attackerOwner, attack, pierce, bonus, t)
        t.hp -= dmg
        onDamaged(t, attackerOwner, attackerId)
        if (t.hp <= 0f) kill(t, attackerOwner, attackerId)
    }

    private fun onDamaged(t: Entity, attackerOwner: Int, attackerId: Int) {
        if (t.owner == GAIA) return
        if (time - lastAlert[t.owner] > 20f) {
            lastAlert[t.owner] = time
            val what = if (t is Building) "${t.type.displayName} is under attack!" else "Your units are under attack!"
            emit(GameEvent(EventType.ATTACKED, t.owner, what, t.x, t.y))
        }
        if (t is Building) t.underAttackTimer = 3f
        if (t is GameUnit && t.alive) {
            val attacker = get(attackerId)
            when {
                // Idle or attack-moving soldiers retaliate.
                t.type.isMilitary && t.type != UnitType.HEALER && (t.order == OrderType.IDLE || t.order == OrderType.ATTACK_MOVE) && attacker != null -> {
                    t.attackMove = true
                    if (t.order == OrderType.IDLE) { t.amX = t.x; t.amY = t.y }
                    t.order = OrderType.ATTACK
                    t.targetId = attackerId
                    t.clearPath()
                }
                // Wounded villagers flee towards the nearest drop site.
                t.type == UnitType.VILLAGER && attacker is GameUnit && t.order != OrderType.ATTACK && t.hp < t.maxHp * 0.6f -> {
                    val tc = nearestDropSite(t, null)
                    if (tc != null && tc.distanceTo(t.x, t.y) > 3f) {
                        releaseFarm(t)
                        t.order = OrderType.MOVE
                        moveTo(t, tc.x + (t.x - tc.x).coerceIn(-2.5f, 2.5f), tc.y + (t.y - tc.y).coerceIn(-2.5f, 2.5f))
                    }
                }
            }
        }
    }

    fun kill(e: Entity, killerOwner: Int, killerId: Int = -1) {
        if (e is ResourceNode) { e.hp = 0f; removeEntity(e); return }
        e.hp = 0f
        val victim = players.getOrNull(e.owner)
        val killer = players.getOrNull(killerOwner)
        when (e) {
            is GameUnit -> {
                cue(SoundCue.DEATH, e.x, e.y)
                victim?.unitsLost = (victim?.unitsLost ?: 0) + 1
                if (killer != null && killer.id != e.owner) killer.unitsKilled++
                (get(killerId) as? GameUnit)?.let { it.kills++ }
                // Free a farm being worked. The unit itself is dropped from the lists at the end of the tick.
                releaseFarm(e)
            }
            is Building -> {
                cue(SoundCue.COLLAPSE, e.x, e.y)
                victim?.buildingsLost = (victim?.buildingsLost ?: 0) + 1
                if (killer != null && killer.id != e.owner) killer.buildingsDestroyed++
                // Refund queued items.
                if (victim != null) for (q in e.queue) when (q) {
                    is ProdItem.Train -> victim.refund(q.unit.cost)
                    is ProdItem.Research -> victim.refund(victim.techCost(q.tech))
                    is ProdItem.Advance -> victim.refund(q.age.advanceCost)
                }
                e.queue.clear()
                if (e.type == BuildingType.WONDER && e.wonderTimer >= 0f) {
                    emit(GameEvent(EventType.WONDER, -1, "${victim?.name}'s Wonder has been destroyed!", e.x, e.y))
                }
                if (victim != null && e.constructed && e.type != BuildingType.WALL) {
                    emit(GameEvent(EventType.INFO, e.owner, "${e.type.displayName} destroyed", e.x, e.y))
                }
                removeEntity(e)
            }
            else -> {}
        }
    }

    private fun releaseFarm(u: GameUnit) {
        val f = building(u.gatherNodeId)
        if (f != null && f.farmerId == u.id) f.farmerId = -1
    }

    private fun applyAttrition(u: GameUnit, dt: Float) {
        u.attritionTimer -= dt
        if (u.attritionTimer > 0f) return
        u.attritionTimer = 2f
        if (!u.type.isMilitary) return
        val xi = u.x.toInt(); val yi = u.y.toInt()
        if (!map.inBounds(xi, yi)) return
        val owner = map.territory[map.idx(xi, yi)]
        if (owner < 0 || !isEnemy(u.owner, owner)) return
        if (u.hp <= u.maxHp * 0.25f) return
        val amount = if (players[owner].has(Tech.FAITH)) 3f else 1.5f
        u.hp = max(u.maxHp * 0.25f, u.hp - amount)
    }

    // ---- gathering

    private fun startGather(u: GameUnit, n: ResourceNode) {
        releaseFarm(u)
        u.attackMove = false
        u.order = OrderType.GATHER
        u.targetId = n.id
        u.gatherNodeId = n.id
        u.gatherResource = n.kind.resource
        u.gatherX = n.x; u.gatherY = n.y
        u.clearPath()
    }

    private fun startFarm(u: GameUnit, farm: Building) {
        if (farm.farmerId >= 0 && farm.farmerId != u.id && unit(farm.farmerId)?.let { it.order == OrderType.GATHER || it.order == OrderType.RETURN } == true) {
            // Farm already worked: help nothing, look for another free farm.
            val other = findFreeFarm(u.owner, farm.x, farm.y, 10f, u.id)
            if (other == null) { setIdle(u); return }
            return startFarm(u, other)
        }
        releaseFarm(u)
        farm.farmerId = u.id
        u.attackMove = false
        u.order = OrderType.GATHER
        u.targetId = farm.id
        u.gatherNodeId = farm.id
        u.gatherResource = ResourceType.FOOD
        u.gatherX = farm.x; u.gatherY = farm.y
        u.clearPath()
    }

    fun findFreeFarm(owner: Int, x: Float, y: Float, radius: Float, forUnit: Int = -1): Building? {
        var best: Building? = null
        var bestD = radius
        for (b in buildings) {
            if (!b.alive || b.owner != owner || b.type != BuildingType.FARM || !b.constructed) continue
            if (b.farmerId >= 0 && b.farmerId != forUnit && unit(b.farmerId) != null) continue
            val d = dist(b.x, b.y, x, y)
            if (d < bestD) { bestD = d; best = b }
        }
        return best
    }

    fun findNode(resource: ResourceType, x: Float, y: Float, radius: Float, exclude: Int = -1): ResourceNode? {
        var best: ResourceNode? = null
        var bestD = radius
        for (n in nodes) {
            if (!n.alive || n.id == exclude || n.kind.resource != resource) continue
            val d = dist(n.x, n.y, x, y)
            if (d < bestD) { bestD = d; best = n }
        }
        return best
    }

    private fun gatherRate(u: GameUnit, kind: NodeKind?): Float {
        val base = when (kind) {
            null -> 0.55f // farm
            NodeKind.BERRIES -> 0.7f
            NodeKind.GAME -> 0.85f
            NodeKind.TREE -> 0.6f
            NodeKind.GOLD -> 0.55f
            NodeKind.STONE -> 0.5f
        }
        val r = kind?.resource ?: ResourceType.FOOD
        return base * players[u.owner].gatherMult(r)
    }

    private fun updateGather(u: GameUnit, dt: Float) {
        val p = players[u.owner]
        val target = get(u.targetId)
        val res = u.gatherResource
        if (target == null || res == null) {
            // Node depleted: look for another nearby.
            if (res == ResourceType.FOOD) {
                val farm = findFreeFarm(u.owner, u.gatherX, u.gatherY, 8f, u.id)
                if (farm != null) { startFarm(u, farm); return }
            }
            val next = if (res != null) findNode(res, u.gatherX, u.gatherY, 10f) else null
            if (next != null) { startGather(u, next); return }
            if (u.carryAmount > 0f) { u.order = OrderType.RETURN; u.clearPath() } else setIdle(u)
            return
        }
        if (u.carryType != null && u.carryType != res && u.carryAmount > 0f) {
            // Switched resources: drop old cargo.
            u.carryAmount = 0f
        }
        if (u.carryAmount >= p.carryCapacity) {
            u.order = OrderType.RETURN
            u.clearPath()
            return
        }
        val isFarm = target is Building
        if (isFarm) {
            val farm = target as Building
            if (farm.farmerId != u.id) {
                if (farm.farmerId >= 0 && unit(farm.farmerId) != null) { startFarm(u, farm); return }
                farm.farmerId = u.id
            }
        }
        val reach = if (isFarm) 0.05f else u.radius + 0.55f
        val inReach = if (isFarm) {
            if (target.distanceTo(u.x, u.y) <= reach) true
            else {
                if (!u.hasPath || u.pathGoalId != target.id) {
                    u.pathGoalId = target.id
                    u.path = pathfinder.findPath(u.x, u.y, target.x + ((u.id % 3) - 1) * 0.4f, target.y + ((u.id / 3 % 3) - 1) * 0.4f)
                    u.pathIndex = 0
                }
                followPath(u, dt)
                if (!u.hasPath && target.distanceTo(u.x, u.y) > reach) { u.x = target.x; u.y = target.y }
                target.distanceTo(u.x, u.y) <= reach
            }
        } else approach(u, target, reach, dt)
        if (!inReach) {
            if (u.stuckTimer > 5f) {
                u.stuckTimer = 0f
                val alt = findNode(res, u.x, u.y, 12f, target.id)
                if (alt != null) startGather(u, alt) else setIdle(u)
            }
            return
        }
        u.moving = false
        u.animTime += dt
        u.attackAnim = 0.2f
        if (!isFarm) u.facing = atan2(target.y - u.y, target.x - u.x)
        u.carryType = res
        val amount = gatherRate(u, (target as? ResourceNode)?.kind) * dt
        if (target is ResourceNode) {
            val take = min(amount, target.amount)
            target.amount -= take
            u.carryAmount += take
            if (target.amount <= 0f) {
                removeEntity(target.also { it.hp = 0f })
            }
        } else {
            u.carryAmount += amount
        }
    }

    fun nearestDropSite(u: GameUnit, r: ResourceType?): Building? {
        var best: Building? = null
        var bestD = Float.MAX_VALUE
        for (b in buildings) {
            if (!b.alive || b.owner != u.owner || !b.constructed) continue
            if (r == null) { if (!b.type.isDropSite) continue } else if (r !in b.type.dropOff) continue
            val d = b.distanceTo(u.x, u.y)
            if (d < bestD) { bestD = d; best = b }
        }
        return best
    }

    private fun updateReturn(u: GameUnit, dt: Float) {
        val r = u.carryType
        if (r == null || u.carryAmount <= 0f) { resumeGather(u); return }
        val drop = nearestDropSite(u, r)
        if (drop == null) { setIdle(u); return }
        if (approach(u, drop, u.radius + 0.55f, dt)) {
            players[u.owner].add(r, u.carryAmount)
            u.carryAmount = 0f
            resumeGather(u)
        } else if (u.stuckTimer > 6f) {
            u.stuckTimer = 0f
            setIdle(u)
        }
    }

    private fun resumeGather(u: GameUnit) {
        val t = get(u.gatherNodeId)
        when {
            t is ResourceNode -> startGather(u, t)
            t is Building && t.type == BuildingType.FARM -> startFarm(u, t)
            u.gatherResource != null -> {
                u.order = OrderType.GATHER
                u.targetId = -1 // triggers search for a replacement
            }
            else -> setIdle(u)
        }
    }

    // ---- building

    private fun updateBuild(u: GameUnit, dt: Float) {
        val b = building(u.targetId)
        if (b == null || b.owner != u.owner || (b.constructed && b.hp >= b.maxHp)) { afterBuild(u, b); return }
        if (!approach(u, b, u.radius + 0.55f, dt)) {
            if (u.stuckTimer > 6f) { u.stuckTimer = 0f; setIdle(u) }
            return
        }
        u.moving = false
        u.animTime += dt
        u.attackAnim = 0.2f
        u.facing = atan2(b.y - u.y, b.x - u.x)
        val p = players[u.owner]
        if (!b.constructed) {
            val dp = dt / b.type.buildTime * p.buildSpeed
            b.progress = min(1f, b.progress + dp)
            b.hp = min(b.maxHp, b.hp + b.maxHp * dp)
            if (b.progress >= 1f) completeBuilding(b)
        } else {
            // Repair at a cost of wood proportional to the building's cost.
            val dhp = b.maxHp / b.type.buildTime * dt * 0.75f
            val woodCost = dhp / b.maxHp * (b.type.cost.wood + b.type.cost.stone) * 0.25f
            if (p.stock[ResourceType.WOOD.ordinal] >= woodCost) {
                p.stock[ResourceType.WOOD.ordinal] -= woodCost
                b.hp = min(b.maxHp, b.hp + dhp)
            } else setIdle(u)
        }
    }

    private fun completeBuilding(b: Building) {
        b.constructed = true
        b.progress = 1f
        b.hp = max(b.hp, 1f)
        territoryDirty = true
        visionDirty = true
        val p = players[b.owner]
        cue(SoundCue.BUILD, b.x, b.y)
        if (b.type != BuildingType.WALL && b.type != BuildingType.FARM) {
            emit(GameEvent(EventType.BUILT, b.owner, "${b.type.displayName} completed", b.x, b.y))
        }
        if (b.type == BuildingType.WONDER && settings.wonderVictory) {
            b.wonderTimer = WONDER_TIME
            emit(GameEvent(EventType.WONDER, -1, "${p.name} has completed a Wonder! Destroy it within 5 minutes!", b.x, b.y))
        }
    }

    private fun afterBuild(u: GameUnit, b: Building?) {
        // Help other nearby foundations first.
        val other = buildings.filter { it.alive && it.owner == u.owner && !it.constructed && dist(it.x, it.y, u.x, u.y) < 12f }
            .minByOrNull { dist(it.x, it.y, u.x, u.y) }
        if (other != null) { u.targetId = other.id; u.clearPath(); return }
        if (b != null && b.constructed) {
            when (b.type) {
                BuildingType.FARM -> if (b.farmerId < 0) { startFarm(u, b); return }
                BuildingType.LUMBER_CAMP -> findNode(ResourceType.WOOD, b.x, b.y, 12f)?.let { startGather(u, it); return }
                BuildingType.MINING_CAMP -> {
                    val gold = findNode(ResourceType.GOLD, b.x, b.y, 10f)
                    val stone = findNode(ResourceType.STONE, b.x, b.y, 10f)
                    val pick = listOfNotNull(gold, stone).minByOrNull { dist(it.x, it.y, b.x, b.y) }
                    if (pick != null) { startGather(u, pick); return }
                }
                BuildingType.MILL -> {
                    val farm = findFreeFarm(u.owner, b.x, b.y, 10f, u.id)
                    if (farm != null) { startFarm(u, farm); return }
                    findNode(ResourceType.FOOD, b.x, b.y, 10f)?.let { startGather(u, it); return }
                }
                else -> {}
            }
        }
        setIdle(u)
    }

    // ---- healing

    private fun updateHeal(u: GameUnit, dt: Float) {
        val t = unit(u.targetId)
        if (t == null || t.hp >= t.maxHp || !isAlly(u.owner, t.owner)) {
            val next = findHealTarget(u, players[u.owner].unitSight(u.type))
            if (next != null) { u.targetId = next.id; u.clearPath() } else setIdle(u)
            return
        }
        if (approach(u, t, u.type.range, dt)) {
            u.moving = false
            u.attackAnim = 0.2f
            t.hp = min(t.maxHp, t.hp + players[u.owner].healRate * dt)
        }
    }

    // ---- separation

    private fun separateUnits(dt: Float) {
        for (u in units) {
            if (!u.alive) continue
            // Workers at their job and units fighting hold their ground more firmly.
            forUnitsNear(u.x, u.y, 0.9f) { o ->
                if (o.id > u.id) {
                    val minD = u.radius + o.radius
                    val dx = o.x - u.x
                    val dy = o.y - u.y
                    val d2 = dx * dx + dy * dy
                    if (d2 < minD * minD) {
                        val d = sqrt(d2).coerceAtLeast(0.001f)
                        val push = (minD - d) * 0.5f * min(1f, dt * 8f)
                        var nx = dx / d
                        var ny = dy / d
                        if (d2 < 1e-6f) { val a = (u.id * 2.39996f); nx = cos(a); ny = sin(a) }
                        val uMove = if (u.moving || u.order == OrderType.IDLE) 1f else 0.3f
                        val oMove = if (o.moving || o.order == OrderType.IDLE) 1f else 0.3f
                        val ux = u.x - nx * push * uMove
                        val uy = u.y - ny * push * uMove
                        if (map.isWalkableF(ux, uy)) { u.x = ux; u.y = uy }
                        val ox = o.x + nx * push * oMove
                        val oy = o.y + ny * push * oMove
                        if (map.isWalkableF(ox, oy)) { o.x = ox; o.y = oy }
                    }
                }
            }
            u.x = u.x.coerceIn(0.05f, map.width - 0.05f)
            u.y = u.y.coerceIn(0.05f, map.height - 0.05f)
        }
    }

    // ---- buildings

    private fun updateBuilding(b: Building, dt: Float) {
        if (b.underAttackTimer > 0f) b.underAttackTimer -= dt
        if (!b.constructed) return
        val p = players[b.owner]
        // Production queue.
        val front = b.queue.firstOrNull()
        if (front != null) {
            var canProgress = true
            if (front is ProdItem.Train && b.queueProgress == 0f) {
                if (p.popUsed + front.unit.pop > p.popCap) {
                    canProgress = false
                    if (p.isHuman && time - lastHousingAlert[p.id] > 15f) {
                        lastHousingAlert[p.id] = time
                        emit(GameEvent(EventType.WARNING, p.id, if (p.popCap >= p.popLimit) "Population limit reached" else "Need more houses!"))
                    }
                }
            }
            if (canProgress) {
                b.queueProgress += dt / front.time
                if (b.queueProgress >= 1f) {
                    b.queue.removeAt(0)
                    b.queueProgress = 0f
                    finishProduction(b, front)
                }
            }
        }
        // Defensive fire.
        if (b.type.attack > 0) {
            b.attackTimer -= dt
            if (b.attackTimer <= 0f) {
                val range = b.type.range
                var target: GameUnit? = null
                var bestD = Float.MAX_VALUE
                forUnitsNear(b.x, b.y, range + b.type.size / 2f + 0.5f) { o ->
                    if (isEnemy(b.owner, o.owner)) {
                        val d = b.distanceTo(o.x, o.y)
                        if (d <= range && d < bestD) { bestD = d; target = o }
                    }
                }
                val t = target
                if (t != null) {
                    b.attackTimer = when (b.type) { BuildingType.FORTRESS -> 1.2f; BuildingType.TOWER -> 1.8f; else -> 2f }
                    val kind = if (p.age >= Age.GUNPOWDER) Projectile.BULLET else Projectile.ARROW
                    projectiles += Projectile(
                        b.owner, b.id, t.id, b.x, b.y, t.x, t.y, p.buildingAttack(b.type), true, 0f,
                        if (kind == Projectile.BULLET) 20f else 10f, kind, emptyMap()
                    )
                } else b.attackTimer = 0.25f
            }
        }
        // Economy trickles.
        if (b.type == BuildingType.MARKET) {
            p.add(ResourceType.GOLD, 0.25f * p.tradeIncomeMult * dt)
        }
        if (b.type == BuildingType.TOWN_CENTER && p.has(Tech.TAXATION)) {
            p.add(ResourceType.GOLD, 0.35f * dt)
        }
        if (b.type == BuildingType.WONDER && b.wonderTimer > 0f) {
            b.wonderTimer -= dt
            if (b.wonderTimer <= 0f) {
                b.wonderTimer = 0f
                endGame(p.team, "${p.name} wins with a Wonder victory!")
            }
        }
    }

    private fun finishProduction(b: Building, item: ProdItem) {
        val p = players[b.owner]
        when (item) {
            is ProdItem.Train -> {
                val (sx, sy) = spawnPoint(b)
                val u = spawnUnit(b.owner, item.unit, sx, sy)
                if (b.hasRally) {
                    val rx = b.rallyX; val ry = b.rallyY
                    val target = entityAtTile(rx.toInt(), ry.toInt())
                    if (u.type == UnitType.VILLAGER && target is ResourceNode) startGather(u, target)
                    else if (u.type == UnitType.VILLAGER && target is Building && target.owner == u.owner && target.type == BuildingType.FARM) startFarm(u, target)
                    else if (u.type == UnitType.VILLAGER && target is Building && target.owner == u.owner && !target.constructed) commandBuild(listOf(u.id), target.id)
                    else commandMove(listOf(u.id), rx, ry)
                }
                if (p.isHuman && u.type != UnitType.VILLAGER) emit(GameEvent(EventType.TRAINED, p.id, "${p.unitName(u.type)} ready", u.x, u.y))
            }
            is ProdItem.Research -> {
                p.techs += item.tech
                p.techCount++
                applyTech(p, item.tech)
                emit(GameEvent(EventType.RESEARCHED, p.id, "${item.tech.displayName} researched", b.x, b.y))
            }
            is ProdItem.Advance -> {
                p.age = item.age
                p.techCount++
                refreshPlayerStats(p)
                emit(GameEvent(EventType.AGE_UP, p.id, "${p.name} advanced to the ${item.age.displayName}", b.x, b.y))
            }
        }
    }

    private fun applyTech(p: Player, t: Tech) {
        when (t) {
            Tech.CIVICS -> territoryDirty = true
            Tech.CARTOGRAPHY -> visionDirty = true
            else -> {}
        }
        refreshPlayerStats(p)
    }

    /** Recalculates max HP of all of a player's entities, preserving their health ratio. */
    fun refreshPlayerStats(p: Player) {
        for (u in units) if (u.alive && u.owner == p.id) {
            val ratio = u.hp / u.maxHp
            u.maxHp = p.unitMaxHp(u.type)
            u.hp = u.maxHp * ratio
        }
        for (b in buildings) if (b.alive && b.owner == p.id) {
            val ratio = b.hp / b.maxHp
            b.maxHp = p.buildingMaxHp(b.type)
            b.hp = max(1f, b.maxHp * ratio)
        }
    }

    private fun spawnPoint(b: Building): Pair<Float, Float> {
        val tx = if (b.hasRally) b.rallyX else b.x + b.type.size
        val ty = if (b.hasRally) b.rallyY else b.y + b.type.size
        var best: Pair<Float, Float>? = null
        var bestD = Float.MAX_VALUE
        for (r in 0..4) {
            for (dy in -1 - r..b.type.size + r) for (dx in -1 - r..b.type.size + r) {
                val onEdge = dx == -1 - r || dy == -1 - r || dx == b.type.size + r || dy == b.type.size + r
                if (!onEdge) continue
                val x = b.tx + dx; val y = b.ty + dy
                if (!map.isWalkable(x, y)) continue
                val d = dist(x + 0.5f, y + 0.5f, tx, ty)
                if (d < bestD) { bestD = d; best = Pair(x + 0.5f, y + 0.5f) }
            }
            if (best != null) return best!!
        }
        return Pair(b.x, b.maxY + 0.5f)
    }

    // ---- projectiles

    private fun updateProjectiles(dt: Float) {
        for (pr in projectiles) {
            if (pr.done) continue
            val t = get(pr.targetId)
            if (t is GameUnit) { pr.tx = t.x; pr.ty = t.y }
            val d = dist(pr.x, pr.y, pr.tx, pr.ty)
            val step = pr.speed * dt
            if (d <= step) {
                pr.x = pr.tx; pr.y = pr.ty
                pr.done = true
                if (t != null && t.distanceTo(pr.tx, pr.ty) < 0.8f) dealDamage(pr.owner, pr.sourceId, t, pr.damage, pr.pierce, pr.bonus)
                if (pr.splash > 0f) {
                    forUnitsNear(pr.tx, pr.ty, pr.splash) { o ->
                        if (o.id != pr.targetId && isEnemy(pr.owner, o.owner)) dealDamage(pr.owner, pr.sourceId, o, pr.damage * 0.5f, false, pr.bonus)
                    }
                }
            } else {
                pr.x += (pr.tx - pr.x) / d * step
                pr.y += (pr.ty - pr.y) / d * step
            }
        }
        projectiles.removeAll { it.done }
    }

    // ---- territory & vision

    fun updateTerritory() {
        territoryDirty = false
        territoryVersion++
        val strength = FloatArray(map.width * map.height)
        map.territory.fill(-1)
        for (b in buildings) {
            if (!b.alive || !b.constructed || b.type.territory <= 0f) continue
            val r = b.type.territory + players[b.owner].territoryBonus
            val x0 = max(0, (b.x - r).toInt()); val x1 = min(map.width - 1, (b.x + r).toInt())
            val y0 = max(0, (b.y - r).toInt()); val y1 = min(map.height - 1, (b.y + r).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val d = dist(x + 0.5f, y + 0.5f, b.x, b.y)
                if (d > r) continue
                val s = r - d + (if (b.type == BuildingType.TOWN_CENTER) 2f else 0f)
                val i = map.idx(x, y)
                if (s > strength[i]) { strength[i] = s; map.territory[i] = b.owner }
            }
        }
    }

    fun updateVision() {
        visionDirty = false
        for (p in players) {
            if (!p.isHuman) continue
            val vis = p.visible
            vis.fill(0)
            for (u in units) if (u.alive && isAlly(p.id, u.owner)) stamp(vis, u.x, u.y, players[u.owner].unitSight(u.type))
            for (b in buildings) if (b.alive && isAlly(p.id, b.owner)) stamp(vis, b.x, b.y, b.type.sight + b.type.size / 2f)
            val ex = p.explored
            for (i in vis.indices) if (vis[i] > 0) ex[i] = true
        }
    }

    private fun stamp(vis: ByteArray, cx: Float, cy: Float, r: Float) {
        val r2 = r * r
        val x0 = max(0, (cx - r).toInt()); val x1 = min(map.width - 1, (cx + r).toInt())
        val y0 = max(0, (cy - r).toInt()); val y1 = min(map.height - 1, (cy + r).toInt())
        for (y in y0..y1) {
            val dy = y + 0.5f - cy
            val row = y * map.width
            for (x in x0..x1) {
                val dx = x + 0.5f - cx
                if (dx * dx + dy * dy <= r2) vis[row + x] = 1
            }
        }
    }

    // ---- victory

    private fun checkVictory() {
        for (p in players) {
            if (p.defeated) continue
            val hasTc = buildings.any { it.alive && it.owner == p.id && it.type == BuildingType.TOWN_CENTER }
            val hasVillager = units.any { it.alive && it.owner == p.id && it.type == UnitType.VILLAGER }
            if (!hasTc && !hasVillager) defeat(p)
        }
        val teams = players.filter { !it.defeated }.map { it.team }.toSet()
        if (teams.size <= 1 && !gameOver) {
            val team = teams.firstOrNull() ?: -1
            val names = players.filter { it.team == team }.joinToString(" & ") { it.name }
            endGame(team, if (team >= 0) "$names conquered the world!" else "Everyone has fallen.")
        }
    }

    private fun defeat(p: Player) {
        if (p.defeated) return
        p.defeated = true
        emit(GameEvent(EventType.DEFEATED, p.id, "${p.name} has been defeated"))
        for (u in units) if (u.alive && u.owner == p.id) { u.hp = 0f }
        for (b in buildings.toList()) if (b.alive && b.owner == p.id) { b.hp = 0f; removeEntity(b) }
    }

    private fun endGame(team: Int, message: String) {
        gameOver = true
        winnerTeam = team
        emit(GameEvent(EventType.VICTORY, -1, message))
    }

    fun score(p: Player): Int {
        val eco = p.gathered.sum() / 10f
        val mil = p.unitsKilled * 10f + p.buildingsDestroyed * 30f
        val tech = p.techCount * 25f + p.age.ordinal * 150f
        return (eco + mil + tech).toInt()
    }

    companion object {
        const val WONDER_TIME = 300f
        const val TICK = 0.05f
    }
}
