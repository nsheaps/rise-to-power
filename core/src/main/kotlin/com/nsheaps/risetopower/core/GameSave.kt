package com.nsheaps.risetopower.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.random.Random

/**
 * Binary save format. Paths and other transient state are not stored; units recompute them on
 * load. AI controllers are recreated from the player list.
 */
object GameSave {
    private const val MAGIC = 0x52325031 // "R2P1"
    private const val VERSION = 1

    fun write(w: World): ByteArray {
        val bytes = ByteArrayOutputStream()
        val o = DataOutputStream(bytes)
        o.writeInt(MAGIC)
        o.writeInt(VERSION)
        val s = w.settings
        o.writeInt(s.mapSize.ordinal)
        o.writeInt(s.mapType.ordinal)
        o.writeLong(s.seed)
        o.writeInt(s.startingResources)
        o.writeBoolean(s.wonderVictory)
        o.writeBoolean(s.revealMap)
        o.writeInt(s.players.size)
        for (p in s.players) {
            o.writeUTF(p.name); o.writeInt(p.civ.ordinal); o.writeBoolean(p.isHuman)
            o.writeInt(p.team); o.writeInt(p.difficulty.ordinal); o.writeInt(p.color)
        }
        o.writeFloat(w.time)
        o.writeLong(w.tick)
        o.writeInt(w.nextId)
        o.writeBoolean(w.gameOver)
        o.writeInt(w.winnerTeam)

        val m = w.map
        for (i in m.terrain.indices) {
            o.writeByte(m.terrain[i].ordinal)
            o.writeFloat(m.elevation[i])
        }

        for (p in w.players) {
            for (v in p.stock) o.writeFloat(v)
            o.writeInt(p.age.ordinal)
            o.writeInt(p.techs.size)
            for (t in p.techs) o.writeInt(t.ordinal)
            o.writeBoolean(p.defeated)
            for (v in p.marketPrice) o.writeFloat(v)
            for (v in p.gathered) o.writeFloat(v)
            o.writeInt(p.unitsKilled); o.writeInt(p.unitsLost)
            o.writeInt(p.buildingsDestroyed); o.writeInt(p.buildingsLost); o.writeInt(p.techCount)
            val ex = p.explored
            o.writeInt(ex.size)
            // Pack explored flags 8 per byte.
            var i = 0
            while (i < ex.size) {
                var b = 0
                for (k in 0 until 8) if (i + k < ex.size && ex[i + k]) b = b or (1 shl k)
                o.writeByte(b)
                i += 8
            }
        }

        o.writeInt(w.nodes.size)
        for (n in w.nodes) {
            o.writeInt(n.id); o.writeInt(n.kind.ordinal); o.writeInt(n.tx); o.writeInt(n.ty); o.writeFloat(n.amount)
        }

        o.writeInt(w.buildings.size)
        for (b in w.buildings) {
            o.writeInt(b.id); o.writeInt(b.owner); o.writeInt(b.type.ordinal); o.writeInt(b.tx); o.writeInt(b.ty)
            o.writeFloat(b.hp); o.writeBoolean(b.constructed); o.writeFloat(b.progress)
            o.writeFloat(b.queueProgress); o.writeFloat(b.rallyX); o.writeFloat(b.rallyY)
            o.writeInt(b.farmerId); o.writeFloat(b.wonderTimer)
            o.writeInt(b.queue.size)
            for (q in b.queue) when (q) {
                is ProdItem.Train -> { o.writeByte(0); o.writeInt(q.unit.ordinal); o.writeFloat(q.time) }
                is ProdItem.Research -> { o.writeByte(1); o.writeInt(q.tech.ordinal); o.writeFloat(q.time) }
                is ProdItem.Advance -> { o.writeByte(2); o.writeInt(q.age.ordinal); o.writeFloat(q.time) }
            }
        }

        val units = w.units.filter { it.alive }
        o.writeInt(units.size)
        for (u in units) {
            o.writeInt(u.id); o.writeInt(u.owner); o.writeInt(u.type.ordinal)
            o.writeFloat(u.x); o.writeFloat(u.y); o.writeFloat(u.hp)
            o.writeInt(u.order.ordinal); o.writeInt(u.targetId)
            o.writeFloat(u.destX); o.writeFloat(u.destY)
            o.writeInt(u.carryType?.ordinal ?: -1); o.writeFloat(u.carryAmount)
            o.writeInt(u.gatherNodeId); o.writeInt(u.gatherResource?.ordinal ?: -1)
            o.writeFloat(u.gatherX); o.writeFloat(u.gatherY)
            o.writeBoolean(u.attackMove); o.writeFloat(u.amX); o.writeFloat(u.amY)
            o.writeFloat(u.facing); o.writeInt(u.kills)
        }
        o.flush()
        return bytes.toByteArray()
    }

