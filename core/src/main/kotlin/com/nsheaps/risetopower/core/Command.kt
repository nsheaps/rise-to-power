package com.nsheaps.risetopower.core

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * A player action. Every change a player makes to the game goes through a command so that it
 * can be sent over the network and applied at the same simulation tick on every device.
 */
sealed class Command {
    data class Smart(val ids: List<Int>, val x: Float, val y: Float, val targetId: Int) : Command()
    data class Move(val ids: List<Int>, val x: Float, val y: Float, val attackMove: Boolean) : Command()
    data class Stop(val ids: List<Int>) : Command()
    data class Return(val ids: List<Int>) : Command()
    data class Delete(val ids: List<Int>) : Command()
    data class Place(val type: BuildingType, val tx: Int, val ty: Int, val builders: List<Int>) : Command()
    data class Wall(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val builders: List<Int>) : Command()
    data class Train(val buildingId: Int, val unit: UnitType) : Command()
    data class Research(val buildingId: Int, val tech: Tech) : Command()
    data class Advance(val buildingId: Int) : Command()
    data class CancelQueue(val buildingId: Int, val index: Int) : Command()
    data class Rally(val buildingIds: List<Int>, val x: Float, val y: Float) : Command()
    data class Trade(val resource: ResourceType, val buy: Boolean) : Command()
    object Resign : Command()

    fun write(o: DataOutputStream) {
        fun ids(l: List<Int>) { o.writeShort(l.size); for (i in l) o.writeInt(i) }
        when (this) {
            is Smart -> { o.writeByte(0); ids(ids); o.writeFloat(x); o.writeFloat(y); o.writeInt(targetId) }
            is Move -> { o.writeByte(1); ids(ids); o.writeFloat(x); o.writeFloat(y); o.writeBoolean(attackMove) }
            is Stop -> { o.writeByte(2); ids(ids) }
            is Return -> { o.writeByte(3); ids(ids) }
            is Delete -> { o.writeByte(4); ids(ids) }
            is Place -> { o.writeByte(5); o.writeByte(type.ordinal); o.writeInt(tx); o.writeInt(ty); ids(builders) }
            is Wall -> { o.writeByte(6); o.writeInt(x0); o.writeInt(y0); o.writeInt(x1); o.writeInt(y1); ids(builders) }
            is Train -> { o.writeByte(7); o.writeInt(buildingId); o.writeByte(unit.ordinal) }
            is Research -> { o.writeByte(8); o.writeInt(buildingId); o.writeByte(tech.ordinal) }
            is Advance -> { o.writeByte(9); o.writeInt(buildingId) }
            is CancelQueue -> { o.writeByte(10); o.writeInt(buildingId); o.writeInt(index) }
            is Rally -> { o.writeByte(11); ids(buildingIds); o.writeFloat(x); o.writeFloat(y) }
            is Trade -> { o.writeByte(12); o.writeByte(resource.ordinal); o.writeBoolean(buy) }
            Resign -> o.writeByte(13)
        }
    }

    companion object {
        /** Upper bound on ids in one command; protects against corrupt or hostile input. */
        private const val MAX_IDS = 2000

        fun read(i: DataInputStream): Command {
            fun ids(): List<Int> {
                val n = i.readUnsignedShort()
                require(n <= MAX_IDS) { "Too many ids: $n" }
                return List(n) { i.readInt() }
            }
            return when (val kind = i.readUnsignedByte()) {
                0 -> Smart(ids(), i.readFloat(), i.readFloat(), i.readInt())
                1 -> Move(ids(), i.readFloat(), i.readFloat(), i.readBoolean())
                2 -> Stop(ids())
                3 -> Return(ids())
                4 -> Delete(ids())
                5 -> Place(BuildingType.entries[i.readUnsignedByte()], i.readInt(), i.readInt(), ids())
                6 -> Wall(i.readInt(), i.readInt(), i.readInt(), i.readInt(), ids())
                7 -> Train(i.readInt(), UnitType.entries[i.readUnsignedByte()])
                8 -> Research(i.readInt(), Tech.entries[i.readUnsignedByte()])
                9 -> Advance(i.readInt())
                10 -> CancelQueue(i.readInt(), i.readInt())
                11 -> Rally(ids(), i.readFloat(), i.readFloat())
                12 -> Trade(ResourceType.entries[i.readUnsignedByte()], i.readBoolean())
                13 -> Resign
                else -> throw IllegalArgumentException("Unknown command $kind")
            }
        }
    }
}
