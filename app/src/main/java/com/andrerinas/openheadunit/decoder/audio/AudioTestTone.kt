package com.andrerinas.openheadunit.decoder.audio

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * A short stereo signal for the onboarding output test: 440 Hz for half a second, then 660 Hz.
 *
 * The fade at both ends keeps the test from adding a click of its own, so a click the user hears
 * came from the output backend rather than from the signal. The amplitude leaves plenty of
 * headroom for a car amplifier.
 */
object AudioTestTone {

    const val SAMPLE_RATE = 48_000
    private const val AMPLITUDE = 0.18
    private const val FADE_SECONDS = 0.02

    fun stereo(seconds: Double = 1.0): ShortArray {
        val frames = (SAMPLE_RATE * seconds).toInt().coerceAtLeast(1)
        val samples = ShortArray(frames * 2)
        val fadeFrames = (SAMPLE_RATE * FADE_SECONDS).toInt().coerceAtLeast(1)
        var phase = 0.0
        for (frame in 0 until frames) {
            val frequency = if (frame < frames / 2) 440.0 else 660.0
            phase += 2.0 * PI * frequency / SAMPLE_RATE
            val envelope = min(1.0, min(frame, frames - 1 - frame).toDouble() / fadeFrames)
            val value = (sin(phase) * AMPLITUDE * envelope * Short.MAX_VALUE).toInt().toShort()
            samples[frame * 2] = value
            samples[frame * 2 + 1] = value
        }
        return samples
    }
}
