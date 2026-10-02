package com.nsheaps.risetopower

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import com.nsheaps.risetopower.core.Civ
import com.nsheaps.risetopower.core.Difficulty
import com.nsheaps.risetopower.core.MapSize
import com.nsheaps.risetopower.core.MapType
import com.nsheaps.risetopower.core.net.HostLobby
import com.nsheaps.risetopower.core.net.JoinLobby
import com.nsheaps.risetopower.core.net.Lockstep
import com.nsheaps.risetopower.core.net.NetOptions
import kotlin.random.Random

/**
 * Bluetooth multiplayer screens: choose to host or join, the host's lobby with game options,
 * and the joining player's list of nearby phones.
 */
@SuppressLint("MissingPermission") // Every Bluetooth call happens after [withBluetooth] checked permissions.
class MultiplayerMenu(private val a: MainActivity) {
    private val handler = Handler(Looper.getMainLooper())
    private var poller: Runnable? = null
    private var server: Bluetooth.Server? = null
    private var hostLobby: HostLobby? = null
    private var joinLobby: JoinLobby? = null
    private var receiver: BroadcastReceiver? = null
    private var pending: (() -> Unit)? = null
    @Volatile private var connecting: Thread? = null

    val inLobby get() = a.root.tag == TAG_HOST || a.root.tag == TAG_JOIN

    fun showMenu() {
        leave()
        a.root.removeAllViews()
        a.root.tag = TAG_MENU
        val col = a.column()
        col.addView(a.title("Multiplayer", 32f))
        col.addView(a.subtitle("Play with friends nearby over Bluetooth. One phone hosts the game and the others join it."))
        col.addView(a.space(12))
        col.addView(a.menuButton("Host Game") { withBluetooth { showHost() } })
        col.addView(a.menuButton("Join Game") { withBluetooth { showJoin() } })
        col.addView(a.menuButton("Back") { a.showMainMenu() })
        col.addView(a.space(8))
        col.addView(a.subtitle("Tip: pairing the phones in Bluetooth settings first makes the host easy to find.", 12f))
        a.root.addView(a.scroll(col))
    }

    // ------------------------------------------------------------------ host

    private fun showHost() {
        leave()
        a.root.removeAllViews()
        a.root.tag = TAG_HOST
        val me = Bluetooth.localName(a)
        val col = a.column()
        col.addView(a.title("Host a Game", 32f))
        val status = a.subtitle("Waiting for players. On their phones, choose Multiplayer › Join Game and pick “$me”.", 14f)
        col.addView(status)
        val players = a.subtitle("", 15f).apply { setTextColor(0xFFF5E9CF.toInt()) }
        col.addView(players)

        val civ = a.option("civ", Civ.entries.map { it.displayName }, 0)
        val ais = a.option("mpAis", (0..6).map { if (it == 0) "None" else "$it" }, 0)
        val difficulty = a.option("difficulty", Difficulty.entries.map { it.displayName }, 1)
        val mapSize = a.option("mapSize", MapSize.entries.map { "${it.displayName} (${it.tiles}x${it.tiles})" }, 1)
        val mapType = a.option("mapType", MapType.entries.map { it.displayName }, 0)
        val teams = a.option("mpTeams", listOf("Free for all", "Players vs AIs"), 0)
        val resources = a.option("resources", MainActivity.RESOURCE_LEVELS.map { it.first }, 0)
        col.addView(a.row("Civilization", civ.view))
        col.addView(a.row("AI opponents", ais.view))
        col.addView(a.row("AI difficulty", difficulty.view))
        col.addView(a.row("Map size", mapSize.view))
        col.addView(a.row("Map type", mapType.view))
        col.addView(a.row("Teams", teams.view))
        col.addView(a.row("Starting resources", resources.view))

        val lobby = HostLobby(me, civ.index)
        hostLobby = lobby
        civ.onChange = { lobby.hostCiv = civ.index }
        val srv = Bluetooth.Server(a) { link -> lobby.add(link) }
        server = srv
        if (!srv.isListening) status.text = "Couldn't start hosting. Turn Bluetooth off and on, then try again."

        val start = a.menuButton("Start", 160) {}
        fun refresh() {
            val names = lobby.joined
            players.text = "Players: ${(listOf("$me (you)") + names).joinToString(", ")}"
            start.isEnabled = names.isNotEmpty()
            start.alpha = if (start.isEnabled) 1f else 0.5f
        }
        refresh()
        start.setOnClickListener {
            if (lobby.joined.isEmpty()) return@setOnClickListener
            stopPolling()
            status.text = "Starting…"
            start.isEnabled = false
            val size = MapSize.entries[mapSize.index]
            val options = NetOptions(
                mapSize = size,
                mapType = MapType.entries[mapType.index],
                aiPlayers = ais.index.coerceAtMost(lobby.maxAis(size)),
                difficulty = Difficulty.entries[difficulty.index],
                coop = teams.index == 1,
                startingResources = MainActivity.RESOURCE_LEVELS[resources.index].second,
                seed = Random.nextLong(),
            )
            val rnd = Random(System.nanoTime())
            Thread({
                val session = lobby.start(options, GameActivity.COLORS, GameActivity.LEADERS.shuffled(rnd), List(8) { rnd.nextInt(Civ.entries.size) })
                a.runOnUiThread {
                    // The host backed out while the map was generating.
                    if (a.root.tag != TAG_HOST) Thread { session.leave() }.start() else launch(session)
                }
            }, "host-start").start()
        }
        val visible = a.menuButton("Be visible", 160) { requestVisible() }
        a.showPinned(col, a.menuButton("Back", 120) { showMenu() }, visible, start)
        poll(250) { if (lobby.poll()) refresh() }
        // Let phones that were never paired with this one find it.
        if (Bluetooth.adapter(a)?.scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) requestVisible()
    }

