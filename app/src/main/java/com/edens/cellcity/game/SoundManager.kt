package com.edens.cellcity.game

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

/**
 * Plays the buffers built by [SoundSynth] and decides what the city should be
 * saying. Nothing is loaded from disk; all calls are safe from the render
 * thread and playback happens on the audio thread.
 *
 * Each effect owns one static [AudioTrack] that is filled once at startup and
 * then replayed with stop/play. Replaying the same effect cuts its previous
 * instance short, which keeps overlapping sounds from turning into noise.
 */
class SoundManager(context: Context) {

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Whether effects and ambience should play at all. */
    var enabled: Boolean = true

    private val sampleRate = SoundSynth.SAMPLE_RATE

    private val birth = Effect(SoundSynth.birth())
    private val death = Effect(SoundSynth.death())
    private val upgrade = Effect(SoundSynth.upgrade())
    private val build = Effect(SoundSynth.build())
    private val fireStart = Effect(SoundSynth.fireStart())
    private val fireCrackle = Effect(SoundSynth.fireCrackle())
    private val fireOut = Effect(SoundSynth.fireOut())
    private val siren = Effect(SoundSynth.siren())
    private val cheer = Effect(SoundSynth.cheer())
    private val grumble = Effect(SoundSynth.grumble())
    private val ambientBuf = SoundSynth.ambience()

    private var ambientTrack: AudioTrack? = null
    private var ambientPlaying = false

    private var lastBirthAt = 0L
    private var lastDeathAt = 0L
    private var lastBuildAt = 0L

    // scene tracking: crackles while fires burn, crowd reactions to the mood
    private var fireTimer = 0f
    private var crowdTimer = 0f
    private var crowdBand = 0
    private var lastCrowdAt = 0L

    // ------------------------------------------------------------------ public

    /** A cell was born (grew or upgraded). Rate-limited so a boom doesn't roar. */
    fun playBirth() {
        val now = System.currentTimeMillis()
        if (now - lastBirthAt < 70) return
        lastBirthAt = now
        birth.play(0.65f)
    }

    fun playUpgrade() {
        upgrade.play(0.6f)
    }

    /** A cell died (a building was abandoned). */
    fun playDeath() {
        val now = System.currentTimeMillis()
        if (now - lastDeathAt < 90) return
        lastDeathAt = now
        death.play(0.7f)
    }

    /** A structure was placed on the map. */
    fun playBuild() {
        val now = System.currentTimeMillis()
        if (now - lastBuildAt < 55) return
        lastBuildAt = now
        build.play(0.55f)
    }

    /** A building has caught fire: roar and crackle. */
    fun onFireStarted() {
        fireStart.play(0.8f)
    }

    /** Crews knocked a fire down: steam and a thump. */
    fun onFireOut() {
        fireOut.play(0.7f)
    }

    /** Crime was reported: a two-tone police siren. */
    fun onCrimeReported() {
        siren.play(0.7f)
    }

    /** People are pleased: a crowd hurray. */
    fun playCheer() {
        cheer.play(0.65f)
    }

    /** People are fed up: a low murmur of complaints. */
    fun playGrumble() {
        grumble.play(0.6f)
    }

    /**
     * Reacts to the city each frame: crackles while anything burns, and a
     * cheer or a grumble now and then depending on how the citizens feel.
     * [dt] is real time in seconds; pass 0 while the city is paused.
     */
    fun updateScene(dt: Float, fires: Int, population: Int, happiness: Float) {
        if (!enabled || dt <= 0f) return

        if (fires > 0) {
            fireTimer += dt
            if (fireTimer >= FIRE_CRACKLE_GAP) {
                fireTimer = 0f
                fireCrackle.play(0.6f)
            }
        } else {
            fireTimer = 0f
        }

        val band = when {
            population < CROWD_MIN_POPULATION -> 0
            happiness >= CROWD_HAPPY -> 1
            happiness <= CROWD_UNHAPPY -> -1
            else -> 0
        }
        if (band != crowdBand) {
            crowdBand = band
            crowdTimer = 0f
            if (band != 0) playCrowd(band)
        }
        crowdTimer += dt
        if (band != 0 && crowdTimer >= if (band > 0) CROWD_HAPPY_GAP else CROWD_UNHAPPY_GAP) {
            crowdTimer = 0f
            playCrowd(band)
        }
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
        track.setVolume(0.55f)
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

    /** Stop one-shots when the game loses focus, without waiting for them to end. */
    fun pauseEffects() {
        birth.pause()
        death.pause()
        upgrade.pause()
        build.pause()
        fireStart.pause()
        fireCrackle.pause()
        fireOut.pause()
        siren.pause()
        cheer.pause()
        grumble.pause()
        fireTimer = 0f
        crowdTimer = 0f
        crowdBand = 0
    }

    fun setSoundEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) {
            pauseAmbient()
            pauseEffects()
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
        for (effect in effects()) effect.release()
    }

    // ------------------------------------------------------------------ internal

    private fun effects(): List<Effect> = listOf(
        birth, death, upgrade, build, fireStart, fireCrackle, fireOut, siren, cheer, grumble
    )

    private fun playCrowd(band: Int) {
        val now = System.currentTimeMillis()
        if (now - lastCrowdAt < CROWD_MIN_GAP) return
        lastCrowdAt = now
        if (band > 0) cheer.play(0.6f) else grumble.play(0.55f)
    }

    /**
     * One static track per effect. Writing the buffer once in [init] leaves the
     * track initialized, and every later play rewinds it with stop/play.
     */
    private inner class Effect(buffer: ShortArray) {

        private var track: AudioTrack? = makeTrack(buffer.size)

        init {
            val t = track
            if (t != null) {
                try {
                    if (t.write(buffer, 0, buffer.size) <= 0) {
                        t.release()
                        track = null
                    }
                } catch (_: Exception) {
                    try {
                        t.release()
                    } catch (_: Exception) {
                    }
                    track = null
                }
            }
        }

        fun play(volume: Float) {
            val t = track ?: return
            try {
                // stop() rewinds a static track to the start of its buffer
                t.stop()
                t.setVolume(volume.coerceIn(0f, 1f))
                t.play()
            } catch (_: IllegalStateException) {
            }
        }

        fun pause() {
            val t = track ?: return
            try {
                if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
            } catch (_: IllegalStateException) {
            }
        }

        fun release() {
            track?.release()
            track = null
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

    @Suppress("unused")
    fun isMusicActive(): Boolean =
        audioManager.isMusicActive

    private companion object {
        /** Seconds between crackles while a fire burns. */
        const val FIRE_CRACKLE_GAP = 2.6f
        /** Below this many residents a crowd is too small to react. */
        const val CROWD_MIN_POPULATION = 25
        const val CROWD_HAPPY = 70f
        const val CROWD_UNHAPPY = 45f
        const val CROWD_HAPPY_GAP = 15f
        const val CROWD_UNHAPPY_GAP = 12f
        /** Never stack crowd noises closer together than this. */
        const val CROWD_MIN_GAP = 6000L
    }
}
