package com.nsheaps.risetopower.core.net

import com.nsheaps.risetopower.core.Command
import com.nsheaps.risetopower.core.EventType
import com.nsheaps.risetopower.core.GameEvent
import com.nsheaps.risetopower.core.World

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
 * If the connection drops the game carries on locally: the host resigns players it lost, and a
 * player who loses the host keeps playing on their own.
 */
class Lockstep private constructor(
    world: World,
    val localPlayer: Int,
    val isHost: Boolean,
    private val peers: List<Peer>,
) {
    class Peer(val link: Link, val playerId: Int, val name: String) {
        var acked = -1
        var alive = true
        /** Checksums reported for turns the host has not finished yet. */
        val reported = HashMap<Int, Long>()
    }

    var world: World = world
        private set

    /** Called on the game thread when the world is replaced by a resync snapshot. */
    var onWorldReplaced: ((World) -> Unit)? = null

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

    private val bundles = HashMap<Int, List<Pair<Int, Command>>>()
    private val pending = ArrayList<Pair<Int, Command>>()
    private val hashes = HashMap<Int, Long>()
    private var resyncNeeded = false
    /** Acks for turns before this were computed on a replaced world and are ignored. */
    private var validFrom = 0
    private var accumulator = 0f
    private var stalled = 0f

    /** Issues a command for the local player. */
    fun issue(c: Command) {
        when {
            offline -> world.execute(localPlayer, c)
            isHost -> pending += localPlayer to c
            else -> peers[0].link.send(Message.Cmd(c))
        }
    }

    /** Advances real time by [dt] seconds; returns the number of simulation ticks run. */
    fun update(dt: Float): Int {
        receive()
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
            val slow = peers.firstOrNull { it.alive && it.acked < turn - MAX_AHEAD }
            if (slow != null) { waitingFor = slow.name; return false }
            if (resyncNeeded) resync()
            schedule(turn + DELAY)
        } else if (!bundles.containsKey(turn)) {
            waitingFor = "host"
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
        // Joined players keep a few used turns: a resync may rewind them to a turn they already ran.
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

    private fun receive() {
        for (p in peers) {
            if (!p.alive) continue
            while (true) {
                val raw = p.link.poll() ?: break
                val m = try { Message.decode(raw) } catch (_: Exception) { continue }
                if (isHost) hostReceive(p, m) else clientReceive(m)
            }
            if (p.link.isClosed && p.alive) lost(p)
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
            is Message.Bye -> lost(p)
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
            is Message.Bye -> lost(peers[0])
            else -> {}
        }
    }

    private fun lost(p: Peer) {
        p.alive = false
        p.link.close()
        world.emit(GameEvent(EventType.INFO, localPlayer, "${p.name} left the game"))
        if (isHost) {
            // Everyone still connected resigns the player at the same turn.
            pending += p.playerId to Command.Resign
            if (peers.none { it.alive }) goOffline()
        } else {
            // The host is gone: keep playing alone.
            world.resign(p.playerId)
            goOffline()
        }
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
    }

    /** Tells the other devices this one is leaving. */
    fun leave() {
        for (p in peers) if (p.alive) {
            p.link.send(Message.Bye("left"))
            (p.link as? StreamLink)?.flushAndClose() ?: p.link.close()
            p.alive = false
        }
        offline = true
    }

    companion object {
        const val TICKS_PER_TURN = 2
        val TURN_TIME = World.TICK * TICKS_PER_TURN
        /** Turns between issuing a command and it taking effect. */
        const val DELAY = 3
        /** How many turns the host may run ahead of the slowest player before waiting. */
        const val MAX_AHEAD = 8

        /**
         * Starts a game as host: [world] is the freshly generated game, [peers] the connected
         * players (each already assigned a player slot). Everyone, the host included, continues
         * from the same snapshot so that all devices start bit-for-bit identical.
         */
        fun host(world: World, localPlayer: Int, peers: List<Peer>): Lockstep {
            val snap = Message.snapshot(world)
            val ls = Lockstep(Message.restore(snap), localPlayer, true, peers)
            for (p in peers) p.link.send(Message.Start(p.playerId, 0, snap))
            // The first few turns have no commands; send them so joined players can begin.
            for (t in 0 until DELAY) ls.schedule(t)
            return ls
        }

        /** Joins a game from the host's [start] message. */
        fun join(host: Link, hostName: String, start: Message.Start): Lockstep =
            Lockstep(Message.restore(start.snapshot), start.playerId, false, listOf(Peer(host, 0, hostName))).also {
                it.turn = start.turn
            }

        /** A single-player game: commands apply immediately and time runs freely. */
        fun local(world: World, localPlayer: Int): Lockstep = Lockstep(world, localPlayer, true, emptyList()).also { it.offline = true }
    }
}
