package com.andrerinas.openheadunit.utils

/**
 * One finished projection, reduced to what the home screen needs to spot a repeated problem.
 *
 * Only head-unit faults are recorded: a codec that had to fall back, a renderer that stalled, and
 * frames the decoder dropped or skipped while it was being fed. A link that goes quiet stops both
 * the fed and the rendered counts, so it does not look like a slow decoder.
 */
data class ProjectionSessionSummary(
    val endedAtMs: Long,
    val codec: String,
    val viewMode: Settings.ViewMode,
    val resolutionId: Int,
    val fpsLimit: Int,
    val codecFallback: Boolean,
    val rendererRecoveries: Int,
    val forcedSurface: Boolean,
    val framesFed: Long,
    val framesDropped: Long,
    val framesSkipped: Long,
    val framesRendered: Long,
)

/** The on-disk form of the last few summaries. Pure so the trimming and parsing can be tested. */
object ProjectionHealthCodec {

    private const val FIELDS = 12

    fun encode(sessions: List<ProjectionSessionSummary>): String =
        sessions.joinToString("\n") { session ->
            listOf(
                session.endedAtMs,
                session.codec,
                session.viewMode.name,
                session.resolutionId,
                session.fpsLimit,
                if (session.codecFallback) 1 else 0,
                session.rendererRecoveries,
                if (session.forcedSurface) 1 else 0,
                session.framesFed,
                session.framesDropped,
                session.framesSkipped,
                session.framesRendered,
            ).joinToString("|")
        }

    fun decode(raw: String): List<ProjectionSessionSummary> =
        raw.lineSequence().mapNotNull { parse(it) }.toList()

    private fun parse(line: String): ProjectionSessionSummary? {
        val fields = line.split("|")
        if (fields.size != FIELDS) return null
        return runCatching {
            ProjectionSessionSummary(
                endedAtMs = fields[0].toLong(),
                codec = fields[1],
                viewMode = Settings.ViewMode.values().first { it.name == fields[2] },
                resolutionId = fields[3].toInt(),
                fpsLimit = fields[4].toInt(),
                codecFallback = fields[5] == "1",
                rendererRecoveries = fields[6].toInt(),
                forcedSurface = fields[7] == "1",
                framesFed = fields[8].toLong(),
                framesDropped = fields[9].toLong(),
                framesSkipped = fields[10].toLong(),
                framesRendered = fields[11].toLong(),
            )
        }.getOrNull()
    }
}
