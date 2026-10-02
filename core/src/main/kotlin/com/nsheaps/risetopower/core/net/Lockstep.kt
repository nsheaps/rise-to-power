package com.nsheaps.risetopower.core.net

import com.nsheaps.risetopower.core.Command
import com.nsheaps.risetopower.core.EventType
import com.nsheaps.risetopower.core.GameEvent
import com.nsheaps.risetopower.core.World
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToInt

/**
 * Deterministic lockstep multiplayer. Every device runs the full simulation; only commands
 * travel over the network.
 *
 * Time is split into turns of [TICKS_PER_TURN] simulation ticks. The host collects commands from
 * everyone and schedules them [DELAY] turns ahead, broadcasting each turn's command list before
 * it is due. A device runs a turn only once it has that turn's list, so all devices apply the
 * same commands at the same tick and stay identical.
 *
 * Joined players report a checksum after each turn. If one ever differs (for example a device
 * computing a float differently) the host sends a snapshot of its world and everyone continues
 * from that, so a desync costs a brief hitch instead of the game.
 *
 * If a player's connection drops, the game carries on without them: their civilization keeps
 * doing what it was told while their phone shows that it is disconnected and keeps trying to
 * reconnect. Back within [FORFEIT_SECONDS], they pick up the current game from a snapshot;
 * otherwise they forfeit. A player who leaves on purpose resigns at once, and if the host leaves
 * the others keep playing on their own.
 */
