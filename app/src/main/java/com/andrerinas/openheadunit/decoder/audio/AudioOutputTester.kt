package com.andrerinas.openheadunit.decoder.audio

import android.os.Build
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import kotlin.math.min

/**
 * Plays a short PCM tone through the output backend the projection would use, chosen by the
 * same [AudioOutputPolicy] rule.
 *
 * The tone says little about music quality, but it opens AAudio for real, writes through it and
 * reads its underrun counter. That is enough to tell a backend that starts and keeps up from one
 * that crackles, and to fall back to AudioTrack with a reason when AAudio cannot open at all.
 */
object AudioOutputTester {

    enum class Backend { AAUDIO, AUDIOTRACK }

    data class Result(val backend: Backend, val underruns: Int, val fallbackReason: String?)

    fun play(settings: Settings): Result {
        val tone = AudioTestTone.stereo()
        val useAAudio = AudioOutputPolicy.useAAudio(
            Build.VERSION.SDK_INT,
            settings.mediaAudioStream,
            settings.attachHwDspEqualizer,
            settings.useAAudioOutput,
        )
        if (useAAudio) {
            try {
                return playWith(AAudioPcmOutput(), Backend.AAUDIO, tone, null)
            } catch (e: Exception) {
                return playOnAudioTrack(settings, tone, e.message ?: e.javaClass.simpleName)
            } catch (e: LinkageError) {
                return playOnAudioTrack(settings, tone, e.message ?: e.javaClass.simpleName)
            }
        }
        return playOnAudioTrack(settings, tone, null)
    }

    private fun playOnAudioTrack(settings: Settings, tone: ShortArray, reason: String?): Result =
        playWith(
            AudioTrackPcmOutput(settings.mediaAudioStream, settings.attachHwDspEqualizer),
            Backend.AUDIOTRACK,
            tone,
            reason,
        ).also {
            if (reason != null) {
                AppLog.w("AudioOutputTester: AAudio failed, using AudioTrack: $reason")
            }
        }

    private fun playWith(
        output: PcmOutput,
        backend: Backend,
        tone: ShortArray,
        fallbackReason: String?,
    ): Result {
        try {
            output.start()
            writeAll(output, tone)
            // Let the tail leave the device buffer before reading the counters.
            Thread.sleep(1_200)
            val underruns = if (output.underrunsSupported) output.underruns else 0
            AppLog.i(
                "AudioOutputTester: backend=$backend underruns=$underruns " +
                    "fallback=${fallbackReason != null}",
            )
            return Result(backend, underruns, fallbackReason)
        } finally {
            runCatching { output.close() }
        }
    }

    private fun writeAll(output: PcmOutput, tone: ShortArray) {
        var offset = 0
        val deadline = SystemClock.elapsedRealtime() + WRITE_TIMEOUT_MS
        while (offset < tone.size && SystemClock.elapsedRealtime() < deadline) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val count = min(CHUNK_SHORTS, tone.size - offset)
            val written = output.write(tone, offset, count)
            if (written < 0) error("the output rejected audio (code $written)")
            if (written == 0) Thread.sleep(5) else offset += written
        }
        if (offset < tone.size) error("the output stopped accepting audio")
    }

    private const val CHUNK_SHORTS = 960 // 10 ms of stereo PCM16
    private const val WRITE_TIMEOUT_MS = 4_000L
}
