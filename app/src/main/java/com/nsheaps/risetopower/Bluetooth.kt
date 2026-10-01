package com.nsheaps.risetopower

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.nsheaps.risetopower.core.net.Link
import com.nsheaps.risetopower.core.net.StreamLink
import java.io.IOException
import java.util.UUID

private const val TAG = "RiseToPowerBt"

/**
 * Bluetooth Classic (RFCOMM) connections between phones. The host listens for the game's service
 * and every joining phone connects to it; each connection becomes a message [Link].
 *
 * Connections are unauthenticated ("insecure") first, so phones need not be paired; if a phone
 * refuses that, a regular paired connection is tried.
 */
@SuppressLint("MissingPermission") // Callers check [Bluetooth.hasPermissions] first.
object Bluetooth {
    val SERVICE_UUID: UUID = UUID.fromString("6b1f3a52-2c7e-4d0b-9a35-5e8c0f4d2b71")
    const val SERVICE_NAME = "Rise to Power"

    fun adapter(ctx: Context): BluetoothAdapter? = ctx.getSystemService(BluetoothManager::class.java)?.adapter

    /** Runtime permissions needed to host, find and join games. */
    fun permissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
    } else {
        // Discovering nearby devices needs location access before Android 12.
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermissions(ctx: Context) = permissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** This phone's Bluetooth name, used as the player's name. */
    fun localName(ctx: Context): String {
        val name = try { adapter(ctx)?.name } catch (_: SecurityException) { null }
        return name?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Player"
    }

    fun deviceName(d: BluetoothDevice): String =
        (try { d.name } catch (_: SecurityException) { null })?.takeIf { it.isNotBlank() } ?: d.address

    fun pairedDevices(ctx: Context): List<BluetoothDevice> =
        try { adapter(ctx)?.bondedDevices?.toList() ?: emptyList() } catch (_: SecurityException) { emptyList() }

    fun link(socket: BluetoothSocket, name: String): Link = StreamLink(socket.inputStream, socket.outputStream, socket, name)

    /** Connects to a host; blocks, so call it off the main thread. */
    fun connect(ctx: Context, device: BluetoothDevice): Link {
        try { adapter(ctx)?.cancelDiscovery() } catch (_: SecurityException) {}
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID) },
            { device.createRfcommSocketToServiceRecord(SERVICE_UUID) },
        )
        var last: IOException? = null
        for (make in attempts) {
            val socket = make()
            try {
                socket.connect()
                return link(socket, "bt-host")
            } catch (e: IOException) {
                last = e
                try { socket.close() } catch (_: IOException) {}
            }
        }
        throw last ?: IOException("Could not connect")
    }

    /** Accepts players joining this phone's game until [close]d. */
    class Server(ctx: Context, private val onJoin: (Link) -> Unit) {
        // An unauthenticated listener also accepts phones that connect over a paired link.
        private val sockets = listOfNotNull(
            open { adapter(ctx)?.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID) },
        )
        @Volatile private var closed = false

        val isListening get() = sockets.isNotEmpty() && !closed

        init {
            for ((i, s) in sockets.withIndex()) {
                Thread({
                    while (!closed) {
                        val socket = try { s.accept() } catch (e: IOException) { if (!closed) Log.w(TAG, "accept failed", e); break }
                        onJoin(link(socket, "bt-guest"))
                    }
                }, "bt-accept-$i").apply { isDaemon = true }.start()
            }
        }

        fun close() {
            closed = true
            for (s in sockets) try { s.close() } catch (_: IOException) {}
        }

        private fun open(f: () -> BluetoothServerSocket?): BluetoothServerSocket? =
            try { f() } catch (e: Exception) { Log.w(TAG, "listen failed", e); null }
    }
}
