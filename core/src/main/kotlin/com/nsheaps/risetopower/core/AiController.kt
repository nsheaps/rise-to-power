package com.nsheaps.risetopower.core

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Computer opponent. Runs a simple economy / build order / army state machine a few times per
 * second. Difficulty changes reaction speed, gather bonus and how aggressive the AI is.
 */
class AiController(private val world: World, val playerId: Int) {
    private val p get() = world.players[playerId]
    private val diff get() = p.difficulty
    private var timer = (playerId * 0.17f) % 1f
    private var attacking = false
    private var attackStartSize = 0
    private var waves = 0
    private var nextAttackTime = diff.firstAttackTime
    private var lastBuildTime = -100f
    private var scoutDone = false

    // Snapshot lists for the current think.
    private val villagers = ArrayList<GameUnit>()
    private val army = ArrayList<GameUnit>()
    private val mine = ArrayList<Building>()
    private val nodeLoad = HashMap<Int, Int>()

    fun update(dt: Float) {
        timer -= dt
        if (timer > 0f) return
        timer = diff.thinkInterval
        if (p.defeated || world.gameOver) return
        snapshot()
        val home = mine.firstOrNull { it.type == BuildingType.TOWN_CENTER } ?: mine.firstOrNull()
        if (home == null) {
            // No buildings at all: try to rebuild a town center.
            villagers.firstOrNull()?.let { v -> tryBuild(BuildingType.TOWN_CENTER, v.x, v.y, 2, 16, listOf(v)) }
            return
        }
        val defending = defend(home)
        economy(home)
        buildings(home, defending)
        research(defending)
        military(home, defending)
        trade()
        if (!defending) offense(home)
        scout(home)
    }

    private fun snapshot() {
        villagers.clear(); army.clear(); mine.clear(); nodeLoad.clear()
        for (u in world.units) {
            if (!u.alive || u.owner != playerId) continue
            if (u.type == UnitType.VILLAGER) {
                villagers += u
                if (u.order == OrderType.GATHER || u.order == OrderType.RETURN) nodeLoad.merge(u.gatherNodeId, 1, Int::plus)
            } else if (u.type != UnitType.SCOUT) army += u
        }
        for (b in world.buildings) if (b.alive && b.owner == playerId) mine += b
    }

    // ------------------------------------------------------------------ economy

    private fun targetVillagers() = min(70, 20 + p.age.ordinal * 12)

    private fun saving(): Boolean {
        val next = p.age.next ?: return false
        if (world.isAdvancing(playerId)) return false
        val req = when (p.age) { Age.ANCIENT -> 13; Age.CLASSICAL -> 22; Age.MEDIEVAL -> 30; else -> 36 }
        return villagers.size >= req && world.time > 120f * (p.age.ordinal + 1) * (if (diff == Difficulty.EASY) 1.6f else 1f) && next.advanceCost.food > 0
    }

    /** Resources set aside for the next age while saving. */
    private fun reserve(): Cost = if (saving()) p.age.next!!.advanceCost else Cost.NONE

    private fun affordWithReserve(c: Cost, extra: Float = 0f): Boolean {
        val r = reserve()
        return ResourceType.entries.all { p[it] >= c[it] + r[it] + (if (c[it] > 0) extra else 0f) }
    }

