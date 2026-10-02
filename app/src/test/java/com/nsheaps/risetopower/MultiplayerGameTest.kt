package com.nsheaps.risetopower

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.nsheaps.risetopower.core.Civ
import com.nsheaps.risetopower.core.Difficulty
import com.nsheaps.risetopower.core.GameSettings
import com.nsheaps.risetopower.core.MapSize
import com.nsheaps.risetopower.core.MapType
import com.nsheaps.risetopower.core.OrderType
import com.nsheaps.risetopower.core.PlayerSetup
import com.nsheaps.risetopower.core.UnitType
import com.nsheaps.risetopower.core.World
import com.nsheaps.risetopower.core.net.Lockstep
import com.nsheaps.risetopower.core.net.Message
import com.nsheaps.risetopower.core.net.StreamLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.ServerSocket
import java.net.Socket
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Plays a joined player's game screen against a host session running in the test. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h420dp-land-xhdpi")
class MultiplayerGameTest {
    private val w = 1800
    private val h = 840
    private val canvas = Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))

    private fun settings() = GameSettings(
        mapSize = MapSize.SMALL, mapType = MapType.CONTINENTAL, seed = 9L,
        players = listOf(
            PlayerSetup("Host", Civ.entries[0], true, 0, Difficulty.NORMAL, 0xFF3A7BD5.toInt()),
            PlayerSetup("Guest", Civ.entries[1], true, 1, Difficulty.NORMAL, 0xFFD63A3A.toInt()),
        ),
    )

    private fun linkPair(): Pair<StreamLink, StreamLink> = ServerSocket(0).use { server ->
        val a = Socket("127.0.0.1", server.localPort)
        val b = server.accept()
        StreamLink(a.getInputStream(), a.getOutputStream(), a, "host") to StreamLink(b.getInputStream(), b.getOutputStream(), b, "guest")
    }

    @Test
    fun guestCommandsReachTheHost() {
        val (hostSide, guestSide) = linkPair()
        val host = Lockstep.host(World(settings()), 0, listOf(Lockstep.Peer(hostSide, 1, "Guest")))
        val start = generateSequence { guestSide.take(2000) }.map { Message.decode(it) }.first { it is Message.Start } as Message.Start
        NetGame.hand(Lockstep.join(guestSide, "Host", start))

        val intent = Intent().putExtra(GameActivity.EXTRA_NET, true)
        val activity = Robolectric.buildActivity(GameActivity::class.java, intent).setup().get()
        activity.awaitLoaded()
        shadowOf(Looper.getMainLooper()).idle()
        val view = activity.view!!
        assertTrue(view.multiplayer)
        assertEquals(1, view.humanId)
        view.renderOffscreen(canvas, w, h)

        fun play(seconds: Float) {
            repeat((seconds / Lockstep.TURN_TIME).toInt()) {
                host.update(Lockstep.TURN_TIME)
                Thread.sleep(3)
                view.simulate(Lockstep.TURN_TIME)
            }
        }

        // Select a citizen and tap open ground next to it.
        val vill = view.world.units.first { it.owner == 1 && it.type == UnitType.VILLAGER }
        view.ui.selection.clear(); view.ui.selection += vill.id
        val (x0, y0) = vill.x to vill.y
        view.camera.centerOn(vill.x, vill.y)
        view.renderOffscreen(canvas, w, h)
        val cam = view.camera
        val (tx, ty) = (1..6).flatMap { r -> listOf(r to 0, 0 to r, -r to 0, 0 to -r) }
            .map { (dx, dy) -> vill.x + dx to vill.y + dy }
            .first { (x, y) -> view.picker.pick(cam.sx(x, y), cam.sy(x, y)) == null }
        val t = SystemClock.uptimeMillis()
        view.injectTouch(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cam.sx(tx, ty), cam.sy(tx, ty), 0))
        view.injectTouch(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, cam.sx(tx, ty), cam.sy(tx, ty), 0))
        view.renderOffscreen(canvas, w, h)

        // The order only takes effect once the host has scheduled it, on both devices alike.
        play(1.5f)
        // Let the guest finish the turns the host has already run, then compare like for like.
        val deadline = System.currentTimeMillis() + 5000
        while (view.session.turn < host.turn && System.currentTimeMillis() < deadline) {
            view.simulate(Lockstep.TURN_TIME / 8)
            Thread.sleep(1)
        }
        assertEquals(host.turn, view.session.turn)
        val onHost = host.world.get(vill.id) as com.nsheaps.risetopower.core.GameUnit
        val onGuest = view.world.get(vill.id) as com.nsheaps.risetopower.core.GameUnit
        assertTrue("host saw the move: ${onHost.order}", onHost.order == OrderType.MOVE || onHost.distanceTo(tx, ty) < 1.5f)
        assertTrue("citizen walked", onHost.distanceTo(x0, y0) > 0.3f)
        assertEquals(onHost.x, onGuest.x, 0f)
        assertEquals(onHost.y, onGuest.y, 0f)
        assertEquals(host.world.checksum(), view.world.checksum())
        assertEquals(0, host.resyncs)

        // Leaving tells the host, which carries on alone.
        view.session.leave()
        play(0.5f)
        assertTrue(host.offline)
        assertTrue(host.world.players[1].defeated)
    }

    @Test
    fun guestScreenShowsDisconnectedUntilItRejoins() {
        val (hostSide, guestSide) = linkPair()
        val host = Lockstep.host(World(settings()), 0, listOf(Lockstep.Peer(hostSide, 1, "Guest")))
        val start = generateSequence { guestSide.take(2000) }.map { Message.decode(it) }.first { it is Message.Start } as Message.Start
        val session = Lockstep.join(guestSide, "Host", start)
        val hostReachable = AtomicBoolean(false)
        session.reconnect = { if (hostReachable.get()) linkPair().let { (h, g) -> host.accept(h); g } else null }
        NetGame.hand(session)

        val activity = Robolectric.buildActivity(GameActivity::class.java, Intent().putExtra(GameActivity.EXTRA_NET, true)).setup().get()
        activity.awaitLoaded()
        shadowOf(Looper.getMainLooper()).idle()
        val view = activity.view!!
        fun play(seconds: Float) {
            repeat((seconds / Lockstep.TURN_TIME).toInt()) {
                host.update(Lockstep.TURN_TIME)
                Thread.sleep(3)
                view.simulate(Lockstep.TURN_TIME)
            }
        }
        play(1f)
        assertEquals(null, view.disconnectedSecondsLeft())

        // Bluetooth drops: the guest's screen says so while the host's game goes on.
        guestSide.close()
        play(1.5f)
        val left = view.disconnectedSecondsLeft()
        assertTrue("disconnected screen: $left", left != null && left > 100f)
        assertEquals(null, view.networkStatus())
        assertEquals(listOf("Guest"), host.away.map { it.name })
        val out = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        repeat(2) { view.renderOffscreen(Canvas(bmp), w, h) }
        FileOutputStream(File(out, "12_disconnected.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(view.hudForTest!!.buttons.any { it.rect.width() > 0 && it.enabled })

        hostReachable.set(true)
        val end = System.currentTimeMillis() + 10_000
        while (view.disconnectedSecondsLeft() != null && System.currentTimeMillis() < end) play(0.1f)
        assertEquals("rejoined", null, view.disconnectedSecondsLeft())
        play(1f)
        val deadline = System.currentTimeMillis() + 5000
        while (view.session.turn < host.turn && System.currentTimeMillis() < deadline) { view.simulate(Lockstep.TURN_TIME / 8); Thread.sleep(1) }
        assertEquals(host.turn, view.session.turn)
        assertEquals(host.world.checksum(), view.world.checksum())
        assertTrue(host.away.isEmpty())
        view.session.leave()
        host.leave()
    }
}
