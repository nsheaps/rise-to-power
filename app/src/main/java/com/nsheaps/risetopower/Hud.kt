package com.nsheaps.risetopower

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.nsheaps.risetopower.core.Age
import com.nsheaps.risetopower.core.BuildMenu
import com.nsheaps.risetopower.core.Building
import com.nsheaps.risetopower.core.BuildingType
import com.nsheaps.risetopower.core.Entity
import com.nsheaps.risetopower.core.GameUnit
import com.nsheaps.risetopower.core.ProdItem
import com.nsheaps.risetopower.core.ResourceNode
import com.nsheaps.risetopower.core.ResourceType
import com.nsheaps.risetopower.core.Tech
import com.nsheaps.risetopower.core.UnitType
import com.nsheaps.risetopower.core.World
import kotlin.math.max
import kotlin.math.min

class HudButton(
    val rect: RectF,
    val label: String,
    val sub: String? = null,
    val enabled: Boolean = true,
    val active: Boolean = false,
    val icon: ((Canvas, RectF) -> Unit)? = null,
    val badge: String? = null,
    val progress: Float = -1f,
    val tooltip: String? = null,
    val action: (() -> Unit)? = null,
)

class Message(val text: String, val color: Int, var age: Float = 0f)

/** Heads-up display: drawn on top of the world and hit-tested for touches. */
class Hud(private val view: GameView, private val world: World, private val humanId: Int, private val renderer: Renderer, private val ui: UiState, private val d: Float) {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD) }
    private val plain = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val matrix = Matrix()

    val buttons = ArrayList<HudButton>()
    private val panels = ArrayList<RectF>()
    val messages = ArrayList<Message>()
    var pressed: HudButton? = null
    var tooltip: String? = null
    private var tooltipTime = 0f

    // Layout
    var w = 1f
    var h = 1f
    val topH get() = 34f * d
    val miniRect = RectF()
    var menu = Menu.NONE
    var buildPage = BuildPage.NONE
    var commandPage = 0
    var confirmDelete = false
    private var deleteTimer = 0f

    enum class Menu { NONE, PAUSE, GAME_OVER, STATS }
    enum class BuildPage { NONE, ECONOMY, MILITARY }

    private val player get() = world.players[humanId]

    fun layout(width: Float, height: Float) {
        w = width; h = height
        val mw = min(190f * d, width * 0.24f)
        miniRect.set(8f * d, height - mw / 2f - 10f * d, 8f * d + mw, height - 10f * d)
    }

    fun hitPanel(x: Float, y: Float): Boolean {
        if (menu != Menu.NONE) return true
        if (y < topH) return true
        for (p in panels) if (p.contains(x, y)) return true
        return false
    }

    fun buttonAt(x: Float, y: Float): HudButton? {
        for (i in buttons.indices.reversed()) {
            val b = buttons[i]
            if (b.rect.contains(x, y)) return b
        }
        return null
    }

    fun message(text: String, color: Int = 0xFFF5E9CF.toInt()) {
        // Collapse repeats so alerts don't flood the log.
        messages.removeAll { it.text == text }
        messages += Message(text, color)
        while (messages.size > 5) messages.removeAt(0)
    }

    fun showTooltip(t: String?) {
        tooltip = t
        tooltipTime = 4f
    }

    // ------------------------------------------------------------------ drawing

    fun draw(c: Canvas, dt: Float) {
        buttons.clear()
        panels.clear()
        if (confirmDelete) {
            deleteTimer += dt
            if (deleteTimer > 3f) { confirmDelete = false; deleteTimer = 0f }
        } else deleteTimer = 0f
        drawTopBar(c)
        drawTools(c)
        drawMinimap(c)
        drawSelectionPanels(c)
        drawMessages(c, dt)
        drawModeBanner(c)
        if (ui.boxActive) {
            fill.color = 0x2280FF80
            c.drawRect(ui.box, fill)
            stroke.color = 0xFF80FF80.toInt(); stroke.strokeWidth = 1.5f * d
            c.drawRect(ui.box, stroke)
        }
        if (tooltipTime > 0f && tooltip != null) {
            tooltipTime -= dt
            drawTooltip(c, tooltip!!)
        }
        when (menu) {
            Menu.PAUSE -> drawPauseMenu(c)
            Menu.GAME_OVER, Menu.STATS -> drawGameOver(c)
            Menu.NONE -> {}
        }
    }

    private fun panel(c: Canvas, r: RectF, alpha: Int = 0xE0) {
        fill.color = Color.argb(alpha, 32, 24, 16)
        c.drawRoundRect(r, 8f * d, 8f * d, fill)
        stroke.color = 0xFF8A6A3A.toInt(); stroke.strokeWidth = 1.5f * d
        c.drawRoundRect(r, 8f * d, 8f * d, stroke)
        panels += RectF(r)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = 0xFFF5E9CF.toInt(), align: Paint.Align = Paint.Align.LEFT, bold: Boolean = true, maxWidth: Float = 0f) {
        val p = if (bold) text else plain
        p.textSize = size * d
        if (maxWidth > 0f) {
            val tw = p.measureText(s)
            if (tw > maxWidth) p.textSize = p.textSize * maxWidth / tw
        }
        p.color = color
        p.textAlign = align
        c.drawText(s, x, y, p)
    }

    private fun resIcon(c: Canvas, r: ResourceType, x: Float, y: Float, rad: Float) {
        val col = when (r) {
            ResourceType.FOOD -> 0xFFD0453A.toInt()
            ResourceType.WOOD -> 0xFF9A6A3A.toInt()
            ResourceType.GOLD -> 0xFFF2C640.toInt()
            ResourceType.STONE -> 0xFFB4B4B0.toInt()
        }
        fill.color = 0xFF1A120A.toInt()
        c.drawCircle(x, y, rad + 1.5f * d, fill)
        fill.color = col
        c.drawCircle(x, y, rad, fill)
        fill.color = 0x55FFFFFF
        c.drawCircle(x - rad * 0.3f, y - rad * 0.3f, rad * 0.35f, fill)
    }

    private fun drawTopBar(c: Canvas) {
        val r = RectF(0f, 0f, w, topH)
        fill.color = 0xE0201810.toInt()
        c.drawRect(r, fill)
        fill.color = 0xFF8A6A3A.toInt()
        c.drawRect(0f, topH - 1.5f * d, w, topH, fill)
        var x = 12f * d
        val cy = topH / 2f
        for (res in ResourceType.entries) {
            resIcon(c, res, x + 8f * d, cy, 8f * d)
            label(c, player[res].toInt().toString(), x + 21f * d, cy + 5.5f * d, 15f)
            x += 82f * d
        }
        val popColor = if (player.popUsed >= player.popCap) 0xFFFF7060.toInt() else 0xFFF5E9CF.toInt()
        label(c, "Pop ${player.popUsed}/${player.popCap}", x, cy + 5.5f * d, 15f, popColor)
        x += 100f * d
        label(c, player.age.displayName, x, cy + 5.5f * d, 15f, 0xFFE2B04A.toInt())
        // Clock and right side buttons.
        val t = world.time.toInt()
        val clock = "%d:%02d".format(t / 60, t % 60)
        val bw = 44f * d
        val menuR = RectF(w - bw - 6f * d, 3f * d, w - 6f * d, topH - 4f * d)
        val speedR = RectF(menuR.left - bw - 6f * d, menuR.top, menuR.left - 6f * d, menuR.bottom)
        label(c, clock, speedR.left - 10f * d, cy + 5.5f * d, 15f, align = Paint.Align.RIGHT)
        smallButton(c, HudButton(menuR, "☰") { view.togglePause() })
        smallButton(c, HudButton(speedR, view.speedLabel()) { view.cycleSpeed() })
    }

    private fun smallButton(c: Canvas, b: HudButton) {
        val pressed = pressed === b || (pressed != null && pressed!!.rect == b.rect)
        fill.color = if (pressed) 0xFF8A6A3A.toInt() else if (b.active) 0xFF5A7A3A.toInt() else 0xFF4A3220.toInt()
        c.drawRoundRect(b.rect, 6f * d, 6f * d, fill)
        stroke.color = if (b.active) 0xFFB0E070.toInt() else 0xFFB08A50.toInt()
        stroke.strokeWidth = 1.2f * d
        c.drawRoundRect(b.rect, 6f * d, 6f * d, stroke)
        label(c, b.label, b.rect.centerX(), b.rect.centerY() + 5f * d, 14f, align = Paint.Align.CENTER)
        buttons += b
    }

    private fun drawTools(c: Canvas) {
        val size = 46f * d
        var y = topH + 10f * d
        val x = 8f * d
        val idle = world.units.count { it.alive && it.owner == humanId && it.type == UnitType.VILLAGER && it.order == com.nsheaps.risetopower.core.OrderType.IDLE }
        val army = world.units.count { it.alive && it.owner == humanId && it.type.isMilitary && it.type != UnitType.SCOUT }
        fun tool(label: String, badge: String?, active: Boolean, icon: ((Canvas, RectF) -> Unit)?, action: () -> Unit) {
            val r = RectF(x, y, x + size, y + size)
            commandButton(c, HudButton(r, if (icon == null) label else "", null, true, active, icon, badge, action = action), small = true)
            panels += RectF(r)
            y += size + 6f * d
        }
        tool("Idle", if (idle > 0) "$idle" else null, false, { cc, rr -> renderer.drawUnitIcon(cc, UnitType.VILLAGER, player.color, inset(rr, 4f), player.age) }) { view.selectIdleVillager() }
        tool("Army", if (army > 0) "$army" else null, false, { cc, rr -> renderer.drawUnitIcon(cc, UnitType.SPEARMAN, player.color, inset(rr, 4f), player.age) }) { view.selectArmy() }
        tool("Box", null, view.boxMode, { cc, rr -> drawBoxIcon(cc, rr) }) { view.boxMode = !view.boxMode }
        tool("Home", null, false, { cc, rr -> renderer.drawBuildingIcon(cc, BuildingType.TOWN_CENTER, player.color, inset(rr, 3f), humanId) }) { view.goHome() }
    }

    private fun inset(r: RectF, v: Float) = RectF(r.left + v * d, r.top + v * d, r.right - v * d, r.bottom - v * d)

    private fun drawBoxIcon(c: Canvas, r: RectF) {
        stroke.color = 0xFFB0E070.toInt(); stroke.strokeWidth = 2f * d
        stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f * d, 3f * d), 0f)
        c.drawRect(r.left + 9f * d, r.top + 9f * d, r.right - 9f * d, r.bottom - 9f * d, stroke)
        stroke.pathEffect = null
    }

    private fun drawMinimap(c: Canvas) {
        val r = miniRect
        val bg = RectF(r.left - 4f * d, r.top - 4f * d, r.right + 4f * d, r.bottom + 4f * d)
        panel(c, bg, 0xC0)
        val layers = view.layers ?: return
        // Diamond: tile (x,y) -> (cx + (x - y) * k, top + (x + y) * k / 2).
        val mw = world.map.width.toFloat()
        val k = r.width() / (2f * mw)
        val vals = floatArrayOf(k, -k, r.centerX(), k / 2f, k / 2f, r.top, 0f, 0f, 1f)
        matrix.setValues(vals)
        c.drawBitmap(layers.minimap, matrix, bmp)
        fun mx(x: Float, y: Float) = r.centerX() + (x - y) * k
        fun my(x: Float, y: Float) = r.top + (x + y) * k / 2f
        val dot = max(1.5f * d, k * 1.2f)
        for (b in world.buildings) {
            if (!b.alive) continue
            val mine = b.owner == humanId || world.isAlly(humanId, b.owner)
            if (!mine && !world.isExploredBy(humanId, b.tx, b.ty)) continue
            fill.color = world.players[b.owner].color
            val bs = max(dot, b.type.size * k)
            c.drawRect(mx(b.x, b.y) - bs / 2, my(b.x, b.y) - bs / 2, mx(b.x, b.y) + bs / 2, my(b.x, b.y) + bs / 2, fill)
        }
        for (u in world.units) {
            if (!u.alive) continue
            if (u.owner != humanId && !world.isAlly(humanId, u.owner) && !world.isVisibleTo(humanId, u.x, u.y)) continue
            fill.color = if (ui.selection.contains(u.id)) Color.WHITE else world.players[u.owner].color
            c.drawRect(mx(u.x, u.y) - dot / 2, my(u.x, u.y) - dot / 2, mx(u.x, u.y) + dot / 2, my(u.x, u.y) + dot / 2, fill)
        }
        // Alerts flash on the minimap.
        for (m in view.pings) {
            stroke.color = Color.argb((255 * (1 - m.t / 3f)).coerceIn(0f, 255f).toInt(), 255, 60, 40)
            stroke.strokeWidth = 2f * d
            c.drawCircle(mx(m.x, m.y), my(m.x, m.y), (4f + (m.t % 1f) * 10f) * d, stroke)
        }
        // Camera view outline.
        val cam = view.camera
        val pts = floatArrayOf(0f, 0f, cam.viewW, 0f, cam.viewW, cam.viewH, 0f, cam.viewH)
        path.reset()
        for (i in 0 until 4) {
            val wx = cam.worldX(pts[i * 2], pts[i * 2 + 1]); val wy = cam.worldY(pts[i * 2], pts[i * 2 + 1])
            val px = mx(wx, wy).coerceIn(r.left, r.right); val py = my(wx, wy).coerceIn(r.top, r.bottom)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        stroke.color = Color.WHITE; stroke.strokeWidth = 1.2f * d
        c.drawPath(path, stroke)
    }

    /** Converts a minimap touch to world coordinates, or null if outside. */
    fun minimapToWorld(x: Float, y: Float): Pair<Float, Float>? {
        val r = miniRect
        if (!RectF(r.left - 4f * d, r.top - 4f * d, r.right + 4f * d, r.bottom + 4f * d).contains(x, y)) return null
        val k = r.width() / (2f * world.map.width)
        val a = (x - r.centerX()) / k
        val b = (y - r.top) / (k / 2f)
        val wx = (a + b) / 2f; val wy = (b - a) / 2f
        return Pair(wx.coerceIn(0f, world.map.width.toFloat()), wy.coerceIn(0f, world.map.height.toFloat()))
    }

    private fun drawMessages(c: Canvas, dt: Float) {
        var y = topH + 22f * d
        val x = 66f * d
        val it = messages.iterator()
        while (it.hasNext()) {
            val m = it.next()
            m.age += dt
            if (m.age > 7f) { it.remove(); continue }
            val a = if (m.age > 6f) (7f - m.age) else 1f
            text.textSize = 14f * d
            val tw = text.measureText(m.text)
            fill.color = Color.argb((140 * a).toInt(), 0, 0, 0)
            c.drawRoundRect(x - 6f * d, y - 15f * d, x + tw + 6f * d, y + 5f * d, 4f * d, 4f * d, fill)
            label(c, m.text, x, y, 14f, Color.argb((255 * a).toInt(), Color.red(m.color), Color.green(m.color), Color.blue(m.color)))
            y += 22f * d
        }
    }

    private fun drawModeBanner(c: Canvas) {
        val t = view.modeHint() ?: return
        text.textSize = 15f * d
        val tw = text.measureText(t)
        val r = RectF(w / 2f - tw / 2f - 14f * d, topH + 8f * d, w / 2f + tw / 2f + 14f * d, topH + 36f * d)
        panel(c, r, 0xD0)
        label(c, t, w / 2f, r.centerY() + 5f * d, 15f, 0xFFFFE070.toInt(), Paint.Align.CENTER)
    }

    private fun drawTooltip(c: Canvas, t: String) {
        val lines = t.split("\n")
        plain.textSize = 13f * d
        val tw = lines.maxOf { plain.measureText(it) }
        val lh = 17f * d
        val bottom = h - 132f * d
        val r = RectF(w - tw - 30f * d, bottom - lines.size * lh - 12f * d, w - 10f * d, bottom)
        panel(c, r, 0xF0)
        var y = r.top + 6f * d + lh - 4f * d
        for ((i, l) in lines.withIndex()) {
            label(c, l, r.left + 10f * d, y, 13f, if (i == 0) 0xFFFFE070.toInt() else 0xFFF5E9CF.toInt(), bold = i == 0)
            y += lh
        }
    }

    // ------------------------------------------------------------------ selection & commands

    private fun selected(): List<Entity> = ui.selection.mapNotNull { world.get(it) }

    private fun drawSelectionPanels(c: Canvas) {
        val sel = selected()
        val cmds = commandButtons(sel)
        if (sel.isEmpty() && cmds.isEmpty()) return
        val bs = 48f * d
        val gap = 5f * d
        val cols = 6
        val rows = 2
        val gridW = cols * bs + (cols - 1) * gap
        val gridH = rows * bs + gap
        val pad = 7f * d
        val gx = w - gridW - pad - 8f * d
        val gy = h - gridH - pad - 8f * d
        if (cmds.isNotEmpty()) {
            panel(c, RectF(gx - pad, gy - pad, gx + gridW + pad, gy + gridH + pad))
            val perPage = cols * rows
            val pages = if (cmds.size > perPage) (cmds.size + perPage - 2) / (perPage - 1) else 1
            if (commandPage >= pages) commandPage = 0
            val shown = if (pages == 1) cmds else {
                val start = commandPage * (perPage - 1)
                cmds.subList(start, min(cmds.size, start + perPage - 1)) + listOf(Cmd("More", "${commandPage + 1}/$pages", icon = { cc, rr -> label(cc, "»", rr.centerX(), rr.centerY() + 8f * d, 26f, align = Paint.Align.CENTER) }) { commandPage = (commandPage + 1) % pages })
            }
            for ((i, cmd) in shown.withIndex()) {
                val col = i % cols; val row = i / cols
                val r = RectF(gx + col * (bs + gap), gy + row * (bs + gap), gx + col * (bs + gap) + bs, gy + row * (bs + gap) + bs)
                commandButton(c, HudButton(r, cmd.label, cmd.sub, cmd.enabled, cmd.active, cmd.icon, cmd.badge, cmd.progress, cmd.tooltip, cmd.action))
            }
        }
        if (sel.isNotEmpty()) {
            val iw = 230f * d
            val ix = (if (cmds.isNotEmpty()) gx - pad - 8f * d else w - 8f * d) - iw
            val left = max(miniRect.right + 12f * d, ix)
            val r = RectF(left, gy - pad, left + iw, gy + gridH + pad)
            panel(c, r)
            drawInfo(c, sel, r)
        }
    }

    private fun commandButton(c: Canvas, b: HudButton, small: Boolean = false) {
        val isPressed = pressed != null && pressed!!.rect == b.rect
        val base = when {
            !b.enabled -> 0xFF3A3028.toInt()
            isPressed -> 0xFF9A7A4A.toInt()
            b.active -> 0xFF4E6A30.toInt()
            else -> 0xFF5A4028.toInt()
        }
        fill.color = base
        c.drawRoundRect(b.rect, 6f * d, 6f * d, fill)
        fill.color = 0x22FFFFFF
        c.drawRoundRect(b.rect.left + 2f * d, b.rect.top + 2f * d, b.rect.right - 2f * d, b.rect.centerY(), 5f * d, 5f * d, fill)
        stroke.color = if (b.active) 0xFFB0E070.toInt() else if (b.enabled) 0xFFC09A5A.toInt() else 0xFF6A5A48.toInt()
        stroke.strokeWidth = 1.3f * d
        c.drawRoundRect(b.rect, 6f * d, 6f * d, stroke)
        if (b.icon != null) {
            c.save()
            c.clipRect(b.rect)
            val ir = RectF(b.rect.left + 3f * d, b.rect.top + 2f * d, b.rect.right - 3f * d, b.rect.bottom - (if (b.sub != null) 12f else 3f) * d)
            b.icon.invoke(c, ir)
            c.restore()
            if (!b.enabled) {
                fill.color = 0x88201810.toInt()
                c.drawRoundRect(b.rect, 6f * d, 6f * d, fill)
            }
        } else {
            label(c, b.label, b.rect.centerX(), b.rect.centerY() + (if (b.sub != null) 0f else 5f) * d, if (small) 12f else 11f, if (b.enabled) 0xFFF5E9CF.toInt() else 0xFF8A7A68.toInt(), Paint.Align.CENTER, maxWidth = b.rect.width() - 4f * d)
        }
        if (b.sub != null) {
            fill.color = 0xAA000000.toInt()
            c.drawRect(b.rect.left + 1f * d, b.rect.bottom - 12f * d, b.rect.right - 1f * d, b.rect.bottom - 1f * d, fill)
            label(c, b.sub, b.rect.centerX(), b.rect.bottom - 3f * d, 8.5f, if (b.enabled) 0xFFFFE8B0.toInt() else 0xFF9A8A70.toInt(), Paint.Align.CENTER, bold = false, maxWidth = b.rect.width() - 4f * d)
        }
        if (b.badge != null) {
            fill.color = 0xFFC03A2A.toInt()
            c.drawCircle(b.rect.right - 6f * d, b.rect.top + 6f * d, 8f * d, fill)
            label(c, b.badge, b.rect.right - 6f * d, b.rect.top + 10f * d, 10f, Color.WHITE, Paint.Align.CENTER)
        }
        if (b.progress >= 0f) {
            fill.color = 0xAA000000.toInt()
            c.drawRect(b.rect.left + 3f * d, b.rect.top + 3f * d, b.rect.right - 3f * d, b.rect.top + 7f * d, fill)
            fill.color = 0xFF6CB8FF.toInt()
            c.drawRect(b.rect.left + 3f * d, b.rect.top + 3f * d, b.rect.left + 3f * d + (b.rect.width() - 6f * d) * b.progress, b.rect.top + 7f * d, fill)
        }
        buttons += b
    }

    class Cmd(
        val label: String,
        val sub: String? = null,
        val enabled: Boolean = true,
        val active: Boolean = false,
        val icon: ((Canvas, RectF) -> Unit)? = null,
        val badge: String? = null,
        val progress: Float = -1f,
        val tooltip: String? = null,
        val action: (() -> Unit)? = null,
    )

    private fun techIcon(t: Tech): (Canvas, RectF) -> Unit = { c, r ->
        val col = when (t.building) {
            BuildingType.MILL -> 0xFF7AA03A.toInt()
            BuildingType.LUMBER_CAMP -> 0xFF8A5A2A.toInt()
            BuildingType.MINING_CAMP -> 0xFFB0A060.toInt()
            BuildingType.BLACKSMITH -> 0xFF7A7A88.toInt()
            BuildingType.LIBRARY -> 0xFF4A6AA8.toInt()
            BuildingType.TEMPLE -> 0xFFE0D8C0.toInt()
            BuildingType.MARKET -> 0xFFE0B040.toInt()
            else -> 0xFFA86A3A.toInt()
        }
        val cx = r.centerX(); val cy = r.centerY()
        val rad = min(r.width(), r.height()) * 0.38f
        fill.color = 0xFF1A120A.toInt()
        c.drawCircle(cx, cy, rad + 2f * d, fill)
        fill.color = col
        c.drawCircle(cx, cy, rad, fill)
        label(c, t.displayName.take(2), cx, cy + 5f * d, 13f, 0xFF1A120A.toInt(), Paint.Align.CENTER)
    }

    private fun ageIcon(age: Age): (Canvas, RectF) -> Unit = { c, r ->
        val cx = r.centerX(); val cy = r.centerY()
        path.reset()
        path.moveTo(cx, cy - 14f * d); path.lineTo(cx + 12f * d, cy); path.lineTo(cx + 5f * d, cy)
        path.lineTo(cx + 5f * d, cy + 12f * d); path.lineTo(cx - 5f * d, cy + 12f * d); path.lineTo(cx - 5f * d, cy)
        path.lineTo(cx - 12f * d, cy); path.close()
        fill.color = 0xFFE2B04A.toInt()
        c.drawPath(path, fill)
        label(c, ROMAN[age.ordinal], cx, cy + 9f * d, 10f, 0xFF1A120A.toInt(), Paint.Align.CENTER)
    }

    private fun textIcon(s: String, color: Int = 0xFFF5E9CF.toInt(), size: Float = 20f): (Canvas, RectF) -> Unit = { c, r ->
        label(c, s, r.centerX(), r.centerY() + size * 0.35f * d, size, color, Paint.Align.CENTER)
    }

    private fun costTip(name: String, cost: com.nsheaps.risetopower.core.Cost, desc: String, extra: String? = null): String {
        val sb = StringBuilder(name)
        sb.append("\nCost: ").append(cost.label())
        if (extra != null) sb.append("\n").append(extra)
        sb.append("\n").append(desc)
        return sb.toString()
    }

    private fun commandButtons(sel: List<Entity>): List<Cmd> {
        val out = ArrayList<Cmd>()
        if (view.hasMode()) {
            out += Cmd("Cancel", icon = textIcon("✕", 0xFFFF8070.toInt())) { view.cancelMode() }
            return out
        }
        if (sel.isEmpty()) return out
        val p = player
        val first = sel.first()
        if (first is Building && first.owner == humanId) {
            val b = first
            if (!b.constructed) {
                out += Cmd("Cancel", "Refund", icon = textIcon("✕", 0xFFFF8070.toInt())) { view.deleteSelected() }
                return out
            }
            for (t in b.type.trains()) {
                val locked = p.age < t.minAge
                val queued = b.queue.count { it is ProdItem.Train && it.unit == t }
                out += Cmd(
                    p.unitName(t), t.cost.label(), !locked && p.canAfford(t.cost),
                    icon = { c, r -> renderer.drawUnitIcon(c, t, p.color, r, p.age) },
                    badge = if (queued > 0) "$queued" else null,
                    tooltip = costTip(p.unitName(t), t.cost, t.description, if (locked) "Requires ${t.minAge.displayName}" else statsLine(t)),
                ) { view.train(b, t) }
            }
            if (b.type == BuildingType.TOWN_CENTER) {
                val next = p.age.next
                val adv = b.queue.firstOrNull { it is ProdItem.Advance }
                if (next != null) {
                    out += Cmd(
                        "Advance", next.advanceCost.label(), adv == null && !world.isAdvancing(humanId) && p.canAfford(next.advanceCost),
                        icon = ageIcon(next), progress = if (adv != null && b.queue.first() === adv) b.queueProgress else -1f,
                        tooltip = costTip("Advance to the ${next.displayName}", next.advanceCost, "Unlocks new buildings, units and technologies. All units are upgraded."),
                    ) { view.advance(b) }
                }
            }
            for (t in b.type.researches()) {
                if (p.has(t)) continue
                if (t.requires != null && !p.has(t.requires!!)) continue
                val queuedHere = b.queue.any { it is ProdItem.Research && it.tech == t }
                val cost = p.techCost(t)
                val locked = p.age < t.minAge
                out += Cmd(
                    t.displayName, cost.label(), !locked && !queuedHere && world.canResearch(p, t) && p.canAfford(cost),
                    active = queuedHere, icon = techIcon(t),
                    tooltip = costTip(t.displayName, cost, t.description, if (locked) "Requires ${t.minAge.displayName}" else null),
                ) { view.research(b, t) }
            }
            if (b.type == BuildingType.MARKET) {
                for (r in listOf(ResourceType.FOOD, ResourceType.WOOD, ResourceType.STONE)) {
                    val buy = world.buyPrice(humanId, r).toInt()
                    out += Cmd("Buy", "${buy}G", p[ResourceType.GOLD] >= buy, icon = { c, rr -> resIcon(c, r, rr.centerX(), rr.centerY(), 10f * d); label(c, "+", rr.centerX() + 13f * d, rr.centerY() - 4f * d, 14f, 0xFF80FF80.toInt(), Paint.Align.CENTER) },
                        tooltip = "Buy 100 ${r.displayName}\nCost: $buy gold") { view.trade(r, true) }
                }
                for (r in listOf(ResourceType.FOOD, ResourceType.WOOD, ResourceType.STONE)) {
                    val sell = world.sellPrice(humanId, r).toInt()
                    out += Cmd("Sell", "+${sell}G", p[r] >= 100f, icon = { c, rr -> resIcon(c, r, rr.centerX(), rr.centerY(), 10f * d); label(c, "−", rr.centerX() + 13f * d, rr.centerY() - 4f * d, 14f, 0xFFFF8070.toInt(), Paint.Align.CENTER) },
                        tooltip = "Sell 100 ${r.displayName}\nGain: $sell gold") { view.trade(r, false) }
                }
            }
            if (b.type.trains().isNotEmpty() || b.type == BuildingType.TOWN_CENTER) {
                out += Cmd("Rally", icon = textIcon("⚑", 0xFFE2B04A.toInt()), tooltip = "Set rally point\nNew units gather there. Citizens rallied to resources start gathering.") { view.startRally() }
            }
            out += deleteCmd()
            return out
        }
        val mine = sel.filterIsInstance<GameUnit>().filter { it.owner == humanId }
        if (mine.isEmpty()) return out
        val villagers = mine.any { it.type == UnitType.VILLAGER }
        val military = mine.any { it.type.isMilitary && it.type != UnitType.HEALER }
        if (villagers) {
            when (buildPage) {
                BuildPage.NONE -> {
                    out += Cmd("Economy", icon = { c, r -> renderer.drawBuildingIcon(c, BuildingType.HOUSE, p.color, r, humanId) }, tooltip = "Build economic buildings") { buildPage = BuildPage.ECONOMY; commandPage = 0 }
                    out += Cmd("Military", icon = { c, r -> renderer.drawBuildingIcon(c, BuildingType.BARRACKS, p.color, r, humanId) }, tooltip = "Build military buildings") { buildPage = BuildPage.MILITARY; commandPage = 0 }
                    if (mine.any { it.carryAmount > 0f }) out += Cmd("Return", icon = textIcon("⇧"), tooltip = "Return carried resources") { view.returnResources() }
                }
                BuildPage.ECONOMY, BuildPage.MILITARY -> {
                    val list = if (buildPage == BuildPage.ECONOMY) BuildMenu.economy else BuildMenu.military
                    out += Cmd("Back", icon = textIcon("←")) { buildPage = BuildPage.NONE; commandPage = 0 }
                    for (t in list) {
                        val locked = p.age < t.minAge
                        out += Cmd(
                            t.displayName, t.cost.label(), !locked && p.canAfford(t.cost),
                            icon = { c, r -> renderer.drawBuildingIcon(c, t, p.color, r, humanId) },
                            tooltip = costTip(t.displayName, t.cost, t.description, if (locked) "Requires ${t.minAge.displayName}" else null),
                        ) { view.startPlacement(t) }
                    }
                    return out
                }
            }
        }
        if (military) {
            out += Cmd("Attack", icon = textIcon("⚔", 0xFFFF9080.toInt()), tooltip = "Attack-move\nUnits attack enemies they meet on the way.") { view.startAttackMove() }
        }
        out += Cmd("Stop", icon = textIcon("■", 0xFFE0E0E0.toInt(), 16f)) { view.stopSelected() }
        out += deleteCmd()
        return out
    }

    private fun deleteCmd() = Cmd(
        if (confirmDelete) "Confirm" else "Delete", if (confirmDelete) "tap again" else null, active = confirmDelete,
        icon = textIcon("🗑", 0xFFFF8070.toInt(), 18f),
    ) {
        if (confirmDelete) { confirmDelete = false; view.deleteSelected() } else confirmDelete = true
    }

    private fun statsLine(t: UnitType): String {
        val p = player
        return "HP ${p.unitMaxHp(t).toInt()}  Atk ${p.unitAttack(t).toInt()}  Armor ${p.unitMeleeArmor(t).toInt()}/${p.unitPierceArmor(t).toInt()}" +
            (if (t.ranged) "  Range ${p.unitRange(t).toInt()}" else "") +
            (if (t.bonus.isNotEmpty()) "\nBonus vs " + t.bonus.keys.joinToString(", ") { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } } else "")
    }

    private fun drawInfo(c: Canvas, sel: List<Entity>, r: RectF) {
        val pad = 8f * d
        val iconR = RectF(r.left + pad, r.top + pad, r.left + pad + 52f * d, r.top + pad + 52f * d)
        val tx = iconR.right + 8f * d
        var ty = r.top + pad + 14f * d
        if (sel.size == 1 || sel.first() !is GameUnit) {
            val e = sel.first()
            val owner = world.players.getOrNull(e.owner)
            val color = owner?.color ?: 0xFFAAAAAA.toInt()
            fill.color = 0x55000000
            c.drawRoundRect(iconR, 6f * d, 6f * d, fill)
            when (e) {
                is GameUnit -> {
                    val p = world.players[e.owner]
                    renderer.drawUnitIcon(c, e.type, color, iconR, p.age)
                    label(c, p.unitName(e.type), tx, ty, 14f, 0xFFFFE070.toInt()); ty += 16f * d
                    label(c, if (e.owner == humanId) "" else p.name, tx, ty, 11f, color, bold = false); if (e.owner != humanId) ty += 14f * d
                    label(c, "HP ${e.hp.toInt()}/${e.maxHp.toInt()}", tx, ty, 12f, bold = false); ty += 15f * d
                    if (e.type.attack > 0) {
                        label(c, "Atk ${p.unitAttack(e.type).toInt()}  Armor ${p.unitMeleeArmor(e.type).toInt()}/${p.unitPierceArmor(e.type).toInt()}", tx, ty, 12f, bold = false); ty += 15f * d
                    }
                    if (e.type == UnitType.VILLAGER && e.carryAmount > 0f) {
                        label(c, "Carrying ${e.carryAmount.toInt()} ${e.carryType?.displayName ?: ""}", tx, ty, 12f, bold = false); ty += 15f * d
                    } else if (e.kills > 0) {
                        label(c, "Kills ${e.kills}", tx, ty, 12f, bold = false); ty += 15f * d
                    }
                    label(c, orderText(e), r.left + pad, r.bottom - pad, 11f, 0xFFBFAF90.toInt(), bold = false)
                }
                is Building -> {
                    val p = world.players[e.owner]
                    renderer.drawBuildingIcon(c, e.type, color, iconR, e.owner)
                    label(c, e.type.displayName, tx, ty, 14f, 0xFFFFE070.toInt()); ty += 16f * d
                    if (e.owner != humanId) { label(c, p.name, tx, ty, 11f, color, bold = false); ty += 14f * d }
                    label(c, "HP ${e.hp.toInt()}/${e.maxHp.toInt()}", tx, ty, 12f, bold = false); ty += 15f * d
                    if (!e.constructed) { label(c, "Building ${(e.progress * 100).toInt()}%", tx, ty, 12f, bold = false); ty += 15f * d }
                    if (e.type.pop > 0) { label(c, "+${e.type.pop} population", tx, ty, 12f, bold = false); ty += 15f * d }
                    if (e.type == BuildingType.FARM) { label(c, if (e.farmerId >= 0) "Being farmed" else "Idle farm", tx, ty, 12f, bold = false); ty += 15f * d }
                    if (e.wonderTimer > 0f) { label(c, "Victory in ${e.wonderTimer.toInt()}s", tx, ty, 12f, 0xFFFFE070.toInt()); ty += 15f * d }
                    if (e.owner == humanId && e.queue.isNotEmpty()) drawQueue(c, e, r)
                }
                is ResourceNode -> {
                    fill.color = 0x55000000
                    label(c, e.kind.displayName, tx, ty, 14f, 0xFFFFE070.toInt()); ty += 16f * d
                    label(c, "${e.amount.toInt()} ${e.kind.resource.displayName}", tx, ty, 12f, bold = false)
                    resIcon(c, e.kind.resource, iconR.centerX(), iconR.centerY(), 16f * d)
                }
            }
        } else {
            // Multi selection: counts per type.
            val counts = sel.filterIsInstance<GameUnit>().groupBy { it.type }
            var x = r.left + pad
            var y = r.top + pad
            val cell = 40f * d
            label(c, "${sel.size} units", r.right - pad, r.bottom - pad, 11f, 0xFFBFAF90.toInt(), Paint.Align.RIGHT, bold = false)
            for ((t, list) in counts) {
                val owner = list.first().owner
                val cr = RectF(x, y, x + cell, y + cell)
                fill.color = 0x55000000
                c.drawRoundRect(cr, 5f * d, 5f * d, fill)
                renderer.drawUnitIcon(c, t, world.players[owner].color, RectF(cr.left + 2f * d, cr.top + 2f * d, cr.right - 2f * d, cr.bottom - 2f * d), world.players[owner].age)
                label(c, "${list.size}", cr.right - 3f * d, cr.bottom - 3f * d, 11f, Color.WHITE, Paint.Align.RIGHT)
                val avg = list.sumOf { it.hp.toDouble() } / list.sumOf { it.maxHp.toDouble() }
                fill.color = 0xFF4CD04C.toInt()
                c.drawRect(cr.left + 2f * d, cr.bottom - 1.5f * d, cr.left + 2f * d + (cell - 4f * d) * avg.toFloat(), cr.bottom, fill)
                val tapType = t
                buttons += HudButton(cr, "", action = { view.narrowSelection(tapType) })
                x += cell + 4f * d
                if (x + cell > r.right - pad) { x = r.left + pad; y += cell + 4f * d }
                if (y + cell > r.bottom) break
            }
        }
    }

    private fun drawQueue(c: Canvas, b: Building, r: RectF) {
        val cell = 26f * d
        var x = r.left + 8f * d
        val y = r.bottom - cell - 6f * d
        val p = world.players[b.owner]
        for ((i, q) in b.queue.withIndex()) {
            if (x + cell > r.right - 4f * d) break
            val cr = RectF(x, y, x + cell, y + cell)
            fill.color = if (i == 0) 0xFF4E6A30.toInt() else 0x66000000
            c.drawRoundRect(cr, 4f * d, 4f * d, fill)
            when (q) {
                is ProdItem.Train -> renderer.drawUnitIcon(c, q.unit, p.color, cr, p.age)
                is ProdItem.Research -> techIcon(q.tech)(c, cr)
                is ProdItem.Advance -> ageIcon(q.age)(c, cr)
            }
            if (i == 0) {
                fill.color = 0xFF6CB8FF.toInt()
                c.drawRect(cr.left, cr.bottom - 3f * d, cr.left + cr.width() * b.queueProgress, cr.bottom, fill)
            }
            val idx = i
            buttons += HudButton(cr, "", action = { view.cancelQueue(b, idx) })
            x += cell + 3f * d
        }
    }

    private fun orderText(u: GameUnit): String = when (u.order) {
        com.nsheaps.risetopower.core.OrderType.IDLE -> "Idle"
        com.nsheaps.risetopower.core.OrderType.MOVE -> "Moving"
        com.nsheaps.risetopower.core.OrderType.ATTACK_MOVE -> "Attack-moving"
        com.nsheaps.risetopower.core.OrderType.ATTACK -> "Attacking"
        com.nsheaps.risetopower.core.OrderType.GATHER -> "Gathering ${u.gatherResource?.displayName?.lowercase() ?: ""}"
        com.nsheaps.risetopower.core.OrderType.BUILD -> "Building"
        com.nsheaps.risetopower.core.OrderType.RETURN -> "Returning ${u.carryType?.displayName?.lowercase() ?: ""}"
        com.nsheaps.risetopower.core.OrderType.HEAL -> "Healing"
    }

    // ------------------------------------------------------------------ menus

    private fun dim(c: Canvas) {
        fill.color = 0xAA000000.toInt()
        c.drawRect(0f, 0f, w, h, fill)
    }

    private fun menuButton(c: Canvas, r: RectF, label: String, enabled: Boolean = true, action: () -> Unit) {
        commandButton(c, HudButton(r, "", null, enabled, false, { cc, rr -> label(cc, label, rr.centerX(), rr.centerY() + 6f * d, 16f, align = Paint.Align.CENTER) }, action = action))
    }

    private fun drawPauseMenu(c: Canvas) {
        dim(c)
        val pw = 300f * d
        val items = listOf<Pair<String, () -> Unit>>(
            "Resume" to { view.togglePause() },
            "Save Game" to { view.saveGame() },
            "Statistics" to { menu = Menu.STATS },
            "Resign" to { view.resign() },
            "Save & Quit" to { view.quitToMenu(save = true) },
        )
        val bh = 44f * d
        val ph = items.size * (bh + 8f * d) + 64f * d
        val r = RectF(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
        panel(c, r, 0xF0)
        label(c, "Paused", r.centerX(), r.top + 34f * d, 22f, 0xFFE2B04A.toInt(), Paint.Align.CENTER)
        var y = r.top + 50f * d
        for ((name, act) in items) {
            menuButton(c, RectF(r.left + 24f * d, y, r.right - 24f * d, y + bh), name, action = act)
            y += bh + 8f * d
        }
    }

    private fun drawGameOver(c: Canvas) {
        dim(c)
        val pw = min(w - 40f * d, 620f * d)
        val rows = world.players.size
        val ph = 150f * d + rows * 24f * d + 60f * d
        val r = RectF(w / 2 - pw / 2, h / 2 - ph / 2, w / 2 + pw / 2, h / 2 + ph / 2)
        panel(c, r, 0xF4)
        val me = player
        val title = when {
            menu == Menu.STATS && !world.gameOver -> "Statistics"
            world.gameOver && world.winnerTeam == me.team -> "Victory!"
            else -> "Defeat"
        }
        label(c, title, r.centerX(), r.top + 42f * d, 30f, if (title == "Defeat") 0xFFE05A4A.toInt() else 0xFFE2B04A.toInt(), Paint.Align.CENTER)
        val msg = view.lastVictoryMessage
        if (msg != null && world.gameOver) label(c, msg, r.centerX(), r.top + 66f * d, 13f, 0xFFBFAF90.toInt(), Paint.Align.CENTER, bold = false)
        val cols = listOf("Player", "Age", "Score", "Gathered", "Kills", "Lost", "Razed", "Techs")
        val colX = listOf(0f, 0.27f, 0.45f, 0.56f, 0.7f, 0.78f, 0.86f, 0.94f)
        var y = r.top + 96f * d
        val left = r.left + 18f * d
        val width = pw - 36f * d
        for ((i, cname) in cols.withIndex()) label(c, cname, left + colX[i] * width, y, 12f, 0xFFE2B04A.toInt(), if (i == 0) Paint.Align.LEFT else Paint.Align.CENTER)
        y += 22f * d
        for (p in world.players) {
            val vals = listOf(
                p.name + (if (p.defeated) " ✝" else ""), ROMAN[p.age.ordinal], "${world.score(p)}", "${p.gathered.sum().toInt()}",
                "${p.unitsKilled}", "${p.unitsLost}", "${p.buildingsDestroyed}", "${p.techCount}",
            )
            for ((i, v) in vals.withIndex()) label(c, v, left + colX[i] * width, y, 12f, if (i == 0) p.color else 0xFFF5E9CF.toInt(), if (i == 0) Paint.Align.LEFT else Paint.Align.CENTER, bold = i == 0)
            y += 24f * d
        }
        val bw = 160f * d
        val by = r.bottom - 54f * d
        if (world.gameOver || me.defeated) {
            menuButton(c, RectF(r.centerX() - bw - 8f * d, by, r.centerX() - 8f * d, by + 40f * d), "Keep watching") { menu = Menu.NONE; view.spectate() }
            menuButton(c, RectF(r.centerX() + 8f * d, by, r.centerX() + bw + 8f * d, by + 40f * d), "Main Menu") { view.quitToMenu(save = false) }
        } else {
            menuButton(c, RectF(r.centerX() - bw / 2, by, r.centerX() + bw / 2, by + 40f * d), "Back") { menu = Menu.PAUSE }
        }
    }

    companion object {
        val ROMAN = listOf("I", "II", "III", "IV", "V")
    }
}
