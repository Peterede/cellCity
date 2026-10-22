package com.edens.cellcity.game

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Every sound in Cell City, synthesised into 16-bit PCM so the game ships no
 * audio files. Pure Kotlin on purpose: no Android imports, so the buffers can
 * be checked in unit tests.
 *
 * Conventions: every buffer fades in and out to silence so retriggering never
 * clicks, and [ambience] loops with a continuous seam.
 */
object SoundSynth {

    const val SAMPLE_RATE = 44100

    private const val TAU = 2.0 * PI

    private fun samples(seconds: Float): Int =
        (SAMPLE_RATE * seconds).toInt().coerceAtLeast(1)

    private fun pcm(value: Float): Short =
        (value * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()

    /** Head/tail fade in seconds, so a cut buffer never pops. */
    private fun fade(i: Int, n: Int, fadeIn: Float, fadeOut: Float): Float {
        val inN = (fadeIn * SAMPLE_RATE).toInt().coerceAtLeast(1)
        val outN = (fadeOut * SAMPLE_RATE).toInt().coerceAtLeast(1)
        var g = 1f
        if (i < inN) g *= i.toFloat() / inN
        val tail = n - 1 - i
        if (tail < outN) g *= tail.toFloat() / outN
        return g
    }

    // ------------------------------------------------------------------ UI

    /** A soft, slightly reedy blip that sweeps from [f0] to [f1]. */
    private fun blip(
        seconds: Float,
        f0: Float,
        f1: Float,
        gain: Float,
        attack: Float
    ): ShortArray {
        val n = samples(seconds)
        val out = ShortArray(n)
        val rnd = Random((f0.toInt() * 31 + f1.toInt()).coerceAtLeast(1))
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toFloat() / n
            val freq = f0 + (f1 - f0) * t
            phase += TAU * freq / SAMPLE_RATE
            val env = if (t < attack) t / attack else exp(-(t - attack) * 4.2f)
            // a little second and third harmonic makes the blip read as a note
            val body = (sin(phase) + 0.18 * sin(phase * 2.0) + 0.06 * sin(phase * 3.0)).toFloat()
            val air = 0.02f * (rnd.nextFloat() * 2f - 1f)
            out[i] = pcm((body + air) * env * gain * fade(i, n, 0.004f, 0.03f))
        }
        return out
    }

    fun birth(): ShortArray = blip(0.20f, 620f, 1080f, 0.42f, 0.02f)

    fun death(): ShortArray = blip(0.36f, 420f, 120f, 0.40f, 0.012f)

    fun upgrade(): ShortArray = blip(0.30f, 520f, 1180f, 0.38f, 0.02f)

    fun build(): ShortArray = blip(0.12f, 230f, 330f, 0.34f, 0.008f)

    // ------------------------------------------------------------------ fire

    /**
     * Low roar of a fire plus sharp crackles: white noise through a one-pole
     * low-pass for the body, short noise bursts for the pops.
     */
    private fun fire(
        seconds: Float,
        pops: Int,
        roar: Float,
        popGain: Float,
        seed: Int
    ): ShortArray {
        val n = samples(seconds)
        val out = ShortArray(n)
        val rnd = Random(seed)
        val popStarts = IntArray(pops) { (rnd.nextFloat() * n).toInt() }.sortedArray()
        val popLen = samples(0.03f)
        var body = 0f
        var nextPop = 0
        var popLeft = 0
        var popEnv = 0f
        for (i in 0 until n) {
            val white = rnd.nextFloat() * 2f - 1f
            body += (white - body) * 0.055f
            val breathe = 0.75f + 0.25f * sin(i * TAU * 0.7 / SAMPLE_RATE).toFloat()
            var s = body * roar * breathe
            if (popLeft == 0 && nextPop < popStarts.size && i >= popStarts[nextPop]) {
                popLeft = popLen
                popEnv = 0.45f + rnd.nextFloat() * 0.55f
                nextPop++
            }
            if (popLeft > 0) {
                s += white * popGain * popEnv * (popLeft.toFloat() / popLen)
                popLeft--
            }
            out[i] = pcm(s * fade(i, n, 0.02f, 0.30f))
        }
        return out
    }

