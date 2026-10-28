package com.andrerinas.openheadunit.decoder.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeRepairTrackerTest {
    private val tracker = KeyframeRepairTracker()

    private fun accepted(pts: Long): Long = tracker.beginKeyframe(pts).also(tracker::commitKeyframe)
    private fun released(pts: Long, rendered: Boolean = true): Boolean {
        tracker.onOutputReleased(tracker.outputToken(), tracker.acceptedOutputToken(), pts, rendered, countOutput = true)
        return tracker.consumeRepair()
    }

    @Test fun `accepted input alone does not repair the picture`() {
        accepted(1_000)
        assertTrue(tracker.awaitingOutput)
        assertFalse(tracker.keyframeDecoded)
        assertFalse(tracker.consumeRepair())
    }

    @Test fun `matching successfully rendered output confirms exactly once`() {
        accepted(1_000)
        assertTrue(released(1_000))
        assertTrue(tracker.keyframeDecoded)
        assertFalse(tracker.awaitingOutput)
        assertFalse(released(1_016))
    }

    @Test fun `old queued output cannot confirm a newer keyframe`() {
        accepted(5_000)
        assertFalse(released(4_800))
        assertFalse(released(4_950))
        assertFalse(tracker.keyframeDecoded)
        assertTrue(released(5_000))
    }

    @Test fun `output without a candidate is not a repair`() {
        assertFalse(released(2_000))
        assertFalse(tracker.keyframeDecoded)
    }

    @Test fun `accepted newer keyframe supersedes older candidate`() {
        val older = accepted(1_000)
        accepted(70_000)
        tracker.onOutputReleased(older, older, 70_000, true, countOutput = true)
        assertFalse(tracker.consumeRepair())
        assertFalse(released(1_500))
        assertTrue(released(70_000))
    }

    @Test fun `preview and discarded output cannot consume repair`() {
        val token = accepted(1_000)
        repeat(3) { assertTrue(tracker.canRepair(token, token, 1_000, countOutput = true)) }
        assertFalse(tracker.keyframeDecoded)
        assertFalse(released(1_000, rendered = false))
        assertTrue(tracker.awaitingOutput)
        assertTrue(released(1_000))
    }

    @Test fun `early matching output waits for input acceptance even without another frame`() {
        val token = tracker.beginKeyframe(1_000)
        assertTrue(tracker.canRepair(token, 0L, 1_000, countOutput = true))
        assertFalse(released(1_000))
        assertFalse(tracker.keyframeDecoded)
        tracker.commitKeyframe(token)
        assertTrue(tracker.consumeRepair())
        assertFalse(tracker.consumeRepair())
    }

    @Test fun `acceptance during native release preserves the reserved token`() {
        val token = tracker.beginKeyframe(1_000)
        assertTrue(tracker.canRepair(token, 0L, 1_000, countOutput = true))
        tracker.commitKeyframe(token)
        tracker.onOutputReleased(token, 0L, 1_000, true, countOutput = true)
        assertTrue(tracker.consumeRepair())
    }

    @Test fun `failed queue cannot be repaired by its early output`() {
        val token = tracker.beginKeyframe(1_000)
        assertFalse(released(1_000))
        tracker.cancelKeyframe(token)
        assertFalse(tracker.consumeRepair())
        assertFalse(tracker.keyframeDecoded)
        assertFalse(tracker.awaitingOutput)
    }

    @Test fun `failed newer submission preserves earlier accepted repair proof`() {
        accepted(1_000)
        val newer = tracker.beginKeyframe(2_000)
        assertTrue(tracker.canRepair(tracker.outputToken(), tracker.acceptedOutputToken(), 1_000, countOutput = true))
        assertFalse(released(1_000))
        tracker.cancelKeyframe(newer)
        assertTrue(tracker.consumeRepair())
    }

    @Test fun `cancellation during release preserves the captured accepted candidate`() {
        accepted(1_000)
        val newer = tracker.beginKeyframe(2_000)
        val primary = tracker.outputToken()
        val accepted = tracker.acceptedOutputToken()
        tracker.cancelKeyframe(newer)
        tracker.onOutputReleased(primary, accepted, 1_000, true, countOutput = true)
        assertTrue(tracker.consumeRepair())
    }

    @Test fun `zero timestamps have a bounded fallback that can leave concealment`() {
        val token = accepted(500_000)
        val bound = KeyframeRepairTracker.RENDERS_BEFORE_TIMESTAMPS_DISBELIEVED
        repeat(bound - 1) {
            assertFalse(tracker.canRepair(token, token, 0, countOutput = true))
            assertFalse(released(0, rendered = false))
        }
        // Preview must permit the threshold output to be shown, without consuming its count.
        repeat(3) { assertTrue(tracker.canRepair(token, token, 0, countOutput = true)) }
        assertFalse(tracker.consumeRepair())
        assertFalse(tracker.timestampsUnusable)
        assertTrue(released(0))
        assertTrue(tracker.timestampsUnusable)
    }

    @Test fun `multiple releases in one pass do not accelerate the timestamp fallback`() {
        val token = accepted(500_000)
        repeat(KeyframeRepairTracker.RENDERS_BEFORE_TIMESTAMPS_DISBELIEVED - 1) {
            assertFalse(released(0, rendered = false))
            assertFalse(tracker.canRepair(token, token, 0, countOutput = false))
            tracker.onOutputReleased(token, token, 0, true, countOutput = false)
            assertFalse(tracker.consumeRepair())
        }
        // At the threshold, a discarded slot advances the pass count and a later rendered
        // slot in that same pass supplies the picture proof.
        assertFalse(released(0, rendered = false))
        assertTrue(tracker.canRepair(token, token, 0, countOutput = false))
        tracker.onOutputReleased(token, token, 0, true, countOutput = false)
        assertTrue(tracker.consumeRepair())
        assertTrue(tracker.timestampsUnusable)
        assertFalse(tracker.consumeRepair())
    }

    @Test fun `provisional output cannot advance the bad timestamp fallback`() {
        val token = tracker.beginKeyframe(500_000)
        repeat(KeyframeRepairTracker.RENDERS_BEFORE_TIMESTAMPS_DISBELIEVED * 2) {
            assertFalse(tracker.canRepair(token, 0L, 0, countOutput = true))
            assertFalse(released(0))
        }
        tracker.commitKeyframe(token)
        assertFalse(tracker.canRepair(token, 0L, 0, countOutput = true))
        assertFalse(tracker.consumeRepair())
        assertFalse(tracker.timestampsUnusable)
        assertTrue(released(500_000))
    }

    @Test fun `stale frame count does not carry over to a newly accepted keyframe`() {
        accepted(1_000)
        repeat(KeyframeRepairTracker.RENDERS_BEFORE_TIMESTAMPS_DISBELIEVED - 1) { released(0) }
        accepted(2_000)
        assertFalse(released(0))
        assertTrue(released(2_000))
        assertFalse(tracker.timestampsUnusable)
    }

    @Test fun `reset rejects late input and output completions despite reused timestamps`() {
        val old = tracker.beginKeyframe(1_000)
        tracker.reset()
        val replacement = tracker.beginKeyframe(1_000)
        tracker.commitKeyframe(old)
        tracker.cancelKeyframe(old)
        tracker.onOutputReleased(old, old, 1_000, true, countOutput = true)
        assertFalse(tracker.keyframeDecoded)
        assertFalse(tracker.consumeRepair())
        tracker.commitKeyframe(replacement)
        assertFalse(tracker.consumeRepair())
        assertTrue(released(1_000))
        tracker.reset()
        assertFalse(tracker.keyframeDecoded)
        assertFalse(tracker.awaitingOutput)
    }
}
