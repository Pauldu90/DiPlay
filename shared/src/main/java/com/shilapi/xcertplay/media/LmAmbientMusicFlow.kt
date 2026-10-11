package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.LmAmbientLightProtocol
import com.shilapi.xcertplay.LmAmbientMode
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Turns played audio energy into the Leapmotor flow-light broadcast, reusing the envelope the BYD
 * path already fills instead of a second spectrum service.
 *
 * The OEM engine reacts to raw spectrum magnitudes that are re-read every callback; our envelope
 * delivers a 200ms played window, so each channel is normalised against its own decaying peak and
 * then eased. Without that the lamp jumps between near-black and saturated on every frame, because
 * raw PCM RMS sits around 0.05..0.3 while the OEM's `|f|*2` scale expects a 0..127 band value.
 */
internal class LmAmbientMusicFlow(private val mode: () -> LmAmbientMode = { LmAmbientMode.AUTO }) {
    private var bassPeak = 0.05
    private var midPeak = 0.05
    private var highPeak = 0.05
    private var smoothed: LmAmbientLightProtocol.Params? = null
    private var previousTime: Long? = null

    /** Drops the smoothing state so a re-enabled feature does not fade in from the last session. */
    fun reset() {
        smoothed = null
        previousTime = null
        bassPeak = 0.05; midPeak = 0.05; highPeak = 0.05
    }

    /**
     * @param energy played window from [AmbientMusicEnvelope]; zero energy yields the idle look.
     * @param idleMode the switch state applied when nothing is playing.
     */
    fun params(energy: AmbientMusicEnvelope.Energy, nowMillis: Long, idleColor: Int): LmAmbientLightProtocol.Params {
        val elapsed = previousTime?.let { (nowMillis - it).coerceAtLeast(0) } ?: 0
        previousTime = nowMillis
        val decay = exp(-elapsed / 4_000.0)
        bassPeak = maxOf(0.05, energy.bassRms, bassPeak * decay)
        midPeak = maxOf(0.05, energy.midRms, midPeak * decay)
        highPeak = maxOf(0.05, energy.highRms, highPeak * decay)
        if (energy.rms < SILENCE_RMS) return idle(idleColor)
        val bass = normalize(energy.bassRms, bassPeak)
        val mid = normalize(energy.midRms, midPeak)
        val high = normalize(energy.highRms, highPeak)
        val target = LmAmbientLightProtocol.flowParams(
            if (mode() == LmAmbientMode.OFF) LmAmbientMode.AUTO else mode(), bass, mid, high)
        return ease(target, elapsed)
    }

    /** The resting state: the user's colour at the default angle, only ever sent while enabled. */
    fun idle(idleColor: Int): LmAmbientLightProtocol.Params =
        LmAmbientLightProtocol.switchParams(if (mode() == LmAmbientMode.OFF) LmAmbientMode.AUTO else mode(),
            LmAmbientLightProtocol.DEG_DEFAULT, idleColor)

    /**
     * Perceptual easing on each channel. The angle follows immediately: it is the OEM engine's
     * audio-driven term and a lag there is visible as the light strip sweeping late.
     */
    private fun ease(target: LmAmbientLightProtocol.Params, elapsed: Long): LmAmbientLightProtocol.Params {
        val previous = smoothed
        val eased = if (previous == null || elapsed <= 0) target else target.copy(
            r = easeChannel(previous.r, target.r),
            g = easeChannel(previous.g, target.g),
            b = easeChannel(previous.b, target.b),
        )
        smoothed = eased
        return eased
    }

    private fun easeChannel(from: Int, to: Int): Int {
        // Fast enough to track a beat, slow enough that a 200ms step does not read as a flicker.
        val factor = 0.45
        return (from + (to - from) * factor).toInt().coerceIn(0, 255)
    }

    /** Maps a raw band magnitude onto 0..1 using its own peak, with a square-root response curve. */
    private fun normalize(value: Double, peak: Double): Double {
        val raw = (if (value.isFinite()) value else 0.0).coerceIn(0.0, 1.0)
        if (raw < SILENCE_RMS) return 0.0
        return sqrt((raw / peak).coerceIn(0.0, 1.0))
    }

    private companion object {
        /** Below this the band is treated as silence rather than "very quiet music". */
        const val SILENCE_RMS = 0.008
    }
}
