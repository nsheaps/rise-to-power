package com.nsheaps.risetopower

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.nsheaps.risetopower.core.Building
import com.nsheaps.risetopower.core.BuildingType
import com.nsheaps.risetopower.core.Entity
import com.nsheaps.risetopower.core.EventType
import com.nsheaps.risetopower.core.GameUnit
import com.nsheaps.risetopower.core.OrderType
import com.nsheaps.risetopower.core.ResourceNode
import com.nsheaps.risetopower.core.ResourceType
import com.nsheaps.risetopower.core.SoundCue
import com.nsheaps.risetopower.core.Tech
import com.nsheaps.risetopower.core.UnitType
import com.nsheaps.risetopower.core.World
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "RiseToPower"

class Ping(val x: Float, val y: Float, var t: Float = 0f)

/**
 * Hosts the game loop on a dedicated thread. Touch events are queued from the UI thread and
 * processed on the game thread, so all game state is only ever touched from one thread.
 */
@SuppressLint("ViewConstructor")
class GameView(private val activity: GameActivity, val world: World, private val sfx: Sfx) : SurfaceView(activity), SurfaceHolder.Callback, Runnable {
    val humanId = world.players.indexOfFirst { it.isHuman }.coerceAtLeast(0)
    private val d = resources.displayMetrics.density
    val camera = IsoCamera(d)
    val ui = UiState()
    var layers: TerrainLayers? = null
    private var renderer: Renderer? = null
    private var hud: Hud? = null

    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var surfaceReady = false
    @Volatile private var softwareFallback = false
    private val touches = ConcurrentLinkedQueue<MotionEvent>()

    // Game state of the view.
    var paused = false
    private var speedIndex = 0
    private val speeds = floatArrayOf(1f, 1.5f, 2f, 3f)
    var boxMode = false
    var lastVictoryMessage: String? = null
    val pings = ArrayList<Ping>()
    private var shownGameOver = false
    private var fogTimer = 0f
    private var miniTimer = 0f
    private var accumulator = 0f
    private var idleCycle = 0

    private enum class Mode { NONE, PLACE, RALLY, ATTACK_MOVE }
    private var mode = Mode.NONE

    init {
        holder.addCallback(this)
        isFocusable = true
        camera.mapW = world.map.width
        camera.mapH = world.map.height
        val tc = world.townCenters(humanId).firstOrNull()
        val start = world.units.firstOrNull { it.owner == humanId }
        when {
            tc != null -> camera.centerOn(tc.x + 1f, tc.y + 1f)
            start != null -> camera.centerOn(start.x, start.y)
            else -> camera.centerOn(world.map.width / 2f, world.map.height / 2f)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        softwareFallback = false
        if (activity.isResumedState) start()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Picked up by the game loop each frame.
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stop()
    }

    fun start() {
        if (running || !surfaceReady) return
        running = true
        thread = Thread(this, "game-loop").also { it.start() }
    }

    fun stop() {
        running = false
        val t = thread ?: return
        try { t.join(2000) } catch (_: InterruptedException) {}
        thread = null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        touches.add(MotionEvent.obtain(event))
        return true
    }

    override fun run() {
        if (layers == null) {
            drawLoading()
            ensureInitialized()
        }
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceAtMost(0.1f)
            last = now
            camera.viewW = width.toFloat()
            camera.viewH = height.toFloat()
            hud!!.layout(width.toFloat(), height.toFloat())
            processTouches()
            applyPendingPause()
            checkLongPress()
            val simulating = !paused && hud!!.menu == Hud.Menu.NONE && !(world.gameOver && !spectating)
            if (simulating) {
                accumulator += dt * speeds[speedIndex]
                var steps = 0
                while (accumulator >= World.TICK && steps < 8) {
                    world.update(World.TICK)
                    accumulator -= World.TICK
                    steps++
                }
                if (steps == 8) accumulator = 0f
            }
            drainEvents()
            updateLayers(dt)
            pruneSelection()
            updatePlacement()
            for (p in pings) p.t += dt
            pings.removeAll { it.t > 3f }

            val canvas = lockFrame()
            if (canvas != null) {
                try {
                    renderer!!.draw(canvas, ui, if (simulating) dt else 0f)
                    hud!!.draw(canvas, dt)
                } finally {
                    postFrame(canvas)
                }
            }
            val frame = (System.nanoTime() - now) / 1_000_000
            if (frame < 15) try { Thread.sleep(15 - frame) } catch (_: InterruptedException) {}
        }
    }