    private fun economy(home: Building) {
        val tcs = mine.filter { it.type == BuildingType.TOWN_CENTER && it.constructed }
        // Villager production.
        if (villagers.size < targetVillagers()) {
            for (tc in tcs) if (tc.queue.size < 2 && tc.queue.none { it is ProdItem.Advance }) world.queueTrain(tc.id, UnitType.VILLAGER)
        }
        // Age advancement.
        if (saving()) {
            val next = p.age.next!!
            if (p.canAfford(next.advanceCost)) tcs.firstOrNull()?.let { world.queueAdvance(it.id) }
        }
        // Put idle villagers to work.
        val counts = IntArray(4)
        for (v in villagers) v.gatherResource?.let { if (v.order == OrderType.GATHER || v.order == OrderType.RETURN) counts[it.ordinal]++ }
        for (v in villagers) {
            if (v.order != OrderType.IDLE) continue
            val r = pickResource(counts)
            if (assign(v, r, home)) counts[r.ordinal]++
            else if (r != ResourceType.WOOD && assign(v, ResourceType.WOOD, home)) counts[ResourceType.WOOD.ordinal]++
        }
        // Rebalance: if food is starved and wood is plentiful, move a woodcutter to food.
        if (world.tick % 200L == (playerId * 13L) % 200L) {
            val want = pickResource(counts)
            val donor = ResourceType.entries.maxByOrNull { p[it] - weights()[it.ordinal] * 1000f }
            if (donor != null && donor != want && p[donor] > 600f) {
                villagers.firstOrNull { it.gatherResource == donor && it.order == OrderType.GATHER && it.carryAmount < 2f }?.let { assign(it, want, home) }
            }
        }
    }

    private fun weights(): FloatArray {
        val w = when (p.age) {
            Age.ANCIENT -> floatArrayOf(0.5f, 0.38f, 0.07f, 0.05f)
            Age.CLASSICAL -> floatArrayOf(0.42f, 0.33f, 0.17f, 0.08f)
            Age.MEDIEVAL -> floatArrayOf(0.38f, 0.30f, 0.22f, 0.10f)
            else -> floatArrayOf(0.36f, 0.28f, 0.26f, 0.10f)
        }
        if (saving()) {
            val c = p.age.next!!.advanceCost
            for (r in ResourceType.entries) if (p[r] < c[r]) w[r.ordinal] += 0.1f
        }
        return w
    }

    private fun pickResource(counts: IntArray): ResourceType {
        val w = weights()
        val total = max(1, counts.sum() + 1)
        var best = ResourceType.FOOD
        var bestDeficit = -Float.MAX_VALUE
        for (r in ResourceType.entries) {
            val deficit = w[r.ordinal] - counts[r.ordinal].toFloat() / total - p[r] / 4000f
            if (deficit > bestDeficit) { bestDeficit = deficit; best = r }
        }
        return best
    }

    private fun dropSites(r: ResourceType) = mine.filter { it.constructed && r in it.type.dropOff }

    private fun assign(v: GameUnit, r: ResourceType, home: Building): Boolean {
        if (r == ResourceType.FOOD) {
            val farm = world.findFreeFarm(playerId, home.x, home.y, 40f)
            if (farm != null) { world.commandGather(listOf(v.id), farm.id); return true }
        }
        val drops = dropSites(r)
        val maxLoad = when (r) { ResourceType.WOOD -> 2; ResourceType.FOOD -> 3; else -> 5 }
        var best: ResourceNode? = null
        var bestScore = Float.MAX_VALUE
        var bestDrop = Float.MAX_VALUE
        for (n in world.nodes) {
            if (!n.alive || n.kind.resource != r) continue
            if ((nodeLoad[n.id] ?: 0) >= maxLoad) continue
            if (n.owner != GAIA) continue
            val terr = world.map.territory[world.map.idx(n.tx, n.ty)]
            if (terr >= 0 && world.isEnemy(playerId, terr)) continue
            var dd = Float.MAX_VALUE
            for (d in drops) dd = min(dd, d.distanceTo(n.x, n.y))
            if (drops.isEmpty()) dd = dist(home.x, home.y, n.x, n.y)
            if (dd > 40f) continue
            val score = dd * 2f + dist(v.x, v.y, n.x, n.y) * 0.5f
            if (score < bestScore) { bestScore = score; best = n; bestDrop = dd }
        }
        val node = best
        // Far from a drop site: build one next to the resource.
        if (node != null && bestDrop > 7f && r != ResourceType.FOOD) {
            val camp = if (r == ResourceType.WOOD) BuildingType.LUMBER_CAMP else BuildingType.MINING_CAMP
            if (pendingCount(camp) == 0 && p.canAfford(camp.cost)) {
                if (tryBuild(camp, node.x, node.y, 2, 6, listOf(v))) return true
            }
        }
        if (node != null && (r != ResourceType.FOOD || bestDrop < 14f)) {
            if (r == ResourceType.FOOD && bestDrop > 6f && pendingCount(BuildingType.MILL) == 0 && world.countBuildings(playerId, BuildingType.MILL) < 2 && p.canAfford(BuildingType.MILL.cost)) {
                if (tryBuild(BuildingType.MILL, node.x, node.y, 2, 5, listOf(v))) return true
            }
            world.commandGather(listOf(v.id), node.id)
            return true
        }
        if (r == ResourceType.FOOD && p.canAfford(BuildingType.FARM.cost) && pendingCount(BuildingType.FARM) < 2) {
            val near = mine.filter { it.constructed && ResourceType.FOOD in it.type.dropOff }.minByOrNull { dist(it.x, it.y, v.x, v.y) } ?: home
            if (tryBuild(BuildingType.FARM, near.x, near.y, 2, 9, listOf(v), margin = 0)) return true
        }
        return false
    }

