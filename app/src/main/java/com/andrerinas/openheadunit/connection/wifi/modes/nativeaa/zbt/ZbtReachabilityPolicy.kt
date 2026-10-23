package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

/**
 * Whether an established external-module route should proceed after one dial.
 *
 * These outcomes preserve the existing retry policy. They are not hardware identity, ownership,
 * or Android Auto success: an unconfirmed attempt can have several causes.
 */
object ZbtReachabilityPolicy {

    enum class Verdict {
        /** A framed message arrived; this alone does not validate the phone or WPP exchange. */
        ANSWERED,

        /** Legacy name: also includes unconfirmed connect failures, not just an accepted silent socket. */
        LISTENING_SILENT,

        /** A ConnectException on this attempt; it does not prove that no daemon is installed. */
        NOTHING_LISTENING
    }

    /** Preserve the existing classification of ConnectException as a refusal for retry policy. */
    private const val REFUSAL = "ConnectException"

    /**
     * @param connectFailure the simple name of what `connect()` threw, or null if it succeeded
     * @param answered whether a frame came back within the hello budget
     */
    fun classify(connectFailure: String?, answered: Boolean): Verdict = when {
        connectFailure == REFUSAL -> Verdict.NOTHING_LISTENING
        // Other failures remain unconfirmed. Do not infer a listener or competing owner from a
        // timeout. Keep the established route available for its real carrier to try.
        connectFailure != null -> Verdict.LISTENING_SILENT
        answered -> Verdict.ANSWERED
        else -> Verdict.LISTENING_SILENT
    }

    /**
     * Whether the established module route is worth attempting. Only the refusal outcome rules
     * it out for this attempt; [ZbtAaCarrier] can retry an unconfirmed opening.
     */
    fun reachable(verdict: Verdict): Boolean = verdict != Verdict.NOTHING_LISTENING

    /** How long a bring-up keeps re-dialling a refusing daemon before ruling the route out. */
    const val REFUSAL_WINDOW_MS = 30_000L

    private val REDIAL_GAPS_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L)

    /**
     * The wait before the next dial after [refusalsSoFar] refusals, [sinceFirstRefusalMs] after
     * the first, clamped to what is left of the window. Null once the window is spent.
     */
    fun redialAfterMs(refusalsSoFar: Int, sinceFirstRefusalMs: Long): Long? {
        val left = REFUSAL_WINDOW_MS - sinceFirstRefusalMs
        if (left <= 0L) return null
        val gap = REDIAL_GAPS_MS[(refusalsSoFar - 1).coerceIn(0, REDIAL_GAPS_MS.size - 1)]
        return minOf(gap, left)
    }
}