    /** Prepares layers, renderer and HUD without a surface (used by tests and the loading frame). */
    fun ensureInitialized() {
        if (layers != null) return
        val l = TerrainLayers(world, humanId)
        l.updateTerritory(force = true)
        l.updateMinimap()
        layers = l
        val r = Renderer(world, humanId, camera, l)
        renderer = r
        hud = Hud(this, world, humanId, r, ui, d)
    }

    /** Runs one frame of input, simulation and drawing onto [c]; used for offscreen rendering in tests. */
    fun renderOffscreen(c: Canvas, w: Int, h: Int, dt: Float = 1f / 60f) {
        ensureInitialized()
        camera.viewW = w.toFloat()
        camera.viewH = h.toFloat()
        hud!!.layout(w.toFloat(), h.toFloat())
        processTouches()
        applyPendingPause()
        drainEvents()
        layers!!.updateFog()
        layers!!.updateTerritory()
        layers!!.updateMinimap()
        pruneSelection()
        updatePlacement()
        renderer!!.draw(c, ui, dt)
        hud!!.draw(c, dt)
    }

    /** Test hook: feeds a synthetic touch as if it came from the UI thread. */
    fun injectTouch(e: MotionEvent) = touches.add(MotionEvent.obtain(e))

    val hudForTest get() = hud

