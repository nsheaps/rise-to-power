package com.nsheaps.risetopower

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.nsheaps.risetopower.core.Civ
import com.nsheaps.risetopower.core.Difficulty
import com.nsheaps.risetopower.core.GameSettings
import com.nsheaps.risetopower.core.MapSize
import com.nsheaps.risetopower.core.MapType
import com.nsheaps.risetopower.core.PlayerSetup
import com.nsheaps.risetopower.core.World
import com.nsheaps.risetopower.core.net.Lockstep
import kotlin.random.Random

private const val TAG = "RiseToPower"

class GameActivity : Activity() {
    var view: GameView? = null
        private set
    private var sfx: Sfx? = null
    @Volatile var isResumedState = false
        private set

    private var loader: Thread? = null
    /** Host of a networked game: the Bluetooth listener that lets dropped players back in. */
    private var listener: java.io.Closeable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(loadingView())
        Immersive.apply(window)
        // Map generation and sound synthesis take too long for the main thread on slower
        // devices (input would time out and the system would kill the app), so build the
        // game in the background and swap the view in when it is ready.
        val load = intent.getBooleanExtra(EXTRA_LOAD, false)
        val online = intent.getBooleanExtra(EXTRA_NET, false)
        val net = if (online) NetGame.take() else null
        listener = net?.listener
        loader = Thread({
            val session = try {
                // A networked game whose connection was lost (e.g. the app was restarted) can't resume.
                if (online) net?.session else (if (load) SaveStore.load(this) else newWorld())?.let { w ->
                    Lockstep.local(w, w.players.indexOfFirst { it.isHuman }.coerceAtLeast(0))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create the game", e)
                null
            }
            val s = if (session != null) Sfx(this) else null
            runOnUiThread { onLoaded(session, s) }
        }, "game-loader").also { it.start() }
    }

    private fun onLoaded(session: Lockstep?, s: Sfx?) {
        if (isFinishing || isDestroyed) { s?.release(); session?.leave(); return }
        if (session == null || s == null) {
            Toast.makeText(this, "Could not start the game", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        sfx = s
        val v = GameView(this, session, s)
        view = v
        setContentView(v)
        Immersive.apply(window)
        if (isResumedState) v.start()
    }

    /** Waits for the background loader; used by tests before inspecting [view]. */
    fun awaitLoaded() {
        loader?.join()
    }

    private fun loadingView() = TextView(this).apply {
        text = "Preparing the land…"
        gravity = Gravity.CENTER
        textSize = 26f
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        setTextColor(0xFFE2B04A.toInt())
        setBackgroundColor(0xFF1B140E.toInt())
    }

    private fun newWorld(): World {
        // A fixed seed replays the same map and opponents (used by tests).
        val rnd = Random(if (intent.hasExtra(EXTRA_SEED)) intent.getLongExtra(EXTRA_SEED, 0L) else System.nanoTime())
        val civ = Civ.entries[intent.getIntExtra(EXTRA_CIV, 0)]
        val opponents = intent.getIntExtra(EXTRA_OPPONENTS, 1)
        val difficulty = Difficulty.entries[intent.getIntExtra(EXTRA_DIFFICULTY, 1)]
        val teams = intent.getIntExtra(EXTRA_TEAMS, 0)
        val names = LEADERS.shuffled(rnd)
        val players = ArrayList<PlayerSetup>()
        players += PlayerSetup("You", civ, true, 0, difficulty, COLORS[0])
        for (i in 1..opponents) {
            val team = when (teams) {
                1 -> 1
                2 -> if (i == 1) 0 else 1
                else -> i
            }
            val aiCiv = Civ.entries[rnd.nextInt(Civ.entries.size)]
            players += PlayerSetup(names[i - 1], aiCiv, false, team, difficulty, COLORS[i % COLORS.size])
        }
        val settings = GameSettings(
            mapSize = MapSize.entries[intent.getIntExtra(EXTRA_MAP_SIZE, 1)],
            mapType = MapType.entries[intent.getIntExtra(EXTRA_MAP_TYPE, 0)],
            seed = rnd.nextLong(),
            players = players,
            startingResources = intent.getIntExtra(EXTRA_RESOURCES, 200),
            wonderVictory = intent.getBooleanExtra(EXTRA_WONDER, true),
            revealMap = intent.getBooleanExtra(EXTRA_REVEAL, false),
        )
        return World(settings)
    }

    override fun onResume() {
        super.onResume()
        Immersive.apply(window)
        isResumedState = true
        view?.start()
    }

    override fun onPause() {
        isResumedState = false
        val v = view
        if (v != null) {
            v.stop()
            val w = v.world
            if (!v.multiplayer && !isFinishing && !w.gameOver && !w.players[v.humanId].defeated) {
                try { SaveStore.save(this, w) } catch (_: Exception) {}
            }
        }
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Immersive.apply(window)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val v = view
        if (v == null) finish() else v.requestPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Leaving the screen ends a networked game for this device.
        view?.let { v -> if (v.multiplayer) Thread { v.session.leave() }.start() }
        try { listener?.close() } catch (_: Exception) {}
        view?.layers?.recycle()
        sfx?.release()
    }

    companion object {
        const val EXTRA_LOAD = "load"
        const val EXTRA_NET = "net"
        const val EXTRA_SEED = "seed"
        const val EXTRA_CIV = "civ"
        const val EXTRA_OPPONENTS = "opponents"
        const val EXTRA_DIFFICULTY = "difficulty"
        const val EXTRA_MAP_SIZE = "mapSize"
        const val EXTRA_MAP_TYPE = "mapType"
        const val EXTRA_TEAMS = "teams"
        const val EXTRA_RESOURCES = "resources"
        const val EXTRA_WONDER = "wonder"
        const val EXTRA_REVEAL = "reveal"

        val COLORS = intArrayOf(
            0xFF3A7BD5.toInt(), 0xFFD63A3A.toInt(), 0xFF3AAA4A.toInt(), 0xFFE0C030.toInt(),
            0xFF9A4AD0.toInt(), 0xFFE07A20.toInt(), 0xFF30C0C0.toInt(), 0xFFE060A0.toInt(),
        )
        val LEADERS = listOf(
            "Caesar", "Alexander", "Cleopatra", "Genghis", "Qin Shi", "Boudica", "Ramses", "Pericles",
            "Charlemagne", "Saladin", "Elizabeth", "Montezuma", "Hannibal", "Wu Zetian",
        )
    }
}
