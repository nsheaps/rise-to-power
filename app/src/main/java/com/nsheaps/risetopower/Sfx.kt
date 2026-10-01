package com.nsheaps.risetopower

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Procedurally synthesised sound effects, so the game ships without audio assets. */
class Sfx(context: Context) {
    enum class Kind { CLICK, SELECT, ACK, MELEE, SHOOT, SIEGE, DEATH, COLLAPSE, BUILD, ALERT, AGE_UP, VICTORY, DEFEAT, ERROR }

    private val pool = SoundPool.Builder()
        .setMaxStreams(10)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = IntArray(Kind.entries.size)
    private val last = LongArray(Kind.entries.size)
    var enabled = true

    init {
        val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
        for (k in Kind.entries) {
            val f = File(dir, "${k.name.lowercase()}_v1.wav")
            if (!f.exists()) writeWav(f, synth(k))
            ids[k.ordinal] = pool.load(f.absolutePath, 1)
        }
    }

    fun play(k: Kind, volume: Float = 1f, minGapMs: Long = 60) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now - last[k.ordinal] < minGapMs) return
        last[k.ordinal] = now
        val rate = 0.94f + Random.nextFloat() * 0.12f
        pool.play(ids[k.ordinal], volume, volume, 1, 0, rate)
    }

    fun release() = pool.release()

    // ------------------------------------------------------------------ synthesis

    private val sr = 22050

    private fun buf(seconds: Float) = FloatArray((sr * seconds).toInt())

    private fun tone(out: FloatArray, start: Float, dur: Float, f0: Float, f1: Float, amp: Float, decay: Float, square: Boolean = false) {
        val s0 = (start * sr).toInt()
        val n = (dur * sr).toInt()
        var phase = 0.0
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= out.size) break
            val t = i.toFloat() / n
            val f = f0 + (f1 - f0) * t
            phase += 2 * PI * f / sr
            var v = sin(phase).toFloat()
            if (square) v = if (v > 0) 0.6f else -0.6f
            val env = exp(-decay * t) * minOf(1f, i / (sr * 0.004f))
            out[idx] += v * amp * env
        }
    }

    private fun noise(out: FloatArray, start: Float, dur: Float, amp: Float, decay: Float, smooth: Float) {
        val r = Random(42)
        val s0 = (start * sr).toInt()
        val n = (dur * sr).toInt()
        var lp = 0f
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= out.size) break
            val t = i.toFloat() / n
            lp += (r.nextFloat() * 2 - 1 - lp) * smooth
            out[idx] += lp * amp * exp(-decay * t) * minOf(1f, i / (sr * 0.003f))
        }
    }

    private fun synth(k: Kind): FloatArray = when (k) {
        Kind.CLICK -> buf(0.05f).also { tone(it, 0f, 0.04f, 1400f, 900f, 0.35f, 6f) }
        Kind.SELECT -> buf(0.14f).also { tone(it, 0f, 0.06f, 660f, 660f, 0.3f, 3f); tone(it, 0.06f, 0.07f, 880f, 880f, 0.3f, 4f) }
        Kind.ACK -> buf(0.14f).also { tone(it, 0f, 0.12f, 520f, 700f, 0.3f, 4f) }
        Kind.MELEE -> buf(0.16f).also { noise(it, 0f, 0.12f, 0.5f, 9f, 0.6f); tone(it, 0f, 0.15f, 2300f, 2100f, 0.18f, 12f) }
        Kind.SHOOT -> buf(0.18f).also { noise(it, 0f, 0.17f, 0.35f, 4f, 0.15f) }
        Kind.SIEGE -> buf(0.4f).also { tone(it, 0f, 0.35f, 110f, 50f, 0.6f, 5f); noise(it, 0f, 0.3f, 0.4f, 6f, 0.08f) }
        Kind.DEATH -> buf(0.25f).also { tone(it, 0f, 0.22f, 320f, 120f, 0.25f, 4f) }
        Kind.COLLAPSE -> buf(0.9f).also { noise(it, 0f, 0.85f, 0.7f, 3f, 0.04f); tone(it, 0f, 0.6f, 70f, 40f, 0.4f, 3f) }
        Kind.BUILD -> buf(0.45f).also { tone(it, 0f, 0.4f, 880f, 880f, 0.22f, 4f); tone(it, 0.1f, 0.35f, 1320f, 1320f, 0.18f, 4f) }
        Kind.ALERT -> buf(0.6f).also { tone(it, 0f, 0.22f, 330f, 330f, 0.35f, 1.5f, true); tone(it, 0.3f, 0.25f, 330f, 300f, 0.35f, 1.5f, true) }
        Kind.AGE_UP -> buf(0.9f).also {
            val notes = floatArrayOf(523f, 659f, 784f, 1047f)
            for ((i, f) in notes.withIndex()) tone(it, i * 0.13f, 0.4f, f, f, 0.25f, 3f)
        }
        Kind.VICTORY -> buf(1.6f).also {
            val notes = floatArrayOf(523f, 659f, 784f, 1047f, 784f, 1047f)
            for ((i, f) in notes.withIndex()) tone(it, i * 0.18f, 0.5f, f, f, 0.25f, 2.5f)
        }
        Kind.DEFEAT -> buf(1.4f).also {
            val notes = floatArrayOf(392f, 349f, 311f, 262f)
            for ((i, f) in notes.withIndex()) tone(it, i * 0.25f, 0.5f, f, f, 0.25f, 2f)
        }
        Kind.ERROR -> buf(0.16f).also { tone(it, 0f, 0.15f, 150f, 140f, 0.35f, 2f, true) }
    }

    private fun writeWav(f: File, data: FloatArray) {
        val pcm = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in data) pcm.putShort((v.coerceIn(-1f, 1f) * 32000).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()); header.putInt(36 + data.size * 2); header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()); header.putInt(16); header.putShort(1); header.putShort(1)
        header.putInt(sr); header.putInt(sr * 2); header.putShort(2); header.putShort(16)
        header.put("data".toByteArray()); header.putInt(data.size * 2)
        FileOutputStream(f).use { it.write(header.array()); it.write(pcm.array()) }
    }
}
