package com.nsheaps.risetopower.core

import com.nsheaps.risetopower.core.net.HostLobby
import com.nsheaps.risetopower.core.net.JoinLobby
import com.nsheaps.risetopower.core.net.Link
import com.nsheaps.risetopower.core.net.Lockstep
import com.nsheaps.risetopower.core.net.Message
import com.nsheaps.risetopower.core.net.NetOptions
import com.nsheaps.risetopower.core.net.PROTOCOL_VERSION
import com.nsheaps.risetopower.core.net.StreamLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class LockstepTest {
    /** Two humans and one AI on a small map. */
    private fun settings(seed: Long = 5L) = GameSettings(
        mapSize = MapSize.SMALL,
        mapType = MapType.CONTINENTAL,
        seed = seed,
        players = listOf(
            PlayerSetup("Host", Civ.entries[0], true, 0, Difficulty.HARD, 0xFF3A7BD5.toInt()),
            PlayerSetup("Guest", Civ.entries[1], true, 1, Difficulty.HARD, 0xFFD63A3A.toInt()),
            PlayerSetup("AI", Civ.entries[2], false, 2, Difficulty.HARD, 0xFF3AAA4A.toInt()),
        ),
    )

    /** A connected pair of links over loopback TCP, like a Bluetooth RFCOMM socket. */
    private fun linkPair(): Pair<StreamLink, StreamLink> {
        ServerSocket(0).use { server ->
            val a = Socket("127.0.0.1", server.localPort)
            val b = server.accept()
            return StreamLink(a.getInputStream(), a.getOutputStream(), a, "a") to StreamLink(b.getInputStream(), b.getOutputStream(), b, "b")
        }
    }

    private fun startGame(seed: Long = 5L): Triple<Lockstep, Lockstep, Pair<Link, Link>> {
        val (hostSide, guestSide) = linkPair()
        val host = Lockstep.host(World(settings(seed)), 0, listOf(Lockstep.Peer(hostSide, 1, "Guest")))
        val start = generateSequence { guestSide.take(2000) }.map { Message.decode(it) }.first { it is Message.Start } as Message.Start
        val guest = Lockstep.join(guestSide, "Host", start)
        return Triple(host, guest, hostSide to guestSide)
    }

    /** Runs both devices in real time for [seconds], optionally doing something every turn. */
    private fun play(host: Lockstep, guest: Lockstep, seconds: Float, each: (Int) -> Unit = {}) {
        val turns = (seconds / Lockstep.TURN_TIME).toInt()
        for (i in 0 until turns) {
            each(i)
            host.update(Lockstep.TURN_TIME)
            Thread.sleep(2)
            guest.update(Lockstep.TURN_TIME)
        }
    }

    /** Brings both devices to the same turn so their states can be compared. */
    private fun catchUp(host: Lockstep, guest: Lockstep) {
        val end = System.currentTimeMillis() + 10_000
        while (guest.turn != host.turn && System.currentTimeMillis() < end) {
            if (guest.turn < host.turn) guest.update(Lockstep.TURN_TIME / 8) else host.update(Lockstep.TURN_TIME / 8)
            Thread.sleep(1)
        }
        assertEquals("devices at the same turn", host.turn, guest.turn)
    }

    @Test
    fun bothDevicesSimulateTheSameGame() {
        val (host, guest) = startGame()
        assertEquals(1, guest.localPlayer)
        assertEquals(host.world.checksum(), guest.world.checksum())

        val hostVillagers = host.world.units.filter { it.owner == 0 && it.type == UnitType.VILLAGER }.map { it.id }
        val guestVillagers = guest.world.units.filter { it.owner == 1 && it.type == UnitType.VILLAGER }.map { it.id }
        val guestTc = guest.world.townCenters(1).first()
        play(host, guest, 40f) { i ->
            when (i) {
                5 -> host.issue(Command.Move(hostVillagers, 10f, 10f, false))
                8 -> guest.issue(Command.Train(guestTc.id, UnitType.VILLAGER))
                12 -> guest.issue(Command.Move(guestVillagers, guestTc.x + 6f, guestTc.y + 6f, false))
                30 -> {
                    val tree = guest.world.nodes.minBy { it.distanceTo(guestTc.x, guestTc.y) + if (it.kind == NodeKind.TREE) 0f else 1000f }
                    guest.issue(Command.Smart(guestVillagers.take(2), tree.x, tree.y, tree.id))
                }
            }
        }
        catchUp(host, guest)
        assertTrue("game ran", host.turn > 300)
        assertEquals("no resync needed", 0, host.resyncs)
        assertEquals(host.world.checksum(), guest.world.checksum())
        // The guest's commands reached the host's simulation too.
        assertTrue("guest trained a citizen", host.world.units.count { it.owner == 1 && it.type == UnitType.VILLAGER } > guestVillagers.size)
        assertTrue("guest citizens gather wood", host.world.units.any { it.owner == 1 && it.gatherResource == ResourceType.WOOD })
        host.leave(); guest.leave()
    }

    @Test
    fun desyncIsRepairedFromTheHostSnapshot() {
        val (host, guest) = startGame(9L)
        play(host, guest, 3f)
        // Corrupt the guest's world, as a device computing something differently would.
        guest.world.players[1].stock[0] += 37f
        play(host, guest, 3f)
        catchUp(host, guest)
        assertTrue("host noticed the desync", host.resyncs >= 1)
        assertEquals(host.world.checksum(), guest.world.checksum())
        assertEquals(host.world.players[1].stock[0], guest.world.players[1].stock[0])
        host.leave(); guest.leave()
    }

    @Test
    fun gameContinuesWhenTheOtherDeviceLeaves() {
        val (host, guest, links) = startGame(11L)
        play(host, guest, 2f)
        guest.leave()
        val end = System.currentTimeMillis() + 5000
        while (!host.offline && System.currentTimeMillis() < end) { host.update(Lockstep.TURN_TIME); Thread.sleep(2) }
        assertTrue(host.offline)
        host.update(Lockstep.TURN_TIME)
        assertTrue("guest resigned", host.world.players[1].defeated)
        val t = host.world.tick
        host.update(1f)
        assertTrue("host keeps playing", host.world.tick > t)
        assertTrue(links.first.isClosed)
    }

    @Test
    fun droppedPlayerRejoinsWhileTheirCivilizationCarriesOn() {
        val (host, guest, links) = startGame(15L)
        // Reconnecting opens a fresh connection, which the host's listener hands to the game.
        val hostReachable = AtomicBoolean(false)
        guest.reconnect = {
            if (!hostReachable.get()) null else linkPair().let { (h, g) -> host.accept(h); g }
        }
        val villagers = guest.world.units.filter { it.owner == 1 && it.type == UnitType.VILLAGER }.map { it.id }
        val tc = guest.world.townCenters(1).first()
        play(host, guest, 0.6f) { i -> if (i == 0) guest.issue(Command.Move(villagers, tc.x + 9f, tc.y + 9f, false)) }

        // Bluetooth drops.
        links.second.close()
        val walker = host.world.get(villagers.first()) as GameUnit
        val (x0, y0) = walker.x to walker.y
        play(host, guest, 0.5f)
        val frozenAt = guest.turn
        val hostTurn = host.turn
        play(host, guest, 2f)
        assertNotNull("guest shows it is disconnected", guest.reconnectSecondsLeft)
        assertEquals("guest's game is on hold", frozenAt, guest.turn)
        assertTrue("host keeps playing", host.turn >= hostTurn + 15)
        assertEquals(listOf("Guest"), host.away.map { it.name })
        assertTrue("seconds left counts down", host.away.single().secondsLeft in 100f..Lockstep.FORFEIT_SECONDS)
        assertTrue("guest's citizens keep walking", walker.distanceTo(x0, y0) > 1f)
        guest.issue(Command.Stop(villagers)) // ignored: nobody to send it to

        hostReachable.set(true)
        val end = System.currentTimeMillis() + 10_000
        while (guest.reconnectSecondsLeft != null && System.currentTimeMillis() < end) play(host, guest, 0.1f)
        assertEquals("reconnected", null, guest.reconnectSecondsLeft)
        play(host, guest, 2f)
        catchUp(host, guest)
        assertEquals(host.world.checksum(), guest.world.checksum())
        assertTrue(host.away.isEmpty())
        assertTrue(guest.away.isEmpty())
        assertFalse(host.world.players[1].defeated)
        assertFalse(guest.offline)
        // Commands flow again after rejoining.
        guest.issue(Command.Move(villagers, tc.x, tc.y, false))
        play(host, guest, 1f)
        catchUp(host, guest)
        assertEquals(host.world.checksum(), guest.world.checksum())
        host.leave(); guest.leave()
    }

    @Test
    fun playerAwayForTwoMinutesForfeits() {
        val (host, guest, links) = startGame(17L)
        guest.reconnect = { null }
        play(host, guest, 0.5f)
        links.second.close()
        repeat(Lockstep.FORFEIT_TURNS - 20) { host.update(Lockstep.TURN_TIME); guest.update(Lockstep.TURN_TIME) }
        assertFalse("still time to come back", host.world.players[1].defeated)
        assertNotNull(guest.reconnectSecondsLeft)
        repeat(40) { host.update(Lockstep.TURN_TIME); guest.update(Lockstep.TURN_TIME) }
        assertTrue("host resigned the absent player", host.world.players[1].defeated)
        assertTrue("host plays on alone", host.offline)
        assertNotNull(guest.forfeitReason)
        assertEquals(null, guest.reconnectSecondsLeft)
        assertTrue("guest sees its own defeat", guest.world.players[1].defeated)
        host.leave(); guest.leave()
    }

    @Test
    fun playerWhoRestartedTheAppRejoinsFromTheJoinScreen() {
        val (host, guest, links) = startGame(19L)
        play(host, guest, 0.5f)
        // The app was closed: the connection drops and the old session is gone.
        links.second.close()
        repeat(10) { host.update(Lockstep.TURN_TIME); Thread.sleep(2) }
        assertEquals(listOf("Guest"), host.away.map { it.name })

        // Someone else can't take the seat.
        val (h1, g1) = linkPair()
        host.accept(h1)
        val stranger = JoinLobby(g1, "Host", "Zoe", 0)
        waitFor("stranger turned away") { host.update(Lockstep.TURN_TIME / 8); stranger.poll(); stranger.closedReason }

        val (h2, g2) = linkPair()
        host.accept(h2)
        val lobby = JoinLobby(g2, "Host", "Guest", 1)
        val back = waitFor("rejoined") { host.update(Lockstep.TURN_TIME / 8); lobby.poll() }
        assertEquals(1, back.localPlayer)
        play(host, back, 1f)
        catchUp(host, back)
        assertEquals(host.world.checksum(), back.world.checksum())
        assertTrue(host.away.isEmpty())
        host.leave(); back.leave()
        guest.leave()
    }

    @Test
    fun hostWaitsForASlowGuest() {
        val (host, guest) = startGame(13L)
        // The guest stops processing; the host must not run away from it.
        repeat(60) { host.update(Lockstep.TURN_TIME); Thread.sleep(1) }
        assertTrue("host held at ${host.turn}", host.turn <= Lockstep.MAX_AHEAD + 1)
        assertEquals("Guest", host.waitingFor)
        catchUp(host, guest)
        play(host, guest, 1f)
        assertEquals(null, host.waitingFor)
        host.leave(); guest.leave()
    }

    @Test
    fun commandsCanOnlyMoveTheirOwnersUnits() {
        val w = World(settings())
        val hostUnit = w.units.first { it.owner == 0 }
        val x = hostUnit.x
        w.execute(1, Command.Move(listOf(hostUnit.id), x + 5f, hostUnit.y, false))
        w.execute(1, Command.Delete(listOf(hostUnit.id)))
        repeat(40) { w.update(World.TICK) }
        assertTrue(hostUnit.alive)
        assertEquals(x, hostUnit.x, 0.5f)
    }

    @Test
    fun commandsAndMessagesRoundTrip() {
        val cmds = listOf(
            Command.Smart(listOf(1, 2), 3.5f, 4.25f, 9), Command.Move(listOf(7), 1f, 2f, true), Command.Stop(listOf(3)),
            Command.Return(listOf(4, 5)), Command.Delete(listOf(6)), Command.Place(BuildingType.HOUSE, 10, 12, listOf(1)),
            Command.Wall(1, 2, 3, 4, listOf(5)), Command.Train(8, UnitType.ARCHER), Command.Research(8, Tech.entries.last()),
            Command.Advance(8), Command.CancelQueue(8, 1), Command.Rally(listOf(8, 9), 5f, 6f),
            Command.Trade(ResourceType.STONE, false), Command.Resign,
        )
        for (c in cmds) {
            val bytes = ByteArrayOutputStream().also { c.write(DataOutputStream(it)) }.toByteArray()
            assertEquals(c, Command.read(DataInputStream(ByteArrayInputStream(bytes))))
        }
        val turn = Message.Turn(42, cmds.mapIndexed { i, c -> i % 3 to c })
        assertEquals(turn, Message.decode(turn.encode()))
        val hello = Message.Hello(1, "Ana", 3)
        assertEquals(hello, Message.decode(hello.encode()))
        for (m in listOf(Message.Rejoin(2, 3, -77L), Message.Ping, Message.Away(listOf(1 to 1200, 3 to 5)))) {
            assertEquals(m, Message.decode(m.encode()))
        }
        val start = Message.decode(Message.Start(2, 17, byteArrayOf(1, 2, 3), 99L).encode()) as Message.Start
        assertEquals("2 17 99", "${start.playerId} ${start.turn} ${start.token}")
        assertEquals(listOf<Byte>(1, 2, 3), start.snapshot.toList())
    }

    @Test
    fun snapshotRestoresAnIdenticalWorld() {
        val w = World(settings(21L))
        repeat(200) { w.update(World.TICK) }
        val a = Message.restore(Message.snapshot(w))
        val b = Message.restore(Message.snapshot(w))
        assertEquals(a.checksum(), b.checksum())
        repeat(400) { a.update(World.TICK); b.update(World.TICK) }
        assertEquals("worlds restored from one snapshot stay identical", a.checksum(), b.checksum())
        assertNotNull(a.townCenters(0).firstOrNull())
        assertFalse(a.gameOver)
    }

    private fun <T> waitFor(what: String, f: () -> T?): T {
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) {
            f()?.let { return it }
            Thread.sleep(5)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun lobbyStartsAThreePlayerGame() {
        val lobby = HostLobby("Ana", 0)
        val (h1, g1) = linkPair()
        val (h2, g2) = linkPair()
        lobby.add(h1); lobby.add(h2)
        val ben = JoinLobby(g1, "Ana", "Ben", 2)
        val ben2 = JoinLobby(g2, "Ana", "Ben", 3)
        waitFor("introductions") { lobby.poll(); if (lobby.joined.size == 2) true else null }
        assertEquals("names are made unique", listOf("Ben", "Ben 2"), lobby.joined.sorted())
        waitFor("lobby list") { ben.poll(); if (ben.players.size == 3) true else null }
        assertEquals(listOf("Ana", "Ben", "Ben 2"), listOf(ben.players[0]) + ben.players.drop(1).sorted())

        val colors = intArrayOf(1, 2, 3, 4)
        val host = lobby.start(NetOptions(mapSize = MapSize.SMALL, aiPlayers = 1, coop = true, seed = 3L), colors, listOf("Caesar"), listOf(4))
        val s1 = waitFor("start 1") { ben.poll() }
        val s2 = waitFor("start 2") { ben2.poll() }
        assertEquals(1, s1.localPlayer); assertEquals(2, s2.localPlayer)
        val w = host.world
        assertEquals(4, w.players.size)
        assertEquals(listOf(true, true, true, false), w.players.map { it.isHuman })
        assertEquals("co-op: humans share a team", listOf(0, 0, 0, 1), w.players.map { it.team })
        assertEquals(Civ.entries[2], w.players[1].civ)

        val v1 = s1.world.units.filter { it.owner == 1 }.map { it.id }
        val v2 = s2.world.units.filter { it.owner == 2 }.map { it.id }
        for (i in 0 until 150) {
            if (i == 3) s1.issue(Command.Move(v1, 20f, 20f, false))
            if (i == 4) s2.issue(Command.Move(v2, 25f, 25f, true))
            host.update(Lockstep.TURN_TIME); Thread.sleep(1)
            s1.update(Lockstep.TURN_TIME); s2.update(Lockstep.TURN_TIME)
        }
        val end = System.currentTimeMillis() + 10_000
        while ((s1.turn != host.turn || s2.turn != host.turn) && System.currentTimeMillis() < end) {
            if (s1.turn < host.turn) s1.update(Lockstep.TURN_TIME / 8)
            if (s2.turn < host.turn) s2.update(Lockstep.TURN_TIME / 8)
            Thread.sleep(1)
        }
        assertEquals(host.turn, s1.turn); assertEquals(host.turn, s2.turn)
        assertEquals(0, host.resyncs)
        assertEquals(host.world.checksum(), s1.world.checksum())
        assertEquals(host.world.checksum(), s2.world.checksum())
        assertTrue(host.world.units.filter { it.owner == 2 }.any { it.order == OrderType.ATTACK_MOVE || it.amX == 25f || it.x > 20f })
        host.leave(); s1.leave(); s2.leave()
    }

    @Test
    fun lobbyTurnsAwayMismatchedVersionsAndFullGames() {
        val lobby = HostLobby("Ana", 0, maxGuests = 1)
        val (h1, g1) = linkPair()
        lobby.add(h1)
        g1.send(Message.Hello(PROTOCOL_VERSION + 1, "Old", 0).encode())
        val bye = waitFor("rejection") { lobby.poll(); g1.poll()?.let { Message.decode(it) } }
        assertTrue(bye is Message.Bye)
        waitFor("removed") { lobby.poll(); if (lobby.joined.isEmpty() && h1.isClosed) true else null }

        val (h2, g2) = linkPair()
        val (h3, g3) = linkPair()
        lobby.add(h2)
        val ok = JoinLobby(g2, "Ana", "Ben", 0)
        waitFor("joined") { lobby.poll(); if (lobby.joined.size == 1) true else null }
        lobby.add(h3)
        val late = JoinLobby(g3, "Ana", "Cy", 0)
        waitFor("full") { lobby.poll(); late.poll(); late.closedReason }
        assertEquals("The game is full", late.closedReason)
        assertEquals(listOf("Ben"), lobby.joined)
        ok.close()
        waitFor("left") { lobby.poll(); if (lobby.joined.isEmpty()) true else null }
        lobby.close()
    }
}
