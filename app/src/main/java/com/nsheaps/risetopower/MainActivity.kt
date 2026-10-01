package com.nsheaps.risetopower

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.nsheaps.risetopower.core.Civ
import com.nsheaps.risetopower.core.Difficulty
import com.nsheaps.risetopower.core.MapGenerator
import com.nsheaps.risetopower.core.MapSize
import com.nsheaps.risetopower.core.MapType

/** Main menu and new game setup, built from plain views. */
class MainActivity : Activity() {
    internal lateinit var root: FrameLayout
    internal val prefs by lazy { getSharedPreferences("setup", Context.MODE_PRIVATE) }

    internal val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF2A1F14.toInt(), 0xFF140E09.toInt())
        )
        setContentView(root)
        Immersive.apply(window)
    }

    private val multiplayer = MultiplayerMenu(this)

    override fun onResume() {
        super.onResume()
        Immersive.apply(window)
        // Bluetooth dialogs (permissions, "make visible") pause this screen; keep the lobby open.
        if (!multiplayer.inLobby && root.tag != MultiplayerMenu.TAG_MENU) showMain()
    }

    override fun onBackPressed() {
        when (root.tag) {
            "setup", "help", MultiplayerMenu.TAG_MENU -> showMain()
            MultiplayerMenu.TAG_HOST, MultiplayerMenu.TAG_JOIN -> multiplayer.showMenu()
            else -> super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        multiplayer.onPermissionsResult(requestCode)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        multiplayer.onActivityResult(requestCode)
    }

    override fun onDestroy() {
        multiplayer.leave()
        super.onDestroy()
    }

    fun showMainMenu() = showMain()

    // ------------------------------------------------------------------ screens

    private fun showMain() {
        multiplayer.leave()
        root.removeAllViews()
        root.tag = "main"
        val col = column()
        col.addView(title("RISE TO POWER", 44f))
        col.addView(subtitle("Build an empire from the Ancient to the Industrial Age"))
        col.addView(space(24))
        if (SaveStore.hasSave(this)) {
            col.addView(menuButton("Continue") {
                startActivity(Intent(this, GameActivity::class.java).putExtra(GameActivity.EXTRA_LOAD, true))
            })
        }
        col.addView(menuButton("New Game") { showSetup() })
        col.addView(menuButton("Multiplayer") { multiplayer.showMenu() })
        col.addView(menuButton("How to Play") { showHelp() })
        col.addView(space(12))
        col.addView(subtitle("v${packageManager.getPackageInfo(packageName, 0).versionName}", 12f))
        root.addView(scroll(col))
    }

    private fun showSetup() {
        root.removeAllViews()
        root.tag = "setup"
        val col = column()
        col.addView(title("New Game", 32f))
        col.addView(space(8))

        val civ = option("civ", Civ.entries.map { it.displayName }, 0)
        val opponents = option("opponents", (1..7).map { "$it" }, 1)
        val difficulty = option("difficulty", Difficulty.entries.map { it.displayName }, 1)
        val mapSize = option("mapSize", MapSize.entries.map { "${it.displayName} (${it.tiles}x${it.tiles})" }, 1)
        val mapType = option("mapType", MapType.entries.map { it.displayName }, 0)
        val teams = option("teams", TEAM_MODES, 0)
        val resources = option("resources", RESOURCE_LEVELS.map { it.first }, 0)
        val wonder = option("wonder", listOf("On", "Off"), 0)
        val reveal = option("reveal", listOf("Fog of war", "Revealed"), 0)

        val civInfo = subtitle("", 13f)
        fun refreshCiv() { civInfo.text = Civ.entries[civ.index].bonus }
        refreshCiv()
        civ.onChange = { refreshCiv() }

        col.addView(row("Civilization", civ.view))
        col.addView(civInfo)
        col.addView(row("Opponents", opponents.view))
        col.addView(row("Difficulty", difficulty.view))
        col.addView(row("Map size", mapSize.view))
        col.addView(row("Map type", mapType.view))
        col.addView(row("Teams", teams.view))
        col.addView(row("Starting resources", resources.view))
        col.addView(row("Wonder victory", wonder.view))
        col.addView(row("Map", reveal.view))
        col.addView(space(8))
        col.addView(subtitle("Larger maps allow more opponents (Small 3, Medium 5, Large 7).", 12f))

        val back = menuButton("Back", 140) { showMain() }
        val start = menuButton("Start", 200) {
            val size = MapSize.entries[mapSize.index]
            val maxOpp = MapGenerator.maxPlayers(size) - 1
            val opp = (opponents.index + 1).coerceAtMost(maxOpp)
            val intent = Intent(this, GameActivity::class.java)
                .putExtra(GameActivity.EXTRA_CIV, civ.index)
                .putExtra(GameActivity.EXTRA_OPPONENTS, opp)
                .putExtra(GameActivity.EXTRA_DIFFICULTY, difficulty.index)
                .putExtra(GameActivity.EXTRA_MAP_SIZE, mapSize.index)
                .putExtra(GameActivity.EXTRA_MAP_TYPE, mapType.index)
                .putExtra(GameActivity.EXTRA_TEAMS, teams.index)
                .putExtra(GameActivity.EXTRA_RESOURCES, RESOURCE_LEVELS[resources.index].second)
                .putExtra(GameActivity.EXTRA_WONDER, wonder.index == 0)
                .putExtra(GameActivity.EXTRA_REVEAL, reveal.index == 1)
            startActivity(intent)
        }
        showPinned(col, back, start)
    }

    /** Shows [content] scrolling above a bar of [buttons] that stays pinned to the bottom. */
    internal fun showPinned(content: View, vararg buttons: View) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        for (b in buttons) bar.addView(b)
        // Options scroll; the buttons stay pinned at the bottom so they are always reachable.
        val screen = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        screen.addView(scroll(content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val holder = FrameLayout(this).apply {
            setBackgroundColor(0xCC140E09.toInt())
            val pad = (6 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        holder.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        screen.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(screen, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showHelp() {
        root.removeAllViews()
        root.tag = "help"
        val col = column()
        col.addView(title("How to Play", 32f))
        val text = TextView(this).apply {
            setTextColor(0xFFE8DCC4.toInt())
            textSize = 14f
            setLineSpacing(4f * dp, 1f)
            text = HELP_TEXT
            maxWidth = (640 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        col.addView(text)
        col.addView(menuButton("Back") { showMain() })
        root.addView(scroll(col))
    }

    // ------------------------------------------------------------------ widgets

    internal inner class Option(val key: String, val values: List<String>, default: Int) {
        var index = prefs.getInt(key, default).coerceIn(0, values.size - 1)
        var onChange: (() -> Unit)? = null
        val view: Button = styledButton(values[index], 220) {}.also { b ->
            b.setOnClickListener {
                index = (index + 1) % values.size
                b.text = values[index]
                prefs.edit().putInt(key, index).apply()
                onChange?.invoke()
            }
            b.setOnLongClickListener {
                index = (index - 1 + values.size) % values.size
                b.text = values[index]
                prefs.edit().putInt(key, index).apply()
                onChange?.invoke()
                true
            }
        }
    }

    internal fun option(key: String, values: List<String>, default: Int) = Option(key, values, default)

    internal fun row(label: String, control: View): View {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val l = TextView(this).apply {
            text = label
            setTextColor(0xFFE8DCC4.toInt())
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams((190 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        r.addView(l)
        r.addView(control)
        r.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return r
    }

    // The column spans the screen width so every text view gets an exact width to wrap or
    // shrink against; wrap-content widths let text be measured narrower than it draws.
    internal fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = (24 * dp).toInt()
        setPadding(pad, pad, pad, pad)
    }

    internal fun scroll(content: View) = ScrollView(this).apply {
        isFillViewport = true
        val wrap = FrameLayout(this@MainActivity)
        wrap.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(wrap, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Single-line heading that shrinks to fit the screen width. */
    internal fun title(text: String, size: Float) = TextView(this).apply {
        this.text = text
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        setTextColor(0xFFE2B04A.toInt())
        setShadowLayer(8f, 0f, 3f, Color.BLACK)
        gravity = Gravity.CENTER
        letterSpacing = 0.08f
        maxLines = 1
        setAutoSizeTextTypeUniformWithConfiguration(16, size.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (size * 1.5f * dp * fontScale()).toInt())
    }

    internal fun subtitle(text: String, size: Float = 15f) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(0xFFBFAF90.toInt())
        gravity = Gravity.CENTER
        setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun fontScale() = resources.configuration.fontScale.coerceAtLeast(1f)

    internal fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, (h * dp).toInt()) }

    internal fun menuButton(text: String, width: Int = 260, onClick: () -> Unit) = styledButton(text, width, onClick).apply {
        textSize = 18f
    }

    internal fun styledButton(text: String, width: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        setTextColor(0xFFF5E9CF.toInt())
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        background = GradientDrawable().apply {
            cornerRadius = 8 * dp
            colors = intArrayOf(0xFF6B4A2B.toInt(), 0xFF4A3220.toInt())
            setStroke((2 * dp).toInt(), 0xFFE2B04A.toInt())
        }
        stateListAnimator = null
        minHeight = (48 * dp).toInt()
        minimumHeight = (48 * dp).toInt()
        val lp = LinearLayout.LayoutParams((width * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins((6 * dp).toInt(), (5 * dp).toInt(), (6 * dp).toInt(), (5 * dp).toInt())
        layoutParams = lp
        setOnClickListener { onClick() }
    }

    companion object {
        val TEAM_MODES = listOf("Free for all", "You vs all AIs", "You + ally vs rest")
        val RESOURCE_LEVELS = listOf("Standard" to 200, "High" to 1000, "Very high" to 5000)

        val HELP_TEXT = """
Goal: Defeat every opponent by destroying their Town Centers and Citizens, or build a Wonder and hold it for 5 minutes.

Camera
- Drag with one finger to scroll. Pinch to zoom.
- Tap the minimap to jump anywhere.

Selecting
- Tap a unit or building to select it. Double tap a unit to select all units of that type on screen.
- Long-press and drag (or turn on the Box button) to draw a selection box.
- Buttons on the left select idle citizens or your whole army.

Commands
- With units selected, tap the ground to move, tap an enemy to attack, tap a resource to gather, or tap your unfinished building to help build it.
- Attack-move: units engage anything they meet on the way.
- Buildings: tap a button in the command panel to train units, research technologies, or set a rally point.
- Placing a building: scroll the green outline where you want it and press Build here, or tap the spot twice.

Economy
- Citizens gather Food (berries, game, farms), Wood (trees), Gold and Stone (mines). Build Mills, Lumber Camps and Mining Camps near resources to shorten trips.
- Houses raise your population limit. Farms provide endless food.
- Markets let you trade resources for gold and generate trade income.

Ages and Territory
- Advance through 5 ages at the Town Center. Each age unlocks buildings and automatically upgrades your units.
- Your national borders grow with Town Centers, Towers and Fortresses. Most buildings must be placed inside your borders. Enemy soldiers suffer attrition in your lands.

Military
- Spearmen beat cavalry, archers beat infantry, cavalry beats archers and siege, and siege destroys buildings. Healers mend wounded units.

Multiplayer
- Up to 4 phones can play together over Bluetooth. One player picks Multiplayer › Host Game; the others pick Join Game and choose the host's phone.
- The host picks the map, AI opponents and whether players team up against the AIs, then presses Start.
- Multiplayer games can't be paused or saved. If a phone disconnects, its player resigns and the game carries on.
        """.trimIndent()
    }
}
