package com.andrerinas.openheadunit.decoder.video

/**
 * A repair requires both accepted keyframe input and successfully released picture output.
 * Either can finish first: queueInputBuffer may still be returning when output is rendered.
 * Reserve before entering the codec, but never hold this monitor across a native call.
 *
 * The accepted candidate survives a failed newer submission. A successful newer submission replaces
 * it, as before. Output snapshots both candidates so a failed provisional submission cannot erase
 * proof of an older accepted picture. Tokens reject delayed releases belonging to newer candidates
 * or replacement codecs; the two reusable slots avoid per-frame/per-keyframe allocations.
 *
 * Finding a keyframe header does not prove that its picture is intact: a lost middle fragment can
 * leave the header readable while the codec produces nothing. Treating submission as recovery used
 * to clear the starvation and transport escalation clocks anyway. Rebuilding then resumed on a
 * P-frame, spending the restart budget without obtaining the keyframe that would repair the picture.
 * The original UNISOC MT50 investigation with one-in-three middle-fragment loss recorded
 * rendered=0 for over 90 seconds while those recovery paths repeatedly reset each other.
 *
 * ### Why the timestamp and not simply the next output
 *
 * MediaCodec can still hold pictures from before the keyframe. A picture released after submission
 * is not necessarily a picture produced by that keyframe. A decoder can also keep producing
 * damaged pictures after losing a reference frame, so output activity alone is not proof of repair.
 * Match each successfully released picture against the captured candidate timestamps, and confirm
 * recovery only once input acceptance is known. A later failed release must not erase earlier
 * success or lend its timestamp to an older picture.
 *
 * Candidate timestamps belong to one codec instance and restart near zero when it is rebuilt.
 * Call [reset] on replacement so the new codec cannot confirm an old candidate. Tokens also reject
 * old completions that arrive after reset; timestamp comparison alone cannot identify their owner.
 *
 * ### Components that do not preserve timestamps
 *
 * Some components return zero or a constant presentation timestamp while pictures keep arriving.
 * Waiting forever for an exact timestamp would misclassify working output as starvation and spend
 * focus cycles on a stream that does not need them. Keep the bounded
 * [RENDERS_BEFORE_TIMESTAMPS_DISBELIEVED] fallback after input acceptance, counting successful output
 * passes rather than every buffer in a catch-up batch. It still needs a successfully rendered
 * output; discarded or failed releases alone cannot confirm recovery. [timestampsUnusable]
 * records that attribution used this fallback, not that the specific keyframe was identified.
 */
class KeyframeRepairTracker {
    private class Candidate {
        var token = 0L
        var ptsUs = 0L
        var outputs = 0
        var rendered = false
        var usedFallback = false

        fun clear() { token = 0L; outputs = 0; rendered = false; usedFallback = false }
        fun copyFrom(other: Candidate) {
            token = other.token
            ptsUs = other.ptsUs
            outputs = other.outputs
            rendered = other.rendered
            usedFallback = other.usedFallback
        }
    }

    private val pending = Candidate()
    private val submitting = Candidate()
    private var nextToken = 0L
    private var decoded = false
    private var disbelievedTimestamps = false

    /** Whether an accepted keyframe has confirmed picture output since the last [reset]. */
    val keyframeDecoded: Boolean
        @Synchronized get() = decoded
    /** A submitted or accepted candidate is still awaiting recovery confirmation. Diagnostic. */
    val awaitingOutput: Boolean
        @Synchronized get() = pending.token != 0L || submitting.token != 0L
    /**
     * Whether any repair has used the output-count fallback instead of a matching timestamp.
     * Retained across reset for the once-per-component diagnostic; it does not bypass timestamp
     * checks for later candidates.
     */
    val timestampsUnusable: Boolean
        @Synchronized get() = disbelievedTimestamps

