package com.nsheaps.risetopower.core.net

import com.nsheaps.risetopower.core.Command
import com.nsheaps.risetopower.core.GameSave
import com.nsheaps.risetopower.core.World
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Messages exchanged between the host and the players who join its game. */
sealed class Message {
    /** Joining player introduces itself. */
    data class Hello(val version: Int, val name: String, val civ: Int) : Message()
    /** Host tells joined players who is in the lobby. */
    data class Lobby(val names: List<String>) : Message()
    /**
     * The game starts (or a player rejoins it): the receiver plays [playerId] in the world stored
     * in [snapshot]. [token] lets that player prove who they are when rejoining.
     */
    class Start(val playerId: Int, val turn: Int, val snapshot: ByteArray, val token: Long = 0L) : Message()
    /** Everyone's state is replaced by [snapshot] at the start of [turn]. */
    class Resync(val turn: Int, val snapshot: ByteArray) : Message()
    /** A joined player's command, to be scheduled by the host. */
    data class Cmd(val command: Command) : Message()
    /** The commands to apply at the start of [turn], in order, with the player issuing each. */
    data class Turn(val turn: Int, val commands: List<Pair<Int, Command>>) : Message()
    /** A joined player finished [turn]; its state hashed to [checksum]. */
    data class Ack(val turn: Int, val checksum: Long) : Message()
    /** The sender is leaving (the host may give a [reason] such as a full lobby). */
    data class Bye(val reason: String) : Message()
    /** A player who lost the connection asks to take [playerId] back. */
    data class Rejoin(val version: Int, val playerId: Int, val token: Long) : Message()
    /** Keeps a quiet connection from looking dead. */
    object Ping : Message()
    /** Players whose connection dropped, with the turn at which each forfeits unless back. */
    data class Away(val players: List<Pair<Int, Int>>) : Message()

    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        val o = DataOutputStream(bytes)
        when (this) {
            is Hello -> { o.writeByte(1); o.writeInt(version); o.writeUTF(name); o.writeByte(civ) }
            is Lobby -> { o.writeByte(2); o.writeByte(names.size); names.forEach { o.writeUTF(it) } }
            is Start -> { o.writeByte(3); o.writeByte(playerId); o.writeInt(turn); o.writeLong(token); o.writeInt(snapshot.size); o.write(snapshot) }
            is Resync -> { o.writeByte(4); o.writeInt(turn); o.writeInt(snapshot.size); o.write(snapshot) }
            is Cmd -> { o.writeByte(5); command.write(o) }
            is Turn -> {
                o.writeByte(6); o.writeInt(turn); o.writeShort(commands.size)
                for ((p, c) in commands) { o.writeByte(p); c.write(o) }
            }
            is Ack -> { o.writeByte(7); o.writeInt(turn); o.writeLong(checksum) }
            is Bye -> { o.writeByte(8); o.writeUTF(reason) }
            is Rejoin -> { o.writeByte(9); o.writeInt(version); o.writeByte(playerId); o.writeLong(token) }
            is Ping -> o.writeByte(10)
            is Away -> { o.writeByte(11); o.writeByte(players.size); for ((p, t) in players) { o.writeByte(p); o.writeInt(t) } }
        }
        o.flush()
        return bytes.toByteArray()
    }

    companion object {
        fun decode(b: ByteArray): Message {
            val i = DataInputStream(ByteArrayInputStream(b))
            fun blob(): ByteArray {
                val n = i.readInt()
                require(n in 0..StreamLink.MAX_MESSAGE)
                return ByteArray(n).also { i.readFully(it) }
            }
            return when (val type = i.readUnsignedByte()) {
                1 -> Hello(i.readInt(), i.readUTF(), i.readUnsignedByte())
                2 -> Lobby(List(i.readUnsignedByte()) { i.readUTF() })
                3 -> {
                    val player = i.readUnsignedByte()
                    val turn = i.readInt()
                    val token = i.readLong()
                    Start(player, turn, blob(), token)
                }
                4 -> Resync(i.readInt(), blob())
                5 -> Cmd(Command.read(i))
                6 -> {
                    val turn = i.readInt()
                    Turn(turn, List(i.readUnsignedShort()) { i.readUnsignedByte() to Command.read(i) })
                }
                7 -> Ack(i.readInt(), i.readLong())
                8 -> Bye(i.readUTF())
                9 -> Rejoin(i.readInt(), i.readUnsignedByte(), i.readLong())
                10 -> Ping
                11 -> Away(List(i.readUnsignedByte()) { i.readUnsignedByte() to i.readInt() })
                else -> throw IllegalArgumentException("Unknown message $type")
            }
        }

        /** Serialised, compressed copy of a world. */
        fun snapshot(w: World): ByteArray {
            val out = ByteArrayOutputStream()
            DeflaterOutputStream(out).use { it.write(GameSave.write(w)) }
            return out.toByteArray()
        }

        fun restore(snapshot: ByteArray): World =
            GameSave.read(InflaterInputStream(ByteArrayInputStream(snapshot)).use { it.readBytes() })
    }
}

fun Link.send(m: Message) = send(m.encode())
