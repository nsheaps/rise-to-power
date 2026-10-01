package com.nsheaps.risetopower.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulationTest {
    private fun settings(
        n: Int = 2,
        size: MapSize = MapSize.SMALL,
        type: MapType = MapType.CONTINENTAL,
        seed: Long = 42L,
        humanFirst: Boolean = false,
        diff: Difficulty = Difficulty.HARD,
    ) = GameSettings(
        mapSize = size,
        mapType = type,
        seed = seed,
        players = (0 until n).map {
            PlayerSetup("P$it", Civ.entries[it % Civ.entries.size], humanFirst && it == 0, it, diff, 0xFF000000.toInt() or (it * 0x404040))
        },
    )

    private fun run(world: World, seconds: Float) {
        val ticks = (seconds / World.TICK).toInt()
        for (i in 0 until ticks) {
            world.update(World.TICK)
            if (world.gameOver) return
        }
    }

    @Test
    fun mapGenerationPlacesStartingAssets() {
        for (type in MapType.entries) {
            val w = World(settings(4, MapSize.MEDIUM, type, seed = 7L + type.ordinal))
            for (p in w.players) {
                assertEquals(1, w.townCenters(p.id).size)
                assertTrue(w.units.count { it.owner == p.id && it.type == UnitType.VILLAGER } >= 4)
            }
            assertTrue(w.nodes.count { it.kind == NodeKind.TREE } > 100)
            assertTrue(w.nodes.any { it.kind == NodeKind.GOLD })
            // Every town center can reach every other one.
            val a = w.townCenters(0).first()
            for (p in 1 until 4) {
                val b = w.townCenters(p).first()
                val path = w.pathfinder.findPathToRect(a.x, a.maxY + 0.5f, b.tx, b.ty, b.tx + 2, b.ty + 2)
                assertTrue("path to player $p on $type", path.size >= 2)
                val ex = path[path.size - 2]; val ey = path[path.size - 1]
                assertTrue("reach $p on $type", b.distanceTo(ex, ey) < 1.5f)
            }
        }
    }

    @Test
    fun villagersGatherAndDeposit() {
        val w = World(settings(2, humanFirst = true))
        val p = w.players[0]
        val vill = w.units.first { it.owner == 0 && it.type == UnitType.VILLAGER }
        val tree = w.findNode(ResourceType.WOOD, vill.x, vill.y, 40f)
        assertNotNull(tree)
        val before = p[ResourceType.WOOD]
        w.commandGather(listOf(vill.id), tree!!.id)
        run(w, 90f)
        assertTrue("wood went up: ${p[ResourceType.WOOD]} vs $before", p[ResourceType.WOOD] > before + 15f)
    }

    @Test
    fun constructionAndTraining() {
        val w = World(settings(2, humanFirst = true))
        val p = w.players[0]
        p.stock.fill(2000f)
        val tc = w.townCenters(0).first()
        val vills = w.units.filter { it.owner == 0 && it.type == UnitType.VILLAGER }.map { it.id }
        var spot: Pair<Int, Int>? = null
        loop@ for (r in 4..10) for (dy in -r..r) for (dx in -r..r) {
            if (w.placementError(0, BuildingType.BARRACKS, tc.tx + dx, tc.ty + dy) == null) { spot = Pair(tc.tx + dx, tc.ty + dy); break@loop }
        }
        assertNotNull(spot)
        val b = w.placeFoundation(0, BuildingType.BARRACKS, spot!!.first, spot.second, vills)
        assertNotNull(b)
        run(w, 40f)
        assertTrue("barracks done (${b!!.progress})", b.constructed)
        assertNull(w.queueTrain(b.id, UnitType.SPEARMAN))
        run(w, 20f)
        assertTrue(w.units.any { it.owner == 0 && it.type == UnitType.SPEARMAN })
        assertNull(w.queueAdvance(tc.id))
        run(w, 45f)
        assertEquals(Age.CLASSICAL, p.age)
    }

    @Test
    fun combatKillsUnits() {
        val w = World(settings(2, humanFirst = true))
        val a = w.spawnUnit(0, UnitType.WARRIOR, 30f, 30f)
        val b = w.spawnUnit(1, UnitType.ARCHER, 31f, 30f)
        w.commandAttack(listOf(a.id), b.id)
        run(w, 30f)
        assertTrue(!b.alive)
        assertTrue(a.alive)
    }

    @Test
    fun saveAndLoadRoundTrip() {
        val w = World(settings(3, humanFirst = true))
        run(w, 60f)
        val bytes = GameSave.write(w)
        val w2 = GameSave.read(bytes)
        assertEquals(w.units.size, w2.units.size)
        assertEquals(w.buildings.size, w2.buildings.size)
        assertEquals(w.nodes.size, w2.nodes.size)
        assertEquals(w.time, w2.time, 0.001f)
        assertEquals(w.players[0][ResourceType.FOOD], w2.players[0][ResourceType.FOOD], 0.01f)
        run(w2, 30f)
    }

    @Test
    fun aiVersusAiProgresses() {
        for (seed in listOf(1L, 2L)) {
            val w = World(settings(2, MapSize.SMALL, seed = seed, diff = Difficulty.HARD))
            var t = 0f
            while (t < 2400f && !w.gameOver) {
                run(w, 60f)
                t += 60f
                if ((t.toInt() % 300) == 0) {
                    val s = w.players.joinToString(" | ") { p ->
                        val v = w.units.count { it.owner == p.id && it.type == UnitType.VILLAGER }
                        val m = w.units.count { it.owner == p.id && it.type.isMilitary }
                        val bld = w.buildings.count { it.owner == p.id }
                        "${p.name} ${p.age.name} v=$v m=$m b=$bld pop=${p.popUsed}/${p.popCap} res=${p.stock.map { it.toInt() }} techs=${p.techs.size}"
                    }
                    println("seed $seed t=${t.toInt()} $s")
                }
            }
            println("seed $seed ended at ${w.time.toInt()}s gameOver=${w.gameOver} winner=${w.winnerTeam}")
            for (p in w.players) assertTrue("${p.name} should advance at least one age", p.age >= Age.CLASSICAL || p.defeated)
            assertTrue("someone should have fought", w.players.sumOf { it.unitsKilled } > 0)
        }
    }
}
