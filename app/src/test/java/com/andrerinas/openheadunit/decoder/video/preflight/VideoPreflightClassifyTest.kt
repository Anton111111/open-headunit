package com.andrerinas.openheadunit.decoder.video.preflight

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPreflightClassifyTest {

    private val markers = (0 until 15).map { 7 + it * 10 }

    private fun classify(
        rendered: Long = 150,
        drawn: Long = 150,
        observesDraws: Boolean = true,
        samples: List<Int> = markers,
        badSamples: Int = 0,
        decoderError: String? = null,
    ) = VideoPreflightPolicy.classifyClip(
        frameCount = 150,
        rendered = rendered,
        drawn = drawn,
        observesDraws = observesDraws,
        samples = samples,
        badSamples = badSamples,
        decoderError = decoderError,
    )

    @Test
    fun `a clean clip passes`() {
        assertEquals(VideoPreflightFailure.NONE, classify())
    }

    @Test
    fun `a decoder that held back a couple of frames still passes`() {
        assertEquals(VideoPreflightFailure.NONE, classify(rendered = 148, drawn = 148))
    }

    @Test
    fun `a texture view that merged a few frames still passes`() {
        // Seen on the API 19 and API 32 emulators: 150 rendered, 144 drawn, the picture fine.
        assertEquals(VideoPreflightFailure.NONE, classify(rendered = 150, drawn = 144))
    }

    @Test
    fun `nothing drawn is no picture`() {
        assertEquals(VideoPreflightFailure.NO_DRAWN_FRAMES, classify(drawn = 0))
    }

    @Test
    fun `losing more than a tenth on screen is missing frames, not no picture`() {
        assertEquals(VideoPreflightFailure.FEW_FRAMES, classify(drawn = 120))
    }

    @Test
    fun `a view that cannot count draws is judged on the decoder alone`() {
        assertEquals(VideoPreflightFailure.NONE, classify(drawn = -1, observesDraws = false))
    }

    @Test
    fun `no rendered frame is no first frame`() {
        assertEquals(VideoPreflightFailure.NO_FIRST_FRAME, classify(rendered = 0, drawn = 0))
    }

    @Test
    fun `too few rendered frames is missing frames`() {
        assertEquals(VideoPreflightFailure.FEW_FRAMES, classify(rendered = 140, drawn = 140))
    }

    @Test
    fun `a decoder error wins over everything else`() {
        assertEquals(VideoPreflightFailure.DECODER_ERROR, classify(decoderError = "boom"))
    }

    @Test
    fun `markers going backwards are a bad picture`() {
        assertEquals(
            VideoPreflightFailure.BAD_MARKER,
            classify(samples = listOf(2, 38, 42, 78, 98, 102, 138, 142, 2, 6, 42)),
        )
    }

    @Test
    fun `markers stuck at zero are a bad picture`() {
        assertEquals(VideoPreflightFailure.BAD_MARKER, classify(samples = List(15) { 0 }))
    }

    @Test
    fun `an unreadable sample is a bad picture`() {
        assertEquals(VideoPreflightFailure.BAD_MARKER, classify(badSamples = 1))
    }
}