    /** One feed worker reserves before queueInputBuffer, then commits or cancels this exact token. */
    @Synchronized
    fun beginKeyframe(ptsUs: Long): Long {
        submitting.clear()
        submitting.token = ++nextToken
        submitting.ptsUs = ptsUs
        return submitting.token
    }

    @Synchronized
    fun commitKeyframe(token: Long) {
        if (token == 0L || submitting.token != token) return
        pending.copyFrom(submitting)
        submitting.clear()
    }

    @Synchronized
    fun cancelKeyframe(token: Long) {
        if (token != 0L && submitting.token == token) submitting.clear()
    }

    /** Snapshot both tokens alongside the output PTS, under the caller's publication lock. */
    @Synchronized
    fun outputToken(): Long = if (submitting.token != 0L) submitting.token else pending.token

    @Synchronized
    fun acceptedOutputToken(): Long = pending.token

    private fun candidate(token: Long): Candidate? = when {
        token == 0L -> null
        token == submitting.token -> submitting
        token == pending.token -> pending
        else -> null
    }

    /** A preview only. A provisional timestamp match may be shown, but does not confirm acceptance. */
    @Synchronized
    fun canRepair(token: Long, acceptedToken: Long, ptsUs: Long, countOutput: Boolean): Boolean =
        canRepairCandidate(token, ptsUs, countOutput) ||
            (acceptedToken != token && canRepairCandidate(acceptedToken, ptsUs, countOutput))

    private fun canRepairCandidate(token: Long, ptsUs: Long, countOutput: Boolean): Boolean {
        val c = candidate(token) ?: return false
        return ptsUs >= c.ptsUs ||
            (c === pending && c.outputs + (if (countOutput) 1 else 0) >= RENDERS_BEFORE_TIMESTAMPS_DISBELIEVED)
    }

    /** Record each successful release, advancing fallback once per pass, not once per buffer. */
    @Synchronized
    fun onOutputReleased(token: Long, acceptedToken: Long, ptsUs: Long, rendered: Boolean, countOutput: Boolean) {
        recordOutput(token, ptsUs, rendered, countOutput)
        if (acceptedToken != token) recordOutput(acceptedToken, ptsUs, rendered, countOutput)
    }

    private fun recordOutput(token: Long, ptsUs: Long, rendered: Boolean, countOutput: Boolean) {
        val c = candidate(token) ?: return
        // Old outputs cannot count toward the bad-timestamp fallback before queue acceptance.
        if (countOutput && c === pending && c.outputs < RENDERS_BEFORE_TIMESTAMPS_DISBELIEVED) c.outputs++
        val timestampMatch = ptsUs >= c.ptsUs
        val fallback = c === pending && c.outputs >= RENDERS_BEFORE_TIMESTAMPS_DISBELIEVED
        if (rendered && (timestampMatch || fallback)) {
            c.rendered = true
            c.usedFallback = !timestampMatch
        }
    }

    /**
     * Consume on output passes AND idle ticks: an early render may await only queue completion.
     * Nothing is confirmed while a newer submission's acceptance remains unresolved.
     */
    @Synchronized
    fun consumeRepair(): Boolean {
        if (submitting.token != 0L || pending.token == 0L || !pending.rendered) return false
        decoded = true
        if (pending.usedFallback) disbelievedTimestamps = true
        pending.clear()
        return true
    }

    /** Retire codec-local candidates and repair state, retaining token uniqueness and diagnostics. */
    @Synchronized
    fun reset() {
        pending.clear()
        submitting.clear()
        decoded = false
        // Tokens are never reused; delayed completions cannot match the next codec's candidate.
        // Retain the diagnostic that this component has produced unusable timestamps.
    }

    companion object {
        // This counts successful output passes, not every buffer in a catch-up batch. It preserves
        // the bounded fallback for components that return zero/constant presentation timestamps.
        const val RENDERS_BEFORE_TIMESTAMPS_DISBELIEVED = 30
    }
}
