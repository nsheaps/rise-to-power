package com.nsheaps.risetopower

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.nsheaps.risetopower.core.AiController
import com.nsheaps.risetopower.core.BuildingType
import com.nsheaps.risetopower.core.UnitType
import com.nsheaps.risetopower.core.World
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders real frames of the game through the Android graphics stack (Robolectric native
 * graphics) and writes them to docs/screenshots. Doubles as a smoke test for the UI layer.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h420dp-land-xhdpi")
class ScreenshotTest {
    private val outDir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
    private val w = 1800
    private val h = 840

    private fun save(view: GameView, name: String, frames: Int = 2) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        repeat(frames) { view.renderOffscreen(c, w, h) }
        FileOutputStream(File(outDir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // Sanity check that something other than the background was drawn.
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        assertTrue("$name should not be blank", px.distinct().size > 50)
    }

    private fun launch(extra: Intent.() -> Unit = {}): GameView {
        val intent = Intent().putExtra(GameActivity.EXTRA_MAP_SIZE, 0).putExtra(GameActivity.EXTRA_OPPONENTS, 1)
            .putExtra(GameActivity.EXTRA_DIFFICULTY, 2)
        intent.extra()
        val activity = Robolectric.buildActivity(GameActivity::class.java, intent).setup().get()
        activity.awaitLoaded()
        shadowOf(Looper.getMainLooper()).idle()
        return activity.view!!
    }

    private fun simulate(world: World, seconds: Float) {
        repeat((seconds / World.TICK).toInt()) { world.update(World.TICK) }
    }

    private fun tap(view: GameView, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        view.injectTouch(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        view.injectTouch(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test
    fun renderGameScreens() {
        val view = launch()
        val world = view.world
        // Let an AI play for the human too so the base develops.
        world.ais += AiController(world, view.humanId)
        save(view, "01_start")

        simulate(world, 540f)
        val tc = world.townCenters(view.humanId).first()
        view.camera.centerOn(tc.x + 2f, tc.y + 2f)
        save(view, "02_base_developing")

        // Select citizens to show the command panel.
        val vills = world.units.filter { it.owner == view.humanId && it.type == UnitType.VILLAGER }.take(5)
        view.ui.selection.clear(); vills.forEach { view.ui.selection += it.id }
        save(view, "03_citizens_selected")
        view.hudForTest!!.buildPage = Hud.BuildPage.ECONOMY
        save(view, "04_build_menu")
        view.hudForTest!!.buildPage = Hud.BuildPage.NONE

        // Town center selected.
        view.ui.selection.clear(); view.ui.selection += tc.id
        save(view, "05_town_center")

        // Placement ghost.
        view.startPlacement(BuildingType.BARRACKS)
        save(view, "06_placement")
        view.cancelMode()

        // Battle scene in the middle of the map.
        val mx = world.map.width / 2f; val my = world.map.height / 2f
        val enemy = 1
        val types = listOf(UnitType.SPEARMAN, UnitType.ARCHER, UnitType.HORSEMAN, UnitType.WARRIOR, UnitType.CATAPULT, UnitType.HEALER)
        val mine = ArrayList<Int>()
        for (i in 0 until 14) {
            val u = world.spawnUnit(view.humanId, types[i % types.size], mx - 3f + (i % 4) * 0.8f, my + (i / 4) * 0.8f)
            mine += u.id
            world.spawnUnit(enemy, types[(i + 2) % types.size], mx + 3f + (i % 4) * 0.8f, my + (i / 4) * 0.8f)
        }
        world.commandMove(mine, mx + 4f, my + 1f, attackMove = true)
        simulate(world, 2.5f)
        view.camera.centerOn(mx + 1f, my + 1f)
        view.camera.zoom = 1.8f
        view.ui.selection.clear(); view.ui.selection.addAll(mine)
        save(view, "07_battle")

        view.camera.zoom = 0.45f
        view.ui.selection.clear()
        view.camera.centerOn(tc.x, tc.y)
        save(view, "08_zoomed_out")

        // Pause menu via the menu button in the top bar.
        tap(view, w - 30f, 30f)
        save(view, "09_pause_menu")
    }

    @Test
    fun lateGameAndVictoryScreen() {
        val view = launch { putExtra(GameActivity.EXTRA_MAP_TYPE, 1) }
        val world = view.world
        world.ais += AiController(world, view.humanId)
        var t = 0f
        var savedLate = false
        while (!world.gameOver && t < 3000f) {
            simulate(world, 30f)
            t += 30f
            if (!savedLate && t >= 1200f) {
                savedLate = true
                val tc = world.townCenters(view.humanId).firstOrNull() ?: world.buildings.first { it.owner == view.humanId }
                view.camera.centerOn(tc.x + 2f, tc.y + 2f)
                view.camera.zoom = 0.8f
                save(view, "10_late_game")
            }
        }
        save(view, "11_game_over", frames = 3)
    }

    @Test
    fun mainMenuRenders() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        FileOutputStream(File(outDir, "00_main_menu.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
