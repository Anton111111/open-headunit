package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioTestToneTest {

    @Test
    fun `stereo tone has the expected length and headroom`() {
        val samples = AudioTestTone.stereo(seconds = 0.1)

        assertEquals((AudioTestTone.SAMPLE_RATE * 0.1).toInt() * 2, samples.size)
        assertTrue(samples.all { abs(it.toInt()) <= 6000 })
    }

    @Test
    fun `both channels carry the same sample`() {
        val samples = AudioTestTone.stereo(seconds = 0.05)

        for (index in 0 until samples.size step 2) {
            assertEquals(samples[index], samples[index + 1])
        }
    }

    @Test
    fun `the fade starts and ends at silence`() {
        val samples = AudioTestTone.stereo(seconds = 0.5)

        assertEquals(0, samples.first().toInt())
        assertEquals(0, samples.last().toInt())
    }
}