    private fun requestVisible() {
        val i = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        try { a.startActivityForResult(i, REQ_VISIBLE) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ join

    private fun showJoin() {
        leave()
        a.root.removeAllViews()
        a.root.tag = TAG_JOIN
        val col = a.column()
        col.addView(a.title("Join a Game", 32f))
        val status = a.subtitle("Pick the phone that is hosting. The host must have Host Game open.", 14f)
        col.addView(status)
        val civ = a.option("civ", Civ.entries.map { it.displayName }, 0)
        col.addView(a.row("Civilization", civ.view))
        col.addView(a.space(6))
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL }
        col.addView(list)
        val seen = HashSet<String>()

        fun connect(d: BluetoothDevice) {
            if (connecting != null || joinLobby != null) return
            val name = Bluetooth.deviceName(d)
            status.text = "Connecting to $name…"
            connecting = Thread({
                val link = try { Bluetooth.connect(a, d) } catch (_: Exception) { null }
                a.runOnUiThread {
                    connecting = null
                    if (a.root.tag != TAG_JOIN) { link?.close(); return@runOnUiThread }
                    if (link == null) {
                        status.text = "Couldn't connect to $name. Is it hosting a game?"
                        return@runOnUiThread
                    }
                    val lobby = JoinLobby(link, name, Bluetooth.localName(a), civ.index)
                    joinLobby = lobby
                    list.removeAllViews()
                    status.text = "Connected to $name. Waiting for the host to start…"
                    poll(150) {
                        val session = lobby.poll()
                        when {
                            session != null -> { stopPolling(); joinLobby = null; launch(session, d) }
                            lobby.closedReason != null -> { stopPolling(); joinLobby = null; status.text = lobby.closedReason; showDevices(list, seen, ::connect) }
                            else -> status.text = "Connected to $name. Waiting for the host to start…\nPlayers: ${lobby.players.joinToString(", ")}"
                        }
                    }
                }
            }, "bt-connect").also { it.start() }
        }

        showDevices(list, seen, ::connect)
        val scan = a.menuButton("Scan again", 180) { startDiscovery() }
        a.showPinned(col, a.menuButton("Back", 140) { showMenu() }, scan)

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_FOUND) return
                @Suppress("DEPRECATION")
                val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (joinLobby == null && seen.add(d.address)) list.addView(deviceButton(d, ::connect))
            }
        }.also {
            val f = IntentFilter(BluetoothDevice.ACTION_FOUND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) a.registerReceiver(it, f, Context.RECEIVER_EXPORTED) else a.registerReceiver(it, f)
        }
        startDiscovery()
    }

    private fun showDevices(list: LinearLayout, seen: HashSet<String>, onPick: (BluetoothDevice) -> Unit) {
        list.removeAllViews()
        seen.clear()
        val paired = Bluetooth.pairedDevices(a)
        if (paired.isNotEmpty()) list.addView(a.subtitle("Paired phones", 13f))
        for (d in paired) if (seen.add(d.address)) list.addView(deviceButton(d, onPick))
        list.addView(a.subtitle("Nearby phones appear here as they are found.", 13f))
    }

    private fun deviceButton(d: BluetoothDevice, onPick: (BluetoothDevice) -> Unit): Button =
        a.styledButton(Bluetooth.deviceName(d), 320) { onPick(d) }

    private fun startDiscovery() {
        val ad = Bluetooth.adapter(a) ?: return
        try {
            if (ad.isDiscovering) ad.cancelDiscovery()
            ad.startDiscovery()
        } catch (_: SecurityException) {}
    }

    // ------------------------------------------------------------------ shared

    /** Starts the game screen; a joined player reconnects to [host] if the connection drops. */
    private fun launch(session: Lockstep, host: BluetoothDevice? = null) {
        // The host keeps listening during the game so that players who drop out can come back.
        val listener = if (session.isHost) server?.also { s -> s.onJoin = { link -> session.accept(link) } } else null
        if (listener != null) server = null
        if (host != null) {
            val ctx = a.applicationContext
            session.reconnect = { Bluetooth.connect(ctx, host) }
        }
        // The connections now belong to the game; drop them from the lobby without closing.
        hostLobby = null
        joinLobby = null
        leave()
        NetGame.hand(session, listener)
        a.showMainMenu()
        a.startActivity(Intent(a, GameActivity::class.java).putExtra(GameActivity.EXTRA_NET, true))
    }

    private fun poll(intervalMs: Long, f: () -> Unit) {
        stopPolling()
        val r = object : Runnable {
            override fun run() {
                f()
                if (poller === this) handler.postDelayed(this, intervalMs)
            }
        }
        poller = r
        handler.post(r)
    }

    private fun stopPolling() {
        poller?.let { handler.removeCallbacks(it) }
        poller = null
    }

    /** Closes the lobby: stops listening, scanning and polling, and hangs up any connection. */
    fun leave() {
        stopPolling()
        server?.close(); server = null
        hostLobby?.let { l -> Thread { l.close() }.start() }; hostLobby = null
        joinLobby?.let { l -> Thread { l.close() }.start() }; joinLobby = null
        receiver?.let { try { a.unregisterReceiver(it) } catch (_: Exception) {} }; receiver = null
        try { Bluetooth.adapter(a)?.cancelDiscovery() } catch (_: SecurityException) {}
    }

    /** Runs [then] once Bluetooth permissions are granted and Bluetooth is on. */
    private fun withBluetooth(then: () -> Unit) {
        val ad = Bluetooth.adapter(a)
        if (ad == null) { toast("This device has no Bluetooth"); return }
        pending = then
        when {
            !Bluetooth.hasPermissions(a) -> a.requestPermissions(Bluetooth.permissions(), REQ_PERMISSIONS)
            !ad.isEnabled -> try { a.startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE) } catch (_: Exception) { toast("Turn on Bluetooth to play together") }
            else -> { pending = null; then() }
        }
    }

    fun onPermissionsResult(requestCode: Int) {
        if (requestCode != REQ_PERMISSIONS) return
        val then = pending ?: return
        if (Bluetooth.hasPermissions(a)) withBluetooth(then)
        else { pending = null; toast("Bluetooth permission is needed to play together") }
    }

    fun onActivityResult(requestCode: Int) {
        if (requestCode != REQ_ENABLE) return
        val then = pending ?: return
        if (Bluetooth.adapter(a)?.isEnabled == true) withBluetooth(then) else pending = null
    }

    private fun toast(s: String) = android.widget.Toast.makeText(a, s, android.widget.Toast.LENGTH_LONG).show()

    companion object {
        const val TAG_MENU = "mp"
        const val TAG_HOST = "mpHost"
        const val TAG_JOIN = "mpJoin"
        const val REQ_PERMISSIONS = 41
        const val REQ_ENABLE = 42
        const val REQ_VISIBLE = 43
    }
}
