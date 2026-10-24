package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionHealthCodecTest {

    private val session = ProjectionSessionSummary(
        endedAtMs = 1_700_000_000_000,
        codec = "H.265",
        viewMode = Settings.ViewMode.TEXTURE,
        resolutionId = Settings.Resolution._1920x1080.id,
        fpsLimit = 60,
        codecFallback = true,
        rendererRecoveries = 2,
        forcedSurface = true,
        framesFed = 900,
        framesDropped = 30,
        framesSkipped = 12,
        framesRendered = 850,
    )

    @Test
    fun `a summary survives a round trip`() {
        assertEquals(listOf(session), ProjectionHealthCodec.decode(ProjectionHealthCodec.encode(listOf(session))))
    }

    @Test
    fun `malformed lines are skipped`() {
        val raw = ProjectionHealthCodec.encode(listOf(session)) + "\nnot a session\n" + "1|2|3"

        assertEquals(listOf(session), ProjectionHealthCodec.decode(raw))
    }

    @Test
    fun `empty input decodes to nothing`() {
        assertTrue(ProjectionHealthCodec.decode("").isEmpty())
    }
}
