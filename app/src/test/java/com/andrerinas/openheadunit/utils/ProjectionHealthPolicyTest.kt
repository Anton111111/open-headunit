package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProjectionHealthPolicyTest {

    @Test
    fun `two codec fallbacks suggest h264`() {
        val action = evaluate(
            sessions = List(2) { session(codecFallback = true) } + List(3) { session() },
            codec = "H.265",
        )

        assertEquals(ProjectionHealthPolicy.Action.CODEC_H264, action)
    }

    @Test
    fun `one fallback is not enough`() {
        val action = evaluate(sessions = listOf(session(codecFallback = true)))

        assertNull(action)
    }

    @Test
    fun `a stored h264 codec is not suggested again`() {
        val action = evaluate(
            sessions = List(2) { session(codecFallback = true) },
            codec = "H.264",
        )

        assertNull(action)
    }

    @Test
    fun `two renderer recoveries suggest surface`() {
        val action = evaluate(sessions = List(2) { session(rendererRecoveries = 1) })

        assertEquals(ProjectionHealthPolicy.Action.RENDERER_SURFACE, action)
    }

    @Test
    fun `a stored surface backend is not suggested again`() {
        val action = evaluate(
            sessions = List(2) { session(rendererRecoveries = 2, forcedSurface = true) },
            viewMode = Settings.ViewMode.SURFACE,
        )

        assertNull(action)
    }

    @Test
    fun `two stutters at sixty fps suggest thirty`() {
        val action = evaluate(
            sessions = List(2) { session(dropped = 50) },
            fps = 60,
        )

        assertEquals(ProjectionHealthPolicy.Action.FPS_30, action)
    }

    @Test
    fun `two stutters at thirty fps and full hd suggest 720p`() {
        val action = evaluate(
            sessions = List(2) { session(dropped = 50) },
            fps = 30,
            resolution = Settings.Resolution._1920x1080,
        )

        assertEquals(ProjectionHealthPolicy.Action.RESOLUTION_720, action)
    }

    @Test
    fun `a stored 720p resolution is not suggested again`() {
        val action = evaluate(
            sessions = List(2) { session(dropped = 50) },
            fps = 30,
            resolution = Settings.Resolution._1280x720,
        )

        assertNull(action)
    }

    @Test
    fun `a 480p panel does not get a 720p suggestion`() {
        val action = evaluate(
            sessions = List(2) { session(dropped = 50) },
            fps = 30,
            resolution = Settings.Resolution.AUTO,
            panelCeiling = Settings.Resolution._800x480,
        )

        assertNull(action)
    }

    @Test
    fun `short sessions are ignored`() {
        val action = evaluate(
            sessions = List(2) {
                session(fed = 50, dropped = 50, rendered = 0)
            },
        )

        assertNull(action)
    }

    @Test
    fun `a dismissed suggestion waits three sessions`() {
        val sessions = List(2) { session(codecFallback = true) }

        assertNull(
            evaluate(
                sessions = sessions,
                total = 12,
                dismissed = ProjectionHealthPolicy.Action.CODEC_H264,
                dismissedAt = 10,
            )
        )
        assertEquals(
            ProjectionHealthPolicy.Action.CODEC_H264,
            evaluate(
                sessions = sessions,
                total = 13,
                dismissed = ProjectionHealthPolicy.Action.CODEC_H264,
                dismissedAt = 10,
            ),
        )
    }

    @Test
    fun `a different problem appears after a dismissal`() {
        val action = evaluate(
            sessions = List(2) { session(rendererRecoveries = 1) },
            total = 12,
            dismissed = ProjectionHealthPolicy.Action.CODEC_H264,
            dismissedAt = 10,
        )

        assertEquals(ProjectionHealthPolicy.Action.RENDERER_SURFACE, action)
    }

    @Test
    fun `an old problem leaves the window`() {
        val sessions = listOf(
            session(codecFallback = true),
            session(codecFallback = true),
            session(),
            session(),
            session(),
            session(),
        )

        assertNull(evaluate(sessions = sessions, codec = "H.265"))
    }

    @Test
    fun `a codec fallback wins over a renderer problem`() {
        val action = evaluate(
            sessions = listOf(
                session(codecFallback = true, rendererRecoveries = 2, forcedSurface = true),
                session(codecFallback = true, rendererRecoveries = 2, forcedSurface = true),
            ),
            codec = "H.265",
        )

        assertEquals(ProjectionHealthPolicy.Action.CODEC_H264, action)
    }

    private fun evaluate(
        sessions: List<ProjectionSessionSummary>,
        total: Int = sessions.size,
        dismissed: ProjectionHealthPolicy.Action? = null,
        dismissedAt: Int = 0,
        codec: String = "H.265",
        viewMode: Settings.ViewMode = Settings.ViewMode.TEXTURE,
        resolution: Settings.Resolution = Settings.Resolution._1920x1080,
        fps: Int = 60,
        panelCeiling: Settings.Resolution = Settings.Resolution._1920x1080,
    ): ProjectionHealthPolicy.Action? = ProjectionHealthPolicy.evaluate(
        ProjectionHealthPolicy.State(
            sessions = sessions,
            totalSessions = total,
            dismissedAction = dismissed,
            dismissedAtSession = dismissedAt,
            storedCodec = codec,
            storedViewMode = viewMode,
            storedResolution = resolution,
            storedFps = fps,
            panelCeiling = panelCeiling,
        )
    )

    private fun session(
        codecFallback: Boolean = false,
        rendererRecoveries: Int = 0,
        forcedSurface: Boolean = false,
        fed: Long = 900,
        dropped: Long = 0,
        skipped: Long = 0,
        rendered: Long = 900,
    ): ProjectionSessionSummary = ProjectionSessionSummary(
        endedAtMs = 0,
        codec = "H.265",
        viewMode = Settings.ViewMode.TEXTURE,
        resolutionId = Settings.Resolution._1920x1080.id,
        fpsLimit = 60,
        codecFallback = codecFallback,
        rendererRecoveries = rendererRecoveries,
        forcedSurface = forcedSurface,
        framesFed = fed,
        framesDropped = dropped,
        framesSkipped = skipped,
        framesRendered = rendered,
    )
}
