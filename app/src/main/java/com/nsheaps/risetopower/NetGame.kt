package com.nsheaps.risetopower

import com.nsheaps.risetopower.core.net.Lockstep
import java.io.Closeable

/** Hands a connected multiplayer session from the lobby to the game screen. */
object NetGame {
    /** A session and, for the host, the Bluetooth listener that lets dropped players back in. */
    class Game(val session: Lockstep, val listener: Closeable?)

    @Volatile private var pending: Game? = null

    fun hand(session: Lockstep, listener: Closeable? = null) { pending = Game(session, listener) }

    fun take(): Game? = pending.also { pending = null }
}
