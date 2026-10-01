package com.nsheaps.risetopower

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import com.nsheaps.risetopower.core.Civ
import com.nsheaps.risetopower.core.Difficulty
import com.nsheaps.risetopower.core.GameSettings
import com.nsheaps.risetopower.core.MapSize
import com.nsheaps.risetopower.core.MapType
import com.nsheaps.risetopower.core.PlayerSetup
import com.nsheaps.risetopower.core.World
import kotlin.random.Random

class GameActivity : Activity() {
    var view: GameView? = null
        private set
    private var sfx: Sfx? = null
    @Volatile var isResumedState = false
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Immersive.apply(window)
        val world = if (intent.getBooleanExtra(EXTRA_LOAD, false)) {
            SaveStore.load(this) ?: run {
                Toast.makeText(this, "Could not load the saved game", Toast.LENGTH_LONG).show()
                finish()
                return
            }
        } else newWorld()
        val s = Sfx(this)
        sfx = s
        val v = GameView(this, world, s)
        view = v
        setContentView(v)
    }

    private fun newWorld(): World {
        val rnd = Random(System.nanoTime())
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
            if (!isFinishing && !w.gameOver && !w.players[v.humanId].defeated) {
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
        view?.requestPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        view?.layers?.recycle()
        sfx?.release()
    }

    companion object {
        const val EXTRA_LOAD = "load"
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
