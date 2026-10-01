package com.nsheaps.risetopower.core.net

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A reliable, ordered, message based connection to one other device. */
interface Link {
    /** Queues a message for sending; never blocks. */
    fun send(message: ByteArray)

    /** Returns the next received message, or null if none is waiting. */
    fun poll(): ByteArray?

    /** Waits up to [timeoutMs] for a message. */
    fun take(timeoutMs: Long): ByteArray?

    val isClosed: Boolean
    fun close()
}

/**
 * A [Link] over a pair of byte streams (a Bluetooth socket, or pipes in tests). Messages are
 * length-prefixed; a reader and a writer thread keep the game thread from ever blocking on I/O.
 */
class StreamLink(input: InputStream, output: OutputStream, private val resource: Closeable? = null, name: String = "link") : Link {
    private val inbox = LinkedBlockingQueue<ByteArray>()
    private val outbox = LinkedBlockingQueue<ByteArray>()
    private val inStream = DataInputStream(input.buffered())
    private val outStream = DataOutputStream(output.buffered())
    @Volatile private var closed = false

    private val reader = Thread({
        try {
            while (!closed) {
                val n = inStream.readInt()
                require(n in 0..MAX_MESSAGE) { "Bad message length $n" }
                val b = ByteArray(n)
                inStream.readFully(b)
                inbox.put(b)
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }, "$name-reader")

    private val writer = Thread({
        try {
            while (!closed) {
                val m = outbox.poll(200, TimeUnit.MILLISECONDS) ?: continue
                outStream.writeInt(m.size)
                outStream.write(m)
                // Send everything queued so far in one flush.
                while (true) {
                    val more = outbox.poll() ?: break
                    outStream.writeInt(more.size)
                    outStream.write(more)
                }
                outStream.flush()
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }, "$name-writer")

    init {
        reader.isDaemon = true
        writer.isDaemon = true
        reader.start()
        writer.start()
    }

    override fun send(message: ByteArray) {
        if (!closed) outbox.put(message)
    }

    override fun poll(): ByteArray? = inbox.poll()

    override fun take(timeoutMs: Long): ByteArray? = inbox.poll(timeoutMs, TimeUnit.MILLISECONDS)

    override val isClosed: Boolean get() = closed && inbox.isEmpty()

    /** Sends whatever is still queued (best effort) and closes the connection. */
    fun flushAndClose(timeoutMs: Long = 500) {
        val end = System.currentTimeMillis() + timeoutMs
        while (outbox.isNotEmpty() && !closed && System.currentTimeMillis() < end) Thread.sleep(10)
        close()
    }

    override fun close() {
        if (closed) return
        closed = true
        try { resource?.close() } catch (_: Exception) {}
        try { inStream.close() } catch (_: Exception) {}
        try { outStream.close() } catch (_: Exception) {}
    }

    companion object {
        const val MAX_MESSAGE = 16 * 1024 * 1024
    }
}