    private fun pendingCount(type: BuildingType) = mine.count { it.type == type && !it.constructed }

    // ------------------------------------------------------------------ construction

    private fun buildings(home: Building, defending: Boolean) {
        // Make sure all foundations have builders.
        for (b in mine) {
            if (b.constructed) continue
            val builders = villagers.count { it.order == OrderType.BUILD && it.targetId == b.id }
            if (builders == 0) {
                val v = villagers.filter { it.order != OrderType.BUILD }.minByOrNull { dist(it.x, it.y, b.x, b.y) }
                if (v != null) world.commandBuild(listOf(v.id), b.id)
            }
        }
        // Repair damaged buildings when not under attack.
        if (!defending) for (b in mine) {
            if (b.constructed && b.hp < b.maxHp * 0.6f && b.underAttackTimer <= 0f && villagers.none { it.order == OrderType.BUILD && it.targetId == b.id }) {
                villagers.filter { it.gatherResource == ResourceType.WOOD }.minByOrNull { dist(it.x, it.y, b.x, b.y) }?.let { world.commandBuild(listOf(it.id), b.id) }
                break
            }
        }
        // Houses.
        val housingNeeded = p.popCap < p.popLimit && p.popCap - p.popUsed < 4 + p.age.ordinal * 2
        val maxPendingHouses = if (p.popCap > 60) 2 else 1
        if (housingNeeded && pendingCount(BuildingType.HOUSE) < maxPendingHouses) {
            if (tryBuild(BuildingType.HOUSE, home.x, home.y, 4, 14, builders(1, home))) return
        }
        if (world.time - lastBuildTime < 6f) return
        val pending = mine.count { !it.constructed && it.type != BuildingType.FARM && it.type != BuildingType.WALL }
        if (pending >= 2) return
        val next = nextBuilding() ?: return
        if (if (defending) !p.canAfford(next.cost) else !affordWithReserve(next.cost)) return
        val near = if (next == BuildingType.TOWN_CENTER) expansionSite(home) else Pair(home.x, home.y)
        if (near == null) return
        val (minR, maxR) = when (next) {
            BuildingType.TOWN_CENTER -> Pair(0, 6)
            BuildingType.TOWER -> Pair(8, 13)
            else -> Pair(5, 16)
        }
        tryBuild(next, near.first, near.second, minR, maxR, builders(if (next == BuildingType.TOWN_CENTER || next == BuildingType.WONDER) 4 else 2, home))
    }

