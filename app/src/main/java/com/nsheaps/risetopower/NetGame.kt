package com.nsheaps.risetopower

import com.nsheaps.risetopower.core.net.Lockstep

/** Hands a connected multiplayer session from the lobby to the game screen. */
object NetGame {
    @Volatile private var pending: Lockstep? = null

    fun hand(session: Lockstep) { pending = session }

    fun take(): Lockstep? = pending.also { pending = null }
}
