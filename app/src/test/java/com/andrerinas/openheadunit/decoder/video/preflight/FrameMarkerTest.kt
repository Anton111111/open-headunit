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
    fun `the marker scales into a preview bitmap`() {
        val sourceWidth = 1920
        val sourceHeight = 1080
        val targetWidth = 320
        val targetHeight = 180
        val rect = FrameMarker.fitRect(sourceWidth, sourceHeight, targetWidth, targetHeight)
        val geometry = FrameMarker.geometryFor(sourceWidth, sourceHeight)
            .scaledTo(rect, sourceWidth, sourceHeight)

        val reading = FrameMarker.read(
            sampler(149, geometry),
            geometry,
            targetWidth,
            targetHeight,
        )

        assertEquals(149, reading?.frame)
    }

    @Test
    fun `a 16 by 9 picture fills a 16 by 9 bitmap`() {
        assertEquals(
            FrameMarker.Rect(0, 0, 320, 180),
            FrameMarker.fitRect(1920, 1080, 320, 180),
        )
    }

    @Test
    fun `a 16 by 9 picture is letterboxed in a 4 by 3 bitmap`() {
        assertEquals(
            FrameMarker.Rect(0, 30, 320, 210),
            FrameMarker.fitRect(1920, 1080, 320, 240),
        )
        assertEquals(
            FrameMarker.Rect(0, 22, 240, 157),
            FrameMarker.fitRect(1920, 1080, 240, 180),
        )
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
