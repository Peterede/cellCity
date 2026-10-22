package com.edens.cellcity.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

class SoundSynthTest {

    private fun oneShots() = listOf(
        "birth" to SoundSynth.birth(),
        "death" to SoundSynth.death(),
        "upgrade" to SoundSynth.upgrade(),
        "build" to SoundSynth.build(),
        "fireStart" to SoundSynth.fireStart(),
        "fireCrackle" to SoundSynth.fireCrackle(),
        "fireOut" to SoundSynth.fireOut(),
        "siren" to SoundSynth.siren(),
        "cheer" to SoundSynth.cheer(),
        "grumble" to SoundSynth.grumble()
    )

    private fun peak(buf: ShortArray): Int = buf.maxOf { abs(it.toInt()) }

    private fun rms(buf: ShortArray): Double {
        var sum = 0.0
        for (s in buf) sum += s.toDouble() * s
        return sqrt(sum / buf.size)
    }

    /** Energy at one frequency, via Goertzel, scaled to the buffer length. */
    private fun toneEnergy(buf: ShortArray, freq: Double): Double {
        val w = 2.0 * PI * freq / SoundSynth.SAMPLE_RATE
        val c = 2.0 * cos(w)
        var s1 = 0.0
        var s2 = 0.0
        for (s in buf) {
            val s0 = s / 32768.0 + c * s1 - s2
            s2 = s1
            s1 = s0
        }
        return sqrt(max(0.0, s1 * s1 + s2 * s2 - c * s1 * s2))
    }

    /**
     * A rough spectral centroid: how far the waveform moves sample to sample
     * relative to how loud it is. High tones score high, low rumbles low.
     */
    private fun brightness(buf: ShortArray, from: Int = 0, to: Int = buf.size): Double {
        var diff = 0.0
        var energy = 0.0
        for (i in max(1, from) until to) {
            diff += abs(buf[i].toInt() - buf[i - 1].toInt()).toDouble()
            energy += abs(buf[i].toInt()).toDouble()
        }
        return if (energy == 0.0) 0.0 else diff / energy
    }

    @Test
    fun everyEffectIsAudibleAndFitsPcm() {
        for ((name, buf) in oneShots()) {
            assertTrue("$name has samples", buf.isNotEmpty())
            assertTrue("$name is audible", peak(buf) > 1200)
            for (s in buf) {
                assertTrue("$name stays inside 16-bit PCM", s.toInt() in -32767..32767)
            }
        }
    }

    @Test
    fun effectsFadeInAndOutSoRetriggersDoNotClick() {
        for ((name, buf) in oneShots()) {
            assertEquals("$name starts silent", 0, buf.first().toInt())
            assertTrue("$name ends silent", abs(buf.last().toInt()) <= 2)
        }
    }

    @Test
    fun synthesisIsDeterministic() {
        assertTrue(SoundSynth.fireStart().contentEquals(SoundSynth.fireStart()))
        assertTrue(SoundSynth.cheer().contentEquals(SoundSynth.cheer()))
        assertTrue(SoundSynth.siren().contentEquals(SoundSynth.siren()))
    }

    @Test
    fun sirenIsABrightTwoToneAndTheGrumbleIsLow() {
        val siren = SoundSynth.siren()
        val grumble = SoundSynth.grumble()
        assertTrue(
            "the siren carries far more energy at 880 Hz than a murmur does",
            toneEnergy(siren, 880.0) > toneEnergy(grumble, 880.0) * 2.0
        )
        assertTrue(
            "the grumble lives down at 150 Hz, where the siren is silent",
            toneEnergy(grumble, 150.0) > toneEnergy(siren, 150.0) * 5.0
        )

        // 0.10-0.30s is the first high tone, 0.46-0.64s the second low one
        val high = brightness(siren, SoundSynth.SAMPLE_RATE / 10, SoundSynth.SAMPLE_RATE * 3 / 10)
        val low = brightness(siren, SoundSynth.SAMPLE_RATE * 46 / 100, SoundSynth.SAMPLE_RATE * 64 / 100)
        assertTrue("the siren alternates between two tones", high > low * 1.15)
    }

    @Test
    fun fireIgnitionIsLouderThanItsCrackleBed() {
        val start = SoundSynth.fireStart()
        val crackle = SoundSynth.fireCrackle()
        assertTrue("the ignition roars", rms(start) > 800)
        assertTrue("the crackle bed stays in the background", rms(crackle) < rms(start) * 0.75)
        assertTrue("the crowd cheer carries", rms(SoundSynth.cheer()) > 500)
        assertTrue("the grumble carries", rms(SoundSynth.grumble()) > 200)
    }

    @Test
    fun ambienceLoopsSeamlessly() {
        val loop = SoundSynth.ambience()
        assertEquals("eight seconds of ambience", SoundSynth.SAMPLE_RATE * 8, loop.size)
        assertTrue("the ambience is audible", peak(loop) > 1500)
        val seam = abs(loop.first().toInt() - loop.last().toInt())
        assertTrue("the loop seam has no step", seam <= peak(loop) / 10)
    }
}
