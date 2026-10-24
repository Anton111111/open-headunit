package com.andrerinas.openheadunit.utils

/**
 * Decides when the last few projections agree on a setting worth suggesting on the home screen.
 *
 * The rules look only at recent sessions and only at faults the head unit caused: a codec fallback,
 * a renderer that had to be rebuilt, and frames the decoder dropped or skipped while frames were
 * still arriving. A link outage lowers both the fed and the rendered counts, so it does not turn
 * into a resolution suggestion.
 */
object ProjectionHealthPolicy {

    /** How many finished sessions one suggestion looks at. */
    const val WINDOW = 5

    /** How many sessions in the window must show the same fault before it is worth a suggestion. */
    const val MIN_SESSIONS = 2

    /** Below this many fed frames a session was too short, or too starved, to judge. */
    const val MIN_FED_FRAMES = 150L

    /** A session that rendered less than this share of what it was fed stuttered. */
    const val LOW_FRAME_RATIO = 0.85

    /** A session that dropped or skipped more than this share of what it was fed stuttered. */
    const val LOST_FRAME_RATIO = 0.05

    /** A dismissed suggestion stays quiet for this many new sessions. */
    const val DISMISS_SESSIONS = 3

    enum class Action {
        CODEC_H264,
        RENDERER_SURFACE,
        FPS_30,
        RESOLUTION_720,
    }

    data class State(
        val sessions: List<ProjectionSessionSummary>,
        val totalSessions: Int,
        val dismissedAction: Action?,
        val dismissedAtSession: Int,
        val storedCodec: String,
        val storedViewMode: Settings.ViewMode,
        val storedResolution: Settings.Resolution,
        val storedFps: Int,
        val panelCeiling: Settings.Resolution,
    )

    fun evaluate(state: State): Action? {
        val window = state.sessions.takeLast(WINDOW)
        if (window.isEmpty()) return null

        val codecFailures = window.count { it.codecFallback }
        val rendererProblems = window.count { it.rendererRecoveries > 0 || it.forcedSurface }
        val stutters = window.count { stuttered(it) }

        val action = when {
            codecFailures >= MIN_SESSIONS && !state.storedCodec.contains("264") ->
                Action.CODEC_H264
            rendererProblems >= MIN_SESSIONS &&
                state.storedViewMode != Settings.ViewMode.SURFACE ->
                Action.RENDERER_SURFACE
            stutters >= MIN_SESSIONS && state.storedFps > 30 ->
                Action.FPS_30
            stutters >= MIN_SESSIONS && resolutionCanDrop(state.storedResolution, state.panelCeiling) ->
                Action.RESOLUTION_720
            else -> null
        } ?: return null

        val dismissedRecently = state.dismissedAction == action &&
            state.totalSessions - state.dismissedAtSession < DISMISS_SESSIONS
        return if (dismissedRecently) null else action
    }

    private fun stuttered(session: ProjectionSessionSummary): Boolean {
        if (session.framesFed < MIN_FED_FRAMES) return false
        val fed = session.framesFed.toDouble()
        val renderedLow = session.framesRendered.toDouble() < fed * LOW_FRAME_RATIO
        val dropped = session.framesDropped.toDouble() > fed * LOST_FRAME_RATIO
        val skipped = session.framesSkipped.toDouble() > fed * LOST_FRAME_RATIO
        return renderedLow || dropped || skipped
    }

    private fun resolutionCanDrop(
        resolution: Settings.Resolution,
        panelCeiling: Settings.Resolution,
    ): Boolean = panelCeiling.width > Settings.Resolution._1280x720.width &&
        (resolution == Settings.Resolution.AUTO ||
            resolution.width > Settings.Resolution._1280x720.width)
}
