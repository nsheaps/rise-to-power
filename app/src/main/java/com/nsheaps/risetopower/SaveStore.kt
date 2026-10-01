package com.nsheaps.risetopower

import android.content.Context
import com.nsheaps.risetopower.core.GameSave
import com.nsheaps.risetopower.core.World
import java.io.File

/** Single slot save game stored in the app's private files directory. */
object SaveStore {
    private const val FILE = "savegame.r2p"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun hasSave(ctx: Context) = file(ctx).exists()

    fun save(ctx: Context, world: World) {
        val bytes = GameSave.write(world)
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file(ctx))
    }

    fun load(ctx: Context): World? = try {
        GameSave.read(file(ctx).readBytes())
    } catch (e: Exception) {
        null
    }

    fun delete(ctx: Context) {
        file(ctx).delete()
    }
}