    private fun nextBuilding(): BuildingType? {
        fun has(t: BuildingType, n: Int = 1) = world.countBuildings(playerId, t) >= n
        val vCount = villagers.size
        val plan = ArrayList<BuildingType>()
        if (vCount >= 8 && !has(BuildingType.BARRACKS)) plan += BuildingType.BARRACKS
        if (vCount >= 12 && !has(BuildingType.ARCHERY_RANGE)) plan += BuildingType.ARCHERY_RANGE
        if (vCount >= 14 && !has(BuildingType.LIBRARY)) plan += BuildingType.LIBRARY
        if (p.age >= Age.CLASSICAL) {
            if (!has(BuildingType.STABLE)) plan += BuildingType.STABLE
            if (!has(BuildingType.BLACKSMITH)) plan += BuildingType.BLACKSMITH
            if (!has(BuildingType.MARKET)) plan += BuildingType.MARKET
            if (vCount >= 24 && world.countBuildings(playerId, BuildingType.TOWN_CENTER) < 2) plan += BuildingType.TOWN_CENTER
            if (!has(BuildingType.SIEGE_WORKSHOP) && vCount >= 20) plan += BuildingType.SIEGE_WORKSHOP
            if (!has(BuildingType.TEMPLE) && vCount >= 22) plan += BuildingType.TEMPLE
            if (!has(BuildingType.BARRACKS, 2) && vCount >= 25) plan += BuildingType.BARRACKS
            if (!has(BuildingType.TOWER, 2) && diff >= Difficulty.NORMAL) plan += BuildingType.TOWER
        }
        if (p.age >= Age.MEDIEVAL) {
            if (!has(BuildingType.FORTRESS) && diff >= Difficulty.NORMAL) plan += BuildingType.FORTRESS
            if (!has(BuildingType.STABLE, 2)) plan += BuildingType.STABLE
            if (world.countBuildings(playerId, BuildingType.TOWN_CENTER) < 3 && vCount >= 40) plan += BuildingType.TOWN_CENTER
            if (!has(BuildingType.ARCHERY_RANGE, 2)) plan += BuildingType.ARCHERY_RANGE
        }
        if (p.age >= Age.INDUSTRIAL && diff >= Difficulty.HARD && !has(BuildingType.WONDER) && world.settings.wonderVictory) plan += BuildingType.WONDER
        return plan.firstOrNull { p.age >= it.minAge }
    }

    private fun expansionSite(home: Building): Pair<Float, Float>? {
        // Look for a resource rich area inside or next to our borders, away from other cities.
        var best: Pair<Float, Float>? = null
        var bestScore = -Float.MAX_VALUE
        for (n in world.nodes) {
            if (n.kind != NodeKind.GOLD && n.kind != NodeKind.STONE) continue
            val d = dist(n.x, n.y, home.x, home.y)
            if (d < 14f || d > 30f) continue
            if (world.buildings.any { it.type == BuildingType.TOWN_CENTER && dist(it.x, it.y, n.x, n.y) < 15f }) continue
            val terr = world.map.territory[world.map.idx(n.tx, n.ty)]
            if (terr >= 0 && terr != playerId) continue
            val score = -d
            if (score > bestScore) { bestScore = score; best = Pair(n.x, n.y) }
        }
        return best
    }

    private fun builders(count: Int, home: Building): List<GameUnit> {
        val pool = villagers.filter { it.order != OrderType.BUILD && it.carryAmount < 4f }
            .sortedBy { (if (it.gatherResource == ResourceType.WOOD) 0f else 10f) + dist(it.x, it.y, home.x, home.y) * 0.1f }
        return pool.take(count)
    }

    private fun tryBuild(type: BuildingType, cx: Float, cy: Float, minR: Int, maxR: Int, builders: List<GameUnit>, margin: Int = 1): Boolean {
        if (builders.isEmpty()) return false
        val spot = findSpot(type, cx, cy, minR, maxR, margin) ?: return false
        val b = world.placeFoundation(playerId, type, spot.first, spot.second, builders.map { it.id })
        if (b != null) {
            lastBuildTime = world.time
            mine += b
        }
        return b != null
    }

