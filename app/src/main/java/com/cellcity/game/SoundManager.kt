package com.cellcity.game

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tiny procedural sound engine. Rather than shipping audio files, every effect
 * is synthesised into a short PCM buffer and played on a pooled [AudioTrack],
 * so the game keeps its "no asset dependencies" promise. All calls are safe to
 * make from the render thread; playback happens on the audio thread.
 */
class SoundManager(context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Whether effects and ambience should play at all. */
    var enabled: Boolean = true

    private val sampleRate = 22050

    private var birthBuf: ShortArray = ShortArray(0)
    private var deathBuf: ShortArray = ShortArray(0)
    private var upgradeBuf: ShortArray = ShortArray(0)
    private var buildBuf: ShortArray = ShortArray(0)
    private var ambientBuf: ShortArray = ShortArray(0)

    private var ambientTrack: AudioTrack? = null
    private var ambientPlaying = false

    private val channels = 8
    private val effects = arrayOfNulls<AudioTrack>(channels)
    private val busy = BooleanArray(channels)
    private var nextChannel = 0

    private var lastBirthAt = 0L
    private var lastDeathAt = 0L

    init {
        birthBuf = tone(0.16f, 620f, 980f, 0.30f, attack = 0.02f)
        deathBuf = tone(0.30f, 420f, 140f, 0.32f, attack = 0.01f)
        upgradeBuf = tone(0.26f, 520f, 1040f, 0.28f, attack = 0.02f)
        buildBuf = tone(0.10f, 240f, 320f, 0.22f, attack = 0.008f)
        ambientBuf = buildAmbient()
        for (i in 0 until channels) {
            effects[i] = makeTrack(birthBuf.size)
        }
    }

    // ------------------------------------------------------------------ public

    /** A cell was born (grew or upgraded). Rate-limited so a boom doesn't roar. */
    fun playBirth() {
        val now = System.currentTimeMillis()
        if (now - lastBirthAt < 70) return
        lastBirthAt = now
        play(birthBuf)
    }

    fun playUpgrade() {
        play(upgradeBuf)
    }

    /** A cell died (a building was abandoned). */
    fun playDeath() {
        val now = System.currentTimeMillis()
        if (now - lastDeathAt < 90) return
        lastDeathAt = now
        play(deathBuf)
    }

    /** A structure was placed on the map. */
    fun playBuild() {
        play(buildBuf)
    }

    fun startAmbient() {
        if (!enabled || ambientPlaying || ambientBuf.isEmpty()) return
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(ambientBuf.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(ambientBuf, 0, ambientBuf.size)
        track.setLoopPoints(0, ambientBuf.size, -1)
        track.setVolume(0.35f)
        track.play()
        ambientTrack = track
        ambientPlaying = true
    }

    fun pauseAmbient() {
        ambientTrack?.let {
            if (it.playState == AudioTrack.PLAYSTATE_PLAYING) it.pause()
        }
    }

    fun resumeAmbient() {
        if (!enabled) return
        val track = ambientTrack
        if (track != null && track.playState == AudioTrack.PLAYSTATE_PAUSED) {
            track.play()
        } else {
            ambientPlaying = false
            startAmbient()
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            pauseAmbient()
        } else {
            resumeAmbient()
        }
    }

    fun release() {
        ambientTrack?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        ambientTrack = null
        ambientPlaying = false
        for (track in effects) {
            track?.release()
        }
    }

    // ------------------------------------------------------------------ synth

    private fun play(buf: ShortArray) {
        if (!enabled || buf.isEmpty()) return
        // try to avoid stacking the same effect on top of itself
        var channel = -1
        for (i in 0 until channels) {
            val idx = (nextChannel + i) % channels
            if (!busy[idx]) {
                channel = idx
                break
            }
        }
        if (channel < 0) channel = nextChannel
        nextChannel = (channel + 1) % channels
        val track = effects[channel] ?: return
        try {
            track.pause()
            track.flush()
            track.setVolume(0.7f)
            track.write(buf, 0, buf.size)
            track.play()
            busy[channel] = true
        } catch (_: IllegalStateException) {
        }
    }

    private fun makeTrack(size: Int): AudioTrack? = try {
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
    } catch (_: Exception) {
        null
    }

    /** A short blip that sweeps from [f0] to [f1] with a soft envelope. */
    private fun tone(
        seconds: Float,
        f0: Float,
        f1: Float,
        gain: Float,
        attack: Float
    ): ShortArray {
        val n = (sampleRate * seconds).toInt().coerceAtLeast(1)
        val out = ShortArray(n)
        val rnd = Random(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toFloat() / n
            val freq = f0 + (f1 - f0) * t
            phase += 2.0 * PI * freq / sampleRate
            val env = envelope(t, attack)
            val body = sin(phase).toFloat() + 0.06f * (rnd.nextFloat() * 2f - 1f)
            out[i] = (body * env * gain * Short.MAX_VALUE).toInt()
                .coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /** Eight seconds of a slow, detuned drone with drifting "waves". */
    private fun buildAmbient(): ShortArray {
        val seconds = 8f
        val n = (sampleRate * seconds).toInt()
        val out = ShortArray(n)
        val rnd = Random(4242)
        var p1 = 0.0
        var p2 = 0.0
        var p3 = 0.0
        for (i in 0 until n) {
            val t = i.toFloat() / sampleRate
            // three low oscillators a fifth apart, slowly beating
            p1 += 2.0 * PI * 110.0 / sampleRate
            p2 += 2.0 * PI * 164.81 / sampleRate
            p3 += 2.0 * PI * 220.0 / sampleRate
            val wobble = 0.5f + 0.5f * sin(t * 0.35f).toFloat()
            val swell = 0.5f + 0.5f * sin(t * 0.13f + 1.1f).toFloat()
            val drone = (sin(p1) * 0.5 + sin(p2) * 0.3 + sin(p3) * 0.18).toFloat()
            val surf = 0.02f * (rnd.nextFloat() * 2f - 1f) * wobble
            val sample = (drone * (0.35f + 0.25f * swell) + surf) * 0.22f
            out[i] = (sample * Short.MAX_VALUE).toInt()
                .coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun envelope(t: Float, attack: Float): Float {
        val a = if (t < attack) t / attack else 1f
        // exponential decay tail
        return (a * exp(-(t - attack).coerceAtLeast(0f) * 4.2f)).toFloat()
    }

    @Suppress("unused")
    fun isMusicActive(): Boolean =
        audioManager.isMusicActive
}
