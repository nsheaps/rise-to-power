package com.nsheaps.risetopower.core.net

import com.nsheaps.risetopower.core.Civ
import com.nsheaps.risetopower.core.Difficulty
import com.nsheaps.risetopower.core.GameSettings
import com.nsheaps.risetopower.core.MapGenerator
import com.nsheaps.risetopower.core.MapSize
import com.nsheaps.risetopower.core.MapType
import com.nsheaps.risetopower.core.PlayerSetup
import com.nsheaps.risetopower.core.World
import java.util.concurrent.CopyOnWriteArrayList

const val PROTOCOL_VERSION = 1

/** Options the host picks for a multiplayer game. */
data class NetOptions(
    val mapSize: MapSize = MapSize.MEDIUM,
    val mapType: MapType = MapType.CONTINENTAL,
    val aiPlayers: Int = 0,
    val difficulty: Difficulty = Difficulty.NORMAL,
    /** Humans form one team against the AIs instead of everyone for themselves. */
    val coop: Boolean = false,
    val startingResources: Int = 200,
    val wonderVictory: Boolean = true,
    val seed: Long = 1L,
)

/**
 * The host's side of the lobby: players connect, introduce themselves, and wait for the host to
 * start. Connections may be added from any thread; everything else runs on one thread.
 */
class HostLobby(private val hostName: String, var hostCiv: Int, val maxGuests: Int = 3) {
    class Guest(val link: Link) {
        var name: String? = null
        var civ = 0
    }

    private val incoming = CopyOnWriteArrayList<Link>()
    private val guests = ArrayList<Guest>()
    private var started = false

    /** Called (from any thread) when a device connects. */
    fun add(link: Link) {
        if (started) { link.send(Message.Bye("The game has already started")); link.close(); return }
        incoming += link
    }

    /** Names of the players who joined and introduced themselves. */
    val joined: List<String> get() = guests.mapNotNull { it.name }

    /** Handles new connections, introductions and departures; returns true if the lobby changed. */
    fun poll(): Boolean {
        var changed = false
        for (l in incoming) {
            incoming.remove(l)
            if (guests.size >= maxGuests) { l.send(Message.Bye("The game is full")); (l as? StreamLink)?.flushAndClose() ?: l.close(); continue }
            guests += Guest(l)
        }
        for (g in guests.toList()) {
            while (true) {
                val raw = g.link.poll() ?: break
                when (val m = try { Message.decode(raw) } catch (_: Exception) { null }) {
                    is Message.Hello -> {
                        if (m.version != PROTOCOL_VERSION) {
                            g.link.send(Message.Bye("Different game versions: update both devices"))
                            (g.link as? StreamLink)?.flushAndClose() ?: g.link.close()
                        } else {
                            g.name = uniqueName(m.name.take(20).ifBlank { "Player" }, g)
                            g.civ = m.civ.coerceIn(0, Civ.entries.size - 1)
                            changed = true
                        }
                    }
                    is Message.Bye -> g.link.close()
                    else -> {}
                }
            }
            if (g.link.isClosed) {
                guests.remove(g)
                changed = true
            }
        }
        if (changed) broadcastLobby()
        return changed
    }

    private fun uniqueName(name: String, self: Guest): String {
        val taken = guests.filter { it !== self }.mapNotNull { it.name } + hostName
        if (name !in taken) return name
        var i = 2
        while ("$name $i" in taken) i++
        return "$name $i"
    }

    private fun broadcastLobby() {
        val msg = Message.Lobby(listOf(hostName) + joined).encode()
        for (g in guests) if (g.name != null) g.link.send(msg)
    }

    /** Most AI opponents a map can take alongside the joined players. */
    fun maxAis(size: MapSize) = (MapGenerator.maxPlayers(size) - 1 - joined.size).coerceAtLeast(0)

    /**
     * Generates the map and starts the game for everyone. Players who connected but never
     * introduced themselves are dropped.
     */
    fun start(options: NetOptions, colors: IntArray, aiNames: List<String>, aiCivs: List<Int>): Lockstep {
        started = true
        for (g in guests.filter { it.name == null }) { g.link.close(); guests.remove(g) }
        val humans = listOf(hostName to hostCiv) + guests.map { it.name!! to it.civ }
        val ais = options.aiPlayers.coerceAtMost((MapGenerator.maxPlayers(options.mapSize) - humans.size).coerceAtLeast(0))
        val setups = ArrayList<PlayerSetup>()
        for ((i, h) in humans.withIndex()) {
            setups += PlayerSetup(h.first, Civ.entries[h.second], true, if (options.coop) 0 else i, options.difficulty, colors[i % colors.size])
        }
        for (k in 0 until ais) {
            val i = humans.size + k
            setups += PlayerSetup(aiNames[k % aiNames.size], Civ.entries[aiCivs[k % aiCivs.size]], false, if (options.coop) 1 else i, options.difficulty, colors[i % colors.size])
        }
        val settings = GameSettings(
            mapSize = options.mapSize, mapType = options.mapType, seed = options.seed, players = setups,
            startingResources = options.startingResources, wonderVictory = options.wonderVictory,
        )
        val peers = guests.mapIndexed { i, g -> Lockstep.Peer(g.link, i + 1, g.name!!) }
        return Lockstep.host(World(settings), 0, peers)
    }

    /** Closes every connection (the host backed out of the lobby). */
    fun close() {
        for (l in incoming) l.close()
        for (g in guests) { g.link.send(Message.Bye("The host closed the game")); (g.link as? StreamLink)?.flushAndClose(200) ?: g.link.close() }
        guests.clear()
    }
}

/** A joining player's side of the lobby. */
class JoinLobby(private val link: Link, private val hostName: String, name: String, civ: Int) {
    /** Everyone in the lobby, host first. */
    var players: List<String> = listOf(hostName)
        private set

    /** Why the connection ended, once it has. */
    var closedReason: String? = null
        private set

    init {
        link.send(Message.Hello(PROTOCOL_VERSION, name, civ))
    }

    /** Processes messages; returns the game session once the host starts it. */
    fun poll(): Lockstep? {
        while (true) {
            val raw = link.poll() ?: break
            when (val m = try { Message.decode(raw) } catch (_: Exception) { null }) {
                is Message.Lobby -> players = m.names
                is Message.Start -> return Lockstep.join(link, hostName, m)
                is Message.Bye -> { closedReason = m.reason; link.close() }
                else -> {}
            }
        }
        if (link.isClosed && closedReason == null) closedReason = "Lost connection to $hostName"
        return null
    }

    fun close() {
        link.send(Message.Bye("left"))
        (link as? StreamLink)?.flushAndClose(200) ?: link.close()
    }
}
