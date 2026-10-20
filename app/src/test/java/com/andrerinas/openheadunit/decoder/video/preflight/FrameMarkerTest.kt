package com.andrerinas.openheadunit.decoder.video.preflight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameMarkerTest {

    @Test
    fun `the geometry matches the generator at every clip size`() {
        assertEquals(FrameMarker.Geometry(684, 333, 20, 21), FrameMarker.geometryFor(800, 480))
        assertEquals(FrameMarker.Geometry(1107, 500, 29, 32), FrameMarker.geometryFor(1280, 720))
        assertEquals(FrameMarker.Geometry(1660, 750, 44, 48), FrameMarker.geometryFor(1920, 1080))
    }

    @Test
    fun `every frame number reads back`() {
        val width = 800
        val height = 480
        val geometry = FrameMarker.geometryFor(width, height)
        for (frame in 0 until VideoPreflightClip.FRAME_COUNT) {
            val reading = FrameMarker.read(sampler(frame, geometry), geometry, width, height)
            assertEquals("frame $frame", frame, reading?.frame)
        }
    }

    @Test
    fun `frame zero reads as all dark`() {
        val geometry = FrameMarker.geometryFor(800, 480)
        val reading = FrameMarker.read(sampler(0, geometry), geometry, 800, 480)

        assertEquals(0, reading?.frame)
    }

    @Test
    fun `a washed out frame is unreadable`() {
        val width = 800
        val height = 480
        val geometry = FrameMarker.geometryFor(width, height)

        val reading = FrameMarker.read(sampler(42, geometry, on = 140, off = 120), geometry, width, height)

        assertNull(reading)
    }

    @Test
    fun `a clip stretched into the preview reads back at every size`() {
        for ((width, height) in listOf(800 to 480, 1280 to 720, 1920 to 1080)) {
            for ((targetWidth, targetHeight) in listOf(320 to 180, 480 to 270, 256 to 144)) {
                val geometry = FrameMarker.geometryFor(width, height)
                val scaleX = targetWidth.toDouble() / width
                val scaleY = targetHeight.toDouble() / height
                for (frame in 0 until 150) {
                    val reading = FrameMarker.read(
                        stretchedSampler(frame, geometry, scaleX, scaleY),
                        geometry, targetWidth, targetHeight, scaleX, scaleY,
                    )
                    assertEquals("${width}x$height in ${targetWidth}x$targetHeight", frame, reading?.frame)
                }
            }
        }
    }

    @Test
    fun `a 5 by 3 clip in a 16 by 9 preview is read where it was stretched to`() {
        // The 480p clip is 800x480 and the preview 320x180: 0.4 across and 0.375 down. Fitting it
        // put the samples about three pixels left of the blocks.
        val geometry = FrameMarker.geometryFor(800, 480)
        val reading = FrameMarker.read(stretchedSampler(130, geometry, 0.4, 0.375), geometry, 320, 180, 0.4, 0.375)

        assertEquals(130, reading?.frame)
    }

    /** The marker drawn the way the preview stretches it: each block scaled on both axes. */
    private fun stretchedSampler(
        frame: Int,
        geometry: FrameMarker.Geometry,
        scaleX: Double,
        scaleY: Double,
    ): (Int, Int) -> Int = { x, y ->
        var lit = false
        for (bit in 0 until 8) {
            if ((frame shr bit) and 1 == 0) continue
            val left = (geometry.x + (bit % 4) * geometry.gap) * scaleX
            val top = (geometry.y + (bit / 4) * geometry.gap) * scaleY
            if (x + 0.5 >= left && x + 0.5 < left + geometry.block * scaleX &&
                y + 0.5 >= top && y + 0.5 < top + geometry.block * scaleY
            ) {
                lit = true
            }
        }
        if (lit) 255 else 0
    }

    @Test
    fun `an offset moves the read to where the picture starts`() {
        val geometry = FrameMarker.geometryFor(800, 480)
        val shifted = sampler(87, geometry)
        val inPadding: (Int, Int) -> Int = { x, y -> shifted(x - 30, y - 12) }

        val reading = FrameMarker.read(inPadding, geometry, 900, 500, offsetX = 30, offsetY = 12)
        val unshifted = FrameMarker.read(inPadding, geometry, 900, 500)

        assertEquals(87, reading?.frame)
        assertEquals(false, unshifted?.frame == 87)
    }

    private fun sampler(
        frame: Int,
        geometry: FrameMarker.Geometry,
        on: Int = 255,
        off: Int = 0,
    ): (Int, Int) -> Int = { x, y ->
        var lit = false
        for (bit in 0 until 8) {
            if ((frame shr bit) and 1 == 0) continue
            val left = geometry.x + (bit % 4) * geometry.gap
            val top = geometry.y + (bit / 4) * geometry.gap
            if (x in left until left + geometry.block && y in top until top + geometry.block) {
                lit = true
            }
        }
        if (lit) on else off
    }
}