    fun read(data: ByteArray): World {
        val i = DataInputStream(ByteArrayInputStream(data))
        require(i.readInt() == MAGIC) { "Not a Rise to Power save" }
        val version = i.readInt()
        require(version == VERSION) { "Unsupported save version $version" }
        val mapSize = MapSize.entries[i.readInt()]
        val mapType = MapType.entries[i.readInt()]
        val seed = i.readLong()
        val startRes = i.readInt()
        val wonder = i.readBoolean()
        val reveal = i.readBoolean()
        val n = i.readInt()
        val setups = (0 until n).map {
            PlayerSetup(i.readUTF(), Civ.entries[i.readInt()], i.readBoolean(), i.readInt(), Difficulty.entries[i.readInt()], i.readInt())
        }
        val settings = GameSettings(mapSize, mapType, seed, setups, startRes, wonder, reveal)
        val w = World(settings, generate = false)
        w.time = i.readFloat()
        w.tick = i.readLong()
        val nextId = i.readInt()
        w.gameOver = i.readBoolean()
        w.winnerTeam = i.readInt()
        w.rng = Random(seed xor w.tick)

        val m = w.map
        for (k in m.terrain.indices) {
            m.terrain[k] = Terrain.entries[i.readByte().toInt()]
            m.elevation[k] = i.readFloat()
            m.refreshBlocked(k, false)
        }

        for (p in w.players) {
            for (k in p.stock.indices) p.stock[k] = i.readFloat()
            p.age = Age.entries[i.readInt()]
            repeat(i.readInt()) { p.techs += Tech.entries[i.readInt()] }
            p.defeated = i.readBoolean()
            for (k in p.marketPrice.indices) p.marketPrice[k] = i.readFloat()
            for (k in p.gathered.indices) p.gathered[k] = i.readFloat()
            p.unitsKilled = i.readInt(); p.unitsLost = i.readInt()
            p.buildingsDestroyed = i.readInt(); p.buildingsLost = i.readInt(); p.techCount = i.readInt()
            val size = i.readInt()
            val ex = BooleanArray(size)
            var k = 0
            while (k < size) {
                val b = i.readByte().toInt()
                for (bit in 0 until 8) if (k + bit < size) ex[k + bit] = (b shr bit) and 1 == 1
                k += 8
            }
            p.explored = ex
        }

        repeat(i.readInt()) {
            val id = i.readInt(); val kind = NodeKind.entries[i.readInt()]
            val tx = i.readInt(); val ty = i.readInt(); val amount = i.readFloat()
            w.nextId = id
            w.spawnNode(kind, tx, ty)?.amount = amount
        }

        repeat(i.readInt()) {
            val id = i.readInt(); val owner = i.readInt(); val type = BuildingType.entries[i.readInt()]
            val tx = i.readInt(); val ty = i.readInt()
            w.nextId = id
            val b = w.addBuilding(owner, type, tx, ty, false)
            b.hp = i.readFloat(); b.constructed = i.readBoolean(); b.progress = i.readFloat()
            b.queueProgress = i.readFloat(); b.rallyX = i.readFloat(); b.rallyY = i.readFloat()
            b.farmerId = i.readInt(); b.wonderTimer = i.readFloat()
            repeat(i.readInt()) {
                val kind = i.readByte().toInt()
                val ord = i.readInt(); val time = i.readFloat()
                b.queue += when (kind) {
                    0 -> ProdItem.Train(UnitType.entries[ord], time)
                    1 -> ProdItem.Research(Tech.entries[ord], time)
                    else -> ProdItem.Advance(Age.entries[ord], time)
                }
            }
        }

        repeat(i.readInt()) {
            val id = i.readInt(); val owner = i.readInt(); val type = UnitType.entries[i.readInt()]
            val x = i.readFloat(); val y = i.readFloat()
            w.nextId = id
            val u = w.spawnUnit(owner, type, x, y)
            u.hp = i.readFloat()
            u.order = OrderType.entries[i.readInt()]; u.targetId = i.readInt()
            u.destX = i.readFloat(); u.destY = i.readFloat()
            u.carryType = i.readInt().let { if (it < 0) null else ResourceType.entries[it] }
            u.carryAmount = i.readFloat()
            u.gatherNodeId = i.readInt()
            u.gatherResource = i.readInt().let { if (it < 0) null else ResourceType.entries[it] }
            u.gatherX = i.readFloat(); u.gatherY = i.readFloat()
            u.attackMove = i.readBoolean(); u.amX = i.readFloat(); u.amY = i.readFloat()
            u.facing = i.readFloat(); u.kills = i.readInt()
        }
        w.nextId = nextId

        // Ages and techs were loaded before entities were created, so max HP values are already correct.
        for (p in w.players) if (!p.isHuman) w.ais += AiController(w, p.id)
        w.recomputePopulation()
        w.updateTerritory()
        w.updateVision()
        return w
    }
}