    fun findSpot(type: BuildingType, cx: Float, cy: Float, minR: Int, maxR: Int, margin: Int = 1): Pair<Int, Int>? {
        val rng = world.rng
        val s = type.size
        for (r in minR..maxR) {
            val tries = 6 + r * 2
            val start = rng.nextFloat() * 6.283f
            for (k in 0 until tries) {
                val a = start + k * 6.283f / tries
                val tx = (cx + cos(a) * r - s / 2f).toInt()
                val ty = (cy + sin(a) * r - s / 2f).toInt()
                if (world.placementError(playerId, type, tx, ty, checkCost = false) != null) continue
                if (margin > 0 && !marginClear(tx, ty, s, margin)) continue
                return Pair(tx, ty)
            }
        }
        return null
    }

    /** Keeps a walkable ring around new buildings so the base doesn't wall itself in. */
    private fun marginClear(tx: Int, ty: Int, s: Int, m: Int): Boolean {
        val map = world.map
        for (y in ty - m until ty + s + m) for (x in tx - m until tx + s + m) {
            if (x >= tx && x < tx + s && y >= ty && y < ty + s) continue
            if (!map.inBounds(x, y)) return false
            val occ = map.occupant[map.idx(x, y)]
            if (occ >= 0 && world.get(occ) is Building) return false
            if (occ >= 0 && world.get(occ) is ResourceNode && (world.get(occ) as ResourceNode).kind != NodeKind.TREE) return false
        }
        return true
    }

    // ------------------------------------------------------------------ research

    private fun research(defending: Boolean) {
        for (b in mine) {
            if (!b.constructed || b.queue.isNotEmpty()) continue
            for (t in b.type.researches()) {
                if (!world.canResearch(p, t)) continue
                if (affordWithReserve(p.techCost(t), if (defending) 300f else 150f)) {
                    world.queueResearch(b.id, t)
                    break
                }
            }
        }
    }

    // ------------------------------------------------------------------ army

    private fun desiredArmy(): Int {
        val base = 6 + p.age.ordinal * 8 + (world.time / 60f).toInt()
        return min(base, p.popLimit / 2)
    }

    private fun military(home: Building, defending: Boolean) {
        if (army.size >= desiredArmy() && !defending) return
        val producers = mine.filter { it.constructed && it.type.trains().any { u -> u.isMilitary && u != UnitType.SCOUT } }
        for (b in producers) {
            if (b.queue.size >= 2) continue
            val options = b.type.trains().filter { it != UnitType.SCOUT && p.age >= it.minAge }
            if (options.isEmpty()) continue
            val pick = chooseUnit(options) ?: continue
            val ok = if (defending || army.size < 4) p.canAfford(pick.cost) else affordWithReserve(pick.cost, 100f)
            if (ok) world.queueTrain(b.id, pick)
        }
        // Rally new units near home.
        for (b in producers) if (!b.hasRally) world.setRally(b.id, home.x + 3f, home.y + 3f)
    }

    private fun chooseUnit(options: List<UnitType>): UnitType? {
        val counts = HashMap<UnitType, Int>()
        for (u in army) counts.merge(u.type, 1, Int::plus)
        val weight = mapOf(
            UnitType.SPEARMAN to 3f, UnitType.WARRIOR to 3f, UnitType.ARCHER to 3f,
            UnitType.HORSEMAN to 3f, UnitType.HORSE_ARCHER to 2f, UnitType.CATAPULT to 1f, UnitType.HEALER to 0.6f,
        )
        return options.minByOrNull { (counts[it] ?: 0) / (weight[it] ?: 1f) }
    }

    private fun enemyThreatNear(home: Building): GameUnit? {
        var threat: GameUnit? = null
        var best = Float.MAX_VALUE
        for (u in world.units) {
            if (!u.alive || !world.isEnemy(playerId, u.owner) || u.type == UnitType.SCOUT) continue
            val terr = world.map.territory[world.map.idx(u.x.toInt().coerceIn(0, world.map.width - 1), u.y.toInt().coerceIn(0, world.map.height - 1))]
            if (terr != playerId) continue
            val d = dist(u.x, u.y, home.x, home.y)
            if (d < best) { best = d; threat = u }
        }
        return threat
    }

