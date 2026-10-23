package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

/**
 * Process-local record of OEM work already submitted. Closing a launcher, a Binder proxy
 * or goc_rfcom does not cancel native SDP/HFP work. Keep that uncertainty across carrier
 * recreation; do not turn a local restart into another settingsaccess=5 command.
 *
 * This holds no Android objects and is deliberately not persisted: after process death
 * there is no live operation identity to reconcile with subsequent callbacks.
 */
internal class HctPendingReconnect {
    data class Pending(val ticket: HctWakeAttempts.Ticket, val submittedAt: Long)
    private var serial = 0L
    private var pending: Pending? = null

    @Synchronized fun ticket(target: String) = HctWakeAttempts.Ticket(++serial, target)
    @Synchronized fun current(): Pending? = pending

    @Synchronized fun submit(ticket: HctWakeAttempts.Ticket, now: Long): Boolean {
        if (pending != null) return false
        pending = Pending(ticket, now)
        return true
    }

    @Synchronized fun accepted(expected: Pending?, target: String) {
        if (expected != null && pending == expected && expected.ticket.target == target) pending = null
    }

    companion object {
        val process = HctPendingReconnect()
    }
}