class Lockstep private constructor(
    world: World,
    val localPlayer: Int,
    val isHost: Boolean,
    private val peers: List<Peer>,
) {
    enum class State { CONNECTED, AWAY, GONE }

    /** Another device in the game: for the host each joined player, for them the host. */
    class Peer(link: Link, val playerId: Int, val name: String, val joinName: String = name) {
        var link = link
            internal set
        var state = State.CONNECTED
            internal set
        /** Proves a rejoining player's identity. */
        var token = 0L
            internal set
        var acked = -1
        /** Host: the turn at which an away player forfeits. */
        var awayUntil = 0
        /** Seconds since anything arrived from this device. */
        var silent = 0f
        /** Checksums reported for turns the host has not finished yet. */
        val reported = HashMap<Int, Long>()
        val alive get() = state == State.CONNECTED
    }

    /** A player whose connection dropped, and how long they have left to come back. */
    data class Away(val playerId: Int, val name: String, val secondsLeft: Float)

    var world: World = world
        private set

    /** Called on the game thread when the world is replaced by a snapshot. */
    var onWorldReplaced: ((World) -> Unit)? = null

    /**
     * Joined player: opens a new connection to the host after the old one dropped. Called on a
     * background thread and may block; throws or returns null if the host can't be reached.
     */
    @Volatile var reconnect: (() -> Link?)? = null

    /** Next turn to run. */
    var turn = 0
        private set

    /** Set while the game is held up waiting for another device. */
    var waitingFor: String? = null
        private set

    /** True once no other device is connected; the game then runs locally. */
    var offline = false
        private set

    var resyncs = 0
        private set

    /** Joined player: seconds left to reconnect before forfeiting, while the connection is down. */
    val reconnectSecondsLeft: Float? get() = if (disconnected) (FORFEIT_SECONDS - disconnectedFor).coerceAtLeast(0f) else null

    /** Joined player: why this player is out of the game, if they were away too long. */
    var forfeitReason: String? = null
        private set

    private val bundles = HashMap<Int, List<Pair<Int, Command>>>()
    private val pending = ArrayList<Pair<Int, Command>>()
    private val hashes = HashMap<Int, Long>()
    private var resyncNeeded = false
    /** Acks for turns before this were computed on a replaced world and are ignored. */
    private var validFrom = 0
    private var accumulator = 0f
    private var stalled = 0f
    private var sincePing = 0f

    // Host: connections that arrived during the game, until they say who they are.
    private class Newcomer(val link: Link) { var age = 0f }
    private val incoming = ConcurrentLinkedQueue<Link>()
    private val newcomers = ArrayList<Newcomer>()

    // Joined player: reconnecting after the connection to the host dropped.
    private var token = 0L
    private var awayTurns: List<Pair<Int, Int>> = emptyList()
    @Volatile private var disconnected = false
    private var disconnectedFor = 0f
    @Volatile private var attempt = 0
    @Volatile private var rejoinLink: Link? = null

    /** Issues a command for the local player. */
    fun issue(c: Command) {
        when {
            offline -> world.execute(localPlayer, c)
            isHost -> pending += localPlayer to c
            disconnected -> {} // Nobody to send it to; the screen says so.
            else -> peers[0].link.send(Message.Cmd(c))
        }
    }

    /** Host: a device connected during the game, maybe a player coming back. Any thread. */
    fun accept(link: Link) {
        if (offline) { link.close(); return }
        incoming += link
    }

    /** Players whose connection dropped and who may still come back. */
    val away: List<Away>
        get() = if (isHost) {
            peers.filter { it.state == State.AWAY }.map { Away(it.playerId, it.name, secondsUntil(it.awayUntil)) }
        } else {
            awayTurns.filter { it.first != localPlayer }.map { (p, t) -> Away(p, world.players[p].name, secondsUntil(t)) }
        }

    private fun secondsUntil(t: Int) = ((t - turn) * TURN_TIME).coerceAtLeast(0f)

    /** Advances real time by [dt] seconds; returns the number of simulation ticks run. */
    fun update(dt: Float): Int {
        receive(dt)
        if (offline) {
            accumulator += dt
            var ticks = 0
            while (accumulator >= World.TICK && ticks < 8) {
                world.update(World.TICK)
                accumulator -= World.TICK
                ticks++
            }
            if (ticks == 8) accumulator = 0f
            return ticks
        }
        if (disconnected) {
            whileDisconnected(dt)
            stalled += dt
            return 0
        }
        sincePing += dt
        if (sincePing >= PING_INTERVAL) {
            sincePing = 0f
            val ping = Message.Ping.encode()
            for (p in peers) if (p.alive) p.link.send(ping)
        }
        accumulator = (accumulator + dt).coerceAtMost(TURN_TIME * 3)
        var ticks = 0
        var steps = 0
        while (steps < 4) {
            // A joined player that has fallen behind catches up by running extra turns.
            val behind = !isHost && bundles.containsKey(turn + DELAY + 1)
            if (accumulator < TURN_TIME && !behind) break
            if (!step()) break
            if (accumulator >= TURN_TIME) accumulator -= TURN_TIME
            ticks += TICKS_PER_TURN
            steps++
        }
        if (waitingFor != null) stalled += dt else stalled = 0f
        return ticks
    }

    /** Seconds the game has been held up waiting for another device. */
    val stalledFor get() = stalled

    private fun step(): Boolean {
        if (isHost) {
            forfeitAbsentees()
            if (offline) return false
            val slow = peers.firstOrNull { it.alive && it.acked < turn - MAX_AHEAD }
            if (slow != null) { waitingFor = slow.name; return false }
            if (resyncNeeded) resync()
            schedule(turn + DELAY)
        } else if (!bundles.containsKey(turn)) {
            waitingFor = peers[0].name
            return false
        }
        waitingFor = null
        run(turn)
        if (!isHost) peers[0].link.send(Message.Ack(turn, world.checksum()))
        else if (peers.isNotEmpty()) {
            val h = world.checksum()
            hashes[turn] = h
            for (p in peers) p.reported.remove(turn)?.let { if (it != h) resyncNeeded = true }
            val oldest = peers.filter { it.alive }.minOfOrNull { it.acked } ?: turn
            hashes.keys.removeAll { it < oldest }
        }
        turn++
        return true
    }

    private fun run(t: Int) {
        val cmds = bundles[t] ?: emptyList()
        // A few used turns are kept: a resync may rewind joined players to a turn they already ran.
        bundles.remove(t - DELAY - 4)
        for ((p, c) in cmds) world.execute(p, c)
        repeat(TICKS_PER_TURN) { world.update(World.TICK) }
    }

    /** Host: closes the command list for turn [t] and sends it to everyone. */
    private fun schedule(t: Int) {
        val list = ArrayList(pending)
        pending.clear()
        bundles[t] = list
        val msg = Message.Turn(t, list).encode()
        for (p in peers) if (p.alive) p.link.send(msg)
    }

    /** Host: replaces every device's world by a snapshot of the host's, at the current turn. */
    private fun resync() {
        resyncNeeded = false
        resyncs++
        val snap = Message.snapshot(world)
        replaceWorld(Message.restore(snap))
        val msg = Message.Resync(turn, snap).encode()
        for (p in peers) if (p.alive) p.link.send(msg)
        hashes.clear()
        for (p in peers) p.reported.clear()
        validFrom = turn
    }

    private fun replaceWorld(w: World) {
        world = w
        onWorldReplaced?.invoke(w)
    }

    private fun receive(dt: Float) {
        if (isHost) admitNewcomers(dt)
        for (p in peers) {
            if (!p.alive) continue
            p.silent += dt
            while (p.alive) {
                val raw = p.link.poll() ?: break
                p.silent = 0f
                val m = try { Message.decode(raw) } catch (_: Exception) { continue }
                if (isHost) hostReceive(p, m) else clientReceive(m)
            }
            if (p.alive && (p.link.isClosed || p.silent > TIMEOUT)) dropped(p)
        }
    }

    private fun hostReceive(p: Peer, m: Message) {
        when (m) {
            is Message.Cmd -> pending += p.playerId to m.command
            is Message.Ack -> {
                p.acked = maxOf(p.acked, m.turn)
                if (m.turn >= validFrom) {
                    val mine = hashes[m.turn]
                    if (mine == null) { if (m.turn >= turn) p.reported[m.turn] = m.checksum }
                    else if (mine != m.checksum) resyncNeeded = true
                }
            }
            is Message.Bye -> left(p)
            else -> {}
        }
    }

    private fun clientReceive(m: Message) {
        when (m) {
            is Message.Turn -> bundles[m.turn] = m.commands
            is Message.Resync -> {
                resyncs++
                replaceWorld(Message.restore(m.snapshot))
                turn = m.turn
                bundles.keys.removeAll { it < m.turn }
            }
            is Message.Away -> awayTurns = m.players
            is Message.Bye -> left(peers[0])
            else -> {}
        }
    }

    /** The connection to [p] dropped without a goodbye. */
    private fun dropped(p: Peer) {
        p.link.close()
        if (isHost) {
            // Their civilization carries on; they have a while to come back.
            p.state = State.AWAY
            p.awayUntil = turn + FORFEIT_TURNS
            p.reported.clear()
            world.emit(GameEvent(EventType.INFO, localPlayer, "${p.name} disconnected"))
            broadcastAway()
        } else if (reconnect == null) {
            left(p)
        } else {
            p.state = State.AWAY
            disconnected = true
            disconnectedFor = 0f
            waitingFor = p.name
            world.emit(GameEvent(EventType.INFO, localPlayer, "Lost the connection to ${p.name}"))
            startReconnecting()
        }
    }

    /** [p] left on purpose (or, for a joined player, the host did). */
    private fun left(p: Peer) {
        p.state = State.GONE
        p.link.close()
        world.emit(GameEvent(EventType.INFO, localPlayer, "${p.name} left the game"))
        if (isHost) {
            // Everyone still connected resigns the player at the same turn.
            pending += p.playerId to Command.Resign
            broadcastAway()
            if (peers.all { it.state == State.GONE }) goOffline()
        } else {
            // The host is gone: keep playing alone.
            world.resign(p.playerId)
            goOffline()
        }
    }

    /** Host: players away for too long forfeit, on the same turn for everyone. */
    private fun forfeitAbsentees() {
        var changed = false
        for (p in peers) if (p.state == State.AWAY && turn >= p.awayUntil) {
            p.state = State.GONE
            pending += p.playerId to Command.Resign
            world.emit(GameEvent(EventType.INFO, localPlayer, "${p.name} didn't come back and forfeits"))
            changed = true
        }
        if (!changed) return
        broadcastAway()
        if (peers.all { it.state == State.GONE }) goOffline()
    }

    private fun broadcastAway() {
        val msg = Message.Away(peers.filter { it.state == State.AWAY }.map { it.playerId to it.awayUntil }).encode()
        for (p in peers) if (p.alive) p.link.send(msg)
    }

    /** Host: lets returning players back in; anyone else is turned away. */
    private fun admitNewcomers(dt: Float) {
        while (true) newcomers += Newcomer(incoming.poll() ?: break)
        for (n in newcomers.toList()) {
            n.age += dt
            val raw = n.link.poll()
            if (raw == null) {
                if (n.link.isClosed || n.age > TIMEOUT) { n.link.close(); newcomers.remove(n) }
                continue
            }
            newcomers.remove(n)
            val m = try { Message.decode(raw) } catch (_: Exception) { null }
            val version = when (m) { is Message.Rejoin -> m.version; is Message.Hello -> m.version; else -> -1 }
            val p = when (m) {
                is Message.Rejoin -> peers.firstOrNull { it.playerId == m.playerId && it.token == m.token }
                // A player who restarted the app finds the game again from the Join screen.
                is Message.Hello -> peers.firstOrNull { it.state == State.AWAY && it.joinName == m.name }
                else -> null
            }
            when {
                version != PROTOCOL_VERSION -> turnAway(n.link, "Different game versions: update both devices")
                p == null -> turnAway(n.link, if (m is Message.Hello) "The game has already started" else "You are no longer in this game")
                p.state == State.GONE -> turnAway(n.link, "You were away too long and forfeited")
                else -> rejoin(p, n.link)
            }
        }
    }

    private fun turnAway(link: Link, reason: String) {
        link.send(Message.Bye(reason))
        (link as? StreamLink)?.flushAndClose(300) ?: link.close()
    }

    /** Host: [p] is back on [link]; they continue from a snapshot of the current turn. */
    private fun rejoin(p: Peer, link: Link) {
        if (p.alive) p.link.close() // They noticed the drop before we did.
        p.link = link
        p.state = State.CONNECTED
        p.silent = 0f
        p.acked = turn - 1
        p.reported.clear()
        link.send(Message.Start(p.playerId, turn, Message.snapshot(world), p.token))
        // Turns already scheduled; later ones follow as usual.
        for (t in turn until turn + DELAY) bundles[t]?.let { link.send(Message.Turn(t, it)) }
        world.emit(GameEvent(EventType.INFO, localPlayer, "${p.name} reconnected"))
        broadcastAway()
    }

    /** Joined player: keeps trying to reach the host in the background. */
    private fun startReconnecting() {
        val connect = reconnect ?: return
        val mine = ++attempt
        Thread({
            while (disconnected && attempt == mine) {
                val link = try { connect() } catch (_: Exception) { null }
                if (link != null) {
                    if (disconnected && attempt == mine) {
                        link.send(Message.Rejoin(PROTOCOL_VERSION, localPlayer, token))
                        rejoinLink = link
                    } else link.close()
                    return@Thread
                }
                try { Thread.sleep(RETRY_MS) } catch (_: InterruptedException) { return@Thread }
            }
        }, "rejoin").apply { isDaemon = true }.start()
    }

    private fun whileDisconnected(dt: Float) {
        disconnectedFor += dt
        val link = rejoinLink
        if (link != null) {
            while (true) {
                val raw = link.poll() ?: break
                when (val m = try { Message.decode(raw) } catch (_: Exception) { null }) {
                    is Message.Start -> { rejoined(link, m); return }
                    is Message.Bye -> { rejoinLink = null; link.close(); forfeit(m.reason); return }
                    else -> {}
                }
            }
            if (link.isClosed) { rejoinLink = null; startReconnecting() }
        }
        if (disconnectedFor >= FORFEIT_SECONDS) forfeit("You were disconnected for too long and forfeited")
    }

    private fun rejoined(link: Link, m: Message.Start) {
        rejoinLink = null
        attempt++
        val host = peers[0]
        host.link = link
        host.state = State.CONNECTED
        host.silent = 0f
        disconnected = false
        waitingFor = null
        stalled = 0f
        accumulator = 0f
        bundles.clear()
        replaceWorld(Message.restore(m.snapshot))
        turn = m.turn
        world.emit(GameEvent(EventType.INFO, localPlayer, "Reconnected to ${host.name}"))
    }

    /** Joined player: out of the game; the rest of it carries on locally to watch. */
    private fun forfeit(reason: String) {
        disconnected = false
        attempt++
        rejoinLink?.close()
        rejoinLink = null
        forfeitReason = reason
        peers[0].state = State.GONE
        world.emit(GameEvent(EventType.INFO, localPlayer, reason))
        world.resign(localPlayer)
        offline = true
        waitingFor = null
        bundles.clear()
    }

    private fun goOffline() {
        if (offline) return
        offline = true
        waitingFor = null
        // Commands already scheduled still apply, in order, so nothing the player did is lost.
        for (t in bundles.keys.sorted()) if (t >= turn) for ((p, c) in bundles[t]!!) world.execute(p, c)
        bundles.clear()
        if (isHost) for ((p, c) in pending) world.execute(p, c)
        pending.clear()
        for (n in newcomers) n.link.close()
        newcomers.clear()
        while (true) (incoming.poll() ?: break).close()
    }

    /** Tells the other devices this one is leaving. */
    fun leave() {
        attempt++
        disconnected = false
        rejoinLink?.close()
        rejoinLink = null
        for (p in peers) if (p.alive) {
            p.link.send(Message.Bye("left"))
            (p.link as? StreamLink)?.flushAndClose() ?: p.link.close()
            p.state = State.GONE
        }
        offline = true
        while (true) (incoming.poll() ?: break).close()
    }

    companion object {
        const val TICKS_PER_TURN = 2
        val TURN_TIME = World.TICK * TICKS_PER_TURN
        /** Turns between issuing a command and it taking effect. */
        const val DELAY = 3
        /** How many turns the host may run ahead of the slowest player before waiting. */
        const val MAX_AHEAD = 8
        /** How long a player whose connection dropped has to come back before forfeiting. */
        const val FORFEIT_SECONDS = 120f
        val FORFEIT_TURNS = (FORFEIT_SECONDS / TURN_TIME).roundToInt()
        /** A connection silent for this many seconds is treated as dropped. */
        const val TIMEOUT = 8f
        const val PING_INTERVAL = 1f
        const val RETRY_MS = 1000L

        /**
         * Starts a game as host: [world] is the freshly generated game, [peers] the connected
         * players (each already assigned a player slot). Everyone, the host included, continues
         * from the same snapshot so that all devices start bit-for-bit identical.
         */
        fun host(world: World, localPlayer: Int, peers: List<Peer>): Lockstep {
            val snap = Message.snapshot(world)
            val ls = Lockstep(Message.restore(snap), localPlayer, true, peers)
            val rnd = SecureRandom()
            for (p in peers) {
                p.token = rnd.nextLong()
                p.link.send(Message.Start(p.playerId, 0, snap, p.token))
            }
            // The first few turns have no commands; send them so joined players can begin.
            for (t in 0 until DELAY) ls.schedule(t)
            return ls
        }

        /** Joins a game from the host's [start] message. */
        fun join(host: Link, hostName: String, start: Message.Start): Lockstep =
            Lockstep(Message.restore(start.snapshot), start.playerId, false, listOf(Peer(host, 0, hostName))).also {
                it.turn = start.turn
                it.token = start.token
            }

        /** A single-player game: commands apply immediately and time runs freely. */
        fun local(world: World, localPlayer: Int): Lockstep = Lockstep(world, localPlayer, true, emptyList()).also { it.offline = true }
    }
}
