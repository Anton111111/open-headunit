package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import org.junit.Assert.assertEquals
import org.junit.Test

class HctWakePolicyTest {
    private val phone = "00:11:22:33:44:55"
    private val disconnected = HctWakePolicy.Snapshot(0, 0)
    private val other = 0xaabbccddeeffL

    private fun decide(
        target: String? = phone,
        paired: Boolean? = true,
        before: HctWakePolicy.Snapshot? = disconnected,
        after: HctWakePolicy.Snapshot? = disconnected,
        generation: Boolean = true,
        reconnect: Boolean = false
    ) = HctWakePolicy.decide(target, paired, before, after, generation, reconnect)

    @Test fun `only explicit verified paired target may wake`() {
        assertEquals(HctWakePolicy.Result.REQUESTED, decide())
        assertEquals(HctWakePolicy.Result.NO_TARGET, decide(target = null))
        assertEquals(HctWakePolicy.Result.NO_TARGET, decide(target = "020000000000"))
        assertEquals(HctWakePolicy.Result.NOT_PAIRED, decide(paired = false))
        assertEquals(HctWakePolicy.Result.NOT_PAIRED, decide(paired = null))
    }

    @Test fun `background wake never reconnects an existing HFP connection`() {
        for (peer in listOf(0x001122334455L, other)) {
            val connected = HctWakePolicy.Snapshot(1, peer)
            assertEquals(HctWakePolicy.Result.HFP_ALREADY_CONNECTED, decide(before = connected, after = connected))
            assertEquals(HctWakePolicy.Result.HFP_ALREADY_CONNECTED, decide(after = connected))
            assertEquals(HctWakePolicy.Result.HFP_ALREADY_CONNECTED, decide(before = connected))
        }
    }

    @Test fun `explicit selection can reconnect the same or another idle phone`() {
        for (peer in listOf(0x001122334455L, other)) {
            val connected = HctWakePolicy.Snapshot(1, peer)
            assertEquals(HctWakePolicy.Result.REQUESTED,
                decide(before = connected, after = connected, reconnect = true))
        }
        assertEquals(HctWakePolicy.Result.REQUESTED, decide(reconnect = true))
    }

    @Test fun `reconnect still requires stable state valid peer pairing and generation`() {
        val connected = HctWakePolicy.Snapshot(1, 0x001122334455L)
        assertEquals(HctWakePolicy.Result.STATE_CHANGED,
            decide(before = connected, after = connected.copy(hfp = 0), reconnect = true))
        assertEquals(HctWakePolicy.Result.STATE_CHANGED,
            decide(before = connected.copy(hfp = 0), after = connected, reconnect = true))
        assertEquals(HctWakePolicy.Result.STATE_CHANGED,
            decide(before = connected, after = connected.copy(peer = other), reconnect = true))
        assertEquals(HctWakePolicy.Result.STATE_CHANGED,
            decide(before = connected, after = connected, generation = false, reconnect = true))
        for (peer in listOf(0L, 0xffffffffffffL, 0x020000000000L)) {
            val invalid = HctWakePolicy.Snapshot(1, peer)
            assertEquals(HctWakePolicy.Result.UNKNOWN_STATE,
                decide(before = invalid, after = invalid, reconnect = true))
        }
        for (paired in listOf(false, null)) {
            assertEquals(HctWakePolicy.Result.NOT_PAIRED,
                decide(paired = paired, before = connected, after = connected, reconnect = true))
        }
        assertEquals(HctWakePolicy.Result.UNKNOWN_STATE, decide(before = null, reconnect = true))
        assertEquals(HctWakePolicy.Result.UNKNOWN_STATE,
            decide(after = HctWakePolicy.Snapshot(6, other), reconnect = true))
    }

    @Test fun `dialing ringing or calls cannot be interrupted by wake`() {
        for (state in 2..5) {
            val busy = HctWakePolicy.Snapshot(state, other)
            assertEquals(HctWakePolicy.Result.HFP_BUSY, decide(before = busy))
            assertEquals(HctWakePolicy.Result.HFP_BUSY, decide(after = busy))
            assertEquals(HctWakePolicy.Result.HFP_BUSY, decide(before = busy, reconnect = true))
            assertEquals(HctWakePolicy.Result.HFP_BUSY, decide(after = busy, reconnect = true))
            assertEquals(HctWakePolicy.Result.HFP_BUSY, decide(before = busy, after = busy, reconnect = true))
        }
    }

    @Test fun `unreadable invalid or changed state never becomes disconnected`() {
        assertEquals(HctWakePolicy.Result.UNKNOWN_STATE, decide(before = null))
        assertEquals(HctWakePolicy.Result.UNKNOWN_STATE, decide(after = null))
        for (state in listOf(-1, 6, 255)) {
            assertEquals(HctWakePolicy.Result.UNKNOWN_STATE, decide(after = HctWakePolicy.Snapshot(state, 0)))
        }
        assertEquals(HctWakePolicy.Result.UNKNOWN_STATE, decide(after = HctWakePolicy.Snapshot(0, -1)))
        assertEquals(HctWakePolicy.Result.STATE_CHANGED, decide(after = HctWakePolicy.Snapshot(0, other)))
        assertEquals(HctWakePolicy.Result.STATE_CHANGED, decide(generation = false))
    }

    @Test fun `a stable stale address is not treated as an active HFP link`() {
        val old = HctWakePolicy.Snapshot(0, other)
        assertEquals(HctWakePolicy.Result.REQUESTED, decide(before = old, after = old))
    }
}
