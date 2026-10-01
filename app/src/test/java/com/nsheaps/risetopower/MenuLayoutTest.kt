package com.nsheaps.risetopower

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Lays out the menu screens on common phone sizes and font scales and checks that no text is
 * clipped: every line must fit inside its view, and the title must stay on one line.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MenuLayoutTest {
    private val outDir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-xxhdpi")
    fun largePhoneLandscape() = checkAllScreens("pixel6")

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun smallPhoneLandscape() = checkAllScreens("small")

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xhdpi")
    fun smallPhoneLargeFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        checkAllScreens("small_largefont")
    }

    private fun checkAllScreens(name: String) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val title = check(activity, "main", name)
        assertEquals("Title should be one line", "RISE TO POWER", title?.text.toString())
        assertEquals("Title should be one line", 1, title!!.layout.lineCount)
        click(activity, "New Game")
        check(activity, "setup", name)
        for (label in listOf("Start", "Back")) {
            val b = textViews(activity.window.decorView).first { it is Button && it.text == label }
            val loc = IntArray(2)
            b.getLocationInWindow(loc)
            assertTrue("[$name] $label button must be visible without scrolling", loc[1] >= 0 && loc[1] + b.height <= activity.resources.displayMetrics.heightPixels)
        }
        activity.onBackPressed()
        click(activity, "How to Play")
        check(activity, "help", name)
        activity.onBackPressed()

        // Bluetooth multiplayer: with permissions granted and Bluetooth on, the lobbies open.
        val app = shadowOf(activity.application)
        app.grantPermissions(*Bluetooth.permissions())
        shadowOf(Bluetooth.adapter(activity)!!).setEnabled(true)
        click(activity, "Multiplayer")
        check(activity, "multiplayer", name)
        click(activity, "Host Game")
        shadowOf(Looper.getMainLooper()).idle()
        check(activity, "host", name)
        for (label in listOf("Start", "Back")) {
            val b = textViews(activity.window.decorView).first { it is Button && it.text == label }
            val loc = IntArray(2)
            b.getLocationInWindow(loc)
            assertTrue("[$name] host $label button must be visible", loc[1] >= 0 && loc[1] + b.height <= activity.resources.displayMetrics.heightPixels)
        }
        assertTrue("Start needs a joined player", !textViews(activity.window.decorView).first { it is Button && it.text == "Start" }.isEnabled)
        activity.onBackPressed()
        click(activity, "Join Game")
        shadowOf(Looper.getMainLooper()).idle()
        check(activity, "join", name)
        activity.onBackPressed()
        activity.onBackPressed()
        assertEquals("main", activity.root.tag)
    }

    /** Lays out the current screen, saves it and asserts all text fits. Returns the title view. */
    private fun check(activity: Activity, screen: String, name: String): TextView? {
        val dm = activity.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        FileOutputStream(File(outDir, "menu_${screen}_$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        var title: TextView? = null
        for (tv in textViews(root)) {
            val layout = tv.layout ?: continue
            if (tv.text.isEmpty()) continue
            if (title == null && tv !is Button) title = tv
            val availW = tv.width - tv.totalPaddingLeft - tv.totalPaddingRight
            val availH = tv.height - tv.totalPaddingTop - tv.totalPaddingBottom
            for (i in 0 until layout.lineCount) {
                assertTrue("[$screen/$name] '${tv.text}' line $i is wider than its view (${layout.getLineMax(i)} > $availW)",
                    layout.getLineMax(i) <= availW + 1f)
            }
            assertTrue("[$screen/$name] '${tv.text}' is taller than its view (${layout.height} > $availH)", layout.height <= availH + 1)
            assertTrue("[$screen/$name] '${tv.text}' is ellipsized", (0 until layout.lineCount).none { layout.getEllipsisCount(it) > 0 })
            val loc = IntArray(2)
            tv.getLocationInWindow(loc)
            assertTrue("[$screen/$name] '${tv.text}' extends past the screen edge", loc[0] >= 0 && loc[0] + tv.width <= w)
        }
        return title
    }

    private fun click(activity: Activity, label: String) {
        val b = textViews(activity.window.decorView).first { it is Button && it.text == label }
        b.performClick()
    }

    private fun textViews(v: View): List<TextView> = when (v) {
        is TextView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { textViews(v.getChildAt(it)) }
        else -> emptyList()
    }
}
