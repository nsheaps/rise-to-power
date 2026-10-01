package com.nsheaps.risetopower

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.nsheaps.risetopower.core.BuildingType
import com.nsheaps.risetopower.core.NodeKind
import com.nsheaps.risetopower.core.OrderType
import com.nsheaps.risetopower.core.UnitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Taps on the real game view and checks what gets picked and commanded. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w900dp-h420dp-land-xhdpi")
class PickingTest {
    private val w = 1800
    private val h = 840
    private val canvas = Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))

    private fun launch(): GameView {
        val intent = Intent().putExtra(GameActivity.EXTRA_MAP_SIZE, 0).putExtra(GameActivity.EXTRA_OPPONENTS, 1)
            .putExtra(GameActivity.EXTRA_SEED, 7L)
        val activity = Robolectric.buildActivity(GameActivity::class.java, intent).setup().get()
        activity.awaitLoaded()
        shadowOf(Looper.getMainLooper()).idle()
        val view = activity.view!!
        view.renderOffscreen(canvas, w, h)
        return view
    }

    private fun describe(e: com.nsheaps.risetopower.core.Entity?) = when (e) {
        null -> "null"
        is com.nsheaps.risetopower.core.ResourceNode -> "${e.kind}@${e.x},${e.y}"
        is com.nsheaps.risetopower.core.GameUnit -> "${e.type}@${e.x},${e.y}"
        is com.nsheaps.risetopower.core.Building -> "${e.type}@${e.x},${e.y}"
        else -> e.toString()
    }

    private fun tap(view: GameView, x: Float, y: Float) {
        // Keep consecutive taps apart so they are not read as a double tap.
        Thread.sleep(400)
        val t = SystemClock.uptimeMillis()
        view.injectTouch(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        view.injectTouch(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
        view.renderOffscreen(canvas, w, h)
    }

    @Test
    fun tappingBerryBushSendsSelectedCitizenToGather() {
        val view = launch()
        val world = view.world
        val tc = world.townCenters(view.humanId).first()
        val bushes = world.nodes.filter { it.kind == NodeKind.BERRIES && it.distanceTo(tc.x, tc.y) < 12f }
        assertTrue(bushes.isNotEmpty())
        var tapped = 0
        val skipped = mutableListOf<String>()
        for (bush in bushes) {
            val vill = world.units.first { it.owner == view.humanId && it.type == UnitType.VILLAGER }
            view.ui.selection.clear(); view.ui.selection += vill.id
            view.camera.centerOn(bush.x, bush.y)
            val cam = view.camera
            val bx = cam.sx(bush.x, bush.y); val by = cam.sy(bush.x, bush.y)
            // A visible part of the bush: start at its middle and spiral out until it is the entity picked.
            val point = (-22..5).flatMap { dy -> (-16..16).map { dx -> dx to dy } }
                .sortedBy { (dx, dy) -> dx * dx + (dy + 8) * (dy + 8) }
                .map { (dx, dy) -> bx + dx * cam.scale to by + dy * cam.scale }
                .firstOrNull { (px, py) -> view.picker.pick(px, py) == bush }
            if (point == null) {
                skipped += "${describe(bush)} covered by ${describe(view.picker.pick(bx, by - 8 * cam.scale))}"
                continue
            }
            tap(view, point.first, point.second)
            assertEquals("citizen should gather ${describe(bush)}", OrderType.GATHER, vill.order)
            assertEquals("target of tap on ${describe(bush)}: ${describe(world.get(vill.targetId))}", bush.id, vill.targetId)
            world.commandMove(listOf(vill.id), vill.x, vill.y)
            tapped++
        }
        // Only a bush completely hidden behind something drawn in front of it may be skipped.
        assertTrue("tappable bushes $tapped of ${bushes.size}; $skipped", tapped >= bushes.size - 1)
    }

    @Test
    fun groundBehindBuildingIsNotPartOfIt() {
        val view = launch()
        val world = view.world
        val tc = world.townCenters(view.humanId).first()
        view.camera.centerOn(tc.x, tc.y)
        val cam = view.camera
        val picker = view.picker
        // The roof of the building picks the building.
        val roof = picker.pick(cam.sx(tc.x, tc.y), cam.sy(tc.minX, tc.minY))
        assertEquals("tc ${tc.x},${tc.y} picked ${describe(roof)}", tc, roof)
        // Empty space beside the raised top corner (inside the old bounding box) does not.
        val sil = picker.silhouette(tc).copyOf()
        val left = sil[0]; val top = sil[9]
        assertNotEquals(tc, picker.pick(left + 4f, top + 4f))

        // A foundation is flat: the space above it is free.
        val (stx, sty) = (-10..10).flatMap { dy -> (-10..10).map { dx -> tc.tx + dx to tc.ty + dy } }
            .first { (x, y) -> world.placementError(view.humanId, BuildingType.BARRACKS, x, y, checkCost = false) == null }
        val site = world.addBuilding(view.humanId, BuildingType.BARRACKS, stx, sty, constructed = false)
        val sx = cam.sx(site.minX, site.minY); val sy = cam.sy(site.minX, site.minY)
        assertNotEquals(site, picker.pick(sx, sy - Renderer.buildingHeight(BuildingType.BARRACKS) * 32f * cam.scale))
        assertEquals(site, picker.pick(cam.sx(site.x, site.y), cam.sy(site.x, site.y)))
    }

    @Test
    fun treeBehindBuildingCanBeTapped() {
        val view = launch()
        val world = view.world
        val tc = world.townCenters(view.humanId).first()
        view.camera.centerOn(tc.x, tc.y)
        val cam = view.camera
        val picker = view.picker
        val sil = picker.silhouette(tc).copyOf()
        // Plant a tree on a free tile just behind the town centre whose crown shows above the roof line
        // (inside the old rectangular hit box of the building).
        var tested = 0
        for ((dx, dy) in listOf(-1 to -1, 0 to -1, -1 to 0, 1 to -2, -2 to 1, -2 to -2)) {
            val tree = world.spawnNode(NodeKind.TREE, tc.tx + dx, tc.ty + dy) ?: continue
            val px = cam.sx(tree.x, tree.y)
            // Highest point of the crown that is not covered by the building.
            val py = (10..56).map { cam.sy(tree.x, tree.y) - it * cam.scale }.firstOrNull { !Picker.insideConvex(px, it, sil) } ?: continue
            assertTrue("tap point lies in the old rectangular hit box", py > cam.sy(tc.minX, tc.minY) - 120f * cam.scale)
            assertEquals("tree at $dx,$dy", tree, picker.pick(px, py))
            tested++
            break
        }
        assertTrue(tested > 0)
    }

    @Test
    fun ownUnitBehindBuildingCanBeSelected() {
        val view = launch()
        val world = view.world
        val tc = world.townCenters(view.humanId).first()
        view.camera.centerOn(tc.x, tc.y)
        val cam = view.camera
        val s = cam.scale
        // Just behind the town centre's back-left wall, as in a player's report: the head shows above
        // the wall but the old box silhouette swallowed the tap.
        val peeking = world.spawnUnit(view.humanId, UnitType.VILLAGER, tc.minX - 0.3f, tc.minY + 0.8f)
        // Right behind the keep, hidden entirely; drawn as a silhouette and still selectable.
        val hidden = world.spawnUnit(view.humanId, UnitType.VILLAGER, tc.minX + 0.2f, tc.minY + 0.2f)
        for (u in listOf(peeking, hidden)) {
            assertTrue(u.x + u.y < tc.x + tc.y)
            tap(view, cam.sx(u.x, u.y), cam.sy(u.x, u.y) - 20f * s)
            assertEquals("selected after tapping ${describe(u)}", setOf(u.id), view.ui.selection.toSet())
        }
        // The town centre itself is still selectable where no unit is in the way (the random map
        // may put a starting citizen in front of it, so try a few spots along its front).
        val front = listOf(0.3f, 0.8f, 1.3f, 1.8f).flatMap { k -> listOf(tc.maxX - k to tc.maxY - 0.3f, tc.maxX - 0.3f to tc.maxY - k) }
            .map { (x, y) -> cam.sx(x, y) to cam.sy(x, y) - 6f * s }
            .first { (px, py) -> view.picker.pick(px, py) == tc }
        tap(view, front.first, front.second)
        assertEquals(setOf(tc.id), view.ui.selection.toSet())
    }

    @Test
    fun buildHereButtonPlacesTheOutlinedBuilding() {
        val view = launch()
        val world = view.world
        val tc = world.townCenters(view.humanId).first()
        val vill = world.units.first { it.owner == view.humanId && it.type == UnitType.VILLAGER }
        view.ui.selection.clear(); view.ui.selection += vill.id
        val type = BuildingType.HOUSE
        val spot = (4..12).flatMap { r -> (-r..r).flatMap { dx -> listOf(dx to -r, dx to r, -r to dx, r to dx) } }
            .map { (dx, dy) -> tc.x.toInt() + dx to tc.y.toInt() + dy }
            .first { (x, y) -> world.placementError(view.humanId, type, x, y) == null }
        view.startPlacement(type)
        view.camera.centerOn(spot.first + type.size / 2f, spot.second + type.size / 2f)
        view.renderOffscreen(canvas, w, h)
        val hud = view.hudForTest!!
        val button = hud.buttons.firstOrNull { it.label == "Build here" }
        assertTrue("Build here button: ${hud.buttons.map { it.label }}", button != null && button.enabled)
        val before = world.buildings.count { it.owner == view.humanId }
        tap(view, button!!.rect.centerX(), button.rect.centerY())
        assertEquals(before + 1, world.buildings.count { it.owner == view.humanId })
        val house = world.buildings.last { it.owner == view.humanId }
        assertEquals(type, house.type)
        assertTrue("citizen should build", vill.order == OrderType.BUILD)
        assertTrue("placement ends", !view.hasMode())
    }
}