    private fun drawLoading() {
        val c = lockFrame() ?: return
        c.drawColor(0xFF1B140E.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE2B04A.toInt(); textSize = 26f * d; textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD)
        }
        c.drawText("Preparing the land…", c.width / 2f, c.height / 2f, p)
        postFrame(c)
    }

    /**
     * Locks the surface for one frame. A surface can only be connected to one producer API, so
     * every frame (including the loading screen) must use the same lock method: hardware when
     * available, otherwise software for the rest of this surface's lifetime.
     */
    private fun lockFrame(): Canvas? {
        if (!softwareFallback) {
            try {
                return holder.lockHardwareCanvas()
            } catch (e: Exception) {
                Log.w(TAG, "Hardware canvas unavailable, falling back to software rendering", e)
                softwareFallback = true
            }
        }
        return try { holder.lockCanvas() } catch (e: Exception) { Log.e(TAG, "lockCanvas failed", e); null }
    }

    private fun postFrame(c: Canvas) {
        try { holder.unlockCanvasAndPost(c) } catch (e: Exception) { Log.e(TAG, "unlockCanvasAndPost failed", e) }
    }

    private fun updateLayers(dt: Float) {
        val l = layers ?: return
        fogTimer -= dt
        if (fogTimer <= 0f) { fogTimer = 0.2f; l.updateFog() }
        l.updateTerritory()
        miniTimer -= dt
        if (miniTimer <= 0f) { miniTimer = 1f; l.updateMinimap() }
    }

    private fun pruneSelection() {
        ui.selection.removeAll { world.get(it) == null }
    }

    // ------------------------------------------------------------------ events & sound

    private fun drainEvents() {
        val h = hud ?: return
        while (world.events.isNotEmpty()) {
            val e = world.events.removeFirst()
            val mine = e.player == humanId
            when (e.type) {
                EventType.ATTACKED -> if (mine) {
                    h.message(e.text, 0xFFFF8070.toInt())
                    sfx.play(Sfx.Kind.ALERT, 0.8f, 3000)
                    if (!e.x.isNaN()) pings += Ping(e.x, e.y)
                }
                EventType.AGE_UP -> {
                    h.message(e.text, if (mine) 0xFFFFE070.toInt() else 0xFFBFAF90.toInt())
                    if (mine) sfx.play(Sfx.Kind.AGE_UP)
                }
                EventType.BUILT -> if (mine) h.message(e.text)
                EventType.RESEARCHED -> if (mine) { h.message(e.text, 0xFF9AD0FF.toInt()); sfx.play(Sfx.Kind.BUILD, 0.6f) }
                EventType.TRAINED -> if (mine) sfx.play(Sfx.Kind.ACK, 0.5f, 400)
                EventType.WARNING -> if (mine) { h.message(e.text, 0xFFFFB060.toInt()); sfx.play(Sfx.Kind.ERROR, 0.6f, 500) }
                EventType.INFO -> if (mine) h.message(e.text, 0xFFFFB060.toInt())
                EventType.WONDER -> { h.message(e.text, 0xFFFFE070.toInt()); sfx.play(Sfx.Kind.ALERT) }
                EventType.DEFEATED -> {
                    h.message(if (mine) "You have been defeated" else e.text, 0xFFFF8070.toInt())
                    if (mine && !world.gameOver) {
                        sfx.play(Sfx.Kind.DEFEAT)
                        h.menu = Hud.Menu.GAME_OVER
                        SaveStore.delete(activity)
                    }
                }
                EventType.VICTORY -> {
                    lastVictoryMessage = e.text
                    h.message(e.text, 0xFFFFE070.toInt())
                }
            }
        }
        if (world.gameOver && !shownGameOver) {
            shownGameOver = true
            h.menu = Hud.Menu.GAME_OVER
            sfx.play(if (world.winnerTeam == world.players[humanId].team) Sfx.Kind.VICTORY else Sfx.Kind.DEFEAT)
            SaveStore.delete(activity)
        }
        val r = renderer ?: return
        for (cue in world.sounds) {
            val sx = camera.sx(cue.x, cue.y); val sy = camera.sy(cue.x, cue.y)
            val onScreen = sx > -50 && sy > -50 && sx < width + 50 && sy < height + 50
            if (!onScreen || !world.isVisibleTo(humanId, cue.x, cue.y)) continue
            r.addCueEffect(cue)
            when (cue.kind) {
                SoundCue.MELEE -> sfx.play(Sfx.Kind.MELEE, 0.35f, 90)
                SoundCue.SHOOT -> sfx.play(Sfx.Kind.SHOOT, 0.3f, 90)
                SoundCue.SIEGE -> sfx.play(Sfx.Kind.SIEGE, 0.6f, 150)
                SoundCue.DEATH -> sfx.play(Sfx.Kind.DEATH, 0.35f, 120)
                SoundCue.COLLAPSE -> sfx.play(Sfx.Kind.COLLAPSE, 0.8f, 300)
                SoundCue.BUILD -> sfx.play(Sfx.Kind.BUILD, 0.5f, 300)
            }
        }
        world.sounds.clear()
    }

    // ------------------------------------------------------------------ touch handling

    private enum class Gesture { NONE, PENDING, PAN, BOX, PINCH, HUD, MINIMAP }

    private var gesture = Gesture.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var pinchDist = 0f
    private var pinchCx = 0f
    private var pinchCy = 0f
    private var lastTapTime = 0L
    private var lastTapType: UnitType? = null
    private var hudLongPressShown = false

    private fun processTouches() {
        while (true) {
            val e = touches.poll() ?: break
            try { handleTouch(e) } finally { e.recycle() }
        }
    }

    private fun handleTouch(e: MotionEvent) {
        val h = hud ?: return
        val slop = 10f * d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                downTime = System.currentTimeMillis()
                hudLongPressShown = false
                val b = h.buttonAt(e.x, e.y)
                when {
                    b != null -> { gesture = Gesture.HUD; h.pressed = b }
                    h.menu != Hud.Menu.NONE -> gesture = Gesture.NONE
                    h.minimapToWorld(e.x, e.y) != null -> {
                        gesture = Gesture.MINIMAP
                        h.minimapToWorld(e.x, e.y)?.let { (wx, wy) -> camera.centerOn(wx, wy) }
                    }
                    h.hitPanel(e.x, e.y) -> gesture = Gesture.NONE
                    else -> gesture = Gesture.PENDING
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2 && gesture != Gesture.HUD) {
                    gesture = Gesture.PINCH
                    ui.boxActive = false
                    pinchDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    pinchCx = (e.getX(0) + e.getX(1)) / 2f
                    pinchCy = (e.getY(0) + e.getY(1)) / 2f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (gesture) {
                    Gesture.PENDING -> {
                        if (hypot(e.x - downX, e.y - downY) > slop) {
                            if (boxMode) {
                                gesture = Gesture.BOX
                                ui.boxActive = true
                                ui.box.set(downX, downY, e.x, e.y)
                            } else {
                                gesture = Gesture.PAN
                                camera.pan(e.x - lastX, e.y - lastY)
                            }
                        }
                    }
                    Gesture.PAN -> camera.pan(e.x - lastX, e.y - lastY)
                    Gesture.BOX -> ui.box.set(minOf(downX, e.x), minOf(downY, e.y), maxOf(downX, e.x), maxOf(downY, e.y))
                    Gesture.PINCH -> if (e.pointerCount >= 2) {
                        val dist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        val cx = (e.getX(0) + e.getX(1)) / 2f
                        val cy = (e.getY(0) + e.getY(1)) / 2f
                        if (pinchDist > 0f) camera.zoomAround(dist / pinchDist, cx, cy)
                        camera.pan(cx - pinchCx, cy - pinchCy)
                        pinchDist = dist; pinchCx = cx; pinchCy = cy
                    }
                    Gesture.MINIMAP -> h.minimapToWorld(e.x, e.y)?.let { (wx, wy) -> camera.centerOn(wx, wy) }
                    Gesture.HUD -> {
                        val b = h.pressed
                        if (b != null && !b.rect.contains(e.x, e.y)) h.pressed = null
                    }
                    Gesture.NONE -> {}
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (gesture == Gesture.PINCH) {
                    // Continue panning with the remaining finger.
                    val remaining = if (e.actionIndex == 0) 1 else 0
                    lastX = e.getX(remaining); lastY = e.getY(remaining)
                    gesture = if (e.pointerCount - 1 >= 2) Gesture.PINCH else Gesture.PAN
                    if (gesture == Gesture.PINCH) pinchDist = 0f
                }
            }
            MotionEvent.ACTION_UP -> {
                when (gesture) {
                    Gesture.HUD -> {
                        val b = h.pressed
                        h.pressed = null
                        if (b != null && b.rect.contains(e.x, e.y) && !hudLongPressShown) {
                            if (b.enabled) {
                                sfx.play(Sfx.Kind.CLICK, 0.5f)
                                if (b.tooltip == null) h.showTooltip(null)
                                b.action?.invoke()
                            } else {
                                sfx.play(Sfx.Kind.ERROR, 0.4f)
                                h.showTooltip(b.tooltip ?: b.label)
                            }
                        }
                    }
                    Gesture.PENDING -> onTap(e.x, e.y)
                    Gesture.BOX -> { boxSelect(); ui.boxActive = false }
                    else -> {}
                }
                gesture = Gesture.NONE
            }
            MotionEvent.ACTION_CANCEL -> {
                gesture = Gesture.NONE
                ui.boxActive = false
                h.pressed = null
            }
        }
    }

    private fun checkLongPress() {
        val h = hud ?: return
        val held = System.currentTimeMillis() - downTime
        if (gesture == Gesture.PENDING && held > 420) {
            gesture = Gesture.BOX
            ui.boxActive = true
            ui.box.set(downX, downY, downX + 1f, downY + 1f)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
        if (gesture == Gesture.HUD && held > 450 && !hudLongPressShown) {
            val b = h.pressed
            if (b?.tooltip != null) {
                hudLongPressShown = true
                h.showTooltip(b.tooltip)
            }
        }
    }

    // ------------------------------------------------------------------ picking & selection

    private fun pickEntity(x: Float, y: Float): Entity? {
        val s = camera.scale
        var best: Entity? = null
        var bestD = 20f * d * max(1f, camera.zoom)
        for (u in world.units) {
            if (!u.alive) continue
            if (u.owner != humanId && !world.isAlly(humanId, u.owner) && !world.isVisibleTo(humanId, u.x, u.y)) continue
            val ux = camera.sx(u.x, u.y)
            val uy = camera.sy(u.x, u.y) - 12f * s
            val dd = hypot(ux - x, uy - y)
            if (dd < bestD) { bestD = dd; best = u }
        }
        if (best != null) return best
        // Buildings: test the projected footprint extended upwards by the building height.
        var bestKey = -1f
        for (b in world.buildings) {
            if (!b.alive) continue
            if (b.owner != humanId && !world.isAlly(humanId, b.owner) && !world.isExploredBy(humanId, b.tx, b.ty)) continue
            val left = camera.sx(b.minX, b.maxY); val right = camera.sx(b.maxX, b.minY)
            val top = camera.sy(b.minX, b.minY) - (if (b.type == BuildingType.FARM) 0f else 40f * s * b.type.size.coerceAtMost(3))
            val bottom = camera.sy(b.maxX, b.maxY)
            if (x < left || x > right || y < top || y > bottom) continue
            // Reject the empty triangles below the footprint diamond's side corners.
            val midY = camera.sy(b.maxX, b.minY)
            if (y > midY) {
                val cx = camera.sx(b.maxX, b.maxY)
                val halfW = (right - left) / 2f
                val allowed = halfW * (1f - (y - midY) / (bottom - midY).coerceAtLeast(1f))
                if (abs(x - cx) > allowed + 6f * d) continue
            }
            val key = b.x + b.y
            if (key > bestKey) { bestKey = key; best = b }
        }
        if (best != null) return best
        // Resource nodes by tile, also checking slightly below (tall trees).
        for (off in floatArrayOf(0f, 10f * s, 20f * s)) {
            val wx = camera.worldX(x, y + off); val wy = camera.worldY(x, y + off)
            val ent = world.entityAtTile(wx.toInt(), wy.toInt())
            if (ent is ResourceNode && world.isExploredBy(humanId, ent.tx, ent.ty)) return ent
        }
        return null
    }

    private fun ownSelectedUnits(): List<GameUnit> =
        ui.selection.mapNotNull { world.unit(it) }.filter { it.owner == humanId }

    private fun select(e: Entity?, additive: Boolean = false) {
        if (!additive) ui.selection.clear()
        hud?.buildPage = Hud.BuildPage.NONE
        hud?.commandPage = 0
        hud?.confirmDelete = false
        if (e != null) {
            ui.selection += e.id
            if (e.owner == humanId) sfx.play(Sfx.Kind.SELECT, 0.4f)
        }
    }

    private fun onTap(x: Float, y: Float) {
        val wx = camera.worldX(x, y)
        val wy = camera.worldY(x, y)
        when (mode) {
            Mode.PLACE -> { tapPlace(); return }
            Mode.RALLY -> {
                ui.selection.mapNotNull { world.building(it) }.filter { it.owner == humanId }.forEach { world.setRally(it.id, wx, wy) }
                ui.effects += Effect(Effect.MOVE_MARK, wx, wy, 0f, 0.6f)
                sfx.play(Sfx.Kind.ACK, 0.5f)
                mode = Mode.NONE
                return
            }
            Mode.ATTACK_MOVE -> {
                val ids = ownSelectedUnits().map { it.id }
                world.commandMove(ids, wx, wy, attackMove = true)
                ui.effects += Effect(Effect.ATTACK_MARK, wx, wy, 0f, 0.6f)
                sfx.play(Sfx.Kind.ACK, 0.5f)
                mode = Mode.NONE
                return
            }
            Mode.NONE -> {}
        }
        val e = pickEntity(x, y)
        val units = ownSelectedUnits()
        // Double tap on an own unit: select all of that type on screen.
        val now = System.currentTimeMillis()
        if (e is GameUnit && e.owner == humanId && now - lastTapTime < 350 && lastTapType == e.type) {
            selectTypeOnScreen(e.type)
            lastTapTime = 0
            return
        }
        lastTapTime = now
        lastTapType = (e as? GameUnit)?.type

        if (units.isNotEmpty()) {
            val hasVillagers = units.any { it.type == UnitType.VILLAGER }
            val hasHealers = units.any { it.type == UnitType.HEALER }
            val selectInstead = when (e) {
                null -> false
                is GameUnit -> e.owner == humanId && !(hasHealers && e.hp < e.maxHp && !ui.selection.contains(e.id))
                is Building -> e.owner == humanId && !(hasVillagers && (!e.constructed || e.hp < e.maxHp || (e.type == BuildingType.FARM && e.farmerId < 0) || (e.type.isDropSite && units.any { it.carryAmount > 0 })))
                is ResourceNode -> !hasVillagers
            }
            if (e != null && selectInstead) {
                select(e)
                return
            }
            world.commandSmart(humanId, units.map { it.id }, wx, wy, e)
            val attack = e != null && world.isEnemy(humanId, e.owner)
            ui.effects += Effect(if (attack) Effect.ATTACK_MARK else Effect.MOVE_MARK, e?.x ?: wx, e?.y ?: wy, 0f, 0.6f)
            sfx.play(Sfx.Kind.ACK, 0.45f, 150)
            return
        }
        select(e)
    }

    private fun selectTypeOnScreen(type: UnitType) {
        ui.selection.clear()
        for (u in world.units) {
            if (!u.alive || u.owner != humanId || u.type != type) continue
            val sx = camera.sx(u.x, u.y); val sy = camera.sy(u.x, u.y)
            if (sx in 0f..width.toFloat() && sy in 0f..height.toFloat()) ui.selection += u.id
        }
        hud?.buildPage = Hud.BuildPage.NONE
        sfx.play(Sfx.Kind.SELECT, 0.5f)
    }

    private fun boxSelect() {
        val r = ui.box
        if (r.width() < 6f && r.height() < 6f) return
        val picked = world.units.filter { u ->
            u.alive && u.owner == humanId && r.contains(camera.sx(u.x, u.y), camera.sy(u.x, u.y) - 10f * camera.scale)
        }
        if (picked.isEmpty()) return
        // Prefer soldiers when the box mixes soldiers and citizens.
        val soldiers = picked.filter { it.type.isMilitary }
        val chosen = if (soldiers.isNotEmpty() && soldiers.size != picked.size) soldiers else picked
        ui.selection.clear()
        chosen.forEach { ui.selection += it.id }
        hud?.buildPage = Hud.BuildPage.NONE
        sfx.play(Sfx.Kind.SELECT, 0.5f)
    }

    fun narrowSelection(type: UnitType) {
        val keep = ui.selection.filter { world.unit(it)?.type == type }
        ui.selection.clear()
        ui.selection.addAll(keep)
    }

    // ------------------------------------------------------------------ placement

    private fun updatePlacement() {
        val t = ui.placing ?: return
        val wx = camera.worldX(camera.viewW / 2f, camera.viewH / 2f)
        val wy = camera.worldY(camera.viewW / 2f, camera.viewH / 2f)
        if (!placementTouched) {
            ui.placeX = (wx - t.size / 2f).roundToInt()
            ui.placeY = (wy - t.size / 2f).roundToInt()
        }
        ui.placeValid = world.placementError(humanId, t, ui.placeX, ui.placeY) == null
    }

    private var placementTouched = false

    private fun tapPlace() {
        val t = ui.placing ?: return
        val wx = camera.worldX(lastX, lastY)
        val wy = camera.worldY(lastX, lastY)
        val tx = (wx - t.size / 2f).roundToInt()
        val ty = (wy - t.size / 2f).roundToInt()
        val builders = ownSelectedUnits().filter { it.type == UnitType.VILLAGER }.map { it.id }
        if (t == BuildingType.WALL) {
            val start = ui.wallStart
            if (start == null) {
                ui.wallStart = Pair(wx.toInt(), wy.toInt())
                ui.placeX = wx.toInt(); ui.placeY = wy.toInt()
                placementTouched = true
                return
            }
            val placed = world.placeWall(humanId, start.first, start.second, wx.toInt(), wy.toInt(), builders)
            if (placed > 0) sfx.play(Sfx.Kind.ACK, 0.5f)
            cancelMode()
            return
        }
        // First tap positions the ghost, a second tap on the same spot confirms.
        if (!placementTouched || tx != ui.placeX || ty != ui.placeY) {
            ui.placeX = tx; ui.placeY = ty
            placementTouched = true
            val err = world.placementError(humanId, t, tx, ty)
            hud?.showTooltip(if (err == null) "Tap again to build ${t.displayName}" else err)
            return
        }
        val b = world.placeFoundation(humanId, t, tx, ty, builders)
        if (b != null) {
            sfx.play(Sfx.Kind.ACK, 0.5f)
            hud?.showTooltip(null)
            cancelMode()
        }
    }

    // ------------------------------------------------------------------ actions used by the HUD

    fun hasMode() = mode != Mode.NONE

    fun modeHint(): String? = when (mode) {
        Mode.PLACE -> if (ui.placing == BuildingType.WALL) {
            if (ui.wallStart == null) "Tap where the wall starts" else "Tap where the wall ends"
        } else "Tap to position the ${ui.placing?.displayName}, tap again to build"
        Mode.RALLY -> "Tap to set the rally point"
        Mode.ATTACK_MOVE -> "Tap a destination to attack-move"
        Mode.NONE -> null
    }

    fun cancelMode() {
        mode = Mode.NONE
        ui.placing = null
        ui.wallStart = null
        placementTouched = false
    }

    fun startPlacement(t: BuildingType) {
        mode = Mode.PLACE
        ui.placing = t
        ui.wallStart = null
        placementTouched = false
        hud?.buildPage = Hud.BuildPage.NONE
    }

    fun startRally() { mode = Mode.RALLY }
    fun startAttackMove() { mode = Mode.ATTACK_MOVE }

    fun stopSelected() = world.commandStop(ownSelectedUnits().map { it.id })
    fun returnResources() = world.commandReturn(ownSelectedUnits().map { it.id })

    fun deleteSelected() {
        for (id in ui.selection.toList()) world.deleteOwn(humanId, id)
        ui.selection.clear()
    }

    fun train(b: Building, t: UnitType) = report(world.queueTrain(b.id, t))
    fun research(b: Building, t: Tech) = report(world.queueResearch(b.id, t))
    fun advance(b: Building) = report(world.queueAdvance(b.id))
    fun trade(r: ResourceType, buy: Boolean) = report(world.marketTrade(humanId, r, buy))
    fun cancelQueue(b: Building, index: Int) = world.cancelQueue(b.id, index)

    private fun report(err: String?) {
        if (err != null) {
            hud?.message(err, 0xFFFFB060.toInt())
            sfx.play(Sfx.Kind.ERROR, 0.5f)
        }
    }

    fun selectIdleVillager() {
        val idle = world.units.filter { it.alive && it.owner == humanId && it.type == UnitType.VILLAGER && it.order == OrderType.IDLE }
        if (idle.isEmpty()) { hud?.message("No idle citizens"); return }
        val u = idle[idleCycle++ % idle.size]
        select(u)
        camera.centerOn(u.x, u.y)
    }

    fun selectArmy() {
        val army = world.units.filter { it.alive && it.owner == humanId && it.type.isMilitary && it.type != UnitType.SCOUT }
        if (army.isEmpty()) { hud?.message("You have no army"); return }
        ui.selection.clear()
        army.forEach { ui.selection += it.id }
        hud?.buildPage = Hud.BuildPage.NONE
        sfx.play(Sfx.Kind.SELECT, 0.5f)
    }

    fun goHome() {
        val tcs = world.townCenters(humanId)
        if (tcs.isEmpty()) return
        val tc = tcs[idleCycle++ % tcs.size]
        camera.centerOn(tc.x, tc.y)
        select(tc)
    }

    fun speedLabel() = when (speeds[speedIndex]) { 1f -> "1x"; 1.5f -> "1.5x"; 2f -> "2x"; else -> "3x" }

    fun cycleSpeed() { speedIndex = (speedIndex + 1) % speeds.size }

    fun togglePause() {
        val h = hud ?: return
        h.menu = if (h.menu == Hud.Menu.PAUSE) Hud.Menu.NONE else Hud.Menu.PAUSE
    }

    /** Called from the UI thread (back button). */
    fun requestPause() {
        pendingPause = true
    }

    @Volatile var pendingPause = false
        private set

    private var spectating = false

    fun spectate() {
        spectating = true
        world.revealAll = true
        layers?.updateFog()
    }

    fun saveGame() {
        try {
            SaveStore.save(activity, world)
            hud?.message("Game saved", 0xFF9AD0FF.toInt())
        } catch (e: Exception) {
            hud?.message("Save failed: ${e.message}", 0xFFFF8070.toInt())
        }
        hud?.menu = Hud.Menu.NONE
    }

    fun resign() {
        world.resign(humanId)
        hud?.menu = Hud.Menu.NONE
    }

    fun quitToMenu(save: Boolean) {
        if (save && !world.gameOver && !world.players[humanId].defeated) SaveStore.save(activity, world)
        running = false
        activity.runOnUiThread { activity.finish() }
    }

    /** Applies a pause requested from the UI thread; called at the start of each frame. */
    fun applyPendingPause() {
        if (pendingPause) {
            pendingPause = false
            val h = hud ?: return
            if (h.menu == Hud.Menu.NONE && mode != Mode.NONE) cancelMode()
            else if (h.menu == Hud.Menu.NONE && h.buildPage != Hud.BuildPage.NONE) h.buildPage = Hud.BuildPage.NONE
            else if (h.menu == Hud.Menu.NONE) h.menu = Hud.Menu.PAUSE
            else if (h.menu == Hud.Menu.PAUSE) h.menu = Hud.Menu.NONE
            else if (h.menu == Hud.Menu.STATS) h.menu = Hud.Menu.PAUSE
        }
    }
}