    private fun defend(home: Building): Boolean {
        val threat = enemyThreatNear(home) ?: return false
        val responders = army.filter { (!attacking || dist(it.x, it.y, threat.x, threat.y) < 25f) && it.order != OrderType.ATTACK }
        if (responders.isNotEmpty()) world.commandMove(responders.map { it.id }, threat.x, threat.y, attackMove = true)
        return true
    }

    private fun offense(home: Building) {
        val idleArmy = army.filter { it.order == OrderType.IDLE }
        if (attacking) {
            if (army.size < max(2, attackStartSize / 3)) {
                // Retreat and regroup.
                attacking = false
                nextAttackTime = world.time + 90f
                world.commandMove(army.map { it.id }, home.x + 3f, home.y + 3f)
                return
            }
            // Keep idle units pushing towards the next target.
            if (idleArmy.isNotEmpty()) {
                val target = pickTarget(idleArmy.first().x, idleArmy.first().y)
                if (target != null) world.commandMove(idleArmy.map { it.id }, target.x, target.y, attackMove = true)
            }
            return
        }
        // Gather idle army near home.
        val stray = idleArmy.filter { dist(it.x, it.y, home.x, home.y) > 14f }
        if (stray.isNotEmpty()) world.commandMove(stray.map { it.id }, home.x + 3f, home.y + 3f)
        val threshold = diff.attackThreshold + waves * 4 + p.age.ordinal * 2
        if (world.time >= nextAttackTime && army.size >= threshold) {
            val target = pickTarget(home.x, home.y) ?: return
            attacking = true
            waves++
            attackStartSize = army.size
            world.commandMove(army.map { it.id }, target.x, target.y, attackMove = true)
        }
    }

    private fun pickTarget(x: Float, y: Float): Entity? {
        // Nearest enemy building, preferring town centers a little.
        var best: Entity? = null
        var bestD = Float.MAX_VALUE
        for (b in world.buildings) {
            if (!b.alive || !world.isEnemy(playerId, b.owner)) continue
            var d = dist(x, y, b.x, b.y)
            if (b.type == BuildingType.WALL) d += 30f
            if (b.type == BuildingType.TOWN_CENTER) d -= 8f
            if (d < bestD) { bestD = d; best = b }
        }
        if (best != null) return best
        return world.units.filter { it.alive && world.isEnemy(playerId, it.owner) }.minByOrNull { dist(x, y, it.x, it.y) }
    }

    private fun scout(home: Building) {
        val scouts = world.units.filter { it.alive && it.owner == playerId && it.type == UnitType.SCOUT && it.order == OrderType.IDLE }
        for (s in scouts) {
            if (!scoutDone) {
                // Visit a few random points of the map.
                val m = world.map
                val x = world.rng.nextInt(4, m.width - 4).toFloat()
                val y = world.rng.nextInt(4, m.height - 4).toFloat()
                world.commandMove(listOf(s.id), x, y)
                if (world.time > 300f) scoutDone = true
            } else if (dist(s.x, s.y, home.x, home.y) > 6f) {
                world.commandMove(listOf(s.id), home.x - 3f, home.y - 3f)
            }
        }
    }

    // ------------------------------------------------------------------ trade

    private fun trade() {
        if (!world.hasBuilding(playerId, BuildingType.MARKET)) return
        val r = reserve()
        val excess = listOf(ResourceType.FOOD, ResourceType.WOOD, ResourceType.STONE).filter { p[it] > 2500f + r[it] }
        for (res in excess) world.marketTrade(playerId, res, buy = false)
        if (p[ResourceType.GOLD] > 1000f + r.gold) {
            val low = listOf(ResourceType.FOOD, ResourceType.WOOD, ResourceType.STONE).minByOrNull { p[it] - r[it] }
            if (low != null && p[low] < 800f + r[low]) world.marketTrade(playerId, low, buy = true)
        }
    }
}