    /** A building catches fire: a bigger, brighter roar. */
    fun fireStart(): ShortArray = fire(1.6f, 12, 0.45f, 0.80f, 101)

    /** Small crackle bed played now and then while anything is burning. */
    fun fireCrackle(): ShortArray = fire(1.1f, 5, 0.18f, 0.70f, 202)

    /** Crews knock a fire down: a steam hiss over a low thump. */
    fun fireOut(): ShortArray {
        val n = samples(0.9f)
        val out = ShortArray(n)
        val rnd = Random(303)
        var low = 0f
        var high = 0f
        var prev = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / SAMPLE_RATE
            val white = rnd.nextFloat() * 2f - 1f
            low += (white - low) * 0.08f
            high = 0.96f * (high + white - prev)
            prev = white
            val steam = high * exp(-t * 3.2f).toFloat() * 0.35f
            val wash = low * exp(-t * 5.5f).toFloat() * 0.50f
            val thump = if (t < 0.18f) {
                sin(TAU * 70.0 * t).toFloat() * (1f - t / 0.18f) * 0.25f
            } else {
                0f
            }
            out[i] = pcm((steam + wash + thump) * fade(i, n, 0.01f, 0.25f))
        }
        return out
    }

    // ------------------------------------------------------------------ crime

    /**
     * Two-tone police siren ("nee-naw"): the pitch glides between a high and a
     * low tone, with a slow vibrato and a couple of harmonics for bite.
     */
    fun siren(): ShortArray {
        val n = samples(1.9f)
        val out = ShortArray(n)
        val rnd = Random(404)
        var phase = 0.0
        var freq = 640f
        for (i in 0 until n) {
            val t = i.toFloat() / SAMPLE_RATE
            val target = if ((t / 0.36f).toInt() % 2 == 0) 880f else 640f
            freq += (target - freq) * 0.0006f
            val vibrato = 1f + 0.025f * sin(TAU * 5.5 * t).toFloat()
            phase += TAU * freq * vibrato / SAMPLE_RATE
            val tone = sin(phase) + 0.30 * sin(phase * 2.0) + 0.12 * sin(phase * 3.0)
            val air = 0.012f * (rnd.nextFloat() * 2f - 1f)
            out[i] = pcm((tone.toFloat() * 0.30f + air) * fade(i, n, 0.03f, 0.15f))
        }
        return out
    }

    // ------------------------------------------------------------------ crowd

    /**
     * A crowd cheering: a band of rising, overlapping voices over a wash of
     * noise, with a few claps on top.
     */
    fun cheer(): ShortArray {
        val n = samples(1.6f)
        val out = ShortArray(n)
        val rnd = Random(505)
        val voices = 5
        val voiceStarts = IntArray(voices) { (rnd.nextFloat() * samples(0.55f)).toInt() }
        val voiceLen = samples(0.75f)
        val base = FloatArray(voices) { 380f + rnd.nextFloat() * 160f }
        val phase = DoubleArray(voices)
        val clapStarts =
            IntArray(7) { (rnd.nextFloat() * (n - samples(0.05f))).toInt() }.sortedArray()
        val clapLen = samples(0.02f)
        var nextClap = 0
        var clapLeft = 0
        var wash = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / SAMPLE_RATE
            val white = rnd.nextFloat() * 2f - 1f
            wash += (white - wash) * 0.18f
            val swell = sin(PI * (t / 1.15f).coerceAtMost(1f)).toFloat()
            var s = wash * swell * 0.30f
            for (v in 0 until voices) {
                val vi = i - voiceStarts[v]
                if (vi < 0 || vi >= voiceLen) continue
                val u = vi.toFloat() / voiceLen
                val f = base[v] + 260f * sin(PI * u.coerceAtMost(1f)).toFloat()
                phase[v] += TAU * f / SAMPLE_RATE
                val env = if (u < 0.08f) u / 0.08f else exp(-(u - 0.08f) * 2.4f)
                val tone = sin(phase[v]) + 0.5 * sin(phase[v] * 2.0) + 0.2 * sin(phase[v] * 3.0)
                s += tone.toFloat() * env * 0.11f
            }
            if (clapLeft == 0 && nextClap < clapStarts.size && i >= clapStarts[nextClap]) {
                clapLeft = clapLen
                nextClap++
            }
            if (clapLeft > 0) {
                s += white * 0.5f * (clapLeft.toFloat() / clapLen)
                clapLeft--
            }
            out[i] = pcm(s * fade(i, n, 0.02f, 0.40f))
        }
        return out
    }

    /**
     * An unhappy crowd: a low murmur of voices, slowly swaying, with a couple
     * of descending grumbles.
     */
    fun grumble(): ShortArray {
        val n = samples(1.3f)
        val out = ShortArray(n)
        val rnd = Random(606)
        var low = 0f
        var lower = 0f
        var bright = 0f
        var dark = 0f
        val phase = DoubleArray(3)
        for (i in 0 until n) {
            val t = i.toFloat() / SAMPLE_RATE
            val white = rnd.nextFloat() * 2f - 1f
            low += (white - low) * 0.035f
            lower += (low - lower) * 0.05f
            // the murmur band is what a phone speaker can actually reproduce
            bright += (white - bright) * 0.15f
            dark += (white - dark) * 0.03f
            val murmur = (bright - dark) * (0.55f + 0.45f * sin(TAU * 3.7 * t + 0.7).toFloat())
            val sway = 0.55f + 0.45f * sin(TAU * 3.1 * t + sin(TAU * 1.7 * t).toFloat()).toFloat()
            var s = lower * sway * 0.45f + murmur * 1.30f
            for (v in 0 until 3) {
                val start = v * 0.13f
                if (t < start) continue
                val u = ((t - start) / 0.9f).coerceIn(0f, 1f)
                val f = 168f - 42f * u - v * 12f
                phase[v] += TAU * f / SAMPLE_RATE
                val env = if (u < 0.25f) u / 0.25f else (1f - (u - 0.25f) / 0.75f).coerceAtLeast(0f)
                s += (sin(phase[v]) + 0.45 * sin(phase[v] * 2.0)).toFloat() * env * 0.06f
            }
            out[i] = pcm(s * fade(i, n, 0.06f, 0.35f))
        }
        return out
    }

    // ------------------------------------------------------------------ ambience

    /**
     * Eight seconds of city hum: a detuned low drone over soft air. Every
     * oscillator completes a whole number of cycles in the loop, and the noise
     * layer is cross-faded into itself, so the seam is inaudible.
     */
    fun ambience(): ShortArray {
        val loopLen = samples(8f)
        val fadeLen = samples(0.45f)
        val total = loopLen + fadeLen
        val rnd = Random(4242)
        val noise = FloatArray(total)
        var low = 0f
        var lower = 0f
        for (i in 0 until total) {
            val white = rnd.nextFloat() * 2f - 1f
            low += (white - low) * 0.06f
            lower += (low - lower) * 0.06f
            noise[i] = lower
        }
        val out = ShortArray(loopLen)
        for (i in 0 until loopLen) {
            val t = i.toDouble() / SAMPLE_RATE
            val swell = 0.5 + 0.5 * sin(TAU * 0.125 * t)
            val wobble = 0.5 + 0.5 * sin(TAU * 0.25 * t + 1.1)
            val drone = sin(TAU * 110.0 * t) * 0.50 +
                sin(TAU * 165.0 * t) * 0.30 +
                sin(TAU * 220.0 * t) * 0.18 +
                sin(TAU * 330.0 * t) * 0.05
            val air = if (i < fadeLen) {
                val w = i.toFloat() / fadeLen
                noise[i] * w + noise[loopLen + i] * (1f - w)
            } else {
                noise[i]
            }
            val s = (drone * (0.55 + 0.25 * swell) + air * 0.20 * wobble) * 0.22
            out[i] = pcm(s.toFloat())
        }
        return out
    }
}
